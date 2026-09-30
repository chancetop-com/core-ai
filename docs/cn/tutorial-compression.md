# 上下文压缩

<span class="legacy-anchor" id="教程-压缩机制-上下文管理"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="概述"></span>
<span class="legacy-anchor" id="核心特性"></span>
<span class="legacy-anchor" id="压缩触发时机"></span>
<span class="legacy-anchor" id="压缩原理"></span>
<span class="legacy-anchor" id="压缩流程"></span>
<span class="legacy-anchor" id="压缩后的消息结构"></span>
<span class="legacy-anchor" id="配置"></span>
<span class="legacy-anchor" id="基本用法"></span>
<span class="legacy-anchor" id="自定义配置"></span>
<span class="legacy-anchor" id="配置参数"></span>
<span class="legacy-anchor" id="禁用压缩"></span>
<span class="legacy-anchor" id="压缩算法"></span>
<span class="legacy-anchor" id="算法设计原则"></span>
<span class="legacy-anchor" id="核心算法流程"></span>
<span class="legacy-anchor" id="步骤-1-检查触发条件"></span>
<span class="legacy-anchor" id="步骤-2-消息拆分策略"></span>
<span class="legacy-anchor" id="步骤-3-对话链保护"></span>
<span class="legacy-anchor" id="步骤-4-摘要生成"></span>
<span class="legacy-anchor" id="步骤-5-结果组装"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="_1-选择合适的阈值"></span>
<span class="legacy-anchor" id="_2-根据用例调整保留轮数"></span>
<span class="legacy-anchor" id="_3-监控-token-使用"></span>
<span class="legacy-anchor" id="_4-与长期记忆结合"></span>
<span class="legacy-anchor" id="_5-处理边缘情况"></span>
<span class="legacy-anchor" id="实现细节"></span>
<span class="legacy-anchor" id="核心类"></span>
<span class="legacy-anchor" id="生命周期集成"></span>
<span class="legacy-anchor" id="token-计数"></span>
<span class="legacy-anchor" id="总结"></span>

压缩控制单个 Agent 的长对话上下文，和跨会话记忆分别配置。压缩可能增加摘要模型调用，不能把它当成没有成本的操作。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 开关与参数

最小 Mock 演示用 `.compression(false)` 关闭摘要。需要调参时，当前 `CompressionConfig` 是 record：

```java
var compression = new CompressionConfig(
    true, 0.75, 4, 2000, null, null);
var agent = Agent.builder()
    .name("context-agent")
    .llmProvider(provider)
    .model(model)
    .compression(compression)
    .build();
```
参数依次为 `enabled`、`triggerThreshold`、`keepRecentTurns`、`keepMinTokens`、`contextWindowTokens`、`summaryModel`。这里的 0.75、4、2000 是示例取值，不是推荐值；null 表示保留相应默认值。先配置 provider，再传入 compression。

## 验证压缩

固定输入和上下文窗口，记录压缩前后消息、保留的最近轮次、工具调用链与 Usage。检查摘要是否遗漏关键约束或工具结果，再评估任务完成率和额外模型开销。

本次未执行真实摘要模型，不能保证特定阈值的效果。算法和实际默认值见[Compression 源码](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/context/Compression.java)。

[基础 Agent](tutorial-basic-agent.md) · [记忆](tutorial-memory.md) · [完整上下文说明](/cn/manual/#_27-记忆-压缩-rag-与-flow)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-compression.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
