"""SDK tests. Run from the repository root:

    python -m unittest discover -s core-ai-sandbox-runtime/sdk/python/tests -t core-ai-sandbox-runtime/sdk/python

The namespace/scripting cases run entirely offline against `FakeSession`; the transport cases
start a loopback HTTP server that answers with the shared `sdk/contract-fixtures/*.json`
payloads, so both the SDK and the Go CLI are tested against one contract.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from core_ai_sandbox import (  # noqa: E402
    FakeSession,
    NotBoundError,
    ToolError,
    ToolNotFoundError,
    ToolResult,
    async_session,
    session,
)
from core_ai_sandbox.errors import CoreAiSandboxError  # noqa: E402
from core_ai_sandbox.testing import FIXTURE_DIR  # noqa: E402


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

    def test_env_missing_is_a_clear_error(self) -> None:
        os.environ.pop("CORE_AI_HUB", None)
        with self.assertRaises(CoreAiSandboxError) as caught:
            session()
        self.assertIn("CORE_AI_HUB", str(caught.exception))

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
        from core_ai_sandbox import AsyncSession

        opened = AsyncSession(hub_url=self.hub_url)
        with self.assertRaises(CoreAiSandboxError):
            opened.mcp["google-gbp"]
        asyncio.run(opened.aclose())


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
