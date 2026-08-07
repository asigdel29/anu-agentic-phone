# Working in `hermes/`

Five files, and the least discoverable directory here: **nothing in it runs from this
repository**. The agent is installed separately, and these are the configuration and the two
plugins it loads. Read the root `AGENTS.md` first.

## What is here

`config.yaml` is the configuration applied to an installed agent. `plugins/session_routing/`
sends `x-session-id` on routed requests, so a follow-up turn keeps its tier instead of being
re-scored. `plugins/model-providers/neuralwatt/` is the provider.

## Installing, and what each script promises

`just install-hermes` puts the plugins where the agent finds them and **enables nothing**.
`just hermes-config` reports what pointing the agent at the router would change and **changes
nothing** — read it before applying. `just hermes-config-apply` applies it, and
`just hermes-unconfig` puts the previous configuration back leaf by leaf, from the state kept
under `deploy/.state/`.

The split is deliberate: a script that both explains and applies gets run for the explanation
and does the applying too.

## Editing a plugin

`plugin.yaml` carries `name`, and it is the key `plugins.enabled` matches — not the directory.
They are kept identical so there is one thing to remember, and a rename has to change both.
Names are underscored because a plugin directory is imported as a Python module.

State `kind` rather than omitting it. Leaving it out puts the loader through a heuristic that
guesses whether a plugin is a memory provider, and a guess is not a default.

## Verifying

There is no test suite here and CI does not lint this directory: `ruff` and `mypy --strict` run
over `train/` only. A change to a plugin is verified by installing it and running the agent, so
a pull request touching this directory should say whether that happened. It usually has not.
