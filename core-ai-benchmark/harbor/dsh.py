"""Harbor adapter for DeepSeek Harness (dsh).

Runs the official DeepSeek harness inside the task container, in its
``sdk-minimal`` profile, and talks to the core-ai LiteLLM gateway.

The gateway expects OpenAI ``content`` parts as objects, while dsh sends plain
strings, so a tiny local proxy normalizes the request before forwarding it.
"""

from __future__ import annotations

import json
import re
import shlex
from pathlib import Path

from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext
from harbor.models.trial.paths import EnvironmentPaths

REMOTE_DIR = "/opt/dsh"
REMOTE_PROXY = f"{REMOTE_DIR}/proxy.py"
REMOTE_RUNNER = f"{REMOTE_DIR}/runner.py"
REMOTE_PATCH = f"{REMOTE_DIR}/patch.yml"
REMOTE_KEY = f"{REMOTE_DIR}/key"
REMOTE_HOME = "/tmp/dsh/home"
REMOTE_INSTRUCTION = "/tmp/dsh/instruction.md"
OUTPUT_FILENAME = "dsh.txt"
SDK_VERSION = "0.2.1rc2"
DEFAULT_MODEL = "deepseek-flash"
DEFAULT_API_BASE = "https://core-ai-server.internal.connexup-uat.net/api/cli/v1"

_LOCAL_DIR = Path(__file__).resolve().parent


class DshAgent(BaseInstalledAgent):
    """Install DeepSeek Harness into the container and run one prompt."""

    def __init__(
        self,
        logs_dir: Path,
        api_key: str | None = None,
        api_base: str | None = None,
        time_limit_seconds: int | None = None,
        time_budget_seconds: int | None = None,
        auto_time_budget: bool = True,
        reasoning_effort: str | None = "max",
        max_tokens: int = 65536,
        request_timeout_seconds: float = 1800.0,
        sdk_version: str = SDK_VERSION,
        *args,
        **kwargs,
    ):
        self._api_key = api_key
        self._api_base = (api_base or DEFAULT_API_BASE).rstrip("/")
        self._time_limit_seconds = _int_or_none(time_limit_seconds)
        self._time_budget_seconds = _int_or_none(time_budget_seconds)
        self._auto_time_budget = bool(auto_time_budget)
        self._reasoning_effort = reasoning_effort
        self._max_tokens = int(max_tokens)
        self._request_timeout_seconds = float(request_timeout_seconds)
        self._sdk_version = sdk_version
        super().__init__(logs_dir, *args, **kwargs)

    @staticmethod
    def name() -> str:
        return "dsh"

    def get_version_command(self) -> str:
        return "python3 -c 'import deepseek_harness; print(1)' 2>&1 || true"

    def parse_version(self, stdout: str) -> str:
        return self._sdk_version

    @property
    def dsh_model(self) -> str:
        name = self.model_name or DEFAULT_MODEL
        return name.rsplit("/", 1)[-1]

    # ------------------------------------------------------------------
    # install
    # ------------------------------------------------------------------

    async def install(self, environment: BaseEnvironment) -> None:
        await self.exec_as_root(
            environment,
            command=(
                "set -e; "
                "if ! command -v python3 >/dev/null 2>&1; then "
                "  (apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-pip) "
                "  || apk add --no-cache python3 py3-pip "
                "  || dnf install -y python3 python3-pip; "
                "fi; "
                "if ! python3 -m pip --version >/dev/null 2>&1; then "
                "  (apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y python3-pip) "
                "  || apk add --no-cache py3-pip "
                "  || dnf install -y python3-pip; "
                "fi; "
                f"python3 -m pip install --quiet --disable-pip-version-check "
                f"--break-system-packages 'deepseek-harness-sdk=={self._sdk_version}' requests "
                f"|| python3 -m pip install --quiet --disable-pip-version-check "
                f"'deepseek-harness-sdk=={self._sdk_version}' requests; "
                "python3 -c 'import deepseek_harness, requests; print(\"dsh ready\")'"
            ),
            timeout_sec=900,
        )
        await self._upload_assets(environment)

    async def _upload_assets(self, environment: BaseEnvironment) -> None:
        await self.exec_as_root(environment, command=f"mkdir -p {REMOTE_DIR} /tmp/dsh")
        await environment.upload_file(str(_LOCAL_DIR / "dsh_proxy.py"), REMOTE_PROXY)
        await environment.upload_file(str(_LOCAL_DIR / "dsh_runner.py"), REMOTE_RUNNER)
        await environment.upload_file(str(_LOCAL_DIR / "dsh_patch.yml"), REMOTE_PATCH)

        proxy = Path(_LOCAL_DIR / "dsh_proxy.py").read_text(encoding="utf-8")
        proxy = proxy.replace(
            'UPSTREAM = "https://core-ai-server.internal.connexup-uat.net/api/cli/v1"',
            f'UPSTREAM = "{self._api_base}"',
        )
        await self._write_remote(environment, REMOTE_PROXY, proxy)

        patch = Path(_LOCAL_DIR / "dsh_patch.yml").read_text(encoding="utf-8")
        patch = patch.replace("http://127.0.0.1:18080/api/cli/v1", "http://127.0.0.1:18080/api/cli/v1")
        patch = patch.replace("id: deepseek-flash", f"id: {self.dsh_model}")
        await self._write_remote(environment, REMOTE_PATCH, patch)

        key = self._api_key or ""
        await self._write_remote(environment, REMOTE_KEY, key)
        await self.exec_as_root(
            environment,
            command=f"chmod 600 {REMOTE_KEY} && chmod 755 {REMOTE_PROXY} {REMOTE_RUNNER}",
        )

    async def _write_remote(
        self, environment: BaseEnvironment, path: str, content: str
    ) -> None:
        await self.exec_as_root(
            environment,
            command=f"printf %s {shlex.quote(content)} > {path}",
        )

    # ------------------------------------------------------------------
    # run
    # ------------------------------------------------------------------

    def _task_agent_timeout_sec(self) -> float | None:
        path = Path(self.logs_dir).parent / "config.json"
        try:
            cfg = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        task_path = (cfg.get("task") or {}).get("path")
        if not task_path:
            return None
        try:
            import tomllib

            with (Path(task_path) / "task.toml").open("rb") as handle:
                task = tomllib.load(handle)
        except (OSError, ImportError):
            return None
        base = (task.get("agent") or {}).get("timeout_sec")
        agent_cfg = cfg.get("agent") or {}
        base = agent_cfg.get("override_timeout_sec") or base
        if base is None:
            return None
        return float(base)

    def _resolve_time_budget(self) -> int | None:
        timeout = self._task_agent_timeout_sec() if self._auto_time_budget else None
        if self._time_budget_seconds is not None:
            explicit = int(self._time_budget_seconds)
            if explicit <= 0:
                return None
            if timeout is not None:
                explicit = min(explicit, max(1, int(timeout - 15)))
            return explicit
        if not self._auto_time_budget or timeout is None:
            return None
        return max(1, int(timeout - 15))

    @with_prompt_template
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        workspace = environment.task_env_config.workdir
        if not workspace:
            pwd = await environment.exec(command="pwd")
            workspace = (pwd.stdout or "/app").strip() or "/app"
        if not workspace.startswith("/"):
            workspace = "/app"

        await self._write_remote(environment, REMOTE_INSTRUCTION, instruction)

        budget = self._resolve_time_budget()
        limit = budget or self._time_limit_seconds or 1800
        output_path = EnvironmentPaths.agent_dir / OUTPUT_FILENAME

        env = {
            "DSH_MODEL": self.dsh_model,
            "DSH_EFFORT": self._reasoning_effort or "",
            "DSH_MAX_TOKENS": str(self._max_tokens),
            "DSH_CWD": workspace,
            "DSH_HOME_DIR": REMOTE_HOME,
            "DSH_REQUEST_TIMEOUT": str(self._request_timeout_seconds),
            "PYTHONUNBUFFERED": "1",
        }
        command = (
            f"cd {shlex.quote(workspace)} && "
            f"nohup python3 {REMOTE_PROXY} > /tmp/dsh/proxy.log 2>&1 & "
            "for i in $(seq 1 30); do "
            "  python3 -c 'import urllib.request,sys; sys.exit(0 if urllib.request.urlopen(\"http://127.0.0.1:18080/models\", timeout=1).status==200 else 1)' 2>/dev/null && break; "
            "  sleep 1; "
            "done; "
            f"timeout {int(limit)} python3 {REMOTE_RUNNER} 2>&1 | tee {output_path}; "
            "rc=${PIPESTATUS[0]}; pkill -f 'python3 /opt/dsh/proxy.py' || true; exit $rc"
        )
        await self.exec_as_agent(environment, command=command, env=env)

    def populate_context_post_run(self, context: AgentContext) -> None:
        log_path = self.logs_dir / OUTPUT_FILENAME
        try:
            text = log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return
        usage = re.search(r"DSH_USAGE (\d+) (\d+) (\d+)", text)
        if usage:
            context.n_input_tokens = int(usage.group(1))
            context.n_output_tokens = int(usage.group(2))
            context.n_cache_tokens = int(usage.group(3))


def _int_or_none(value) -> int | None:
    if value is None or value == "":
        return None
    return int(value)
