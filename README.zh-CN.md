<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./assets/core-ai-logo-v5-symbol-c-wordmark-dark.svg">
    <img src="./assets/core-ai-logo-v5-symbol-c-wordmark.svg" alt="core-ai" width="640">
  </picture>
</p>

<div align="center">

[![Java Source](https://img.shields.io/badge/Java%20source-25-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](https://chancetop-com.github.io/core-ai/cn/)
[![GitHub Stars](https://img.shields.io/github/stars/chancetop-com/core-ai?style=social)](https://github.com/chancetop-com/core-ai)

[English](README.md) | [中文](README.zh-CN.md)

</div>

# Core AI

## 中文官网

**[访问 Core AI 中文官网 →](https://chancetop-com.github.io/core-ai/cn/)** · **[中文使用手册：Server / CLI / 框架](https://chancetop-com.github.io/core-ai/cn/manual/)** · [English documentation](https://chancetop-com.github.io/core-ai/en/)

Core AI 在同一仓库中提供团队 Agent 服务、终端 CLI 和 Java 框架。先按任务选择入口，再逐步接入自己的模型、工具与运行环境。

## 选择入口

| 部分 | 用途 | 开始阅读 |
| --- | --- | --- |
| **core-ai-server** | 管理 Agent、工具、技能和数据集，提供 Web、REST 与 SSE | [Server 上手](https://chancetop-com.github.io/core-ai/cn/server) |
| **core-ai-cli** | 本地 Agent、编辑器 ACP 和 Server Hub 资源命令；可独立配置模型或登录 Server | [CLI 上手](https://chancetop-com.github.io/core-ai/cn/cli) |
| **core-ai 框架** | 在 Java 应用中构建 Agent、函数工具、上下文、记忆、RAG 与 Flow | [框架上手](https://chancetop-com.github.io/core-ai/cn/framework) |

Server 与 CLI 共享 `core-ai` 和 `core-ai-api`。框架 Flow 与 Server Web 工作流有不同定义，配置不能直接互换。

## 版本与环境

当前源码：CLI **2.0.21**、框架 **1.4.0-SNAPSHOT**、公共 API **1.3.0-SNAPSHOT**。版本来源为 [CLI VERSION](core-ai-cli/src/main/resources/VERSION) 与 [ProjectVersions](buildSrc/src/main/kotlin/Versions.kt)；发布包先检查自己的 `--version`。

| 路线 | 要求 |
| --- | --- |
| 原生 CLI 发布包 | 使用所选 release 对应的系统与架构；无需单独安装 Java |
| Java 源码构建或 JVM 运行 | **JDK 25**，使用仓库 **Gradle Wrapper 9.3.0**；框架、API、CLI、Server 的源码构建共用 Java 25 toolchain |
| CLI 原生编译 | **GraalVM JDK 25** 与对应平台的 native-image 构建依赖 |
| Server 容器 | Docker / Compose、MongoDB、Redis 及自己的模型配置；宿主机无需安装 JDK |
| Server 前端构建 | 当前发布流程使用 **Node.js 22** 与 npm |

SNAPSHOT 版本常量不代表 Maven 制品已发布。仓库内优先使用 project 依赖；独立应用使用团队确认的制品或 composite build。

## 快速开始

### 1. 下载 CLI，先检查 help

从 [Releases](https://github.com/chancetop-com/core-ai/releases) 选择版本和架构。已核对的 [v2.0.21](https://github.com/chancetop-com/core-ai/releases/tag/v2.0.21) 提供以下包；后续版本以实际资产为准：

| 系统 | 压缩包 | 包内可执行文件 |
| --- | --- | --- |
| macOS | `core-ai-cli-darwin.tar.gz` | `core-ai-cli-darwin` |
| Linux | `core-ai-cli-linux.tar.gz` | `core-ai-cli-linux` |
| Windows | `core-ai-cli-windows.zip` | `core-ai-cli-windows.exe` |

在下载目录解压并先确认版本。macOS：

```sh
tar -xzf core-ai-cli-darwin.tar.gz
./core-ai-cli-darwin --version
./core-ai-cli-darwin --help
```

Linux 使用对应的 `core-ai-cli-linux.tar.gz` 和 `./core-ai-cli-linux`。Windows PowerShell：

```powershell
Expand-Archive -Path .\core-ai-cli-windows.zip -DestinationPath .\core-ai-cli
.\core-ai-cli\core-ai-cli-windows.exe --version
.\core-ai-cli\core-ai-cli-windows.exe --help
```

预期输出版本与参数。之后按[安装指南](https://chancetop-com.github.io/core-ai/cn/manual/#_10-macos-安装与检查)将程序命名为 `core-ai-cli` / `core-ai-cli.exe` 并加入用户 PATH；[Windows 安装](https://chancetop-com.github.io/core-ai/cn/manual/#_11-windows-安装与检查)和[跨平台排障](https://chancetop-com.github.io/core-ai/cn/cli-troubleshooting)说明路径、权限、shell 与编码差异。以下命令假设已经完成用户 PATH 配置。

### 2. 配置模型或连接 Server

独立使用时，在 `~/.core-ai/agent.properties` 配置自己的 provider、模型与测试凭据；工作区 `.core-ai/agent.properties` 可覆盖全局配置。Windows 用户目录通常是 `%USERPROFILE%`。[配置步骤](https://chancetop-com.github.io/core-ai/cn/manual/#_13-登录-server-或独立配置模型)列出实际属性。

已有团队 Server 时，替换占位地址后交互登录，再发现可见资源：

```sh
core-ai-cli --login=https://core-ai.example.com
core-ai-cli catalog --json
core-ai-cli mcp search echo --json
```

把搜索结果的实际路径替换下面的 `SERVER/TOOL`；先查看 schema，按 schema 创建 UTF-8 `args.json` 后再调用：

```sh
core-ai-cli mcp describe SERVER/TOOL --json
core-ai-cli mcp call SERVER/TOOL --args-file args.json --json
```

模型配置完成后，可进入工作区、发送单次提示或恢复会话：

```sh
core-ai-cli --workspace /path/to/project
core-ai-cli --prompt "Hello"
core-ai-cli --continue
core-ai-cli --resume
```

这些操作需要自己的模型/Server 环境，可能产生模型调用。会话中使用 `/help` 查看交互命令。

### 3. 使用或部署 Server

已有服务的读者按 [Server 指南](https://chancetop-com.github.io/core-ai/cn/server)取得账号、可用模型和资源权限，再从 Agents / Chat 开始。

自行部署先按[Compose 步骤](https://chancetop-com.github.io/core-ai/cn/manual/#_2-使用仓库-compose)复制并编辑配置，准备 external 数据卷、MongoDB、Redis、管理员、模型与沙箱设置，然后再检查和启动自己的文件：

```sh
docker compose -f docker-compose.manual.yml config --quiet
docker compose -f docker-compose.manual.yml up -d
docker compose -f docker-compose.manual.yml ps
```

`docker-compose.manual.yml` 是手册建议的本地配置文件名，需要先创建和配置。浏览器访问自己配置的 `SYS_PUBLIC_URL`。[Server 手册](https://chancetop-com.github.io/core-ai/cn/manual/#第一部分-core-ai-server)涵盖源码启动、REST/SSE、截图与排障。

### 4. 开发 Java Agent

在仓库内模块使用同 checkout 的依赖：

```kotlin
dependencies {
    implementation(project(":core-ai"))
    implementation(project(":core-ai-api"))
}
```

应用通过 `Agent.builder()` 配置 provider、模型与工具；公共调用 `agent.run(query, context)` 返回 `String`。从[离线 Mock Agent](https://chancetop-com.github.io/core-ai/cn/framework#离线-agent)获得第一个可核对结果，再阅读[基础 Agent](https://chancetop-com.github.io/core-ai/cn/tutorial-basic-agent)和[已编译 API 片段](docs/examples/GuideApiExamples.java)。离线脚本需要已有依赖缓存，其前提在指南中明确说明。

## 从源码运行

```sh
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai
./gradlew :core-ai-cli:run --args='--help'
```

Windows PowerShell 使用 `.\gradlew.bat :core-ai-cli:run --args='--help'`。CLI 原生构建任务是 `:core-ai-cli:nativeCompile`；Server 配置与 `:core-ai-server:installDist` 见[源码启动指南](https://chancetop-com.github.io/core-ai/cn/manual/#_3-配置项与源码启动)。首次构建需要访问所需依赖仓库；完整构建与服务测试有各自环境前提。

## 文档与验证范围

[快速开始](https://chancetop-com.github.io/core-ai/cn/quickstart) · [完整中文手册](https://chancetop-com.github.io/core-ai/cn/manual/) · [教程索引](https://chancetop-com.github.io/core-ai/cn/tutorials) · [API 参考](https://chancetop-com.github.io/core-ai/cn/api) · [English docs](https://chancetop-com.github.io/core-ai/en/)

macOS 的源码 JVM help/version、Mock Agent 与 Python FakeSession 已验证；指南中的当前 API 片段已编译核对。发布包安装、Windows/Linux 实机、Server/Docker 启动和真实模型请求未在本次文档工作中执行。详细手册目前为中文，英文使用指南与教程提供对应入口。

## 贡献

通过 [Issues](https://github.com/chancetop-com/core-ai/issues)提交版本、复现步骤与去敏错误。代码变更遵循 [AGENTS.md](AGENTS.md)，运行相关模块检查；文档变更可在 `docs/` 使用 `npm ci`、`npm run build` 和 `npm run check:links`（当前文档 CI 使用 Node 20 与 Python 3）。请附上验证范围。

## 许可

保留项目现有的 Apache 2.0 标识，并链接 [Apache License 2.0 正文](https://www.apache.org/licenses/LICENSE-2.0)。当前 checkout 没有独立 `LICENSE` 文件，正式仓库许可文件仍需维护者补齐。
