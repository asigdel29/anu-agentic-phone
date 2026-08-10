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
nothing**; read it before applying. `just hermes-config-apply` applies it, and
`just hermes-unconfig` puts the previous configuration back leaf by leaf, from the state kept
under `deploy/.state/`.

The split is deliberate: a script that both explains and applies gets run for the explanation
and does the applying too.

## Editing a plugin

`plugin.yaml` carries `name`, and the two plugins here treat it differently, so check which kind
you are editing before assuming a rule.

`session_routing` is matched by `plugins.enabled` in `config.yaml`, and its `name` is the
directory name, underscored because a plugin directory is imported as a Python module. Renaming
it means changing the directory, the `name`, and the `config.yaml` entry together.

`neuralwatt` is a model provider and is not listed in `plugins.enabled` at all. Its `name` is
`neuralwatt-profile`, hyphenated and deliberately not the directory name. Do not "fix" it to
match.

State `kind` rather than omitting it. Leaving it out puts the loader through a heuristic that
guesses whether a plugin is a memory provider, and a guess is not a default.

## Verifying

There is no test suite here, and what CI does check is narrower than it looks. `yamllint` runs
unconditionally over the whole repository, so `config.yaml` and both `plugin.yaml` files are
held to `.yamllint.yml`: 140 columns, no trailing space. Nothing else covers this directory:
`ruff` and `mypy --strict` are configured for `train/` and skipped entirely today, so no Python
here is linted or type-checked by anything.

A change to a plugin is verified by installing it and running the agent. A pull request touching
this directory should say whether that happened. It usually has not.
