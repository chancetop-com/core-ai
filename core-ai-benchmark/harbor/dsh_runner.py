"""Run one DeepSeek Harness turn inside a task container."""

from __future__ import annotations

import json
import os
import pathlib
import sys

from deepseek_harness import DeepSeekHarness

HOME_DIR = os.environ.get("DSH_HOME_DIR", "/tmp/dsh/home")
MODEL = os.environ.get("DSH_MODEL", "deepseek-flash")
EFFORT = os.environ.get("DSH_EFFORT", "max") or None
MAX_TOKENS = int(os.environ.get("DSH_MAX_TOKENS", "65536"))
CWD = os.environ.get("DSH_CWD", "/app")
REQUEST_TIMEOUT = float(os.environ.get("DSH_REQUEST_TIMEOUT", "1800"))
PROMPT_PATH = pathlib.Path("/tmp/dsh/instruction.md")
KEY_PATH = pathlib.Path("/opt/dsh/key")
PATCH_PATH = "/opt/dsh/patch.yml"


def main() -> int:
    prompt = PROMPT_PATH.read_text(encoding="utf-8")
    api_key = KEY_PATH.read_text(encoding="utf-8").strip()

    with DeepSeekHarness(
        provider="core-ai",
        model=MODEL,
        reasoning_effort=EFFORT,
        max_tokens=MAX_TOKENS,
        cwd=CWD,
        dsh_home=HOME_DIR,
        profile="sdk-minimal",
        patches=(PATCH_PATH,),
        base_url="http://127.0.0.1:18080/api/cli/v1",
        api_key=api_key,
        request_timeout_seconds=REQUEST_TIMEOUT,
    ) as harness:
        result = harness.run(prompt, session_id="dsh-1")

    input_tokens, output_tokens, cache_tokens = _usage_from_sessions(HOME_DIR)
    if not input_tokens:
        input_tokens, output_tokens, cache_tokens = _walk_usage(result.events)

    print(result.final_response or "")
    print(f"DSH_FINISH {result.finish_reason}")
    print(f"DSH_USAGE {input_tokens} {output_tokens} {cache_tokens}")
    return 0


def _walk_usage(payload) -> tuple[int, int, int]:
    """Sum every usage object regardless of where the runtime nests it."""
    total = [0, 0, 0]

    def visit(node):
        if isinstance(node, dict):
            if "inputTokens" in node or "outputTokens" in node:
                total[0] += int(node.get("inputTokens") or 0)
                total[1] += int(node.get("outputTokens") or 0)
                total[2] += int(node.get("cacheReadTokens") or 0)
                return
            for value in node.values():
                visit(value)
        elif isinstance(node, list):
            for item in node:
                visit(item)

    visit(payload)
    return total[0], total[1], total[2]


def _usage_from_sessions(home: str) -> tuple[int, int, int]:
    """Sum provider usage from the persisted session log."""
    total = [0, 0, 0]
    for path in pathlib.Path(home).glob("sessions/**/session.v4.jsonl"):
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for line in lines:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            add = _walk_usage(event)
            total[0] += add[0]
            total[1] += add[1]
            total[2] += add[2]
    return total[0], total[1], total[2]


if __name__ == "__main__":
    raise SystemExit(main())
