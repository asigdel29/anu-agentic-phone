//! testenv.rs — the environment, as the crate's tests are allowed to touch it.
//!
//! History
//!   2026-08-07  A. Sigdel  Created, from the lock that lived in config's tests.
//!
//! Contents
//!   `with_env`  Run a body with variables applied, holding the crate-wide lock.
//!
//! Environment variables are process-global and the lib tests share one process,
//! so a test that sets one races every test that reads one. `config` knew this
//! and kept a mutex — but inside its own `mod tests`, where nothing else could
//! take it, while its comment claimed nothing else read the environment
//! meanwhile. `ffi` did, both writing the credential and reading it back through
//! `Config::from_env`, and CI duly failed an unrelated change with
//! `Missing("NEURALWATT_API_KEY")` when the two overlapped.
//!
//! One lock, reachable from every module, is the whole fix. It lives here rather
//! than in `config` because it belongs to no single module: the next test that
//! reads a variable will be somewhere else again, and a lock owned by one module
//! is a lock the others quietly do without.

/// The one lock. Anything reading or writing the environment in a test holds it.
static ENV: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Run `body` with `vars` applied, restoring previous values afterwards.
///
/// # Arguments
/// * `vars` — variables to set, WHERE `None` removes one for the duration.
///
/// # Returns
/// Whatever `body` returned, with every named variable back as it was — a leaked
/// variable would silently change whichever test ran next.
///
/// # Panics
/// Does not panic on a poisoned lock: a test that panicked while holding it left
/// the environment restored by the guard below it, so the state is still sound
/// and failing every later test as well would only hide the first one.
///
/// # Atomic
/// Serialised against every other caller. Not reentrant — the lock is a plain
/// mutex, so `body` must not call this again or it will deadlock rather than
/// nest.
pub(crate) fn with_env<T>(vars: &[(&str, Option<&str>)], body: impl FnOnce() -> T) -> T {
    let _guard = ENV
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let saved: Vec<_> = vars
        .iter()
        .map(|(key, _)| ((*key).to_owned(), std::env::var(key).ok()))
        .collect();

    for (key, value) in vars {
        set(key, *value);
    }
    let out = body();
    for (key, value) in &saved {
        set(key, value.as_deref());
    }
    out
}

/// Set or remove one variable.
///
/// # Rely
/// Called only with the lock above held, which is what makes the unsafe calls
/// sound: nothing else in this process reads the environment meanwhile.
fn set(key: &str, value: Option<&str>) {
    unsafe {
        match value {
            Some(v) => std::env::set_var(key, v),
            None => std::env::remove_var(key),
        }
    }
}
