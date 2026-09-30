# RAG 检索增强

<span class="legacy-anchor" id="教程-rag-检索增强生成-集成"></span>
<span class="legacy-anchor" id="目录"></span>
<span class="legacy-anchor" id="rag-概述"></span>
<span class="legacy-anchor" id="什么是-rag"></span>
<span class="legacy-anchor" id="core-ai-的-rag-架构"></span>
<span class="legacy-anchor" id="向量存储配置"></span>
<span class="legacy-anchor" id="_1-milvus-向量存储"></span>
<span class="legacy-anchor" id="_2-hnswlib-向量存储-轻量级"></span>
<span class="legacy-anchor" id="文档处理"></span>
<span class="legacy-anchor" id="_1-文档加载和分割"></span>
<span class="legacy-anchor" id="_2-智能文档分割"></span>
<span class="legacy-anchor" id="嵌入和检索"></span>
<span class="legacy-anchor" id="_1-生成嵌入向量"></span>
<span class="legacy-anchor" id="_2-相似度检索"></span>
<span class="legacy-anchor" id="_3-重排序"></span>
<span class="legacy-anchor" id="查询优化"></span>
<span class="legacy-anchor" id="_1-查询重写"></span>
<span class="legacy-anchor" id="_2-自适应检索"></span>
<span class="legacy-anchor" id="rag-配置和集成"></span>
<span class="legacy-anchor" id="_1-代理-rag-配置"></span>
<span class="legacy-anchor" id="_2-动态知识更新"></span>
<span class="legacy-anchor" id="实战案例"></span>
<span class="legacy-anchor" id="案例1-技术文档助手"></span>
<span class="legacy-anchor" id="案例2-客户支持知识库"></span>
<span class="legacy-anchor" id="案例3-研究论文助手"></span>
<span class="legacy-anchor" id="性能优化"></span>
<span class="legacy-anchor" id="_1-索引优化"></span>
<span class="legacy-anchor" id="_2-查询优化"></span>
<span class="legacy-anchor" id="监控和调试"></span>
<span class="legacy-anchor" id="_1-rag-性能监控"></span>
<span class="legacy-anchor" id="最佳实践"></span>
<span class="legacy-anchor" id="总结"></span>

RAG 将检索到的文档片段加入模型输入。需要正确的文档索引、embedding、VectorStore 和模型配置，单独设置 builder 并不会建立向量库。

::: info 示例范围
以下片段来自[完整编译样例](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java)，以当前源码做类型检查；未执行联网模型或外部存储。`provider`、`model` 等参数由调用者传入。运行证据见[离线 Agent](framework.md#离线-agent)。
:::
## 接入 RagConfig

当前 Agent 配置入口是 `.ragConfig(...)`，不是旧概览的 `.enableRAG(...)`。由调用者传入已准备的 `vectorStore`：

```java
var rag = RagConfig.builder()
    .useRag(true)
    .topK(5)
    .threshold(0.0)
    .vectorStore(vectorStore)
    .llmProvider(provider)
    .enableQueryRewriting(false)
    .build();
var agent = Agent.builder()
    .name("rag-agent")
    .llmProvider(provider)
    .model(model)
    .ragConfig(rag)
    .build();
```
这个片段关闭 query rewriting，便于先单独检查检索；不是所有任务的推荐设置。重写、embedding 或 rerank 的请求取决于实际 provider 和配置。本次没有访问向量库或模型。

## 按顺序检查

1. 核对文档来源、解析和 chunk，保留可追溯标识。
2. 检查 embedding 维度与索引 schema 一致。
3. 直接测试 VectorStore 检索，记录匹配片段和分数。
4. 接入 Agent 后，检查最终 prompt 和答案引用是否对应检索片段。
5. 在固定测试集合上再比较 topK、threshold、查询重写及重排。

Milvus 与 HNSWLib 在独立 backend 模块中；需要实际运行时依赖，不能仅凭配置名称假设已加载。源码入口：[RagConfig](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/rag/RagConfig.java)、[RagPipeline](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/RagPipeline.java)。

## 常见问题

零结果先查索引、身份、维度和过滤条件；有结果但答案不正确时检查 chunk 内容、注入的上下文和提示。检索结果不代表文档已授权给当前用户，授权应在资源边界执行。

[函数工具](tutorial-tool-calling.md) · [记忆](tutorial-memory.md) · [API 参考](/cn/api)

## 历史长篇材料

此前的场景分析和长篇示例保存在[固定版本记录](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/cn/tutorial-rag.md)中，可能使用旧 API。当前开发请以本页源码核对示例为准。
