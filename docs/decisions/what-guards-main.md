# What guards main

`main` had no protection at all until the end of Milestone H. Forty-eight pull requests went
through CI and the guards because somebody chose to put them there, not because anything
required it.

This says what requires it now, and — more usefully — which two of the obvious settings are
absent on purpose, because both are the kind somebody turns on later to tidy up.

## It came last, and that was the point

`strict` means every open pull request needs a rebase whenever `main` moves. Imposing that
across a milestone of fifty sequential merges would have bought nothing and cost a rebase
each time. The gates were doing their job throughout; what was missing was only the guarantee
that they could not be skipped, and a guarantee is worth having once there is something to
guarantee about.

## The checks are display names, not job ids

`Required` and `Guards` are the `name:` fields of two jobs. Their ids are `required` and
`guards`, and a ruleset naming an id waits forever for a check that never reports under that
name — a pull request that can never be merged, with nothing anywhere saying why.

`Required` is CI's aggregator. It exists because a job added to `needs` and forgotten in
`RESULTS` makes the aggregator pass green over that job's failure, so the aggregator is what
gets required rather than eleven separate checks that can drift out of step with the workflow.
`Guards` is `pr-governance.yml`, which is a separate workflow because it reads the pull
request rather than the code.

## There is no merge queue, and that is the important absence

`pr-governance.yml` triggers on `pull_request` alone. Its own header says why: on a
`merge_group` ref there is no pull request to read — no title, no body, no head branch — so
`issue-link`, `pr-size` and `slopgate` have nothing to look at.

A merge queue would therefore wait forever for `Guards` on every queued candidate, and the
queue would stall with no failing check to point at. The fix, if a queue is ever wanted, is
to give `pr-governance.yml` a `merge_group` trigger and decide what the three guards mean
there — not to enable the queue and see.

## There is no required review

A solo repository with one is a repository nobody can merge to. If a second person ever
works here, this is the line to change, and it is one number.

## Squash only

Every merge so far has been a squash, and the history reads as one commit per pull request
pointing at one issue. Allowing a merge commit would put branch history into a log that has
never had any, and allowing a rebase merge would produce commits whose messages were written
for a branch rather than for `main`.

`required_linear_history` says the same thing from the other side, and both are set: the
merge-method list is what the UI offers, and linear history is what the branch will accept
however somebody gets there.

## It is applied by a script

`scripts/protect-main.sh`, reviewable in a diff like anything else, and idempotent — running
it again is how to see what is set, which is why there is no separate command for that. A
rule that lives only in a settings page is a rule nobody reviews, and this one has a clause
whose *absence* is the whole point.
