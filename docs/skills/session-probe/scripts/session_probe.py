"""Inspect this core-ai session: transport, identity, catalog, schemas, ad-hoc calls.

The same file runs in both places the SDK supports — inside a sandbox (through the
session hub) and on a workstation (through ``core-ai-cli``) — which is exactly what it
is for: the quickest way to see what the *current* session can reach, and to try a
call without hand-writing CLI syntax.

    python session_probe.py                            # transport, identity, catalog digest
    python session_probe.py --search elastic           # which tools match all words
    python session_probe.py --describe kubernetes/pods_list
    python session_probe.py --call kubernetes/namespaces_list --args '{}'
    python session_probe.py --call some/tool --args -  # JSON on stdin
    python session_probe.py --json                     # machine-readable form of any mode

Exit codes: 0 ok, 1 the tool failed, 2 usage / unknown tool, 3 not bound / no credentials.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from typing import Any, Optional

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NOT_BOUND = 3

MAX_TEXT = 4000
MAX_SCHEMA = 3000
MAX_LIST = 60

try:  # Windows consoles are not always UTF-8; never let a print kill the report
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from core_ai_session import (
        CoreAiSessionError,
        NotBoundError,
        Task,
        ToolError,
        ToolNotFoundError,
        session,
    )
except ImportError:  # guidance instead of a traceback
    print(
        "core_ai_session is not importable. Inside a sandbox it is preinstalled; on a "
        "workstation install it with `pip install core-ai-session`, or from a source "
        "checkout with `pip install -e sdk/core-ai-session`.",
        file=sys.stderr,
    )
    raise SystemExit(EXIT_NOT_BOUND)


def _head(text: str, limit: int) -> str:
    text = text or ""
    if len(text) <= limit:
        return text
    return text[:limit] + "\n… (%d more chars)" % (len(text) - limit)


def _overview(opened: Any) -> dict[str, Any]:
    identity = None
    try:
        info = opened.me()
        identity = {
            "sandbox_state": info.sandbox_state,
            "sandbox_id": info.sandbox_id,
            "agent_name": info.agent_name,
            "session_id": info.session_id,
            "tool_count": info.tool_count,
            "caller": info.caller,
        }
    except CoreAiSessionError:
        pass  # a hub may be unbound while the catalog still answers; identity stays empty
    catalog = opened.catalog()
    groups = Counter((entry.kind, entry.group) for entry in catalog.tools)
    return {
        "transport": type(opened).__name__,
        "backend": opened.backend,
        "identity": identity,
        "tools": len(catalog.tools),
        "kinds": dict(Counter(entry.kind for entry in catalog.tools)),
        "groups": [
            {"kind": kind, "group": group, "tools": count}
            for (kind, group), count in sorted(groups.items())
        ],
        "catalog_gaps": list(getattr(opened, "catalog_gaps", []) or []),
    }


def _search(opened: Any, words: str) -> dict[str, Any]:
    hits = opened.tools(query=words)
    return {
        "query": words,
        "count": len(hits),
        "tools": [
            {"path": entry.path, "kind": entry.kind, "group": entry.group,
             "description": entry.description}
            for entry in hits
        ],
    }


def _describe(opened: Any, name: str) -> dict[str, Any]:
    detail = opened.describe(name)
    return {
        "path": detail.path,
        "kind": detail.kind,
        "group": detail.group,
        "ref_id": detail.ref_id,
        "description": detail.description,
        "input_schema": detail.input_schema,
        "timeout_seconds": detail.timeout_seconds,
    }


def _call(opened: Any, name: str, raw_args: str, timeout: Optional[int]) -> dict[str, Any]:
    arguments: dict[str, Any] = {}
    if raw_args.strip():
        arguments = json.loads(raw_args)
        if not isinstance(arguments, dict):
            raise ValueError("--args must be a JSON object")
    controls = {"timeout": timeout} if timeout else {}
    result = opened.call(name, arguments, **controls)
    if isinstance(result, Task):
        return {"status": result.status, "task_id": result.task_id, "tool": result.tool,
                "duration_ms": 0, "text": "", "is_error": False,
                "content_parts": 0}
    return {
        "status": result.status,
        "duration_ms": result.duration_ms,
        "text": result.text,
        "is_error": result.is_error,
        "content_parts": len(result.content),
    }


def _print_human(args: argparse.Namespace, payload: Any) -> None:
    if args.search is not None:
        for item in payload["tools"][:MAX_LIST]:
            description = (item.get("description") or "").replace("\n", " ")
            print("%-58s %-9s %s" % (item["path"], item["kind"], description[:70]))
        print("— %d match(es)" % payload["count"])
        if payload["count"] > MAX_LIST:
            print("… %d more not shown" % (payload["count"] - MAX_LIST))
        return
    if args.describe is not None:
        print("%s (%s, group=%s)" % (payload["path"], payload["kind"], payload["group"] or "-"))
        print(payload["description"] or "(no description)")
        schema = json.dumps(payload["input_schema"] or {}, ensure_ascii=False, indent=2)
        print(_head(schema, MAX_SCHEMA))
        return
    if args.call is not None:
        print("status=%s duration_ms=%s content_parts=%s" % (
            payload["status"], payload["duration_ms"], payload["content_parts"]))
        print(_head(payload["text"] or "(no text)", MAX_TEXT))
        return
    print("transport: %s (backend=%s)" % (payload["transport"], payload["backend"]))
    identity = payload.get("identity") or {}
    if identity:
        caller = identity.get("caller") or {}
        print("identity : sandbox_state=%s user=%s agent=%s" % (
            identity.get("sandbox_state") or "-",
            caller.get("user") or caller.get("source") or "-",
            identity.get("agent_name") or "-"))
    print("catalog  : %d tools %s" % (payload["tools"], payload["kinds"]))
    for row in payload["groups"]:
        print("   %-9s %-26s %d" % (row["kind"], row["group"] or "-", row["tools"]))
    gaps = payload.get("catalog_gaps") or []
    print("catalog_gaps: %s" % ("; ".join(gaps) if gaps else "(none)"))


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="session_probe.py",
        description="Probe this core-ai session (the same command works in a sandbox and locally).",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--search", metavar="WORDS", help="list catalog tools matching all words")
    mode.add_argument("--describe", metavar="SERVER/TOOL", help="show one tool's input_schema")
    mode.add_argument("--call", metavar="SERVER/TOOL", help="call one tool (describe it first)")
    parser.add_argument("--args", default="", help="JSON object for --call; '-' reads it from stdin")
    parser.add_argument("--timeout", type=int, default=None, help="max seconds the call may run")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = parser.parse_args(argv)

    if (args.args or args.timeout) and args.call is None:
        print("--args/--timeout only make sense together with --call", file=sys.stderr)
        return EXIT_USAGE

    try:
        opened = session()
        if args.search is not None:
            payload: Any = _search(opened, args.search)
        elif args.describe is not None:
            payload = _describe(opened, args.describe)
        elif args.call is not None:
            raw = sys.stdin.read() if args.args == "-" else args.args
            payload = _call(opened, args.call, raw, args.timeout)
        else:
            payload = _overview(opened)
    except NotBoundError as error:
        print("not bound: %s" % error, file=sys.stderr)
        return EXIT_NOT_BOUND
    except ToolNotFoundError as error:
        print("unknown tool: %s" % error, file=sys.stderr)
        return EXIT_USAGE
    except (ToolError, CoreAiSessionError) as error:
        print("failed: %s" % error, file=sys.stderr)
        return EXIT_FAILED
    except (ValueError, TypeError) as error:
        print("bad arguments: %s" % error, file=sys.stderr)
        return EXIT_USAGE

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    else:
        _print_human(args, payload)
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
