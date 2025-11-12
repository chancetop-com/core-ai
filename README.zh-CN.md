# Core-AI Framework

<div align="center">

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](./docs/)
[![GitHub Stars](https://img.shields.io/github/stars/chancetop-com/core-ai?style=social)](https://github.com/chancetop-com/core-ai)

[English](README.md) | [中文](README.zh-CN.md)

</div>

---

## 🌟 Core-AI：构建智能 AI 代理应用

Core-AI 是一个强大的 Java 框架，专门用于构建 AI 代理和多代理应用程序。它提供了 LLM 提供商、代理、工具、RAG（检索增强生成）、向量存储和代理流程编排的全面抽象。

### ✨ 核心特性

- **🤖 智能代理** - 构建具有记忆、反思和工具调用能力的自主代理
- **👥 多代理系统** - 协调多个专业代理协同工作
- **🔧 工具集成** - 支持 JSON Schema 和 MCP 协议的广泛工具系统
- **📚 RAG 支持** - 内置 RAG，集成向量存储（Milvus、HNSWLib）
- **🔄 流程编排** - 可视化工作流设计，支持条件路由和并行执行
- **🔍 可观测性** - OpenTelemetry 追踪，兼容 Langfuse、Jaeger 等
- **☁️ LLM 提供商** - 支持 Azure OpenAI、Azure AI Inference 等

### 🚀 快速开始

#### 安装

**Maven:**
```xml
<dependency>
    <groupId>com.chancetop</groupId>
    <artifactId>core-ai</artifactId>
    <version>1.1.84</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'com.chancetop:core-ai:1.1.84'
```

#### Maven 仓库配置

在构建配置中添加以下仓库：

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://neowu.github.io/maven-repo/")
        content {
            includeGroupByRegex("core\\.framework.*")
        }
    }
    maven {
        url = uri("https://chancetop-com.github.io/maven-repo/")
        content {
            includeGroupByRegex("com\\.chancetop.*")
        }
    }
}
```

#### 基础示例

```java
// 初始化 LLM 提供商
AzureOpenAIConfig config = AzureOpenAIConfig.builder()
    .endpoint("https://your-resource.openai.azure.com")
    .apiKey("your-api-key")
    .deploymentName("gpt-4")
    .build();

LLMProvider llmProvider = new AzureOpenAILLMProvider(config);

// 创建 AI 代理
Agent agent = Agent.builder()
    .name("assistant")
    .description("一个有用的 AI 助手")
    .llmProvider(llmProvider)
    .systemPrompt("你是一个友好且专业的 AI 助手。")
    .build();

// 执行查询
AgentOutput output = agent.execute("今天我能为您做什么？");
System.out.println(output.getOutput());
```

### 📖 文档

**入门指南**
- [概述](./docs/overview.md) - 核心概念和架构
- [快速开始](./docs/quickstart.md) - 10分钟快速上手

**详细教程**
- [构建智能代理](./docs/tutorial-basic-agent.md) - 创建具有记忆和反思能力的智能代理
- [多代理系统](./docs/tutorial-multi-agent.md) - 使用切换策略协调多个代理
- [RAG 集成](./docs/tutorial-rag.md) - 实现检索增强生成
- [工具调用](./docs/tutorial-tool-calling.md) - 使用自定义工具扩展代理
- [流程编排](./docs/tutorial-flow.md) - 构建复杂工作流

### 🏗️ 架构设计

```
┌─────────────────────────────────────┐
│        应用层（Applications）        │
├─────────────────────────────────────┤
│      编排层（Orchestration）         │
│    Flow / AgentGroup / Planning     │
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

### 💡 应用场景

- **🤖 客户支持** - 构建具有工具集成的上下文感知支持代理
- **💻 代码助手** - 创建理解和执行任务的开发助手
- **📊 数据分析** - 构建数据查询和报告生成代理
- **🔄 工作流自动化** - 自动化复杂业务流程
- **📚 知识管理** - 企业知识库和问答系统
- **👥 协作 AI** - 多个专业代理协同工作

### 🛠️ 开发环境

**环境要求：**
- Java 21+
- Gradle 8.0+
- LLM API 密钥（Azure OpenAI 或兼容提供商）

**从源码构建：**
```bash
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai
./gradlew build
```

**运行示例：**
```bash
./gradlew :example-service:run
```
---

<div align="center">

### 🌐 相关链接

[GitHub](https://github.com/chancetop-com/core-ai) • [文档](./docs/) • [问题反馈](https://github.com/chancetop-com/core-ai/issues) • [讨论](https://github.com/chancetop-com/core-ai/discussions)


</div>