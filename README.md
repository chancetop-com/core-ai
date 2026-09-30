<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./assets/core-ai-logo-v5-symbol-c-wordmark-dark.svg">
    <img src="./assets/core-ai-logo-v5-symbol-c-wordmark.svg" alt="core-ai" width="640">
  </picture>
</p>

<div align="center">

[![Java Source](https://img.shields.io/badge/Java%20source-25-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](https://chancetop-com.github.io/core-ai/en/)
[![GitHub Stars](https://img.shields.io/github/stars/chancetop-com/core-ai?style=social)](https://github.com/chancetop-com/core-ai)

[English](README.md) | [中文](README.zh-CN.md)

</div>

# Core AI

## 中文官网

**[访问 Core AI 中文官网 →](https://chancetop-com.github.io/core-ai/cn/)** · **[中文使用手册：Server / CLI / 框架](https://chancetop-com.github.io/core-ai/cn/manual/)** · [English documentation](https://chancetop-com.github.io/core-ai/en/)

Core AI brings a team Agent service, a terminal CLI and a Java framework into one repository. Choose a starting point, then connect your own models, tools and runtime environment.

## Choose a starting point

| Part | Purpose | Guide |
| --- | --- | --- |
| **core-ai-server** | Manage agents, tools, skills and datasets through Web, REST and SSE | [Server](https://chancetop-com.github.io/core-ai/en/server) |
| **core-ai-cli** | Local agents, editor ACP and Server Hub resource commands; configure a provider directly or sign in to Server | [CLI](https://chancetop-com.github.io/core-ai/en/cli) |
| **core-ai framework** | Build agents, function tools, context, memory, RAG and Flow in Java applications | [Framework](https://chancetop-com.github.io/core-ai/en/framework) |

Server and CLI share `core-ai` and `core-ai-api`. Framework Flow and Server Web workflows have different definitions and configurations.

## Versions and requirements

Current source: CLI **2.0.21**, framework **1.4.0-SNAPSHOT**, public API **1.3.0-SNAPSHOT**. See [CLI VERSION](core-ai-cli/src/main/resources/VERSION) and [ProjectVersions](buildSrc/src/main/kotlin/Versions.kt). Check `--version` for your installed release.

| Route | Requirement |
| --- | --- |
| Native CLI release | Use a release matching your OS and architecture; no separate Java installation is needed |
| Java source build or JVM execution | **JDK 25** and the repository **Gradle Wrapper 9.3.0**; framework, API, CLI and Server source builds share the Java 25 toolchain |
| CLI native compilation | **GraalVM JDK 25** and platform native-image build dependencies |
| Server container | Docker / Compose, MongoDB, Redis and your model configuration; no host JDK is required |
| Server frontend build | The current release workflow uses **Node.js 22** and npm |

SNAPSHOT version constants do not establish Maven availability. Use same-checkout project dependencies, artifacts confirmed by your team, or a composite build.

## Quick start

### 1. Download CLI and check help

Choose a version and architecture from [Releases](https://github.com/chancetop-com/core-ai/releases). The verified [v2.0.21](https://github.com/chancetop-com/core-ai/releases/tag/v2.0.21) asset list contains these packages; check actual assets for later releases:

| OS | Archive | Executable |
| --- | --- | --- |
| macOS | `core-ai-cli-darwin.tar.gz` | `core-ai-cli-darwin` |
| Linux | `core-ai-cli-linux.tar.gz` | `core-ai-cli-linux` |
| Windows | `core-ai-cli-windows.zip` | `core-ai-cli-windows.exe` |

Extract in your download directory and inspect the version. macOS:

```sh
tar -xzf core-ai-cli-darwin.tar.gz
./core-ai-cli-darwin --version
./core-ai-cli-darwin --help
```

For Linux use `core-ai-cli-linux.tar.gz` and `./core-ai-cli-linux`. Windows PowerShell:

```powershell
Expand-Archive -Path .\core-ai-cli-windows.zip -DestinationPath .\core-ai-cli
.\core-ai-cli\core-ai-cli-windows.exe --version
.\core-ai-cli\core-ai-cli-windows.exe --help
```

Expect the version and command parameters. Then follow the [installation steps (Chinese)](https://chancetop-com.github.io/core-ai/cn/manual/#_10-macos-安装与检查) to name the program `core-ai-cli` / `core-ai-cli.exe` and add its user directory to PATH. [Windows installation (Chinese)](https://chancetop-com.github.io/core-ai/cn/manual/#_11-windows-安装与检查) and [troubleshooting](https://chancetop-com.github.io/core-ai/en/cli-troubleshooting) cover paths, permissions, shells and encoding. Subsequent commands assume this PATH setup.

### 2. Configure a model or connect Server

For standalone use, configure your provider, model and test credentials in `~/.core-ai/agent.properties`; workspace `.core-ai/agent.properties` can override global settings. On Windows the user directory is usually `%USERPROFILE%`. [Configuration steps (Chinese)](https://chancetop-com.github.io/core-ai/cn/manual/#_13-登录-server-或独立配置模型) list the actual properties.

With an existing team Server, replace the placeholder URL, sign in interactively, then discover authorized resources:

```sh
core-ai-cli --login=https://core-ai.example.com
core-ai-cli catalog --json
core-ai-cli mcp search echo --json
```

Replace `SERVER/TOOL` with a path returned by search. Describe its schema, prepare UTF-8 `args.json`, then call:

```sh
core-ai-cli mcp describe SERVER/TOOL --json
core-ai-cli mcp call SERVER/TOOL --args-file args.json --json
```

Once model configuration is ready, enter a workspace, send one prompt or resume a session:

```sh
core-ai-cli --workspace /path/to/project
core-ai-cli --prompt "Hello"
core-ai-cli --continue
core-ai-cli --resume
```

These operations use your model/Server environment and may make model requests. Use `/help` inside an interactive session.

### 3. Use or deploy Server

For an existing service, follow the [Server guide](https://chancetop-com.github.io/core-ai/en/server) to obtain an account, available models and resource permissions, then start in Agents / Chat.

For self-hosting, first follow the [Compose steps (Chinese)](https://chancetop-com.github.io/core-ai/cn/manual/#_2-使用仓库-compose): copy and edit the configuration; prepare external volumes, MongoDB, Redis, administrator, models and sandbox settings. Then check and start your file:

```sh
docker compose -f docker-compose.manual.yml config --quiet
docker compose -f docker-compose.manual.yml up -d
docker compose -f docker-compose.manual.yml ps
```

`docker-compose.manual.yml` is the manual's suggested local filename and must be created and configured first. Open your configured `SYS_PUBLIC_URL`. The [Server manual (Chinese)](https://chancetop-com.github.io/core-ai/cn/manual/#第一部分-core-ai-server) includes source startup, REST/SSE, screenshots and troubleshooting.

### 4. Develop a Java Agent

Use same-checkout dependencies in a repository module:

```kotlin
dependencies {
    implementation(project(":core-ai"))
    implementation(project(":core-ai-api"))
}
```

Configure providers, models and tools with `Agent.builder()`; the public `agent.run(query, context)` call returns `String`. Start with the [offline Mock Agent](https://chancetop-com.github.io/core-ai/en/framework#离线-agent), then read [Basic Agent](https://chancetop-com.github.io/core-ai/en/tutorial-basic-agent) and the [compile-checked API fragments](docs/examples/GuideApiExamples.java). The offline script requires cached dependencies, as stated in its guide.

## Run from source

```sh
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai
./gradlew :core-ai-cli:run --args='--help'
```

In Windows PowerShell use `.\gradlew.bat :core-ai-cli:run --args='--help'`. Native CLI compilation uses `:core-ai-cli:nativeCompile`. Server configuration and `:core-ai-server:installDist` are covered by [source startup (Chinese)](https://chancetop-com.github.io/core-ai/cn/manual/#_3-配置项与源码启动). First builds need access to dependency repositories; full builds and service tests have their own environment prerequisites.

## Documentation and validation

[Quick Start](https://chancetop-com.github.io/core-ai/en/quickstart) · [Full Chinese manual](https://chancetop-com.github.io/core-ai/cn/manual/) · [Tutorials](https://chancetop-com.github.io/core-ai/en/tutorials) · [API Reference](https://chancetop-com.github.io/core-ai/en/api) · [中文官网](https://chancetop-com.github.io/core-ai/cn/)

The CLI manual is also published as an installable agent skill (`core-ai-cli-manual`) for tools such as Claude Code and Codex; [download and install instructions](https://chancetop-com.github.io/core-ai/skills/).

Source JVM help/version, Mock Agent and Python FakeSession were tested on macOS; current guide API fragments were compile-checked. Native installation, Windows/Linux execution, Server/Docker startup and live models were not exercised in this documentation work. The detailed operations manual is currently Chinese; English use guides and tutorials have corresponding entries.

## Contributing

Submit versions, reproduction steps and sanitized errors through [Issues](https://github.com/chancetop-com/core-ai/issues). Follow [AGENTS.md](AGENTS.md) and relevant module checks for code changes. For documentation, run `npm ci`, `npm run build` and `npm run check:links` in `docs/` (current docs CI uses Node 20 and Python 3). Include the scope of validation.

## License

The existing project badge identifies Apache 2.0 and links to the [Apache License 2.0 text](https://www.apache.org/licenses/LICENSE-2.0). This checkout has no standalone `LICENSE` file; maintainers still need to add the repository's license file.
