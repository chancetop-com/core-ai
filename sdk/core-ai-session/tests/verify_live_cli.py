"""Point the SDK at the real ``core-ai-cli`` and show what comes back.

Not part of the test suite: it needs a logged-in CLI (``core-ai-cli --login``) and a server with
tools you can actually call, so it cannot run in CI. Its job is to keep the local transport honest:

* confirm the *real* ``--json`` envelopes still match what ``tests/fake_cli.py`` fakes (field names,
  exit codes, where the answer sits), and
* dump every parsed field (``--dump DIR``) so a null or zero in the wrong place is obvious and the
  ``sdk/core-ai-session/contract-fixtures/`` samples can be brought back in line when they drift.

Run it from anywhere:

    python sdk/core-ai-session/tests/verify_live_cli.py                 # discovery only
    python sdk/core-ai-session/tests/verify_live_cli.py --tool google_gbp_get_reviews
    python sdk/core-ai-session/tests/verify_live_cli.py \\
        --tool google_gbp_get_reviews --call --arg location=locations/123 --dump /tmp/observed
    python sdk/core-ai-session/tests/verify_live_cli.py --record /tmp/exchanges

Read-only by design: ``--call`` is opt-in, and nothing is written unless ``--dump``/``--record`` is
given. ``--record`` writes the *raw* envelopes (argv + stdin + exit + stdout), which is what the
curated ``sdk/core-ai-session/contract-fixtures/recorded/`` set was built from — and it is real tool output, so
treat a recording as your own data, not as shareable by default.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from core_ai_session import CliSession, ToolError  # noqa: E402
from core_ai_session.errors import CoreAiSessionError  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cli", default=None, help="path to core-ai-cli (default: $CORE_AI_CLI or PATH)")
    parser.add_argument("--tool", default=None, help="a catalog name to describe, e.g. server/tool")
    parser.add_argument("--call", action="store_true", help="also call --tool (opt-in: it is a real call)")
    parser.add_argument("--arg", action="append", default=[], metavar="K=V", help="call argument, repeatable")
    parser.add_argument("--dump", default=None, metavar="DIR", help="write the observed payloads here")
    parser.add_argument("--record", default=None, metavar="DIR",
                        help="capture every core-ai-cli exchange (raw argv/stdin/exit/stdout) into DIR")
    return parser.parse_args()


def dump(directory: str | None, name: str, payload: object) -> None:
    if not directory:
        return
    target = Path(directory)
    target.mkdir(parents=True, exist_ok=True)
    body = json.dumps(payload, indent=2, ensure_ascii=False,
                      default=lambda value: getattr(value, "__dict__", str(value)))
    (target / f"{name}.json").write_text(body + "\n", encoding="utf-8")
    print(f"    wrote {target / (name + '.json')}")


def call_arguments(pairs: list[str]) -> dict[str, str]:
    arguments: dict[str, str] = {}
    for pair in pairs:
        key, _, value = pair.partition("=")
        if not key or not _:
            raise SystemExit(f"--arg expects K=V, got: {pair}")
        arguments[key] = value
    return arguments


def as_dict(value: object) -> dict:
    """The parsed dataclass, so a field that failed to map is visible as ``None``/``0``."""
    return {field: getattr(value, field) for field in value.__dataclass_fields__}  # type: ignore[attr-defined]


def main() -> int:
    options = parse_args()
    cli = shlex.split(options.cli) if options.cli else None
    print(f"core-ai-cli: {options.cli or os.environ.get('CORE_AI_CLI') or 'core-ai-cli (PATH)'}")
    session = None

    try:
        session = CliSession(cli=cli, record=options.record)
        if options.record:
            print(f"recording every exchange into: {options.record}")
        me = session.me()
        print(f"me: sandbox_state={me.sandbox_state} tools={me.tool_count} caller={me.caller}")
        dump(options.dump, "live-me", as_dict(me))

        catalog = session.refresh()
        by_kind: dict[str, int] = {}
        for entry in catalog.tools:
            by_kind[entry.kind] = by_kind.get(entry.kind, 0) + 1
        print(f"catalog: {len(catalog.tools)} tools {by_kind}")
        for entry in catalog.tools[:15]:
            print(f"  {entry.kind:9} {entry.path}")
        if len(catalog.tools) > 15:
            print(f"  … {len(catalog.tools) - 15} more")
        dump(options.dump, "live-catalog", {
            "tools": [{"name": entry.name, "kind": entry.kind, "group": entry.group, "path": entry.path,
                       "ref_id": entry.ref_id, "description": entry.description} for entry in catalog.tools],
        })

        if not options.tool:
            print("\nName a --tool to describe it (add --call to run it).")
            return 0

        detail = session.describe(options.tool)
        print(f"\ndescribe {detail.name}: kind={detail.kind} group={detail.group} path={detail.path}")
        print(f"  input_schema: {json.dumps(detail.input_schema, ensure_ascii=False)[:400]}")
        dump(options.dump, "live-tool-detail", as_dict(detail))

        if not options.call:
            return 0

        arguments = call_arguments(options.arg)
        print(f"\ncalling {options.tool} with {arguments} …")
        result = session.call(options.tool, arguments)
        print(f"  call_id={result.call_id} status={result.status} duration_ms={result.duration_ms}")
        print(f"  is_error={result.is_error} text[0:400]={result.text[:400]!r}")
        dump(options.dump, "live-call", as_dict(result))
        return 0
    except ToolError as error:
        print(f"tool error: tool={error.tool} status={error.status_code} message={error.message}")
        return 1
    except CoreAiSessionError as error:
        print(f"transport error: {error}")
        return 1
    finally:
        if session is not None:
            session.close()


if __name__ == "__main__":
    raise SystemExit(main())
