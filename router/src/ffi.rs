//! ffi.rs — the C ABI the iOS app calls the decision core through.
//!
//! History
//!   2026-08-06  A. Sigdel  Created.
//!
//! Contents
//!   `Router`, `Decision`, and the `wattrouter_*` entry points.
//!
//! On a board this crate is an HTTP proxy, because the agent and the coding
//! harness are separate processes. In an app there is one address space, so the
//! proxy is the wrong shape: no socket, no TLS, no connection pool.
//!
//! One entry point, not one per stage: exposing `classify`, `score` and `decide`
//! separately would move their ordering into the caller, where it would drift
//! from the server's.
//!
//! No panic may cross this boundary — that is undefined behaviour. Every entry
//! point catches and reports failure as a value.

use crate::backend::Backend;
use crate::cache::DecisionCache;
use crate::chain::chain_for;
use crate::classify::classify;
use crate::config::Config;
use crate::embed::{Embedder, HashEmbedder};
use crate::head::Head;
use crate::policy::{Reason, Thresholds, decide};
use crate::tier::Tier;
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};

/// Everything a decision needs; opaque to C.
pub struct Router {
    /// Every tier's chain, resolved once at construction.
    ///
    /// A decision names a tier, which is a role; what answers is the chain
    /// behind it. Resolved here rather than per call for two reasons: a caller
    /// can borrow a name that outlives the call without owning or freeing it,
    /// and the request path allocates nothing. Six tiers of at most three
    /// entries, built once.
    chains: [Vec<ChainEntry>; Tier::ALL.len()],
    thresholds: Thresholds,
    embedder: HashEmbedder,
    cache: DecisionCache,
    head: Option<Head>,
}

/// One attempt: a model name C can hold, and where to run it.
///
/// [`crate::chain::Step`] borrows its name from configuration and carries a
/// Rust enum; this owns a NUL-terminated copy and the wire code, which are what
/// crosses.
struct ChainEntry {
    backend: u8,
    model: CString,
}

/// A decision, by value so the caller frees nothing.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct Decision {
    /// Tier discriminant; `255` IF the call failed.
    pub tier: u8,
    /// Reason code; meaningless when `tier` is `255`.
    pub reason: u8,
    /// Difficulty score, or `-1.0` IF unscored.
    pub score: f32,
}

impl Decision {
    /// Returned when a call could not decide: one field to check, nothing to free.
    const FAILED: Self = Self {
        tier: u8::MAX,
        reason: u8::MAX,
        score: -1.0,
    };
}

/// A stable wire code, written out rather than taken from the discriminant (as
/// `metrics::reason_index` is): reordering the enum must not change what a
/// number means to a caller compiled against the old order.
const fn reason_code(reason: Reason) -> u8 {
    match reason {
        Reason::Pinned => 0,
        Reason::Background => 1,
        Reason::ContextTooLarge => 2,
        Reason::Scored => 3,
        Reason::CodeShaped => 4,
        Reason::Unscored => 5,
        Reason::Sticky => 6,
    }
}

/// Borrow a C string as UTF-8; null and invalid encoding both read as absent.
///
/// # Safety
/// `ptr` must be null or a valid NUL-terminated string outliving the call.
unsafe fn borrow<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(ptr) }.to_str().ok()
}

/// Build a router, configured from the environment as the server is — one way to
/// configure the core rather than two that can disagree.
///
/// # Returns
/// A pointer the caller passes to [`wattrouter_free`], or null IF configuration
/// was rejected.
///
/// # Safety
/// `head_path` must be null or a valid NUL-terminated string. The returned
/// pointer must not be used after `wattrouter_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_new(head_path: *const c_char) -> *mut Router {
    catch_unwind(|| {
        let Ok(config) = Config::from_env() else {
            return std::ptr::null_mut();
        };
        let embedder = HashEmbedder::new();

        // Explicit wins over configured: the app keeps weights in its sandbox,
        // which the configured default cannot reach.
        let path = unsafe { borrow(head_path) }.map_or_else(
            || config.head_path().to_path_buf(),
            std::path::PathBuf::from,
        );

        // A missing head is not a failure; the policy has an unscored path.
        let head = Head::load(&path, &embedder.id()).ok();
        let thresholds = head
            .as_ref()
            .and_then(Head::thresholds)
            .and_then(|(cheap, mid)| Thresholds::new(cheap, mid))
            .unwrap_or_default();

        // A model name containing a NUL cannot cross as a C string; it would
        // also be a name no provider has, so the chain simply omits it rather
        // than refusing to build a router over a value nothing will ask for.
        let chains = Tier::ALL.map(|tier| {
            chain_for(&config, tier)
                .into_iter()
                .filter_map(|step| {
                    Some(ChainEntry {
                        backend: backend_code(step.backend()),
                        model: CString::new(step.model()).ok()?,
                    })
                })
                .collect()
        });

        Box::into_raw(Box::new(Router {
            chains,
            thresholds,
            embedder,
            cache: DecisionCache::new(),
            head,
        }))
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Release a router.
///
/// # Safety
/// `router` must come from [`wattrouter_new`] and not already be freed. Null is
/// accepted and ignored.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_free(router: *mut Router) {
    if router.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| drop(unsafe { Box::from_raw(router) })));
}

/// Decide which tier serves a request: classify, score, apply policy, apply
/// session stickiness. The server's ordering, because it is the server's code.
///
/// # Arguments
/// * `body_json` — an OpenAI-shaped chat completion request.
/// * `pin` — a tier name to force, or null.
/// * `session` — a session identifier for stickiness, or null.
///
/// # Returns
/// A [`Decision`], or `tier == 255` IF `router` was null, the body was not valid
/// UTF-8 JSON, or the call panicked.
///
/// # Safety
/// Pointers must be null or valid NUL-terminated strings outliving the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_decide(
    router: *const Router,
    body_json: *const c_char,
    pin: *const c_char,
    session: *const c_char,
) -> Decision {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(router) = (unsafe { router.as_ref() }) else {
            return Decision::FAILED;
        };
        let Some(raw) = (unsafe { borrow(body_json) }) else {
            return Decision::FAILED;
        };
        let Ok(body) = serde_json::from_str::<serde_json::Value>(raw) else {
            return Decision::FAILED;
        };

        let classified = classify(&body, unsafe { borrow(pin) });
        let session = unsafe { borrow(session) }.unwrap_or_default();

        let score = router.head.as_ref().and_then(|head| {
            if classified.text.is_empty() {
                return None;
            }
            if let Some(cached) = router.cache.score_for(&classified.text) {
                return Some(cached);
            }
            let vector = router.embedder.embed(&classified.text).ok()?;
            let score = head.score(&vector);
            router.cache.remember_score(&classified.text, score);
            Some(score)
        });

        let mut decision = decide(&classified.signals, score, &router.thresholds);
        if decision.reason != Reason::Pinned {
            let effective = router.cache.escalate(session, decision.tier);
            if effective > decision.tier {
                decision = crate::policy::Decision::new(effective, Reason::Sticky);
            }
        }
        Decision {
            tier: decision.tier as u8,
            reason: reason_code(decision.reason),
            score: score.unwrap_or(-1.0),
        }
    }))
    .unwrap_or(Decision::FAILED)
}

/// A stable wire code for a backend, written out for the reason [`reason_code`]
/// is: the caller compiles against these numbers.
const fn backend_code(backend: Backend) -> u8 {
    match backend {
        Backend::Local => 0,
        Backend::Remote => 1,
    }
}

/// `tier`'s chain, or an empty slice IF the inputs name none.
///
/// # Safety
/// `router` must be null or valid; each entry point states that at its boundary.
unsafe fn chain_of<'a>(router: *const Router, tier: u8) -> &'a [ChainEntry] {
    const NONE: &[ChainEntry] = &[];
    catch_unwind(AssertUnwindSafe(|| {
        let router = unsafe { router.as_ref() };
        router
            .and_then(|router| router.chains.get(tier as usize))
            .map_or(NONE, Vec::as_slice)
    }))
    .unwrap_or(NONE)
}

/// How many models back `tier`.
///
/// # Returns
/// At most [`crate::chain::MAX_CHAIN`], or `0` IF `router` was null or `tier`
/// names no tier — so a caller may walk every index below it without checking
/// each one for absence.
///
/// # Safety
/// `router` must be null or come from [`wattrouter_new`] and not yet freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_chain_length(router: *const Router, tier: u8) -> usize {
    unsafe { chain_of(router, tier) }.len()
}

/// The `index`th model backing `tier`, in the order it will be tried.
///
/// Reached by index rather than returned as an array: a chain is at most three
/// long and the router already owns the names, so nothing is allocated to cross
/// and the caller frees nothing — the property [`Decision`] has.
///
/// # Returns
/// A NUL-terminated name borrowed from `router` and valid until it is freed, or
/// null IF `index` is past the end of the chain.
///
/// # Safety
/// `router` must be null or come from [`wattrouter_new`] and not yet freed. The
/// returned pointer must not outlive it.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_chain_model(
    router: *const Router,
    tier: u8,
    index: usize,
) -> *const c_char {
    unsafe { chain_of(router, tier) }
        .get(index)
        .map_or(std::ptr::null(), |entry| entry.model.as_ptr())
}

/// Where the `index`th model of `tier`'s chain runs: `0` local, `1` remote.
///
/// This is what separates a file to load into this process from an HTTP request,
/// which a model name alone does not say.
///
/// # Returns
/// A backend code, or `255` IF `index` is past the end of the chain.
///
/// # Safety
/// `router` must be null or come from [`wattrouter_new`] and not yet freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn wattrouter_chain_backend(
    router: *const Router,
    tier: u8,
    index: usize,
) -> u8 {
    unsafe { chain_of(router, tier) }
        .get(index)
        .map_or(u8::MAX, |entry| entry.backend)
}

/// The `index`th name, or null IF there is none.
///
/// Total: no lookup built on it can panic, so no entry point that is only a
/// lookup needs a `catch_unwind` to make one safe to cross the boundary.
fn name_at(names: &[&'static CStr], index: u8) -> *const c_char {
    names
        .get(index as usize)
        .map_or(std::ptr::null(), |name| name.as_ptr())
}

/// The name of a tier code, as configuration and metrics spell it.
///
/// A decision crosses as two numbers, which is all the caller needs to act but
/// nothing it can display or log. These return the words without the caller
/// keeping a second copy of the vocabulary that can fall behind this one.
///
/// # Arguments
/// * `tier` — a code from [`Decision`]'s `tier`.
///
/// # Returns
/// A static NUL-terminated name, borrowed for the program's lifetime and never
/// freed by the caller, or null IF `tier` names no tier — which includes the
/// failure sentinel.
#[unsafe(no_mangle)]
pub extern "C" fn wattrouter_tier_name(tier: u8) -> *const c_char {
    // Written out rather than indexed off `Tier::ALL`, for the reason
    // `reason_code` is: the caller is compiled against these codes, and
    // reordering the enum must not change what a number means to it.
    // `tier_names_match_the_tier` holds the spellings to the tier's own.
    const NAMES: [&CStr; 6] = [c"aux", c"cheap", c"mid", c"code", c"long", c"heavy"];
    name_at(&NAMES, tier)
}

/// The name of a reason code, as metrics spell it.
///
/// # Arguments
/// * `reason` — a code from [`Decision`]'s `reason`.
///
/// # Returns
/// A static NUL-terminated name, borrowed for the program's lifetime and never
/// freed by the caller, or null IF `reason` names no reason.
#[unsafe(no_mangle)]
pub extern "C" fn wattrouter_reason_name(reason: u8) -> *const c_char {
    const NAMES: [&CStr; 7] = [
        c"pinned",
        c"background",
        c"context-too-large",
        c"scored",
        c"code-shaped",
        c"unscored",
        c"sticky",
    ];
    name_at(&NAMES, reason)
}

/// The name of a backend code, as configuration spells it.
///
/// A chain crosses as a model name and a number. The name says what to ask; the
/// number says whether to ask it over a socket or load it into this process, and
/// a caller that wants to log or display that half needs the word for it. Here
/// rather than in the caller for the reason the other two are: the vocabulary
/// has one home, and a second copy is one that falls behind this one.
///
/// # Arguments
/// * `backend` — a code from [`wattrouter_chain_backend`].
///
/// # Returns
/// A static NUL-terminated name, borrowed for the program's lifetime and never
/// freed by the caller, or null IF `backend` names no backend — which includes
/// the past-the-end sentinel.
#[unsafe(no_mangle)]
pub extern "C" fn wattrouter_backend_name(backend: u8) -> *const c_char {
    // Written out rather than indexed off `Backend::ALL`, for the reason
    // `backend_code` is: the caller is compiled against these codes, and
    // reordering the enum must not change what a number means to it.
    // `backend_names_match_the_backend` holds the spellings to the backend's own.
    const NAMES: [&CStr; 2] = [c"local", c"remote"];
    name_at(&NAMES, backend)
}

#[cfg(test)]
mod tests {
    use super::{Decision, Router, reason_code};
    use crate::backend::Backend;
    use crate::policy::Reason;
    use crate::testenv::with_env;
    use crate::tier::Tier;
    use std::ffi::{CStr, CString};

    /// The header, as the app's compiler will read it.
    const HEADER: &str = include_str!("../include/wattrouter.h");

    /// The header tells a caller whether one router may be shared across
    /// threads. Nothing but this decides that claim: the cache is behind a mutex
    /// and everything else is read-only after construction. If that stops being
    /// true this stops compiling, rather than the header quietly becoming wrong.
    const _: fn() = || {
        fn assert_shareable<T: Sync + Send>() {}
        assert_shareable::<Router>();
    };

    /// Build a router as the app would, and free it as the app must.
    ///
    /// Holds the crate-wide environment lock for the whole of `body`, not just
    /// the construction. `wattrouter_new` reads the environment, and so does any
    /// `Config::from_env` a body makes to compare against — the credential
    /// vanishing between the two is what failed CI, and a stray
    /// `WATTROUTER_MODEL_*` from a concurrent test would have been worse, since
    /// that compares two different configurations and reports a wrong answer
    /// rather than a missing one.
    fn with_router<T>(body: impl FnOnce(*mut Router) -> T) -> T {
        with_env(&[("NEURALWATT_API_KEY", Some("ffi-test"))], || {
            let router = unsafe { super::wattrouter_new(std::ptr::null()) };
            assert!(!router.is_null(), "router builds without a head");
            let out = body(router);
            unsafe { super::wattrouter_free(router) };
            out
        })
    }

    fn decide(router: *mut Router, json: &str, pin: Option<&str>, sess: &str) -> Decision {
        let body = CString::new(json).unwrap();
        let pin = pin.map(|p| CString::new(p).unwrap());
        let session = CString::new(sess).unwrap();
        unsafe {
            super::wattrouter_decide(
                router,
                body.as_ptr(),
                pin.as_ref().map_or(std::ptr::null(), |p| p.as_ptr()),
                session.as_ptr(),
            )
        }
    }

    #[test]
    fn a_decision_crosses_the_boundary() {
        with_router(|router| {
            let d = decide(
                router,
                r#"{"messages":[{"role":"user","content":"hello there"}]}"#,
                None,
                "",
            );
            assert_ne!(d.tier, u8::MAX, "should have decided");
            assert_eq!(d.tier, Tier::Mid as u8, "unscored lands in the middle");
        });
    }

    #[test]
    fn the_policy_rules_survive_the_boundary() {
        with_router(|router| {
            // Rules the server tests already cover, asserted again through C to
            // prove the crossing preserves them.
            let pinned = decide(
                router,
                r#"{"messages":[{"role":"user","content":"x"}]}"#,
                Some("cheap"),
                "",
            );
            assert_eq!(pinned.tier, Tier::Cheap as u8);
            assert_eq!(pinned.reason, reason_code(Reason::Pinned));

            let background = decide(
                router,
                r#"{"messages":[{"role":"user","content":"t"}],"max_tokens":16}"#,
                None,
                "",
            );
            assert_eq!(background.tier, Tier::Aux as u8);
            assert_eq!(background.reason, reason_code(Reason::Background));
        });
    }

    #[test]
    fn hostile_input_returns_a_value_rather_than_unwinding() {
        // A panic across the boundary is undefined behaviour; each of these
        // must return the sentinel instead.
        with_router(|router| {
            for bad in ["not json", ""] {
                assert_eq!(decide(router, bad, None, "").tier, u8::MAX, "for {bad:?}");
            }
        });
        assert_eq!(decide(std::ptr::null_mut(), "{}", None, "").tier, u8::MAX);
        // Freeing null must also be a no-op rather than a fault.
        unsafe { super::wattrouter_free(std::ptr::null_mut()) };
    }

    #[test]
    fn reason_codes_are_distinct() {
        let mut seen = std::collections::HashSet::new();
        for reason in Reason::ALL {
            assert!(seen.insert(reason_code(reason)), "duplicate for {reason:?}");
        }
    }

    #[test]
    fn tier_names_match_the_tier() {
        // The codes are written out in `wattrouter_tier_name` so that they stay
        // put; this is what keeps writing them out from drifting.
        for tier in Tier::ALL {
            let name = unsafe { CStr::from_ptr(super::wattrouter_tier_name(tier as u8)) };
            assert_eq!(name.to_str().unwrap(), tier.name());
        }
        assert!(super::wattrouter_tier_name(Decision::FAILED.tier).is_null());
    }

    #[test]
    fn reason_names_match_the_reason() {
        for reason in Reason::ALL {
            let code = reason_code(reason);
            let name = unsafe { CStr::from_ptr(super::wattrouter_reason_name(code)) };
            assert_eq!(name.to_str().unwrap(), reason.label());
        }
        assert!(super::wattrouter_reason_name(Decision::FAILED.reason).is_null());
    }

    #[test]
    fn backend_names_match_the_backend() {
        for backend in Backend::ALL {
            let code = super::backend_code(backend);
            let name = unsafe { CStr::from_ptr(super::wattrouter_backend_name(code)) };
            assert_eq!(name.to_str().unwrap(), backend.name());
        }
        // The code `wattrouter_chain_backend` returns past the end of a chain.
        assert!(super::wattrouter_backend_name(u8::MAX).is_null());
    }

    /// Every `wattrouter_*` name `text` uses as a function — the identifier
    /// immediately followed by an opening parenthesis, which is what separates a
    /// declaration or a call from a type name or a mention in prose.
    fn entry_points(text: &str) -> std::collections::BTreeSet<&str> {
        let mut found = std::collections::BTreeSet::new();
        for (start, _) in text.match_indices("wattrouter_") {
            let rest = &text[start..];
            let end = rest
                .find(|c: char| !c.is_ascii_alphanumeric() && c != '_')
                .unwrap_or(rest.len());
            if rest[end..].starts_with('(') {
                found.insert(&rest[..end]);
            }
        }
        found
    }

    #[test]
    fn a_chain_crosses_the_boundary_in_order() {
        with_router(|router| {
            // The chain the server derives, read through C one index at a time.
            // `with_router` has already set the credential the config needs.
            let config = crate::config::Config::from_env().expect("valid");

            // Every tier, not one: a caller reaches these by tier code, and a
            // crossing that mishandled a particular code would be invisible in a
            // check of a single tier. Compared against `chain_for` rather than
            // against written-out names, so a change to the catalogue moves this
            // with it instead of breaking it.
            for tier in Tier::ALL {
                let expected = crate::chain::chain_for(&config, tier);
                assert!(expected.len() > 1, "{} has no fallback", tier.name());

                let length = unsafe { super::wattrouter_chain_length(router, tier as u8) };
                assert_eq!(length, expected.len(), "for {}", tier.name());

                for (index, step) in expected.iter().enumerate() {
                    let name = unsafe { super::wattrouter_chain_model(router, tier as u8, index) };
                    assert!(!name.is_null(), "{} index {index} is inside", tier.name());
                    let name = unsafe { CStr::from_ptr(name) }.to_str().unwrap();
                    assert_eq!(name, step.model(), "{} at index {index}", tier.name());

                    let backend =
                        unsafe { super::wattrouter_chain_backend(router, tier as u8, index) };
                    assert_eq!(
                        backend,
                        super::backend_code(step.backend()),
                        "{} at index {index}",
                        tier.name()
                    );
                }
            }
        });
    }

    #[test]
    fn walking_past_a_chain_is_absence_rather_than_a_fault() {
        with_router(|router| {
            let length = unsafe { super::wattrouter_chain_length(router, Tier::Mid as u8) };
            for past in [length, length + 1, usize::MAX] {
                let name = unsafe { super::wattrouter_chain_model(router, Tier::Mid as u8, past) };
                assert!(name.is_null(), "at {past}");
                let backend =
                    unsafe { super::wattrouter_chain_backend(router, Tier::Mid as u8, past) };
                assert_eq!(backend, u8::MAX, "at {past}");
            }
            // A tier code that names no tier, and a null router, likewise.
            assert_eq!(
                unsafe { super::wattrouter_chain_length(router, u8::MAX) },
                0
            );
            assert_eq!(
                unsafe { super::wattrouter_chain_length(std::ptr::null(), Tier::Mid as u8) },
                0
            );
        });
    }

    #[test]
    fn backend_codes_are_distinct() {
        let mut seen = std::collections::HashSet::new();
        for backend in Backend::ALL {
            assert!(
                seen.insert(super::backend_code(backend)),
                "duplicate for {backend:?}"
            );
        }
    }

    #[test]
    fn the_header_declares_exactly_the_entry_points() {
        // The header is written by hand, so nothing else stops it describing a
        // library that no longer exists. A caller compiled against a stale
        // declaration does not fail to link; it reads the wrong answer.
        let declared = entry_points(HEADER);
        assert!(
            declared.contains("wattrouter_decide"),
            "the scan found nothing, which would make agreement vacuous"
        );
        // Both FFI files, because there is one header over the two of them.
        // Read as text rather than as symbols, so the git half counts here even
        // in a build that did not compile it — which is the build this test
        // usually runs in, and the one that would otherwise report the header as
        // describing functions that do not exist.
        let library = concat!(include_str!("ffi.rs"), include_str!("ffi_git.rs"));
        assert_eq!(
            declared,
            entry_points(library),
            "the header and the FFI sources disagree about the entry points"
        );
    }

    #[test]
    fn the_decision_struct_matches_the_header() {
        // C reads these offsets from its own declaration of the struct. A
        // mismatch misreads every field rather than failing to link, so the two
        // layouts are asserted rather than assumed to agree.
        assert_eq!(size_of::<Decision>(), 8);
        assert_eq!(align_of::<Decision>(), 4);
        assert_eq!(std::mem::offset_of!(Decision, tier), 0);
        assert_eq!(std::mem::offset_of!(Decision, reason), 1);
        assert_eq!(std::mem::offset_of!(Decision, score), 4);

        let sentinel = format!("#define WATTROUTER_FAILED {}", Decision::FAILED.tier);
        assert!(
            HEADER.contains(&sentinel),
            "the header's sentinel is not the one the library returns"
        );
    }
}
