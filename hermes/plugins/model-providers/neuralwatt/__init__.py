"""Provider profile pointing Hermes at the local router.

History
    2026-08-05  A. Sigdel  Created.

Contents
    NEURALWATT  The profile, registered on import.

NeuralWatt is not one of Hermes's bundled providers, so this supplies one. It
deliberately points at the router on loopback rather than at the provider: the
whole purpose of the stack is that every call is routed, and a profile aimed at
the provider would be a working path that quietly bypasses it.

Install by symlinking this directory into ``$HERMES_HOME/plugins/model-providers/``.
Hermes discovers profiles there and lets them override bundled ones of the same
name, so no Hermes source is modified.
"""

from __future__ import annotations

from providers import register_provider
from providers.base import ProviderProfile

#: Advertised to Hermes's model picker.
#:
#: ``auto`` is first because it is the intended default: the router decides. The
#: per-tier names follow so a tier can be pinned by hand when a routing decision
#: is wrong; the router accepts them as model names for exactly that reason.
_MODELS = ("auto", "heavy", "code", "long", "mid", "cheap", "aux")

NEURALWATT = ProviderProfile(
    name="neuralwatt",
    display_name="NeuralWatt (via wattrouter)",
    description="Routed to the cheapest model that can serve the request.",
    signup_url="https://portal.neuralwatt.com",
    # Loopback. The router holds the only credential on the board, so Hermes
    # needs none of its own and a compromise of Hermes does not yield one.
    base_url="http://127.0.0.1:8080/v1",
    env_vars=("WATTROUTER_ADDR",),
    fallback_models=_MODELS,
    # Hermes's own housekeeping — titles, summaries, compaction — is the
    # highest-volume traffic in a session. Pinning it to the auxiliary tier means
    # the router does not have to infer that from `max_tokens` for every one.
    default_aux_model="aux",
    # The router speaks the OpenAI wire protocol in both directions.
    api_mode="chat_completions",
    hostname="127.0.0.1",
    # The router is a proxy, not a model host: it does not implement the
    # prompt-cache extension, and an unknown top-level field would be rejected
    # rather than ignored.
    supports_prompt_cache_key=False,
)

register_provider(NEURALWATT)
