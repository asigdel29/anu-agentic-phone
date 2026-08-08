//! lib.rs — the wattrouter library.
//!
//! History
//!   2026-08-05  A. Sigdel  Created with the tier vocabulary. Configuration and
//!                          the server follow.
//!
//! Contents
//!   backend   Where a tier's model runs: in this process, or over the network.
//!   cache     Remembering routing decisions.
//!   chain     The models a tier may use, in order.
//!   classify  Reading routing signals off a request.
//!   config  What the router reads from the environment.
//!   embed   Turning a prompt into a vector.
//!   ffi     The C ABI an app calls the decision core through.
//!   head    Scoring a prompt's difficulty.
//!   metrics Counting what the router did.
//!   policy  Choosing a tier from a score and the request's signals.
//!   upstream  Forwarding a request to the provider.
//!   tier    The routing tiers: the vocabulary the rest of the router speaks.
//!   testenv The environment lock the tests share. Test builds only.
//!
//! Split from the binary so that each piece can be tested without binding a port
//! or starting a runtime, and so integration tests can reach the same items the
//! binary does rather than a copy of them.
//!
//! The router presents itself as an OpenAI-compatible endpoint, because that is
//! the protocol its clients speak. Everything it adds — tier
//! selection, stickiness, fallback — happens behind that interface.

#![warn(missing_docs)]
// Opted into here rather than passed on CI's command line, so that a local
// `cargo clippy` reports exactly what CI reports.
#![warn(clippy::pedantic)]

pub mod backend;
pub mod cache;
pub mod chain;
pub mod classify;
pub mod config;
pub mod embed;
pub mod ffi;
#[cfg(any(feature = "git", feature = "memory"))]
pub mod ffi_answer;
#[cfg(feature = "git")]
pub mod ffi_git;
#[cfg(feature = "memory")]
pub mod ffi_memory;
#[cfg(feature = "git")]
pub mod git;
pub mod head;
#[cfg(feature = "memory")]
pub mod memory;
pub mod metrics;
pub mod policy;
pub mod tier;
pub mod upstream;

// Not part of the crate's surface: one lock the tests share, so that a module
// setting a variable cannot fail a module reading one.
#[cfg(test)]
mod testenv;
