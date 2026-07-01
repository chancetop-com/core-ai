<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./assets/core-ai-logo-v5-symbol-c-wordmark-dark.svg">
    <img src="./assets/core-ai-logo-v5-symbol-c-wordmark.svg" alt="core-ai" width="640">
  </picture>
</p>

<div align="center">

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](https://chancetop-com.github.io/core-ai/)
[![GitHub Stars](https://img.shields.io/github/stars/chancetop-com/core-ai?style=social)](https://github.com/chancetop-com/core-ai)

[English](README.md) | [中文](README.zh-CN.md)

</div>

---

## 🌟 Core-AI：你的终端 AI 智能助手 & 代理服务器

Core-AI 为你提供在终端中运行的 AI 编程助手（`core-ai-cli`）和可自部署的代理服务器（`core-ai-server`），附带 Web 管理界面。CLI 可独立使用（直接配置任意 LLM 提供商的 API Key），也可连接 core-ai-server 获取团队协作功能。在命令行或浏览器中与大模型对话、执行工具、编排子代理、管理知识库。

### 🚀 快速开始

#### CLI 工具

几秒内下载运行：

```bash
# macOS
curl -L -o core-ai-cli https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-darwin
chmod +x core-ai-cli && sudo mv core-ai-cli /usr/local/bin/

# Linux
curl -L -o core-ai-cli https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-linux
chmod +x core-ai-cli && sudo mv core-ai-cli /usr/local/bin/

# Windows (PowerShell)
mkdir "$env:USERPROFILE\bin" -Force
Invoke-WebRequest -Uri "https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-windows.exe" -OutFile "$env:USERPROFILE\bin\core-ai-cli.exe"
```

```bash
core-ai-cli
```

CLI 支持两种使用模式：

- **独立模式** — 直接在 `~/.core-ai/agent.properties` 中配置提供商的 API Key 即可开始对话。支持 OpenAI、DeepSeek、OpenRouter、Azure、LiteLLM 及任何兼容 OpenAI 的 API。
- **连接服务器** — 登录 core-ai-server 自动配置 LLM 代理，共享代理和团队功能。

首次运行时如无配置，CLI 会引导你登录服务器。你可以随时通过 `/model` 命令添加或切换提供商。

**常用命令：**

```bash
core-ai-cli                                          # 交互式对话
core-ai-cli --prompt "解释量子计算"                     # 单次查询
core-ai-cli --model "openai/gpt-4o"                    # 指定模型
core-ai-cli --workspace /path/to/project                # 设置工作目录
core-ai-cli --continue                                  # 恢复上次会话
core-ai-cli --server https://your-server.com --api-key your-token  # 连接远程服务器
core-ai-cli --serve                                     # 启动 A2A Web 服务器
core-ai-cli --upgrade                                   # 自动更新
```

#### 服务器（Docker）

一行命令在本地启动 core-ai-server：

**前置条件：** [Docker](https://docs.docker.com/get-docker/) 和 Docker Compose

```bash
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai
docker compose -f docker-compose.local.yml up -d
```

浏览器打开 [https://localhost:8443](https://localhost:8443)。默认管理员：`admin@example.com` / `admin`

<details>
<summary>最简 Docker Compose 示例</summary>

```yaml
name: core-ai

services:
  mongo:
    image: mongo:7
    command: ["mongod", "--replSet", "rs0", "--bind_ip_all"]
    ports:
      - "27017:27017"
    volumes:
      - mongo-data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand({ ping: 1 }).ok"]
      interval: 10s
      timeout: 5s
      retries: 10

  mongo-init:
    image: mongo:7
    depends_on:
      mongo:
        condition: service_healthy
    restart: "no"
    entrypoint:
      - bash
      - -c
      - |
        mongosh --host mongo:27017 --quiet --eval '
          try {
            rs.status();
          } catch (e) {
            rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "mongo:27017" }] });
          }
        '

  core-ai-server:
    image: chancetop/core-ai-server:latest
    depends_on:
      mongo-init:
        condition: service_completed_successfully
    ports:
      - "8080:8080"
    environment:
      SYS_HTTP_LISTEN: "8080"
      SYS_MONGO_URI: "mongodb://mongo:27017/core-ai?replicaSet=rs0"
      SYS_ADMIN_EMAIL: "admin@example.com"
      SYS_ADMIN_PASSWORD: "admin"
      SYS_ADMIN_NAME: "Admin"
      LLM_MODEL: "gpt-4o"

volumes:
  mongo-data:
```

</details>

> 💡 完整版 `docker-compose.local.yml` 包含 Redis、沙箱和 SSL。详见 [core-ai-server/README.md](core-ai-server/README.md)。

### ✨ 功能特性

- **💬 终端助手** — 在终端中进行 AI 对话，支持文件操作、网页搜索、代码执行、子代理编排。独立使用，只需配置任意 LLM 提供商的 API Key
- **🌐 代理服务器** — 自部署 Web 管理界面，支持代理管理、会话历史、多用户团队协作
- **🧩 工具 & MCP** — 内置工具（读、写、搜索、抓取、grep、glob）加 MCP 协议支持自定义工具服务器
- **👥 多代理协作** — 通过 A2A 协议将任务委派给子代理和远程代理
- **🧠 记忆 & 知识库** — 持久化会话记忆、Markdown 知识库、自动提取
- **🔌 多提供商** — 连接 OpenAI、DeepSeek、OpenRouter、Azure、LiteLLM 或任何兼容 OpenAI 的 API
- **📋 任务管理** — 内置 Todo 追踪，支持计划/审查工作流
- **🎯 Skills 系统** — 模块化、可复用的领域知识包，渐进式披露

### 💡 应用场景

- **💻 编程助手** — 理解你的代码库，执行多步骤任务，管理 Todo
- **🤖 客户支持** — 带工具集成和知识库的上下文感知代理
- **📊 数据分析** — 查询数据、生成报告、可视化结果
- **🔄 工作流自动化** — 通过代理流水线自动化复杂业务流程
- **📚 知识管理** — 构建和查询企业知识库
- **👥 团队协作** — 多用户通过服务器共享代理、会话和工具

### 🔧 开发者

Core-AI 同时也是用于构建自定义 AI 代理应用的 Java SDK。

#### Java SDK

**Maven：**
```xml
<dependency>
    <groupId>com.chancetop</groupId>
    <artifactId>core-ai</artifactId>
    <version>1.0.24</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'com.chancetop:core-ai:1.0.24'
```

构建配置中添加仓库：
```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://neowu.github.io/maven-repo/")
        content { includeGroupByRegex("core\\.framework.*") }
    }
    maven {
        url = uri("https://chancetop-com.github.io/maven-repo/")
        content { includeGroupByRegex("com\\.chancetop.*") }
    }
}
```

**基础用法：**
```java
AzureOpenAIConfig config = AzureOpenAIConfig.builder()
    .endpoint("https://your-resource.openai.azure.com")
    .apiKey("your-api-key")
    .deploymentName("gpt-4")
    .build();

LLMProvider llmProvider = new AzureOpenAILLMProvider(config);

Agent agent = Agent.builder()
    .name("assistant")
    .description("一个有用的 AI 助手")
    .llmProvider(llmProvider)
    .systemPrompt("你是一个友好且专业的 AI 助手。")
    .build();

AgentOutput output = agent.execute("今天我能为您做什么？");
System.out.println(output.getOutput());
```

#### 架构设计

```
┌─────────────────────────────────────┐
│        应用层（Applications）        │
├─────────────────────────────────────┤
│      编排层（Orchestration）         │
│         Flow / Planning             │
├─────────────────────────────────────┤
│         代理层（Agents）             │
│   Agent / Memory / Reflection       │
├─────────────────────────────────────┤
│       能力层（Capabilities）         │
│  Tools / RAG / VectorStore / MCP    │
├─────────────────────────────────────┤
│      提供商层（Providers）           │
│     LLM / Embeddings / Reranker     │
└─────────────────────────────────────┘
```

#### 从源码构建

**环境要求：** Java 21+、Gradle 8.0+

```bash
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai

# 构建 Java 项目
./gradlew build

# 构建 CLI native 二进制（需要 GraalVM JDK 21+）
./gradlew :core-ai-cli:nativeCompile

# 运行示例服务
./gradlew :example-service:run
```

### 📖 文档

- [概述](./docs/cn/overview.md) — 核心概念与架构
- [快速开始](./docs/cn/quickstart.md) — 10 分钟快速上手
- [构建智能代理](./docs/cn/tutorial-basic-agent.md) — 创建具有记忆和反思能力的代理
- [记忆系统](./docs/cn/tutorial-memory.md) — 记忆与向量语义搜索
- [工具调用](./docs/cn/tutorial-tool-calling.md) — 使用自定义工具扩展代理
- [RAG 集成](./docs/cn/tutorial-rag.md) — 实现检索增强生成
- [Skills 系统](./docs/cn/tutorial-skills.md) — 模块化领域知识包
- [流程编排](./docs/cn/tutorial-flow.md) — 构建复杂工作流

---

<div align="center">

### 🌐 相关链接

[GitHub](https://github.com/chancetop-com/core-ai) • [文档](./docs/) • [问题反馈](https://github.com/chancetop-com/core-ai/issues) • [讨论](https://github.com/chancetop-com/core-ai/discussions)

</div>
