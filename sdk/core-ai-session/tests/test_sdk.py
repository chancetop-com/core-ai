"""SDK tests. Run from the repository root:

    python -m unittest discover -s sdk/core-ai-session/tests

(Passing ``-t`` as well makes discovery require an importable ``tests`` package, so don't.)

The namespace/scripting cases run entirely offline against `FakeSession`; the transport cases
start a loopback HTTP server (`Session`) or a fake `core-ai-cli` (`CliSession`), both answering
with the shared `sdk/core-ai-session/contract-fixtures/*.json` payloads, so every transport is tested against one
contract.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from core_ai_session import (  # noqa: E402
    AsyncCliSession,
    CliSession,
    FakeSession,
    NotBoundError,
    Session,
    ToolError,
    ToolNotFoundError,
    ToolResult,
    async_session,
    session,
)
from core_ai_session.errors import CoreAiSessionError  # noqa: E402
from core_ai_session.testing import FIXTURE_DIR  # noqa: E402

FAKE_CLI = Path(__file__).resolve().parent / "fake_cli.py"
CLI_COMMAND = [sys.executable, str(FAKE_CLI)]


def fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


LIST_REVIEWS_SCHEMA = {
    "type": "object",
    "properties": {
        "location": {"type": "string"},
        "page_size": {"type": "integer"},
        "timeout": {"type": "integer", "description": "upstream request timeout in ms"},
    },
    "required": ["location"],
}


class FakeSessionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fake = FakeSession(catalog=fixture("catalog.json"))

    def test_namespaces_follow_the_catalog(self) -> None:
        tools = {tool.name for tool in self.fake.catalog().tools}
        self.assertIn("google_gbp_list_reviews", tools)
        self.assertEqual("mcp", self.fake.mcp["google-gbp"].list_reviews.kind)
        self.assertEqual("api", self.fake.api["restaurant-api"].reviews.get_reviews.kind)
        self.assertEqual("llm_call", self.fake.llm_call["seo-title-semantics"].kind)
        self.assertEqual("agent", self.fake.agent["review-responder"].kind)
        self.assertEqual("builtin", self.fake.builtin.web_search.kind)

    def test_dash_and_underscore_are_interchangeable(self) -> None:
        self.assertEqual(
            self.fake.mcp["google-gbp"].list_reviews._entry.name,
            self.fake.mcp.google_gbp.list_reviews._entry.name,
        )

    def test_hidden_tools_are_not_exposed(self) -> None:
        with self.assertRaises(ToolNotFoundError):
            self.fake.builtin.activate_tools
        with self.assertRaises(ToolNotFoundError):
            self.fake.tool("activate_tools")

    def test_unknown_namespace_key_suggests_candidates(self) -> None:
        with self.assertRaises(ToolNotFoundError) as caught:
            self.fake.mcp["google-gbp"].list_review
        self.assertIn("list_reviews", str(caught.exception))

    def test_group_is_not_callable(self) -> None:
        with self.assertRaises(TypeError):
            self.fake.mcp["google-gbp"]()

    def test_scripted_return_is_recorded(self) -> None:
        self.fake.mcp["google-gbp"].list_reviews.returns({"reviews": [{"review_id": "r1"}]})
        result = self.fake.mcp["google-gbp"].list_reviews(location="ChIJ")
        self.assertEqual({"reviews": [{"review_id": "r1"}]}, result.data)
        self.assertEqual(1, len(self.fake.calls))
        self.assertEqual("google_gbp_list_reviews", self.fake.calls[0].tool)
        self.assertEqual({"location": "ChIJ"}, self.fake.calls[0].arguments)

    def test_scripted_error_is_raised(self) -> None:
        self.fake.api.restaurant_api.reviews.get_reviews.raises(ToolError("db is down"))
        with self.assertRaises(ToolError) as caught:
            self.fake.api.restaurant_api.reviews.get_reviews(store_id="1")
        self.assertEqual("db is down", caught.exception.message)

    def test_error_result_raises_instead_of_returning(self) -> None:
        self.fake.mcp["google-gbp"].list_reviews.returns(
            ToolResult(call_id="c1", text="boom", is_error=True, error_message="upstream said no")
        )
        with self.assertRaises(ToolError) as caught:
            self.fake.mcp["google-gbp"].list_reviews(location="ChIJ")
        self.assertEqual("upstream said no", caught.exception.message)
        self.assertEqual("c1", caught.exception.call_id)
        self.assertEqual("mcp", caught.exception.kind)

    def test_missing_required_argument_fails_before_any_call(self) -> None:
        self.fake._detail_fixture["google_gbp_list_reviews"] = {
            "name": "google_gbp_list_reviews",
            "kind": "mcp",
            "input_schema": LIST_REVIEWS_SCHEMA,
        }
        self.fake.mcp["google-gbp"].list_reviews.returns({})
        with self.assertRaises(TypeError) as caught:
            self.fake.mcp["google-gbp"].list_reviews(page_size=10)
        self.assertIn("location", str(caught.exception))
        self.assertEqual([], self.fake.calls)

    def test_wrong_argument_type_fails_before_any_call(self) -> None:
        self.fake._detail_fixture["google_gbp_list_reviews"] = {
            "name": "google_gbp_list_reviews",
            "kind": "mcp",
            "input_schema": LIST_REVIEWS_SCHEMA,
        }
        with self.assertRaises(TypeError):
            self.fake.mcp["google-gbp"].list_reviews(location="x", page_size="twenty")
        self.assertEqual([], self.fake.calls)

    def test_timeout_is_the_tool_argument_when_the_schema_declares_it(self) -> None:
        self.fake._detail_fixture["google_gbp_list_reviews"] = {
            "name": "google_gbp_list_reviews",
            "kind": "mcp",
            "input_schema": LIST_REVIEWS_SCHEMA,
        }
        self.fake.mcp["google-gbp"].list_reviews.returns({})
        self.fake.mcp["google-gbp"].list_reviews(location="x", timeout=5000)
        self.assertEqual({"location": "x", "timeout": 5000}, self.fake.calls[0].arguments)
        self.assertIsNone(self.fake.calls[0].timeout)

    def test_timeout_is_the_sdk_argument_when_the_tool_has_no_such_parameter(self) -> None:
        self.fake.mcp["google-gbp"].reply_review.returns({})
        self.fake.mcp["google-gbp"].reply_review(review_id="r1", timeout=45)
        self.assertEqual({"review_id": "r1"}, self.fake.calls[0].arguments)
        self.assertEqual(45, self.fake.calls[0].timeout)

    def test_agent_node_maps_task_to_query_and_defaults_timeout(self) -> None:
        self.fake.agent["review-responder"].returns({"reply": "thanks"})
        self.fake.agent["review-responder"].run("draft a reply for r2")
        self.assertEqual({"query": "draft a reply for r2"}, self.fake.calls[0].arguments)
        self.assertEqual(300, self.fake.calls[0].timeout)

    def test_files_publish_returns_download_url(self) -> None:
        self.fake.files.returns("https://blob.example/report.html")
        url = self.fake.files.publish("/tmp/report.html", title="weekly report")
        self.assertEqual("https://blob.example/report.html", url)
        published = self.fake.calls_of("submit_artifacts")[0]
        self.assertEqual([{"path": "/tmp/report.html", "title": "weekly report"}], published.arguments["artifacts"])

    def test_pending_call_returns_task_and_polls(self) -> None:
        self.fake.agent["review-responder"].returns(
            ToolResult(call_id="c9", status="pending", task_id="task-1")
        )
        self.fake.script_task("task-1", {"call_id": "c9", "status": "completed", "text": "{\"reply\":\"ok\"}"})
        task = self.fake.agent["review-responder"].run("draft", wait=False)
        self.assertEqual("task-1", task.task_id)
        result = task.wait(timeout=5)
        self.assertEqual({"reply": "ok"}, result.data)

    def test_call_accepts_kind_dot_leaf_shorthand(self) -> None:
        self.fake.mcp["google-gbp"].list_reviews.returns({"reviews": []})
        result = self.fake.call("mcp.google_gbp_list_reviews", {"location": "ChIJ"})
        self.assertEqual({"reviews": []}, result.data)

    def test_me_is_offline_too(self) -> None:
        self.assertEqual("restaurant-local-seo", self.fake.me().agent_name)
        self.assertEqual("store-001", self.fake.me().caller["external_id"])

    def test_from_fixture_reads_the_checked_in_contract(self) -> None:
        fake = FakeSession.from_fixture("catalog.json", details=["tool-detail.json"])
        self.assertEqual("google_gbp_list_reviews", fake.mcp["google-gbp"].list_reviews._entry.name)
        detail = fake.describe("google-gbp/list_reviews")  # the details fixture answers this one
        self.assertEqual(["location"], detail.input_schema["required"])

    def test_a_missing_fixture_says_that_a_wheel_has_none(self) -> None:
        with self.assertRaises(FileNotFoundError) as caught:
            FakeSession.from_fixture("no-such-fixture.json")
        message = str(caught.exception)
        self.assertIn("no-such-fixture.json", message)
        self.assertIn("source checkout", message)
        self.assertIn("FakeSession(catalog=", message)


class _HubHandler(BaseHTTPRequestHandler):
    """Answers the SDK with the shared contract fixtures; records the last request."""

    payloads = {
        "/catalog": ("catalog.json", 200),
        "/me": ("me.json", 200),
        "/tools/google_gbp_list_reviews": ("tool-detail.json", 200),
        "/tools": ("tools.json", 200),
    }
    calls: list[tuple[str, dict]] = []

    def do_GET(self) -> None:  # noqa: N802 - http.server API
        path = self.path.split("?")[0]
        if path in self.payloads:
            name, status = self.payloads[path]
            self._respond(fixture(name), status)
            return
        self._respond(fixture("not-bound.json"), 503)

    def do_POST(self) -> None:  # noqa: N802 - http.server API
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        _HubHandler.calls.append((self.path, body))
        if "reply_review" in self.path:
            self._respond(fixture("call-error.json"), 200)
            return
        self._respond(fixture("call-success.json"), 200)

    def _respond(self, payload: dict, status: int) -> None:
        encoded = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *args: object) -> None:
        return None


class HttpSessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _HubHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.hub_url = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self) -> None:
        _HubHandler.calls = []
        os.environ["CORE_AI_HUB"] = self.hub_url

    def test_hub_url_comes_from_the_environment(self) -> None:
        with session() as opened:
            self.assertEqual("restaurant-local-seo", opened.me().agent_name)
            self.assertEqual("1.0", opened.me().contract_version)

    def test_catalog_is_lazy_then_cached(self) -> None:
        with session() as opened:
            self.assertIsNone(opened._catalog)
            self.assertEqual("mcp", opened.mcp["google-gbp"].list_reviews.kind)
            self.assertIsNotNone(opened._catalog)
            first = opened._catalog
            self.assertIs(first, opened.catalog())

    def test_describe_returns_a_parsed_schema(self) -> None:
        with session() as opened:
            detail = opened.describe("google_gbp_list_reviews")
            self.assertTrue(detail.callable)
            self.assertEqual(["location"], detail.input_schema["required"])

    def test_call_serializes_arguments_as_json_text(self) -> None:
        with session() as opened:
            result = opened.mcp["google-gbp"].list_reviews(location="ChIJN1t_tDeuEmsRUsoyG83frY4", page_size=20)
            self.assertEqual("0f8c2b1d-5e6a-4c7d-9a3f-2b4c6d8e0f12", result.call_id)
            self.assertEqual(2, len(result.data["reviews"]))
            self.assertEqual(842, result.duration_ms)
            path, body = _HubHandler.calls[-1]
            self.assertEqual("/tools/google_gbp_list_reviews/call", path)
            self.assertEqual({"location": "ChIJN1t_tDeuEmsRUsoyG83frY4", "page_size": 20}, json.loads(body["arguments"]))
            self.assertEqual(120, body["timeout_seconds"])

    def test_timeout_is_capped_at_the_contract_maximum(self) -> None:
        with session() as opened:
            opened.mcp["google-gbp"].list_reviews(location="x", timeout=9000)
            self.assertEqual(600, _HubHandler.calls[-1][1]["timeout_seconds"])

    def test_is_error_response_raises_tool_error(self) -> None:
        with session() as opened:
            with self.assertRaises(ToolError) as caught:
                opened.mcp["google-gbp"].reply_review(review_id="r1", comment="thanks")
            self.assertEqual("google people api: 500 internal error (rate limited?)", caught.exception.message)
            self.assertEqual("google_gbp_reply_review", caught.exception.tool)
            self.assertEqual("7d3a9c44-1b2f-4e5a-8c9d-0e1f2a3b4c5d", caught.exception.call_id)

    def test_unknown_tool_is_reported_without_a_request(self) -> None:
        with session() as opened:
            with self.assertRaises(ToolNotFoundError):
                opened.tool("google_gbp_list_reviewz")
            self.assertEqual([], _HubHandler.calls)

    def test_not_bound_is_retried_then_raised(self) -> None:
        with session(hub_url=self.hub_url) as opened:
            opened._client.close()
            opened._client = _RecordingClient(self.hub_url)
            with self.assertRaises(NotBoundError):
                opened.me()
            self.assertGreaterEqual(opened._client.attempts, 4)

    def test_env_missing_means_the_cli_backend(self) -> None:
        os.environ.pop("CORE_AI_HUB", None)
        os.environ["CORE_AI_CLI"] = "core-ai-cli-not-installed"
        try:
            with self.assertRaises(CoreAiSessionError) as caught:
                session()
        finally:
            os.environ.pop("CORE_AI_CLI", None)
        self.assertIn("core-ai-cli-not-installed", str(caught.exception))
        self.assertIn("CORE_AI_HUB", str(caught.exception))

    def test_direct_session_without_a_hub_url_points_at_the_cli(self) -> None:
        os.environ.pop("CORE_AI_HUB", None)
        with self.assertRaises(CoreAiSessionError) as caught:
            Session()
        self.assertIn("backend='cli'", str(caught.exception))

    def test_async_session_loads_catalog_and_runs_parallel_calls(self) -> None:
        async def run() -> None:
            opened = await async_session(self.hub_url)
            try:
                self.assertEqual("restaurant-local-seo", (await opened.me()).agent_name)
                results = await asyncio.gather(
                    opened.mcp["google-gbp"].list_reviews(location="a"),
                    opened.mcp["google-gbp"].list_reviews(location="b"),
                )
                self.assertEqual(2, len(results))
                self.assertEqual(2, len(_HubHandler.calls))
            finally:
                await opened.aclose()

        asyncio.run(run())

    def test_async_session_namespace_needs_a_loaded_catalog(self) -> None:
        from core_ai_session import AsyncSession

        opened = AsyncSession(hub_url=self.hub_url)
        with self.assertRaises(CoreAiSessionError):
            opened.mcp["google-gbp"]
        asyncio.run(opened.aclose())


class CliSessionTest(unittest.TestCase):
    """The local transport: the same script surface, ``core-ai-cli ... --json`` underneath.

    No server and no CLI installation: `fake_cli.py` answers with the same shared contract
    fixtures the loopback hub uses, so a call must produce the same `ToolResult` either way.
    """

    def setUp(self) -> None:
        self.record = Path(tempfile.mkdtemp()) / "calls.jsonl"
        self.saved = {name: os.environ.get(name) for name in ("FAKE_CLI_RECORD", "CORE_AI_HUB", "CORE_AI_CLI")}
        os.environ["FAKE_CLI_RECORD"] = str(self.record)
        os.environ.pop("CORE_AI_HUB", None)
        os.environ.pop("CORE_AI_CLI", None)

    def tearDown(self) -> None:
        for name, value in self.saved.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    def calls(self) -> list[dict]:
        """Every recorded invocation, in order (``fake_cli.py`` writes one file per call)."""
        directory = Path(f"{self.record}.d")
        if not directory.exists():
            return []
        return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(directory.glob("*.json"))]

    def invocations(self) -> list[dict]:
        """Only the tool/agent calls, without the catalog discovery that precedes them."""
        return [call for call in self.calls()
                if call["argv"][:2] in (["mcp", "call"], ["api-tool", "call"], ["agent", "run"])]

    def open(self, **kwargs: object) -> CliSession:
        return CliSession(cli=CLI_COMMAND, poll_interval=0.01, **kwargs)

    # ---------- catalog ----------

    def test_catalog_names_match_the_sandbox_catalog(self) -> None:
        opened = self.open()
        local = {tool.name for tool in opened.catalog().tools}
        remote = {
            tool["name"] for tool in fixture("catalog.json")["tools"]
            if tool["kind"] != "builtin" and tool["exposure"] != "hidden"
        }
        self.assertEqual(remote, local)
        self.assertEqual("cli", opened.backend)
        self.assertEqual("mcp", opened.mcp["google-gbp"].list_reviews.kind)
        self.assertEqual("api", opened.api["restaurant-api"].reviews.get_reviews.kind)
        self.assertEqual("llm_call", opened.llm_call["seo-title-semantics"].kind)
        self.assertEqual("agent", opened.agent["review-responder"].kind)

    def test_a_source_that_fails_once_is_retried(self) -> None:
        os.environ["FAKE_CLI_FLAKY"] = "mcp:google-places"
        os.environ["FAKE_CLI_FLAKY_FILE"] = str(Path(tempfile.mkdtemp()) / "flaky.count")
        os.environ["FAKE_CLI_FLAKY_TIMES"] = "1"
        try:
            opened = self.open()
            opened.catalog()
            self.assertEqual("mcp", opened.mcp["google-places"].search_places.kind)  # the retry won
            self.assertEqual([], opened.catalog_gaps)
            retries = [call for call in self.calls()
                       if call["argv"][:4] == ["mcp", "search", "--on-server", "google-places"]]
            self.assertEqual(2, len(retries), "the failed listing should be tried once more")
        finally:
            for name in ("FAKE_CLI_FLAKY", "FAKE_CLI_FLAKY_FILE", "FAKE_CLI_FLAKY_TIMES"):
                os.environ.pop(name, None)

    def test_a_source_that_stays_down_is_a_reported_catalog_gap(self) -> None:
        os.environ["FAKE_CLI_FLAKY"] = "mcp:google-places"  # no counter file: it never lists
        try:
            opened = self.open()
            with self.assertLogs("core_ai_session", level="WARNING") as logs:
                catalog = opened.catalog()
            names = {tool.name for tool in catalog.tools}
            self.assertEqual(["mcp server google-places"], opened.catalog_gaps)
            self.assertNotIn("google_places_search_places", names)
            self.assertIn("google_gbp_list_reviews", names)    # one source down, the rest survives
            self.assertEqual(len(catalog.tools), opened.me().tool_count)  # one enumeration, one count
            self.assertTrue(any("catalog is incomplete" in line for line in logs.output))
        finally:
            os.environ.pop("FAKE_CLI_FLAKY", None)

    def test_a_source_that_lists_less_than_it_advertises_is_a_gap(self) -> None:
        # the live shape: tikhub-tiktok answered `mcp servers` with tool_count=174 and then listed nothing
        os.environ["FAKE_CLI_SHORT"] = "mcp:google-places"
        try:
            opened = self.open()
            with self.assertLogs("core_ai_session", level="WARNING") as logs:
                catalog = opened.catalog()
            self.assertEqual(["mcp server google-places (listed 0 of 1)"], opened.catalog_gaps)
            opened.mcp["google-gbp"].list_reviews  # a healthy sibling is unaffected
            with self.assertRaises(ToolNotFoundError):
                opened.mcp["google-places"].search_places
            self.assertIn("google_gbp_list_reviews", {tool.name for tool in catalog.tools})
            self.assertTrue(any("catalog is incomplete" in line for line in logs.output))
        finally:
            os.environ.pop("FAKE_CLI_SHORT", None)

    def test_a_source_that_shrinks_between_enumerations_is_a_gap(self) -> None:
        opened = self.open()
        opened.catalog()
        self.assertEqual([], opened.catalog_gaps)
        os.environ["FAKE_CLI_TRIM"] = "mcp:google-gbp"  # the index flaps between two refresh() calls
        try:
            with self.assertLogs("core_ai_session", level="WARNING") as logs:
                opened.refresh()
            self.assertEqual(["mcp server google-gbp (listed 1, the previous enumeration listed 2)"],
                             opened.catalog_gaps)
            self.assertTrue(any("catalog is incomplete" in line for line in logs.output))
        finally:
            os.environ.pop("FAKE_CLI_TRIM", None)

    def test_builtin_tools_do_not_exist_locally(self) -> None:
        opened = self.open()
        opened.catalog()
        with self.assertRaises(ToolNotFoundError) as caught:
            opened.builtin.web_search
        self.assertIn("builtin", str(caught.exception))
        with self.assertRaises(ToolNotFoundError):
            opened.tool("web_search")

    def test_me_reports_a_local_session(self) -> None:
        info = self.open().me()
        self.assertEqual("local", info.sandbox_state)
        self.assertEqual("local-cli", info.caller["source"])
        self.assertEqual(6, info.tool_count)
        self.assertEqual("", info.session_id)  # there is no session to name

    def test_empty_catalog_is_not_an_error(self) -> None:
        os.environ["FAKE_CLI_EMPTY"] = "1"
        try:
            self.assertEqual([], self.open().tools())
        finally:
            os.environ.pop("FAKE_CLI_EMPTY", None)

    # ---------- calls ----------

    def test_call_returns_the_same_result_shape_as_the_hub(self) -> None:
        opened = self.open()
        result = opened.mcp["google-gbp"].list_reviews(location="ChIJN1t_tDeuEmsRUsoyG83frY4", page_size=20)
        self.assertEqual(fixture("call-success.json")["call_id"], result.call_id)
        self.assertEqual(2, len(result.data["reviews"]))
        self.assertEqual(842, result.duration_ms)
        argv = self.calls()[-1]["argv"]
        self.assertEqual("mcp", argv[0])
        self.assertEqual("call", argv[1])
        self.assertEqual("google-gbp/list_reviews", argv[2])
        self.assertIn("--args-file", argv)
        self.assertIn("--json", argv)

    def test_arguments_travel_on_stdin_not_argv(self) -> None:
        opened = self.open()
        review = "谢谢，服务很好" * 4000  # long and non-ASCII: argv would mangle or overflow it
        opened.mcp["google-gbp"].reply_review(review_id="r1", comment=review)
        last = self.invocations()[-1]
        self.assertEqual({"review_id": "r1", "comment": review}, json.loads(last["stdin"]))
        self.assertNotIn(review, last["argv"])

    def test_timeout_is_clamped_to_the_cli_maximum(self) -> None:
        opened = self.open()
        with self.assertLogs("core_ai_session", level="WARNING") as logs:
            opened.mcp["google-gbp"].list_reviews(location="x", timeout=9000)
        self.assertTrue(any("reduced to 300" in line for line in logs.output))
        argv = self.calls()[-1]["argv"]
        self.assertEqual("300", argv[argv.index("--timeout") + 1])

    def test_a_tool_schema_is_fetched_and_enforced_before_calling(self) -> None:
        opened = self.open()
        detail = opened.describe("google_gbp_list_reviews")
        self.assertEqual(["location"], detail.input_schema["required"])
        described = len(self.calls())
        with self.assertRaises(TypeError) as caught:
            opened.mcp["google-gbp"].list_reviews(page_size=10)
        self.assertIn("location", str(caught.exception))
        self.assertEqual(described, len(self.calls()))

    def test_is_error_result_raises_tool_error(self) -> None:
        opened = self.open()
        with self.assertRaises(ToolError) as caught:
            opened.mcp["google-gbp"].reply_review(review_id="r1", comment="__fail__")
        # the CLI's call response has no error_message field, so the message arrives as text
        self.assertEqual(fixture("call-error.json")["error_message"], caught.exception.message)
        self.assertEqual("google_gbp_reply_review", caught.exception.tool)
        self.assertEqual("mcp", caught.exception.kind)
        self.assertEqual(fixture("call-error.json")["call_id"], caught.exception.call_id)

    def test_unknown_name_fails_without_running_the_cli(self) -> None:
        opened = self.open()
        with self.assertRaises(ToolNotFoundError):
            opened.tool("google_gbp_list_reviewz")
        self.assertEqual([], self.invocations())

    def test_describe_missing_operation_is_not_found(self) -> None:
        opened = self.open()
        with self.assertRaises(ToolNotFoundError):
            opened.api["restaurant-api"].nope

    # ---------- exit codes ----------

    def test_not_logged_in_is_a_not_bound_error(self) -> None:
        os.environ["FAKE_CLI_OFFLINE"] = "1"
        try:
            with self.assertRaises(NotBoundError) as caught:
                self.open().catalog()
        finally:
            os.environ.pop("FAKE_CLI_OFFLINE", None)
        self.assertIn("--login", str(caught.exception))

    def test_forbidden_is_a_tool_error_with_403(self) -> None:
        os.environ["FAKE_CLI_FORBIDDEN"] = "1"
        try:
            with self.assertRaises(ToolError) as caught:
                self.open().catalog()
        finally:
            os.environ.pop("FAKE_CLI_FORBIDDEN", None)
        self.assertEqual(403, caught.exception.status_code)

    def test_usage_error_names_the_cli_version_and_the_floor(self) -> None:
        opened = self.open()
        with self.assertRaises(CoreAiSessionError) as caught:
            opened._require(["nonsense", "thing"])
        self.assertIn("upgrade", str(caught.exception))
        self.assertIn("2.0.12", str(caught.exception))  # the installed CLI, probed only on failure
        self.assertIn("2.0.10", str(caught.exception))

    # ---------- agents ----------

    def test_agent_run_sends_the_task_on_stdin(self) -> None:
        opened = self.open()
        result = opened.agent["review-responder"].run("draft a reply for r2\n第二行")
        self.assertEqual(fixture("task-completed.json")["text"], result.text)
        last = self.calls()[-1]
        self.assertEqual("draft a reply for r2\n第二行", last["stdin"])
        self.assertEqual("--task-file", last["argv"][3])
        self.assertEqual("-", last["argv"][4])

    def test_a_local_run_reports_tokens_but_not_model_or_cost(self) -> None:
        # `agent run --json` prints an AgentHubRunResult whose token_usage is a bare {"input","output"}
        result = self.open().agent["review-responder"].run("draft a reply for r2")
        usage = fixture("task-completed.json")["llm_usage"]
        self.assertEqual(usage["input_tokens"], result.llm_usage.input_tokens)
        self.assertEqual(usage["output_tokens"], result.llm_usage.output_tokens)
        self.assertIsNone(result.llm_usage.model)
        self.assertIsNone(result.llm_usage.cost)

    def test_llm_call_uses_the_agent_run_command(self) -> None:
        opened = self.open()
        opened.llm_call["seo-title-semantics"](query="rewrite this title", image_url="https://img.example/a.png")
        argv = self.calls()[-1]["argv"]
        self.assertEqual(["agent", "run", "seo-title-semantics"], argv[:3])
        self.assertEqual("https://img.example/a.png", argv[argv.index("--attach") + 1])

    def test_llm_call_rejects_agent_only_arguments(self) -> None:
        opened = self.open()
        with self.assertRaises(TypeError):
            opened.llm_call["seo-title-semantics"](query="x", context_id="ctx-1")

    def test_agent_run_maps_errors_to_tool_error(self) -> None:
        opened = self.open()
        with self.assertRaises(ToolError) as caught:
            opened.agent["review-responder"].run("this fails")
        self.assertEqual("the model refused the task", caught.exception.message)

    def test_a_running_agent_returns_a_task_that_polls_to_completion(self) -> None:
        opened = self.open()
        task = opened.agent["review-responder"].run("still-running please", wait=False)
        self.assertEqual("pending", task.status)
        self.assertEqual(fixture("task-completed.json")["task_id"], task.task_id)
        result = task.wait(timeout=5)
        self.assertEqual(fixture("task-completed.json")["text"], result.text)
        self.assertIn("agent", self.calls()[-1]["argv"])

    def test_waiting_for_input_looks_pending_and_says_how_to_answer(self) -> None:
        opened = self.open()
        with self.assertLogs("core_ai_session", level="WARNING") as logs:
            task = opened.agent["review-responder"].run("needs-input please", wait=False)
        self.assertEqual("pending", task.status)
        self.assertTrue(any("agent reply" in line for line in logs.output))

    # ---------- transport selection ----------

    def test_session_factory_uses_the_cli_when_the_hub_is_unset(self) -> None:
        directory = Path(tempfile.mkdtemp())
        os.environ["CORE_AI_CLI"] = write_cli_wrapper(directory)
        opened = session()
        self.assertIsInstance(opened, CliSession)
        self.assertEqual("cli", opened.backend)
        self.assertEqual("local", opened.me().sandbox_state)

    def test_a_sandbox_never_falls_back_to_the_local_user(self) -> None:
        os.environ["CORE_AI_HUB"] = "http://127.0.0.1:9/hub"
        with self.assertRaises(CoreAiSessionError) as caught:
            session(backend="cli")
        self.assertIn("CORE_AI_HUB", str(caught.exception))
        with self.assertRaises(CoreAiSessionError):
            session(hub_url="cli")
        with self.assertRaises(CoreAiSessionError):
            session(backend="nonsense")

    def test_a_hub_url_still_wins_over_detection(self) -> None:
        opened = session("http://127.0.0.1:9/hub")
        self.assertEqual("hub", opened.backend)
        opened.close()

    def test_async_cli_session_runs_calls_in_threads(self) -> None:
        async def run() -> None:
            opened = await async_session(backend="cli", cli=CLI_COMMAND)
            try:
                results = await asyncio.gather(
                    opened.mcp["google-gbp"].list_reviews(location="a"),
                    opened.api["restaurant-api"].reviews.get_reviews(store_id="1"),
                )
                self.assertEqual(2, len(results))
                self.assertEqual(2, len(self.invocations()))
            finally:
                await opened.aclose()

        asyncio.run(run())

    def test_async_cli_namespace_needs_a_loaded_catalog(self) -> None:
        opened = AsyncCliSession(cli=CLI_COMMAND)
        with self.assertRaises(CoreAiSessionError):
            opened.mcp["google-gbp"]
        asyncio.run(opened.aclose())


def write_cli_wrapper(directory: Path) -> str:
    """An executable that runs `fake_cli.py`, so `shutil.which`/`CORE_AI_CLI` can find it."""
    if os.name == "nt":
        wrapper = directory / "fake-core-ai-cli.cmd"
        wrapper.write_text(f'@echo off\r\n"{sys.executable}" "{FAKE_CLI}" %*\r\n', encoding="utf-8")
    else:
        wrapper = directory / "fake-core-ai-cli"
        wrapper.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{FAKE_CLI}" "$@"\n', encoding="utf-8")
        wrapper.chmod(0o755)
    return str(wrapper)


class _RecordingClient:
    """Minimal httpx.Client stand-in that always answers 503 not_bound."""

    def __init__(self, hub_url: str) -> None:
        self.hub_url = hub_url
        self.attempts = 0

    def request(self, method: str, url: str, **kwargs: object) -> "_Response":
        self.attempts += 1
        return _Response(fixture("not-bound.json"), 503)

    def close(self) -> None:
        return None


class _Response:
    status_code = 200
    text = ""

    def __init__(self, payload: dict, status: int) -> None:
        self.status_code = status
        self._payload = payload
        self.text = json.dumps(payload)

    def json(self) -> dict:
        return self._payload


if __name__ == "__main__":
    unittest.main()
