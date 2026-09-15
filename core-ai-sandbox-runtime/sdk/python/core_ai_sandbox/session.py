"""`core_ai_sandbox` — call this session's agent-configured capabilities from a sandbox script.

A script never holds credentials and never imports vendor SDKs: it talks to the sandbox
runtime's loopback proxy (``CORE_AI_HUB``), which forwards to the core-ai server's
``/api/sandbox-hub/*`` with a session token the runtime injected, not the script.

    from core_ai_sandbox import session

    s = session()
    reviews = s.mcp["google-gbp"].get_reviews(location="ChIJ...")
    page = s.llm_call["seo-title-semantics"](query=reviews.text[:2000])
    s.files.publish("report.html", title="月报")

Call parameters follow the tool's own schema; ``timeout``/``wait``/``wait_timeout`` are the
SDK's own arguments unless the tool declares them (``run_bash(timeout=5000)`` stays the
sandbox bash timeout).
"""

from __future__ import annotations

import json
import os
import sys
import time
from importlib.metadata import PackageNotFoundError, version as package_version
from typing import Any, Optional, Union
from urllib.parse import quote

import httpx

from .errors import CoreAiSandboxError, NotBoundError, ToolError, ToolNotFoundError
from .models import Catalog, SessionInfo, Task, ToolDetail, ToolResult, ToolSummary
from .tree import (
    CONTRACT_VERSION,
    DEFAULT_TIMEOUT,
    DEFAULT_WAIT_TIMEOUT,
    MAX_TIMEOUT,
    _SessionBase,
    parse_detail,
    parse_session_info,
)

DEFAULT_HUB_URL = "http://127.0.0.1:8081/hub"
NOT_BOUND_RETRIES = 3
NOT_BOUND_RETRY_DELAY = 1.0
SUMMARY_FIELDS = ("name", "kind", "group", "path", "ref_id", "description")


def _summaries(payload: dict[str, Any]) -> list[ToolSummary]:
    return [
        ToolSummary(
            **{field: item.get(field) or "" for field in SUMMARY_FIELDS},
            exposure=(item.get("exposure") or "direct").lower(),
        )
        for item in payload.get("tools") or []
    ]

__all__ = [
    "session",
    "async_session",
    "Session",
    "AsyncSession",
    "Task",
    "ToolResult",
    "ToolSummary",
    "ToolDetail",
    "Catalog",
    "SessionInfo",
    "CoreAiSandboxError",
    "NotBoundError",
    "ToolError",
    "ToolNotFoundError",
]


def sdk_version() -> str:
    try:
        return package_version("core-ai-sandbox")
    except PackageNotFoundError:
        return "0.0.0"


def _resolve_hub_url(hub_url: Optional[str]) -> str:
    url = hub_url or os.environ.get("CORE_AI_HUB") or ""
    if not url:
        raise CoreAiSandboxError(
            "CORE_AI_HUB is not set, so this process is not running inside a core-ai sandbox. "
            "Scripts must run through the session's sandbox (for example the run_bash tool); "
            "the hub cannot be reached from anywhere else."
        )
    return url.rstrip("/")


def _script_name() -> Optional[str]:
    if not sys.argv:
        return None
    name = os.path.basename(sys.argv[0] or "")
    if not name or name in ("-c", "-m") or name.startswith("-") or name.endswith("python"):
        return None
    return name


def _headers(script: Optional[str]) -> dict[str, str]:
    headers = {"X-Core-AI-Client": f"sdk-python/{sdk_version()}"}
    name = script or _script_name()
    if name:
        headers["X-Core-AI-Script"] = name[:200]
    return headers


def _error_payload(response: httpx.Response) -> tuple[str, str]:
    try:
        payload = response.json()
    except ValueError:
        text = response.text or ""
        return text[:200] or f"HTTP {response.status_code}", ""
    if isinstance(payload, dict):
        code = str(payload.get("error") or payload.get("error_code") or "")
        message = payload.get("message") or payload.get("error_message") or json.dumps(payload)
        return str(message), code
    return str(payload)[:200], ""


class Session(_SessionBase):
    """Synchronous hub session. One ``httpx.Client`` per session, closed via ``close()``."""

    def __init__(self, hub_url: Optional[str] = None, timeout: int = DEFAULT_TIMEOUT,
                 script: Optional[str] = None, wait_timeout: float = DEFAULT_WAIT_TIMEOUT,
                 client: Optional[httpx.Client] = None) -> None:
        super().__init__()
        self.hub_url = _resolve_hub_url(hub_url)
        self.timeout = max(1, min(int(timeout), MAX_TIMEOUT))
        self.wait_timeout = wait_timeout
        self._client = client or httpx.Client(timeout=httpx.Timeout(float(self.timeout) + 60.0))
        self._headers = _headers(script)

    # ---------- HTTP ----------

    def _request(self, method: str, path: str, json_body: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        attempt = 0
        while True:
            attempt += 1
            try:
                response = self._client.request(method, self.hub_url + path, json=json_body, headers=self._headers)
            except httpx.HTTPError as error:
                raise CoreAiSandboxError(f"hub request {method} {path} failed: {error}") from error
            if response.status_code == 503 and self._is_not_bound(response):
                if attempt <= NOT_BOUND_RETRIES:
                    time.sleep(NOT_BOUND_RETRY_DELAY)
                    continue
                raise NotBoundError(
                    "this sandbox is not bound to a session yet; retry once the agent session is ready"
                )
            if response.status_code >= 400:
                self._raise_http_error(response, path)
            return self._json(response, path)

    @staticmethod
    def _is_not_bound(response: httpx.Response) -> bool:
        return "not_bound" in (response.text or "")

    @staticmethod
    def _json(response: httpx.Response, path: str) -> dict[str, Any]:
        try:
            payload = response.json()
        except ValueError as error:
            raise CoreAiSandboxError(f"hub returned a non-JSON body for {path}: {response.text[:200]}") from error
        if not isinstance(payload, dict):
            raise CoreAiSandboxError(f"hub returned an unexpected body for {path}")
        return payload

    def _raise_http_error(self, response: httpx.Response, path: str) -> None:
        message, code = _error_payload(response)
        status = response.status_code
        if status == 401:
            raise NotBoundError(f"the session token was rejected by the hub (401): {message}")
        if status == 404:
            raise ToolNotFoundError(f"not found: {message}")
        if status in (429, 403, 408, 504):
            raise ToolError(message, status_code=status)
        raise CoreAiSandboxError(f"hub request {path} failed with HTTP {status}: {message} ({code})")

    # ---------- session metadata ----------

    def me(self) -> SessionInfo:
        payload = self._request("GET", "/me")
        info = parse_session_info(payload)
        self.session_id = info.session_id
        self.agent_name = info.agent_name
        self._acknowledge_contract(info.contract_version)
        return info

    def catalog(self) -> Catalog:
        if self._catalog is not None:
            return self._catalog
        return self._load(self._request("GET", "/catalog"))

    def refresh(self) -> Catalog:
        return self._load(self._request("GET", "/catalog"))

    def _load(self, payload: dict[str, Any]) -> Catalog:
        catalog = self._load_catalog(payload)
        self.session_id = catalog.session_id
        self.agent_name = catalog.agent_name
        return catalog

    def tools(self, query: Optional[str] = None, kind: Optional[str] = None) -> list[ToolSummary]:
        params = []
        if query:
            params.append("query=" + quote(query))
        if kind:
            params.append("kind=" + quote(kind))
        path = "/tools" + ("?" + "&".join(params) if params else "")
        return _summaries(self._request("GET", path))

    def describe(self, name: str) -> ToolDetail:
        entry = self.find_entry(name)
        target = entry.name if entry is not None else name
        payload = self._request("GET", f"/tools/{quote(target, safe='')}")
        detail = parse_detail(payload)
        self._details[detail.name] = detail
        return detail

    # ---------- invocation ----------

    def _require_catalog(self) -> Catalog:
        if self._catalog is None:
            return self.catalog()
        return self._catalog

    def _fetch_detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        try:
            payload = self._request("GET", f"/tools/{quote(entry.name, safe='')}")
        except (ToolNotFoundError, CoreAiSandboxError):
            return None
        return parse_detail(payload)

    def _invoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                wait: bool = True, wait_timeout: float = DEFAULT_WAIT_TIMEOUT) -> Union[ToolResult, Task]:
        payload = self._request(
            "POST",
            f"/tools/{quote(entry.name, safe='')}/call",
            {"arguments": json.dumps(arguments), "timeout_seconds": self._effective_timeout(timeout)},
        )
        return self._finish(self._to_result(payload), entry, wait, wait_timeout)

    def call(self, name: str, arguments: Optional[dict[str, Any]] = None, **kwargs: Any) -> Union[ToolResult, Task]:
        """Call a tool by catalog name. ``wait=False`` returns a `Task` when it is still pending."""
        entry = self.find_entry(name)
        if entry is None:
            raise ToolNotFoundError(f"'{name}' is not in this session's catalog")
        merged = dict(arguments or {})
        merged.update(kwargs)
        return self._invoke_node(None, entry, merged)

    def _fetch_task(self, task_id: str) -> dict[str, Any]:
        return self._request("GET", f"/tasks/{quote(task_id, safe='')}")

    def _effective_timeout(self, timeout: Optional[int]) -> int:
        return max(1, min(int(timeout or self.timeout), MAX_TIMEOUT))

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "Session":
        return self

    def __exit__(self, *exc_info: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        state = "loaded" if self._catalog is not None else "lazy"
        return f"<core_ai_sandbox.Session {self.hub_url} catalog={state}>"


class AsyncSession(Session):
    """``await``-friendly session: one ``AsyncClient``, parallel calls via ``asyncio.gather``.

    The catalog is loaded by `async_session()`/`refresh()`, so namespace access stays synchronous
    (``s.mcp["x"]`` cannot await) while every call is awaited.
    """

    def __init__(self, **kwargs: Any) -> None:
        client = kwargs.pop("client", None)
        super().__init__(client=client or httpx.AsyncClient(timeout=httpx.Timeout(float(kwargs.get("timeout", DEFAULT_TIMEOUT)) + 60.0)), **kwargs)

    async def _arequest(self, method: str, path: str, json_body: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        attempt = 0
        while True:
            attempt += 1
            try:
                response = await self._client.request(method, self.hub_url + path, json=json_body, headers=self._headers)
            except httpx.HTTPError as error:
                raise CoreAiSandboxError(f"hub request {method} {path} failed: {error}") from error
            if response.status_code == 503 and self._is_not_bound(response):
                if attempt <= NOT_BOUND_RETRIES:
                    await _async_sleep(NOT_BOUND_RETRY_DELAY)
                    continue
                raise NotBoundError(
                    "this sandbox is not bound to a session yet; retry once the agent session is ready"
                )
            if response.status_code >= 400:
                self._raise_http_error(response, path)
            return self._json(response, path)

    async def me(self) -> SessionInfo:  # type: ignore[override]
        payload = await self._arequest("GET", "/me")
        info = parse_session_info(payload)
        self.session_id = info.session_id
        self.agent_name = info.agent_name
        self._acknowledge_contract(info.contract_version)
        return info

    async def catalog(self) -> Catalog:  # type: ignore[override]
        if self._catalog is not None:
            return self._catalog
        return self._load(await self._arequest("GET", "/catalog"))

    async def refresh(self) -> Catalog:  # type: ignore[override]
        return self._load(await self._arequest("GET", "/catalog"))

    async def tools(self, query: Optional[str] = None, kind: Optional[str] = None) -> list[ToolSummary]:  # type: ignore[override]
        params = []
        if query:
            params.append("query=" + quote(query))
        if kind:
            params.append("kind=" + quote(kind))
        return _summaries(await self._arequest("GET", "/tools" + ("?" + "&".join(params) if params else "")))

    async def describe(self, name: str) -> ToolDetail:  # type: ignore[override]
        entry = self.find_entry(name)
        target = entry.name if entry is not None else name
        payload = await self._arequest("GET", f"/tools/{quote(target, safe='')}")
        detail = parse_detail(payload)
        self._details[detail.name] = detail
        return detail

    def _require_catalog(self) -> Catalog:
        if self._catalog is None:
            raise CoreAiSandboxError(
                "an AsyncSession loads its catalog asynchronously: use `s = await async_session()` "
                "or `await s.refresh()` before touching namespaces"
            )
        return self._catalog

    def is_async(self) -> bool:
        return True

    def _fetch_detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        return None  # a describe round trip cannot be awaited from a synchronous call path

    async def _ainvoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                       wait: bool = True, wait_timeout: float = DEFAULT_WAIT_TIMEOUT) -> Union[ToolResult, Task]:
        payload = await self._arequest(
            "POST",
            f"/tools/{quote(entry.name, safe='')}/call",
            {"arguments": json.dumps(arguments), "timeout_seconds": self._effective_timeout(timeout)},
        )
        return await self._afinish(self._to_result(payload), entry, wait, wait_timeout)

    async def _afinish(self, result: ToolResult, entry: ToolSummary, wait: bool,
                       wait_timeout: float) -> Union[ToolResult, Task]:
        if result.status == "pending" and result.task_id:
            task = Task(session=self, task_id=result.task_id, tool=entry.name, status="pending")
            if not wait:
                return task
            await self._await_task(task, wait_timeout)
            if task.result is None:
                raise self.pending_error(entry.name, entry.kind, task.task_id, wait_timeout)
            result = task.result
        if result.is_error:
            raise self.tool_error(result, entry.name)
        return result

    def _invoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                wait: bool = True, wait_timeout: float = DEFAULT_WAIT_TIMEOUT) -> Any:
        return self._ainvoke(entry, arguments, timeout=timeout, wait=wait, wait_timeout=wait_timeout)

    async def _await_task(self, task: Task, wait_timeout: float) -> None:
        deadline = time.monotonic() + wait_timeout
        while task.status == "pending":
            if time.monotonic() >= deadline:
                return
            await _async_sleep(task.session.poll_interval)
            payload = await self._arequest("GET", f"/tasks/{quote(task.task_id, safe='')}")
            task.status = payload.get("status", task.status)
            if task.status != "pending":
                task.result = self._to_result(payload)

    async def call(self, name: str, arguments: Optional[dict[str, Any]] = None, **kwargs: Any) -> Union[ToolResult, Task]:  # type: ignore[override]
        entry = self.find_entry(name)
        if entry is None:
            raise ToolNotFoundError(f"'{name}' is not in this session's catalog")
        merged = dict(arguments or {})
        merged.update(kwargs)
        return await self._invoke_node(None, entry, merged)

    async def _fetch_task(self, task_id: str) -> dict[str, Any]:  # type: ignore[override]
        return await self._arequest("GET", f"/tasks/{quote(task_id, safe='')}")

    async def aclose(self) -> None:
        await self._client.aclose()

    def close(self) -> None:
        raise CoreAiSandboxError("AsyncSession must be closed with `await s.aclose()`")

    async def __aenter__(self) -> "AsyncSession":
        await self.refresh()
        return self

    async def __aexit__(self, *exc_info: Any) -> None:
        await self.aclose()


async def _async_sleep(seconds: float) -> None:
    import asyncio

    await asyncio.sleep(seconds)


def session(hub_url: Optional[str] = None, *, timeout: int = DEFAULT_TIMEOUT,
            script: Optional[str] = None) -> Session:
    """Open the hub session of this sandbox. The catalog is fetched on first use."""
    return Session(hub_url=hub_url, timeout=timeout, script=script)


async def async_session(hub_url: Optional[str] = None, *, timeout: int = DEFAULT_TIMEOUT,
                        script: Optional[str] = None) -> AsyncSession:
    """Open the hub session and load its catalog (required before namespace access)."""
    opened = AsyncSession(hub_url=hub_url, timeout=timeout, script=script)
    await opened.refresh()
    return opened
