# Core-AI Documentation

<div align="center">

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://www.oracle.com/java/)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](..)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](../LICENSE)

### 📚 Select Your Language / 选择语言

</div>

---

## 🌍 English Documentation

Welcome to Core-AI documentation! Core-AI is a powerful Java framework for building AI agents and multi-agent applications.

### 📖 Documentation Structure

- **[Overview](../en/overview.md)** - Understand Core-AI's core concepts and architecture
- **[Quick Start](../en/quickstart.md)** - Get started in 10 minutes
- **[Architecture & Internals](../en/tutorial-architecture.md)** - Framework core mechanisms deep dive
- **[Tutorials](../en/tutorials.md)** - In-depth learning guides
  - [Building AI Agents](../en/tutorial-basic-agent.md)
  - [Memory Systems](../en/tutorial-memory.md)
  - [Compression Mechanism](../en/tutorial-compression.md)
  - [RAG Integration](../en/tutorial-rag.md)
  - [Tool Calling](../en/tutorial-tool-calling.md)
  - [Skills System](../en/tutorial-skills.md)
  - [Flow Orchestration](../en/tutorial-flow.md)

### 🚀 Quick Navigation

| Topic | Description | Link |
|-------|-------------|------|
| **Getting Started** | Learn the basics and set up your environment | [Quick Start](../en/quickstart.md) |
| **Architecture & Internals** | Deep dive into framework core mechanisms | [Architecture Tutorial](../en/tutorial-architecture.md) |
| **Agent Development** | Build intelligent agents with memory and tools | [Agent Tutorial](../en/tutorial-basic-agent.md) |
| **Memory Systems** | Short-term and long-term memory management | [Memory Tutorial](../en/tutorial-memory.md) |
| **Compression** | Context compression and token management | [Compression Tutorial](../en/tutorial-compression.md) |
| **RAG & Vector Search** | Implement retrieval-augmented generation | [RAG Tutorial](../en/tutorial-rag.md) |
| **Tool Integration** | Extend agents with custom tools | [Tool Tutorial](../en/tutorial-tool-calling.md) |
| **Skills System** | Modular domain knowledge packages | [Skills Tutorial](../en/tutorial-skills.md) |
| **Workflow Design** | Build complex execution flows | [Flow Tutorial](../en/tutorial-flow.md) |

---

## 🇨🇳 中文文档

欢迎使用 Core-AI 文档！Core-AI 是一个用于构建 AI 代理和多代理应用程序的强大 Java 框架。

### 📖 文档结构

- **[概述](overview.md)** - 了解 Core-AI 的核心概念和架构
- **[快速开始](quickstart.md)** - 10分钟快速上手
- **[架构与原理](tutorial-architecture.md)** - 框架核心机制深度解析
- **[教程系列](tutorials.md)** - 深入学习指南
  - [构建智能代理](tutorial-basic-agent.md)
  - [记忆系统](tutorial-memory.md)
  - [压缩机制](tutorial-compression.md)
  - [RAG 集成](tutorial-rag.md)
  - [工具调用](tutorial-tool-calling.md)
  - [Skills 系统](tutorial-skills.md)
  - [流程编排](tutorial-flow.md)
- **设计文档**
  - [Server 架构设计](design-server-architecture.md) - 设计原则、领域模型、执行流程、追踪
  - [Agent 平台架构](../en/design-agent-platform-architecture.md) - 企业 Agent 平台设计
  - [客户端/服务端架构](../en/design-client-server-architecture.md) - C/S 架构、会话模型、SSE 事件
  - [A2A 框架与 CLI/Server 集成](design-cli-server-a2a.md) - 框架级 A2A、server-only Tool/MCP、CLI/server 集成设计

### 🚀 快速导航

| 主题 | 描述 | 链接 |
|------|------|------|
| **入门指南** | 学习基础知识并设置环境 | [快速开始](quickstart.md) |
| **架构与原理** | 深入理解框架核心机制 | [架构文档](tutorial-architecture.md) |
| **代理开发** | 构建具有记忆和工具的智能代理 | [代理教程](tutorial-basic-agent.md) |
| **记忆系统** | 短期和长期记忆管理 | [记忆教程](tutorial-memory.md) |
| **压缩机制** | 上下文压缩与管理 | [压缩教程](tutorial-compression.md) |
| **RAG 与向量搜索** | 实现检索增强生成 | [RAG 教程](tutorial-rag.md) |
| **工具集成** | 使用自定义工具扩展代理 | [工具教程](tutorial-tool-calling.md) |
| **Skills 系统** | 模块化领域知识包 | [Skills 教程](tutorial-skills.md) |
| **工作流设计** | 构建复杂的执行流程 | [流程教程](tutorial-flow.md) |

---

## 📋 Documentation Index

### Core Concepts / 核心概念

| English | 中文 | Description |
|---------|------|-------------|
| [Overview](../en/overview.md) | [概述](overview.md) | Framework architecture and features |
| [Quick Start](../en/quickstart.md) | [快速开始](quickstart.md) | Getting started guide |

### Design / 设计文档

| English | 中文 | Focus Area |
|---------|------|------------|
| [Server Architecture](../en/design-server-architecture.md) | [Server 架构设计](design-server-architecture.md) | Design principles, domain model, execution flows, tracing |
| [Agent Platform](../en/design-agent-platform-architecture.md) | — | Enterprise agent platform design |
| [Client/Server](../en/design-client-server-architecture.md) | — | C/S architecture, session model, SSE events |
| — | [A2A 框架与 CLI/Server 集成](design-cli-server-a2a.md) | Framework-level A2A design, server-only tools/MCP, CLI/server integration |

### Deep Dive / 深入解析

| English | 中文 | Focus Area |
|---------|------|------------|
| [Architecture](../en/tutorial-architecture.md) | [架构与原理](tutorial-architecture.md) | Core mechanisms, lifecycle, execution engine |

### Tutorials / 教程

| English | 中文 | Focus Area |
|---------|------|------------|
| [Basic Agent](../en/tutorial-basic-agent.md) | [基础代理](tutorial-basic-agent.md) | Agent creation, memory, reflection |
| [Memory Systems](../en/tutorial-memory.md) | [记忆系统](tutorial-memory.md) | Short-term, long-term, unified memory |
| [Compression](../en/tutorial-compression.md) | [压缩机制](tutorial-compression.md) | Context compression, token management |
| [RAG Integration](../en/tutorial-rag.md) | [RAG 集成](tutorial-rag.md) | Vector stores, retrieval, reranking |
| [Tool Calling](../en/tutorial-tool-calling.md) | [工具调用](tutorial-tool-calling.md) | Custom tools, MCP protocol |
| [Skills System](../en/tutorial-skills.md) | [Skills 系统](tutorial-skills.md) | Domain knowledge packages, progressive disclosure |
| [Flow Orchestration](../en/tutorial-flow.md) | [流程编排](tutorial-flow.md) | Workflows, routing, state management |

### API Reference / API 参考

| Component | Documentation | Examples |
|-----------|---------------|----------|
| Agent API | [JavaDoc](../javadoc/agent) | [Examples](../../example-service) |
| Flow API | [JavaDoc](../javadoc/flow) | [Examples](../../example-service) |
| Tool API | [JavaDoc](../javadoc/tool) | [Examples](../../example-service) |

---

## 🔧 Additional Resources / 其他资源

### Community / 社区

- **GitHub**: [chancetop-com/core-ai](https://github.com/chancetop-com/core-ai)
- **Issues**: [Report bugs or request features](https://github.com/chancetop-com/core-ai/issues)
- **Discussions**: [Join the conversation](https://github.com/chancetop-com/core-ai/discussions)

### Examples / 示例

- **Example Service**: Complete reference implementation in [`example-service/`](../../example-service)
- **Code Samples**: Quick examples in each tutorial
- **Best Practices**: Production-ready patterns and configurations

### Version Information / 版本信息

- **Current Version**: 1.1.84
- **Java Requirement**: 21+
- **License**: Apache License 2.0

---

<div align="center">

### 💬 Need Help? / 需要帮助？

If you have questions or need assistance, please:
如果您有问题或需要帮助，请：

[Open an Issue](https://github.com/chancetop-com/core-ai/issues) | [Start a Discussion](https://github.com/chancetop-com/core-ai/discussions)

</div>
