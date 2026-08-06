/* wattrouter.h — the C ABI the iOS app calls the decision core through.
 *
 * History
 *   2026-08-06  A. Sigdel  Created.
 *
 * Contents
 *   wattrouter            A router; opaque.
 *   wattrouter_decision   A tier, why it was chosen, and the score behind it.
 *   wattrouter_new/free   Router lifetime.
 *   wattrouter_decide     The whole decision path: classify, score, policy.
 *   wattrouter_tier_name  The name behind a tier code.
 *   wattrouter_reason_name  The name behind a reason code.
 *
 * Hand-written rather than generated: the surface is five functions and one
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

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* WATTROUTER_H */
