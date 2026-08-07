"""Tell the router which conversation a request belongs to.

History
    2026-08-07  A. Sigdel  Created.

Contents
    HEADER      The header the router reads a session from.
    PROVIDER    The provider whose calls are routed.
    register    Registers the middleware with Hermes.

The router keeps a session's tier and never lowers it, so a conversation that
turns out to need the heavy tier does not drop back to the middle one halfway
through, and a follow-up turn is not re-scored. ``cache.rs`` calls that the
larger of the cache's two wins. It needs ``x-session-id``, and nothing in this
stack was sending one, so it had never happened.

Install by symlinking this directory into ``$HERMES_HOME/plugins/`` and enabling
it in ``plugins.enabled``. Unlike a model provider, a feature plugin is opt-in.

Why middleware rather than the provider profile
-----------------------------------------------
``ProviderProfile.build_api_kwargs_extras`` is called per request with the
session id and costs one dict allocation, which makes it the obvious seam. It is
the wrong one. ``agent/auxiliary_client.py`` decides whether a profile handles
its own reasoning by comparing the *method identity*::

    profile_handles_reasoning = (
        type(profile).build_api_kwargs_extras
        is not ProviderProfile.build_api_kwargs_extras
        or ...
    )

Overriding it to add a header therefore suppresses the generic reasoning
injection for every auxiliary call, whatever the override actually does. The
live configuration sets ``agent.reasoning_effort``, so that is a real change and
an invisible one: nobody debugging missing reasoning on a summary would connect
it to a session header.

The middleware seam has no such coupling. It costs two deepcopies of the request
payload, which ``apply_llm_request_middleware`` takes as soon as any middleware
is registered. Measured with the interpreter Hermes runs, that cost follows the
tool-schema count and not the conversation, because message content is an
immutable ``str`` that ``deepcopy`` returns unchanged: 0.10ms with no tools,
1.95ms at thirty, and still 2.14ms when the conversation triples. Two or three
milliseconds against a turn dominated by inference is the right price for not
changing what reasoning does.
"""

from __future__ import annotations

from typing import Any

#: What the router reads. Matched case-insensitively on its side, so the
#: spelling here is for whoever reads a request log rather than for the parser.
HEADER = "x-session-id"

#: Only this provider's calls are tagged. The header means nothing to anyone
#: else, and a stray one on a call to a real provider is noise in someone's logs
#: at best. Matches the profile registered by the neuralwatt plugin.
PROVIDER = "neuralwatt"


def _tag(request: dict[str, Any], **context: Any) -> dict[str, Any] | None:
    """Add the session id to a routed request.

    Arguments
        request: the provider kwargs, already copied by the caller.
        context: at least ``session_id`` and ``provider``, supplied by
            ``agent/conversation_loop.py`` where the middleware is applied.

    Returns
        ``{"request": ...}`` when the header was added, or ``None`` to leave the
        request alone. Returning ``None`` rather than an unchanged copy is what
        keeps the middleware trace honest about which calls it touched.

    A request with no session is left alone rather than given an invented one:
    the router treats an absent session as "this turn stands by itself", which
    is correct for anything Hermes cannot attribute to a conversation.
    """
    if context.get("provider") != PROVIDER:
        return None

    session_id = context.get("session_id")
    if not session_id:
        return None

    # Merged rather than replaced. Nothing else sets a header on this path
    # today, but the Copilot initiator header shows the pattern is used, and
    # dropping someone else's header to add ours would be a poor trade.
    headers = dict(request.get("extra_headers") or {})
    headers[HEADER] = str(session_id)
    request["extra_headers"] = headers
    return {"request": request}


def register(ctx: Any) -> None:
    """Register the middleware with Hermes.

    Arguments
        ctx: the plugin context Hermes supplies on load.
    """
    ctx.register_middleware("llm_request", _tag)
