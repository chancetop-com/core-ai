# 基础 Agent

<span class="legacy-anchor" id="教程-构建智能代理-agent"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="代理基础概念"></span>
<span class="legacy-anchor" id="什么是代理-agent"></span>
<span class="legacy-anchor" id="代理的核心组件"></span>
<span class="legacy-anchor" id="核心架构设计"></span>
<span class="legacy-anchor" id="代理执行流程"></span>
<span class="legacy-anchor" id="_1-最简单的代理"></span>
<span class="legacy-anchor" id="_2-配置丰富的代理"></span>
<span class="legacy-anchor" id="_3-流式输出代理"></span>
<span class="legacy-anchor" id="_1-静态系统提示"></span>
<span class="legacy-anchor" id="_2-动态模板-mustache"></span>
<span class="legacy-anchor" id="_3-用户查询的提示模板"></span>
<span class="legacy-anchor" id="_4-langfuse-提示集成"></span>
<span class="legacy-anchor" id="_1-基本反思"></span>
<span class="legacy-anchor" id="_2-自定义反思配置"></span>
<span class="legacy-anchor" id="_3-带监听器的反思"></span>
<span class="legacy-anchor" id="_4-简化的反思配置"></span>
<span class="legacy-anchor" id="状态转换机制原理"></span>
<span class="legacy-anchor" id="_1-代理状态"></span>
<span class="legacy-anchor" id="_2-持久化和恢复"></span>
<span class="legacy-anchor" id="_3-重置代理状态"></span>
<span class="legacy-anchor" id="_4-token-使用跟踪"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="_1-单一职责原则"></span>
<span class="legacy-anchor" id="_2-错误处理"></span>
<span class="legacy-anchor" id="_3-正确使用上下文"></span>
<span class="legacy-anchor" id="_4-可观测性与追踪"></span>
<span class="legacy-anchor" id="_5-记忆集成"></span>
<span class="legacy-anchor" id="总结"></span>

先阅读[框架上手](framework.md)，确认 Java 25 和依赖。第一个 Agent 建议使用 Mock provider，获得可核对的输入、返回值和状态。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 创建基本代理

`Agent.builder()` 返回 `AgentBuilder`。模型名称由自己的 provider 决定，`run` 返回 `String`。用户和会话上下文由调用方创建：

```java
var agent = Agent.builder()
    .name("guide-agent")
    .llmProvider(provider)
    .model(model)
    .systemPrompt("Answer clearly.")
    .compression(false)
    .maxTurn(3)
    .build();
var context = ExecutionContext.builder()
    .userId("demo-user")
    .sessionId("demo-session")
    .build();
String result = agent.run("Hello", context);
```
检查 `agent.getNodeStatus()`，完成应为 `COMPLETED`。需要输入或异步任务时可能暂停，字符串输出本身不能证明整个流程已完成。

## 系统提示和模板

`.systemPrompt(...)` 设置系统提示，`.model(...)` 与 `.temperature(...)` 配置模型请求，`.maxTurn(...)` 限制 Agent 轮次。固定模型、简短任务和少量工具便于调试；轮次不是进程级超时。

变量可通过 `ExecutionContext` 或 `run(query, variables)` 提供，模板内容及变量由应用管理。不要把系统提示当成用户隔离或外部授权。

## 流式响应

正确包名是 `ai.core.llm.streaming.StreamingCallback`，输出回调是 `onChunk(String)`，完成回调为无参数 `onComplete()`：

```java
var agent = Agent.builder()
    .name("streaming-agent")
    .llmProvider(provider)
    .model(model)
    .streaming(true)
    .streamingCallback(new StreamingCallback() {
        @Override public void onChunk(String chunk) {
            System.out.print(chunk);
        }
        @Override public void onComplete() {
            System.out.println();
        }
        @Override public void onError(Throwable error) {
            System.err.println(error.getMessage());
        }
    }).build();
```
随后使用 `agent.run(query, context)`。回调避免阻塞和泄露敏感内容。这个片段只做编译核对，未运行真实 Streaming。

## 反思机制

反思通过 `reflectionConfig(...)`、相关评估设置及 provider 实现配合，可能增加模型调用。首次示例先保持最小配置，再按 [AgentBuilder 源码](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/AgentBuilder.java)核对需要的选项。

## 状态管理

应用应管理 `userId`、`sessionId`、持久化与恢复边界。可见状态包括 `INITED`、`RUNNING`、`WAITING_FOR_USER_INPUT`、`WAITING_FOR_ASYNC_TASK`、`COMPLETED` 和 `FAILED`。不要将等待状态当成执行失败后立即重复提交。

## 下一步

[函数工具](tutorial-tool-calling.md) · [记忆](tutorial-memory.md) · [压缩](tutorial-compression.md) · [API 参考](/cn/api)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-basic-agent.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
