# Skill 放置位置实验

> Skill 描述放在 Tool Description 还是 System Prompt 中，哪种方案的 LLM 调用准确率更高？

## 背景

在 LLM Agent 框架中，Skill（可复用的 prompt 模板）需要被模型感知，以便决定何时调用。有两个天然的放置位置：

- **Tool Description**：OpenAI 兼容的 tool/function schema 中的 `description` 字段。模型经过专门的 fine-tuning 来读取此字段做 tool selection。
- **System Prompt**：对话开头的系统消息。享有 primacy bias（位于上下文开头，注意力权重高）。

每个 Skill 包含两类信息：
- **What** — 功能描述（这个 skill 做什么）
- **When** — 触发条件（什么时候该用，什么时候不该用）

本实验测试不同的放置策略对 tool-call 准确率的影响。

## 实验设计

### 自变量：放置策略（4 组）

| 组 | Tool Description 内容 | System Prompt 内容 | 策略 |
|----|----------------------|-------------------|------|
| A | skill 名称 + what + when | `"You are a helpful assistant with access to tools."` | 全部放 tool desc |
| B | `"Use a skill to accomplish specialized tasks."` | skill 名称 + what + when | 全部放 system prompt |
| C | skill 名称 + what + when | skill 名称 + what + when | 两处都放（冗余） |
| D | skill 名称 + **what** | skill **when**（触发条件） | What/When 分离 |

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

#### 汇总

| 组 | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|----|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 0.944 | 0.680 | 0.791 | 0.025 | 0.600 | 17 | 1 | 8 | 14 |
| B | 1.000 | 0.680 | 0.810 | 0.000 | 0.600 | 17 | 0 | 8 | 15 |
| **C** | **1.000** | **0.800** | **0.889** | **0.000** | **1.000** | **20** | **0** | **5** | **15** |
| D | 1.000 | 0.640 | 0.780 | 0.000 | 0.800 | 16 | 0 | 9 | 15 |

**最优方案：C 组（F1=0.889）**

#### 混淆矩阵 — A 组（全放 tool desc）

```
                      预测结果
                  触发 Skill    未触发
实际  应触发        17 (TP)      8 (FN)
      不应触发       1 (FP)     14 (TN)

Precision=0.944  Recall=0.680  F1=0.791
```

#### 混淆矩阵 — B 组（全放 system prompt）

```
                      预测结果
                  触发 Skill    未触发
实际  应触发        17 (TP)      8 (FN)
      不应触发       0 (FP)     15 (TN)

Precision=1.000  Recall=0.680  F1=0.810
```

#### 混淆矩阵 — C 组（两处都放）

```
                      预测结果
                  触发 Skill    未触发
实际  应触发        20 (TP)      5 (FN)
      不应触发       0 (FP)     15 (TN)

Precision=1.000  Recall=0.800  F1=0.889
```

#### 混淆矩阵 — D 组（what/when 分离）

```
                      预测结果
                  触发 Skill    未触发
实际  应触发        16 (TP)      9 (FN)
      不应触发       0 (FP)     15 (TN)

Precision=1.000  Recall=0.640  F1=0.780
```

#### 各组漏触发 & 误触发明细

| 组 | 漏触发的 Query | 误触发 |
|----|---------------|--------|
| A | security vulnerabilities, SQL (x2), Docker deploy, CI/CD, refactor strategy | git log → git-commit |
| B | security vulnerabilities, optimize SQL, API docs, CI/CD, refactor strategy, perf analyze | 无 |
| C | README, CI/CD, refactor strategy, spaghetti code, perf analyze | 无 |
| D | security vulnerabilities, optimize SQL, Docker deploy, CI/CD, refactor (x2), perf (x2) | 无 |

### Claude Sonnet 4.6（部分结果 — C 组执行中被取消）

| 组 | Precision | Recall | F1 | FalseRate | AmbiguousAcc | TP | FP | FN | TN |
|----|-----------|--------|----|-----------|--------------|----|----|----|----|
| A | 1.000 | 0.520 | 0.684 | 0.000 | 0.600 | 13 | 0 | 12 | 15 |
| B | 1.000 | 0.720 | 0.837 | 0.000 | 1.000 | 18 | 0 | 7 | 15 |
| C | — | — | — | — | — | — | — | — | — |

## 关键发现

### 1. C 组（冗余放置）在 GPT-4.1-mini 上全面最优

在 tool description 和 system prompt 两处都放 skill 信息，所有指标均为最佳。信息冗余强化了模型的理解，而非稀释注意力。

### 2. A 组是唯一出现误触发的

将全部信息放在 tool description 中，导致唯一的假正例（FP=1）："Show me the git log" 被错误路由到 git-commit。当 skill 列表出现在 tool schema 中时，模型倾向于调用工具。

### 3. D 组（what/when 分离）表现最差

与最初假设相反，将 "what" 放在 tool desc、"when" 放在 system prompt 的分离策略，召回率最低（0.640）。当两处位置都没有完整信息时，模型反而犹豫不决。

### 4. Claude 与 GPT 的注意力模式不同

Claude Sonnet 在 A 组（信息仅在 tool desc）时召回率极低（0.520），但 B 组（信息在 system prompt）时显著提升（0.720）。这表明**不同模型家族的最优放置策略可能不同**——Claude 更关注 system prompt，GPT 对两处的关注度更均衡。

### 5. 跨组的共同难题

以下 query 在大多数组中都未能触发：

| Query | 期望 Skill | 漏触发的组 |
|-------|-----------|-----------|
| Refactor this class to use the strategy pattern | refactor | A, B, C, D |
| Create a CI/CD pipeline with GitHub Actions | deploy | A, B, C, D |
| Profile this method and find the bottleneck | perf-analyze | C, D |
| Why is this endpoint so slow? Analyze performance. | perf-analyze | B, D |

这说明问题可能在于 skill 描述的关键词覆盖不足，而非放置位置。

## 已知局限

1. **Token 预算未对齐** — C 组的总 token 数约为 A/B/D 组的 2 倍。无法区分提升来自"冗余策略"还是"信息量加倍"。
2. **仅单一 tool** — 真实 Agent 有多个 tool 竞争选择。本实验仅测试"调还是不调"一个 tool。
3. **temperature=0 + majority vote 冗余** — 确定性输出下跑 3 次取投票没有意义。应使用 temperature>0 或只跑 1 次。
4. **样本量小** — 40 条 case，无统计显著性检验。组间差异可能是噪声。
5. **歧义评估使用子串匹配** — `"code-review,refactor".contains("review")` 可能错误匹配。应改为集合判断。
6. **执行顺序固定** — A→B→C→D 固定顺序，可能引入 API 层面的偏差（缓存、限流）。

## 后续改进

- [ ] 修复歧义评估 bug（子串匹配 → 集合匹配）
- [ ] 补全更多模型数据（GPT-5.2、minimax-m2.5）和 Claude 完整数据
- [ ] 控制 token 预算：填充较短的 prompt 或裁剪较长的
- [ ] 添加干扰 tool（file_read、shell_exec）模拟真实多 tool 场景
- [ ] 扩展规模测试：skill 数量增加到 20、30、50，寻找交叉点
- [ ] 使用 temperature>0 配合更多轮次运行，做统计显著性检验
