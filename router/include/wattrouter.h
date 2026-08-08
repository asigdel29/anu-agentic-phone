/* wattrouter.h — the C ABI the iOS app calls the decision core through.
 *
 * History
 *   2026-08-06  A. Sigdel  Created.
 *   2026-08-08  A. Sigdel  Declared the git half, which allocates and so needs a
 *                          way to give the allocation back.
 *
 * Contents
 *   wattrouter            A router; opaque.
 *   wattrouter_decision   A tier, why it was chosen, and the score behind it.
 *   wattrouter_new/free   Router lifetime.
 *   wattrouter_decide     The whole decision path: classify, score, policy.
 *   wattrouter_chain_length/model/backend  The models behind a tier, in order.
 *   wattrouter_tier_name  The name behind a tier code.
 *   wattrouter_reason_name  The name behind a reason code.
 *   wattrouter_backend_name  The name behind a backend code.
 *   wattrouter_git_head      Where a repository's HEAD points.
 *   wattrouter_git_status    The working tree, against the index and the head.
 *   wattrouter_git_add       Staging paths.
 *   wattrouter_git_commit    Writing what is staged.
 *   wattrouter_string_free   Release what an allocating call returned.
 *   wattrouter_memory_open/free  A memory store's lifetime.
 *   wattrouter_memory_remember   Putting a turn in.
 *
 * The git half is compiled only into a build with the `git` feature, and the
 * board's is not one — it has a shell and does not need libgit2 in a process
 * that would never call it. One header describes both builds, so calling these
 * without the feature is a link error naming the symbol, which is the failure
 * worth having. `scripts/build-ios-core.sh` turns it on: a phone has no shell.
 *
 * The memory half is compiled only into a build with the `memory` feature, on
 * the same terms as `git` above and for the same reason.
 *
 * Hand-written rather than generated: the surface is seventeen functions and one
 * struct, and a generator would cost more to keep in the build than it saves.
 * A hand-written header can drift from the library it describes, though, and a
 * mismatch here is a wrong answer rather than a link error — so the test
 * `the_header_declares_exactly_the_entry_points` in ffi.rs holds the two in
 * step, and `the_decision_struct_matches_the_header` covers the layout.
 *
 * Link against libwattrouter.a built for aarch64-apple-ios. The build needs
 * neither ONNX Runtime nor the server, so --no-default-features --lib is the
 * build that belongs in an app.
 */

#ifndef WATTROUTER_H
#define WATTROUTER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* What `tier` and `reason` carry when a call could not decide: one field to
 * check, and nothing to free. */
#define WATTROUTER_FAILED 255

/* Everything a decision needs: thresholds, embedder, score cache, and the
 * scoring head IF one was loaded.
 *
 * One router may be shared across threads. Its score cache is behind a mutex and
 * the rest is read-only once built, so concurrent wattrouter_decide calls are
 * safe and only contend on a cache hit. Asserted at compile time by
 * `assert_shareable` in ffi.rs rather than promised here. */
typedef struct wattrouter wattrouter;

/* A decision, returned by value so the caller frees nothing. */
typedef struct {
    /* Which tier serves the request, ascending by capability — a caller may
     * compare two codes and the larger is the more capable. Read the name with
     * wattrouter_tier_name. WATTROUTER_FAILED IF the call could not decide. */
    uint8_t tier;
    /* Why this tier and not another; read the name with wattrouter_reason_name.
     * Meaningless when `tier` is WATTROUTER_FAILED. */
    uint8_t reason;
    /* Difficulty in [0.0, 1.0], higher meaning harder, or -1.0 IF the prompt was
     * not scored — no head loaded, or no text to score. */
    float score;
} wattrouter_decision;

/* Build a router, configured from the environment as the server is.
 *
 * head_path names the scoring head's weights, or is NULL to take the configured
 * default. A head that will not load is not a failure: the policy has an
 * unscored path.
 *
 * Returns a router to pass to wattrouter_free, or NULL IF configuration was
 * rejected. */
wattrouter *wattrouter_new(const char *head_path);

/* Release a router. NULL is accepted and ignored. */
void wattrouter_free(wattrouter *router);

/* Decide which tier serves a request.
 *
 * body_json is an OpenAI-shaped chat completion request. pin names a tier to
 * force, or is NULL. session identifies the conversation, so that a tier the
 * session has already been raised to is not dropped partway through; NULL means
 * no stickiness.
 *
 * Every pointer is borrowed for the call only. Returns a decision whose `tier`
 * is WATTROUTER_FAILED IF router was NULL, IF body_json was not valid UTF-8
 * JSON, or IF the call failed internally — no panic crosses this boundary. */
wattrouter_decision wattrouter_decide(const wattrouter *router,
                                      const char *body_json,
                                      const char *pin,
                                      const char *session);

/* How many models back `tier`, in the order they will be tried.
 *
 * A decision names a tier, which is a role. What answers is the chain behind it,
 * and a caller holding only the tier cannot dispatch.
 *
 * Returns at most three, or 0 IF `router` is NULL or `tier` names no tier — so
 * every index below the result can be read without checking each for absence. */
size_t wattrouter_chain_length(const wattrouter *router, uint8_t tier);

/* The `index`th model backing `tier`.
 *
 * Returns a name borrowed from `router` and valid until wattrouter_free, which
 * the caller must not free, or NULL IF `index` is past the end of the chain. */
const char *wattrouter_chain_model(const wattrouter *router, uint8_t tier,
                                   size_t index);

/* Where the `index`th model of `tier`'s chain runs: 0 local, 1 remote.
 *
 * A model name does not say how to reach it — one is a file to load into this
 * process and another is an HTTP request. This is what separates them.
 *
 * Returns a backend code, or WATTROUTER_FAILED IF `index` is past the end. */
uint8_t wattrouter_chain_backend(const wattrouter *router, uint8_t tier,
                                 size_t index);

/* The name of a tier code, as configuration and metrics spell it.
 *
 * Returns a static string the caller must not free, or NULL IF `tier` names no
 * tier — which includes WATTROUTER_FAILED. */
const char *wattrouter_tier_name(uint8_t tier);

/* The name of a reason code, as metrics spell it.
 *
 * Returns a static string the caller must not free, or NULL IF `reason` names
 * no reason — which includes WATTROUTER_FAILED. */
const char *wattrouter_reason_name(uint8_t reason);

/* The name of a backend code, as configuration spells it.
 *
 * A chain crosses as a model name and a number; this is the word behind the
 * number, for a caller that logs or displays which half of the stack answered.
 *
 * Returns a static string the caller must not free, or NULL IF `backend` names
 * no backend — which includes WATTROUTER_FAILED. */
const char *wattrouter_backend_name(uint8_t backend);

/* Every git call answers with one JSON envelope — {"ok": …} with the operation's
 * result, or {"error": "…"} with why it could not be done. The refusals are
 * written for the model to act on rather than for a caller to classify, so they
 * cross as text; a caller switches on which key is present.
 *
 * A returned string is owned by the caller and released with
 * wattrouter_string_free, never with free: Rust allocated it and the two
 * allocators need not be the same one.
 */

/* Where HEAD points.
 *
 * `ok` carries a `kind` of "branch", "detached" or "unborn", and the name or
 * commit behind it. Unborn is a repository with no commits yet, which libgit2
 * reports as a failure and this does not: it is the state an agent most often
 * finds on a repository it has just made.
 *
 * Returns an owned string, or NULL IF `path` was NULL or not UTF-8. */
char *wattrouter_git_head(const char *path);

/* The working tree, against the index and the head.
 *
 * `ok` carries the head as above, plus `staged` and `unstaged` as lists of
 * {path, kind}, and `untracked` and `conflicted` as lists of paths. An untracked
 * directory is named rather than walked, so a large clone answers with the
 * directory instead of everything under it. A conflicted path appears only in
 * `conflicted`, because it is not something to commit.
 *
 * Returns an owned string, or NULL on the terms above. */
char *wattrouter_git_status(const char *path);

/* Stage paths, and answer with the status that results.
 *
 * paths_json is a JSON array of strings relative to the repository root, where a
 * directory stages what is under it. JSON rather than a char ** and a count
 * because the model writes these as JSON and the tool decodes them there;
 * rebuilding that as a C array only to parse it back is three shapes for one
 * value. A `paths_json` that is not an array of strings is refused with a message
 * saying so, and that refusal does not read as a git failure.
 *
 * `error` names the missing path IF one is missing, so that a model which
 * misspelt one of four is told which. Nothing is staged in that case.
 *
 * Returns an owned string, or NULL IF either argument was NULL or not UTF-8. */
char *wattrouter_git_add(const char *path, const char *paths_json);

/* Commit what is staged.
 *
 * `ok` is the short id of the commit written. Committing nothing is an error
 * rather than an empty commit: libgit2 writes a commit whose tree matches its
 * parent without complaint, and a model doing that in a loop produces a history
 * of identical trees while believing it is making progress.
 *
 * Returns an owned string, or NULL on the terms above. */
char *wattrouter_git_commit(const char *path, const char *message);

/* Release a string returned by any call that allocates one. NULL is accepted and
 * ignored. */
void wattrouter_string_free(char *text);

/* A memory store, bounded and opened. Opaque.
 *
 * `keep` is how many recent turns stay in front of the horizon; everything older
 * moves to an archive table in the same file before the store opens, because
 * opening is what loads and indexes all of it. See
 * docs/decisions/memory-on-a-phone-forgets.md.
 *
 * One store may be shared across threads: it is behind a mutex, so concurrent
 * calls queue rather than race.
 *
 * Returns a store to pass to wattrouter_memory_free, or NULL IF `path` was NULL
 * or not UTF-8, IF the horizon failed, or IF the store would not open. No
 * envelope: with no handle there is nothing to free. */
typedef struct wattrouter_memory wattrouter_memory;
wattrouter_memory *wattrouter_memory_open(const char *path, size_t keep);

/* Release a memory store. NULL is accepted and ignored. */
void wattrouter_memory_free(wattrouter_memory *memory);

/* Put a turn in.
 *
 * `ts` is a Unix timestamp in seconds. A turn with no text is refused rather
 * than stored: nothing indexes it, so it could never be recalled and would still
 * count against the horizon.
 *
 * `ok` is the turn's id. Returns an owned string to release with
 * wattrouter_string_free, or NULL IF any pointer was NULL or not UTF-8. */
char *wattrouter_memory_remember(const wattrouter_memory *memory,
                                 const char *session, const char *speaker,
                                 const char *text, int64_t ts);


#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* WATTROUTER_H */
