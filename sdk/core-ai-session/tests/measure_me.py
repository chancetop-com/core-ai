"""One-off: is `me().tool_count` == the catalog size, and what does a flapping source look like?

`me()` in the CLI transport counts the catalog it had to enumerate, so `me()` and `catalog()` agree by
construction. Two *enumerations* can still disagree — a live index can serve 174 tools one minute and
none the next (observed with `tikhub-tiktok`) — and that is what `session.catalog_gaps` is for. This
measures the real CLI instead of guessing.

    python sdk/core-ai-session/tests/measure_me.py
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from core_ai_session import CliSession  # noqa: E402


def by_kind(session: CliSession) -> dict[str, int]:
    counts: dict[str, int] = {}
    for entry in session.catalog().tools:
        counts[entry.kind] = counts.get(entry.kind, 0) + 1
    return counts


def mcp_count(session: CliSession) -> int:
    return sum(1 for entry in session.catalog().tools if entry.kind == "mcp")


def main() -> int:
    for round_number in (1, 2):
        started = time.time()
        session = CliSession()
        try:
            info = session.me()
            print(f"round {round_number} me()      : tool_count={info.tool_count} in {time.time() - started:.1f}s")
            print(f"round {round_number} catalog() : {len(session.catalog().tools)} tools {by_kind(session)}")
            print(f"round {round_number} gaps      : {session.catalog_gaps or 'none'}")
        finally:
            session.close()

    print("\none session, four enumerations — the flap and the gap list:")
    session = CliSession()
    try:
        for index in range(4):
            catalog = session.refresh()
            print(f"  enumeration {index + 1}: {len(catalog.tools)} tools (mcp {mcp_count(session)}) "
                  f"gaps={session.catalog_gaps or 'none'}")
    finally:
        session.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
