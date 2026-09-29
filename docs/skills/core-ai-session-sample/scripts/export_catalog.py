"""export_catalog.py - freeze a real session's catalog into JSON for offline tests.

    python export_catalog.py --out catalog.json --kind mcp            # toolbox a sandbox exposes
    python export_catalog.py --out catalog.json --details --limit 5   # + input_schema per tool
    python export_catalog.py --tool google-gbp/list_reviews --details

`FakeSession` replays a catalog you give it, so a test exercises the same namespaces, schemas and
dataset bindings production has. Export once per agent, check the file in, and the offline suite
stops guessing at names:

    fake = FakeSession(catalog=json.loads(Path("catalog.json").read_text()),
                       details=json.loads(Path("details.json").read_text()))

Export inside a sandbox for the agent's real mounts; a local export is whatever *your* user can see
(the same scripts, different reach - see the skill's SKILL.md).

Exit codes: 0 ok, 2 usage, 3 not bound / no credentials.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Optional

from core_ai_session import CoreAiSessionError, NotBoundError, ToolNotFoundError, session

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NOT_BOUND = 3

DEFAULT_DETAIL_LIMIT = 25

try:  # Windows consoles are not always UTF-8; never let a print kill the report
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - a console without reconfigure still prints
    pass


def _tool_payload(entry: Any) -> dict[str, Any]:
    return {"name": entry.name, "kind": entry.kind, "group": entry.group, "path": entry.path,
            "ref_id": entry.ref_id, "description": entry.description, "exposure": entry.exposure}


def _dataset_payload(info: Any) -> dict[str, Any]:
    return {
        "dataset_id": info.dataset_id,
        "name": info.name,
        "type": info.type,
        "permission": info.permission,
        "description": info.description,
        "schema": [{"name": field.name, "type": field.type, "required": field.required,
                    "description": field.description} for field in info.schema],
    }


def _selected(opened: Any, args: argparse.Namespace) -> list[Any]:
    if args.tool:
        entry = opened.find_entry(args.tool)
        if entry is None:
            raise ToolNotFoundError(f"'{args.tool}' is not in this session's catalog")
        return [entry]
    return opened.tools(query=args.query, kind=args.kind)


def catalog_payload(opened: Any, tools: list[Any]) -> dict[str, Any]:
    """The payload shape `/catalog` answers with, rebuilt from the parsed objects."""
    catalog = opened.catalog()
    counts: dict[tuple[str, str], int] = {}
    for entry in tools:
        key = (entry.kind, entry.group or "")
        counts[key] = counts.get(key, 0) + 1
    payload = {
        "session_id": catalog.session_id,
        "agent_name": catalog.agent_name,
        "sandbox_id": catalog.sandbox_id,
        "sandbox_state": catalog.sandbox_state,
        "expires_at": catalog.expires_at,
        "groups": [{"kind": kind, "group": group, "path": group, "count": count}
                   for (kind, group), count in counts.items()],
        "tools": [_tool_payload(entry) for entry in tools],
        "datasets": [_dataset_payload(info) for info in catalog.datasets],
    }
    return payload


def details_payload(opened: Any, tools: list[Any], limit: int) -> list[dict[str, Any]]:
    details = []
    for entry in tools[:limit]:
        detail = opened.describe(entry.path)
        details.append({"name": detail.name, "kind": detail.kind, "group": detail.group,
                        "path": detail.path, "ref_id": detail.ref_id,
                        "description": detail.description, "input_schema": detail.input_schema,
                        "timeout_seconds": detail.timeout_seconds})
    if len(tools) > limit:
        sys.stderr.write(f"only the first {limit} of {len(tools)} tools were described "
                         f"(raise --limit to export more)\n")
    return details


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="export_catalog.py",
        description="Export this session's catalog (and optionally tool schemas) for offline tests.",
    )
    parser.add_argument("--out", default="catalog.json", help="catalog file (default catalog.json)")
    parser.add_argument("--details", metavar="FILE", help="also write tool details to FILE")
    parser.add_argument("--limit", type=int, default=DEFAULT_DETAIL_LIMIT,
                        help=f"most tools to describe with --details (default {DEFAULT_DETAIL_LIMIT})")
    parser.add_argument("--query", help="only tools matching all words")
    parser.add_argument("--kind", help="only this kind: mcp | api | llm_call | agent | builtin")
    parser.add_argument("--tool", help="only this tool path")
    return parser


def main(argv: Optional[list[str]] = None, *, opened: Optional[Any] = None) -> int:
    args = _parser().parse_args(argv)
    active: Optional[Any] = opened
    try:
        if active is None:
            active = session()
        tools = _selected(active, args)
        Path(args.out).write_text(
            json.dumps(catalog_payload(active, tools), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"wrote {len(tools)} tool(s) to {args.out}")
        if args.details:
            details = details_payload(active, tools, max(1, args.limit))
            Path(args.details).write_text(json.dumps(details, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")
            print(f"wrote {len(details)} detail(s) to {args.details}")
    except NotBoundError as error:
        print(f"not bound: {error}", file=sys.stderr)
        return EXIT_NOT_BOUND
    except ToolNotFoundError as error:
        print(f"unknown name: {error}", file=sys.stderr)
        return EXIT_USAGE
    except CoreAiSessionError as error:
        print(f"failed: {error}", file=sys.stderr)
        return EXIT_FAILED
    finally:
        if opened is None and active is not None:
            active.close()
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
