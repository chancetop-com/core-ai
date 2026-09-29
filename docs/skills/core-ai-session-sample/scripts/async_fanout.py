"""async_fanout.py - call several tools at once with `AsyncSession`.

    python async_fanout.py --calls '[{"tool": "a/list", "args": {}}, {"tool": "b/list"}]'
    python async_fanout.py --calls - < calls.json --save review-log --json

The async session mirrors the synchronous one (`await s.call(...)`, `await s.dataset[...].insert(...)`)
and adds what a script reaches for when one call at a time is too slow: `asyncio.gather` runs them
concurrently on one session, so N independent reads cost roughly one call's wall time, not N.

A failure is data here: each call reports its own outcome, and one bad tool does not cancel the rest.
`--save` writes one aggregate record to a GENERAL dataset once every call has settled.

Exit codes: 0 every call succeeded, 1 at least one failed, 2 usage, 3 not bound / no credentials.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from typing import Any, Optional

from core_ai_session import (
    CoreAiSessionError,
    NotBoundError,
    ToolError,
    ToolNotFoundError,
    async_session,
)

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NOT_BOUND = 3

MAX_TEXT = 400

try:  # Windows consoles are not always UTF-8; never let a print kill the report
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - a console without reconfigure still prints
    pass


def _call_specs(raw: str) -> list[dict[str, Any]]:
    """--calls: a JSON array of {"tool": "...", "args": {...}}; '-' reads it from stdin."""
    text = sys.stdin.read() if raw == "-" else raw
    parsed = json.loads(text)
    if not isinstance(parsed, list) or not parsed:
        raise ValueError("--calls must be a non-empty JSON array")
    specs = []
    for item in parsed:
        if not isinstance(item, dict) or not item.get("tool"):
            raise ValueError('each call must look like {"tool": "server/tool", "args": {...}}')
        specs.append({"tool": str(item["tool"]), "args": item.get("args") or {}})
    return specs


async def _one(opened: Any, spec: dict[str, Any], timeout: Optional[int]) -> dict[str, Any]:
    """One call; its failure is a field in the row, not an exception that stops the gather."""
    started = time.perf_counter()
    try:
        result = await opened.call(spec["tool"], spec["args"], timeout=timeout)
        return {
            "tool": spec["tool"],
            "status": getattr(result, "status", "completed"),
            "duration_ms": round((time.perf_counter() - started) * 1000),
            "text": (getattr(result, "text", "") or "")[:MAX_TEXT],
            "is_error": bool(getattr(result, "is_error", False)),
        }
    except ToolNotFoundError as error:
        return {"tool": spec["tool"], "status": "missing", "duration_ms": 0, "text": str(error),
                "is_error": True}
    except ToolError as error:
        return {"tool": spec["tool"], "status": "failed", "duration_ms": 0, "text": error.message,
                "is_error": True}


async def fan_out(opened: Any, specs: list[dict[str, Any]], timeout: Optional[int]) -> dict[str, Any]:
    started = time.perf_counter()
    rows = await asyncio.gather(*(_one(opened, spec, timeout) for spec in specs))
    wall_ms = round((time.perf_counter() - started) * 1000)
    return {
        "calls": list(rows),
        "wall_ms": wall_ms,
        "sum_ms": sum(row["duration_ms"] for row in rows),
        "failed": sum(1 for row in rows if row["is_error"]),
    }


async def run(args: argparse.Namespace, opened: Any) -> tuple[dict[str, Any], Optional[dict[str, Any]]]:
    report = await fan_out(opened, _call_specs(args.calls), args.timeout)
    saved = None
    if args.save and not args.dry_run:
        node = opened.dataset[args.save]
        data = {
            "calls": [{"tool": row["tool"], "status": row["status"]} for row in report["calls"]],
            "failed": report["failed"],
            "wall_ms": report["wall_ms"],
        }
        payload = await node.insert(data)
        saved = {"dataset": node.name or node.dataset_id, "op": "records.insert", "result": payload}
    return report, saved


def _print_report(report: dict[str, Any], saved: Optional[dict[str, Any]]) -> None:
    for row in report["calls"]:
        mark = "!" if row["is_error"] else " "
        print(f"{mark} {row['tool']:<40} {row['status']:<9} {row['duration_ms']:>7} ms  "
              f"{row['text'].splitlines()[0][:60] if row['text'] else ''}")
    print(f"— {len(report['calls'])} call(s), {report['failed']} failed, "
          f"wall {report['wall_ms']} ms vs sum {report['sum_ms']} ms")
    if saved:
        print(f"dataset: {saved['dataset']} -> {saved['op']}")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="async_fanout.py",
        description="Run several tool calls concurrently on one AsyncSession.",
    )
    parser.add_argument("--calls", required=True, help='JSON array of {"tool", "args"}; "-" reads stdin')
    parser.add_argument("--save", metavar="DATASET", help="GENERAL dataset to write the summary to")
    parser.add_argument("--timeout", type=int, help="max seconds each call may run in the session")
    parser.add_argument("--dry-run", action="store_true", help="call and report, but write nothing")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    return parser


async def main_async(argv: Optional[list[str]] = None, *, opened: Optional[Any] = None) -> int:
    """The async entry point: one session, one loop; the tests await this one directly."""
    args = _parser().parse_args(argv)
    try:
        if opened is not None:
            report, saved = await run(args, opened)
        else:
            report, saved = await _open_and_run(args)
    except NotBoundError as error:
        print(f"not bound: {error}", file=sys.stderr)
        return EXIT_NOT_BOUND
    except ToolNotFoundError as error:
        print(f"unknown name: {error}", file=sys.stderr)
        return EXIT_USAGE
    except ToolError as error:
        print(f"failed: {error.message}", file=sys.stderr)
        return EXIT_FAILED
    except (CoreAiSessionError, ValueError, TypeError) as error:
        print(f"failed: {error}", file=sys.stderr)
        return EXIT_FAILED if isinstance(error, CoreAiSessionError) else EXIT_USAGE
    if args.json:
        print(json.dumps({"report": report, "dataset": saved}, ensure_ascii=False, indent=2, default=str))
    else:
        _print_report(report, saved)
    return EXIT_FAILED if report["failed"] else EXIT_OK


def main(argv: Optional[list[str]] = None, *, opened: Optional[Any] = None) -> int:
    """What the shell runs: one event loop around `main_async`."""
    return asyncio.run(main_async(argv, opened=opened))


async def _open_and_run(args: argparse.Namespace) -> tuple[dict[str, Any], Optional[dict[str, Any]]]:
    opened = await async_session()
    try:
        return await run(args, opened)
    finally:
        await opened.aclose()


if __name__ == "__main__":
    raise SystemExit(main())
