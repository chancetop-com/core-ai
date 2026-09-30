# 教程与学习路径

先通过 [快速开始](quickstart.md)选择 Server、CLI 或框架，再按需要逐步添加能力。源码要求 Java 25 与仓库 Wrapper；基础 Java 知识即可开始离线示例，不必先准备真实模型凭据。

| 教程 | 学到什么 | 额外前提 |
| --- | --- | --- |
| [基础 Agent](tutorial-basic-agent.md) | builder、run、状态和 Streaming 契约 | Mock 示例可离线；真实 provider 另配 |
| [函数工具](tutorial-tool-calling.md) | 注解、schema、函数到工具的转换 | 涉及外部写入时确认实际授权 |
| [记忆](tutorial-memory.md) | 用户上下文、MemoryStore 与自动召回 | 存储和 embedding / 提取模型 |
| [压缩](tutorial-compression.md) | 压缩开关和参数 | 摘要模型、上下文窗口与成本评估 |
| [RAG](tutorial-rag.md) | VectorStore、RagConfig 与检索检查 | 索引、embedding、后端依赖 |
| [Skills](tutorial-skills.md) | SKILL.md、Registry 和来源优先级 | 已审核本地目录与脚本依赖 |
| [Flow](tutorial-flow.md) | 节点、边、检查与 run 契约 | 各节点 provider 和数据输入 |
| [架构](tutorial-architecture.md) | Server / CLI / 框架边界 | 先跑通最小 Agent |

## 第一个可核对的成功结果

从 [Java 离线 Agent](framework.md#离线-agent)开始，预期得到“离线演示成功”、`COMPLETED` 和调用次数 1。脚本要求已有依赖缓存，不能保证刚克隆的仓库可离线构建。Python [FakeSession 演示](manual/#_29-python-离线演示与截图)验证资源发现与参数。

## 示例的验证范围

本轮教程中的当前 API 示例做编译核对，不执行真实模型、记忆提取或向量库请求。离线运行的证据来自手册。设计记录和历史实验可能描述旧接口或提案，阅读时按当前源码确认。

[API 参考](/cn/api) · [CLI 跨平台排障](cli-troubleshooting.md) · [完整手册](manual/) · [报告问题](https://github.com/chancetop-com/core-ai/issues)
