# 知识系统设计 v2

> Unified Memory Architecture for AI Agent — Session / Domain / User Preference

---

## 1. 总体架构

知识系统围绕一个核心问题设计：**Agent 对话中产生的知识如何被持久化、组织和复用？**

### 1.1 五层存储管道

数据从原始对话到常驻上下文，经过逐层提炼：

```
Session → daily-logs → episodes → Wiki (knowledge) → 晋升为常驻知识
  (①)        (②)          (③)         (④)                  (⑤)
```

| 层 | 文件 | 职责 | 写入者 | 读取者 |
|---|---|---|---|---|
| ① Session | 内存（对话上下文） | 原始对话流，无需额外克隆 | Main Agent（实时追加） | T1/T2 提炼任务 |
| ② daily-logs | `daily-logs/YYYY-MM-DD/任务名.md` | 结构化事件记录：处理内容、障碍与应对、用户反馈、结果 | T1/T2/T4 | Agent 历史查询 |
| ③ episodes | `episodes/YYYY-MM-DD.md` | 当日叙事摘要，按日索引 | T1/T2 | Agent 历史查询（按日检索） |
| ④ Knowledge(Wiki) | `Memory.md` + `log.md` + `knowledge/*.md` | 持久化交叉引用知识网络 | T1/T2 Ingest | Agentic search（主要检索层） |
| ⑤ 晋升为常驻知识 | `preferences.md` / `soul.md` / `instructions.md` | 常驻注入的稳定知识 | T3 Dream Task | 每次对话自动加载 |

### 1.2 各层内容示例（以 grubhub 爬虫开发为例）

**② daily-logs** — 结构化事件记录

```markdown
---
task: grubhub.com 菜单爬虫开发
date: 2026-03-28
result: grubhub_menu.py，11 分类，95 菜品，耗时约 20s
---

## 障碍 & 应对
| 障碍                        | 应对方式                                        |
|-----------------------------|------------------------------------------------|
| networkidle 超时             | 改用目标元素等待                                 |
| 虚拟化渲染，DOM 数据不完整    | 放弃 DOM 抓取，改为 API 拦截                     |
| feed API 返回骨架空数据       | 遍历点击分类 tab 触发懒加载                       |

## 用户反馈
验证结果正确，数据格式符合预期。

## 关键决策
- 放弃 DOM 抓取，优先 API 拦截（SPA 虚拟化渲染不可靠）
- headless=False，反爬效果最佳
```

**③ episodes** — 当日摘要索引

```markdown
## 摘要
进行 grubhub.com 菜单爬虫开发，获取 11 分类 95 菜品。
关键用户反馈：验证结果正确。
→ [daily-logs/2026-03-28/grubhub_menu_crawler.md]
```

**④ knowledge** — Wiki 知识页面（四类）

- **project/** — 项目技术知识（踩坑解法、可复用模式、架构决策）
- **user/** — 用户画像（工作风格、目标偏好、技术背景）
- **feedback/** — 行为反馈（用户对 agent 行为的纠正或确认）
- **reference/** — 外部参考（API 端点、工具文档、数据路径）

**⑤ 晋升为常驻知识** — 常驻上下文三目标

| 文件 | 路径 | 内容 | 定位 |
|---|---|---|---|
| `preferences.md` | `~/` 用户级 | 沟通风格、工作方式、反馈偏好 | 跨项目用户画像 |
| `soul.md` | `./` 项目级 | 技术判断原则、解题模式、决策倾向 | Agent 能力积累 |
| `instructions.md` | `./` 项目级 | 强制规则、禁止行为、开发规范 | 外部约束（<100行） |

> **稳定性验证：** 仅跨多个 session 反复出现的模式才执行晋升。一次性偶发行为停留在 ④ 不提升。

---

## 2. Domain Knowledge: Wiki 知识库

### 2.1 核心范式

Domain Knowledge 不是被动检索的 flat files，而是 **LLM 增量构建和维护的持久化 Wiki**。知识通过多层晋升管道持续提炼，保持最新与准确，而非每次查询重新推导。

| Wiki 模式（本设计） |
|---|
| LLM 增量构建和维护持久化 Wiki |
| 知识通过多层晋升管道持续提炼，保持最新与准确 |
| 持续累积，每个来源和查询都丰富 Wiki |
| 交叉引用已就绪，矛盾已标记 |

### 2.2 Wiki 目录结构

```
.core-ai/
├── knowledge/                     ← Knowledge 根目录（LLM 拥有并维护）
│   ├── Memory.md                  ← Wiki 索引 · 每页一行摘要(cli 上限200条) · 始终加载
│   ├── log.md                     ← 操作时间线 · append-only · 可 grep
│   ├── project/                   ← 项目知识（模式/原则/决策/对比）
│   │   ├── spa-scraping.md
│   │   ├── two-phase-intercept.md
│   │   └── crawler-comparison.md
│   ├── user/                      ← 用户画像
│   │   └── profile.md
│   ├── feedback/                  ← 反馈与纠正
│   │   └── crawler-behavior.md
│   └── reference/                 ← 外部参考（API 端点/工具文档）
│       ├── grubhub-api.md
│       └── nodriver.md
├── daily-logs/                    ← ② 结构化事件记录
└── episodes/                      ← ③ 每日叙事摘要
```

### 2.3 Memory.md — Wiki 索引中枢

Wiki 的平面索引，每条一行摘要 + 文件路径，按类别组织。是 agentic search 的唯一入口，始终加载进上下文。规模 <200 行。

```markdown
# Wiki Index
## project
- [spa-scraping](knowledge/project/spa-scraping.md) — SPA 爬虫策略与踩坑
- [two-phase-intercept](knowledge/project/two-phase-intercept.md) — 两阶段拦截模式
- [crawler-comparison](knowledge/project/crawler-comparison.md) — 爬虫方案对比
## user
- [profile](knowledge/user/profile.md) — 用户画像与工作偏好
## feedback
- [crawler-behavior](knowledge/feedback/crawler-behavior.md) — 爬虫行为反馈
## reference
- [grubhub-api](knowledge/reference/grubhub-api.md) — API 端点与数据结构
- [nodriver](knowledge/reference/nodriver.md) — 浏览器自动化工具用法
```

### 2.4 log.md — 操作时间线

Append-only 时间线，记录每次 Ingest / Query / Lint 操作。统一前缀格式，可用 grep 解析。

```markdown
# Wiki Operations Log

## [2026-03-28] ingest | grubhub 菜单爬虫
- 新增: reference/grubhub-api.md
- 更新: project/spa-scraping.md (5 条新踩坑)
- 更新: user/profile.md (结果验证偏好)
- 更新: Memory.md

## [2026-03-28] query | SPA 爬虫最佳实践
- 综合 3 个 wiki 页面生成对比分析
- 结果写回: project/crawler-comparison.md

## [2026-03-29] lint | 周健康检查
- 发现: project/spa-scraping.md 与 reference/nodriver.md 矛盾 refs: grubhub-2026-03-28, doordash-2026-04-10(confuse)
- 修复: 统一 headless 策略描述
```

### 2.5 Wiki Page 格式

正文内通过 `refs:[uri]` 标记引用来源，`refs:[uri](confuse)` 标记矛盾。

```markdown
# SPA 爬虫策略

## 核心原则
对 SPA 站点，优先拦截 Network API，避免 DOM 抓取。
DOM 在虚拟化渲染下不可靠；API 响应更稳定且包含完整数据。

## 踩坑与解法
### networkidle 超时 refs: grubhub-2026-03-28, doordash-2026-04-10
- 现象: Timeout 30000ms，SPA 持续后台请求
- 解法: 改用目标元素出现作为就绪信号 → [[nodriver]]
- 验证: 2/2 站点有效 ✓

### Virtual Scroll 数据丢失 refs: grubhub-2026-03-28
- 现象: 滚动后 section 数量骤减
- 解法: 放弃 DOM，改为 API 拦截

refs: grubhub-2026-03-28, doordash-2026-04-10(confuse): headless 策略在 Grubhub 有效但 Doordash 检测 headless，结论冲突
```

### 2.6 三种维护操作

**Ingest（摄取）** — 从 Raw Sources 提取知识写入 Wiki

流程：读 source → 写 summary → 更新 Memory.md + 相关页面 → 追加 log.md → 标记矛盾。一个 source 可能触及 10-15 个 wiki 页面。

**Query（查询）** — 渐进式读取，按需加载

从 Memory.md 定位入口 → 沿正文 `refs:[]` 和 `[[link]]` 渐进加载相关页面 → 综合答案 + 标注来源。读取过程中若产生新的综合见解，触发一次 Ingest 写入 Wiki。

**Lint（健康检查）** — Wiki 质量维护

检查项目：矛盾检测、过期声明、孤立页面、缺失交叉引用、数据缺口。发现问题后自动修复或标记。

---

## 3. 触发机制

四种触发机制覆盖知识提取的完整生命周期：

### T1 · Independent Agent（异步后台提炼）

- **触发条件：** 每 10 轮对话检查，满足条件后触发；或 5 轮执行后超过 10s 无新输入
- **执行模式：** Fork 独立 agent，异步非阻塞，不占主 context window
- **执行路径：** 读 Session → 写 ② daily-logs → 写 ③ episodes → Ingest ④ Wiki → 更新 Memory.md + log.md

### T2 · Main Agent（用户主动触发）

- **触发条件：** 用户命令 `/learn`、"记住这个"、auto-learning 规则命中
- **执行模式：** 主流程同步执行，用户可见即时反馈
- **执行路径：** 与 T1 一致（Session → ② → ③ → ④）

### T3 · Dream Task（24h 深度整理）

- **触发条件：** 超过 24 小时未执行
- **执行模式：** 启动独立 agent，独立持久化会话（关闭后可恢复继续运行）
- **执行路径：**
  1. 加载全部历史（② + ③ + ④ + 更早归档）
  2. 跨 session 聚合，识别稳定规律与一次性偏差
  3. Wiki Lint（矛盾检测、过期声明、孤立页面、缺失交叉引用）
  4. 晋升为常驻知识：稳定模式路由到 `preferences.md` / `soul.md` / `instructions.md`
  5. 重组 Memory.md 索引

### T4 · Session Close（快速快照）

- **触发条件：** CLI 会话关闭 hook，无条件执行
- **执行模式：** Agent 单次 LLM call，快速快照，允许冗余
- **执行路径：** 快速总结 session 要点 → 追加写入 ② daily-logs → 下次启动后由 T1 融合

### 触发条件一览

| 触发器 | 触发条件 | 模式 | 写入目标 |
|---|---|---|---|
| T1 Independent Agent | 每 10 轮检查 / 空闲 10s | Fork 独立 agent，异步 | ② ③ ④ Memory.md |
| T2 Main Agent | 用户命令 / auto-learning | 主流程同步 | ② ③ ④ |
| T3 Dream Task | 超过 24h 未执行 | 独立 agent，持久化会话 | ⑤ preferences/soul/CLAUDE |
| T4 Session Close | 会话关闭 hook | 单次 LLM Call | ② 快照 |

### 为什么 T1/T2/T3 使用 Agent 而非单次 LLM Call

| 原因 | 说明 |
|---|---|
| 命中缓存 | 加装主 agent session 后，消息前缀与主 agent 一致，Claude 服务端直接命中缓存 |
| 按需加载 | 通过 Memory.md 定向读取相关 Wiki 页面，无需全量预加载 |
| 增量写入 | 使用 `edit_file` 只输出变更部分，不覆盖整个文件 |
| 执行一致性 | 复用主 agent 的 llmProvider、systemPrompt、toolCalls |
| 行业验证 | Claude Code、Hermes Agent 等均采用 Agent 模式执行记忆提取 |

---

## 4. Agent Context 注入

每次对话启动时，按两级策略注入上下文：

### 常驻注入（每次对话自动加载）

- `preferences.md` — 用户偏好（沟通风格、工作方式、反馈偏好）
- `soul.md` — Agent 思维原则（技术判断、决策倾向、解题模式）
- `instructions.md` — 强制规则（项目约束、禁止行为、开发规范，<100行）

### 按需加载（Agentic Search）

- `Memory.md` — Wiki 索引中枢，始终加载，<200行
- `knowledge/*.md` — 按 refs/[[link]] 渐进展开
- `episodes/YYYY-MM-DD.md` — 历史查询按日检索（最近 2 日启动时预加载）

### 对话生命周期

```
启动:    prefs + soul + instructions.md 常驻注入 · Memory.md 按需
对话中:  T1 Independent Agent 每10轮 读 Session → 写 ②③④ 提炼链
随时:    T2 Main Agent 用户命令 · sync · Session → ② → ③ → ④ · 即时反馈
关闭:    T4 Session Close 快摘要写 ② · 下次启动 T1 融合
每日:    T3 Dream Task 读全部历史 → 深度整理 + 晋升 → ⑤
```

