# 07 - Trace 与调试

> 🎯 **学习目标**：掌握 core-ai-frontend 中 Trace 追踪系统的完整使用，学会通过 Span 树、Token 成本分析和管理员仪表盘观测与调试 AI 系统运行
>
> ⏱️ **预计时间**：1.5 天

---

## 📋 本章内容

- [0.1 Trace 系统概览](#01-trace-系统概览)
- [0.2 核心数据模型](#02-核心数据模型)
- [0.3 Span 类型与状态](#03-span-类型与状态)
- [0.4 Trace 列表与筛选](#04-trace-列表与筛选)
- [0.5 智能搜索](#05-智能搜索)
- [0.6 时间范围与分面筛选](#06-时间范围与分面筛选)
- [0.7 Trace 详情与 Span 树](#07-trace-详情与-span-树)
- [0.8 Span 检查器](#08-span-检查器)
- [0.9 Token 与成本追踪](#09-token-与成本追踪)
- [0.10 Trace API 详解](#10-trace-api-详解)
- [0.11 管理员分析仪表盘](#11-管理员分析仪表盘)
- [0.12 颜色方案与视觉设计](#12-颜色方案与视觉设计)

---

## 0.1 Trace 系统概览

Trace（追踪）系统是 core-ai 平台观测和调试 AI 运行的核心基础设施。每当 Agent 被调用、LLM 发起请求、工具被执行，系统都会自动生成一条 Trace 记录。

### 0.1.1 Trace 页面文件清单

| 文件 | 职责 |
|------|------|
| `pages/traces/TraceList.tsx` | Trace 列表页，支持多维度筛选 |
| `pages/traces/TraceDetail.tsx` | Trace 详情页，展示 Span 树 |
| `pages/traces/TraceInspector.tsx` | Span 详情检查器 |
| `pages/traces/TraceDetailPanel.tsx` | 选中 Span 的详情面板 |
| `pages/traces/traceViewModel.ts` | Trace 数据视图模型逻辑 |
| `pages/traces/colors.ts` | Span 类型的颜色方案 |
| `pages/dashboard/Dashboard.tsx` | 管理员分析仪表盘 |

💡 **设计意图**：Trace 系统采用「列表 → 详情 → 检查器」三级下钻结构，每一级聚焦不同粒度的观测需求。

### 0.1.2 Trace 在系统中的位置

```
用户请求
  → Gateway 接收
  → 创建 Trace（traceId 贯穿全链路）
  → Agent 运行 → 产生 AGENT Span
    → LLM 调用 → 产生 LLM Span
    → 工具执行 → 产生 TOOL Span
  → 返回结果
  → Trace 完成，写入存储
  → 前端 Trace 列表可查看
```

---

## 0.2 核心数据模型

### 0.2.1 Trace 结构

```typescript
interface Trace {
  id: string;                   // Trace 内部 ID
  traceId: string;              // 全链路 Trace ID
  name: string;                 // Trace 名称
  type: 'agent' | 'llm_call' | 'external';  // Trace 类型
  source: TraceSource;          // 来源
  agentName: string;            // Agent 名称
  model: string;                // 使用的模型
  sessionId: string;            // 会话 ID
  userId: string;               // 用户 ID
  status: TraceStatus;          // 状态
  errorMessage?: string;        // 错误信息
  input?: string;               // 输入内容
  output?: string;              // 输出内容
  preview?: string;             // 预览摘要
  metadata?: object;            // 元数据
  inputTokens: number;          // 输入 Token 数
  outputTokens: number;         // 输出 Token 数
  totalTokens: number;          // 总 Token 数
  cachedTokens: number;         // 缓存 Token 数
  costUsd: number;              // 费用（美元）
  durationMs: number;           // 耗时（毫秒）
  startedAt: string;            // 开始时间
  completedAt?: string;         // 完成时间
}
```

### 0.2.2 Trace 来源（Source）

| 来源 | 说明 |
|------|------|
| `chat` | 用户对话 |
| `test` | 测试运行 |
| `api` | API 调用 |
| `a2a` | Agent-to-Agent 调用 |
| `scheduled` | 定时任务 |
| `workflow` | 工作流触发 |
| `llm_test` | LLM 测试 |
| `llm_api` | LLM API 直调 |
| `gateway` | Gateway 直接调用 |
| `external` | 外部来源 |

### 0.2.3 Trace 状态

| 状态 | 说明 |
|------|------|
| `RUNNING` | 运行中 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |
| `ERROR` | 出错 |

💡 **设计意图**：Trace 记录的全量字段设计使其成为「可观测性」的单一数据源——从来源、状态、Token 消耗到费用，一条记录覆盖调试与运营分析的双重需求。

---

## 0.3 Span 类型与状态

Trace 由若干 Span 组成，每个 Span 代表一个执行单元。

### 0.3.1 Span 结构

```typescript
interface Span {
  id: string;                 // Span 内部 ID
  traceId: string;            // 所属 Trace ID
  spanId: string;             // Span 业务 ID
  parentSpanId?: string;      // 父 Span ID（树形结构）
  name: string;               // Span 名称
  type: SpanType;             // Span 类型
  model?: string;             // 使用的模型（LLM Span）
  input?: string;             // 输入
  output?: string;            // 输出
  inputTokens: number;        // 输入 Token
  outputTokens: number;       // 输出 Token
  cachedTokens: number;       // 缓存 Token
  costUsd: number;            // 费用
  durationMs: number;         // 耗时
  status: SpanStatus;         // 状态
  attributes?: object;        // 自定义属性
  startedAt: string;          // 开始时间
  completedAt?: string;       // 完成时间
}
```

### 0.3.2 Span 类型

| 类型 | 说明 | 典型场景 |
|------|------|---------|
| `LLM` | LLM 模型调用 | 调用 GPT/Claude 等 |
| `AGENT` | Agent 执行 | Agent 整体运行 |
| `TOOL` | 工具执行 | 调用 web_search、code_run 等 |
| `FLOW` | 流程节点 | 工作流节点 |
| `GROUP` | 分组 | 逻辑分组多个 Span |

### 0.3.3 Span 状态

| 状态 | 说明 |
|------|------|
| `OK` | 成功 |
| `CANCELLED` | 已取消 |
| `ERROR` | 出错 |

💡 **设计意图**：Span 的树形结构（`parentSpanId`）让复杂的调用链可以被可视化为嵌套树，便于定位问题根因。

---

## 0.4 Trace 列表与筛选

`TraceList.tsx` 是 Trace 系统的入口页面。

### 0.4.1 筛选维度

| 维度 | 字段 | 说明 |
|------|------|------|
| **智能搜索** | `q` | 搜索 ID、用户名、Trace 名称、Agent 名称 |
| **名称正则** | `name` | 按名称正则匹配 |
| **类型** | `type` | agent / llm_call / external |
| **来源** | `source` | chat / api / workflow 等 |
| **Agent 名称** | `agentName` | 指定 Agent |
| **模型** | `model` | 指定模型 |
| **状态** | `status` | RUNNING / COMPLETED / ERROR / CANCELLED |
| **会话 ID** | `sessionId` | 指定会话 |
| **用户 ID** | `userId` | 指定用户 |
| **时间范围** | `range` | 15m / 1h / 24h / 7d / 30d |
| **自定义时间** | `startFrom` / `startTo` | 自定义起止时间 |

### 0.4.2 列表交互流程

```
页面加载
  → 默认查询最近 1h 的 Trace
  → 渲染列表（名称、类型、来源、模型、状态、耗时、Token、费用、时间）
  → 用户修改筛选条件
  → 触发 API 请求（防抖 300ms）
  → 更新列表
  → 点击某行 → 跳转 TraceDetail
```

💡 **设计意图**：多维筛选让不同角色（开发者、运营、管理员）都能快速定位自己关心的 Trace。

---

## 0.5 智能搜索

智能搜索是 Trace 列表的核心亮点功能，通过 `q` 参数实现跨维度搜索。

### 0.5.1 搜索范围

`q` 参数同时搜索以下维度：

- **Trace ID / Span ID**：精确匹配 ID
- **用户账号**：搜索用户名或 ID
- **Trace 名称**：模糊匹配
- **Agent 名称**：模糊匹配

### 0.5.2 使用示例

```
q="error"           → 搜索名称或 Agent 名包含 error 的 Trace
q="user@example.com"→ 搜索该用户的 Trace
q="trace-abc123"    → 搜索指定 ID 的 Trace
q="gpt-4"           → 搜索使用 gpt-4 模型的 Trace（结合 model 分面）
```

💡 **设计意图**：将多个搜索维度合并为单一输入框，降低用户心智负担——不需要记住该用哪个筛选字段，直接输入关键词即可。

---

## 0.6 时间范围与分面筛选

### 0.6.1 时间范围预设

| 预设 | 说明 |
|------|------|
| `15m` | 最近 15 分钟 |
| `1h` | 最近 1 小时 |
| `24h` | 最近 24 小时 |
| `7d` | 最近 7 天 |
| `30d` | 最近 30 天 |

还可以选择「自定义」，手动指定起止时间。

### 0.6.2 分面筛选（Facets）

分面（Facet）是从 Trace 数据中动态提取的维度值分布，用于辅助筛选。

```typescript
interface TraceFacet {
  value: string;    // 维度值
  count: number;    // 该值的 Trace 数量
}
```

前端通过 `api.traces.facets(field, filters)` 获取分面数据：

| 字段 | 说明 |
|------|------|
| `model` | 模型分面（如 gpt-4, claude-3） |
| `agentName` | Agent 名称分面 |
| `source` | 来源分面 |

💡 **设计意图**：分面筛选让用户可以看到每个维度值的分布，比如「哪个模型调用量最大」「哪个来源的 Trace 最多」，无需手写查询。

---

## 0.7 Trace 详情与 Span 树

`TraceDetail.tsx` 展示单条 Trace 的完整信息。

### 0.7.1 页面布局

```
┌──────────────────────────────────────┐
│ Trace 头部信息                        │
│ 名称 | 类型 | 来源 | 状态 | 耗时 | Token | 费用 │
├──────────────────────────────────────┤
│ Span 树（左侧）        │ Span 详情（右侧） │
│                        │                   │
│ ▼ AGENT: main_agent   │ 选中 Span 的       │
│   ▼ LLM: gpt-4       │ 输入/输出/属性      │
│   ▼ TOOL: search     │                   │
│   ▼ LLM: gpt-4       │                   │
│ ▼ AGENT: sub_agent   │                   │
└──────────────────────────────────────┘
```

### 0.7.2 Span 树构建逻辑

`traceViewModel.ts` 负责将扁平的 Span 列表转换为树形结构：

```typescript
// 伪代码 - Span 树构建
function buildSpanTree(spans: Span[]): SpanNode[] {
  const map = new Map<string, SpanNode>();
  const roots: SpanNode[] = [];

  // 1. 创建节点映射
  spans.forEach(span => {
    map.set(span.spanId, { span, children: [] });
  });

  // 2. 建立父子关系
  spans.forEach(span => {
    const node = map.get(span.spanId)!;
    if (span.parentSpanId && map.has(span.parentSpanId)) {
      map.get(span.parentSpanId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  return roots;
}
```

💡 **设计意图**：树形结构直观呈现调用链的嵌套关系，点击任意 Span 即可在右侧面板查看完整输入输出。

---

## 0.8 Span 检查器

`TraceInspector.tsx` 和 `TraceDetailPanel.tsx` 提供 Span 级别的深度检查。

### 0.8.1 检查器功能

- **输入/输出查看**：完整展示 Span 的 input 和 output（可能很大，支持折叠）
- **属性查看**：展示 Span 的自定义 attributes
- **Token 统计**：inputTokens / outputTokens / cachedTokens
- **费用**：该 Span 的 costUsd
- **耗时**：durationMs
- **状态标识**：OK / CANCELLED / ERROR 颜色区分
- **跳转到子 Trace**：如果 Span 触发了子 Trace，可以跳转

### 0.8.2 获取 Span 详情

```typescript
// 获取 Span 完整详情（含大字段）
const spanDetail = await api.traces.span(traceId, spanId);
// spanDetail 包含完整的 input / output，可能体积较大
```

💡 **设计意图**：Span 检查器是定位问题的「显微镜」——当 Trace 级别的预览不够时，下钻到 Span 查看完整 I/O。

---

## 0.9 Token 与成本追踪

Trace 系统内建了 Token 消耗和成本追踪能力。

### 0.9.1 Token 字段

| 字段 | 说明 |
|------|------|
| `inputTokens` | 输入 Token 数 |
| `outputTokens` | 输出 Token 数 |
| `totalTokens` | 总 Token 数（input + output） |
| `cachedTokens` | 命中缓存的 Token 数 |

### 0.9.2 成本计算

每个 Span 的 `costUsd` 由模型定价自动计算：

```
costUsd = inputTokens × inputPrice + outputTokens × outputPrice
```

缓存 Token 通常有折扣（如 10% 价格），降低重复调用的成本。

### 0.9.3 LLM-only Span 查询

```typescript
// 只查询 LLM 类型的 Span（用于分析模型消耗）
const llmSpans = await api.traces.generations(offset, limit, model);
```

💡 **设计意图**：Token 与成本的精确追踪是 AI 系统运营的基础——没有度量就没有优化。

---

## 0.10 Trace API 详解

### 0.10.1 API 速查表

| 操作 | API | 返回 |
|------|-----|------|
| **列表** | `api.traces.list(offset, limit, filters)` | `TraceListResponse` |
| **详情** | `api.traces.get(id)` | `Trace` |
| **Span 列表** | `api.traces.spans(id)` | `Span[]` |
| **Span 详情** | `api.traces.span(traceId, spanId)` | `Span`（含完整 I/O） |
| **LLM Span** | `api.traces.generations(offset, limit, model)` | `Span[]` |
| **会话摘要** | `api.traces.sessionSummary(sessionId)` | `SessionSummary` |
| **分面** | `api.traces.facets(field, filters)` | `TraceFacet[]` |

### 0.10.2 筛选参数（TraceFilter）

```typescript
interface TraceFilter {
  q?: string;            // 智能搜索
  name?: string;         // 名称正则
  type?: string;         // 类型
  source?: string;       // 来源
  agentName?: string;    // Agent 名称
  model?: string;        // 模型
  status?: string;       // 状态
  sessionId?: string;    // 会话 ID
  userId?: string;       // 用户 ID
  range?: '15m' | '1h' | '24h' | '7d' | '30d';
  startFrom?: string;    // 自定义起始
  startTo?: string;      // 自定义截止
}
```

### 0.10.3 会话摘要

```typescript
interface SessionSummary {
  sessionId: string;
  totalTraces: number;
  totalTokens: number;
  totalCostUsd: number;
  firstTraceAt: string;
  lastTraceAt: string;
  agents: string[];
  models: string[];
}
```

💡 **设计意图**：会话摘要提供了一次会话的全局视图——总共多少轮对话、消耗多少 Token、花费多少钱，适合运营分析。

---

## 0.11 管理员分析仪表盘

`pages/dashboard/Dashboard.tsx` 是面向管理员的全局分析仪表盘。

### 0.11.1 仪表盘功能

- **全局概览**：总 Token、总费用、总调用次数、平均 Token/次
- **趋势分析**：Token 消耗与费用的时间趋势
- **维度分析**：按来源、Agent、用户、提供商、模型分组统计
- **模式切换**：历史模式 / 实时模式

### 0.11.2 分析数据模型

```typescript
// 全局概览
interface AnalyticsGlobal {
  totalTokens: number;
  totalCostUsd: number;
  totalCalls: number;
  avgTokensPerCall: number;
  p90: number;            // P90 耗时
  // ...更多统计指标
}

// 趋势点
interface TrendPoint {
  timestamp: string;
  tokens: number;
  cost: number;
  calls: number;
}

// 维度项
interface DimensionItem {
  name: string;           // 维度值（如模型名、Agent 名）
  tokens: number;
  cost: number;
  calls: number;
}

// 维度分析
interface DimensionAnalytics {
  dimension: string;
  items: DimensionItem[];
}
```

### 0.11.3 分析维度

| 维度 | 说明 |
|------|------|
| `source` | 按来源分组（chat / api / workflow ...） |
| `agent` | 按 Agent 分组 |
| `user` | 按用户分组 |
| `provider` | 按模型提供商分组（OpenAI / Anthropic ...） |
| `model` | 按模型分组 |

### 0.11.4 管理员分析 API

| API | 说明 |
|-----|------|
| `api.adminAnalytics.global` | 全局概览 |
| `api.adminAnalytics.trend` | 时间趋势 |
| `api.adminAnalytics.bySource` | 按来源 |
| `api.adminAnalytics.byAgent` | 按 Agent |
| `api.adminAnalytics.byUser` | 按用户 |
| `api.adminAnalytics.byProvider` | 按提供商 |
| `api.adminAnalytics.byModel` | 按模型 |
| `api.adminAnalytics.dimensionTrend` | 维度趋势 |

### 0.11.5 图表渲染

仪表盘使用 **recharts** 库渲染图表：

- **折线图**：Token / 费用时间趋势
- **柱状图**：各维度对比
- **饼图**：维度占比
- **面积图**：累积消耗

💡 **设计意图**：仪表盘将 Trace 数据转化为运营视角的可视化报表，帮助管理员回答「钱花在哪了」「哪个 Agent 最费钱」「哪个模型性价比最高」等关键问题。

---

## 0.12 颜色方案与视觉设计

`colors.ts` 定义了 Span 类型的颜色方案，确保不同类型在视觉上可区分。

### 0.12.1 Span 类型颜色

| 类型 | 颜色 | 说明 |
|------|------|------|
| `LLM` | 紫色系 | 模型调用 |
| `AGENT` | 蓝色系 | Agent 执行 |
| `TOOL` | 绿色系 | 工具执行 |
| `FLOW` | 橙色系 | 流程节点 |
| `GROUP` | 灰色系 | 分组 |

### 0.12.2 状态颜色

| 状态 | 颜色 |
|------|------|
| `OK` | 绿色 |
| `CANCELLED` | 黄色 |
| `ERROR` | 红色 |

💡 **设计意图**：统一的配色方案让用户在不同页面（列表、详情、仪表盘）都能快速识别 Span 类型和状态，降低视觉认知成本。

---

### 🔧 动手实践

1. **查看 Trace 列表**：打开 Trace 列表页，观察最近的 Trace 记录，注意每条 Trace 的类型、来源、状态
2. **使用智能搜索**：在搜索框输入一个 Agent 名称，观察搜索结果
3. **应用分面筛选**：点击模型分面，选择一个模型，观察列表如何过滤
4. **查看 Trace 详情**：点击一条 Trace，观察 Span 树的嵌套结构
5. **检查 Span**：在 Span 树中点击一个 LLM Span，查看右侧面板的完整输入输出
6. **对比 Token 消耗**：查看不同 Span 的 Token 消耗，计算总成本
7. **浏览仪表盘**：打开管理员仪表盘，观察全局统计和各维度分析图表
8. **切换时间范围**：在仪表盘切换不同时间范围（15m / 1h / 24h / 7d），观察趋势变化

### 📝 自测题

1. Trace 和 Span 是什么关系？一个 Trace 可以包含哪些类型的 Span？
2. 智能搜索（`q` 参数）可以搜索哪些维度？为什么这样设计？
3. 分面筛选（Facets）和普通筛选有什么区别？它的数据来源是什么？
4. `cachedTokens` 是什么意思？它对成本有什么影响？
5. 工作流产生的 Trace，其来源（source）是什么？Span 类型会包含哪些？
6. 管理员仪表盘的「历史模式」和「实时模式」有什么区别？
7. 如何通过 Span 树定位一次调用中的性能瓶颈？

---

## 🎉 本章小结

本章我们深入学习了 core-ai-frontend 的 Trace 追踪与调试系统：

- **Trace 数据模型**：理解了 Trace 与 Span 的结构、类型、来源、状态
- **列表与筛选**：掌握了智能搜索、分面筛选、时间范围预设等核心能力
- **Span 树分析**：学会了通过树形结构观测调用链路，定位问题根因
- **Token 与成本追踪**：理解了 Token 消耗的计算方式和成本追踪机制
- **管理员仪表盘**：掌握了全局概览、趋势分析、多维度分析等运营能力
- **视觉设计**：了解了 Span 类型和状态的配色方案

Trace 系统是 AI 系统可靠运行的「眼睛」——没有可观测性，就没有可调试性，更没有可优化性。

---

## 🚀 下一章

在 [08-工具与技能](./08-工具与技能.md) 中，我们将学习 core-ai-frontend 中的工具系统（MCP 服务器、API 工具、内置工具）和技能市场（技能浏览、上传、管理），了解如何扩展 Agent 的能力边界。

*最后更新: 2026-08-31*
