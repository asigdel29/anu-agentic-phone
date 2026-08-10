//! `jni_answer.rs` — what the JNI bindings share across the boundary.
//!
//! History
//!   2026-08-09  A. Sigdel  Created, from `jni_git.rs` and `jni_memory.rs`,
//!                          which held identical copies of the first two.
//!   2026-08-10  A. Sigdel  Down to one helper with #541. `owned` went when the
//!                          crossing stopped using C strings; `guarded` went
//!                          when jni 0.22 started catching panics itself.
//!
//! Contents
//!   `handed`  Hand an envelope to Kotlin.
//!
//! This existed because `jni_git.rs` and `jni_memory.rs` held byte-identical
//! copies of two helpers, and neither copy applied the rules `jni.rs` states for
//! this boundary. #468 says none of them was a live failure; they were rules the
//! file next door followed and these two did not.
//!
//! Two of the three are gone rather than fixed. `owned` produced a `CString`,
//! which nothing crossing here needs since #565. `guarded` turned a panic into a
//! refusal, which `EnvUnowned::with_env` now does for every entry point in the
//! crate — it arrived in #482 to close exactly that gap and the dependency
//! closed it a second time.
//!
//! Not a reach into `jni_git` from `jni_memory`, which would make the memory
//! feature require the git feature. Gated on `android` with either of them, the
//! way `answer` is gated on either alone.
//!
//! `jni.rs` does not use this and that is deliberate. It answers with a
//! `Decided` it serialises itself, and its `read` is what the other two call.

use jni::Env;
use jni::sys::jstring;

/// Hand JSON to Kotlin.
///
/// `answered` was the same idea over a `*mut c_char` that it also freed. Nothing
/// crossing owns a Rust allocation now, so this is that helper minus the free.
/// It is here rather than in either caller because both need it, and a second
/// copy is what #468 and #482 were about.
///
/// # Returns
/// Null IF the JVM would not allocate, which is an out-of-memory condition with
/// nothing useful to say to it.
pub(crate) fn handed(env: &mut Env<'_>, json: String) -> jstring {
    env.new_string(json)
        .map_or(std::ptr::null_mut(), jni::objects::JString::into_raw)
}
