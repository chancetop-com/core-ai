"""Parity tests: what the SDK makes of a **real** ``core-ai-cli``'s answers.

`test_sdk.py` drives `fake_cli.py`, whose payloads are written to match *our* reading of the
contract. This module drives `replay_cli.py` instead, which re-serves the anonymised bytes a real CLI
printed against a real hub (`sdk/core-ai-session/contract-fixtures/recorded/`), so an assertion here is about the
wire rather than about our model of it. Run it like the rest:

    python -m unittest discover -s sdk/core-ai-session/tests

Two cases reach past the public namespace on purpose — a not-found describe and a usage error are
only observable at the transport, because the namespace layer refuses an unknown name before the CLI
is ever started.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import curate_recordings as curate  # noqa: E402
from core_ai_session import CliSession, CoreAiSessionError, Task, ToolError, ToolNotFoundError  # noqa: E402
from core_ai_session.testing import FIXTURE_DIR  # noqa: E402

REPLAY_CLI = Path(__file__).resolve().parent / "replay_cli.py"
REPLAY_COMMAND = [sys.executable, str(REPLAY_CLI)]
RECORDED = FIXTURE_DIR / "recorded"
MANIFEST = json.loads((RECORDED / "manifest.json").read_text(encoding="utf-8"))
PLACEHOLDER = MANIFEST["redaction"]["placeholder"]


def recorded(name: str) -> dict:
    return json.loads((RECORDED / name).read_text(encoding="utf-8"))


class RecordedContractTest(unittest.TestCase):
    """Each test opens its own session, so a failure names one surface of the recordings."""

    def setUp(self) -> None:
        self.saved = os.environ.get("CORE_AI_CLI_RECORDED")
        os.environ["CORE_AI_CLI_RECORDED"] = str(RECORDED)
        self.sent = Path(tempfile.mkdtemp(prefix="core-ai-replayed-"))
        self.session = CliSession(cli=REPLAY_COMMAND, record=str(self.sent), poll_interval=0.01)

    def tearDown(self) -> None:
        self.session.close()
        if self.saved is None:
            os.environ.pop("CORE_AI_CLI_RECORDED", None)
        else:
            os.environ["CORE_AI_CLI_RECORDED"] = self.saved

    def sent_calls(self) -> list[dict]:
        """What `CliSession` actually spawned, as its own recorder wrote it."""
        return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(self.sent.glob("*.json"))]

    # ---------- catalog ----------

    def test_the_catalog_is_exactly_what_the_real_cli_listed(self) -> None:
        by_kind: dict[str, set[str]] = {}
        for tool in self.session.catalog().tools:
            by_kind.setdefault(tool.kind, set()).add(tool.path)
        self.assertEqual(
            {
                "MongoDB/aggregate",
                "MongoDB/list-databases",
                "elasticsearch/esql",
                "elasticsearch/get_mappings",
                "elasticsearch/get_shards",
                "kubernetes/configuration_view",
                "kubernetes/events_list",
                "kubernetes/namespaces_list",
            },
            by_kind["mcp"],
        )
        self.assertEqual({"restaurant-api/reviews/batchUpdateOrderItemQuantity"}, by_kind["api"])
        self.assertEqual({"Agent Builder", "Assistant"}, by_kind["agent"])
        self.assertEqual({"seo-title-semantics"}, by_kind["llm_call"])
        # a description is prose, so the fixture withholds it; what matters is that the field arrives
        self.assertEqual({PLACEHOLDER}, {tool.description for tool in self.session.tools() if tool.kind == "mcp"})

    def test_me_counts_the_tools_the_catalog_holds(self) -> None:
        info = self.session.me()
        self.assertEqual(len(self.session.catalog().tools), info.tool_count)
        self.assertEqual("local", info.sandbox_state)  # the CLI transport never claims a session
        self.assertEqual("", info.session_id)

    def test_a_curated_listing_is_reported_as_partial(self) -> None:
        # the fixtures are slices of a bigger hub: `mcp servers` keeps the real per-server tool counts
        # while the search recordings keep only the tools the tests need, so the SDK must say so
        # instead of quietly presenting a short catalog as the whole truth
        with self.assertLogs("core_ai_session", level="WARNING") as logs:
            self.session.catalog()
        self.assertEqual(
            [
                "mcp server MongoDB (listed 2 of 18)",
                "mcp server elasticsearch (listed 3 of 5)",
                "mcp server kubernetes (listed 3 of 13)",
                "api app restaurant-api (listed 1 of 55)",
            ],
            self.session.catalog_gaps,
        )
        self.assertTrue(any("catalog is incomplete" in line for line in logs.output))

    # ---------- describe ----------

    def test_a_recorded_describe_parses_every_schema_shape(self) -> None:
        smallest = self.session.describe("MongoDB/list-databases")
        self.assertEqual("mcp", smallest.kind)
        self.assertEqual(120, smallest.timeout_seconds)
        self.assertEqual(
            {"type": "object", "properties": {"connectionId": {"type": "string"}},
             "required": ["connectionId"], "additionalProperties": False},
            smallest.input_schema,
        )
        self.assertEqual(recorded("mcp-describe-mongodb-list-databases.json")["stdout"]["ref_id"], smallest.ref_id)

        described = self.session.describe("kubernetes/namespaces_list")
        self.assertTrue(described.input_schema["properties"]["fieldSelector"]["pattern"].startswith("^[."))

        # an api operation sends its schema as JSON *text*; it must arrive parsed, nested objects and all
        operation = self.session.describe("restaurant-api/reviews/batchUpdateOrderItemQuantity")
        self.assertEqual("api", operation.kind)
        self.assertEqual(120, operation.timeout_seconds)
        self.assertEqual(["id", "is_need_print", "order_item_quantities", "operator"],
                         operation.input_schema["required"])
        quantity = operation.input_schema["properties"]["order_item_quantities"]["items"]
        self.assertEqual(["order_item_id", "quantity"], quantity["required"])
        self.assertEqual("integer", quantity["properties"]["quantity"]["type"])
        # the DTO class names behind request_type/response_type are withheld, the fields stay
        wire = recorded("api-tool-describe.json")["stdout"]
        self.assertEqual(PLACEHOLDER, wire["request_type"])
        self.assertEqual(PLACEHOLDER, wire["response_type"])
        self.assertEqual("PUT", wire["method"])

    # ---------- calls ----------

    def test_a_recorded_success_call_keeps_the_hub_shape(self) -> None:
        wire = recorded("mcp-call-kubernetes-namespaces-list.json")["stdout"]
        result = self.session.mcp["kubernetes"].namespaces_list()
        self.assertEqual(wire["call_id"], result.call_id)
        self.assertEqual(wire["duration_ms"], result.duration_ms)
        self.assertEqual("completed", result.status)
        self.assertFalse(result.is_error)
        self.assertEqual(PLACEHOLDER, result.text)  # the tool's own output is what we redacted
        self.assertEqual(["text"], [part.type for part in result.content])
        self.assertIsNone(result.data)

    def test_a_recorded_business_failure_arrives_as_text(self) -> None:
        wire = recorded("mcp-call-business-failure.json")["stdout"]
        with self.assertRaises(ToolError) as caught:
            self.session.mcp["MongoDB"].list_databases(connectionId="no-such-value")
        self.assertEqual(recorded("mcp-call-business-failure.json")["stdout"]["text"], caught.exception.message)
        self.assertEqual(recorded("mcp-call-business-failure.json")["stdout"]["call_id"], caught.exception.call_id)
        self.assertEqual("MongoDB_list_databases", caught.exception.tool)  # the script-facing name

    def test_the_arguments_the_sdk_sends_are_the_ones_the_recordings_answered(self) -> None:
        self.session.mcp["kubernetes"].namespaces_list()
        with self.assertRaises(ToolError):  # the only MongoDB call on record is a business failure
            self.session.mcp["MongoDB"].list_databases(connectionId="no-such-value")
        calls = [call for call in self.sent_calls() if call["argv"][:2] == ["mcp", "call"]]
        self.assertEqual(2, len(calls))
        for call, fixture in zip(calls, ("mcp-call-kubernetes-namespaces-list.json",
                                         "mcp-call-business-failure.json")):
            wire = recorded(fixture)
            self.assertEqual(wire["argv"][:3], call["argv"][:3])
            self.assertIn("--args-file", call["argv"])
            self.assertEqual(json.loads(wire["stdin"]), json.loads(call["stdin"]))

    # ---------- error envelopes ----------

    def test_a_recorded_not_found_envelope_is_a_not_found_error(self) -> None:
        # white-box: the namespace layer would refuse an unknown server before asking the CLI
        with self.assertRaises(ToolNotFoundError) as caught:
            self.session._require(["mcp", "describe", "no-such-server/no-such-tool"])
        self.assertEqual(recorded("mcp-describe-unknown.json")["stdout"]["error"]["message"], str(caught.exception))

    def test_a_recorded_task_poll_not_found_is_a_not_found_error(self) -> None:
        # the polling id is the fixture's own: the recordings answer an argv, not a meaning
        unknown = recorded("agent-status-unknown.json")["argv"][2]
        with self.assertRaises(ToolNotFoundError):
            Task(session=self.session, task_id=unknown).poll()

    def test_a_recorded_usage_error_names_the_cli_version(self) -> None:
        # white-box for the same reason: only a wrong argv makes the CLI answer with exit 2
        usage = recorded("cli-usage-error.json")
        with self.assertRaises(CoreAiSessionError) as caught:
            self.session._require(["definitely-not-a-command"])
        message = str(caught.exception)
        self.assertIn("rejected this call", message)
        self.assertIn(usage["stderr"].splitlines()[0].strip(), message)  # stdout was empty: the text is stderr's
        self.assertIn(f"core-ai-cli {MANIFEST['captured']['version']}", message)

    def test_a_request_without_a_recording_fails_loudly(self) -> None:
        with self.assertRaises(CoreAiSessionError) as caught:
            self.session._require(["mcp", "search", "--on-server", "a-server-nobody-recorded"])
        self.assertIn("no recording for", str(caught.exception))

    # ---------- the recordings themselves ----------

    def test_the_manifest_matches_the_fixtures_on_disk(self) -> None:
        on_disk = {path.name for path in RECORDED.glob("*.json")} - {"manifest.json"}
        self.assertEqual(on_disk, {case["file"] for case in MANIFEST["cases"]})
        for case in MANIFEST["cases"]:
            self.assertEqual(case["argv"], recorded(case["file"])["argv"], case["file"])
        self.assertEqual("core-ai-cli", MANIFEST["captured"]["cli"])
        answered = subprocess.run([*REPLAY_COMMAND, "--version"], capture_output=True, text=True, check=False)
        self.assertEqual(MANIFEST["captured"]["version"], answered.stdout.strip())

    def test_the_replay_serves_the_repo_fixtures_without_the_environment_variable(self) -> None:
        os.environ.pop("CORE_AI_CLI_RECORDED", None)
        opened = CliSession(cli=REPLAY_COMMAND, poll_interval=0.01)
        try:
            self.assertEqual(8, len([tool for tool in opened.catalog().tools if tool.kind == "mcp"]))
        finally:
            opened.close()


class CurationAuditTest(unittest.TestCase):
    """`curate_recordings.leaks()` is what keeps a *future* capture from publishing private data."""

    def setUp(self) -> None:
        self.redactor = curate.Redactor(curate.RENAMES, curate.REDACT_KEYS, curate.JSON_STRING_KEYS)

    def test_it_names_what_must_never_reach_a_fixture(self) -> None:
        for leaky, expected in (
            ("write to jane.doe@example.com", "an email address"),
            (r"C:\Users\stephen\notes.md", "an absolute path"),
            ("https://internal.corp.example/api/v1/orders", "a private host"),
            ("BOCancelOrderRequest", "an internal class name"),
            ("550e8400-e29b-41d4-a716-446655440000", "a raw uuid"),
            ("9f8e7d6c5b4a39281706f5e4d3c2b1a0", "a raw 24+ hex id"),
            ("the order-service app", "a name RENAMES should have replaced"),
        ):
            found = curate.leaks(leaky, self.redactor)
            self.assertTrue(any(expected in item for item in found), f"{leaky!r} -> {found}")

    def test_it_leaves_what_a_fixture_is_allowed_to_say(self) -> None:
        allowed = " ".join((
            PLACEHOLDER, "https://[host]/", "https://www.mongodb.com/docs/manual/",
            "restaurant-api/reviews/cancel", "MongoDB/list-databases",
            "000000000000000000000001",              # a pseudonymised 24+ hex id
            "00000000-0000-4000-8000-000000000001",  # a pseudonymised uuid
        ))
        self.assertEqual([], curate.leaks(allowed, self.redactor))
