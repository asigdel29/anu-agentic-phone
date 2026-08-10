"""Answer the app's completion requests from a script, and record what it asked.

History
    2026-08-09  A. Sigdel  Created with #516.

Contents
    Script   The turns to answer with, read from a JSON file.
    Handler  One request: append it to the log, answer with the next turn.
    main     Parse arguments and serve until interrupted.

Why this exists is #516: every tool this application has -- read_screen, tap,
type_text, open_app -- needs a model to decide to call it, and the only reachable
model is a provider with a bill attached. So the part of the application that is
the whole point of it had no test anybody could run.

This speaks the same wire format the provider does, which is the only thing that
makes it worth anything: text/event-stream, OpenAI chunk shape, tool calls as
indexed fragments. ServerSentEvent.kt is the reader and this is written against
it rather than against a specification, because a stub that is faithful to a
document the client does not implement tests nothing.

Two halves, and the second is the interesting one:

  * The script says what the model decides. A list of turns, consumed in order,
    so a run is reproducible and a failure is the same failure twice.
  * The log says what the application asked. Every request body is appended to
    a file, which is how somebody writes the *next* script -- a tap can only be
    aimed once read_screen has answered, and its answer arrives here in the tool
    result of the following request.

Standard library only, and deliberately: this is a test harness, and a harness
that has to be installed before the tests run is a harness that gets skipped.

Cleartext on purpose. The emulator reaches the host at 10.0.2.2 and the debug
build permits that one address; see android/app/src/debug/res/xml. Nothing here
should ever be reachable from a real network, which is why it binds loopback.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

#: What the app is told it is talking to. Only echoed back, never chosen from.
MODEL = "stub"


class Script:
    """The turns to answer with, and how far through them we are.

    # Atomic
    Served from a threading server, so `next_turn` is called from more than one
    thread. The lock is over the cursor rather than the list, which is read-only
    once loaded.
    """

    def __init__(self, turns: list[dict]) -> None:
        self._turns = turns
        self._at = 0
        self._lock = threading.Lock()

    @classmethod
    def load(cls, path: pathlib.Path) -> Script:
        """Read a script file, or fail saying which one and why.

        A malformed script is fatal here rather than at the first request,
        because a stub that starts and then refuses every turn looks to the
        application exactly like a provider that is down.
        """
        raw = json.loads(path.read_text())
        turns = raw["turns"] if isinstance(raw, dict) else raw
        if not isinstance(turns, list) or not turns:
            raise ValueError(f"{path}: no turns in it")
        return cls(turns)

    def next_turn(self) -> dict:
        """The next turn, or a spoken refusal once the script runs out.

        Running out is answered rather than raised. A turn loop that receives a
        transport error retries or reports a provider failure, and neither is
        what happened -- what happened is that nobody wrote down what the model
        should do next, and saying so in the answer puts that on the screen.
        """
        with self._lock:
            if self._at >= len(self._turns):
                return {"say": "the script ended before the turn did"}
            turn = self._turns[self._at]
            self._at += 1
            return turn


def chunk(delta: dict, finish: str | None = None) -> bytes:
    """One `data:` line in the shape ServerSentEvent.decoding expects."""
    payload = {
        "id": "stub",
        "object": "chat.completion.chunk",
        "model": MODEL,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    return f"data: {json.dumps(payload)}\n\n".encode()


def stream_for(turn: dict) -> list[bytes]:
    """The whole body for one turn, as the lines it is sent as.

    Text is split across chunks on purpose. A stub that answers in one chunk
    never exercises the assembly the client does, and streaming is the thing
    most likely to be wrong.
    """
    lines: list[bytes] = []

    said = turn.get("say")
    if said:
        for word in said.split(" "):
            lines.append(chunk({"content": word + " "}))
        lines.append(chunk({}, finish="stop"))

    calls = turn.get("calls") or ([turn["call"]] if turn.get("call") else [])
    for index, call in enumerate(calls):
        # Split the same way a provider does: the fragment carrying the name
        # carries no arguments, so a client that reads only the first fragment
        # is caught here rather than on a device.
        lines.append(
            chunk(
                {
                    "tool_calls": [
                        {
                            "index": index,
                            "id": f"call_{index}",
                            "function": {"name": call["name"], "arguments": ""},
                        },
                    ],
                },
            ),
        )
        arguments = json.dumps(call.get("arguments", {}))
        lines.append(
            chunk(
                {
                    "tool_calls": [
                        {"index": index, "function": {"arguments": arguments}},
                    ],
                },
            ),
        )
    if calls:
        lines.append(chunk({}, finish="tool_calls"))

    lines.append(b"data: [DONE]\n\n")
    return lines


class Handler(BaseHTTPRequestHandler):
    """One request: record it, then answer it from the script."""

    script: Script
    log: pathlib.Path

    def do_GET(self) -> None:  # noqa: N802 - the base class names it
        """Answer `/v1/models`, which is the only GET anything here makes."""
        body = json.dumps(
            {"object": "list", "data": [{"id": MODEL, "object": "model"}]},
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802 - the base class names it
        """Record the request, then stream the next turn back."""
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8", "replace")

        # Appended before answering, so a request that makes the stub fall over
        # is still in the file that would say why.
        with self.log.open("a") as sink:
            sink.write(raw + "\n")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for line in stream_for(self.script.next_turn()):
            self.wfile.write(line)
            self.wfile.flush()

    def log_message(self, fmt: str, *args: object) -> None:
        """One line per request on stderr, so a silent app is distinguishable."""
        sys.stderr.write(f"stub-model: {fmt % args}\n")


def main() -> int:
    """Serve until interrupted. Returns the process exit code."""
    parsed = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parsed.add_argument("script", type=pathlib.Path, help="JSON file of turns")
    parsed.add_argument("--port", type=int, default=8099)
    parsed.add_argument(
        "--log",
        type=pathlib.Path,
        default=pathlib.Path("stub-requests.jsonl"),
        help="where each request body is appended",
    )
    args = parsed.parse_args()

    try:
        Handler.script = Script.load(args.script)
    except (OSError, ValueError, json.JSONDecodeError) as why:
        sys.stderr.write(f"stub-model: {why}\n")
        return 2
    Handler.log = args.log

    # Loopback: the emulator reaches the host through 10.0.2.2 whatever this
    # binds, so binding wider buys nothing and offers a model endpoint with no
    # authentication to the network.
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    except OSError as why:
        # Almost always a second copy still running. A traceback here reads as
        # a broken harness, and the next thing somebody does is doubt the
        # harness rather than kill the process holding the port.
        sys.stderr.write(
            f"stub-model: cannot listen on 127.0.0.1:{args.port}: {why}\n"
            f"stub-model: something else may already be serving it; "
            f"try `pkill -f stub-model.py`, or pass --port\n",
        )
        return 2
    sys.stderr.write(
        f"stub-model: {len(Handler.script._turns)} turn(s) on "  # noqa: SLF001
        f"127.0.0.1:{args.port}, logging to {args.log}\n",
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
