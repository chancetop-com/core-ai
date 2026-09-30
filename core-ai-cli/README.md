# core-ai-cli

The Core-AI terminal agent: interactive REPL, one-shot `--prompt` mode and ACP agent mode,
shipped as a GraalVM native binary.

## Contents

| Package | Purpose |
| --- | --- |
| `ai.core.cli.agent` | Agent session loop, turn execution and session persistence |
| `ai.core.cli.command` | Slash commands and REPL handlers |
| `ai.core.cli.hub` | `mcp` / `skill` / `api-tool` / `agent` hub subcommands |
| `ai.core.cli.config` | `agent.properties` and LLM provider configuration |
| `ai.core.cli.ui` | Terminal rendering, pickers and spinners |
| `ai.core.cli.memory` | Session memory and knowledge extraction |
| `ai.core.cli.skill` / `plugin` / `hook` | Skills, plugins and lifecycle hooks |
| `ai.core.cli.acp` / `a2a` | Agent Client Protocol (stdio) and remote agent calls |
| `ai.core.cli.upgrade` | Self-update from GitHub releases |

## Build

```bash
./gradlew :core-ai-cli:nativeCompile   # native binary (GraalVM)
./gradlew :core-ai-cli:run             # run on the JVM
```

Build config lives in the root `build.gradle.kts` (`project(":core-ai-cli") { ... }`).

## Install the manual as a skill

Usage is documented in the [CLI manual](../docs/skills/core-ai-cli-manual/SKILL.md) — also published
as the `core-ai-cli-manual` agent skill, so tools that load `SKILL.md` skills (Claude Code, Codex, …)
can pick it up directly:

- **Download**: <https://chancetop-com.github.io/core-ai/skills/core-ai-cli-manual.zip> — unzip into
  the tool's skills directory (`~/.claude/skills/`, `~/.agents/skills/`, `~/.core-ai/skills/`, …).
- **Codex**: `$skill-installer install https://github.com/chancetop-com/core-ai/tree/master/docs/skills/core-ai-cli-manual`
- **core-ai-cli**: `core-ai-cli skill pull <namespace>/core-ai-cli-manual` (needs a logged-in
  core-ai-server), or unzip the download into `~/.core-ai/skills/`.
- All skills and install notes: <https://chancetop-com.github.io/core-ai/skills/>
