# Quick Start

Choose the part that matches your task. Server, CLI and the Java framework live in the same repository.

| Task | Starting point | Requirements |
| --- | --- | --- |
| Use shared agents and tools | [Server guide](/en/server) | Your administrator's service URL, account and permissions |
| Work from a terminal | [CLI guide](/en/cli) | A release for your OS and architecture; Java 25 for source builds |
| Build agents in Java | [Framework guide](/en/framework) | Java 25, Gradle Wrapper 9.3.0 and project dependencies |

## Check your CLI {#_1-检查本地入口}

```sh
core-ai-cli --version
core-ai-cli --help
core-ai-cli mcp call --help
```

These help commands do not call a model or Server. The root command exposes `mcp`, `skill`, `api-tool`, `agent`, `report`, `dataset` and `catalog`. Eight help/version commands were verified through offline-compiled JVM source on macOS; native release installation was not tested.

## Start offline {#_2-运行离线演示}

[The framework guide](/en/framework#离线-agent) includes a tested Mock Agent and a repeatable script. It requires existing Gradle/SpotBugs classpaths and cached dependencies; it stops if they are missing. The [FakeSession example](/cn/manual/#_29-python-离线演示与截图) checks discovery, arguments and results without a remote service.

Current source baseline: `e1afa9fa`, CLI 2.0.21, framework 1.4.0-SNAPSHOT, public API 1.3.0-SNAPSHOT. Use same-checkout project dependencies or artifacts confirmed by your team; the version constants do not establish Maven publication. Current agents use `run` returning `String`, function annotations from `ai.core.api.tool.function`, and `StreamingCallback.onChunk(String)`.

## Connect your test environment {#_3-接入自己的测试环境}

Follow the [complete manual (Chinese)](/cn/manual/) for installation, Server login, model configuration, REST/SSE, five screenshots and collaboration examples. Check [Windows / macOS troubleshooting](/en/cli-troubleshooting) for PATH, shell, encoding and config paths.

Server startup, real model requests, Windows execution and native release installation were not verified in this documentation task. Historical design records may describe earlier APIs or proposals; use the current manual and source contracts when developing against this baseline.
