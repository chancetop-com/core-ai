# Core-AI 概述

## 什么是 Core-AI？

Core-AI 是一个强大的 Java 框架，专门用于构建智能代理（AI Agent）和多代理应用程序。它提供了一套完整的抽象层和工具，让开发者能够轻松集成各种大语言模型（LLM）提供商，构建复杂的 AI 驱动应用。

## 核心特性

### 1. 统一的 LLM 抽象
- **多提供商支持**：无缝集成 Azure OpenAI、Azure AI Inference 等多个 LLM 提供商
- **标准化接口**：统一的 API 接口，轻松切换不同的模型提供商
- **流式响应**：支持实时流式输出，提升用户体验

### 2. 智能代理（Agent）
- **自主决策**：代理可以根据任务自主选择工具和执行策略
- **系统提示**：支持自定义系统提示和 Mustache 模板引擎
- **记忆管理**：内置短期和长期记忆系统
- **反思能力**：代理可以反思执行结果并自我改进

### 3. 工具调用（Tool Calling）
- **函数调用**：支持 JSON Schema 定义的函数调用
- **MCP 协议**：完整支持 Model Context Protocol，实现标准化的工具集成
- **内置工具集**：提供常用工具的开箱即用实现

### 4. RAG（检索增强生成）
- **向量存储**：集成 Milvus 和 HNSWLib 等向量数据库
- **智能检索**：支持查询重写、相似度搜索和重排序
- **文档处理**：内置文档分割和处理工具

### 5. 多代理协作
- **代理组**：管理多个代理协同工作
- **切换策略**：DirectHandoff、AutoHandoff、HybridAutoDirectHandoff
- **规划策略**：支持复杂的多代理协调和任务规划

### 6. 流程编排（Flow）
- **可视化流程**：通过有向图编排复杂的执行流程
- **节点类型**：支持 Agent、LLM、RAG、Tool、Operator 等多种节点
- **条件路由**：支持基于条件的动态路由
- **状态管理**：完整的流程状态跟踪和持久化

### 7. 可观测性
- **分布式追踪**：内置 OpenTelemetry 支持
- **性能监控**：详细的执行时间和令牌使用统计
- **集成支持**：兼容 Langfuse、Jaeger 等观测平台

## 架构设计

### 分层架构

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

### 模块化设计

Core-AI 采用高度模块化的设计，主要模块包括：

- **core-ai-api**: API 定义和接口（Java 17 兼容）
- **core-ai**: 主框架库，包含所有核心功能
- **example-service**: 参考实现，展示框架使用方式
- **example-service-interface**: 服务接口定义

## 设计原则

### 1. 构建者模式
所有核心类都提供流畅的构建者 API，简化复杂对象的创建：

```java
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(provider)
    .systemPrompt("You are a helpful assistant")
    .enableRAG(ragConfig)
    .build();
```

### 2. 异步优先
框架设计充分考虑异步执行，支持流式响应和并发处理。

### 3. 可扩展性
通过接口和抽象类，轻松扩展新的 LLM 提供商、工具和向量存储。

### 4. 生产就绪
- 完整的错误处理和重试机制
- 状态持久化和恢复
- 分布式追踪和监控

## 使用场景

Core-AI 适用于构建各种 AI 驱动的应用：

1. **智能客服系统**：构建能够理解上下文、调用工具的客服代理
2. **代码助手**：创建能够理解代码、执行任务的开发助手
3. **数据分析平台**：构建能够查询数据、生成报告的分析代理
4. **工作流自动化**：通过流程编排实现复杂的业务流程自动化
5. **知识管理系统**：利用 RAG 构建企业知识库和问答系统
6. **多代理协作系统**：构建多个专业代理协同完成复杂任务

## 技术栈

- **Java 21**：利用最新的 Java 特性
- **Gradle**：使用 Kotlin DSL 的构建系统
- **core-ng framework**：基础应用框架
- **Jackson**：JSON 处理
- **Mustache**：模板引擎
- **OpenTelemetry**：分布式追踪

## 开源生态

Core-AI 积极拥抱开源生态，与以下项目良好集成：

- **Milvus**：开源向量数据库
- **OpenTelemetry**：云原生可观测性框架
- **Model Context Protocol**：标准化的模型上下文协议
- **Langfuse**：LLM 应用的可观测性平台

## 下一步

- 查看 [快速开始指南](quickstart.md) 了解如何快速上手
- 阅读 [教程](tutorial.md) 深入学习各项功能
- 探索 [API 文档](api-reference.md) 了解详细接口
- 参考 [示例项目](examples.md) 获取实际应用案例