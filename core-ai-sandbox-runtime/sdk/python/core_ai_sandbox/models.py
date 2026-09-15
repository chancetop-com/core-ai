"""Data types shared by the session, the CLI contract and the test double."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional


@dataclass(frozen=True)
class LlmUsage:
    model: Optional[str] = None
    input_tokens: Optional[int] = None
    output_tokens: Optional[int] = None
    cost: Optional[float] = None


@dataclass(frozen=True)
class ContentPart:
    type: Optional[str] = None
    text: Optional[str] = None
    mime_type: Optional[str] = None
    data: Optional[str] = None


@dataclass
class ToolResult:
    """One tool call's outcome. ``.data`` is the only place a script should parse payloads."""

    call_id: str = ""
    status: str = "completed"
    text: str = ""
    content: list[ContentPart] = field(default_factory=list)
    duration_ms: int = 0
    task_id: Optional[str] = None
    llm_usage: Optional[LlmUsage] = None
    is_error: bool = False
    # `error_code` is the upstream/tool error code, `status_code` the upstream HTTP status for
    # API tools or the hub status for rejected calls (mirrors the CLI exit-code contract).
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    status_code: Optional[int] = None

    @property
    def data(self) -> Any:
        """``text`` parsed as JSON when it is JSON, else ``None`` (never raises)."""
        if not self.text:
            return None
        try:
            return json.loads(self.text)
        except ValueError:
            return None

    def __str__(self) -> str:
        return self.text


@dataclass(frozen=True)
class ToolSummary:
    name: str
    kind: str
    group: str = ""
    path: str = ""
    ref_id: str = ""
    description: str = ""
    exposure: str = ""

    @property
    def callable(self) -> bool:
        return self.exposure != "hidden"


@dataclass(frozen=True)
class ToolDetail(ToolSummary):
    input_schema: Optional[dict[str, Any]] = None
    timeout_seconds: int = 120


@dataclass(frozen=True)
class CatalogGroup:
    kind: str
    group: str = ""
    path: str = ""
    count: int = 0


@dataclass
class Catalog:
    session_id: str = ""
    agent_name: str = ""
    sandbox_id: str = ""
    sandbox_state: str = ""
    expires_at: Optional[str] = None
    groups: list[CatalogGroup] = field(default_factory=list)
    tools: list[ToolSummary] = field(default_factory=list)

    def by_kind(self, kind: str) -> list[ToolSummary]:
        return [tool for tool in self.tools if tool.kind == kind]

    def find(self, name_or_ref: str) -> Optional[ToolSummary]:
        for tool in self.tools:
            if tool.name == name_or_ref or tool.ref_id == name_or_ref or tool.path == name_or_ref:
                return tool
        return None

    def names(self) -> list[str]:
        return [tool.name for tool in self.tools]


@dataclass
class SessionInfo:
    session_id: str = ""
    agent_name: str = ""
    sandbox_id: str = ""
    sandbox_state: str = ""
    expires_at: Optional[str] = None
    tool_count: int = 0
    contract_version: Optional[str] = None
    caller: dict[str, Any] = field(default_factory=dict)


@dataclass
class Task:
    """A ``status=pending`` call: poll or wait for it instead of blocking the hub request.

    Only synchronous sessions can block on a task; an `AsyncSession` returns a `Task` for
    introspection and expects the caller to poll with ``await session.call(...)`` again.
    """

    session: Any
    task_id: str
    status: str = "pending"
    result: Optional[ToolResult] = None
    tool: Optional[str] = None

    def poll(self) -> "Task":
        _require_sync_task(self.session)
        payload = self.session._fetch_task(self.task_id)
        self.status = payload.get("status", self.status)
        if self.status != "pending":
            self.result = self.session._to_result(payload)
        return self

    def wait(self, timeout: float = 1800.0) -> ToolResult:
        import time

        _require_sync_task(self.session)
        deadline = time.monotonic() + timeout
        while self.status == "pending":
            if time.monotonic() >= deadline:
                break
            time.sleep(self.session.poll_interval)
            self.poll()
        if self.result is None:
            return ToolResult(
                call_id="",
                status="pending",
                task_id=self.task_id,
                text=f"task {self.task_id} is still pending after {timeout:.0f}s",
            )
        if self.result.is_error:
            raise self.session.tool_error(self.result, self.tool or "")
        return self.result


def _require_sync_task(session: Any) -> None:
    if session.is_async():
        from .errors import CoreAiSandboxError

        raise CoreAiSandboxError("an AsyncSession cannot block on a task; use wait=False and poll again")


def content_parts(raw: Optional[Iterable[dict[str, Any]]]) -> list[ContentPart]:
    parts = []
    for item in raw or []:
        parts.append(
            ContentPart(
                type=item.get("type"),
                text=item.get("text"),
                mime_type=item.get("mime_type"),
                data=item.get("data"),
            )
        )
    return parts
