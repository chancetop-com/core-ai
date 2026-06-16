import shlex
from pathlib import Path

from harbor.agents.installed.base import (
    BaseInstalledAgent,
    CliFlag,
    with_prompt_template,
)
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

_PROVIDER_ENV_KEYS: dict[str, list[str]] = {
    "openrouter": ["OPENROUTER_API_KEY"],
    "openai": ["OPENAI_API_KEY"],
    "deepseek": ["DEEPSEEK_API_KEY"],
    "azure": ["AZURE_OPENAI_API_KEY"],
    "azure-inference": ["AZURE_INFERENCE_API_KEY"],
    "litellm": ["LITELLM_API_KEY"],
}

# Providers that require a base URL in agent.properties ({provider}.api.base)
_PROVIDER_BASE_URL_KEYS: dict[str, list[str]] = {
    "litellm": ["LITELLM_API_BASE", "LITELLM_BASE_URL"],
}

_OUTPUT_FILENAME = "core-ai-cli.txt"
_REMOTE_INSTALL_DIR = "/opt/core-ai-cli"


class CoreAiCli(BaseInstalledAgent):
    """
    Core-AI CLI agent — a Java-based AI coding assistant.

    Installation requires either a pre-built Gradle distribution directory
    (``install_dir``) or a native binary path (``binary_path``).  When neither
    is supplied the agent attempts to download the latest release from GitHub.

    Model name format follows the Harbor convention ``provider/model``, e.g.
    ``openrouter/anthropic/claude-sonnet-4-5``.  For providers that include a
    sub-path (OpenRouter), the first segment is the provider name and the rest
    is the model identifier passed to core-ai-cli.
    """

    CLI_FLAGS = [
        CliFlag(
            "max_turns",
            cli="--max-turns",
            type="int",
            env_fallback="CORE_AI_MAX_TURNS",
        ),
    ]

    def __init__(
        self,
        logs_dir: Path,
        install_dir: str | None = None,
        binary_path: str | None = None,
        jre_path: str | None = None,
        api_key: str | None = None,
        api_base: str | None = None,
        *args,
        **kwargs,
    ):
        """
        Args:
            logs_dir: Directory for trial logs.
            install_dir: Path to a Gradle install distribution directory
                (the directory that contains ``bin/`` and ``lib/``).
                If supplied, it is uploaded to the container and Java is
                installed as a runtime dependency.
            binary_path: Path to a pre-built native ``core-ai-cli`` binary.
                Takes precedence over ``install_dir`` when both are set.
            jre_path: Path to a pre-extracted Linux JRE directory on the host
                (the directory that contains ``bin/java``). When set, the JRE
                is uploaded directly instead of installed via apt-get, making
                the setup fully offline. Takes precedence over apt-get when
                ``install_dir`` is used.
            api_key: API key for the LLM provider.  Falls back to the
                provider-specific environment variable if not set.
            api_base: Base URL for providers that require it (e.g. litellm).
                Falls back to the provider-specific environment variable.
        """
        self._install_dir = install_dir
        self._binary_path = binary_path
        self._jre_path = jre_path
        self._api_key = api_key
        self._api_base = api_base
        super().__init__(logs_dir, *args, **kwargs)

    @staticmethod
    def name() -> str:
        return "core-ai-cli"

    def get_version_command(self) -> str | None:
        return "core-ai-cli --version 2>&1 || true"

    def parse_version(self, stdout: str) -> str:
        # core-ai-cli prints "1.0.0" or similar
        import re

        text = stdout.strip()
        match = re.search(r"(\d+\.\d+[\.\d]*)", text)
        if match:
            return match.group(1)
        return text

    # ------------------------------------------------------------------
    # Installation
    # ------------------------------------------------------------------

    async def install(self, environment: BaseEnvironment) -> None:
        if self._binary_path:
            await self._install_native_binary(environment)
        elif self._install_dir:
            await self._install_from_dist(environment)
        else:
            await self._install_from_github(environment)

    async def _install_native_binary(self, environment: BaseEnvironment) -> None:
        """Upload a locally-built native binary to the container."""
        await environment.upload_file(self._binary_path, "/usr/local/bin/core-ai-cli")  # type: ignore[arg-type]
        await self.exec_as_root(
            environment,
            command="chmod +x /usr/local/bin/core-ai-cli",
        )

    async def _install_from_dist(self, environment: BaseEnvironment) -> None:
        """Upload a Gradle application distribution and ensure Java is available."""
        await self._ensure_java(environment)
        await environment.upload_dir(self._install_dir, _REMOTE_INSTALL_DIR)  # type: ignore[arg-type]
        await self.exec_as_root(
            environment,
            command=(
                f"chmod +x {_REMOTE_INSTALL_DIR}/bin/core-ai-cli && "
                f"ln -sf {_REMOTE_INSTALL_DIR}/bin/core-ai-cli /usr/local/bin/core-ai-cli"
            ),
        )

    async def _ensure_java(self, environment: BaseEnvironment) -> None:
        """Make Java available in the container via one of three strategies:
        1. jre_path supplied → upload from host (fully offline)
        2. java already in PATH → nothing to do
        3. fallback → apt-get install (requires internet)
        """
        _REMOTE_JRE_DIR = "/opt/jre"

        if self._jre_path:
            await environment.upload_dir(self._jre_path, _REMOTE_JRE_DIR)
            await self.exec_as_root(
                environment,
                command=(
                    f"chmod +x {_REMOTE_JRE_DIR}/bin/java && "
                    f"ln -sf {_REMOTE_JRE_DIR}/bin/java /usr/local/bin/java"
                ),
            )
            return

        java_check = await environment.exec(command="java -version 2>&1; echo $?")
        if (java_check.stdout or "").strip().endswith("0"):
            return  # already installed

        await self.exec_as_root(
            environment,
            command="apt-get update && apt-get install -y openjdk-21-jre-headless",
            env={"DEBIAN_FRONTEND": "noninteractive"},
        )

    async def _install_from_github(self, environment: BaseEnvironment) -> None:
        """Download native binary from GitHub releases."""
        await self.exec_as_root(
            environment,
            command="apt-get update && apt-get install -y curl",
            env={"DEBIAN_FRONTEND": "noninteractive"},
        )
        version_tag = f"v{self._version}" if self._version else "latest"
        if version_tag == "latest":
            base_url = (
                "https://github.com/chancetop-com/core-ai/releases/latest/download"
            )
        else:
            base_url = f"https://github.com/chancetop-com/core-ai/releases/download/{version_tag}"

        await self.exec_as_agent(
            environment,
            command=(
                "set -euo pipefail; "
                'ARCH=$(uname -m | sed "s/x86_64/amd64/;s/aarch64/arm64/"); '
                f'URL="{base_url}/core-ai-cli-linux-${{ARCH}}"; '
                'mkdir -p "$HOME/.local/bin" && '
                'curl -fsSL "$URL" -o "$HOME/.local/bin/core-ai-cli" && '
                'chmod +x "$HOME/.local/bin/core-ai-cli" && '
                'export PATH="$HOME/.local/bin:$PATH" && '
                "core-ai-cli --version"
            ),
        )

    # ------------------------------------------------------------------
    # Config helpers
    # ------------------------------------------------------------------

    def _resolve_provider_and_model(self) -> tuple[str, str]:
        """Split ``provider/model`` into (provider, model).

        For OpenRouter the model string itself contains a slash
        (e.g. ``anthropic/claude-sonnet-4-5``), so only the first segment is
        the provider.
        """
        if not self.model_name:
            return "openrouter", "anthropic/claude-sonnet-4-5"
        if "/" not in self.model_name:
            return "openrouter", self.model_name
        provider, model = self.model_name.split("/", 1)
        return provider, model

    def _resolve_api_key(self, provider: str) -> str:
        if self._api_key:
            return self._api_key
        for env_key in _PROVIDER_ENV_KEYS.get(provider, []):
            val = self._get_env(env_key)
            if val:
                return val
        return ""

    def _resolve_api_base(self, provider: str) -> str | None:
        if self._api_base:
            return self._api_base
        for env_key in _PROVIDER_BASE_URL_KEYS.get(provider, []):
            val = self._get_env(env_key)
            if val:
                return val
        return None

    def _build_config_properties(self, provider: str, model: str, api_key: str) -> str:
        lines = [
            f"{provider}.api.key={api_key}",
            f"{provider}.model={model}",
            f"{provider}.models={model}",
            f"active.provider={provider}",
            # Dummy username — required by InteractiveConfigSetup.writeConfig() for
            # litellm; harmless for other providers.
            "username=harbor",
            "agent.max.turn=10000",
            "agent.coding.enabled=true",
            "agent.todo.v2.enabled=true",
        ]
        api_base = self._resolve_api_base(provider)
        if api_base:
            lines.insert(0, f"{provider}.api.base={api_base}")
        # litellm needs extra tuning fields to avoid hitting default timeouts
        if provider == "litellm":
            lines += [
                "litellm.stream.buffer.size=256",
                "litellm.timeout.seconds=300",
            ]
        return "\n".join(lines)

    # ------------------------------------------------------------------
    # ATIF context
    # ------------------------------------------------------------------

    def populate_context_post_run(self, context: AgentContext) -> None:
        # Plain text output only — no structured trajectory for now.
        pass

    # ------------------------------------------------------------------
    # Run
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

        config_content = self._build_config_properties(provider, model, api_key)
        escaped_config = shlex.quote(config_content)
        escaped_instruction = shlex.quote(instruction)

        env: dict[str, str] = {}
        for env_key in _PROVIDER_ENV_KEYS.get(provider, []):
            val = self._get_env(env_key)
            if val:
                env[env_key] = val

        # When using a JRE/JDK distribution, set JAVA_HOME so the Gradle startup
        # script resolves the full path to java directly (JAVACMD=$JAVA_HOME/bin/java)
        # instead of relying on PATH lookup which can fail in some shell environments.
        if self._install_dir and not self._binary_path:
            env["JAVA_HOME"] = "/opt/jre"

        # Write config to the default location (~/.core-ai/agent.properties).
        # InteractiveConfigSetup.isConfigValid() hard-codes this path, so using
        # --config /tmp/... alone is not enough to bypass the interactive setup.
        await self.exec_as_agent(
            environment,
            command=(
                f"mkdir -p ~/.core-ai && "
                f"printf %s {escaped_config} > ~/.core-ai/agent.properties"
            ),
            env=env,
        )

        cli_flags = self.build_cli_flags()
        extra_flags = (cli_flags + " ") if cli_flags else ""

        await self.exec_as_agent(
            environment,
            command=(
                'export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"; '
                f"core-ai-cli "
                f"--dangerously-skip-permissions "
                f"--workspace /app "
                f"--prompt {escaped_instruction} "
                f"{extra_flags}"
                f"2>&1 | tee /logs/agent/{_OUTPUT_FILENAME}"
            ),
            env=env,
        )
