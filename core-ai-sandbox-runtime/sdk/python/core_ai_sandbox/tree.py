"""Catalog-driven namespace tree shared by the real, async and fake sessions.

The capabilities of a session are defined only by the server's ``/catalog``: no tool name,
server name or schema is baked in here, so an agent's mount change shows up on the script's
next ``session()`` without an SDK release.
"""

from __future__ import annotations

import difflib
import json
import logging
from typing import Any, Optional, Union

from .errors import ToolNotFoundError
from .models import Catalog, CatalogGroup, LlmUsage, SessionInfo, ToolDetail, ToolResult, ToolSummary, content_parts

LOGGER = logging.getLogger("core_ai_sandbox")

RESERVED_CALL_ARGS = ("timeout", "wait", "wait_timeout")
CONTRACT_VERSION = "1"
DEFAULT_TIMEOUT = 120
MAX_TIMEOUT = 600
DEFAULT_AGENT_TIMEOUT = 300
DEFAULT_WAIT_TIMEOUT = 1800


def _normalize_key(key: str) -> list[str]:
    """``-`` and ``_`` are interchangeable in every namespace key."""
    return [key, key.replace("-", "_"), key.replace("_", "-")]


def _lookup(children: dict[str, "Node"], key: str) -> Optional["Node"]:
    if key in children:
        return children[key]
    for candidate in _normalize_key(key):
        matches = [name for name in children if name.lower() == candidate.lower()]
        if len(matches) == 1:
            return children[matches[0]]
    return None


def _suggestions(children: dict[str, "Node"], key: str) -> str:
    candidates = difflib.get_close_matches(key, list(children), n=3, cutoff=0.3)
    if not candidates:
        candidates = sorted(children)[:3]
    return ", ".join(candidates) if candidates else "(none)"


class Node:
    """A lazily-resolved namespace level: attribute/item access walks the catalog tree."""

    def __init__(self, session: "_SessionBase", kind: str, key: str,
                 entry: Optional[ToolSummary] = None, children: Optional[dict[str, "Node"]] = None) -> None:
        self._session = session
        self.kind = kind
        self.key = key
        self._entry = entry
        self._children = children if children is not None else {}

    def __getattr__(self, item: str) -> "Node":
        if item.startswith("_"):
            raise AttributeError(item)
        return self._child(item)

    def __getitem__(self, item: str) -> "Node":
        return self._child(item)

    def _child(self, item: str) -> "Node":
        child = _lookup(self._children, item)
        if child is None:
            raise ToolNotFoundError(
                f"{self._entry_path()} has no '{item}'; did you mean: {_suggestions(self._children, item)}?"
            )
        return child

    def _entry_path(self) -> str:
        trail = [self.kind] if self.kind else []
        return ".".join(trail + [self.key])

    def __call__(self, **kwargs: Any) -> Any:
        if self._entry is None:
            raise TypeError(
                f"{self._entry_path()} is a group, not a tool; available: "
                f"{', '.join(sorted(self._children)) or '(empty)'}"
            )
        return self._session._invoke_node(self, self._entry, dict(kwargs))

    def __repr__(self) -> str:
        if self._entry is not None:
            return f"<tool {self._entry.name}>"
        return f"<group {self._entry_path()} ({len(self._children)} children)>"


class AgentNode(Node):
    """``s.agent["review-responder"].run("...")`` — the sub-agent tool takes a query."""

    def run(self, task: str, wait: bool = True, timeout: Optional[int] = None, **kwargs: Any) -> Any:
        arguments = {"query": task}
        arguments.update(kwargs)
        if timeout is not None:
            arguments["timeout"] = timeout
        return self._session._invoke_node(self, self._entry, arguments, wait=wait, timeout=timeout)

    def __call__(self, task: str, **kwargs: Any) -> Any:
        return self.run(task, **kwargs)


class FilesNamespace:
    """Publish sandbox files as session artifacts, for tools that need a URL."""

    PUBLISH_TOOL = "submit_artifacts"

    def __init__(self, session: "_SessionBase") -> None:
        self._session = session

    def publish(self, path: str, *, title: Optional[str] = None, name: Optional[str] = None,
                content_type: Optional[str] = None, description: Optional[str] = None) -> str:
        entry = self._session.find_entry(self.PUBLISH_TOOL)
        if entry is None:
            raise ToolNotFoundError(
                f"{self.PUBLISH_TOOL} is not in this session's tool set, so files cannot be published"
            )
        artifact: dict[str, Any] = {"path": path}
        if title:
            artifact["title"] = title
        if name:
            artifact["name"] = name
        if content_type:
            artifact["content_type"] = content_type
        if description:
            artifact["description"] = description
        result = self._session.call(entry.name, {"artifacts": [artifact]})
        payload = result.data if isinstance(result.data, dict) else {}
        submitted = payload.get("submitted") or []
        if not submitted:
            failed = payload.get("failed") or []
            reason = failed[0].get("error") if failed else (result.text or "no artifact submitted")
            raise self._session.tool_error(result, entry.name, f"failed to publish {path}: {reason}")
        return submitted[0]["download_url"]

    def __repr__(self) -> str:
        return "<files namespace>"


class _SessionBase:
    """Catalog cache, namespace tree, validation and result mapping.

    Transport is left to the subclasses (`Session`, `AsyncSession`, `FakeSession`); everything
    else — including which argument is the SDK's own and which belongs to the tool — is shared
    so all three behave identically.
    """

    _node_class: type = Node
    _agent_node_class: type = AgentNode
    _files_class: type = FilesNamespace

    def __init__(self, session_id: str = "", agent_name: str = "") -> None:
        self._catalog: Optional[Catalog] = None
        self._details: dict[str, Optional[ToolDetail]] = {}
        self._tree: dict[str, Node] = {}
        self.session_id = session_id
        self.agent_name = agent_name

    # ---------- catalog ----------

    def _load_catalog(self, payload: dict[str, Any]) -> Catalog:
        catalog = Catalog(
            session_id=payload.get("session_id", ""),
            agent_name=payload.get("agent_name", ""),
            sandbox_id=payload.get("sandbox_id", ""),
            sandbox_state=payload.get("sandbox_state", ""),
            expires_at=payload.get("expires_at"),
            groups=[
                CatalogGroup(
                    kind=item.get("kind", ""),
                    group=item.get("group", ""),
                    path=item.get("path", ""),
                    count=item.get("count", 0),
                )
                for item in payload.get("groups") or []
            ],
            tools=[
                ToolSummary(
                    name=item["name"],
                    kind=item.get("kind", ""),
                    group=item.get("group", ""),
                    path=item.get("path", ""),
                    ref_id=item.get("ref_id", ""),
                    description=item.get("description", ""),
                    exposure=(item.get("exposure") or "direct").lower(),
                )
                for item in payload.get("tools") or []
            ],
        )
        self._catalog = catalog
        self._details.clear()
        self._tree = self._build_tree(catalog)
        return catalog

    def _require_catalog(self) -> Catalog:
        if self._catalog is None:
            raise RuntimeError("catalog is not loaded yet")
        return self._catalog

    def _build_tree(self, catalog: Catalog) -> dict[str, Node]:
        roots: dict[str, Node] = {}
        for entry in catalog.tools:
            if entry.exposure == "hidden":
                continue
            root = roots.setdefault(entry.kind, self._node_class(self, entry.kind, entry.kind))
            segments = [segment for segment in (entry.path or entry.name).split("/") if segment]
            if not segments:
                segments = [entry.name]
            self._insert(root, segments, entry)
        return roots

    def _insert(self, root: Node, segments: list[str], entry: ToolSummary) -> None:
        node = root
        for depth, segment in enumerate(segments):
            leaf = depth == len(segments) - 1
            child = _lookup(node._children, segment)
            if child is None:
                node_class = self._agent_node_class if (leaf and entry.kind == "agent") else self._node_class
                child = node_class(self, entry.kind, segment)
                node._children[segment] = child
            if leaf:
                child._entry = entry
            node = child

    # ---------- namespace access ----------

    @property
    def mcp(self) -> Node:
        return self._root("mcp")

    @property
    def api(self) -> Node:
        return self._root("api")

    @property
    def llm_call(self) -> Node:
        return self._root("llm_call")

    @property
    def agent(self) -> Node:
        return self._root("agent")

    @property
    def builtin(self) -> Node:
        return self._root("builtin")

    @property
    def files(self) -> FilesNamespace:
        return self._files_class(self)

    def _root(self, kind: str) -> Node:
        self._require_catalog()
        root = self._tree.get(kind)
        if root is None:
            raise ToolNotFoundError(f"this session has no '{kind}' tools (kinds: {', '.join(sorted(self._tree))})")
        return root

    def find_entry(self, name: str) -> Optional[ToolSummary]:
        entry = self._require_catalog().find(name)
        if entry is None or entry.exposure == "hidden":
            return None
        return entry

    def tool(self, name: str):
        entry = self.find_entry(name)
        if entry is None:
            catalog = self._require_catalog()
            close = difflib.get_close_matches(name, catalog.names(), n=3, cutoff=0.3)
            raise ToolNotFoundError(f"'{name}' is not in this session's catalog; did you mean: {', '.join(close) or '(none)'}")

        def call(**kwargs: Any) -> Any:
            return self._invoke_node(None, entry, dict(kwargs))

        call.__name__ = entry.name
        call.__doc__ = entry.description
        return call

    # ---------- invocation ----------

    def _invoke_node(self, node: Optional[Node], entry: Optional[ToolSummary], arguments: dict[str, Any],
                     wait: bool = True, timeout: Optional[int] = None) -> Any:
        assert entry is not None
        arguments, controls = self._split_reserved(entry, arguments, timeout)
        self._validate(entry, arguments)
        return self._invoke(
            entry,
            arguments,
            timeout=controls.get("timeout"),
            wait=controls.get("wait", wait),
            wait_timeout=controls.get("wait_timeout", DEFAULT_WAIT_TIMEOUT),
        )

    def _split_reserved(self, entry: ToolSummary, arguments: dict[str, Any],
                        timeout: Optional[int]) -> tuple[dict[str, Any], dict[str, Any]]:
        """``timeout``/``wait`` are the SDK's unless the tool's own schema declares them.

        ``run_bash(timeout=5000)`` must keep meaning "the sandbox bash timeout", so the tool's
        schema wins whenever the name is a real parameter.
        """
        properties = self._schema_properties(entry)
        controls: dict[str, Any] = {}
        for key in RESERVED_CALL_ARGS:
            if key in arguments and (properties is None or key not in properties):
                controls[key] = arguments.pop(key)
        if timeout is not None:
            controls["timeout"] = timeout
        if entry.kind == "agent" and "timeout" not in controls:
            controls["timeout"] = DEFAULT_AGENT_TIMEOUT
        return arguments, controls

    def _validate(self, entry: ToolSummary, arguments: dict[str, Any]) -> None:
        schema = self._schema(entry)
        if not schema:
            return
        properties = schema.get("properties") or {}
        missing = [name for name in schema.get("required") or [] if name not in arguments]
        if missing:
            raise TypeError(f"{entry.name} is missing required argument(s): {', '.join(missing)}")
        for name, value in arguments.items():
            spec = properties.get(name)
            if not isinstance(spec, dict) or value is None:
                continue
            expected = spec.get("type")
            if expected and not _accepts(expected, value):
                raise TypeError(
                    f"{entry.name}({name}=...) expects {expected}, got {type(value).__name__}"
                )

    def _schema(self, entry: ToolSummary) -> Optional[dict[str, Any]]:
        detail = self._detail(entry)
        if detail is None:
            return None
        return detail.input_schema

    def _schema_properties(self, entry: ToolSummary) -> Optional[dict[str, Any]]:
        schema = self._schema(entry)
        if not schema:
            return None
        properties = schema.get("properties")
        return properties if isinstance(properties, dict) else None

    def _detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        if entry.name not in self._details:
            self._details[entry.name] = self._fetch_detail(entry)
        return self._details[entry.name]

    def _fetch_detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        return None  # only the HTTP sessions can describe; validation is then skipped

    # ---------- result mapping ----------

    def _to_result(self, payload: dict[str, Any]) -> ToolResult:
        usage = payload.get("llm_usage")
        return ToolResult(
            call_id=payload.get("call_id", ""),
            status=payload.get("status", "completed"),
            text=payload.get("text") or "",
            content=content_parts(payload.get("content")),
            duration_ms=payload.get("duration_ms") or 0,
            task_id=payload.get("task_id"),
            is_error=bool(payload.get("is_error")),
            error_code=payload.get("error_code"),
            error_message=payload.get("error_message"),
            status_code=payload.get("status_code"),
            llm_usage=LlmUsage(
                model=usage.get("model"),
                input_tokens=usage.get("input_tokens"),
                output_tokens=usage.get("output_tokens"),
                cost=usage.get("cost"),
            ) if isinstance(usage, dict) else None,
        )

    def tool_error(self, result: ToolResult, tool: str, message: Optional[str] = None):
        from .errors import ToolError

        entry = self.find_entry(tool)
        return ToolError(
            message or result.error_message or result.text or f"{tool} failed",
            tool=tool,
            kind=entry.kind if entry else None,
            status_code=result.status_code,
            call_id=result.call_id,
            task_id=result.task_id if result.status == "timeout" else None,
        )

    def pending_error(self, tool: str, kind: str, task_id: str, waited: float):
        from .errors import ToolError

        return ToolError(
            f"{tool} is still running after {waited:.0f}s; poll it with wait=False and task {task_id}",
            tool=tool,
            kind=kind,
            status_code=504,
            task_id=task_id,
        )

    def _finish(self, result: ToolResult, entry: ToolSummary, wait: bool,
                wait_timeout: float) -> Union[ToolResult, "Task"]:
        """Turn a payload into either a result, a `Task`, or an exception — never a half result."""
        from .models import Task

        if result.status == "pending" and result.task_id:
            task = Task(session=self, task_id=result.task_id, tool=entry.name, status="pending")
            if not wait:
                return task
            outcome = task.wait(wait_timeout)
            if outcome.status == "pending":
                raise self.pending_error(entry.name, entry.kind, task.task_id, wait_timeout)
            return outcome
        if result.is_error:
            raise self.tool_error(result, entry.name)
        return result

    @property
    def poll_interval(self) -> float:
        return 2.0

    def is_async(self) -> bool:
        return False

    def _acknowledge_contract(self, contract_version: Optional[str]) -> None:
        if not contract_version:
            return
        major = str(contract_version).split(".")[0]
        if major != CONTRACT_VERSION:
            LOGGER.warning(
                "core-ai sandbox hub contract %s does not match this SDK (expects %s.x); "
                "upgrade the sandbox image or pin an older SDK",
                contract_version,
                CONTRACT_VERSION,
            )


def _accepts(expected: Any, value: Any) -> bool:
    if isinstance(expected, list):
        return any(_accepts(item, value) for item in expected)
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "array":
        return isinstance(value, (list, tuple))
    if expected == "object":
        return isinstance(value, dict)
    return True


def parse_detail(payload: dict[str, Any]) -> ToolDetail:
    schema = payload.get("input_schema")
    parsed = None
    if isinstance(schema, str) and schema.strip():
        try:
            parsed = json.loads(schema)
        except ValueError:
            parsed = None
    elif isinstance(schema, dict):
        parsed = schema
    return ToolDetail(
        name=payload.get("name", ""),
        kind=payload.get("kind", ""),
        group=payload.get("group", ""),
        path=payload.get("path", ""),
        ref_id=payload.get("ref_id", ""),
        description=payload.get("description", ""),
        exposure=(payload.get("exposure") or "direct").lower(),
        input_schema=parsed,
        timeout_seconds=payload.get("timeout_seconds") or DEFAULT_TIMEOUT,
    )


def parse_session_info(payload: dict[str, Any]) -> SessionInfo:
    return SessionInfo(
        session_id=payload.get("session_id", ""),
        agent_name=payload.get("agent_name", ""),
        sandbox_id=payload.get("sandbox_id", ""),
        sandbox_state=payload.get("sandbox_state", ""),
        expires_at=payload.get("expires_at"),
        tool_count=payload.get("tool_count") or 0,
        contract_version=payload.get("contract_version"),
        caller=payload.get("caller") or {},
    )
