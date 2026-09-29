"""full_pipeline.py - the worked sample: read one tool, digest it, save it, publish it.

    python full_pipeline.py --tool google-gbp/list_reviews --args '{"location": "ChIJ..."}'
    python full_pipeline.py --tool google-gbp/list_reviews --args - < args.json \
        --summarize seo-title-semantics --save review-log --publish digest.md --json
    python full_pipeline.py --tool google-gbp/list_reviews --args '{}' --dry-run

One script, both places: inside a sandbox `session()` reaches the session hub, on a workstation it
drives `core-ai-cli`, and in the offline test it is a `FakeSession`. The stages:

    1. resolve    the tool in this session's catalog - a name that is not there is a usage error
    2. call       it, bounded; a call still running after --wait reports its task id (exit 6)
    3. digest     via an LLM_CALL definition (--summarize), or the raw text
    4. save       one record to a GENERAL dataset, or a patch to a SESSION one (--save)
    5. publish    the digest as a session artifact (--publish), public when --public
    6. report     the stages as text, or as one JSON object with --json

Exit codes: 0 ok, 1 a stage failed, 2 usage / unknown name, 3 not bound / no credentials.
"""

from __future__ import annotations

import argparse
import difflib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, Union

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

DIGEST_CHARS = 2000  # how much of the tool's answer the digest may read (a digest is not a copy)
DEFAULT_WAIT = 300  # seconds to wait for a pending task before reporting it as still running

try:  # Windows consoles are not always UTF-8; never let a print kill the report
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - a console without reconfigure still prints
    pass


class StageFailed(Exception):
    """A stage that cannot continue, with the exit code the failure deserves."""

    def __init__(self, message: str, code: int) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


def _arguments(raw: str) -> dict[str, Any]:
    """--args: a JSON object, or ``-`` to read one from stdin (long payloads never belong in argv)."""
    text = sys.stdin.read() if raw == "-" else raw
    if not text.strip():
        return {}
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("--args must be a JSON object")
    return parsed


def _resolve_tool(opened: Any, name: str) -> Any:
    """The catalog entry, by path, tool name or ref id - never a guess.

    The catalog is already in hand, so a "did you mean" hint costs no round trip.
    """
    entry = opened.find_entry(name)
    if entry is None:
        close = difflib.get_close_matches(name, [tool.path for tool in opened.catalog().tools],
                                          n=3, cutoff=0.3)
        hint = f"; did you mean: {', '.join(close)}" if close else ""
        raise StageFailed(f"'{name}' is not in this session's catalog{hint}", EXIT_USAGE)
    return entry


def _wait_for(task: Task, seconds: int) -> Any:
    """A pending call: wait a bounded time, then hand back whatever state it reached."""
    sys.stderr.write(f"still running after the call: task {task.task_id}, waiting up to {seconds}s\n")
    return task.wait(seconds)


def _call_tool(opened: Any, entry: Any, arguments: dict[str, Any], timeout: Optional[int],
               wait: int) -> Any:
    result = opened.call(entry.name, arguments, timeout=timeout, wait=False)
    if isinstance(result, Task):
        result = _wait_for(result, wait)
    if isinstance(result, Task) or (getattr(result, "status", "") == "pending"):
        task_id = getattr(result, "task_id", None) or "?"
        raise StageFailed(
            f"{entry.path} is still running (task {task_id}); poll it with `wait=False` and the "
            f"same call, or raise --wait",
            EXIT_RUNNING,
        )
    return result


def _summarize(opened: Any, definition: str, text: str) -> str:
    """Digest the answer with an LLM_CALL definition the agent mounts.

    `s.llm_call[name]` and `s.call(name, {...})` reach the same tool; the typed form is just the
    readable one when the kind is known. A definition the session does not mount is a usage error,
    not a silent fallback: the caller asked for it by name.
    """
    try:
        node = opened.llm_call[definition]
    except ToolNotFoundError:
        available = [tool.path for tool in opened.catalog().by_kind("llm_call")][:10]
        raise StageFailed(
            f"no llm_call named '{definition}' in this session"
            f" (mounted: {', '.join(available) or 'none'})",
            EXIT_USAGE,
        ) from None
    result = node(query=text[:DIGEST_CHARS])
    return result.text


def _save(opened: Any, ref: str, *, tool: str, digest: str) -> dict[str, Any]:
    """Write to a dataset binding, respecting the type the binding declares.

    SESSION datasets hold one state document per session, GENERAL datasets hold records. The SDK
    checks the type and the permission before the round trip and raises with the server's own
    sentence, so there is nothing to re-implement here.
    """
    try:
        node = opened.dataset[ref]
    except ToolNotFoundError as error:
        raise StageFailed(str(error), EXIT_USAGE) from None
    data = {
        "source_tool": tool,
        "digest": digest[:DIGEST_CHARS],
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    if (node.type or "").upper() == "SESSION":
        payload = node.patch(data)
        return {"dataset": node.name or node.dataset_id, "op": "state.patch", "result": payload}
    payload = node.insert(data)
    return {"dataset": node.name or node.dataset_id, "op": "records.insert", "result": payload}


def _publish(opened: Any, path: str, digest: str, *, title: Optional[str], public: bool) -> dict[str, Any]:
    """Write the digest to a file and publish it as a session artifact; the URL goes in the report."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(digest, encoding="utf-8")
    url = opened.files.publish(str(target), title=title or target.name, content_type="text/markdown",
                               public=public)
    return {"path": str(target), "url": url, "public": public}


def _report(args: argparse.Namespace, report: dict[str, Any]) -> None:
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
        return
    print(f"tool     : {report['tool']} ({report['duration_ms']} ms)")
    digest = report["digest"]
    print(f"digest   : {digest['chars']} chars"
          + (f", summarized by {digest['llm_call']}" if digest["llm_call"] else ", raw text"))
    if report["dataset"]:
        print(f"dataset  : {report['dataset']['dataset']} -> {report['dataset']['op']}")
    if report["artifact"]:
        print(f"artifact : {report['artifact']['url']}")
    if report["out"]:
        print(f"file     : {report['out']}")


def run(args: argparse.Namespace, opened: Any) -> dict[str, Any]:
    """The five stages, in order; raises `StageFailed` with the code the caller should exit with."""
    entry = _resolve_tool(opened, args.tool)
    result = _call_tool(opened, entry, _arguments(args.args), args.timeout, args.wait)

    text = result.text or ""
    digest = text
    llm_call: Optional[str] = None
    if args.summarize:
        digest = _summarize(opened, args.summarize, text)
        llm_call = args.summarize
    digest = digest.strip() or "(the tool returned no text)"

    report: dict[str, Any] = {
        "tool": entry.path,
        "call_id": result.call_id,
        "status": result.status,
        "duration_ms": result.duration_ms,
        "digest": {"chars": len(digest), "llm_call": llm_call},
        "dataset": None,
        "artifact": None,
        "out": None,
        "dry_run": args.dry_run,
    }

    if args.dry_run:
        return report
    if args.out:
        Path(args.out).write_text(digest, encoding="utf-8")
        report["out"] = args.out
    if args.save:
        report["dataset"] = _save(opened, args.save, tool=entry.path, digest=digest)
    if args.publish:
        report["artifact"] = _publish(opened, args.publish, digest, title=args.title, public=args.public)
    return report


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="full_pipeline.py",
        description="Call one tool, digest its answer, save a record and publish an artifact.",
    )
    parser.add_argument("--tool", required=True, help="tool path, e.g. google-gbp/list_reviews")
    parser.add_argument("--args", default="", help="JSON object for the tool; '-' reads it from stdin")
    parser.add_argument("--summarize", metavar="LLM_CALL", help="LLM_CALL definition to digest with")
    parser.add_argument("--save", metavar="DATASET", help="dataset binding (name or id) to write to")
    parser.add_argument("--publish", metavar="FILE", help="write the digest to FILE and publish it")
    parser.add_argument("--out", metavar="FILE", help="write the digest to FILE (no publish)")
    parser.add_argument("--title", help="artifact title (defaults to the file name)")
    parser.add_argument("--public", action="store_true", help="publish into public object storage")
    parser.add_argument("--dry-run", action="store_true", help="read and digest, but write nothing")
    parser.add_argument("--timeout", type=int, help="max seconds the tool call may run")
    parser.add_argument("--wait", type=int, default=DEFAULT_WAIT,
                        help=f"seconds to wait for a pending task (default {DEFAULT_WAIT})")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    return parser


def main(argv: Optional[list[str]] = None, *, opened: Optional[Any] = None) -> int:
    """`opened` is the seam the tests use: pass a session in and this script uses it as-is."""
    args = _parser().parse_args(argv)
    if args.publish and args.dry_run:
        print("--publish and --dry-run ask for opposite things", file=sys.stderr)
        return EXIT_USAGE
    active: Optional[Any] = opened
    try:
        if active is None:
            active = session()
        report = run(args, active)
    except StageFailed as error:
        print(error.message, file=sys.stderr)
        return error.code
    except NotBoundError as error:
        print(f"not bound: {error}", file=sys.stderr)
        return EXIT_NOT_BOUND
    except ToolNotFoundError as error:
        print(f"not in this session: {error}", file=sys.stderr)
        return EXIT_USAGE
    except ToolError as error:
        print(f"tool failed: {error.message}", file=sys.stderr)
        return EXIT_FAILED
    except (CoreAiSessionError, ValueError) as error:
        print(f"failed: {error}", file=sys.stderr)
        return EXIT_USAGE if isinstance(error, ValueError) else EXIT_FAILED
    finally:
        if opened is None and active is not None:
            active.close()
    _report(args, report)
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
