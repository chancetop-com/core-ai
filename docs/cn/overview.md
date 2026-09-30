# Core AI 概览

<span class="legacy-anchor" id="core-ai-概述"></span>
<span class="legacy-anchor" id="什么是-core-ai"></span>
<span class="legacy-anchor" id="核心特性"></span>
<span class="legacy-anchor" id="_1-统一的-llm-抽象"></span>
<span class="legacy-anchor" id="_2-智能代理-agent"></span>
<span class="legacy-anchor" id="_3-工具调用-tool-calling"></span>
<span class="legacy-anchor" id="_4-rag-检索增强生成"></span>
<span class="legacy-anchor" id="_5-流程编排-flow"></span>
<span class="legacy-anchor" id="_6-可观测性"></span>
<span class="legacy-anchor" id="架构设计"></span>
<span class="legacy-anchor" id="分层架构"></span>
<span class="legacy-anchor" id="模块化设计"></span>
<span class="legacy-anchor" id="设计原则"></span>
<span class="legacy-anchor" id="_1-构建者模式"></span>
<span class="legacy-anchor" id="_2-异步优先"></span>
<span class="legacy-anchor" id="_3-可扩展性"></span>
<span class="legacy-anchor" id="_4-生产就绪"></span>
<span class="legacy-anchor" id="使用场景"></span>
<span class="legacy-anchor" id="技术栈"></span>
<span class="legacy-anchor" id="开源生态"></span>
<span class="legacy-anchor" id="下一步"></span>

Core AI 由团队服务、命令行和 Java 框架组成。三部分在同一仓库中共享 `core-ai` 与 `core-ai-api`，但入口和运行前提不同。

## 选择入口

| 部分 | 解决什么问题 | 从哪里开始 |
| --- | --- | --- |
| Server | 管理 Agent、工具、技能和数据集，提供 Web、REST、SSE | [Server 上手](server.md) |
| CLI | 本地 Agent、编辑器 ACP、Server Hub 资源操作 | [CLI 上手](cli.md) |
| 框架 | 在 Java 应用内构建 Agent、工具、上下文和 Flow | [Java 框架](framework.md) |

CLI 可以独立配置模型，也可以登录 Server 使用模型代理。Hub 子命令负责发现与调用 Server 资源；登录和资源授权仍由服务决定。框架 Flow 与 Server Web 工作流是不同层次，配置不能直接互换。

## 环境与版本

当前文档以 `e1afa9fa` 源码为基线：CLI 2.0.21，框架 1.4.0-SNAPSHOT，公共 API 1.3.0-SNAPSHOT。Java 源码需要 **25**，Wrapper 为 **Gradle 9.3.0**；原生 CLI 发布包自带运行时。Server 还需要 MongoDB 和 Redis，其他服务按功能准备。

版本常量不代表 SNAPSHOT 已发布到 Maven。优先使用同 checkout 的 project 依赖，或团队确认的实际制品。其他 release 先检查对应源码、资产架构及 `--help`。

## 能力与前提

- Agent：系统提示、模型、工具和执行状态；从最小问答逐步增加能力。
- 工具：函数注解、MCP 和权限审批；具体副作用由工具实现决定。
- 上下文：压缩与记忆有独立配置，记忆不是会话目录的别名。
- 检索：RAG 需要文档、embedding、VectorStore 及其运行依赖。
- 编排与观测：框架 Flow、Server 工作流和 Traces 分别查看实际实现与配置。

## 推荐阅读路径

[快速开始](quickstart.md) → [离线 Agent](framework.md#离线-agent) → [基础 Agent](tutorial-basic-agent.md) → [函数工具](tutorial-tool-calling.md)。按任务需要继续 [Memory](tutorial-memory.md)、[RAG](tutorial-rag.md)、[Skills](tutorial-skills.md) 或 [Flow](tutorial-flow.md)。

[教程索引](tutorials.md)提供每项能力的前提与验证范围。[API 参考](/cn/api)指向实际类型和源码；设计记录用于了解背景，不能替代当前契约。

## 验证范围

Mac 的源码 CLI help/version、Mock Agent 和 FakeSession 已实测。Server 启动、真实模型请求、Windows 实机、发布包安装与升级未验证。文档中的预期结果不等同于执行记录，详见[完整手册](manual/)。
