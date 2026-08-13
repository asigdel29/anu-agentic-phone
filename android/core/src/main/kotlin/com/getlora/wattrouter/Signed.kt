// Signed.kt: who the commits from this phone say made them.
//
// History
//   2026-08-11  A. Sigdel  Created with #636.
//
// Contents
//   Who     A name and an email, as somebody typed them.
//   Signed  A Worktree that says who is committing, before it commits.
//
// The core reads user.name and user.email from the repository's own config and
// nowhere else, and nothing on a phone writes either, so every commit on one
// failed until #639 added identify. This is what calls it.
//
// It sits at the Worktree seam rather than inside GitCommitTool, which is
// Budget.kt's reasoning applied to the other half of the tools: every git tool
// reaches the repository through one object, so a second thing that commits is
// signed without its author knowing this exists.
//
// Read per commit rather than held, which is what Confirmed relies on for the
// same shape of setting. Somebody who corrects a misspelt email between typing
// and the model committing means this commit rather than the next launch, and
// the driver is built once and kept, so an identity captured where the tools
// are assembled would be whichever one was set when the application started.
//
// Only commit. Reading, staging and making a repository sign nothing, and
// writing a configuration entry on a read is a write nobody asked for.

package com.getlora.wattrouter

/**
 * Who a commit from this phone will say made it.
 *
 * @property name and [email] as typed, untrimmed and unchecked. The core trims
 *   both and refuses a blank either side, so validating here would be a second
 *   copy of a rule that already has somewhere to live.
 */
data class Who(val name: String, val email: String)

/**
 * A [Worktree] that says who is committing, immediately before it does.
 *
 * @param who read on every commit, and null while nobody has said. Null is an
 *   ordinary state rather than a failure: an identity is typed once and a phone
 *   that has not been told is a phone whose commits should be refused, which is
 *   what the core does with the words for it.
 */
class Signed(private val worktree: Worktree, private val who: () -> Who?) : Worktree {

    override fun init(): String? = worktree.init()

    // Passed through rather than intercepted. Something calling this directly
    // is saying who once, on purpose, and is not the caller this exists for.
    override fun identify(name: String, email: String): String? =
        worktree.identify(name, email)

    override fun head(): String? = worktree.head()

    override fun status(): String? = worktree.status()

    override fun add(paths: List<String>): String? = worktree.add(paths)

    // Neither writes a commit, so neither needs somebody to sign one. Passing
    // them through unchanged is the decision rather than an oversight: the one
    // thing this class does is put an identity in front of `commit`.
    override fun remoteSet(name: String, url: String): String? = worktree.remoteSet(name, url)

    override fun fetch(name: String): String? = worktree.fetch(name)

    override fun push(remote: String, branch: String): String? = worktree.push(remote, branch)

    override fun pull(remote: String, branch: String): String? = worktree.pull(remote, branch)

    /**
     * Say who, then commit.
     *
     * Before rather than after, for [Budgeted]'s reason turned around: a commit
     * signed by whoever was configured last time and then corrected is a commit
     * with the wrong name on it, and a commit is not editable afterwards.
     */
    override fun commit(message: String): String? {
        // Nothing at all when nobody has been named, so the core's own refusal
        // is what the model reads. Calling identify with blanks would produce
        // the same refusal one layer further from where it is understood.
        who()?.let {
            // The answer is dropped deliberately. Every way this can fail is a
            // way the commit below fails too, with the same reason and better
            // words: there is no repository, or the identity is half filled in.
            // Reporting it here would put two sentences in front of the model
            // about one problem.
            worktree.identify(it.name, it.email)
        }
        return worktree.commit(message)
    }
}
