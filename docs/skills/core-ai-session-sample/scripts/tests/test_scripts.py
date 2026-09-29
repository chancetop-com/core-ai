"""Offline tests for the sample scripts: no credentials, no network, no sandbox.

    python -m unittest discover -s scripts/tests          # from the skill directory

Two testing styles, both credential-free:

* `FakeSession` replays a catalog you hand it and records every call, so a test can assert the call
  sequence and the arguments without a transport at all;
* `httpx.MockTransport` serves the hub's HTTP contract in-process when the test needs the real
  transport code path (search, describe, tasks) - see `MockHub` below.

The async test drives a real `AsyncSession` over the same mock hub, so concurrency and the awaited
dataset path are exercised too. A skill script should be asserted here first, then run once in a
sandbox against the tools that are really mounted.
"""

from __future__ import annotations

import asyncio
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx  # noqa: E402  (the SDK's own dependency)

import async_fanout  # noqa: E402
import discover_and_call  # noqa: E402
import export_catalog  # noqa: E402
import full_pipeline  # noqa: E402
from core_ai_session import AsyncSession, Session, ToolError  # noqa: E402
from core_ai_session.testing import FakeSession  # noqa: E402

CATALOG = {
    "session_id": "sess-sample-1",
    "agent_name": "sample-agent",
    "sandbox_state": "ready",
    "tools": [
        {"name": "google_gbp_list_reviews", "kind": "mcp", "group": "google-gbp",
         "path": "google-gbp/list_reviews", "ref_id": "mcp:google-gbp:list_reviews",
         "description": "List the reviews of a location.", "exposure": "direct"},
        {"name": "seo_title_semantics", "kind": "llm_call", "path": "seo-title-semantics",
         "ref_id": "seo-title-semantics", "description": "Rewrite a page title.",
         "exposure": "direct"},
        {"name": "submit_artifacts", "kind": "builtin", "path": "submit_artifacts",
         "ref_id": "submit_artifacts", "description": "Publish a file as an artifact.",
         "exposure": "direct"},
        {"name": "insert_dataset_record", "kind": "builtin", "path": "insert_dataset_record",
         "ref_id": "insert_dataset_record", "description": "Insert a dataset record.",
         "exposure": "direct"},
    ],
    "datasets": [
        {"dataset_id": "ds-menu", "name": "menu-state", "type": "SESSION", "permission": "FULL",
         "schema": [{"name": "menuPublished", "type": "boolean"}]},
        {"dataset_id": "ds-log", "name": "review-log", "type": "GENERAL", "permission": "WRITE",
         "schema": [{"name": "source_tool", "type": "string"}]},
        {"dataset_id": "ds-history", "name": "seo-history", "type": "GENERAL", "permission": "READ",
         "schema": []},
    ],
}

DETAILS = [
    {"name": "google_gbp_list_reviews", "kind": "mcp", "group": "google-gbp",
     "path": "google-gbp/list_reviews", "description": "List the reviews of a location.",
     "timeout_seconds": 60,
     "input_schema": {"type": "object", "properties": {"location": {"type": "string"}},
                      "required": ["location"]}},
]

REVIEWS = {"reviews": [{"id": "r-1", "rating": 2, "text": "cold soup"}]}
DIGEST = "The soup was cold; the staff were kind."


def envelope(payload: Any, **overrides: Any) -> dict:
    """What the hub answers for one tool call: the tool's text output inside the result envelope."""
    result = {"call_id": "call-1", "status": "completed", "duration_ms": 3,
              "text": json.dumps(payload, ensure_ascii=False)}
    result.update(overrides)
    return result


def _fake() -> FakeSession:
    return FakeSession(catalog=CATALOG, details=DETAILS)


def _run(func, *args, **kwargs):
    """Run one script's entry point, capturing (exit code, stdout, stderr) as strings."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = func(*args, **kwargs)
    return code, out.getvalue(), err.getvalue()


class MockHub:
    """A scripted core-ai-server: the real `Session` code over `httpx.MockTransport`.

    Answers `/catalog`, `/tools` (search), `/tools/<name>` (describe) and `/tools/<name>/call`,
    so a test can exercise the transport layer itself - the part `FakeSession` deliberately skips.
    """

    def __init__(self, scripted: dict[str, dict] | None = None) -> None:
        self.scripted = scripted or {}
        self.calls: list[tuple[str, dict]] = []
        self.client = httpx.Client(transport=httpx.MockTransport(self._handle))
        self.session = Session(hub_url="http://hub.test/hub", client=self.client,
                               session_id="sess-sample-1")

    def close(self) -> None:
        self.client.close()

    def _handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path.removeprefix("/hub")
        if path == "/catalog":
            return httpx.Response(200, json=CATALOG)
        if path == "/tools":
            query = (request.url.params.get("query") or "").lower().split()
            kind = request.url.params.get("kind") or ""
            matched = [
                tool for tool in CATALOG["tools"]
                if (not kind or tool["kind"] == kind)
                and all(word in f"{tool['path']} {tool['name']} {tool['description']}".lower()
                        for word in query)
            ]
            return httpx.Response(200, json={"tools": matched})
        if path.startswith("/tools/"):
            name = path[len("/tools/"):]
            if name.endswith("/call"):
                tool = name[: -len("/call")]
                body = json.loads(request.content.decode("utf-8"))
                self.calls.append((tool, json.loads(body["arguments"])))
                payload = self.scripted.get(tool)
                if payload is None:
                    return httpx.Response(404, json={"error": "not_found", "message": f"{tool} is unknown"})
                return httpx.Response(200, json=payload)
            detail = next((item for item in DETAILS if item["name"] == name), None)
            if detail is None:
                return httpx.Response(404, json={"error": "not_found", "message": f"{name} is unknown"})
            return httpx.Response(200, json=detail)
        return httpx.Response(404, json={"error": "not_found", "message": path})


class DiscoverAndCallTest(unittest.TestCase):
    def test_overview_reports_identity_catalog_and_datasets(self) -> None:
        payload = discover_and_call.overview(_fake())
        self.assertEqual("fake", payload["backend"])
        self.assertEqual(4, payload["tools"])
        self.assertEqual({"mcp": 1, "llm_call": 1, "builtin": 2}, payload["kinds"])
        self.assertEqual(["menu-state", "review-log", "seo-history"],
                         [item["name"] for item in payload["datasets"]])
        self.assertIsNone(payload["datasets_note"])

    def test_a_local_session_without_a_session_id_explains_datasets(self) -> None:
        fake = FakeSession(catalog=CATALOG)  # a fake stands in for the local transport here
        fake._local_anchor = lambda: ""  # type: ignore[method-assign]  # what CliSession answers
        payload = discover_and_call.overview(fake)
        self.assertEqual([], payload["datasets"])
        self.assertIn("session id", payload["datasets_note"])

    def test_search_and_describe_go_through_the_transport(self) -> None:
        hub = MockHub()
        self.addCleanup(hub.close)
        code, out, _err = _run(discover_and_call.main, ["--search", "reviews"], opened=hub.session)
        self.assertEqual(0, code)
        self.assertIn("google-gbp/list_reviews", out)

        code, out, _err = _run(discover_and_call.main, ["--describe", "google-gbp/list_reviews"],
                               opened=hub.session)
        self.assertEqual(0, code)
        self.assertIn('"location"', out)

    def test_describe_reads_the_offline_detail_fixture(self) -> None:
        code, out, _err = _run(discover_and_call.main, ["--describe", "google-gbp/list_reviews"],
                               opened=_fake())
        self.assertEqual(0, code)
        self.assertIn("List the reviews of a location.", out)

    def test_an_unknown_name_is_a_usage_error(self) -> None:
        code, _out, err = _run(discover_and_call.main, ["--describe", "google-gbp/nope"], opened=_fake())
        self.assertEqual(2, code)
        self.assertIn("not in this session's catalog", err)

    def test_a_call_reports_text_and_metadata(self) -> None:
        fake = _fake()
        fake.mcp["google-gbp"]["list_reviews"].returns(REVIEWS)
        code, out, _err = _run(discover_and_call.main,
                               ["--call", "google-gbp/list_reviews", "--args", '{"location": "x"}'],
                               opened=fake)
        self.assertEqual(0, code)
        self.assertIn("completed", out)
        self.assertEqual({"location": "x"}, fake.calls[0].arguments)

    def test_a_call_over_the_transport_sends_the_arguments_as_json_text(self) -> None:
        hub = MockHub({"google_gbp_list_reviews": envelope(REVIEWS, duration_ms=12)})
        self.addCleanup(hub.close)
        code, out, _err = _run(discover_and_call.main, [
            "--call", "google-gbp/list_reviews", "--args", '{"location": "x"}', "--json",
        ], opened=hub.session)
        self.assertEqual(0, code)
        self.assertEqual(12, json.loads(out)["duration_ms"])
        self.assertEqual([("google_gbp_list_reviews", {"location": "x"})], hub.calls)


class FullPipelineTest(unittest.TestCase):
    """The worked example, end to end, with every stage scripted."""

    def _scripted(self) -> FakeSession:
        fake = _fake()
        fake.mcp["google-gbp"]["list_reviews"].returns(REVIEWS)
        fake.llm_call["seo-title-semantics"].returns(DIGEST)
        fake.files.returns("https://files.example/digest.md")
        fake.dataset.returns({"status": "created", "inserted_fields": ["source_tool"]},
                             op="records.insert")
        return fake

    def test_full_pipeline_runs_every_stage_in_order(self) -> None:
        fake = self._scripted()
        with tempfile.TemporaryDirectory() as directory:
            out_file = Path(directory) / "digest.md"
            code, out, err = _run(full_pipeline.main, [
                "--tool", "google-gbp/list_reviews", "--args", '{"location": "x"}',
                "--summarize", "seo-title-semantics", "--save", "review-log",
                "--publish", str(out_file), "--out", str(out_file), "--json",
            ], opened=fake)
            self.assertEqual(0, code, err)
            self.assertEqual(DIGEST, out_file.read_text(encoding="utf-8"))
        report = json.loads(out)
        self.assertEqual("google-gbp/list_reviews", report["tool"])
        self.assertEqual({"chars": len(DIGEST), "llm_call": "seo-title-semantics"}, report["digest"])
        self.assertEqual("records.insert", report["dataset"]["op"])
        self.assertEqual("https://files.example/digest.md", report["artifact"]["url"])

        self.assertEqual(["google_gbp_list_reviews", "seo_title_semantics", "submit_artifacts"],
                         [call.tool for call in fake.calls])
        self.assertEqual({"query": json.dumps(REVIEWS)}, fake.calls[1].arguments)
        saved = fake.dataset_calls[0]
        self.assertEqual(("records.insert", "ds-log"), (saved.op, saved.dataset_id))
        self.assertEqual("google-gbp/list_reviews", saved.arguments["data"]["source_tool"])

    def test_dry_run_reads_and_digests_but_writes_nothing(self) -> None:
        fake = self._scripted()
        code, out, err = _run(full_pipeline.main, [
            "--tool", "google-gbp/list_reviews", "--args", "{\"location\": \"x\"}",
            "--dry-run", "--json",
        ], opened=fake)
        self.assertEqual(0, code, err)
        self.assertTrue(json.loads(out)["dry_run"])
        self.assertEqual(["google_gbp_list_reviews"], [call.tool for call in fake.calls])
        self.assertEqual([], fake.dataset_calls)

    def test_a_session_dataset_is_patched_not_inserted(self) -> None:
        fake = self._scripted()
        fake.dataset.returns({"status": "updated", "updated_fields": ["source_tool"]},
                             op="state.patch")
        code, out, err = _run(full_pipeline.main, [
            "--tool", "google-gbp/list_reviews", "--args", "{\"location\": \"x\"}",
            "--save", "menu-state", "--json",
        ], opened=fake)
        self.assertEqual(0, code, err)
        self.assertEqual("state.patch", json.loads(out)["dataset"]["op"])
        self.assertEqual("state.patch", fake.dataset_calls[0].op)

    def test_a_read_only_binding_is_refused_before_any_round_trip(self) -> None:
        fake = self._scripted()
        code, _out, err = _run(full_pipeline.main, [
            "--tool", "google-gbp/list_reviews", "--args", "{\"location\": \"x\"}",
            "--save", "seo-history",
        ], opened=fake)
        self.assertEqual(1, code)
        self.assertIn("access denied to dataset: ds-history", err)
        self.assertEqual([], fake.dataset_calls)

    def test_a_tool_error_keeps_its_message_and_exit_code(self) -> None:
        fake = _fake()
        fake.mcp["google-gbp"]["list_reviews"].raises(ToolError("quota exceeded", status_code=429))
        code, _out, err = _run(full_pipeline.main, [
            "--tool", "google-gbp/list_reviews", "--args", "{\"location\": \"x\"}",
        ], opened=fake)
        self.assertEqual(1, code)
        self.assertIn("quota exceeded", err)

    def test_an_unknown_tool_lists_candidates_instead_of_calling(self) -> None:
        fake = _fake()
        code, _out, err = _run(full_pipeline.main, ["--tool", "google-gbp/list_reviewz"], opened=fake)
        self.assertEqual(2, code)
        self.assertIn("google-gbp/list_reviews", err)
        self.assertEqual([], fake.calls)

    def test_summarize_requires_a_mounted_llm_call(self) -> None:
        fake = _fake()
        fake.mcp["google-gbp"]["list_reviews"].returns(REVIEWS)
        code, _out, err = _run(full_pipeline.main, [
            "--tool", "google-gbp/list_reviews", "--args", "{\"location\": \"x\"}",
            "--summarize", "not-mounted",
        ], opened=fake)
        self.assertEqual(2, code)
        self.assertIn("seo-title-semantics", err)


class AsyncFanoutTest(unittest.TestCase):
    """An async script is awaited in the test's own loop: `main_async` is the entry to drive."""

    def _opened(self, scripted: dict[str, dict]) -> AsyncSession:
        client = httpx.AsyncClient(transport=httpx.MockTransport(
            MockHub(scripted)._handle))  # the same scripted server, awaited
        return AsyncSession(hub_url="http://hub.test/hub", client=client,
                            session_id="sess-sample-1")

    async def _scenario(self, scripted: dict[str, dict], argv: list[str]) -> int:
        opened = self._opened(scripted)
        await opened.refresh()
        try:
            return await async_fanout.main_async(argv, opened=opened)
        finally:
            await opened.aclose()

    def _arun(self, scripted: dict[str, dict], argv: list[str]):
        """One scenario, one event loop, capturing (exit code, stdout, stderr) like `_run`."""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = asyncio.run(self._scenario(scripted, argv))
        return code, out.getvalue(), err.getvalue()

    def test_calls_run_concurrently_and_the_summary_is_written(self) -> None:
        scripted = {
            "google_gbp_list_reviews": envelope(REVIEWS, duration_ms=12),
            "insert_dataset_record": envelope({"status": "created", "dataset_id": "ds-log",
                                               "inserted_fields": ["calls"]}),
        }
        code, out, err = self._arun(scripted, [
            "--calls", json.dumps([
                {"tool": "google-gbp/list_reviews", "args": {"location": "a"}},
                {"tool": "google-gbp/list_reviews", "args": {"location": "b"}},
            ]),
            "--save", "review-log", "--json",
        ])
        self.assertEqual(0, code, err)
        payload = json.loads(out)
        self.assertEqual(2, len(payload["report"]["calls"]))
        self.assertEqual(0, payload["report"]["failed"])
        self.assertEqual("records.insert", payload["dataset"]["op"])

    def test_one_failure_does_not_cancel_the_others(self) -> None:
        scripted = {
            "google_gbp_list_reviews": envelope(REVIEWS),
            "seo_title_semantics": {"call_id": "call-2", "status": "failed", "text": "",
                                    "is_error": True, "error_message": "model down",
                                    "status_code": 503},
        }
        code, out, err = self._arun(scripted, [
            "--calls", json.dumps([{"tool": "google-gbp/list_reviews"},
                                   {"tool": "seo-title-semantics", "args": {"query": "x"}}]),
            "--json",
        ])
        self.assertEqual(1, code, err)
        calls = json.loads(out)["report"]["calls"]
        self.assertEqual(["completed", "failed"], [call["status"] for call in calls])
        self.assertIn("model down", calls[1]["text"])


class ExportCatalogTest(unittest.TestCase):
    def test_the_export_feeds_a_fake_session_unchanged(self) -> None:
        source = _fake()
        with tempfile.TemporaryDirectory() as directory:
            catalog_file = Path(directory) / "catalog.json"
            details_file = Path(directory) / "details.json"
            code, _out, err = _run(export_catalog.main, [
                "--out", str(catalog_file), "--details", str(details_file), "--tool",
                "google-gbp/list_reviews",
            ], opened=source)
            self.assertEqual(0, code, err)

            exported = FakeSession(catalog=json.loads(catalog_file.read_text(encoding="utf-8")),
                                   details=json.loads(details_file.read_text(encoding="utf-8")))
            self.assertEqual(["google-gbp/list_reviews"],
                             [tool.path for tool in exported.catalog().tools])
            self.assertEqual(["location"], exported.describe("google-gbp/list_reviews")
                             .input_schema["required"])


if __name__ == "__main__":
    unittest.main()
