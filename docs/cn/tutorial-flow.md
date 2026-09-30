# 框架 Flow 与 Server 工作流

<span class="legacy-anchor" id="教程-流程编排-flow-orchestration"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="流程编排概述"></span>
<span class="legacy-anchor" id="什么是-flow"></span>
<span class="legacy-anchor" id="flow-架构"></span>
<span class="legacy-anchor" id="flow-执行引擎原理"></span>
<span class="legacy-anchor" id="有向图执行模型"></span>
<span class="legacy-anchor" id="边的类型与作用"></span>
<span class="legacy-anchor" id="条件路由机制"></span>
<span class="legacy-anchor" id="创建基本流程"></span>
<span class="legacy-anchor" id="_1-简单线性流程"></span>
<span class="legacy-anchor" id="_2-复杂流程构建"></span>
<span class="legacy-anchor" id="节点类型"></span>
<span class="legacy-anchor" id="_1-agent-节点"></span>
<span class="legacy-anchor" id="_2-tool-节点"></span>
<span class="legacy-anchor" id="_3-llm-节点"></span>
<span class="legacy-anchor" id="_4-rag-节点"></span>
<span class="legacy-anchor" id="_5-operator-节点"></span>
<span class="legacy-anchor" id="边和连接"></span>
<span class="legacy-anchor" id="_1-基本连接"></span>
<span class="legacy-anchor" id="_2-条件边"></span>
<span class="legacy-anchor" id="_3-设置边"></span>
<span class="legacy-anchor" id="条件路由"></span>
<span class="legacy-anchor" id="_1-简单条件路由"></span>
<span class="legacy-anchor" id="_2-复杂条件路由"></span>
<span class="legacy-anchor" id="_3-动态路由"></span>
<span class="legacy-anchor" id="状态管理"></span>
<span class="legacy-anchor" id="_1-流程状态"></span>
<span class="legacy-anchor" id="_2-上下文管理"></span>
<span class="legacy-anchor" id="_3-检查点和恢复"></span>
<span class="legacy-anchor" id="并行和异步执行"></span>
<span class="legacy-anchor" id="_1-并行分支"></span>
<span class="legacy-anchor" id="_2-异步节点"></span>
<span class="legacy-anchor" id="实战案例"></span>
<span class="legacy-anchor" id="案例1-贷款审批流程"></span>
<span class="legacy-anchor" id="案例2-内容生成管道"></span>
<span class="legacy-anchor" id="案例3-数据-etl-流程"></span>
<span class="legacy-anchor" id="高级特性"></span>
<span class="legacy-anchor" id="_1-子流程"></span>
<span class="legacy-anchor" id="_2-事件驱动"></span>
<span class="legacy-anchor" id="监控和可观测性"></span>
<span class="legacy-anchor" id="_1-流程监控"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="总结"></span>
<span class="legacy-anchor" id="相关资源"></span>

先区分 Java 框架的 `ai.core.flow.Flow` 和 Server 的 Web 工作流。两者的定义、节点模型、发布与运行生命周期不同，不能将 UI 导出的 JSON 直接当成 Java Flow 参数。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 节点、边与构造

Java Flow 使用 `FlowNode`、`FlowEdge`、`ConnectionEdge` 等类型。builder 接收 `.nodes(...)` 和 `.edges(...)`，也可配置 providers、vector stores、tracer 和监听器。节点的 `check` / `init` 契约由具体实现决定。

不要只根据节点类名判断功能完成度。例如当前 `EmptyFlowNode.execute(...)` 返回 null；需要先查看目标节点实现和输入约束。

## 检查与运行

下面假设调用者已经构造 `flow`，提供存在的起点 ID、输入和上下文：

```java
flow.validate();
String result = flow.run(startNodeId, input, context);
```
当前入口是 `run(startNodeId, input, context)` 或 variables overload，返回 String；不是旧教程的 `FlowOutput` / `flow.execute(input)`。校验通过不代表外部模型和后端已连通。

## 最小验证顺序

1. 确认起点、目标节点 ID、连接边和 settings 边。
2. 单独检查节点输入与结果。
3. 执行最小路径，观察当前节点和输出监听器。
4. 再添加分支、持久化和需要用户输入的节点。

本轮没有运行 Flow 或 Server 工作流。可恢复性、并行、错误传播和持久化应逐一按选定节点及源码测试，不根据设计提案推断已实现。

## Server 的操作路径

在 Web 编辑器先用最小 START → 一个节点 → END 测试数据流，检查 draft 和 published 生命周期。界面示例与步骤见[工具和工作流](/cn/manual/#_5-工具-技能-数据集与工作流)。

[Flow 源码](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/flow/Flow.java) · [架构边界](tutorial-architecture.md) · [Server 上手](server.md)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-flow.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
