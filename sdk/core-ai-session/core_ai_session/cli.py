"""The local backend: the same script, the same call surface, run through ``core-ai-cli``.

Inside a sandbox every call goes to the **session hub** — the runtime's loopback proxy to
``/api/sandbox-hub/*``, authenticated with a session token the runtime holds and the script
never sees (see `session.py`). Outside a sandbox there is no session and no runtime, but there
is ``core-ai-cli``, which speaks the same hub contract with *your* credentials.

`CliSession` is that second transport: identical namespace tree, identical validation, identical
result mapping — only the calls are ``core-ai-cli <leaf> ... --json`` subprocesses instead of
HTTP requests. A skill script therefore never has to know where it runs::

    from core_ai_session import session

    s = session()                      # sandbox: the session hub; locally: core-ai-cli
    reviews = s.mcp["google-gbp"].list_reviews(location="ChIJ...")

What does **not** follow the environment is the identity and the reach, and that difference is
deliberate rather than hidden:

* in a sandbox a call runs as the *session's* caller and can only reach what the *session agent*
  mounts;
* locally a call runs as **you**, against everything **you** can see, and costs your own quota.

That is why the first local session logs a warning, why ``CORE_AI_HUB`` always wins when it is
set, and why ``backend="cli"`` is refused while it is set. See ``docs/cn/design-sandbox-hub.md``
§5.8 for the full list of local/production differences.

Only ``builtin`` tools (``web_search``, ``submit_artifacts``) have no local counterpart: the
user-level hub has no such tools, so they are simply absent from the local catalog and raise
`ToolNotFoundError` instead of pretending to work.

When a call comes back wrong, ``record=DIR`` (or ``CORE_AI_CLI_RECORD=DIR``) captures every
exchange — the argv, the stdin, the exit code and the raw stdout — as ``DIR/0001-mcp-call.json``…
files. That is how a local misbehaviour becomes a bug report, and how the *real* envelopes in
``sdk/core-ai-session/contract-fixtures/recorded/`` were captured; a recording holds real tool output, so it stays
opt-in and its contents are the caller's own data.
"""

from __future__ import annotations

import asyncio
import json
import locale
import logging
import os
import re
import shutil
import subprocess
import threading
import time
from typing import Any, Callable, Iterable, Optional, Sequence, Union

from .errors import CoreAiSessionError, NotBoundError, ToolError, ToolNotFoundError
from .models import Catalog, SessionInfo, Task, ToolDetail, ToolResult, ToolSummary
from .tree import (
    DEFAULT_TIMEOUT,
    DEFAULT_WAIT_TIMEOUT,
    _SessionBase,
    parse_detail,
    parse_session_info,
)

LOGGER = logging.getLogger("core_ai_session")

CLI_ENV = "CORE_AI_CLI"
DEFAULT_CLI = "core-ai-cli"
# Opt-in capture of every exchange, for contract fixtures and bug reports (`record=` also works).
RECORD_ENV = "CORE_AI_CLI_RECORD"
# `skill` / `api-tool` / `agent` landed in 2.0.10 (mcp is older); below that the subcommands this
# session drives are simply missing, which surfaces as a usage error.
MIN_CLI_VERSION = "2.0.10"

# `core-ai-cli mcp call --timeout` / `agent run --timeout`: the server-side wait limit, capped at
# 300s by the CLI. A sandbox hub call may wait up to 600s, so a long timeout is clamped locally.
CLI_MAX_TIMEOUT = 300
# The Java client aborts a call at (--timeout + 30)s; discovery calls use its 60s default.
CLI_CALL_GRACE_SECONDS = 30
CLI_DISCOVERY_TIMEOUT_SECONDS = 60
# Keep whole payloads whole: the CLI truncates text output by default (64KiB / 20000 chars) while a
# sandbox call does not, and silently losing the tail of a report is worse than a large buffer.
MAX_OUTPUT_CHARS = 1_000_000
SEARCH_LIMIT = 200  # the hub's maximum page size; the per-server/app drill-down lifts the 3-item cap

EXIT_TOOL_ERROR = 1
EXIT_USAGE = 2
EXIT_UNAUTHENTICATED = 3
EXIT_FORBIDDEN = 4
EXIT_NOT_FOUND = 5
EXIT_TIMEOUT = 6
EXIT_INPUT_REQUIRED = 7

# The sandbox hub's whole status vocabulary is completed|pending|failed, and it reports a run that
# waits for input as *pending* (the caller polls until the human answers). Collapse the CLI's richer
# statuses onto that vocabulary so a script takes the same path in both places.
RUN_STATUSES = {
    "running": "pending",
    "input_required": "pending",
    "completed": "completed",
    "failed": "failed",
    "cancelled": "failed",
}

# The sandbox projects `llm_call` definitions and sub-agents as tools taking a `query`; the local
# catalog describes them the same way, from the agent's own `input_hint`-free hub shape.
LLM_CALL_SCHEMA = {
    "type": "object",
    "properties": {
        "query": {"type": "string", "description": "the text this LLM call should work on"},
        "image_url": {"type": "string", "description": "optional image URL attached to the call"},
    },
    "required": ["query"],
}
AGENT_SCHEMA = {
    "type": "object",
    "properties": {
        "query": {"type": "string", "description": "the task for this agent"},
    },
    "required": ["query"],
}

_WARNED_LOCAL_MODE = False


def script_name(path: str) -> str:
    """``server/tool`` -> ``server_tool``: the same rule the sandbox catalog applies to names."""
    return re.sub(r"[^A-Za-z0-9]", "_", path)


def _decode(raw: bytes) -> str:
    """The CLI writes UTF-8 on a UTF-8 console and the console codepage on Windows; accept both."""
    encodings = ["utf-8", locale.getpreferredencoding(False) or "utf-8"]
    for encoding in encodings:
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def _warn_local_mode(cli: str) -> None:
    global _WARNED_LOCAL_MODE
    if _WARNED_LOCAL_MODE:
        return
    _WARNED_LOCAL_MODE = True
    LOGGER.warning(
        "core_ai_session: no session hub here, using the local core-ai-cli backend (%s). "
        "Calls run as your own user against everything you can see, not as the target agent's "
        "session; the catalog is what your user can call, not what an agent mounts.",
        cli,
    )


def _last_record_index(directory: str) -> int:
    """The highest ``NNNN`` already used in a recording directory, so a second capture appends.

    Knowing the number requires reading the directory once per session, and only when recording is
    on; a directory that does not exist yet simply starts at zero.
    """
    highest = 0
    try:
        for name in os.listdir(directory):
            prefix, _, _rest = name.partition("-")
            if prefix.isdigit():
                highest = max(highest, int(prefix))
    except OSError:
        return 0
    return highest


def _resolve_cli(cli: Optional[Union[str, Sequence[str]]]) -> list[str]:
    """Resolve the CLI to an argv prefix: a name on PATH, a path, or an explicit command sequence."""
    if isinstance(cli, (list, tuple)):
        command = [str(part) for part in cli if str(part)]
        if not command:
            raise CoreAiSessionError("cli= must name the core-ai-cli executable")
        return command
    candidate = (cli or os.environ.get(CLI_ENV) or DEFAULT_CLI).strip() or DEFAULT_CLI
    resolved = shutil.which(candidate)
    if resolved:
        return [resolved]
    if os.path.isfile(candidate):
        return [candidate]
    raise CoreAiSessionError(
        f"cannot find the core-ai-cli executable ({candidate!r}); CORE_AI_HUB is not set either, so "
        "this process has no session to talk to. Inside a sandbox the runtime sets CORE_AI_HUB and "
        "nothing needs to be configured; to run a skill script on this machine, install core-ai-cli "
        "('core-ai-cli upgrade'), run 'core-ai-cli --login' once, then retry. "
        f"Set {CLI_ENV} to point at a specific binary."
    )


def _error_message(payload: Any, stderr: str) -> str:
    if isinstance(payload, dict):
        error = payload.get("error")
        if isinstance(error, dict) and error.get("message"):
            return str(error["message"])
        for key in ("error_message", "message"):
            if payload.get(key):
                return str(payload[key])
    return (stderr or "").strip()[:400]


def _summary(path: str, kind: str, *, group: str = "", ref_id: Optional[str] = None,
             description: Optional[str] = None) -> dict[str, Any]:
    return {
        "name": script_name(path),
        "kind": kind,
        "group": group,
        "path": path,
        "ref_id": ref_id or path,
        "description": description or "",
        "exposure": "direct",
    }


def _groups(tools: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    counts: dict[tuple[str, str], int] = {}
    for tool in tools:
        key = (tool["kind"], tool.get("group") or "")
        counts[key] = counts.get(key, 0) + 1
    return [
        {"kind": kind, "group": group, "path": group, "count": count}
        for (kind, group), count in counts.items()
    ]


def _detail(entry: ToolSummary, payload: dict[str, Any], schema: Any) -> ToolDetail:
    """Catalog naming wins over the hub's own: a script addresses the entry by its script name."""
    return parse_detail(
        {
            "name": entry.name,
            "kind": entry.kind,
            "group": entry.group,
            "path": entry.path,
            "ref_id": payload.get("ref_id") or entry.ref_id,
            "description": payload.get("description") or entry.description,
            "input_schema": schema,
            "timeout_seconds": payload.get("timeout_seconds") or DEFAULT_TIMEOUT,
        }
    )


def _agent_arguments(entry: ToolSummary, arguments: dict[str, Any], *, image_only: bool) -> tuple[str, list[str], Optional[str]]:
    """Split a script's call into the CLI's task/context/attach shape, refusing unknown arguments.

    ``image_only`` is the `llm_call` projection: the sandbox lets such a call carry a ``query`` and
    an optional ``image_url`` and nothing else, so a local call refuses the rest instead of quietly
    forwarding an argument the sandbox would not accept.
    """
    remaining = dict(arguments)
    task = remaining.pop("query", None)
    if task is None:
        task = remaining.pop("task", None)
    if task is None:
        task = remaining.pop("prompt", None)
    if not isinstance(task, str) or not task.strip():
        raise TypeError(f"{entry.name} needs a task: pass query='...' (or s.agent['{entry.path}'].run('...'))")
    supported = ["query", "image_url"]
    attachments: list[str] = []
    image = remaining.pop("image_url", None)
    if isinstance(image, str) and image:
        attachments.append(image)
    context_id: Optional[str] = None
    if not image_only:
        context_id = remaining.pop("context_id", None)
        supported += ["context_id", "attachments"]
        extra = remaining.pop("attachments", None) or remaining.pop("attach", None)
        if isinstance(extra, str) and extra:
            attachments.append(extra)
        elif isinstance(extra, (list, tuple)):
            attachments.extend(str(item) for item in extra if item)
    if remaining:
        raise TypeError(
            f"{entry.name}(...) got unsupported argument(s): {', '.join(sorted(remaining))} "
            f"(supported here: {', '.join(supported)})"
        )
    return task, attachments, context_id


class CliSession(_SessionBase):
    """Hub session over ``core-ai-cli``: same API as the sandbox `Session`, local identity.

    Every call is one short-lived subprocess, so prefer this for scripts you run by hand or in CI,
    not for tight loops. Long-running agent runs behave exactly as they do in a sandbox: the call
    returns a `Task` you can poll (``wait=False``) once the CLI's 300s wait budget runs out.
    """

    backend = "cli"

    def __init__(self, cli: Optional[Union[str, Sequence[str]]] = None, *, server: Optional[str] = None,
                 api_key: Optional[str] = None, timeout: int = DEFAULT_TIMEOUT,
                 script: Optional[str] = None, wait_timeout: float = DEFAULT_WAIT_TIMEOUT,
                 poll_interval: Optional[float] = None, record: Optional[str] = None) -> None:
        super().__init__()
        self._cli_argv = _resolve_cli(cli)
        self.cli = " ".join(self._cli_argv)
        self.timeout = max(1, min(int(timeout), CLI_MAX_TIMEOUT))
        self.wait_timeout = wait_timeout
        self._poll_interval = poll_interval
        # accepted so a skill's call site stays the same in both places; the sandbox-only
        # X-Core-AI-Script header has no local equivalent (the server attributes the API key's user)
        self.script = script
        self._server = server
        self._api_key = api_key
        self._version_probe = ""
        self._timeout_warned = False
        self._task_tools: dict[str, str] = {}
        self._waiting_warned: set[str] = set()
        self._unlistable: list[str] = []
        self._source_counts: dict[str, int] = {}
        self._record_dir = (record or os.environ.get(RECORD_ENV) or "").strip()
        self._record_lock = threading.Lock()
        self._record_index = 0
        _warn_local_mode(self.cli)

    # ---------- subprocess ----------

    @property
    def poll_interval(self) -> float:
        # a poll is a whole JVM start, so wait longer between them than the HTTP sessions do
        return self._poll_interval or 1.0

    def _version(self) -> str:
        """The installed CLI's version, asked for only after something already failed.

        A probe is a whole JVM start, so it is not worth paying on every session; by the time a
        command is rejected with a usage error, one extra start to name the installed version is
        exactly what the reader needs.
        """
        if self._version_probe == "":
            self._version_probe = "unknown"
            try:
                completed = subprocess.run(
                    self._spawn([*self._cli_argv, "--version"]),
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=CLI_DISCOVERY_TIMEOUT_SECONDS, check=False,
                )
            except (OSError, subprocess.TimeoutExpired):
                return self._version_probe
            printed = (_decode(completed.stdout).strip() or _decode(completed.stderr).strip()).splitlines()
            if completed.returncode == 0 and printed:
                self._version_probe = printed[0].strip()[:60]
        return self._version_probe

    def _command(self, args: Sequence[str]) -> list[str]:
        command = [*self._cli_argv, *args, "--json"]
        if self._server:
            command += ["--server", self._server]
        if self._api_key:
            command += ["--api-key", self._api_key]
        return command

    @staticmethod
    def _spawn(command: list[str]) -> list[str]:
        # CreateProcess cannot launch a .cmd/.bat shim directly; go through the command interpreter.
        if os.name == "nt" and command[0].lower().endswith((".cmd", ".bat")):
            return [os.environ.get("COMSPEC") or "cmd.exe", "/c", *command]
        return command

    def _run_raw(self, args: Sequence[str], stdin: Optional[str] = None, *,
                 kill_after: float) -> tuple[int, dict[str, Any], str, str]:
        """One CLI invocation: JSON on stdout, exit code as the contract, stdin for anything long."""
        command = self._spawn(self._command(args))
        body = (stdin or "").encode("utf-8")
        try:
            completed = subprocess.run(
                command,
                input=body,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=kill_after,
                check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise ToolError(
                f"core-ai-cli {' '.join(args[:2])} did not finish within {kill_after:.0f}s",
                status_code=504,
            ) from error
        except OSError as error:
            raise CoreAiSessionError(
                f"cannot run {self.cli}: {error}. Install core-ai-cli and put it on PATH, "
                "or point CORE_AI_CLI at it."
            ) from error
        stdout = _decode(completed.stdout)
        stderr = _decode(completed.stderr)
        try:
            payload = json.loads(stdout) if stdout.strip() else {}
        except ValueError:
            payload = {}
        if not isinstance(payload, dict):
            payload = {"result": payload}
        self._record(args, stdin, completed.returncode, stdout, stderr)
        return completed.returncode, payload, stdout, stderr

    def _record(self, args: Sequence[str], stdin: Optional[str], code: int, stdout: str, stderr: str) -> None:
        """Write one exchange as ``DIR/NNNN-<leaf>.json``; off unless a directory was configured.

        Recording must never break the call it records, so any failure here is a warning. The index
        is taken under a lock because `AsyncCliSession` runs calls in threads; a run's *output* may
        contain anything the caller is allowed to see, which is why this is opt-in.
        """
        directory = self._record_dir
        if not directory:
            return
        try:
            with self._record_lock:
                if self._record_index == 0:  # a second capture into the same directory keeps counting
                    self._record_index = _last_record_index(directory)
                self._record_index += 1
                index = self._record_index
            leaf = "-".join(script_name(part) for part in args[:2])
            name = f"{index:04d}-{leaf or 'cli'}.json"
            os.makedirs(directory, exist_ok=True)
            with open(os.path.join(directory, name), "w", encoding="utf-8") as handle:
                json.dump(
                    {"cli": self.cli, "argv": list(args), "stdin": stdin or "", "exit": code,
                     "stdout": stdout, "stderr": stderr},
                    handle, ensure_ascii=False, indent=2,
                )
                handle.write("\n")
        except OSError as error:
            LOGGER.warning("core_ai_session: cannot record this core-ai-cli exchange in %s: %s",
                           directory, error)

    def _raise_exit(self, code: int, payload: Any, stderr: str, *, tool: Optional[str] = None,
                    kind: Optional[str] = None) -> None:
        """Map the CLI's stable exit codes onto the SDK's exceptions (mirrors the hub's mapping)."""
        message = _error_message(payload, stderr) or f"core-ai-cli exited {code}"
        if code == EXIT_TOOL_ERROR:
            raise ToolError(message, tool=tool, kind=kind)
        if code == EXIT_USAGE:
            raise CoreAiSessionError(
                f"core-ai-cli rejected this call: {message} (installed: core-ai-cli {self._version()}; "
                f"these hub subcommands need {MIN_CLI_VERSION}+ — 'core-ai-cli upgrade' updates it)"
            )
        if code == EXIT_UNAUTHENTICATED:
            raise NotBoundError(
                f"core-ai-cli has no usable credentials: {message}; run 'core-ai-cli --login' once "
                "(or pass --api-key / CORE_AI_API_KEY)"
            )
        if code == EXIT_FORBIDDEN:
            raise ToolError(message, tool=tool, kind=kind, status_code=403)
        if code == EXIT_NOT_FOUND:
            raise ToolNotFoundError(message)
        if code in (EXIT_TIMEOUT, EXIT_INPUT_REQUIRED):
            raise ToolError(message, tool=tool, kind=kind, status_code=504)
        raise CoreAiSessionError(f"core-ai-cli exited {code}: {message}")

    def _require(self, args: Sequence[str], stdin: Optional[str] = None, *,
                 kill_after: Optional[float] = None) -> dict[str, Any]:
        timeout = kill_after if kill_after is not None else CLI_DISCOVERY_TIMEOUT_SECONDS + CLI_CALL_GRACE_SECONDS
        code, payload, _stdout, stderr = self._run_raw(args, stdin, kill_after=timeout)
        if code != 0:
            self._raise_exit(code, payload, stderr)
        return payload

    def _call(self, args: Sequence[str], stdin: str, entry: ToolSummary, seconds: int) -> dict[str, Any]:
        """A tool call: a business failure exits 1 *with* the result payload, and is not a transport error."""
        code, payload, _stdout, stderr = self._run_raw(args, stdin, kill_after=seconds + CLI_CALL_GRACE_SECONDS)
        if code != 0 and not (code == EXIT_TOOL_ERROR and payload):
            self._raise_exit(code, payload, stderr, tool=entry.name, kind=entry.kind)
        return payload

    # ---------- catalog ----------

    @property
    def catalog_gaps(self) -> list[str]:
        """Sources that are missing tools from this local catalog, from the last enumeration.

        The local catalog is best effort:

        * a source (mcp server, api app) that fails to list is retried once, then skipped;
        * a source that lists *fewer* tools than it advertises, hits the page limit, or shrinks
          between two enumerations of this session, is kept but flagged.

        Either way the source is named here — ``['mcp server tikhub-tiktok (listed 0 of 174)']`` — and
        one aggregated warning is logged. A tool that exists in a sandbox but is missing here is
        usually explained by this list; the hub catalogs are authoritative, this one is not.
        """
        return list(self._unlistable)

    def _list_source(self, args: Sequence[str], label: str) -> Optional[dict[str, Any]]:
        """``args`` retried once: one hiccup must not silently shrink the catalog."""
        for attempt in (1, 2):
            try:
                return self._require(args)
            except NotBoundError:
                raise  # no credentials at all: the caller is asking the wrong question for every source
            except CoreAiSessionError as error:
                if attempt == 1:
                    LOGGER.debug("core_ai_session: %s failed once (%s); retrying", label, error)
                    continue
                LOGGER.debug("core_ai_session: %s is not listable: %s", label, error)
                self._unlistable.append(label)
        return None

    def _check_count(self, label: str, found: int, advertised: Optional[int]) -> None:
        """Flag a source whose listing looks smaller than it should.

        `mcp servers` / `api-tool apps` state a tool count per source; when a live index flaps, a
        source can answer with no tools at all while still advertising dozens, and a silent shrink
        would make `session.tool(...)` report a confusing `ToolNotFoundError`. A source that shrinks
        between two enumerations of the *same* session is flagged too: that is what a flapping index
        looked like in practice (`tikhub-tiktok` advertised 174 tools, listed 0, then the reverse).
        """
        previous = self._source_counts.get(label)
        self._source_counts[label] = found
        if found >= SEARCH_LIMIT:
            self._unlistable.append(f"{label} (hit the {SEARCH_LIMIT}-tool page limit)")
        elif isinstance(advertised, int) and found < advertised:
            self._unlistable.append(f"{label} (listed {found} of {advertised})")
        elif previous is not None and found < previous:
            self._unlistable.append(f"{label} (listed {found}, the previous enumeration listed {previous})")

    def _mcp_tools(self) -> list[dict[str, Any]]:
        tools: list[dict[str, Any]] = []
        servers = self._require(["mcp", "servers"]).get("servers") or []
        for server in servers:
            name = server.get("name")
            if not name:
                continue
            response = self._list_source(["mcp", "search", "--on-server", name, "--limit", str(SEARCH_LIMIT)],
                                         f"mcp server {name}")
            if response is None:
                continue
            found = response.get("tools") or []
            self._check_count(f"mcp server {name}", len(found), server.get("tool_count"))
            for tool in found:
                if not tool.get("name"):
                    continue
                tools.append(_summary(f"{name}/{tool['name']}", "mcp", group=name,
                                      ref_id=tool.get("ref_id"), description=tool.get("description")))
        return tools

    def _api_tools(self) -> list[dict[str, Any]]:
        tools: list[dict[str, Any]] = []
        apps = self._require(["api-tool", "apps"]).get("apps") or []
        for app in apps:
            name = app.get("name")
            if not name:
                continue
            response = self._list_source(["api-tool", "search", "--on-app", name, "--limit", str(SEARCH_LIMIT)],
                                         f"api app {name}")
            if response is None:
                continue
            found = response.get("operations") or []
            self._check_count(f"api app {name}", len(found), app.get("operation_count"))
            for operation in found:
                path = operation.get("qualified_name") or "/".join(
                    part for part in (operation.get("app"), operation.get("service"), operation.get("name")) if part
                )
                if not path:
                    continue
                tools.append(_summary(path, "api", group=operation.get("app") or name,
                                      ref_id=operation.get("ref_id"), description=operation.get("description")))
        return tools

    def _agent_tools(self, agent_type: str) -> list[dict[str, Any]]:
        tools: list[dict[str, Any]] = []
        seen: set[str] = set()
        response = self._list_source(["agent", "search", "--type", agent_type, "--limit", str(SEARCH_LIMIT)],
                                     f"{agent_type} list")
        if response is None:
            return tools
        for agent in response.get("agents") or []:
            name = agent.get("name")
            if not name:
                continue
            if name in seen:
                # two visible agents share a name: `agent run <name>` needs an id, so keep the first
                LOGGER.warning("core_ai_session: more than one visible %s is named %s; "
                               "the local catalog keeps the first one", agent_type, name)
                continue
            seen.add(name)
            tools.append(_summary(name, agent_type, ref_id=name, description=agent.get("description")))
        return tools

    def _build_catalog(self) -> dict[str, Any]:
        self._unlistable = []
        tools: list[dict[str, Any]] = []
        tools.extend(self._mcp_tools())
        tools.extend(self._api_tools())
        tools.extend(self._agent_tools("agent"))
        tools.extend(self._agent_tools("llm_call"))
        if self._unlistable:
            LOGGER.warning(
                "core_ai_session: this local catalog is incomplete — %s. See session.catalog_gaps; the "
                "sandbox catalogs are authoritative, this one is best effort.",
                "; ".join(self._unlistable),
            )
        return {
            "session_id": "",
            "agent_name": "",
            "sandbox_id": "",
            "sandbox_state": "local",
            "contract_version": None,
            "groups": _groups(tools),
            "tools": tools,
        }

    def _load(self, payload: dict[str, Any]) -> Catalog:
        catalog = self._load_catalog(payload)
        self.session_id = catalog.session_id
        self.agent_name = catalog.agent_name
        return catalog

    def catalog(self) -> Catalog:
        if self._catalog is not None:
            return self._catalog
        return self.refresh()

    def refresh(self) -> Catalog:
        return self._load(self._build_catalog())

    def _require_catalog(self) -> Catalog:
        if self._catalog is None:
            return self.catalog()
        return self._catalog

    def tools(self, query: Optional[str] = None, kind: Optional[str] = None) -> list[ToolSummary]:
        entries = list(self._require_catalog().tools)
        if kind:
            entries = [entry for entry in entries if entry.kind == kind]
        if query:
            words = query.lower().split()
            entries = [
                entry for entry in entries
                if all(word in f"{entry.name} {entry.path} {entry.description}".lower() for word in words)
            ]
        return entries

    def describe(self, name: str) -> ToolDetail:
        return self._describe(name, self._fetch_detail)

    def _describe(self, name: str, fetch: Callable[[ToolSummary], Optional[ToolDetail]]) -> ToolDetail:
        """Look the name up, then fetch its detail with ``fetch``.

        ``fetch`` is the caller's blocking fetcher: ``_fetch_detail`` for a synchronous session,
        the same function bound inside the caller's worker thread for an asynchronous one.
        """
        entry = self.find_entry(name) or self._require_catalog().find(name)
        if entry is None:
            raise ToolNotFoundError(
                f"'{name}' is not in this local catalog; s.tools() lists everything you can call"
            )
        detail = fetch(entry)
        if detail is None:
            raise ToolNotFoundError(f"'{name}' cannot be described from this machine")
        self._details[entry.name] = detail
        return detail

    def _fetch_detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        """Local schemas come from ``describe`` for mcp/api and are synthesized for agents."""
        if entry.kind == "llm_call":
            return _detail(entry, {}, LLM_CALL_SCHEMA)
        if entry.kind == "agent":
            return _detail(entry, {}, AGENT_SCHEMA)
        args = ["mcp", "describe", entry.path] if entry.kind == "mcp" else ["api-tool", "describe", entry.path]
        try:
            payload = self._require(args)
        except CoreAiSessionError as error:
            LOGGER.debug("core_ai_session: describe %s failed: %s", entry.path, error)
            return None
        return _detail(entry, payload, payload.get("input_schema"))

    def me(self) -> SessionInfo:
        """Local stand-in for the session info: no sandbox, no session token, no session caller."""
        info = parse_session_info(
            {
                "session_id": "",
                "agent_name": "",
                "sandbox_id": "",
                "sandbox_state": "local",
                "expires_at": None,
                "tool_count": len(self._require_catalog().tools),
                "contract_version": None,
                "caller": {"source": "local-cli", "user": _os_user(), "cli": self.cli},
            }
        )
        self.session_id = info.session_id
        self.agent_name = info.agent_name
        return info

    # ---------- invocation ----------

    def _effective_timeout(self, timeout: Optional[int]) -> int:
        requested = max(1, int(timeout or self.timeout))
        if requested > CLI_MAX_TIMEOUT:
            if not self._timeout_warned:
                self._timeout_warned = True
                LOGGER.warning(
                    "core_ai_session: timeout %ss reduced to %ss — the local hub waits at most %ss. "
                    "The work keeps running: pass wait=False and poll the returned task.",
                    requested, CLI_MAX_TIMEOUT, CLI_MAX_TIMEOUT,
                )
            return CLI_MAX_TIMEOUT
        return requested

    def _invoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                wait: bool = True, wait_timeout: float = DEFAULT_WAIT_TIMEOUT) -> Union[ToolResult, Task]:
        seconds = self._effective_timeout(timeout)
        if entry.kind in ("mcp", "api"):
            leaf = "mcp" if entry.kind == "mcp" else "api-tool"
            payload = self._call(
                [leaf, "call", entry.path, "--args-file", "-", "--timeout", str(seconds),
                 "--max-output", str(MAX_OUTPUT_CHARS)],
                json.dumps(arguments, ensure_ascii=False),
                entry,
                seconds,
            )
        elif entry.kind in ("llm_call", "agent"):
            payload = self._run_agent(entry, arguments, seconds, image_only=entry.kind == "llm_call")
        else:
            raise ToolNotFoundError(
                f"'{entry.kind}' tools exist inside a sandbox only; this local session can call "
                "mcp, api, llm_call and agent tools"
            )
        return self._finish(self._to_result(payload), entry, wait, wait_timeout)

    def _run_agent(self, entry: ToolSummary, arguments: dict[str, Any], seconds: int, *,
                   image_only: bool) -> dict[str, Any]:
        task, attachments, context_id = _agent_arguments(entry, arguments, image_only=image_only)
        args = ["agent", "run", entry.path, "--task-file", "-", "--timeout", str(seconds),
                "--max-output", str(MAX_OUTPUT_CHARS)]
        if context_id:
            args += ["--context-id", str(context_id)]
        for url in attachments:
            args += ["--attach", url]
        # the task itself travels on stdin: no argv quoting, no command-line length limit, UTF-8 kept
        code, payload, stdout, stderr = self._run_raw(args, task, kill_after=seconds + CLI_CALL_GRACE_SECONDS)
        return self._agent_payload(entry, code, payload, stdout, stderr)

    def _agent_payload(self, entry: ToolSummary, code: int, payload: dict[str, Any], stdout: str,
                       stderr: str) -> dict[str, Any]:
        status = payload.get("status")
        if not status:
            self._raise_exit(code, payload, stderr, tool=entry.name, kind=entry.kind)
        task_id = payload.get("task_id")
        output = payload.get("output") or ""
        if status == "input_required":
            # inside a sandbox this is a *pending* task too: the caller is asked and the script polls.
            # Locally nobody is asked, so say once what unblocks it — then behave identically.
            self._warn_waiting_input(entry, task_id, payload.get("input_request") or {})
        usage = payload.get("token_usage") or {}
        error_message = payload.get("error_message")
        if status == "cancelled" and not error_message:
            error_message = "run cancelled"
        if task_id and RUN_STATUSES.get(status) == "pending":
            self._task_tools[task_id] = entry.name
        return {
            "call_id": task_id or "",
            "status": RUN_STATUSES.get(status, status),
            "text": output,
            "content": [{"type": "text", "text": output}] if output else [],
            "duration_ms": payload.get("duration_ms") or 0,
            "task_id": task_id,
            "is_error": status in ("failed", "cancelled"),
            "error_code": payload.get("error_code"),
            "error_message": error_message,
            "llm_usage": {"input_tokens": usage.get("input"), "output_tokens": usage.get("output")} if usage else None,
        }

    def _warn_waiting_input(self, entry: ToolSummary, task_id: Optional[str], request: dict[str, Any]) -> None:
        if not task_id or task_id in self._waiting_warned:
            return
        self._waiting_warned.add(task_id)
        waiting = request.get("message") or request.get("tool") or "an approval"
        LOGGER.warning(
            "core_ai_session: %s is waiting for %s on task %s. There is no chat caller here: answer it "
            "with 'core-ai-cli agent reply %s --approve' (or --deny / --message \"...\") and the poll "
            "will pick the run up again.", entry.name, waiting, task_id, task_id,
        )

    def _fetch_task(self, task_id: str) -> dict[str, Any]:
        """Poll one run: ``agent status`` renders the same result shape as ``agent run`` did."""
        entry = ToolSummary(name=self._task_tools.get(task_id, "agent"), kind="agent")
        code, payload, stdout, stderr = self._run_raw(
            ["agent", "status", task_id],  # status has no --max-output: it never truncates
            kill_after=CLI_DISCOVERY_TIMEOUT_SECONDS + CLI_CALL_GRACE_SECONDS,
        )
        return self._agent_payload(entry, code, payload, stdout, stderr)

    def call(self, name: str, arguments: Optional[dict[str, Any]] = None, **kwargs: Any) -> Union[ToolResult, Task]:
        entry = self.find_entry(name)
        if entry is None:
            raise ToolNotFoundError(f"'{name}' is not in this local catalog; s.tools() lists what you can call")
        merged = dict(arguments or {})
        merged.update(kwargs)
        return self._invoke_node(None, entry, merged)

    def close(self) -> None:
        return None  # one subprocess per call: nothing is held open

    def __enter__(self) -> "CliSession":
        return self

    def __exit__(self, *exc_info: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        state = "loaded" if self._catalog is not None else "lazy"
        return f"<core_ai_session.CliSession {self.cli} catalog={state}>"


class AsyncCliSession(CliSession):
    """``await``-friendly local session: every CLI call runs in a worker thread.

    Same contract as `AsyncSession` (namespace access is synchronous, calls are awaited, the
    catalog is loaded by `async_session()`/`refresh()`), because a `CliSession` call is exactly
    the kind of blocking work an event loop must not do itself.
    """

    backend = "cli"

    def is_async(self) -> bool:
        return True

    def _require_catalog(self) -> Catalog:
        if self._catalog is None:
            raise CoreAiSessionError(
                "an AsyncCliSession loads its catalog asynchronously: use `s = await async_session()` "
                "or `await s.refresh()` before touching namespaces"
            )
        return self._catalog

    def _fetch_detail(self, entry: ToolSummary) -> Optional[ToolDetail]:
        return None  # a subprocess round trip cannot be awaited from a synchronous call path

    async def me(self) -> SessionInfo:  # type: ignore[override]
        return await asyncio.to_thread(CliSession.me, self)

    async def catalog(self) -> Catalog:  # type: ignore[override]
        if self._catalog is not None:
            return self._catalog
        return await self.refresh()

    async def refresh(self) -> Catalog:  # type: ignore[override]
        return await asyncio.to_thread(CliSession.refresh, self)

    async def tools(self, query: Optional[str] = None, kind: Optional[str] = None) -> list[ToolSummary]:  # type: ignore[override]
        return await asyncio.to_thread(CliSession.tools, self, query, kind)

    async def describe(self, name: str) -> ToolDetail:  # type: ignore[override]
        return await asyncio.to_thread(self._describe, name, self._fetch_detail_blocking)

    def _fetch_detail_blocking(self, entry: ToolSummary) -> Optional[ToolDetail]:
        """The synchronous fetcher, for use inside a worker thread.

        ``_fetch_detail`` has to answer ``None`` here because the lazy namespace tree resolves
        details from a synchronous path that must not run a subprocess on the event loop;
        ``describe`` is awaited and can afford the round trip, so it asks for this version.
        """
        return CliSession._fetch_detail(self, entry)

    def _invoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                wait: bool = True, wait_timeout: float = DEFAULT_WAIT_TIMEOUT) -> Any:
        return self._ainvoke(entry, arguments, timeout=timeout, wait=wait, wait_timeout=wait_timeout)

    async def _ainvoke(self, entry: ToolSummary, arguments: dict[str, Any], *, timeout: Optional[int] = None,
                       wait: bool = True, wait_timeout: float = DEFAULT_WAIT_TIMEOUT) -> Union[ToolResult, Task]:
        result = await asyncio.to_thread(
            CliSession._invoke, self, entry, arguments,
            timeout=timeout, wait=False, wait_timeout=wait_timeout,
        )
        if isinstance(result, ToolResult):
            return result
        task: Task = result
        if not wait:
            return task
        await self._await_task(task, wait_timeout)
        if task.result is None:
            raise self.pending_error(entry.name, entry.kind, task.task_id, wait_timeout)
        if task.result.is_error:
            raise self.tool_error(task.result, entry.name)
        return task.result

    async def _await_task(self, task: Task, wait_timeout: float) -> None:
        deadline = time.monotonic() + wait_timeout
        while task.status == "pending":
            if time.monotonic() >= deadline:
                return
            await asyncio.sleep(self.poll_interval)
            payload = await asyncio.to_thread(self._fetch_task, task.task_id)
            task.status = payload.get("status", task.status)
            if task.status != "pending":
                task.result = self._to_result(payload)

    async def call(self, name: str, arguments: Optional[dict[str, Any]] = None, **kwargs: Any) -> Union[ToolResult, Task]:  # type: ignore[override]
        entry = self.find_entry(name)
        if entry is None:
            raise ToolNotFoundError(f"'{name}' is not in this local catalog; s.tools() lists what you can call")
        merged = dict(arguments or {})
        merged.update(kwargs)
        return await self._invoke_node(None, entry, merged)

    async def aclose(self) -> None:
        return None

    def close(self) -> None:
        return None

    async def __aenter__(self) -> "AsyncCliSession":
        await self.refresh()
        return self

    async def __aexit__(self, *exc_info: Any) -> None:
        await self.aclose()


def _os_user() -> str:
    for key in ("USERNAME", "USER", "LOGNAME"):
        value = os.environ.get(key)
        if value:
            return value
    return ""
