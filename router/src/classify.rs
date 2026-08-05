//! classify.rs — reading routing signals off a request.
//!
//! History
//!   2026-08-05  A. Sigdel  Created.
//!
//! Contents
//!   `MAX_ROUTING_CHARS`  Cap on the text handed to the scorer.
//!   `Classified`         The text to score, plus the policy's signals.
//!   `classify`           Reads both from a request body and its headers.
//!
//! This runs in front of every call, so it does no allocation it can avoid and
//! never fails. A malformed body is the upstream's business to reject: the
//! router's job is to forward it, and a classifier that rejected first would turn
//! a routing concern into an outage. Anything undeterminable is simply absent,
//! and the policy already has a defined answer for absent.

use crate::policy::Signals;
use crate::tier::Tier;

/// Cap on the text handed to the scorer.
///
/// Routing cost must not grow with conversation length — a hundred-turn
/// conversation should cost the same to route as a one-turn one. Roughly 512
/// tokens, which is also where the embedding model's own window stops being
/// useful.
pub const MAX_ROUTING_CHARS: usize = 2048;

/// Rough characters per token, for the capability rule.
///
/// A real tokeniser would cost more than the routing decision it informs. This is
/// only ever compared against a threshold deliberately set well below the true
/// limits, so the error it introduces is absorbed by that margin.
const CHARS_PER_TOKEN: usize = 4;

/// The text to score and the signals that go with it.
#[derive(Debug, Clone, Default)]
pub struct Classified {
    /// The last user message, truncated. Empty IF the request had none, in which
    /// case there is nothing to score and the policy's unscored path applies.
    pub text: String,
    /// What the policy needs.
    pub signals: Signals,
}

/// Read routing signals from a request body and its pinning header.
///
/// # Arguments
/// * `body` — a parsed chat completion request. Any shape is tolerated.
/// * `tier_header` — the value of `x-wattrouter-tier`, IF present.
///
/// # Returns
/// Signals for the policy, always. Fields that cannot be determined take their
/// default, which the policy handles explicitly.
///
/// # Rely
/// Called on the request path, before anything is sent upstream. Does no I/O and
/// never blocks.
#[must_use]
pub fn classify(body: &serde_json::Value, tier_header: Option<&str>) -> Classified {
    let messages = body.get("messages").and_then(|m| m.as_array());

    // The header wins over the model field: a caller who set both was more
    // deliberate about the header, and it is the documented override.
    let pinned = tier_header.and_then(tier_from_name).or_else(|| {
        body.get("model")
            .and_then(|m| m.as_str())
            .and_then(tier_from_name)
    });

    let Some(messages) = messages else {
        return Classified {
            signals: Signals {
                pinned,
                ..Signals::default()
            },
            ..Classified::default()
        };
    };

    // Every message counts towards the capability rule; only the last user
    // message is scored.
    let total_chars: usize = messages.iter().filter_map(content_of).map(str::len).sum();

    let last_user = messages
        .iter()
        .rev()
        .find(|m| m.get("role").and_then(|r| r.as_str()) == Some("user"))
        .and_then(content_of)
        .unwrap_or_default();

    let text = truncate_on_char_boundary(last_user, MAX_ROUTING_CHARS);

    Classified {
        signals: Signals {
            estimated_tokens: total_chars / CHARS_PER_TOKEN,
            has_code: looks_like_code(&text),
            is_background: is_background(body),
            pinned,
        },
        text,
    }
}

/// The textual content of a message.
///
/// Multipart content is skipped rather than flattened. It is rare on this path,
/// and mis-flattening it would feed the scorer a mangled string, which is worse
/// than feeding it nothing — the policy has an answer for nothing.
fn content_of(message: &serde_json::Value) -> Option<&str> {
    message.get("content").and_then(|c| c.as_str())
}

/// Match a tier by name, for pinning.
///
/// `auto` is deliberately not a tier: it is the request to decide, which is the
/// default, so it maps to no pin at all.
fn tier_from_name(name: &str) -> Option<Tier> {
    Tier::ALL.into_iter().find(|t| t.name() == name)
}

/// Cut `text` to at most `limit` bytes without splitting a character.
///
/// Slicing mid-character would panic. This sits on the request path, so the
/// boundary is found rather than assumed.
fn truncate_on_char_boundary(text: &str, limit: usize) -> String {
    if text.len() <= limit {
        return text.to_owned();
    }
    let mut end = limit;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    text[..end].to_owned()
}

/// Whether the text looks like code, a diff, or a stack trace.
///
/// Cheap and deliberately shallow. A false positive costs a request served by the
/// code tier instead of the general one at the same price bracket; a false
/// negative costs nothing at all. Neither justifies parsing.
fn looks_like_code(text: &str) -> bool {
    const MARKERS: [&str; 8] = [
        "```",
        "diff --git",
        "@@ ",
        "Traceback",
        "fn ",
        "def ",
        "class ",
        "import ",
    ];
    MARKERS.iter().any(|m| text.contains(m))
}

/// Whether this is housekeeping rather than a person waiting.
///
/// The explicit marker is authoritative. The `max_tokens` fallback exists because
/// agents issue titling and summarising calls without marking them, and those are
/// the highest-volume requests in a session — routing them to a large model is
/// the single most wasteful thing the router could do.
fn is_background(body: &serde_json::Value) -> bool {
    if body
        .get("x_wattrouter_background")
        .and_then(serde_json::Value::as_bool)
        == Some(true)
    {
        return true;
    }

    // A tiny output cap means a title or a label, not an answer to a question.
    body.get("max_tokens")
        .and_then(serde_json::Value::as_u64)
        .is_some_and(|n| n <= 32)
}

#[cfg(test)]
mod tests {
    use super::{MAX_ROUTING_CHARS, classify};
    use crate::tier::Tier;
    use serde_json::json;

    #[test]
    fn the_last_user_message_is_what_gets_scored() {
        // Not the first, and not the system prompt: it is the current question
        // that determines difficulty, not the history around it.
        let body = json!({"messages": [
            {"role": "system", "content": "You are helpful."},
            {"role": "user", "content": "first question"},
            {"role": "assistant", "content": "an answer"},
            {"role": "user", "content": "the current question"},
        ]});
        assert_eq!(classify(&body, None).text, "the current question");
    }

    #[test]
    fn token_estimate_covers_the_whole_conversation() {
        // The capability rule is about what the upstream must hold, which is
        // everything, even though only the last message is scored.
        let body = json!({"messages": [
            {"role": "user", "content": "x".repeat(4000)},
            {"role": "assistant", "content": "y".repeat(4000)},
        ]});
        assert_eq!(classify(&body, None).signals.estimated_tokens, 2000);
    }

    #[test]
    fn scored_text_is_capped_regardless_of_message_size() {
        // Routing a hundred-turn conversation must cost what routing one turn
        // costs.
        let body = json!({"messages": [{"role": "user", "content": "a".repeat(50_000)}]});
        assert_eq!(classify(&body, None).text.len(), MAX_ROUTING_CHARS);
    }

    #[test]
    fn truncation_does_not_split_a_character() {
        // A three-byte character, deliberately: MAX_ROUTING_CHARS is not a
        // multiple of three, so the cap lands mid-character and the boundary
        // search actually runs. A two-byte character would divide evenly and
        // leave this asserting nothing.
        assert_ne!(
            MAX_ROUTING_CHARS % 3,
            0,
            "otherwise this test proves nothing"
        );
        let body = json!({"messages": [{"role": "user", "content": "日".repeat(50_000)}]});
        let text = classify(&body, None).text;
        assert!(text.len() <= MAX_ROUTING_CHARS);
        assert!(!text.is_empty() && text.chars().all(|c| c == '日'));
    }

    #[test]
    fn code_is_recognised() {
        let code = json!({"messages": [{"role": "user", "content": "fix ```fn main() {}```"}]});
        let prose = json!({"messages": [{"role": "user", "content": "what is the capital"}]});
        assert!(classify(&code, None).signals.has_code);
        assert!(!classify(&prose, None).signals.has_code);
    }

    #[test]
    fn both_pinning_routes_work_and_the_header_wins() {
        let body = json!({"model": "cheap", "messages": []});
        assert_eq!(classify(&body, None).signals.pinned, Some(Tier::Cheap));
        // Some clients cannot set headers, hence the model field; a caller who
        // set both was more deliberate about the header.
        assert_eq!(
            classify(&body, Some("heavy")).signals.pinned,
            Some(Tier::Heavy)
        );
    }

    #[test]
    fn auto_is_not_a_pin() {
        // `auto` is the request to decide, which is already the default.
        let body = json!({"model": "auto", "messages": []});
        assert!(classify(&body, None).signals.pinned.is_none());
    }

    #[test]
    fn a_tiny_output_cap_reads_as_background() {
        // Agents issue titling calls without marking them, and they are the
        // highest-volume requests in a session.
        let title =
            json!({"messages": [{"role": "user", "content": "name this"}], "max_tokens": 16});
        let answer =
            json!({"messages": [{"role": "user", "content": "name this"}], "max_tokens": 4096});
        assert!(classify(&title, None).signals.is_background);
        assert!(!classify(&answer, None).signals.is_background);
    }

    #[test]
    fn a_hostile_body_yields_defaults_rather_than_failing() {
        // The upstream is entitled to reject this. A classifier that rejected
        // first would turn a routing concern into an outage.
        for body in [
            json!({}),
            json!({"messages": "not an array"}),
            json!({"messages": [{"role": "user"}]}),
            json!({"messages": [{"content": 42}]}),
            json!(null),
        ] {
            let out = classify(&body, None);
            assert!(out.text.is_empty(), "for {body}");
            assert!(out.signals.pinned.is_none(), "for {body}");
        }
    }
}
