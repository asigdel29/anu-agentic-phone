//! cache.rs — remembering routing decisions.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `CAPACITY`       How many entries are kept.
//!   `DecisionCache`  Prompt-hash and session lookups over one store.
//!
//! Two lookups, one store. A repeated prompt is not re-embedded, and a follow-up
//! turn reuses its session's tier rather than being scored again. The second is
//! the larger win by far: scoring is the only expensive step on the request path,
//! and most turns in a conversation are follow-ups.
//!
//! A session's tier may rise but never fall. A conversation that silently gets
//! worse partway through is a worse outcome than one that is uniformly cheap, and
//! the tier the work eventually needed is the tier the whole conversation should
//! have had. [`Tier`] derives `Ord` for exactly this.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::tier::Tier;

/// Entries kept per lookup kind before the oldest is evicted.
///
/// A few hundred sessions and prompts is far more than one board sees at once,
/// and the whole structure is a few tens of kilobytes. The cap exists to bound a
/// long-running process, not to ration anything.
pub const CAPACITY: usize = 512;

/// Insertion-ordered map with a capacity bound.
///
/// Written rather than pulled in: the router needs one eviction rule over a few
/// hundred entries, and the dependency would be larger than the code.
#[derive(Debug)]
struct Bounded<V> {
    entries: HashMap<String, (u64, V)>,
    /// Monotonic counter standing in for a clock. A clock would be the obvious
    /// choice and the wrong one — it makes tests wait, and ordering is all that
    /// eviction needs.
    tick: u64,
}

impl<V> Bounded<V> {
    fn new() -> Self {
        Self {
            entries: HashMap::new(),
            tick: 0,
        }
    }

    fn get(&self, key: &str) -> Option<&V> {
        self.entries.get(key).map(|(_, value)| value)
    }

    fn put(&mut self, key: String, value: V) {
        self.tick += 1;
        let tick = self.tick;
        self.entries.insert(key, (tick, value));

        if self.entries.len() > CAPACITY {
            // Evict the least recently written. Scanning is fine at this size and
            // avoids carrying a second index that could disagree with this one.
            if let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, (tick, _))| *tick)
                .map(|(key, _)| key.clone())
            {
                self.entries.remove(&oldest);
            }
        }
    }
}

/// Routing decisions, remembered.
///
/// # Atomic
/// Every method takes one lock for its whole body, so a concurrent reader never
/// observes a half-applied update. The lock is uncontended in practice: it is
/// held for a hash lookup, which is orders of magnitude shorter than the upstream
/// call each request then makes.
#[derive(Debug)]
pub struct DecisionCache {
    inner: Mutex<Inner>,
}

#[derive(Debug)]
struct Inner {
    by_prompt: Bounded<f32>,
    by_session: Bounded<Tier>,
}

impl DecisionCache {
    /// An empty cache.
    #[must_use]
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Inner {
                by_prompt: Bounded::new(),
                by_session: Bounded::new(),
            }),
        }
    }

    /// The score previously computed for this exact prompt.
    ///
    /// # Returns
    /// `Some(score)` IF the identical text was scored recently.
    ///
    /// # Rely
    /// Called on the request path, before embedding.
    ///
    /// # Atomic
    /// One lock acquisition; sees a consistent snapshot.
    #[must_use]
    pub fn score_for(&self, prompt: &str) -> Option<f32> {
        let inner = self.lock();
        inner.by_prompt.get(&key_of(prompt)).copied()
    }

    /// Remember a score for this prompt.
    ///
    /// # Atomic
    /// One lock acquisition.
    pub fn remember_score(&self, prompt: &str, score: f32) {
        let mut inner = self.lock();
        inner.by_prompt.put(key_of(prompt), score);
    }

    /// The tier this session has settled on.
    ///
    /// # Rely
    /// Called on the request path, before scoring. A hit is what lets a follow-up
    /// turn skip the only expensive step.
    ///
    /// # Atomic
    /// One lock acquisition.
    #[must_use]
    pub fn tier_for(&self, session: &str) -> Option<Tier> {
        if session.is_empty() {
            return None;
        }
        let inner = self.lock();
        inner.by_session.get(session).copied()
    }

    /// Record a tier for this session, keeping the higher of the two.
    ///
    /// # Returns
    /// The tier now in effect, which is `max(existing, tier)`. A session that
    /// once needed the heavy tier keeps it: the work has proven itself hard, and
    /// dropping back would make the conversation get worse midway through.
    ///
    /// # Atomic
    /// Reads and writes under one lock, so two concurrent turns cannot both read
    /// the old tier and each write their own — the result is the maximum of
    /// everything recorded, whatever the interleaving.
    pub fn escalate(&self, session: &str, tier: Tier) -> Tier {
        if session.is_empty() {
            return tier;
        }
        let mut inner = self.lock();
        let effective = inner
            .by_session
            .get(session)
            .copied()
            .map_or(tier, |existing| existing.max(tier));
        inner.by_session.put(session.to_owned(), effective);
        effective
    }

    /// Take the lock, recovering from a poisoned one.
    ///
    /// A panic while holding this lock would leave the cache poisoned, and every
    /// later request would fail. The data is a cache — worst case it holds a
    /// stale tier — so continuing with it beats refusing to serve.
    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

impl Default for DecisionCache {
    fn default() -> Self {
        Self::new()
    }
}

/// A stable key for a prompt.
///
/// FNV-1a over the text. Not cryptographic and does not need to be: a collision
/// costs one request the wrong cached score, which the escalation rule can still
/// correct, and prompts here are already truncated to a bounded length.
fn key_of(prompt: &str) -> String {
    const OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
    const PRIME: u64 = 0x0000_0100_0000_01b3;

    let mut h = OFFSET;
    for byte in prompt.as_bytes() {
        h ^= u64::from(*byte);
        h = h.wrapping_mul(PRIME);
    }
    format!("{h:016x}")
}

#[cfg(test)]
mod tests {
    use super::{CAPACITY, DecisionCache};
    use crate::tier::Tier;

    #[test]
    fn a_score_survives_until_it_is_asked_for() {
        let cache = DecisionCache::new();
        assert!(cache.score_for("a prompt").is_none());
        cache.remember_score("a prompt", 0.75);
        assert_eq!(cache.score_for("a prompt"), Some(0.75));
        assert!(cache.score_for("a different prompt").is_none());
    }

    #[test]
    fn a_session_tier_rises_but_never_falls() {
        // The rule the whole module exists to enforce. A conversation that
        // silently gets worse partway through is worse than a uniformly cheap
        // one, so once the work has proven itself hard the tier stays.
        let cache = DecisionCache::new();
        assert_eq!(cache.escalate("s1", Tier::Cheap), Tier::Cheap);
        assert_eq!(cache.escalate("s1", Tier::Heavy), Tier::Heavy);
        assert_eq!(cache.escalate("s1", Tier::Cheap), Tier::Heavy);
        assert_eq!(cache.tier_for("s1"), Some(Tier::Heavy));
    }

    #[test]
    fn sessions_do_not_see_one_another() {
        let cache = DecisionCache::new();
        cache.escalate("s1", Tier::Heavy);
        cache.escalate("s2", Tier::Cheap);
        assert_eq!(cache.tier_for("s1"), Some(Tier::Heavy));
        assert_eq!(cache.tier_for("s2"), Some(Tier::Cheap));
    }

    #[test]
    fn an_absent_session_id_is_not_cached() {
        // Requests without a session are independent. Keying them all on the
        // empty string would let one conversation escalate every other one.
        let cache = DecisionCache::new();
        assert_eq!(cache.escalate("", Tier::Heavy), Tier::Heavy);
        assert!(cache.tier_for("").is_none());
    }

    #[test]
    fn the_cache_stays_bounded() {
        // A long-running process must not grow without limit.
        let cache = DecisionCache::new();
        for i in 0..(CAPACITY * 2) {
            cache.remember_score(&format!("prompt {i}"), 0.5);
        }
        let inner = cache.lock();
        assert!(inner.by_prompt.entries.len() <= CAPACITY + 1);
    }

    #[test]
    fn the_oldest_entry_is_the_one_evicted() {
        let cache = DecisionCache::new();
        cache.remember_score("first", 0.1);
        for i in 0..CAPACITY {
            cache.remember_score(&format!("filler {i}"), 0.5);
        }
        assert!(cache.score_for("first").is_none(), "oldest should be gone");
        assert_eq!(
            cache.score_for(&format!("filler {}", CAPACITY - 1)),
            Some(0.5)
        );
    }

    #[test]
    fn concurrent_escalation_settles_on_the_maximum() {
        // Two turns racing must not lose the higher tier. Read-then-write outside
        // one lock could have both read Cheap and the later write win.
        let cache = std::sync::Arc::new(DecisionCache::new());
        let mut handles = Vec::new();
        for tier in [Tier::Cheap, Tier::Heavy, Tier::Mid, Tier::Aux] {
            let cache = std::sync::Arc::clone(&cache);
            handles.push(std::thread::spawn(move || {
                for _ in 0..200 {
                    cache.escalate("shared", tier);
                }
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(cache.tier_for("shared"), Some(Tier::Heavy));
    }
}
