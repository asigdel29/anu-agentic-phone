"""Download the routing training set and reduce it to prompts and labels.

History
    2026-08-05  A. Sigdel  Created.

Contents
    main  Writes prompts.jsonl from the upstream dataset.

Source is ``routellm/gpt4_judge_battles``: prompts judged between a strong model
(``model_a``, gpt-4-1106-preview) and a weak one (``model_b``, mixtral-8x7b). The
label is P(strong wins), which is exactly the quantity the router thresholds.

Only the prompt and the label survive. The responses are not needed and are the
bulk of the download, so they are dropped rather than written out.

Worth stating plainly: these are 2024 preferences over 2024 models, and the tiers
this feeds are 2026 models. What transfers is the ordering of prompts by
difficulty, not any absolute judgement. Thresholds need retuning against real
traffic regardless.

Result of the first run, recorded because it is load-bearing: a head fitted on
these labels over hash embeddings does not separate the classes. See the header
of ``router/src/bin/train-head.rs``.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

DATASET = "routellm/gpt4_judge_battles"
OUT = Path(__file__).parent / "prompts.jsonl"


def label_of(row: dict) -> float:
    """P(strong model wins) for one judged battle.

    A tie is 0.5 rather than discarded: it is evidence that the weak model was
    sufficient, which is precisely what the cheap tier is for, and dropping ties
    would bias the head towards routing everything upward.
    """
    if row["winner_model_a"]:
        return 1.0
    if row["winner_model_b"]:
        return 0.0
    return 0.5


def text_of(row: dict) -> str:
    """The prompt, which upstream stores as a JSON-encoded list of turns.

    Only the first turn is kept. The router scores the current question, so
    training on a concatenation of turns would fit a distribution it never sees.
    """
    raw = row["prompt"]
    try:
        turns = json.loads(raw)
        return str(turns[0]) if isinstance(turns, list) and turns else str(raw)
    except (json.JSONDecodeError, TypeError):
        return str(raw)


def main() -> int:
    """Write prompts.jsonl. Returns a process exit code."""
    try:
        from datasets import load_dataset
    except ImportError:
        print("needs `datasets`: uv run --with datasets train/fetch_dataset.py", file=sys.stderr)
        return 1

    rows = load_dataset(DATASET, split="train")
    written = 0
    with OUT.open("w", encoding="utf-8") as out:
        for row in rows:
            text = text_of(row).strip()
            # A prompt with no words cannot be embedded, and one word carries no
            # signal worth fitting against.
            if len(text.split()) < 2:
                continue
            out.write(json.dumps({"text": text, "label": label_of(row)}) + "\n")
            written += 1

    print(f"wrote {written} examples to {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
