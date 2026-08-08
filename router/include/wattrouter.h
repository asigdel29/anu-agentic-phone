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
 *   wattrouter_string_free   Release what the git half returned.
 *
 * The git half is compiled only into a build with the `git` feature, and the
 * board's is not one — it has a shell and does not need libgit2 in a process
 * that would never call it. One header describes both builds, so calling these
 * without the feature is a link error naming the symbol, which is the failure
 * worth having. `scripts/build-ios-core.sh` turns it on: a phone has no shell.
 *
 * Hand-written rather than generated: the surface is eleven functions and one
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

/* Release a string returned by the git half. NULL is accepted and ignored. */
void wattrouter_string_free(char *text);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* WATTROUTER_H */
