"""A stand-in for ``core-ai-cli ... --json``, so `CliSession` can be tested without a server.

It answers the JSON envelopes the real CLI prints — the `HubCallResponse` / `AgentHubRunResult`
DTOs of ``core-ai-api``, single line, snake_case — and honours `HubExitCodes`: 0 ok, 1 tool
error, 2 usage, 3 unauthenticated, 4 forbidden, 5 not found, 6 timeout, 7 input required. The
answers are derived from the shared ``sdk/core-ai-session/contract-fixtures/*.json`` payloads, which is what lets
a test assert that the CLI backend and the sandbox hub hand a script the *same* tool result.

Two shapes deliberately differ from the sandbox fixtures, and the tests pin that down:

* a failed call is a `HubCallResponse` — the CLI has no ``error_code``/``error_message`` field, so
  the tool's message arrives as ``text`` (the sandbox response carries it as ``error_message``);
* a run is an `AgentHubRunResult` — ``output`` instead of ``text``, and ``token_usage`` is a bare
  ``{"input": n, "output": n}`` map, so ``llm_usage.model``/``cost`` are not known locally.

Environment knobs, all optional:

* ``FAKE_CLI_RECORD`` — record every invocation: one file ``{"argv": [...], "stdin": "..."}`` per
  call under ``<path>.d/``, ordered by file name. (One file per call, not one appending log: the
  async test invokes the CLI from several threads and a shared appender interleaves half-written
  lines on Windows.)
* ``FAKE_CLI_OFFLINE=1`` — pretend the CLI has no credentials (exit 3).
* ``FAKE_CLI_FORBIDDEN=1`` — pretend the server refused the call (exit 4).
 * ``FAKE_CLI_EMPTY=1`` — pretend nothing is published/visible (empty catalogs).
 * ``FAKE_CLI_FLAKY="mcp:google-places"`` — make those sources fail to list. Add
   ``FAKE_CLI_FLAKY_TIMES=n`` (default 1) and ``FAKE_CLI_FLAKY_FILE=<path>`` (a counter file, so the
   failure survives across the CLI's one process per call) to make the first *n* attempts fail.
 * ``FAKE_CLI_SHORT="mcp:google-places"`` — list those sources successfully but with no tools, the
   shape of a live index that flaps: the source still advertises its tool count.
 * ``FAKE_CLI_TRIM="mcp:google-gbp"`` — the same sources lose one tool, and advertise the smaller
   count, the shape of one enumeration of a flapping index being smaller than the last.
"""

from __future__ import annotations

import json
import os
import sys
import time
import uuid
from pathlib import Path

FIXTURES = Path(__file__).resolve().parents[1] / "contract-fixtures"
BOOLEAN_FLAGS = {"json", "raw", "quiet", "detach", "insecure", "help", "version"}


def fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


SERVERS = {
    "servers": [
        {"name": "google-gbp", "description": "Google Business Profile", "category": "google",
         "state": "ready", "tool_count": 2},
        {"name": "google-places", "description": "Google Places", "category": "google",
         "state": "ready", "tool_count": 1},
    ]
}

TOOLS = {
    "google-gbp": [
        {"qualified_name": "google-gbp/list_reviews", "ref_id": "mcp:google-gbp:list_reviews",
         "server": "google-gbp", "name": "list_reviews",
         "description": "List the reviews of a business location.", "score": 1.0},
        {"qualified_name": "google-gbp/reply_review", "ref_id": "mcp:google-gbp:reply_review",
         "server": "google-gbp", "name": "reply_review",
         "description": "Publish a reply to a review.", "score": 1.0},
    ],
    "google-places": [
        {"qualified_name": "google-places/search_places", "ref_id": "mcp:google-places:search_places",
         "server": "google-places", "name": "search_places",
         "description": "Find a place id by name and address.", "score": 1.0},
    ],
}

MCP_DETAILS = {
    "mcp:google-gbp:list_reviews": {
        "qualified_name": "google-gbp/list_reviews",
        "ref_id": "mcp:google-gbp:list_reviews",
        "server": "google-gbp",
        "server_state": "ready",
        "description": "List the reviews of a business location.",
        "input_schema": fixture("tool-detail.json")["input_schema"],
    },
    "mcp:google-gbp:reply_review": {
        "qualified_name": "google-gbp/reply_review",
        "ref_id": "mcp:google-gbp:reply_review",
        "server": "google-gbp",
        "server_state": "ready",
        "description": "Publish a reply to a review.",
        "input_schema": json.dumps({
            "type": "object",
            "properties": {"review_id": {"type": "string"}, "comment": {"type": "string"}},
            "required": ["review_id", "comment"],
        }),
    },
    "mcp:google-places:search_places": {
        "qualified_name": "google-places/search_places",
        "ref_id": "mcp:google-places:search_places",
        "server": "google-places",
        "server_state": "ready",
        "description": "Find a place id by name and address.",
        "input_schema": json.dumps({
            "type": "object",
            "properties": {"name": {"type": "string"}, "address": {"type": "string"}},
            "required": ["name"],
        }),
    },
}

APPS = {
    "apps": [
        {"name": "restaurant-api", "description": "Restaurant service API", "base_url": "https://api.example",
         "version": "v1", "service_count": 1, "operation_count": 1},
    ]
}

OPERATIONS = {
    "operations": [
        {"qualified_name": "restaurant-api/reviews/get_reviews",
         "ref_id": "restaurant-api/reviews/get_reviews", "app": "restaurant-api", "service": "reviews",
         "name": "get_reviews", "method": "GET", "path": "/reviews",
         "description": "List the reviews of a store", "need_auth": True},
    ]
}

OPERATION_DETAILS = {
    "restaurant-api/reviews/get_reviews": {
        "qualified_name": "restaurant-api/reviews/get_reviews",
        "ref_id": "restaurant-api/reviews/get_reviews",
        "tool_name": "restaurant_api_reviews_get_reviews",
        "app": "restaurant-api", "service": "reviews", "name": "get_reviews",
        "method": "GET", "path": "/reviews",
        "description": "List the reviews of a store",
        "input_schema": json.dumps({
            "type": "object",
            "properties": {"store_id": {"type": "string"}},
            "required": ["store_id"],
        }),
    }
}

AGENTS = {
    "agent": [
        {"id": "a1b2c3d4e5f60718293a4b5c6d7e8f90", "name": "review-responder",
         "description": "Draft replies to customer reviews", "type": "agent", "source": "server",
         "status": "published", "owner_is_me": True, "tool_count": 3},
    ],
    "llm_call": [
        {"id": "b1c2d3e4f5a60718293a4b5c6d7e8f901", "name": "seo-title-semantics",
         "description": "Title/description semantics for local SEO", "type": "llm_call", "source": "server",
         "status": "published", "owner_is_me": True},
    ],
}


def call_failure() -> tuple[dict, int]:
    """A failed `mcp call`: the CLI's `HubCallResponse`, message in ``text``, exit 1."""
    error = fixture("call-error.json")
    return {
        "call_id": error["call_id"],
        "success": False,
        "is_error": True,
        "text": error["error_message"],
        "duration_ms": error["duration_ms"],
    }, 1


def run_result(status: str, **overrides: object) -> dict:
    """An `AgentHubRunResult` (see ``AgentHubRunResult`` in core-ai-api)."""
    done = fixture("task-completed.json")
    usage = done["llm_usage"]
    payload: dict[str, object] = {
        "task_id": done["task_id"],
        "context_id": "ctx-77c1",
        "agent_id": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
        "status": status,
        "output": done["text"] if status == "completed" else "",
        "duration_ms": done["duration_ms"],
        "token_usage": {"input": usage["input_tokens"], "output": usage["output_tokens"]},
    }
    payload.update(overrides)
    return payload


def parse(argv: list[str]) -> tuple[dict[str, object], list[str]]:
    flags: dict[str, object] = {}
    positionals: list[str] = []
    index = 0
    while index < len(argv):
        token = argv[index]
        if token.startswith("--"):
            name = token[2:]
            if name in BOOLEAN_FLAGS:
                flags[name] = True
                index += 1
            else:
                flags[name] = argv[index + 1] if index + 1 < len(argv) else ""
                index += 2
        else:
            positionals.append(token)
            index += 1
    return flags, positionals


def record(argv: list[str], stdin: str) -> None:
    """One file per invocation under ``<FAKE_CLI_RECORD>.d``, ordered by file name.

    Concurrent calls (``AsyncCliSession`` runs them in threads) share no writer, so nothing can
    interleave; sequential calls stay in order because the name starts with a nanosecond clock.
    """
    path = os.environ.get("FAKE_CLI_RECORD")
    if not path:
        return
    directory = Path(f"{path}.d")
    directory.mkdir(parents=True, exist_ok=True)
    name = f"{time.time_ns():020d}-{os.getpid():06d}-{uuid.uuid4().hex[:6]}.json"
    (directory / name).write_text(json.dumps({"argv": argv, "stdin": stdin}), encoding="utf-8")


def emit(payload: object, code: int) -> int:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    return code


def error(code: int, name: str, message: str) -> int:
    return emit({"error": {"code": name, "message": message, "status": code}}, code)


def read_stdin() -> str:
    return sys.stdin.read() if not sys.stdin.isatty() else ""


def sources_env(name: str) -> set[str]:
    return {part.strip() for part in os.environ.get(name, "").split(",") if part.strip()}


def flaky(source: str) -> bool:
    """Should ``source`` fail to list this time? See ``FAKE_CLI_FLAKY`` in the module docstring."""
    if source not in sources_env("FAKE_CLI_FLAKY"):
        return False
    counter = os.environ.get("FAKE_CLI_FLAKY_FILE")
    if not counter:
        return True
    path = Path(counter)
    seen = int(path.read_text(encoding="utf-8").strip() or 0) if path.exists() else 0
    path.write_text(str(seen + 1), encoding="utf-8")
    return seen < int(os.environ.get("FAKE_CLI_FLAKY_TIMES", "1"))


def short(source: str) -> bool:
    """Should ``source`` list nothing while still advertising its tools? (see ``FAKE_CLI_SHORT``)"""
    return source in sources_env("FAKE_CLI_SHORT")


def trimmed(source: str) -> bool:
    """Should ``source`` be one tool smaller, count and all? (see ``FAKE_CLI_TRIM``)"""
    return source in sources_env("FAKE_CLI_TRIM")


def main() -> int:
    argv = sys.argv[1:]
    if argv[:1] == ["--version"]:  # asked for only after a usage error, to name the installed CLI
        sys.stdout.write("2.0.12\n")
        return 0
    flags, positionals = parse(argv)
    stdin = read_stdin() if ("-" in argv) else ""
    record(argv, stdin)

    if os.environ.get("FAKE_CLI_OFFLINE"):
        return error(3, "unauthenticated", "no credentials; run 'core-ai-cli --login'")
    if os.environ.get("FAKE_CLI_FORBIDDEN"):
        return error(4, "forbidden", "this tool is restricted to admins")

    empty = bool(os.environ.get("FAKE_CLI_EMPTY"))
    leaf = "/".join(positionals[:2])
    rest = positionals[2:]

    if leaf == "mcp/servers":
        if empty:
            return emit({"servers": []}, 0)
        servers = json.loads(json.dumps(SERVERS["servers"]))  # a copy: the counts below are adjusted
        for server in servers:
            if trimmed(f"mcp:{server['name']}"):
                server["tool_count"] = max(0, len(TOOLS.get(server["name"], [])) - 1)
        return emit({"servers": servers}, 0)
    if leaf == "mcp/search":
        server = str(flags.get("on-server") or "")
        if flaky(f"mcp:{server}"):
            return error(6, "timeout", f"the hub timed out listing {server}")
        if short(f"mcp:{server}"):
            return emit({"tools": []}, 0)
        if trimmed(f"mcp:{server}"):
            return emit({"tools": TOOLS.get(server, [])[:-1]}, 0)
        return emit({"tools": [] if empty else TOOLS.get(server, [])}, 0)
    if leaf == "mcp/describe":
        qualified = rest[0] if rest else ""
        detail = MCP_DETAILS.get(f"mcp:{qualified.replace('/', ':', 1)}")
        return emit(detail, 0) if detail else error(5, "not_found", f"unknown tool: {qualified}")
    if leaf == "mcp/call":
        qualified = rest[0] if rest else ""
        if "__fail__" in stdin:  # a test asking for the business-failure path
            return emit(*call_failure())
        return emit(fixture("call-success.json"), 0)

    if leaf == "api-tool/apps":
        return emit({"apps": []} if empty else APPS, 0)
    if leaf == "api-tool/search":
        return emit({"operations": []} if empty else OPERATIONS, 0)
    if leaf == "api-tool/describe":
        qualified = rest[0] if rest else ""
        detail = OPERATION_DETAILS.get(qualified)
        return emit(detail, 0) if detail else error(5, "not_found", f"unknown operation: {qualified}")
    if leaf == "api-tool/call":
        return emit(fixture("call-success.json"), 0)

    if leaf == "agent/search":
        wanted = str(flags.get("type") or "agent")
        return emit({"agents": []} if empty else {"agents": AGENTS.get(wanted, [])}, 0)
    if leaf == "agent/run":
        # the task travels on --task-file -; its text decides which outcome this fake plays
        if "needs-input" in stdin:
            return emit(run_result("input_required", input_request={
                "call_id": "call-1", "tool": "shell", "arguments": '{"command":"ls"}',
                "message": "the agent wants to run shell",
            }), 7)
        if "still-running" in stdin:
            return emit(run_result("running", output="working on it"), 6)
        if "fails" in stdin:
            return emit(run_result("failed", error_code="turn_failed",
                                   error_message="the model refused the task"), 1)
        return emit(run_result("completed"), 0)
    if leaf == "agent/status":
        task_id = rest[0] if rest else ""
        if task_id == fixture("task-completed.json")["task_id"]:
            return emit(run_result("completed"), 0)
        return emit(run_result("running", task_id=task_id, output=""), 6)

    return error(2, "usage", f"unknown command: {' '.join(positionals)}")


if __name__ == "__main__":
    sys.exit(main())
