# Skill 放置位置实验

> Skill 描述放在 Tool Description 还是 System Prompt 中，哪种方案的 LLM 调用准确率更高？

## 背景

在 LLM Agent 框架中，Skill（可复用的 prompt 模板）需要被模型感知，以便决定何时调用。有两个天然的放置位置：

- **Tool Description**：OpenAI 兼容的 tool/function schema 中的 `description` 字段。模型经过专门的 fine-tuning 来读取此字段做 tool selection。
- **System Prompt**：对话开头的系统消息。享有 primacy bias（位于上下文开头，注意力权重高）。

每个 Skill 包含两类信息：
- **What** — 功能描述（这个 skill 做什么）
- **When** — 触发条件（什么时候该用，什么时候不该用）

本实验测试不同的放置策略对 tool-call 准确率的影响，并验证「跨位置放置」是否优于「同位置重复」。

## 实验设计

### 自变量：放置策略（6 组）

| 组 | Tool Description 内容 | System Prompt 内容 | 策略 |
|----|----------------------|-------------------|------|
| A | skill 名称 + what + when | `"You are a helpful assistant with access to tools."` | 全部放 tool desc |
| B | `"Use a skill to accomplish specialized tasks."` | skill 名称 + what + when | 全部放 system prompt |
| C | skill 名称 + what + when | skill 名称 + what + when | 跨位置放置（两处都放） |
| D | skill 名称 + **what** | skill **when**（触发条件） | What/When 分离 |
| E | skill 信息 **重复 2 次** | 通用提示 | 同位置重复（tool desc） |
| F | 通用提示 | skill 信息 **重复 2 次** | 同位置重复（system prompt） |

E 和 F 组是控制组，用于区分两个假设：
- **跨位置互补假说**：C 赢是因为 tool desc 和 system prompt 走不同的注意力路径，互相强化
- **纯重复强化假说**：C 赢只是因为信息出现了两次，位置无所谓

### 控制变量

- 相同的 10 个 mock skill
- 相同的 40 条测试 query
- 相同模型（同一轮对比中）
- temperature=0.0（确定性输出）
- 单一 tool schema（`use_skill`，参数 `name`）
- 每条 query 跑 3 次，取多数投票结果

### Mock Skills（10 个）

| Skill | What（功能描述） | When（触发条件） |
|-------|----------------|-----------------|
| code-review | 检查代码中的 bug、安全问题和风格问题 | 用于代码审查/找 bug/安全检查。不用于写新代码或重构 |
| git-commit | 创建规范的 git commit 和 commit message | 用于提交/暂存代码。不用于 git log、diff 等只读操作 |
| web-search | 搜索网络获取实时信息 | 用于实时信息/时事。不用于代码库问题 |
| test-generator | 生成单元测试（JUnit/pytest/jest） | 用于生成/编写测试。不用于运行或修复测试 |
| sql-query | 编写和优化 SQL（MySQL/PostgreSQL/SQLite） | 用于写 SQL/优化查询。不用于 ORM 或应用层数据访问 |
| doc-writer | 生成文档、README、API 文档 | 用于写文档/README。不用于代码审查中的注释 |
| deploy | 管理部署、Docker、CI/CD 配置 | 用于部署/Docker/CI/CD。不用于本地开发环境搭建 |
| refactor | 重构代码以改善结构和可读性 | 用于明确的重构请求。不用于 bug 修复或功能添加 |
| api-design | 设计 REST/GraphQL API 和 schema | 用于 API 设计/契约定义。不用于实现 handler |
| perf-analyze | 分析性能、定位瓶颈 | 用于性能分析/profiling。不用于一般代码审查或 bug 修复 |

### 测试集（40 条）

#### 正例（20 条）— 应该触发特定 skill

| # | Query | 期望 Skill | 变体说明 |
|---|-------|-----------|---------|
| 1 | Review this Java class for potential bugs | code-review | 直接匹配 |
| 2 | Can you check this code for security vulnerabilities? | code-review | 安全角度 |
| 3 | Commit these changes with a good message | git-commit | 直接匹配 |
| 4 | Stage all modified files and create a commit | git-commit | 多步操作 |
| 5 | What happened in the news today? | web-search | 时事 |
| 6 | Search the web for the latest Python 3.13 features | web-search | 显式搜索 |
| 7 | Generate unit tests for this service class | test-generator | 直接匹配 |
| 8 | Write JUnit tests for the UserController | test-generator | 指定框架 |
| 9 | Write a SQL query to find top 10 customers by revenue | sql-query | 直接匹配 |
| 10 | Optimize this slow PostgreSQL query | sql-query | 优化 |
| 11 | Write a README for this project | doc-writer | 直接匹配 |
| 12 | Generate API documentation for these endpoints | doc-writer | API 文档 |
| 13 | Set up a Docker deployment for this Spring Boot app | deploy | 直接匹配 |
| 14 | Create a CI/CD pipeline with GitHub Actions | deploy | CI/CD |
| 15 | Refactor this class to use the strategy pattern | refactor | 直接匹配 |
| 16 | Clean up this spaghetti code and improve readability | refactor | 清理代码 |
| 17 | Design a REST API for a todo app | api-design | 直接匹配 |
| 18 | What should the API contract look like for user management? | api-design | 契约设计 |
| 19 | Profile this method and find the bottleneck | perf-analyze | 直接匹配 |
| 20 | Why is this endpoint so slow? Analyze performance. | perf-analyze | 诊断 |

#### 近似例（8 条）— 语义接近但不应触发

| # | Query | 陷阱 Skill | 不应触发的原因 |
|---|-------|-----------|-------------|
| 1 | Show me the git log for the last 10 commits | git-commit | 只读 git 操作 |
| 2 | Run the existing tests and show results | test-generator | 运行测试，不是生成 |
| 3 | Fix this failing test assertion | test-generator | 修复测试，不是生成 |
| 4 | Add input validation to this endpoint handler | api-design | 实现功能，不是设计 |
| 5 | Set up my local development environment | deploy | 本地环境，不是部署 |
| 6 | Add a comment explaining this complex regex | doc-writer | 行内注释，不是文档 |
| 7 | Fix this NullPointerException in the service layer | code-review | 修 bug，不是审查 |
| 8 | Write the JPA entity for this table | sql-query | ORM 代码，不是 SQL |

#### 歧义例（5 条）— 可匹配多个 skill

| # | Query | 可接受的 Skill |
|---|-------|---------------|
| 1 | Review and refactor this authentication module | code-review, refactor |
| 2 | Write tests and document the payment service | test-generator, doc-writer |
| 3 | Design and deploy a microservice API | api-design, deploy |
| 4 | Optimize this SQL query and profile the endpoint | sql-query, perf-analyze |
| 5 | Review the code and check for performance issues | code-review, perf-analyze |

#### 反例（7 条）— 无关问题，不应触发任何 skill

| # | Query | 类别 |
|---|-------|------|
| 1 | What is the capital of France? | 常识 |
| 2 | Explain how HashMap works in Java | 概念解释 |
| 3 | Calculate 42 * 17 + 3 | 数学计算 |
| 4 | Tell me a joke about programming | 娱乐 |
| 5 | What does this error message mean? | 错误解释 |
| 6 | How do I install Node.js on macOS? | 安装指导 |
| 7 | What is the difference between REST and GraphQL? | 概念对比 |

### 评估指标

| 指标 | 定义 | 说明 |
|------|------|------|
| TP（真正例） | 正例/歧义 case，正确触发了对应 skill | 调对了 |
| FP（假正例） | 调了错误的 skill，或不该调的时候调了 | 调错了 / 误触发 |
| FN（假反例） | 应该触发但没触发，或触发了错误 skill | 漏调了 |
| TN（真反例） | 近似例/反例 case，正确地没有触发 | 正确拒绝 |
| Precision | TP / (TP + FP) | 调用的里面，多少是对的 |
| Recall | TP / (TP + FN) | 该调用的里面，多少被正确触发了 |
| F1 | 2 * P * R / (P + R) | 精确率和召回率的调和平均 |
| False Trigger Rate | FP / total cases | 误触发率 |
| Ambiguous Accuracy | 歧义正确数 / 歧义总数 | 歧义场景处理能力 |

## 实验结果

### GPT-4.1-mini

| 组 | 策略 | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|----|------|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 全放 tool desc | 0.944 | 0.680 | 0.791 | 0.025 | 0.600 | 17 | 1 | 8 | 14 |
| B | 全放 system prompt | 1.000 | 0.680 | 0.810 | 0.000 | 0.600 | 17 | 0 | 8 | 15 |
| **C** | **跨位置放置** | **1.000** | **0.800** | **0.889** | **0.000** | **1.000** | **20** | **0** | **5** | **15** |
| D | what/when 分离 | 1.000 | 0.640 | 0.780 | 0.000 | 0.800 | 16 | 0 | 9 | 15 |
| E | tool desc 重复 2x | 0.944 | 0.680 | 0.791 | 0.025 | 0.800 | 17 | 1 | 8 | 14 |
| F | system prompt 重复 2x | 1.000 | 0.680 | 0.810 | 0.000 | 0.600 | 17 | 0 | 8 | 15 |

#### 混淆矩阵

```
A 组（全放 tool desc）               B 组（全放 system prompt）
          触发    未触发                      触发    未触发
应触发    17(TP)   8(FN)            应触发    17(TP)   8(FN)
不应触发   1(FP)  14(TN)           不应触发   0(FP)  15(TN)
P=0.944 R=0.680 F1=0.791          P=1.000 R=0.680 F1=0.810

C 组（跨位置放置）                   D 组（what/when 分离）
          触发    未触发                      触发    未触发
应触发    20(TP)   5(FN)            应触发    16(TP)   9(FN)
不应触发   0(FP)  15(TN)           不应触发   0(FP)  15(TN)
P=1.000 R=0.800 F1=0.889          P=1.000 R=0.640 F1=0.780

E 组（tool desc 重复 2x）           F 组（system prompt 重复 2x）
          触发    未触发                      触发    未触发
应触发    17(TP)   8(FN)            应触发    17(TP)   8(FN)
不应触发   1(FP)  14(TN)           不应触发   0(FP)  15(TN)
P=0.944 R=0.680 F1=0.791          P=1.000 R=0.680 F1=0.810
```

### MiniMax-M2.5

| 组 | 策略 | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|----|------|-----------|--------|----|-----------|--------------|----|----|----|----|
| **A** | **全放 tool desc** | **1.000** | **0.480** | **0.649** | **0.000** | **0.200** | **12** | **0** | **13** | **15** |
| B | 全放 system prompt | 0.909 | 0.400 | 0.556 | 0.025 | 0.000 | 10 | 1 | 15 | 14 |
| C | 跨位置放置 | 0.923 | 0.480 | 0.632 | 0.025 | 0.200 | 12 | 1 | 13 | 14 |
| D | what/when 分离 | 0.917 | 0.440 | 0.595 | 0.025 | 0.200 | 11 | 1 | 14 | 14 |
| E | tool desc 重复 2x | 1.000 | 0.400 | 0.571 | 0.000 | 0.200 | 10 | 0 | 15 | 15 |
| F | system prompt 重复 2x | 1.000 | 0.400 | 0.571 | 0.000 | 0.000 | 10 | 0 | 15 | 15 |

注：MiniMax-M2.5 在测试过程中出现了偶发的 500 错误和超时，可能影响了受影响组（B、C）的召回率。

### Claude Sonnet 4.6（部分结果 — C 组执行中被取消）

| 组 | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|----|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 1.000 | 0.520 | 0.684 | 0.000 | 0.600 | 13 | 0 | 12 | 15 |
| B | 1.000 | 0.720 | 0.837 | 0.000 | 1.000 | 18 | 0 | 7 | 15 |

## 假设验证：跨位置互补 vs 同位置重复

核心问题：C 组赢是因为信息出现在**两个不同位置**，还是仅仅因为信息出现了**两次**？

### GPT-4.1-mini

| 对比 | F1 | Recall | 结论 |
|-----|-----|--------|------|
| C（跨位置） | **0.889** | **0.800** | 全面最优 |
| E（tool desc 2x）vs A（tool desc 1x） | 0.791 vs 0.791 | 0.680 vs 0.680 | 同位置重复**零效果** |
| F（system prompt 2x）vs B（system prompt 1x） | 0.810 vs 0.810 | 0.680 vs 0.680 | 同位置重复**零效果** |
| C vs E | 0.889 vs 0.791 | 0.800 vs 0.680 | 跨位置显著优于同位置重复 |
| C vs F | 0.889 vs 0.810 | 0.800 vs 0.680 | 跨位置显著优于同位置重复 |

**结论：是跨位置互补效应，不是纯重复强化。**

### MiniMax-M2.5

| 对比 | F1 | 结论 |
|-----|-----|------|
| C（跨位置） | 0.632 | 不是最优（A=0.649 更好） |
| E（tool desc 2x）vs A（tool desc 1x） | 0.571 vs 0.649 | 重复**反而有害** |
| F（system prompt 2x）vs B（system prompt 1x） | 0.571 vs 0.556 | 差异很小 |

**结论：跨位置对 MiniMax 无效。仅放 tool desc（A 组）最优。**

## 原理分析：为什么不同模型表现不同？

### 根本原因：Prompt Format 架构差异

GPT 和 MiniMax 的结果差异，可以用各自的 prompt 模板如何编码 tool 定义来解释。以下分析基于公开文档和开源模型信息，实际内部实现可能有差异。

### OpenAI GPT：Harmony 格式 — Tool 和 System 在不同的消息段

根据 OpenAI 公开的 Harmony response format 规范和开源的 gpt-oss 模型，GPT 模型具有独立的消息段（segment）：

```
<|start|>system<|message|>You are a helpful assistant.<|end|>
<|start|>developer<|message|>
namespace functions {
  // Use a skill to accomplish specialized tasks.
  type use_skill = (_: { name: string }) => any;
}
<|end|>
<|start|>user<|message|>Review this Java class...<|end|>
```

关键架构细节：**Tool 定义被注入到 `developer` 消息中，与 `system` 消息是分开的。** 它们使用不同的 special token（`<|start|>system` vs `<|start|>developer`），在推理时产生**不同的注意力模式**。

这意味着：
- **A 组**（仅 tool desc）：skill 信息只在 `developer` 段
- **B 组**（仅 system prompt）：skill 信息只在 `system` 段
- **C 组**（两处都放）：skill 信息在**两个不同的段** — 模型通过两条不同的注意力路径接触到信息
- **E 组**（tool desc 2x）：skill 信息在同一个 `developer` 段内重复 — 没有新的注意力路径

这就解释了为什么 GPT 上 C > E：跨段放置激活了不同的 attention head，这些 head 在训练时分别学会了处理 `system` 和 `developer` 内容。

### MiniMax-M2.5：自定义格式 — Tool 嵌入在 System 消息内

根据 MiniMax 开源模型仓库和 tool calling 文档，MiniMax 使用自己的 special token 体系：

```
]~!b[]~b]system
You are a helpful assistant.
<tools>
[{"type":"function","function":{"name":"use_skill",...}}]
</tools>
[e~[]~b]user
Review this Java class...
[e~[]~b]ai
```

关键差异：**Tool 定义通过 `<tools>` 标签嵌入在 `system` 消息内部。** 没有独立的 `developer` 段。Tool desc 和 system prompt 共享同一个注意力段。

这意味着：
- MiniMax 的 **C 组**：两份 skill 信息最终都在同一个 `system` 段内 — 功能上等同于同位置重复
- 因为只有一个段同时承载系统指令和 tool schema，没有跨段互补的可能
- **A 组效果最好**是因为 `<tools>` 标签在 system 消息内提供了清晰的结构边界，模型对 tool schema 内的信息更有信心

### Claude：Anthropic 格式 — 偏向 System Prompt

Claude 在 API 层面 system prompt 和 tool 定义是分开的，但 Anthropic 的内部 prompt 模板未公开。A 组（0.520）和 B 组（0.720）之间的巨大差距初步表明，Claude 的训练可能更侧重于从 system prompt 中获取行为指导，而 tool schema 可能主要用于参数校验而非调用决策。此推断仅基于 6 组中的 2 组数据，需要完整数据验证。

### 总结：Format 决定最优策略

| 模型 | Prompt 格式 | Tool 所在段 | 最优策略 | 原因 |
|------|------------|-----------|---------|------|
| GPT-4.1-mini | Harmony | 独立的 `developer` 消息 | C（跨位置） | 两个独立注意力段互相强化 |
| MiniMax-M2.5 | 自定义 token | `system` 消息内（`<tools>` 标签） | A（仅 tool desc） | 单一段；`<tools>` 标签提供结构清晰度 |
| Claude Sonnet | Anthropic 格式 | 独立但权重较低 | B（system prompt） | 训练偏向于从 system prompt 获取行为指导 |

## 关键发现

### 1. 跨位置放置对有独立 tool 段的模型（GPT）效果最优

C 组（F1=0.889）在 GPT-4.1-mini 上全面领先。E/F 控制组证明这不是因为重复 — 而是跨段注意力强化。

### 2. 同位置重复零收益（甚至有害）

- GPT：E=A（F1 完全相同 0.791），F=B（F1 完全相同 0.810）
- MiniMax：E < A（0.571 < 0.649），F ≈ B

在同一个 prompt 段内重复信息不会增加新信号。模型对该段已有完整的注意力覆盖。

### 3. 最优策略因模型而异

不存在通用的最佳放置方案。最优策略取决于模型的 prompt 模板如何分隔 tool 定义和系统指令。

### 4. Tool-use 训练质量比放置策略重要得多

MiniMax-M2.5 最好的 F1（0.649）远低于 GPT-4.1-mini 最差的（0.780）。模型的 tool-use 微调质量是决定性因素。

### 5. D 组（what/when 分离）在 GPT 上持续表现最差

GPT-4.1-mini 上 D 组 F1 最低（0.780）。MiniMax 上 D 组（0.595）低于基线 A 组（0.649），但并非绝对最差（E/F 均为 0.571）。两个模型上，将功能描述和触发条件拆到不同位置都导致两处位置各自缺乏足够的上下文来做出自信的决策。

## 已知局限

1. **Token 预算未完全对齐** — C 组的总 token 数约为 A/B 的 2 倍。E/F 组部分解决了这个问题（和 C 相同的 token 量，但放置位置不同）。
2. **仅单一 tool** — 真实 Agent 有多个 tool 竞争选择。
3. **temperature=0 + majority vote 冗余** — 确定性输出下跑 3 次取投票没有意义。
4. **样本量小** — 40 条 case，无统计显著性检验。
5. **歧义评估使用子串匹配** — 应改为集合判断。
6. **执行顺序固定** — A→B→C→D→E→F 固定顺序，可能引入 API 层面的偏差。
7. **MiniMax API 不稳定** — 偶发的 500 错误和超时可能影响了结果。

## 后续改进

- [ ] 修复歧义评估 bug（子串匹配 → 集合匹配）
- [ ] 补全 Claude Sonnet 全 6 组数据
- [ ] 添加干扰 tool（file_read、shell_exec）模拟真实多 tool 场景
- [ ] 扩展规模测试：skill 数量增加到 20、30、50，寻找交叉点
- [ ] 通过检查实际发送给模型的 token 序列来验证 prompt format 理论
- [ ] 使用 prompt 模板完全可见的开源模型测试（Qwen、Llama）
