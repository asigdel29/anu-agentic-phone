//! lib.rs — the wattrouter library.
//!
//! History
//!   2026-08-05  A. Sigdel  Created with the tier vocabulary. Configuration and
//!                          the server follow.
//!
//! Contents
//!   cache     Remembering routing decisions.
//!   chain     The models a tier may use, in order.
//!   classify  Reading routing signals off a request.
//!   config  What the router reads from the environment.
//!   embed   Turning a prompt into a vector.
//!   head    Scoring a prompt's difficulty.
//!   policy  Choosing a tier from a score and the request's signals.
//!   upstream  Forwarding a request to the provider.
//!   tier    The routing tiers: the vocabulary the rest of the router speaks.
//!
//! Split from the binary so that each piece can be tested without binding a port
//! or starting a runtime, and so integration tests can reach the same items the
//! binary does rather than a copy of them.
//!
//! The router presents itself as an OpenAI-compatible endpoint, because that is
//! the only protocol both Hermes and OpenCode speak. Everything it adds — tier
//! selection, stickiness, fallback — happens behind that interface.

#![warn(missing_docs)]
// Opted into here rather than passed on CI's command line, so that a local
// `cargo clippy` reports exactly what CI reports.
#![warn(clippy::pedantic)]

pub mod cache;
pub mod chain;
pub mod classify;
pub mod config;
pub mod embed;
pub mod head;
pub mod policy;
pub mod tier;
pub mod upstream;
