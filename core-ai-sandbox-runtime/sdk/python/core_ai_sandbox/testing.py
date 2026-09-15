"""`FakeSession` — the offline stand-in used by tests and by `--dry-run` style local runs.

It shares the whole namespace tree, validation and result mapping with the real sessions, so a
test exercises the same call path a script would; only the transport is replaced by scripted
answers:

    fake = FakeSession.from_fixture("catalog.json")
    fake.mcp["google-gbp"].get_reviews.returns({"reviews": [{"rating": 5}]})
    fake.llm_call["seo-title-semantics"].raises(ToolError("model overloaded"))

    result = fake.call("mcp.googleapis_get_reviews", {"location": "x"})
    assert fake.calls[0].arguments == {"location": "x"}
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional, Union

from .errors import CoreAiSandboxError
from .models import Catalog, SessionInfo, Task, ToolDetail, ToolResult, ToolSummary
from .tree import (
    CONTRACT_VERSION,
    AgentNode,
    FilesNamespace,
    Node,
    _SessionBase,
    parse_detail,
    parse_session_info,
)

FIXTURE_DIR = Path(__file__).resolve().parents[2] / "contract-fixtures"


def _read_fixture(name: str) -> dict[str, Any]:
    path = Path(name)
    if not path.is_absolute():
        path = FIXTURE_DIR / name
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


@dataclass
class FakeCall:
    tool: str
    kind: str = ""
    arguments: dict[str, Any] = field(default_factory=dict)
    timeout: Optional[int] = None
    wait: bool = True
    ref_id: str = ""


class _Scripted:
    """Per-tool behaviour queue: the last registered behaviour repeats forever."""

    def __init__(self) -> None:
        self.behaviours: list[Any] = []

    def returns(self, value: Any) -> "_Scripted":
        self.behaviours.append(_AsReturn(value))
        return self

    def raises(self, error: Union[BaseException, type]) -> "_Scripted":
        self.behaviours.append(error)
        return self

    def next(self) -> Any:
        if not self.behaviours:
            return None
        if len(self.behaviours) == 1:
            return self.behaviours[0]
        return self.behaviours.pop(0)


class _AsReturn:
    def __init__(self, value: Any) -> None:
        self.value = value


class FakeNode(Node):
    def returns(self, value: Any) -> "FakeNode":
        self._scripted().returns(value)
        return self

    def raises(self, error: Union[BaseException, type]) -> "FakeNode":
        self._scripted().raises(error)
        return self

    def _scripted(self) -> _Scripted:
        session = self._session
        assert isinstance(session, FakeSession)
        if self._entry is None:
            raise CoreAiSandboxError(f"{self._entry_path()} is a group; script a tool instead")
        return session.script.setdefault(self._entry.name, _Scripted())


class FakeAgentNode(FakeNode, AgentNode):
    pass


class FakeFilesNamespace(FilesNamespace):
    def returns(self, download_url: str, *, file_id: str = "fake-file", name: str = "fake.bin") -> "FakeFilesNamespace":
        session = self._session
        assert isinstance(session, FakeSession)
        session.script.setdefault(
            self.PUBLISH_TOOL,
            _Scripted(),
        ).returns(
            {
                "submitted": [
                    {
                        "path": "/tmp/fake",
                        "file_id": file_id,
                        "file_name": name,
                        "download_url": download_url,
                    }
                ],
                "failed": [],
            }
        )
        return self


class FakeSession(_SessionBase):
    """Records every call and answers from scripts, with no network access at all."""

    _node_class = FakeNode
    _agent_node_class = FakeAgentNode
    _files_class = FakeFilesNamespace

    def __init__(self, catalog: Optional[dict[str, Any]] = None,
                 details: Optional[list[dict[str, Any]]] = None) -> None:
        super().__init__()
        self.calls: list[FakeCall] = []
        self.script: dict[str, _Scripted] = {}
        self.tasks: dict[str, dict[str, Any]] = {}
        self._fixture = catalog or {}
        self._detail_fixture = {item.get("name"): item for item in details or []}
        self._load_catalog(self._fixture)

    @classmethod
    def from_fixture(cls, name: str, details: Optional[list[str]] = None) -> "FakeSession":
        return cls(catalog=_read_fixture(name), details=[_read_fixture(item) for item in details or []])

    # ---------- catalog ----------

    def _require_catalog(self) -> Catalog:
        return self._catalog  # type: ignore[return-value]

    def catalog(self) -> Catalog:
        return self._require_catalog()

    def refresh(self) -> Catalog:
        return self._require_catalog()

    def _fetch_detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        item = self._detail_fixture.get(entry.name)
        if item is None:
            for candidate in self._fixture.get("tools") or []:
                if candidate.get("name") == entry.name:
                    item = candidate
                    break
        if item is None:
            return None
        detail = parse_detail(item)
        self._details[entry.name] = detail
        return detail

    @property
    def poll_interval(self) -> float:
        return 0.001

    def script_task(self, task_id: str, payload: dict[str, Any]) -> "FakeSession":
        self.tasks[task_id] = payload
        return self

    def _fetch_task(self, task_id: str) -> dict[str, Any]:
        if task_id not in self.tasks:
            raise CoreAiSandboxError(f"task {task_id} has no scripted payload")
        return self.tasks[task_id]

    # ---------- transport ----------

    def _invoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                wait: bool = True, wait_timeout: float = 1200.0) -> Union[ToolResult, Task]:
        self.calls.append(
            FakeCall(
                tool=entry.name,
                kind=entry.kind,
                arguments=dict(arguments),
                timeout=timeout,
                wait=wait,
                ref_id=entry.ref_id,
            )
        )
        behaviour = self.script.get(entry.name, _Scripted()).next()
        if behaviour is None:
            raise CoreAiSandboxError(
                f"{entry.name} has no scripted answer; call `.returns(...)` or `.raises(...)` first"
            )
        if isinstance(behaviour, _AsReturn):
            return self._finish(self._as_result(behaviour.value), entry, wait, wait_timeout)
        raise behaviour

    @staticmethod
    def _as_result(value: Any) -> ToolResult:
        if isinstance(value, ToolResult):
            return value
        if isinstance(value, str):
            text = value
        elif value is None:
            text = ""
        else:
            text = json.dumps(value, ensure_ascii=False)
        return ToolResult(call_id="fake-call", text=text, duration_ms=1)

    # ---------- helpers ----------

    def calls_of(self, tool: str) -> list[FakeCall]:
        return [call for call in self.calls if call.tool == tool]

    def call(self, name: str, arguments: Optional[dict[str, Any]] = None, **kwargs: Any) -> Any:
        entry = self._resolve(name)
        merged = dict(arguments or {})
        merged.update(kwargs)
        return self._invoke_node(None, entry, merged)

    def _resolve(self, name: str) -> ToolSummary:
        """Accept the catalog name, the tool path, or a ``kind.leaf`` shorthand."""
        entry = self.find_entry(name)
        if entry is not None:
            return entry
        if "." in name:
            kind, _, leaf = name.rpartition(".")
            for tool in self._require_catalog().tools:
                if tool.kind == kind and (tool.name == leaf or tool.name.endswith(leaf)):
                    return tool
        raise CoreAiSandboxError(f"'{name}' is not in this fake catalog")

    def me(self) -> SessionInfo:
        return parse_session_info(
            {
                "session_id": self._fixture.get("session_id", "fake-session"),
                "agent_name": self._fixture.get("agent_name", "fake-agent"),
                "sandbox_id": self._fixture.get("sandbox_id", "fake-sandbox"),
                "sandbox_state": self._fixture.get("sandbox_state", "ready"),
                "expires_at": self._fixture.get("expires_at"),
                "contract_version": self._fixture.get("contract_version", CONTRACT_VERSION),
                "caller": self._fixture.get("caller") or {"external_id": "store-001"},
                "tool_count": len(self._fixture.get("tools") or []),
            }
        )

    def close(self) -> None:
        return None
