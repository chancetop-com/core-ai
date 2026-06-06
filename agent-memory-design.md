# Agent 级记忆系统设计文档

## 1. 背景与约束

### 1.1 需求

当前 core-ai-server 的 Agent 每次启动从空白状态开始，无法从历史运行中学习。本设计为每个 Agent 建立独立、稳定、轻量的记忆，使其后续运行更高效。

核心约束：

| 约束 | 说明 |
|------|------|
| **单业务 Agent** | 每个 Agent 只做一件事。复杂业务通过多个 Agent + workflow 组合实现，不应由单一 Agent 承担 |
| **记忆稳定收敛** | Agent 的业务范围固定，记忆不应无限增长，应在 10-20 条内自然收敛 |
| **非侵入** | 所有代码在 server 层实现，不修改 core-ai 框架 |
| **不阻塞** | 记忆提取完全异步，不增加 Agent 启动或运行延迟 |

### 1.2 当前代码库现状

| 维度 | 现状 |
|------|------|
| **core-ai Memory** | 有 `Memory`/`MemoryStore` 等原型类，仅 `InMemoryStore`，以 `userId` 为隔离键 |
| **Agent 构建** | `AgentRunner.buildAgent()` 使用 `builder.systemPrompt(string)`，未使用 `PromptInject` |
| **Trace 系统** | `traces`/`spans` MongoDB 集合，含 `session_id`/`user_id`/`agent_name`/`input`/`output`/`tokens` |
| **PromptInject** | `AgentBuilder.systemPromptSection()` 支持结构化注入，`SectionType.MEMORY` 类型已定义 |
| **后台调度** | `ServerModule` 通过 `schedule().fixedRate()` 注册 Job |

---

## 2. 总体架构

### 2.1 两阶段模型

```
┌──────────────────────────────────────────────────────────────────┐
│                     Agent Memory System                           │
│                                                                   │
│  ┌───────────────────────┐       ┌──────────────────────────┐    │
│  │  Phase 1: Extraction  │       │  Phase 2: Injection       │    │
│  │  (异步, 每小时)        │       │  (Agent 启动时, 同步)    │    │
│  │                       │       │                           │    │
│  │  ConsolidationJob     │       │  AgentRunner              │    │
│  │       │               │       │  .buildAgent()            │    │
│  │       ▼               │       │       │                   │    │
│  │  ┌───────────────┐    │       │       ▼                   │    │
│  │  │ 加载已有记忆   │    │       │  ┌───────────────────┐   │    │
│  │  │ + 新 trace     │    │       │  │ AgentMemoryService │   │    │
│  │  └───────┬───────┘    │       │  │ .findByAgentId()   │   │    │
│  │          ▼            │       │  │ .formatAsPrompt()  │   │    │
│  │  ┌───────────────┐    │       │  └────────┬──────────┘   │    │
│  │  │ LLM 融合更新   │    │       │           ▼              │    │
│  │  │ (见已有, 判    │    │       │  ┌───────────────────┐   │    │
│  │  │  增删改合并)   │    │       │  │ PromptInject      │   │    │
│  │  └───────┬───────┘    │       │  │ SectionType.MEMORY│   │    │
│  │          ▼            │       │  └───────────────────┘   │    │
│  │  ┌───────────────┐    │       │                           │    │
│  │  │ 全量替换写入   │    │       │  System Prompt:           │    │
│  │  │ agent_memories│    │       │  ...                      │    │
│  │  └───────────────┘    │       │  ## Agent Memory          │    │
│  └───────────────────────┘       │  - [记忆 1]               │    │
│                                   │  - [记忆 2]               │    │
│                                   └──────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计原则

| 原则 | 说明 |
|------|------|
| **LLM 管理记忆集合** | 每次提取时 LLM 看到已有记忆 + 新 trace，自主判断增删改合并，输出完整的最新集合，全量替换写入 DB。去重、更新、淘汰全部由 LLM 完成，不靠数据库层面的 hash 或 embedding |
| **全量注入** | 记忆集合小而稳定，有多少条就注入多少条 |
| **Append 式溯源** | `source_trace_ids` 累积记录每条记忆的来源 trace |
| **Agent 级隔离** | `agent_id` 为隔离键 |
| **极简数据模型** | 只有 5 个业务字段，不引入 importance / type / embedding / hash / status 等模型层概念 |

---

## 3. 数据模型

### 3.1 `agent_memories`

```
Collection: agent_memories
├── _id:               ObjectId
├── agent_id:          String         (索引, 隔离键)
├── type:              String         (纯标注, 给人看的标签)
├── content:           String         (一条不超过 200 字的经验)
├── source_trace_ids:  List<String>   (来源 trace ID, 溯源)
├── created_at:        ZonedDateTime
├── updated_at:        ZonedDateTime
```

索引：

```javascript
db.agent_memories.createIndex({ agent_id: 1 })
```

### 3.2 `agent_memory_extraction_cursors`

```
Collection: agent_memory_extraction_cursors
├── _id:               ObjectId
├── agent_id:          String         (唯一索引)
├── last_processed_at: ZonedDateTime  (已处理 trace 的时间截止点)
├── updated_at:        ZonedDateTime
```

索引：

```javascript
db.agent_memory_extraction_cursors.createIndex({ agent_id: 1 }, { unique: true })
```

### 3.3 关于 type 字段

`type` 字段保留，但仅作为**纯标注**（给人看的标签），不参与任何业务逻辑：

- 不建索引、不在注入时按 type 分组、不在查询时按 type 过滤
- 5 种类别：`WORKFLOW_PATTERN` / `GOTCHA` / `TOOL_USAGE` / `EFFICIENCY` / `DOMAIN_KNOWLEDGE`
- LLM 提取时顺手标注，方便人工在数据库里扫一眼大致了解记忆构成
- 性质类似代码注释：不参与执行，但帮助理解

### 3.4 不需要的字段

以下字段经讨论后确认不需要：

| 字段 | 去掉原因 |
|------|---------|
| `importance` | 不存在"存着但不注入"的场景——LLM 提取时就决定了记不记，记了就注入 |
| `content_hash` | LLM 已经看到已有记忆做语义去重，MD5 拦不住语义重复 |
| `embedding` | 同上，不需要向量相似度去重 |
| `status` | 只有一种状态——记忆在集合里就是有效的 |
| `decay_factor` / `access_count` | 不存在热度管理的场景 |

---

## 4. 详细设计

### 4.1 文件清单

**新增：**

| 文件 | 位置 | 说明 |
|------|------|------|
| `AgentMemory.java` | `server/memory/` | 实体类，MongoDB `@Collection("agent_memories")` |
| `AgentMemoryExtractionCursor.java` | `server/memory/` | 游标实体类 |
| `AgentMemoryService.java` | `server/memory/` | 记忆查询 / 写入 / 格式化 |
| `AgentMemoryConsolidationJob.java` | `server/memory/` | 定时提取 Job |
| `AgentMemoryPromptInject.java` | `server/memory/` | `PromptInject` 实现，`SectionType.MEMORY` |

**修改：**

| 文件 | 改动 |
|------|------|
| `AgentRunner.java` | `buildAgent()` 中调用 `agentMemoryService.buildMemoryPromptInject(definition.id)` |
| `ServerModule.java` | `configure()` 中注册 `AgentMemoryConsolidationJob` 调度 |

### 4.2 `AgentMemoryService`

```java
package ai.core.server.memory;

public class AgentMemoryService {

    /**
     * 查询 agent 的全部记忆，按 created_at 升序
     */
    public List<AgentMemory> findByAgentId(String agentId);

    /**
     * 构建 PromptInject，SectionType.MEMORY
     * 无记忆时返回 null (不注入空 section)
     */
    public PromptInject buildMemoryPromptInject(String agentId);

    /**
     * 将记忆列表格式化为 system prompt 文本
     */
    public String formatAsPrompt(List<AgentMemory> memories);

    /**
     * 全量替换 agent 的记忆集合
     */
    public void replaceAll(String agentId, List<AgentMemory> memories);

    /**
     * 获取游标（首次返回 null）
     */
    public AgentMemoryExtractionCursor getCursor(String agentId);

    /**
     * 更新游标
     */
    public void upsertCursor(AgentMemoryExtractionCursor cursor);
}
```

### 4.3 记忆注入格式

```
## Agent Memory

The following patterns were learned from your previous successful runs.
Use them to work more efficiently, but override when a situation clearly
calls for a different approach.

- [记忆 1]
- [记忆 2]
- [记忆 3]
```

- 平铺列表，不分组
- 全部记忆都注入，不按任何条件过滤
- 无记忆时整个 section 不注入

### 4.4 `AgentRunner.buildAgent()` 修改

```java
// 现有逻辑
if (systemPrompt != null) builder.systemPrompt(systemPrompt);

// 新增：注入记忆 section
var memorySection = agentMemoryService.buildMemoryPromptInject(definition.id);
if (memorySection != null) {
    builder.systemPromptSection(memorySection);
}
```

`AgentBuilder` 内部已实现：先拼接 base `systemPrompt` 字符串，再依次 append 所有 `systemPromptSections`。无需修改框架代码。

### 4.5 `AgentMemoryPromptInject`

```java
package ai.core.server.memory;

import ai.core.prompt.PromptInject;

public class AgentMemoryPromptInject implements PromptInject {
    private final String content;

    public AgentMemoryPromptInject(String content) {
        this.content = content;
    }

    @Override
    public String inject() {
        return content;
    }

    @Override
    public SectionType type() {
        return SectionType.MEMORY;
    }
}
```

### 4.6 后台提取：`AgentMemoryConsolidationJob`

#### 触发参数

| 参数 | 值 |
|------|-----|
| 执行频率 | 每小时 |
| idle 阈值 | 24 小时（session 最后活动距今超过 24h） |
| 每次最多处理 trace 数 | 50 条 |
| 最少 trace 数 | 5 条（低于此数跳过，不够提取价值） |

#### 执行流程

```
1. 从 traces 集合查询所有 distinct agent_name
2. 对每个 agent:
   a. 获取 extraction_cursor（首次则为 epoch）
   b. 查询满足条件的 trace:
      - agent_name = agentId
      - status = COMPLETED
      - started_at > cursor.last_processed_at
      - 所在 session 的 last_message_at 距今 > 24h
   c. 排除 cursor 中已处理的 trace
   d. 若新 trace < 5，跳过
   e. 从 agent_memories 加载已有记忆
   f. 构建 prompt（已有记忆 + 新 trace），调用 LLM
   g. 解析 LLM 输出，全量替换写入 agent_memories
   h. 更新 cursor (last_processed_at, last_trace_ids)
```

#### LLM 提取 Prompt

```
You maintain an agent's memory — a concise, stable set of reusable
experiences that help the same agent perform more efficiently in future runs.

## Current memory
{existing_memories 格式化为列表，空则显示 "(empty)"}

## New execution traces
{new_traces}

## Task

Review the current memory against the new traces, then produce the UPDATED
complete memory set. For each memory:

- VALIDATE: if a trace reaffirms a memory, keep it unchanged
- REFINE:   if a trace adds nuance or reveals an edge case, improve it
- MERGE:    if two memories overlap, combine them into one
- ADD:      if a trace reveals a genuinely new reusable pattern, add it
- REMOVE:   if a memory is outdated or no longer applies, drop it

Rules:
- Only include patterns reusable across future runs
- Skip one-off facts tied to specific user input
- Keep each memory under 200 words
- Be specific and actionable

For each memory, also label its type:
WORKFLOW_PATTERN | GOTCHA | TOOL_USAGE | EFFICIENCY | DOMAIN_KNOWLEDGE

Output JSON:
{
  "memories": [
    { "type": "WORKFLOW_PATTERN", "content": "..." },
    { "type": "GOTCHA", "content": "..." }
  ]
}
```

#### 写入逻辑

```
old_memories = findByAgentId(agentId);     // 写入前的快照
new_memories = LLM 输出的 memories 列表;

new_memories 中:
  - 每条 memory 的 source_trace_ids = 处理到的 trace_id 集合
  - created_at  = 若 content 在 old_memories 中能找到则保留旧值，否则 = now
  - updated_at  = now

replaceAll(agentId, new_memories);         // 全量替换
```

旧记录中被 LLM 淘汰的条目自然消失——不归档、不保留。

### 4.7 服务配置

```yaml
memory:
  extraction:
    enabled: true
    scheduleIntervalMinutes: 60
    idleThresholdHours: 24
    maxTracesPerAgent: 50
    minTracesForExtraction: 5
    model: "gpt-4.1-mini"
  injection:
    enabled: true
```

---

## 5. 集成点

### 5.1 `ServerModule` 注册

```java
// 绑定
bind(AgentMemoryService.class);
bind(AgentMemoryConsolidationJob.class);

// 定时任务
schedule().fixedRate(
    "agent-memory-consolidation",
    bind(AgentMemoryConsolidationJob.class),
    Duration.ofMinutes(60)
);
```

### 5.2 AgentRunner 调用链

```
AgentRunner.buildAgent()
    ├── resolveSystemPrompt()          // 不变
    ├── appendArtifactInstructions()    // 不变
    ├── appendDatasetInstructions()     // 不变
    ├── agentMemoryService             // 新增
    │   .buildMemoryPromptInject()
    │   → PromptInject (SectionType.MEMORY)
    │   → builder.systemPromptSection(memorySection)
    └── builder.build()
```

### 5.3 session idle 判断

`ConsolidationJob` 不依赖 `AgentSessionManager`，通过数据库查询判断：

```
traces.session_id → chat_sessions.last_message_at > (now - 24h) → idle
```

### 5.4 LLM 成本估算

以 `gpt-4.1-mini` 为例：

```
50 trace × ~500 token/trace  = 25K input tokens   (~$0.01)
已有记忆 ~10 条 × 100 token   = 1K input tokens     (~$0.0004)
LLM 输出 ~10 条 × 100 token   = 1K output tokens    (~0.0004)
───────────────────────────────────────────────────
每次提取 ≈ $0.011
每 agent 每天最多 24 次 ≈  $0.26
```

实际远低于上限——大部分 agent 每天不会有新的 idle trace。

---

## 6. 三个核心决策

### 6.1 为什么让 LLM 管理记忆集合而不用 DB 层面的去重？

LLM 能做语义级别的去重、合并、更新、淘汰——这些都是 hash 和 embedding 做不到或做不好的。把记忆集合的完整管理交给 LLM，DB 只是一个存储载体。

### 6.2 为什么不做 importance 分层？

"存着但不注入"的场景不存在。单业务 Agent 的记忆小而稳定，记了就注入。如果哪天记忆真的膨胀到需要分层，说明该拆 agent 了——那是架构设计的问题，不是记忆系统的问题。

### 6.3 为什么是 agent_id 隔离而不是 user_id？

需求是「每个 Agent 拥有独立记忆」。不同用户的同名 Agent 有不同 `AgentDefinition.id`，`agent_id` 天然隔离了不同用户。若未来需要跨 Agent 的用户级记忆，属于新的需求范围，届时再评估。

---

*设计版本：v2.0*
*最后更新：2026-06-06*
