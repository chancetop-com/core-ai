"""main.py - REPLACE: one sentence on what this script does, plus the command that runs it.

    python scripts/main.py --search "reviews"
    python scripts/main.py --describe google-gbp/list_reviews
    python scripts/main.py --tool google-gbp/list_reviews --args '{"location": "ChIJ..."}' --json

Runs unchanged in both places: `session()` picks the transport (the sandbox's session hub, or
core-ai-cli on a workstation) and the script never holds a credential. Exit codes: 0 ok, 1 the tool
failed, 2 usage / unknown name, 3 not bound / no credentials.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Optional

from core_ai_session import (
    CoreAiSessionError,
    NotBoundError,
    Task,
    ToolError,
    ToolNotFoundError,
    session,
)

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NOT_BOUND = 3
EXIT_RUNNING = 6

DEFAULT_WAIT = 120


def _arguments(raw: str) -> dict[str, Any]:
    """--args: a JSON object, or '-' to read one from stdin."""
    text = sys.stdin.read() if raw == "-" else raw
    if not text.strip():
        return {}
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("--args must be a JSON object")
    return parsed


def _call(opened: Any, name: str, arguments: dict[str, Any], timeout: Optional[int],
          wait: int) -> Any:
    """One call; a still-running call becomes a task we describe instead of blocking forever."""
    result = opened.call(name, arguments, timeout=timeout, wait=False)
    if isinstance(result, Task):
        result = result.wait(wait)
    if isinstance(result, Task) or result.status == "pending":
        return None
    return result


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="main.py", description="REPLACE: what this does.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--search", metavar="WORDS", help="tools matching all words")
    mode.add_argument("--describe", metavar="PATH", help="one tool's description and input_schema")
    mode.add_argument("--tool", metavar="PATH", help="call one tool (describe it first)")
    parser.add_argument("--kind", help="--search: mcp | api | llm_call | agent | builtin")
    parser.add_argument("--args", default="", help="JSON object for --tool; '-' reads it from stdin")
    parser.add_argument("--timeout", type=int, help="max seconds the call may run in the session")
    parser.add_argument("--wait", type=int, default=DEFAULT_WAIT,
                        help=f"seconds to wait for a pending task (default {DEFAULT_WAIT})")
    parser.add_argument("--dry-run", action="store_true", help="read only, write nothing")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    return parser


def main(argv: Optional[list[str]] = None, *, opened: Optional[Any] = None) -> int:
    args = _parser().parse_args(argv)
    active: Optional[Any] = opened
    try:
        if active is None:
            active = session()
        if args.search is not None:
            payload: dict[str, Any] = {"query": args.search, "tools": [
                {"path": tool.path, "kind": tool.kind, "description": tool.description}
                for tool in active.tools(query=args.search, kind=args.kind)]}
        elif args.describe is not None:
            detail = active.describe(args.describe)
            payload = {"path": detail.path, "kind": detail.kind, "description": detail.description,
                       "input_schema": detail.input_schema}
        else:
            result = _call(active, args.tool, _arguments(args.args), args.timeout, args.wait)
            if result is None:
                print("the call is still running; poll it again with the same arguments",
                      file=sys.stderr)
                return EXIT_RUNNING
            payload = {"tool": args.tool, "status": result.status, "duration_ms": result.duration_ms,
                       "text": result.text}
    except NotBoundError as error:
        print(f"not bound: {error}", file=sys.stderr)
        return EXIT_NOT_BOUND
    except ToolNotFoundError as error:
        print(f"unknown name: {error}", file=sys.stderr)
        return EXIT_USAGE
    except ToolError as error:
        print(f"tool failed: {error.message}", file=sys.stderr)
        return EXIT_FAILED
    except (CoreAiSessionError, ValueError) as error:
        print(f"failed: {error}", file=sys.stderr)
        return EXIT_FAILED if isinstance(error, CoreAiSessionError) else EXIT_USAGE
    finally:
        if opened is None and active is not None:
            active.close()

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    else:
        print(payload.get("text") or json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
