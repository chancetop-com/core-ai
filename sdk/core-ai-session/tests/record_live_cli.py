"""Record the *real* ``core-ai-cli`` exchanges that ``sdk/core-ai-session/contract-fixtures/recorded/`` comes from.

Not part of the test suite: it needs a logged-in CLI and the tools it names, so it cannot run in CI.
Its job is to keep the recorded contract honest — a recording is the CLI's own bytes, so an envelope
that drifts away from what the SDK parses shows up as a failing replay test rather than as a bug in
the field.

    python sdk/core-ai-session/tests/record_live_cli.py --record /tmp/exchanges
    python sdk/core-ai-session/tests/record_live_cli.py --record /tmp/exchanges \\
        --call kubernetes_namespaces_list          # name the tool to call (a real call!)
    python sdk/core-ai-session/tests/record_live_cli.py --record /tmp/exchanges \\
        --run-agent brand_website_seo              # opt-in: runs an agent, which costs tokens

What it does, in order: loads the catalog (every discovery command), describes a few read-only tools,
makes **one** read-only call (opt-in by name, auto-picked when omitted), and finally probes the error
paths that need no credentials to reproduce (unknown tool, unknown subcommand, unknown run). It never
writes to the workspace: recordings are your own tool output, so keep them out of the repository and
curate a redacted subset with `curate_recordings.py`.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from core_ai_session import CliSession, ToolError  # noqa: E402
from core_ai_session.errors import CoreAiSessionError  # noqa: E402

# Read-only tools, in preference order: the first one whose schema needs no argument is called.
SAFE_CALLS = [
    "kubernetes/namespaces_list",
    "kubernetes/pods_list",
    "elasticsearch/list_indices",
    "google-gbp/get_reviews",
    "google-gbp/list_accounts",
    "MongoDB/list-databases",
]
# Described (schema only, no call) so the recorded set carries a real `input_schema` string.
DESCRIBE = [
    "kubernetes/namespaces_list",
    "google-gbp/get_reviews",
    "MongoDB/list-databases",
    "elasticsearch/list_indices",
]
# `s._require` raises for a non-zero exit; the point here is the recording, not the answer.
ERROR_PROBES: list[tuple[list[str], str]] = [
    (["mcp", "describe", "no-such-server/no-such-tool"], ""),
    (["mcp", "call", "no-such-server/no-such-tool", "--args-file", "-"], "{}"),
    (["api-tool", "describe", "no-such-app/no-such-operation"], ""),
    (["definitely-not-a-command"], ""),
    (["agent", "status", "00000000000000000000000000000000"], ""),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--record", required=True, metavar="DIR", help="directory to record into (keep it out of the repo)")
    parser.add_argument("--cli", default=None, help="path to core-ai-cli (default: $CORE_AI_CLI or PATH)")
    parser.add_argument("--call", default=None, metavar="NAME", help="the read-only tool to call for the success envelope")
    parser.add_argument("--run-agent", default=None, metavar="NAME",
                        help="also run this agent/llm_call (opt-in: it costs tokens and takes time)")
    return parser.parse_args()


def probe(session: CliSession, args: list[str], stdin: str = "") -> None:
    """One expected-to-fail command, recorded through the same choke point a real call uses."""
    try:
        payload = session._require(args, stdin or None)
        print(f"  {args[0]} {args[1] if len(args) > 1 else '':22} -> exit 0 {json.dumps(payload)[:80]}")
    except CoreAiSessionError as error:
        print(f"  {args[0]} {args[1] if len(args) > 1 else '':22} -> {type(error).__name__}: {str(error)[:90]}")


def pick_call(session: CliSession, catalog_names: list[str], wanted: str | None) -> str | None:
    """The first candidate whose schema declares no required argument, so a real call can succeed."""
    for name in ([wanted] if wanted else SAFE_CALLS):
        if name not in catalog_names:
            continue
        try:
            detail = session.describe(name)
        except CoreAiSessionError as error:
            print(f"  describe {name} failed: {error}")
            continue
        if wanted or not (detail.input_schema or {}).get("required"):
            return name
    return None


def bogus_arguments(schema: dict) -> dict:
    """Every required property, filled with a plausible value for its type: the call must *reach* the
    hub and fail there, because a local validation error never asks the CLI anything."""
    filler = {"string": "no-such-value", "integer": 1, "number": 1, "boolean": False,
              "array": ["no-such-value"], "object": {}}
    arguments: dict[str, object] = {}
    for name in (schema or {}).get("required") or []:
        declared = ((schema or {}).get("properties") or {}).get(name) or {}
        arguments[name] = filler.get(str(declared.get("type") or "string"), "no-such-value")
    return arguments


def main() -> int:
    options = parse_args()
    cli = shlex.split(options.cli) if options.cli else None
    directory = Path(options.record).expanduser()
    directory.mkdir(parents=True, exist_ok=True)
    print(f"recording into {directory}\n")

    session = CliSession(cli=cli, record=str(directory))
    try:
        catalog = session.refresh()
        names = {entry.path for entry in catalog.tools}
        by_kind: dict[str, int] = {}
        for entry in catalog.tools:
            by_kind[entry.kind] = by_kind.get(entry.kind, 0) + 1
        print(f"catalog: {len(catalog.tools)} tools {by_kind}")

        print("\ndescribe:")
        for name in DESCRIBE:
            if name not in names:
                continue
            try:
                detail = session.describe(name)
                required = (detail.input_schema or {}).get("required") or []
                print(f"  {name}: timeout={detail.timeout_seconds} required={required}")
            except CoreAiSessionError as error:
                print(f"  {name}: {type(error).__name__}: {str(error)[:90]}")
        # one api operation per app: `api-tool describe` is where an operation's schema shows up as
        # JSON text, and curation keeps exactly one operation per app
        seen_apps: set[str] = set()
        for entry in catalog.tools:
            if entry.kind != "api" or entry.group in seen_apps:
                continue
            seen_apps.add(entry.group)
            try:
                detail = session.describe(entry.path)
                print(f"  {entry.path} (api): timeout={detail.timeout_seconds}")
            except CoreAiSessionError as error:
                print(f"  {entry.path}: {type(error).__name__}: {str(error)[:90]}")

        print("\nsuccess call:")
        target = pick_call(session, list(names), options.call)
        if not target:
            print("  no read-only candidate was callable; pass --call NAME")
        else:
            try:
                result = session.call(target, {})
                print(f"  {target}: status={result.status} is_error={result.is_error} "
                      f"duration_ms={result.duration_ms} text={result.text[:60]!r}")
            except CoreAiSessionError as error:
                print(f"  {target}: {type(error).__name__}: {str(error)[:90]}")

        print("\nfailure call (a real business failure, not a transport error):")
        for name in ("MongoDB/list-databases", "google-gbp/get_reviews", "elasticsearch/get_mappings"):
            if name not in names:
                continue
            try:
                detail = session.describe(name)
                arguments = bogus_arguments(detail.input_schema)
                session.call(name, arguments)
                print(f"  {name} with {arguments}: unexpectedly succeeded")
            except ToolError as error:
                print(f"  {name}: ToolError status={error.status_code} {str(error)[:90]}")
                break
            except CoreAiSessionError as error:
                print(f"  {name}: {type(error).__name__}: {str(error)[:90]}")
                break

        if options.run_agent:
            print(f"\nagent run {options.run_agent} (this costs tokens):")
            try:
                task = session.call(options.run_agent, query="Reply with the single word: recorded.")
                print(f"  status={task.status} is_error={task.is_error} text={task.text[:80]!r}")
            except CoreAiSessionError as error:
                print(f"  {type(error).__name__}: {str(error)[:90]}")

        print("\nerror probes:")
        for args, stdin in ERROR_PROBES:
            probe(session, args, stdin)
    finally:
        session.close()

    written = [path for path in sorted(directory.glob("*.json")) if path.name != "capture.json"]
    # provenance for the manifest: what produced these bytes, and never *where* from
    (directory / "capture.json").write_text(json.dumps({
        "cli": "core-ai-cli",
        "version": session._version(),
        "date": date.today().isoformat(),
        "exchanges": len(written),
    }, indent=2) + "\n", encoding="utf-8")
    print(f"\n{len(written)} exchanges recorded in {directory}")
    print("next: python sdk/core-ai-session/tests/curate_recordings.py "
          f"--from {directory} --out sdk/core-ai-session/contract-fixtures/recorded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
