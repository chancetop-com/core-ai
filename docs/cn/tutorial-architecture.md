# 架构与运行边界

<span class="legacy-anchor" id="core-ai-架构与原理深度解析"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="整体架构"></span>
<span class="legacy-anchor" id="分层架构设计"></span>
<span class="legacy-anchor" id="核心设计原则"></span>
<span class="legacy-anchor" id="agent-执行引擎"></span>
<span class="legacy-anchor" id="执行流程详解"></span>
<span class="legacy-anchor" id="状态转换机制"></span>
<span class="legacy-anchor" id="核心代码解析"></span>
<span class="legacy-anchor" id="生命周期系统"></span>
<span class="legacy-anchor" id="钩子点设计"></span>
<span class="legacy-anchor" id="abstractlifecycle-抽象类"></span>
<span class="legacy-anchor" id="内置生命周期实现"></span>
<span class="legacy-anchor" id="自定义生命周期示例"></span>
<span class="legacy-anchor" id="工具执行机制"></span>
<span class="legacy-anchor" id="json-schema-自动生成"></span>
<span class="legacy-anchor" id="schema-生成核心逻辑"></span>
<span class="legacy-anchor" id="工具执行流程"></span>
<span class="legacy-anchor" id="工具执行核心代码"></span>
<span class="legacy-anchor" id="消息处理流程"></span>
<span class="legacy-anchor" id="消息类型与结构"></span>
<span class="legacy-anchor" id="消息构建流程"></span>
<span class="legacy-anchor" id="flow-执行引擎"></span>
<span class="legacy-anchor" id="flow-图结构"></span>
<span class="legacy-anchor" id="flow-执行流程"></span>
<span class="legacy-anchor" id="边的处理逻辑"></span>
<span class="legacy-anchor" id="条件路由"></span>
<span class="legacy-anchor" id="追踪与遥测"></span>
<span class="legacy-anchor" id="opentelemetry-集成"></span>
<span class="legacy-anchor" id="agenttracer-核心方法"></span>
<span class="legacy-anchor" id="追踪属性规范"></span>
<span class="legacy-anchor" id="总结"></span>

阅读本页前，先跑通[离线 Agent](framework.md#离线-agent)。此处描述当前入口和模块关系，不将历史设计方案当作已实现功能。

## 三部分与公共 API

<div class="architecture-map">
<div><strong>调用入口</strong><a href="/core-ai/cn/server">Server / Web</a><a href="/core-ai/cn/cli">CLI</a><span>Java 应用</span></div>
<div><strong>Agent 执行</strong><span>core-ai 框架</span><span>Provider · Tools · Context</span></div>
<div><strong>共享类型</strong><span>core-ai-api</span><a href="/core-ai/cn/api">接口与 DTO</a></div>
</div>

Server 负责共享资源、身份与会话服务。CLI 管理本地交互和 Hub 命令。框架负责 Agent、provider、工具及上下文。公共 API 模块还包含服务 DTO，并不是“保证 Java 17 兼容”的独立承诺；当前源码构建要求 Java 25。

## Agent 运行入口

应用调用 `Node.run(...)`，传入查询和可选上下文。Agent builder 配置模型、工具和生命周期选项，执行可能完成、失败或等待输入/异步任务。用 `NodeStatus` 判断状态，勿把内部 `execute` 方法当公共调用入口。

## 工具与资源身份

本地函数工具、stdio MCP 和 Server Hub 是不同入口。Server 资源权限不能从 prompt、工具名称或本地配置推导。Python SDK 在本机通过 CLI，在 sandbox 环境通过会话 Hub，身份边界随运行环境变化。

## 上下文与编排

Memory、Compression、RAG 分别解决召回、上下文长度和文档检索。Java Flow 与 Server 工作流的定义不可互换；持久化和恢复需要明确实现与生命周期。

## 源码定位

| 主题 | 入口 |
| --- | --- |
| 公共运行方法 | [Node.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/Node.java) |
| Agent 配置 | [AgentBuilder.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/AgentBuilder.java) |
| CLI 命令 | [Main.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai-cli/src/main/java/Main.java) |
| Server 服务 | [ServerApp.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai-server/src/main/java/ai/core/server/ServerApp.java) |

[API 参考](/cn/api) · [基础 Agent](tutorial-basic-agent.md) · [Flow](tutorial-flow.md) · [历史 Server 设计](design-server-architecture.md)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-architecture.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
