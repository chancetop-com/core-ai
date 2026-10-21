"""Harbor installed-agent adapter for core-ai-cli.

Targets Harbor 0.24+ and Terminal-Bench 2.1. The adapter installs the
core-ai-cli native binary into the task container, writes the CLI config, and
runs one non-interactive prompt per trial.
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

_PROVIDER_ENV_KEYS: dict[str, list[str]] = {
    "openrouter": ["OPENROUTER_API_KEY"],
    "openai": ["OPENAI_API_KEY"],
    "deepseek": ["DEEPSEEK_API_KEY"],
    "azure": ["AZURE_OPENAI_API_KEY"],
    "litellm": ["LITELLM_API_KEY"],
}

_PROVIDER_BASE_ENV_KEYS: dict[str, list[str]] = {
    "litellm": ["LITELLM_API_BASE", "LITELLM_BASE_URL"],
    "openai": ["OPENAI_API_BASE", "OPENAI_BASE_URL"],
    "azure": ["AZURE_OPENAI_ENDPOINT", "AZURE_OPENAI_API_BASE"],
}

_DEFAULT_MODEL = "deepseek-flash"
_REMOTE_BIN = "/usr/local/bin/core-ai-cli"
_OUTPUT_FILENAME = "core-ai-cli.txt"


def _int_or_none(value):
    if value is None or value == "":
        return None
    return int(value)


def _float_or_none(value):
    if value is None or value == "":
        return None
    return float(value)


def _bool_or_default(value, default):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _active_auth_entry() -> dict | None:
    """Return the active entry from ~/.core-ai/auth.json, if present."""
    path = Path.home() / ".core-ai" / "auth.json"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    if isinstance(payload, list):
        for entry in payload:
            if isinstance(entry, dict) and entry.get("active") is True:
                return entry
        return None
    if isinstance(payload, dict) and payload.get("api_key"):
        return payload
    return None


def _host_default_model() -> str | None:
    """Read the model configured by the local core-ai-cli login."""
    path = Path.home() / ".core-ai" / "agent.properties"
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    litellm_model = None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key == "llm.model" and value:
            return value
        if key == "litellm.model" and value:
            litellm_model = value
    return litellm_model


class CoreAiCli(BaseInstalledAgent):
    """Install core-ai-cli inside the task container and run one prompt."""

    def __init__(
        self,
        logs_dir: Path,
        binary_path: str | None = None,
        api_key: str | None = None,
        api_base: str | None = None,
        max_turns: int | None = None,
        time_limit_seconds: int | None = None,
        time_budget_seconds: int | None = None,
        budget_margin_seconds: int = 15,
        auto_time_budget: bool = True,
        subagent_concurrency: int | None = None,
        token_budget: int | None = None,
        cost_budget_usd: float | None = None,
        budget_reserve_tokens: int | None = None,
        budget_reserve_cost_usd: float | None = None,
        budget_reserve_percent: int | None = None,
        coding: bool = True,
        todo_v2: bool = True,
        memory: bool = False,
        reasoning_effort: str | None = "max",
        *args,
        **kwargs,
    ):
        self._binary_path = binary_path
        self._api_key = api_key
        self._api_base = api_base
        self._max_turns = _int_or_none(max_turns)
        self._time_limit_seconds = _int_or_none(time_limit_seconds)
        self._time_budget_seconds = _int_or_none(time_budget_seconds)
        self._budget_margin_seconds = int(budget_margin_seconds)
        self._auto_time_budget = _bool_or_default(auto_time_budget, True)
        self._subagent_concurrency = _int_or_none(subagent_concurrency)
        self._token_budget = _int_or_none(token_budget)
        self._cost_budget_usd = _float_or_none(cost_budget_usd)
        self._budget_reserve_tokens = _int_or_none(budget_reserve_tokens)
        self._budget_reserve_cost_usd = _float_or_none(budget_reserve_cost_usd)
        self._budget_reserve_percent = _int_or_none(budget_reserve_percent)
        self._supports_time_budget = False
        self._coding = coding
        self._todo_v2 = todo_v2
        self._memory = memory
        self._reasoning_effort = reasoning_effort
        super().__init__(logs_dir, *args, **kwargs)

    @staticmethod
    def name() -> str:
        return "core-ai-cli"

    def get_version_command(self) -> str:
        return "core-ai-cli --version 2>&1 || true"

    def parse_version(self, stdout: str) -> str:
        match = re.search(r"(\d+\.\d+[\.\d]*)", stdout or "")
        return match.group(1) if match else (stdout or "").strip()

    # ------------------------------------------------------------------
    # install
    # ------------------------------------------------------------------

    async def install(self, environment: BaseEnvironment) -> None:
        if self._binary_path:
            await environment.upload_file(self._binary_path, _REMOTE_BIN)
            await self.exec_as_root(environment, command=f"chmod +x {_REMOTE_BIN}")
        else:
            await self._install_from_release(environment)
        await self._probe_time_budget(environment)

    async def _probe_time_budget(self, environment: BaseEnvironment) -> None:
        try:
            probe = await self.exec_as_root(
                environment,
                command=f"{_REMOTE_BIN} --help 2>&1 || true",
            )
            self._supports_time_budget = "--time-budget-seconds" in (probe.stdout or "")
        except Exception:
            self._supports_time_budget = False

    async def _install_from_release(self, environment: BaseEnvironment) -> None:
        version = self._version or "latest"
        if version == "latest":
            base = "https://github.com/chancetop-com/core-ai/releases/latest/download"
        else:
            base = (
                "https://github.com/chancetop-com/core-ai/releases/download/"
                f"v{version}"
            )
        await self.exec_as_root(
            environment,
            command=(
                "set -euo pipefail; "
                "if ! command -v curl >/dev/null 2>&1; then "
                "  (apt-get update && apt-get install -y curl ca-certificates) "
                "  || (apk add --no-cache curl ca-certificates) "
                "  || (dnf install -y curl ca-certificates); "
                "fi; "
                f"curl -fsSL {base}/core-ai-cli-linux.tar.gz "
                "-o /tmp/core-ai-cli.tar.gz; "
                "tar -xzf /tmp/core-ai-cli.tar.gz -C /usr/local/bin; "
                f"mv /usr/local/bin/core-ai-cli-linux {_REMOTE_BIN}; "
                f"chmod +x {_REMOTE_BIN}; "
                f"{_REMOTE_BIN} --version"
            ),
        )

    # ------------------------------------------------------------------
    # model / config
    # ------------------------------------------------------------------

    def _resolve_provider_and_model(self) -> tuple[str, str]:
        model_name = self.model_name or _host_default_model() or _DEFAULT_MODEL
        if "/" not in model_name:
            return "litellm", model_name
        provider, model = model_name.split("/", 1)
        if provider not in _PROVIDER_ENV_KEYS:
            return "litellm", model_name
        return provider, model

    def _resolve_api_key(self, provider: str) -> str:
        if self._api_key:
            return self._api_key
        for key in _PROVIDER_ENV_KEYS.get(provider, []):
            value = self._get_env(key)
            if value:
                return value
        if provider == "litellm":
            entry = _active_auth_entry()
            if entry and entry.get("api_key"):
                return str(entry["api_key"])
        return ""

    def _resolve_api_base(self, provider: str) -> str | None:
        if self._api_base:
            return self._api_base
        for key in _PROVIDER_BASE_ENV_KEYS.get(provider, []):
            value = self._get_env(key)
            if value:
                return value
        if provider == "litellm":
            entry = _active_auth_entry()
            if entry and entry.get("server_url"):
                return str(entry["server_url"]).rstrip("/") + "/api/cli/v1"
        return None

    def _build_config(self, provider: str, model: str, api_key: str) -> str:
        lines = []
        api_base = self._resolve_api_base(provider)
        if api_base:
            lines.append(f"{provider}.api.base={api_base}")
        lines.extend(
            [
                f"{provider}.api.key={api_key}",
                f"{provider}.model={model}",
                f"{provider}.models={model}",
                "active.provider=" + provider,
                "llm.model=" + model,
                "username=bench",
                f"agent.max.turn={self._max_turns or 10000}",
                f"agent.coding.enabled={str(self._coding).lower()}",
                f"agent.todo.v2.enabled={str(self._todo_v2).lower()}",
                f"agent.memory.enabled={str(self._memory).lower()}",
                "agent.memory.prompt.extraction=false",
            ]
        )
        if self._reasoning_effort:
            lines.append(
                "llm.request.extra_body="
                + json.dumps(
                    {"reasoning_effort": self._reasoning_effort},
                    separators=(",", ":"),
                )
            )
        if self._subagent_concurrency:
            lines.append(f"agent.subagent.max-concurrent={int(self._subagent_concurrency)}")
        if self._token_budget is not None and self._token_budget > 0:
            lines.append(f"agent.budget.tokens={int(self._token_budget)}")
        if self._cost_budget_usd is not None and self._cost_budget_usd > 0:
            lines.append(f"agent.budget.cost.usd={float(self._cost_budget_usd)}")
        if self._budget_reserve_tokens is not None and self._budget_reserve_tokens > 0:
            lines.append(f"agent.budget.reserve.tokens={int(self._budget_reserve_tokens)}")
        if self._budget_reserve_cost_usd is not None and self._budget_reserve_cost_usd > 0:
            lines.append(f"agent.budget.reserve.cost.usd={float(self._budget_reserve_cost_usd)}")
        if self._budget_reserve_percent:
            lines.append(f"agent.budget.reserve.percent={int(self._budget_reserve_percent)}")
        return "\n".join(lines) + "\n"

    def _trial_config(self) -> dict | None:
        path = Path(self.logs_dir).parent / "config.json"
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def _task_agent_timeout_sec(self) -> float | None:
        cfg = self._trial_config()
        if not cfg:
            return None
        task_path = (cfg.get("task") or {}).get("path")
        if not task_path:
            return None
        task_toml = Path(task_path) / "task.toml"
        try:
            import tomllib
            with task_toml.open("rb") as fh:
                task = tomllib.load(fh)
        except (OSError, ImportError):
            return None

        base = (task.get("agent") or {}).get("timeout_sec")
        agent_cfg = cfg.get("agent") or {}
        base = agent_cfg.get("override_timeout_sec") or base
        if base is None:
            return None
        max_sec = agent_cfg.get("max_timeout_sec")
        multiplier = cfg.get("agent_timeout_multiplier")
        if multiplier is None:
            multiplier = cfg.get("timeout_multiplier") or 1.0
        return min(float(base), float(max_sec) if max_sec else float("inf")) * float(multiplier)

    def _resolve_time_budget(self) -> int | None:
        timeout = self._task_agent_timeout_sec() if self._auto_time_budget else None
        margin = max(5, int(self._budget_margin_seconds))
        if self._time_budget_seconds is not None:
            explicit = int(self._time_budget_seconds)
            if explicit <= 0:
                return None
            if timeout is not None:
                explicit = min(explicit, max(1, int(timeout - margin)))
            return explicit
        if not self._auto_time_budget or timeout is None:
            return None
        return max(1, int(timeout - margin))

    def _build_auth_config(self, provider: str, api_key: str) -> str | None:
        if provider != "litellm":
            return None
        entry = _active_auth_entry()
        if not entry or not entry.get("server_url") or not entry.get("user_id"):
            return None
        auth = {
            "server_url": str(entry["server_url"]).rstrip("/"),
            "api_key": api_key or str(entry.get("api_key") or ""),
            "user_id": str(entry["user_id"]),
            "name": str(entry.get("name") or ""),
            "role": str(entry.get("role") or ""),
            "login_at": str(entry.get("login_at") or ""),
            "active": True,
            "insecure": bool(entry.get("insecure", False)),
        }
        return json.dumps([auth], separators=(",", ":"))

    async def _prepare_config(
        self,
        environment: BaseEnvironment,
        config: str,
        auth_config: str | None,
    ) -> None:
        command = (
            "mkdir -p ~/.core-ai && "
            f"printf %s {shlex.quote(config)} > ~/.core-ai/agent.properties"
        )
        if auth_config:
            command += (
                f" && printf %s {shlex.quote(auth_config)} > ~/.core-ai/auth.json"
            )
        await self.exec_as_agent(environment, command=command)

    # ------------------------------------------------------------------
    # run
    # ------------------------------------------------------------------

    @with_prompt_template
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        provider, model = self._resolve_provider_and_model()
        api_key = self._resolve_api_key(provider)
        config = self._build_config(provider, model, api_key)
        auth_config = self._build_auth_config(provider, api_key)
        await self._prepare_config(environment, config, auth_config)

        workspace = environment.task_env_config.workdir
        if not workspace:
            pwd = await environment.exec(command="pwd")
            workspace = (pwd.stdout or "/app").strip() or "/app"
        if not workspace.startswith("/"):
            workspace = "/app"

        flags = [
            "--dangerously-skip-permissions",
            f"--workspace {shlex.quote(workspace)}",
            f"--prompt {shlex.quote(instruction)}",
        ]
        budget = self._resolve_time_budget()
        hard_limit = self._time_limit_seconds
        if budget and self._supports_time_budget:
            flags.append(f"--time-budget-seconds {budget}")
            hard_limit = budget if hard_limit is None else min(hard_limit, budget)
        elif budget and not self._supports_time_budget:
            hard_limit = budget if hard_limit is None else min(hard_limit, budget)
            print(f"warning: this core-ai-cli does not support --time-budget-seconds; using time limit {hard_limit}")
        if hard_limit:
            flags.append(f"--time-limit-seconds {int(hard_limit)}")

        env: dict[str, str] = {}
        for key in _PROVIDER_ENV_KEYS.get(provider, []):
            value = self._get_env(key)
            if value:
                env[key] = value

        output_path = EnvironmentPaths.agent_dir / _OUTPUT_FILENAME
        await self.exec_as_agent(
            environment,
            command=(
                'export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"; '
                f"core-ai-cli {' '.join(flags)} 2>&1 | tee {output_path}"
            ),
            env=env,
        )

    def populate_context_post_run(self, context: AgentContext) -> None:
        # core-ai-cli prints a human-readable stream, not an ATIF trajectory.
        # Parse its final usage line, e.g.
        #   ✦ 22s | 138,223 tokens (↑ 135,776 ↓ 2,447 ~115,968)
        log_path = self.logs_dir / _OUTPUT_FILENAME
        try:
            text = log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return
        matches = re.findall(
            r"([\d,]+) tokens \(↑ ([\d,]+) ↓ ([\d,]+)(?: ~([\d,]+))?",
            text,
        )
        if not matches:
            return
        _, input_tokens, output_tokens, cache_tokens = matches[-1]
        context.n_input_tokens = int(input_tokens.replace(",", ""))
        context.n_output_tokens = int(output_tokens.replace(",", ""))
        if cache_tokens:
            context.n_cache_tokens = int(cache_tokens.replace(",", ""))
