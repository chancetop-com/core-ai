# 记忆与用户上下文

<span class="legacy-anchor" id="教程-记忆系统"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="概述"></span>
<span class="legacy-anchor" id="记忆系统"></span>
<span class="legacy-anchor" id="架构"></span>
<span class="legacy-anchor" id="核心概念"></span>
<span class="legacy-anchor" id="记忆属性"></span>
<span class="legacy-anchor" id="设置记忆"></span>
<span class="legacy-anchor" id="记忆提取"></span>
<span class="legacy-anchor" id="记忆召回"></span>
<span class="legacy-anchor" id="记忆检索原理"></span>
<span class="legacy-anchor" id="userid-传递机制"></span>
<span class="legacy-anchor" id="存储接口"></span>
<span class="legacy-anchor" id="自定义存储实现"></span>
<span class="legacy-anchor" id="与-agent-集成"></span>
<span class="legacy-anchor" id="executioncontext"></span>
<span class="legacy-anchor" id="使用-unifiedmemory-配置"></span>
<span class="legacy-anchor" id="完整示例"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="_1-正确使用-executioncontext"></span>
<span class="legacy-anchor" id="_2-生产环境存储设置"></span>
<span class="legacy-anchor" id="_3-提取时机"></span>
<span class="legacy-anchor" id="_4-服务层封装"></span>
<span class="legacy-anchor" id="总结"></span>

记忆用于跨轮次或会话召回用户相关信息；会话消息、CLI 会话文件和 MemoryStore 是不同概念。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 配置 Memory

当前入口为 `ai.core.memory.Memory`、`MemoryStore` 和 `MemoryConfig`。下面使用内存 store 演示配置，不提供跨进程持久化：

```java
var memory = Memory.builder()
    .llmProvider(provider)
    .memoryStore(new InMemoryStore())
    .build();
var memoryConfig = MemoryConfig.builder()
    .autoRecall(true)
    .maxRecallRecords(5)
    .build();
var agent = Agent.builder()
    .name("memory-agent")
    .llmProvider(provider)
    .model(model)
    .unifiedMemory(memory, memoryConfig)
    .build();
```
`Memory` 的检索和提取可能需要 embedding 或模型调用。本例只编译，不执行这些请求。`maxRecallRecords` 实现限制在 1 到 20；默认配置启用自动召回、默认最多 5 条。

## 用户隔离与运行

使用带正确 `userId` 和 `sessionId` 的 `ExecutionContext` 运行 Agent。Store 的接口按用户读写，应用仍要确认用户来自已认证身份。切换用户时不要复用错误上下文或共享未隔离的召回结果。

## 验证步骤

1. 在测试 store 中保存明确的 fixture，并检查同一用户可读。
2. 用另一用户检查隔离。
3. 单独检查检索，再开启自动召回和提取。
4. 持久化实现检查重启、删除、访问控制和敏感内容处理。

本次未执行真实记忆提取、embedding 或持久化后端。接入细节以[Memory 源码](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/memory)为准。

[基础 Agent](tutorial-basic-agent.md) · [压缩](tutorial-compression.md) · [RAG](tutorial-rag.md)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-memory.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
