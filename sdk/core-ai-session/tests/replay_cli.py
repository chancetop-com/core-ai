"""Replays the recorded ``core-ai-cli`` envelopes from ``sdk/core-ai-session/contract-fixtures/recorded/``.

    CORE_AI_CLI_RECORDED=sdk/core-ai-session/contract-fixtures/recorded \
        python tests/replay_cli.py mcp describe MongoDB/list-databases --json

Why a second fake CLI: `fake_cli.py` *models* the CLI — it knows commands, flags and statuses and
answers with payloads built to match — while this one *replays captured bytes*: stdout, stderr and
the exit code are exactly what the real CLI printed against a real hub, anonymised by
`curate_recordings.py`. A test that must pin "how the SDK reads what the CLI really sends" drives
this one, so the assertion is about the wire rather than about our model of it.

Matching: the command (first two positionals) and the flags that decide *which* resources come
back — ``--on-server``, ``--on-app``, ``--type`` — must be equal, and if the recording carries a
third positional (a tool, an app operation or a task id) that must match too. ``--timeout``,
``--max-output``, ``--limit``, ``--args-file``, ``--json`` and stdin are ignored: they do not decide
which envelope answers. When nothing matches, the replay refuses loudly (exit 78) instead of
inventing an answer, so a test cannot silently pass on a request the recordings never saw.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

RECORDED_ENV = "CORE_AI_CLI_RECORDED"
DEFAULT_DIR = Path(__file__).resolve().parents[1] / "contract-fixtures" / "recorded"
SELECTOR_FLAGS = ("on-server", "on-app", "type")
BOOLEAN_FLAGS = {"json", "raw", "quiet", "detach", "insecure", "help", "version", "stale"}
NO_RECORDING_EXIT = 78  # EX_CONFIG: this replay has no answer for that request


def split_argv(argv: list[str]) -> tuple[list[str], dict[str, str]]:
    positionals: list[str] = []
    flags: dict[str, str] = {}
    index = 0
    while index < len(argv):
        token = argv[index]
        if token.startswith("--"):
            name, equals, value = token[2:].partition("=")
            if equals or name in BOOLEAN_FLAGS:
                flags[name] = value or "true"
                index += 1
            else:
                flags[name] = argv[index + 1] if index + 1 < len(argv) else ""
                index += 2
        else:
            positionals.append(token)
            index += 1
    return positionals, flags


def matches(recorded_argv: list[str], argv: list[str]) -> bool:
    want_positionals, want_flags = split_argv([str(part) for part in recorded_argv])
    got_positionals, got_flags = split_argv(argv)
    if want_positionals[:2] != got_positionals[:2]:
        return False
    if len(want_positionals) > 2 and (len(got_positionals) < 3 or want_positionals[2] != got_positionals[2]):
        return False
    return all(want_flags.get(flag, "") == got_flags.get(flag, "") for flag in SELECTOR_FLAGS)


def load(directory: Path) -> list[dict]:
    recordings = []
    for path in sorted(directory.glob("*.json")):
        if path.name == "manifest.json":
            continue
        recordings.append(json.loads(path.read_text(encoding="utf-8")))
    return recordings


def captured_version(directory: Path) -> str:
    manifest = directory / "manifest.json"
    if manifest.exists():
        captured = json.loads(manifest.read_text(encoding="utf-8")).get("captured") or {}
        return str(captured.get("version") or "")
    return ""


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    directory = Path(os.environ.get(RECORDED_ENV) or DEFAULT_DIR)
    argv = sys.argv[1:]
    if argv[:1] in (["--version"], ["-V"]):
        print(captured_version(directory) or "unknown")
        return 0
    if not argv:
        print(f"replay_cli: no arguments; set {RECORDED_ENV} or pass an argv to replay", file=sys.stderr)
        return NO_RECORDING_EXIT
    for recording in load(directory):
        if not matches(recording.get("argv") or [], argv):
            continue
        if recording.get("stdout") is not None:
            sys.stdout.write(json.dumps(recording["stdout"], ensure_ascii=False, separators=(",", ":")) + "\n")
        if recording.get("stderr"):
            sys.stderr.write(str(recording["stderr"]))
        return int(recording.get("exit") or 0)
    print(f"replay_cli: no recording for: {' '.join(argv)} (in {directory})", file=sys.stderr)
    return NO_RECORDING_EXIT


if __name__ == "__main__":
    raise SystemExit(main())
