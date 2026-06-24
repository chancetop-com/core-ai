# Core AI Server Workflow 引擎设计（Engine Spec）

> 本文是 Workflow **执行引擎**的权威设计，由多视角设计评审综合而成。
> 它 **supersedes**（取代）`design-workflow-server.md` 中的 §6.4 图模型、§6.5/§6.6 Run/NodeRun、§7 节点语义、§8 变量模型、§9 执行语义，以及 §3.2 非目标里关于"不做并行/循环/iteration/代码节点"的条目。
> `design-workflow-server.md` 中的**平台集成**部分（触发入口 §10、权限与凭证 §11、API §13、前端 §14、测试 §16、风险 §17）仍然有效，本文不重复，只在需要处引用。

---

## 0. 北极星范围（locked scope）

目标引擎 = **可持久化（崩溃可恢复，多副本）+ 并发（并行 fan-out / join）+ 容器子图（loop / iteration）** 的图引擎。

- **两种运行模式，一套引擎**：
  - **Workflow**：单次执行；manual / API / webhook / schedule 触发；END 节点产出 `run.output`。
  - **Chatflow**：会话有状态；每条用户消息 = 一次完整 run；`conversation.*` 变量跨 run 持久在 `ChatSession`；ANSWER 节点经现有 SSE 流式产出。
- **状态模型一次建到位**（P0），节点类型分阶段点亮。新增任何特性 = 新增一个 executor，**不动引擎**。
- **human input（运行中挂起等人）暂不点亮**，但 checkpoint 基建预留 `WAITING`，将来可加。

---

## 1. 优雅内核：六个正交概念

整个引擎只有一处控制逻辑——一个纯函数；所有不确定性只藏在一个接口后面。

> **不变式（一句话）**：`frontier = plan(graph, durable-facts)`，`values = project(durable-facts)`。引擎从不修改控制状态，只追加 node-run 检查点，然后重新推导。恢复 = 再调一次 `plan`。

| # | 概念 | Java 形态 | 是什么 | 为什么不可约 |
|---|------|----------|--------|------|
| **C1** | **Graph** | `WorkflowGraph(nodes, edges)`，不可变、sha256 钉死 | 静态拓扑 + 每节点 executor 类型 + 类型化变量声明 + 预算好的支配树 | 唯一非历史的输入 |
| **C2** | **Planner** | `Frontier plan(WorkflowGraph, RunState)` —— **纯、全、确定** | 把持久事实 fold 成 就绪集 + 跳过集 + 边裁决 | 引擎本体；唯一控制逻辑；零 mock 可单测 |
| **C3** | **NodeExecutor** | `NodeOutcome execute(NodeContext)` | **唯一**副作用面，一类节点一个实现 | 隔离所有非确定性/IO；新特性 = 新 executor |
| **C4** | **RunState** | `WorkflowRun`(租约) + `WorkflowNodeRun[]`(按 `scopePath` 的检查点) | planner 要 fold 的持久真相 | 崩溃可恢复；frontier 可从它重算，无需保存游标 |
| **C5** | **VariablePool** | 类型化分作用域 map，是已完成 node-run + env/sys/conversation 的**投影** | 解析 selector，读节点输出 | 本身派生，和 frontier 同源——同一 fold，不同投影 |
| **C6** | **Cursor / 租约** | `WorkflowRun.{claimedBy, leaseUntil}`，run 级 CAS | 跨副本的单写者租约 | 让纯 fold 变得持久且单写；唯一的跨副本认领 |

### 1.1 这些"不是概念"（优雅的真正来源）

- **边三态（PENDING/ACTIVE/SKIPPED）不是存储状态**——由 planner 从每个源 node-run 的 `status` + `chosen_edge_ids` **推导**（`chosen_edge_ids` 为空=NORMAL 全 ACTIVE；非空=BRANCH 选中 ACTIVE 其余 SKIPPED）。
- **并行 / 分支 / join / fan-out / skip 不是概念**——是 `plan` 计算出的边裁决转移。**不存在 coordinator / scheduler / join 对象**。
- **IF/ELSE / LOOP / ITERATION / HTTP / CODE / AGENT 不是引擎概念**——是 `NodeExecutor` 实现。引擎从不 `instanceof` 节点。
- **容器（loop/iteration）不是第二个引擎**——是 C3 带着 `scopePath` 重新进入 C2 跑子图。
- **VariablePool 不独立持久化**——它是 `fold(已完成 node-run) + env/sys/conversation`。
- **NOTE 不进图**——画布元数据，加载时排除。
- **"恢复服务 / 节点调度器 / join 协调器" 都不存在**——全部坍缩为"重新认领 run，再调一次 `plan`"。

---

## 2. 执行模型

一个 run 由**一个**拥有它的 worker 整体驱动，采用**连续 ready-queue**（不是 wave-barrier）：节点一旦就绪立即派发，完成回流后增量重跑 planner。

### 2.1 边裁决推导（planner 从每个源 node-run 算出）

| 源 node-run | 出边裁决 |
|------|------|
| `COMPLETED, kind=NORMAL` | **全部**出边 `ACTIVE`（并行 fan-out 是默认、免费的） |
| `COMPLETED, kind=BRANCH(chosen)` | 选中出边 `ACTIVE`，其余 `SKIPPED` |
| `SKIPPED` | 全部出边 `SKIPPED`（skip 传播） |

### 2.2 就绪与跳过谓词（精确）

```
READY(N)   ⟺  N 在此 scopePath 还没有 node-run
              ∧ inEdges(N) ≠ ∅
              ∧ ∀ e ∈ inEdges(N): verdict(e) ∈ {ACTIVE, SKIPPED}   // 全部终态
              ∧ ∃ e ∈ inEdges(N): verdict(e) = ACTIVE              // 至少一条活

SKIPPED(N) ⟺  inEdges(N) ≠ ∅
              ∧ ∀ e ∈ inEdges(N): verdict(e) ∈ {ACTIVE, SKIPPED}   // 全部终态
              ∧ ∄ e ∈ inEdges(N): verdict(e) = ACTIVE              // 无一活

START（无入边）在 run 启动时被播种为 READY。
```

`SKIPPED` 本身写成一条 `WorkflowNodeRun(status=SKIPPED)`，从而在下一轮 `plan` 中继续向下传播。

**单调格证明（join 永不死锁）**：每条边只从 `PENDING → {ACTIVE, SKIPPED}` 一次、永不回退 ⟹ 从任意检查点重放都收敛到同一不动点；任何 join 的入边最终全终态——只要 ≥1 ACTIVE 它就触发，否则它自己 SKIPPED 继续传播。

### 2.3 主循环（连续 ready-queue，ToolOrchestration 范式）

```java
// WorkflowRunner.advance(run) — runs in the claiming replica.
// An INDEPENDENT heartbeat thread renews the lease, never gated on node completion.
void advance(WorkflowRun run) {
    WorkflowGraph graph = versions.load(run.versionId);           // sha256-verified
    Map<NodeRef, Future<?>> inflight = new ConcurrentHashMap<>();
    while (true) {
        if (isCancelled(run.id)) { stopInflight(inflight); return; }   // cancel via Mongo status, not the futures map
        Frontier f = planner.plan(graph, RunState.of(run, nodeRuns.byRun(run.id)));   // PURE
        // Completion = frontier EXHAUSTED (no ready, no skip) AND nothing in flight. NOT "a sink completed":
        // under parallel fan-out an early END can complete while sibling branches still have ready work, so
        // gating on outputReached would drop that work. f.outputReached() then classifies success vs stuck/failed.
        // RACE NOTE: a node may complete between this plan and the inflight check. When inflight is empty the
        // journal is stable (recordOutcome happens-before each inflight remove), so RE-PLAN from a fresh read
        // before classifying — classifying from the stale `f` could wrongly report FAILED.
        if (!f.hasProgress() && inflight.isEmpty()) {
            Frontier fresh = planner.plan(graph, RunState.of(run, nodeRuns.byRun(run.id)));
            if (fresh.hasProgress()) continue;
            finish(run, fresh); return;
        }
        for (NodeRef nr : f.skipSet())  appendSkip(run, nr);          // write SKIPPED node-run; next plan propagates it
        for (NodeRef nr : f.readySet()) {
            appendRunning(run, nr);                                   // RUNNING node-run; unique index makes it idempotent
            inflight.put(nr, pool.submit(() -> runNode(graph, run, nr)));   // Semaphore(maxConcurrency)-gated
        }
        if (f.readySet().isEmpty() && f.skipSet().isEmpty()) {
            if (inflight.isEmpty()) return;
            awaitAnyCompletion(inflight);                            // CompletableFuture.anyOf — NOT join-all
        }
        // runNode appends COMPLETED/FAILED node-run, removes from inflight, loop re-plans incrementally
    }
}
```

- **并行 = ToolOrchestration 映射**：`f.readySet()` 可能含 N 个节点，各自提交到共享 `executorService`，由 `Semaphore(maxConcurrency)` 门控——就是 `ToolOrchestration` 的批并发习惯。**并行是"没有串行化"，不是一种节点类型。** 后一波早就就绪的节点立刻派发，无 barrier。

### 2.4 容器递归（ITERATION / LOOP）= C3 重新进入 C2

容器节点的 executor 在**作用域变量池**里对子图开一个子 fold，只多一个 `scopePath`：

- **ITERATION（map）**：对 `array[0..n)`，每项开子作用域 `[ITER:i]`，播种 `loop.item / loop.index`，递归 `advance` 跑子图，受 `parallelCap` 约束（复用同一 `Semaphore`）。输出 = 各子图终端输出的数组。
- **LOOP（顺序 fold）**：子作用域 `[LOOP:0], [LOOP:1], …` 顺序跑，把 `loop.carry` 从上一子的投影池穿到下一子；迭代间检查 planner 求值的退出条件 selector；**硬 `maxIterations` 护栏**。输出 = 最终 carry。

每个子 node-run 带扩展 `scopePath`，因此容器重复执行是互相区分的检查点。父引擎只看到"一个慢节点完成了"。

---

## 3. 状态与持久化

四个 collection，对照 agent 那一摞。**node-run 是值的唯一真相**。

### 3.1 关键设计决策 D1：不存派生的 edge 缓存

> 综合稿曾提议在 run 文档上额外存一份派生的 `edge_state` map 做"O(1) resume"。**本设计去掉它。**
>
> 理由：whole-run 单 pod 驱动时，planner 在**内存里增量重算**，正常推进根本不碰 Mongo；只有恢复时才从 Mongo 重 fold 一次，而恢复罕见、且 node-run 是终态事实，重 fold 只是对 node-run 集合扫一遍（O(节点)，不是 O(事件)）。那份持久缓存是过早优化，还引入"分歧时以谁为准"的第二副本。**去掉后，状态真相只剩 node-run 一处。** 若将来 resume 延迟真成瓶颈，再加这层缓存，不影响其它设计。

### 3.2 WorkflowDefinition（草稿）→ WorkflowPublishedVersion（不可变）

沿用 `design-workflow-server.md` §6.1/§6.2 的草稿/发布快照模型，要点：

```java
@Collection(name = "workflow_definitions")
public class WorkflowDefinition {
    @Id public String id;
    @Field(name = "user_id") public String userId;
    @Field(name = "name") public String name;
    @Field(name = "mode") public WorkflowMode mode;            // WORKFLOW | CHATFLOW
    @Field(name = "graph") public Document draftGraph;         // editable canvas, includes NOTE nodes
    @Field(name = "published_version_id") public String publishedVersionId;
    @Field(name = "updated_at") public ZonedDateTime updatedAt;
}

@Collection(name = "workflow_published_versions")
public class WorkflowPublishedVersion {
    @Id public String id;
    @Field(name = "workflow_id") public String workflowId;
    @Field(name = "sha256") public String sha256;             // pins config; verified on every run/resume
    @Field(name = "graph") public Document graph;             // NOTE excluded; dominator tree precomputed
    @Field(name = "env_vars") public Map<String, TypedValue> envVars;
    @Field(name = "agent_snapshots") public Map<String, Document> agentSnapshots;  // embedded AgentPublishedConfig per AGENT node — no agent_id drift
    @Field(name = "tool_digests") public Map<String, String> toolDigests;          // metadata digest, never secrets
    @Field(name = "published_at") public ZonedDateTime publishedAt;
}
```

### 3.2.1 公开发现 + 运行 + 克隆（discover + run + clone）

**决策 D2：publish 即 public，不引入独立可见性字段。** `published_version_id != null` 本身就是持久化、可查询的"已公开"信号——草稿私有，一旦发布即对全体可见，与 `AgentDefinition.status = PUBLISHED` 同构。无需新增 `is_public`/`visibility` 字段；视图层的 `status` 由 `publishedVersionId != null` 算出。

**访问分层（对已发布 workflow，非 owner 能 run/clone，不能改）：**

| 动作 | owner | 其他用户（仅当已发布） | 落点 |
|---|---|---|---|
| list / discover | 自己的全部 | 见 `my=false` 分支 | `list(userId, myWorkflows)` |
| open / view | 可编辑草稿 | **只读**，渲染冻结的已发布 graph | `getReadable(id, userId)` |
| run | ✓（含 draft preview） | ✓ 跑已发布版本，**run 归属调用者** | `createRun`（run.userId = caller） |
| clone | ✓ | ✓ 复制到自己名下 | `clone(sourceId, userId)` |
| update / publish / delete | ✓ | ✗ `ForbiddenException` | `get(id, userId)`（严格 owner） |

**列表查询对照 `AgentDefinitionService.list(userId, myAgents)` 的分支：**

```java
public List<WorkflowDefinition> list(String userId, Boolean myWorkflows) {
    if (myWorkflows != null && !myWorkflows) {                 // discover：别人的已发布
        return definitionCollection.find(Filters.and(
            Filters.ne("user_id", userId),
            Filters.ne("published_version_id", null)));         // ne null = 存在且非空 = 已发布
    }
    return definitionCollection.find(Filters.eq("user_id", userId));  // null/true：自己的（草稿 + 已发布）
}
```

前端两次拉取（`my=true` 我的 + `my=false` 发现区），与 Chat 页对 agent 的 `list(true)+list(false)` 合并同构。`published_version_id` 单独建索引（migration `20260624001`），否则 notablescan 环境下跨用户查询走 COLLSCAN 报 500。

**运行：非 owner 直接跑已发布版本，run 归属调用者。** `createRun` 放开归属校验——只要已发布，任何用户都能跑；run 行的 `user_id` 是调用者，因此 `getRun`/`listRuns`/`listNodeRuns`/`resume` 这些 run 级 owner 校验天然只让调用者看见自己的 run（无需改）。未发布仍私有：非 owner → `ForbiddenException`，owner → `BadRequestException("not published")`。owner 的 draft preview（`createPreviewRun`）保持 owner-only。

```java
public WorkflowRun createRun(String workflowId, String input, TriggerType triggeredBy, String userId) {
    var definition = definitionCollection.get(workflowId).orElseThrow(...);
    if (definition.publishedVersionId == null) {                // 未发布 = 私有
        if (!definition.userId.equals(userId)) throw new ForbiddenException(...);
        throw new BadRequestException("workflow is not published");
    }
    var version = versionCollection.get(definition.publishedVersionId).orElseThrow(...);
    return insertRun(definition, version, input, triggeredBy, userId);   // run.userId = 调用者
}
```

**只读打开：** `getReadable` 让非 owner 用已发布 graph 渲染只读画布（前端据 `WorkflowView.editable=false` 锁掉拖拽/连线/Save/Publish/配置面板，只留 Run + Clone）；owner 仍拿可编辑草稿。**永不**把 owner 未发布的实时草稿暴露给他人：

```java
public WorkflowDefinition getReadable(String id, String userId) {
    var d = definitionCollection.get(id).orElseThrow(...);
    if (d.userId.equals(userId)) return d;                      // owner：可编辑草稿
    if (d.publishedVersionId == null) throw new ForbiddenException(...);
    d.draftGraph = versionCollection.get(d.publishedVersionId).orElseThrow(...).graph;  // 只读：换成冻结的已发布 graph（仅内存，不回写）
    return d;
}
```

**克隆：** 复制**冻结的已发布版本**到调用者名下的全新草稿——复制语义与 discover「只暴露已发布」一致，永不泄露未发布改动：

```java
public WorkflowDefinition clone(String sourceId, String userId) {
    var source = definitionCollection.get(sourceId).orElseThrow(...);
    if (source.publishedVersionId == null) throw new ForbiddenException(...);   // 只能克隆已公开的
    var published = versionCollection.get(source.publishedVersionId).orElseThrow(...);
    var copy = new WorkflowDefinition();
    copy.id = UUID.randomUUID().toString();
    copy.userId = userId;                                       // 归属转移到调用者
    copy.draftGraph = published.graph;
    definitionCollection.insert(copy);
    return copy;
}
```

> **遗留丑陋（性质同 §12.2）：** 克隆出的 graph 仍引用原作者的 `agent_id`。克隆者重新发布时，`WorkflowPublishService.captureAgentSnapshots` 校验 agent 归属（`own ∨ system_default`）会失败——克隆是可用的起点，但克隆者需先把 AGENT/LLM 节点换成自己的 agent 才能 republish。后续可选：连引用的 agent 一起克隆 / 放开已发布 agent 的跨用户访问 / run 时直接用版本内嵌的 `agent_snapshots`。

### 3.3 WorkflowRun（Cursor + run 级 CAS；对照 AgentRun + AgentSchedule.nextRunAt）

```java
@Collection(name = "workflow_runs")
public class WorkflowRun {
    @Id public String id;
    @Field(name = "workflow_id") public String workflowId;
    @Field(name = "version_id") public String versionId;
    @Field(name = "version_sha256") public String versionSha256;
    @Field(name = "user_id") public String userId;
    @Field(name = "mode") public WorkflowMode mode;
    @Field(name = "session_id") public String sessionId;      // chatflow only (rides ChatSession)
    @Field(name = "triggered_by") public TriggerType triggeredBy;   // REUSE existing enum
    @Field(name = "status") public RunStatus status;          // REUSE: PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED
    @Field(name = "input") public String input;
    @Field(name = "output") public String output;
    @Field(name = "token_usage") public TokenUsage tokenUsage;      // REUSE
    @Field(name = "error") public String error;
    // ---- run-level CAS lease: the ONLY cross-replica claim; mirrors AgentSchedule.nextRunAt ----
    @Field(name = "claimed_by") public String claimedBy;      // worker id; null when unclaimed
    @Field(name = "lease_until") public ZonedDateTime leaseUntil;   // PENDING rows seeded = now (claimable immediately)
    @Field(name = "started_at") public ZonedDateTime startedAt;
    @Field(name = "completed_at") public ZonedDateTime completedAt;
}
```

### 3.4 WorkflowNodeRun（日志——检查点、值复用、观测；无跨 pod claim）

```java
@Collection(name = "workflow_node_runs")
public class WorkflowNodeRun {
    @Id public String id;                                     // = sha(runId + nodeId + scopePathKey)
    @Field(name = "run_id") public String runId;
    @Field(name = "node_id") public String nodeId;
    @Field(name = "scope_path") public List<ScopeFrame> scopePath;   // STRUCTURED: [] | [{ITER,3}] | [{LOOP,2},{ITER,0}]
    @Field(name = "scope_path_key") public String scopePathKey;     // canonical string for the unique index
    @Field(name = "status") public NodeRunStatus status;            // NEW enum (see below)
    @Field(name = "input_json") public String inputJson;           // resolved selectors snapshotted at dispatch (JSON) -> deterministic replay
    @Field(name = "output") public String output;                  // the memoized, reusable result (JSON)
    @Field(name = "chosen_edge_ids") public List<String> chosenEdgeIds;  // null = NORMAL (all out-edges active); non-null = BRANCH. Edge verdicts DERIVED from this, never stored.
    @Field(name = "child_run_id") public String childRunId;        // AGENT/LLM node: the decoupled child AgentRun id (agent_runs)
    @Field(name = "external_call_ref") public String externalCallRef;  // HTTP/TOOL/MCP external call ref, optional
    @Field(name = "span_id") public String spanId;                 // links Span(SpanType.FLOW)
    @Field(name = "attempt") public int attempt;
    @Field(name = "error") public String error;
    @Field(name = "started_at") public ZonedDateTime startedAt;
    @Field(name = "completed_at") public ZonedDateTime completedAt;
}
// UNIQUE INDEX (run_id, node_id, scope_path_key): the RUNNING insert is race-safe / idempotent
// — same idiom as IngestService.saveSpan catching the 11000 duplicate-key error.
```

```java
public enum NodeRunStatus {                       // distinct from RunStatus, which lacks SKIPPED/FAILED_RETRYABLE
    @MongoEnumValue("RUNNING") RUNNING,
    @MongoEnumValue("COMPLETED") COMPLETED,
    @MongoEnumValue("SKIPPED") SKIPPED,
    @MongoEnumValue("FAILED_RETRYABLE") FAILED_RETRYABLE
    // WAITING reserved for deferred human-input — checkpoint infra already supports it
}
```

> **检查点 = 一条 `WorkflowNodeRun` 写入**，同时携带可复用的 `output` 与 `chosen_edge_ids`——一条记录在恢复时同时重放"值复用"和"控制流"（边裁决由 planner 从 `chosen_edge_ids` 重新推导）。
> 输出 > 16MB 或 `FILE` 类型的值，外溢到现有 `FileRecord` artifact 存储，node-run 只存指针。

### 3.5 run 级 CAS 认领（逐字照搬 AgentScheduler.processSchedule 习惯）

`WorkflowRunnerJob`（`schedule().fixedRate(...)`，对照 `AgentSchedulerJob`）找出可认领的 run（`status ∈ {PENDING,RUNNING} AND lease_until ≤ now`），逐个整体 CAS 认领：

```java
ZonedDateTime now = ZonedDateTime.now();
long claimed = workflowRunCollection.update(
    Filters.and(
        Filters.eq("_id", run.id),
        Filters.in("status", RunStatus.PENDING, RunStatus.RUNNING),
        Filters.lte("lease_until", now)),                     // unclaimed (PENDING seeded now) OR expired lease
    Updates.combine(
        Updates.set("claimed_by", workerId),
        Updates.set("status", RunStatus.RUNNING),
        Updates.set("lease_until", now.plusSeconds(LEASE_SECONDS))));
if (claimed == 0) return;                                     // another replica owns it — same as scheduler's updated==0
```

- **独立 heartbeat**：专用定时线程每 `LEASE_SECONDS/3` 续 `lease_until`，条件仅为 `claimed_by == workerId`——**绝不**挂在节点完成上，否则一个慢 AGENT/LOOP 节点会触发误抢占。
- **跨副本 cancel** = Mongo 里置 `status=CANCELLED`；`advance` 每个 `plan` tick 检查；**不**碰进程内 `runningFutures` map（遵守已知约束）。

### 3.6 恢复算法——没有恢复代码（re-fold）

```
1. WorkflowRunnerJob 发现 status=RUNNING AND lease_until ≤ now（原 owner 崩了），用同一 CAS 整体重认领。
2. 校验 version_sha256 与钉死的发布版本一致。
3. 调 advance(run)。planner.plan() fold 现有 node-run：
     - COMPLETED node-run 是事实 -> output 复用，永不重派（readiness 谓词跳过它）。
     - RUNNING 但无终态 = 崩溃时在飞：
         * 幂等 executor（IF/ELSE、AGGREGATOR、START/END） -> 丢弃，重新入 frontier（安全、确定）。
         * 非幂等 executor（AGENT/LLM/CODE/HTTP） -> 写 FAILED_RETRYABLE；run 暂停等人工 retry
           （retry = 在 attempt+1 上新建 RUNNING node-run）。
4. plan() 恢复出完全相同的 frontier。零专用恢复分支。
```

确定性重放的依据：每个节点派发时已把**解析后的输入快照**进 `input_json`，所以上游被重投影不会悄悄改变下游输入。

---

## 4. 变量模型

```java
public final class VariablePool {                 // a DERIVED projection of node-runs + env/sys/conversation, not separately stored
    enum Scope { ENV, SYS, CONVERSATION, NODE, LOOP }
    record TypedValue(VarType type, Object value) {}
    enum VarType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, FILE }
    private final VariablePool parent;            // container child -> parent chain; inner scope shadows outer
    Optional<TypedValue> resolve(String selector);            // e.g. "nodes.http1.output.body.userId"
    VariablePool childScope(Map<String, TypedValue> locals);  // container scoping (loop.item / index / carry)
}
```

| 作用域 | selector 前缀 | 生命周期 | 来源 |
|------|------|------|------|
| Environment | `env.*` | 每个发布版本（不可变） | `WorkflowPublishedVersion.envVars`（快照，无 secret） |
| System | `sys.*` | 每次 run | `sys.run_id`、`sys.user_id`、`sys.query`、`sys.mode`、`sys.session_id`、`sys.now` |
| Conversation | `conversation.*` | 每个 **session**，跨 run（chatflow） | `ChatSession.conversationVars` |
| Node output | `nodes.<id>.output.<field>` | 每次 run | 所在作用域 COMPLETED node-run 的投影 |
| Loop locals | `loop.item / loop.index / loop.carry` | 每个容器 frame | 容器播种进子池 |

- **中心承诺**：**边携带"活性（liveness）"，池携带"值（values）"；读池是节点间唯一的耦合。** 没有携带值的边——这正是支配校验**足够**的原因。
- **selector 解析**：点分路径；沿 `scopePath` 链由内向外解析（`[ITER:3]` 内的节点看见自己的 `loop.item` 并遮蔽外层，同时仍可读外层 `nodes.X.output.*`）。

### 4.1 发布期支配校验（跨分支正确性基石）

```java
void validate(WorkflowGraph g) {
    Map<Node, Set<Node>> dom = computeDominators(g);          // START dominates all
    for (Node y : g.nodes())
        for (Selector s : y.referencedNodeSelectors())        // selectors of form nodes.X.output.*
            require(dom.get(y).contains(g.node(s.nodeId())),
                "node %s reads %s but %s is not on every path to %s — insert a VARIABLE_AGGREGATOR"
                    .formatted(y, s.nodeId(), s.nodeId(), y));
    typeCheck(g);   // declared output type of nodes.X.output.f must satisfy Y's declared input type
}
```

**三件套 = 一个保证**：发布期**支配检查** + 运行期**skip 传播** + **AGGREGATOR 节点支配 join**，使跨分支读成为"发布期报错（还提示插聚合节点）"，运行期永不 NPE。一次算好，钉进 sha256，运行期零成本。

### 4.2 Chatflow 会话变量

run 启动时 `conversation.*` 从 `ChatSession.conversationVars`（新字段，紧挨现有 `artifacts`/`loadedTools`）水合；run COMPLETED 时把变动的 `conversation.*` 切片经 `chatSessionCollection.replace` 刷回（复用 `AgentSessionManager` 持久化）。它们跨 run 存活，因为活在 **session** 上而非 per-run 日志。Workflow 模式只是空的 conversation 作用域——零代码差异。

---

## 5. 单一 NodeExecutor 接口

```java
public interface NodeExecutor {
    NodeType type();                              // dispatch key; registered like ToolRefResolver on ToolSourceType
    NodeOutcome execute(NodeContext ctx);         // the ONLY effectful method; the engine never inspects output
}

public final class NodeContext {
    public final WorkflowNode node;               // static config (Document)
    public final List<ScopeFrame> scopePath;
    public final VariablePool pool;               // resolve inputs via selectors
    public final Document resolvedInput;          // selectors pre-resolved by the runner -> snapshotted to input_json
    public final ExecutionContext exec;           // REUSE core-ai ExecutionContext (sandbox, llm, userId, streamingCallback)
    public final SubFold subFold;                 // ONLY containers use it: re-enter advance() over a sub-graph
}

public sealed interface NodeOutcome {
    record Normal(Document output) implements NodeOutcome {}                            // -> all out-edges ACTIVE
    record Branch(Document output, List<String> chosenEdges) implements NodeOutcome {} // -> chosen ACTIVE, rest SKIPPED
    record Fail(String error, boolean retryable) implements NodeOutcome {}             // -> FAILED_RETRYABLE | FAILED
}
```

runner 把 selector 解析成 `resolvedInput`（快照），调 `execute`，把 `NodeOutcome` 翻译成一条 `WorkflowNodeRun` 检查点。**新增节点类型 = 加一个 executor + 在 `Map<NodeType, NodeExecutor>` 注册；引擎、实体、planner、恢复全不动。**

| 节点 | `execute()` 摘要 | Outcome | 幂等 |
|------|------|------|------|
| **START** | 读 input/触发 payload → `sys.*`/`env.*` 入池 | `Normal(input)` | 是 |
| **END** | 解析最终 selector → run 输出 | `Normal(output)`；无出边即终端 | 是 |
| **ANSWER**(chatflow) | 经现有 SSE `StreamingCallback` 流式 | `Normal(text)` | 尽力* |
| **AGENT** | `AgentRunner.run(snapshotDef, …)` 起**解耦子运行**，await 终态，collect output（见 §5.1）——**不**内联 agent 循环 | `Normal(result)` | **否** |
| **LLM** | 同 §5.1，`DefinitionType.LLM_CALL` 的子运行 | `Normal(text)` | **否** |
| **CODE** | `sandbox.execute("run_python_script", args, exec)` via SandboxService | `Normal(stdout/return)` | **否** |
| **HTTP** | core-framework `HTTPClient`；selector → 请求 | `Normal({status,body,headers})` | **否** |
| **IF/ELSE** | 在池上求值条件 | `Branch(empty, [chosenEdgeId])` | 是 |
| **AGGREGATOR** | 取第一个未跳过的上游值 | `Normal(merged)` | 是 |
| **ITERATION** | `ctx.subFold.mapParallel(subGraph, items, parallelCap)` | `Normal([childEnds])` | 递归 |
| **LOOP** | `ctx.subFold.foldSequential(subGraph, carry, exitCond, maxIter)` | `Normal(finalCarry)` | 递归 |
| **NOTE** | 不是 executor——加载时排除出图 | — | — |

\* ANSWER 在中途崩溃后重放会重发已发出的 token——见 §9 残留丑陋。

### 5.1 AGENT / LLM 节点 = 解耦子运行（submit / await / collect）

AGENT 与 LLM 节点**不**把执行内联进 workflow worker，而是经 `AgentRunner.run()` 起一个**独立子运行**（`AgentRun`，`DefinitionType=AGENT|LLM_CALL`），等终态、收结果。agent 怎么跑（沙箱、工具、子 agent）全是子运行自己的事，workflow 不碰。

```java
// AgentExecutor / LlmExecutor.execute(ctx) — both share this contract
AgentDefinition def = transientFrom(version.agentSnapshots.get(node.id));   // pinned snapshot, never the draft -> no drift
String childRunId = agentRunner.run(def, ctx.resolvedInput, TriggerType.WORKFLOW, ctx.pool.asVariables());
nodeRuns.setChildRunId(ctx, childRunId);                                     // persist the link BEFORE awaiting
RunStatus s = awaitTerminal(childRunId);                                     // poll agent_runs (durable truth); in-proc future for fast wakeup
AgentRun child = agentRunCollection.get(childRunId);
return s == RunStatus.COMPLETED ? new Normal(collect(child)) : new Fail(child.error, false);
```

- **为什么 AGENT 和 LLM 都走子运行**：统一观测（各自 `traceId`/transcript/token usage，两层 trace 用 `child_run_id` 串）、统一恢复、统一 cancel。`AgentRunner` 本就同时支持两种 `DefinitionType`，零额外成本。
- **"call agent 的 API"= 调 run 入口契约**，不必是真 HTTP。单体内是进程内同契约调用（与 `POST /api/agents/{id}/runs` 同契约），零 HTTP 开销；将来 agent 运行时拆成独立服务，这里换远程调用即可，契约不变。
- **await 套进现有循环**：这就是个"较慢的 `runNode`"，复用 §2.3 的 `inflight` + `awaitAnyCompletion`，不需要新机制。代价：等待期间占 workflow 池一个线程（阻塞在 I/O 等待，便宜）。
- **cancel / timeout**：转发 `AgentRunner.cancel(child_run_id)`（同 pod 内存生效；跨 pod 经 Mongo `status=CANCELLED`）。
- **恢复**：AGENT/LLM 是非幂等节点，其"副作用"= 起了一个子运行。崩溃在飞 → 标 `FAILED_RETRYABLE`，子 `AgentRun` 变 stale；手动 retry = `attempt+1` 起一个新子运行。
- **快照**：传进 `run()` 的是从 `version.agentSnapshots` 嵌入的 `AgentPublishedConfig` 快照构造的 transient definition，**不读 agent 当前草稿**——防版本漂移。

### 5.2 Sandbox 与隔离边界（对标 Dify）

**编排器本身不在沙箱里**——`Planner`/`WorkflowRunner`/变量解析/IF 求值是可信平台控制逻辑，跑在 server JVM（同 `AgentScheduler`）。**引擎编排沙箱化的执行，但自己不被沙箱化。** 沙箱是每个 executor 自己的事，经已有的 `ExecutionContext.sandbox`（`NodeContext.exec` 已携带）懒注入，引擎无感。

| 节点 | 隔离方式 | 谁负责 |
|------|------|------|
| START/END/IF/AGGREGATOR/容器控制 | 无 | JVM 内纯计算 |
| **AGENT / LLM** | **委托给子运行自己的沙箱** | agent 子系统（`AgentRunner` → `SandboxService`，按子 runId）。workflow 完全不碰 |
| **CODE** | **无状态沙箱（Dify `dify-sandbox` 式）**：JSON 进/出，受限库、seccomp、超时、资源限制，**无共享 FS** | `core-ai-sandbox-runtime` / `SandboxProvider`；`sandbox.execute("run_python_script", …)` |
| **HTTP** | **SSRF 出口策略 / allowlist**（Dify 用 SSRF 代理），非进程隔离 | executor 内做出口校验 |
| **TOOL**（builtin shell/file、MCP、API） | 复用现有拦截 | `ToolExecutor.shouldIntercept(toolName)` |

**关键设计（按 Dify）：CODE 节点无状态——数据（含文件）只走变量池，不走沙箱 FS。** 文件用 `VarType.FILE` 走 `FileRecord`（平台按引用取），与 Dify 的 File 变量一致。这样：(a) 和引擎"池是唯一耦合"原则一致；(b) **沙箱 FS 持久化问题不存在**（没有 FS 状态可丢，CODE 节点的持久契约就是 output）；(c) 跨节点传数据走池/FileRecord，不依赖 `/tmp`。底层容器复不复用是实现细节，只要对外契约无状态。

> **结论**：workflow 引擎**直接**负责的沙箱只有 **CODE 节点（无状态）**；AGENT/LLM 的沙箱全权交给子运行；HTTP 是网络策略；TOOL 复用现有拦截。
> **effective config**：`WorkflowPublishedConfig.sandboxConfig`（workflow 级，给 CODE）+ CODE 节点可选 `sandbox_override`，复用 `AgentSandboxConfig`（`memoryLimitMb/cpuLimitMillicores/timeoutSeconds/networkEnabled/environmentVariables/…`）和 `getEffectiveConfig` 范式；`env.*` 可注入成沙箱 env vars。

### 5.3 Agent 产物（artifact）交给下游

> **实现状态：L1 + L2 已落地。** `ArtifactRef`（domain）/ `AgentRunResult.artifacts` / `MongoAgentRunGateway` 映射 / `NodeOutcome.Normal.artifacts` / `WorkflowNodeRun.artifacts` / `VariablePool` 的 `nodes.<id>.artifacts` 通道 + List 下标 / `OutputComposer.composeArtifacts` + AGGREGATOR 并集 / `WorkflowRunner.collectArtifacts` → `WorkflowRun.artifacts` / 前端 `ArtifactView` + 变量选择器 + RunTrace 下载链接，均已实现并有测试。L3 不做（见下三决策）。**→ 2026-06 修订：入向消费与 END 交付物两处决策被 §5.3.2 推翻/收紧，以 §5.3.2 为准。**

**断点（现状）**：artifact 产出链已完整——agent 在沙箱生成文件 → `submit_artifacts` → `FileService.upload` 得 `file_id` → 组 `AgentRunArtifact`(file_id/file_name/content_type/size/source_path/title/description) → append 到**子 `AgentRun.artifacts`**。但 workflow 层断了：`MongoAgentRunGateway.awaitResult` 手握整个 `child` 却只 `return child.output`；`AgentRunResult` 只带 (completed/output/error)；于是 `NodeOutcome.Normal(output)` → `WorkflowNodeRun.output`（String）→ 池只有 `nodes.<id>.output`。**artifact 从未被抬到节点层，下游不可见。** 要交给下游，本质是补这条「抬升 + 暴露」链路。

**模型决策（对齐 §4 / §5.2）**：文件以**引用**进池、不进沙箱 FS——这正是 §4 `VarType.FILE` 走 `FileRecord`、§5.2「数据含文件只走池」的落地。给 AGENT 输出开**独立 `nodes.<id>.artifacts` 通道**，而非把 output 改成 `{text,artifacts}` 信封：保 <code v-pre>{{ nodes.x.output }}</code> = 文本回复的现有契约不破，且与 agent 自身 (reply 文本 / artifact list) 的天然二分对称，并能泛化给将来产文件的 HTTP/CODE。绝不把字节塞进池。

池中引用形态（JSON 数组）：
```json
[{ "file_id":"…","file_name":"report.pdf","content_type":"application/pdf",
   "size":12345,"url":"https://…/api/files/<id>/content","title":"…","description":"…" }]
```

**L1 收集 + 通道**（精确改动）：
```java
// 1) lean 引用 record：由 AgentRunArtifact + publicUrl 映射；url 复用 SubmitArtifactsTool 的绝对 download_url 规则
record ArtifactRef(String fileId, String fileName, String contentType, Long size, String url, String title, String description) {}
record AgentRunResult(boolean completed, String output, String error, List<ArtifactRef> artifacts) { … }

// 2) gateway 零额外查询——child 已在手
return child.status == COMPLETED
    ? AgentRunResult.completed(child.output, ArtifactRef.from(child.artifacts))   // child.artifacts 直接读
    : AgentRunResult.failed(…);

// 3) outcome 携带（runner 持久化 node-run 时落库；泛化为「节点产物」，将来 HTTP/CODE 同走）
record Normal(String output, String childRunId, List<ArtifactRef> artifacts) implements NodeOutcome { … }

// 4) 日志层新增字段（紧挨 output；display-only，不参与边裁决）
class WorkflowNodeRun { … public List<ArtifactRef> artifacts; }

// 5) 池新增命名空间 + 数组下标解析
class VariablePool {
    private final Map<String, String> nodeArtifacts;   // nodeId -> JSON array string，与 nodeOutputs 并列
    // resolve: "nodes.<id>.artifacts(.<index>.<field>)?" -> 走 nodeArtifacts；navigate 补 List 下标（artifacts.0.url）
}
```
> 子任务：`VariablePool.navigate` 目前只走 `Map`，要补 `List` 下标（`parts[i]` 为整数时索引 list），否则 `artifacts.0.url` 取不到，只能整取数组。
>
> 特判（L1 必须同时补）：`AggregatorExecutor` 在合并 output 的同时，对各 input 的 artifacts 做 `file_id` 去重**并集**，作为聚合节点自己的 `artifacts` 产出——否则并行多 agent 产文件在 fan-in 处静默丢失。

**L2 传到 workflow 输出**（用户可见价值最高）：END / `OutputComposer` 汇总时把沿活路径 COMPLETED 节点的 artifacts **并集**挂到 run 结果（新增 `WorkflowRun.artifacts`，形态同 `ArtifactRef`），API/chat/A2A 调用方才真正拿到下载链接。去重键 = `file_id`（同一文件被多节点引用只交付一份），顺序按节点完成序。

**L3 沙箱落地——已决定不做**：三个开放问题（见 §5.3.1）已拍板全走引用/URL，不引入「预拉文件进消费方沙箱按 path 读」。理由：与 §5.2「CODE 无状态、文件走引用」的北极星一致，且引用通道已覆盖全部下游类型。`stage_artifacts` 之类的预拉配置作为已知逃生舱**记录在案但不实现**，仅当将来出现引用通道确实解不了的文件加工流水线再回头评估。**→ 2026-06：该逃生舱被行使，但形态比 `stage_artifacts` 配置更收敛——按引用自动推导的平台 staging，见 §5.3.2。**

### 5.3.1 下游消费矩阵（三决策已定）

前提：变量池按 **node id 全局可读**（同作用域所有 COMPLETED node-run），**不沿边携带**——下游在控制流上决定活性，在数据访问上任何被 agent **支配**的节点都能读 `nodes.agent.artifacts`。问题实质是「每种消费方拿什么、什么形态」：

| 下游节点 | 要 agent 的什么 | 形态 | L1+L2 后 | 缺口 / 特判 |
|---|---|---|---|---|
| **END** | 文本 + 把文件交付调用方 | `output` 文本 + `artifacts` 引用 | ✅ <code v-pre>{{nodes.a.output}}</code> + `WorkflowRun.artifacts` 并集 | 无——L2 即为它 |
| **AGGREGATOR** | 多并行分支（含 agent）合并 | 输出合并 + **artifacts 并集** | ⚠️ 现仅合并 output | **L1 同步补**：`file_id` 去重并集（见上） |
| **IF_ELSE** | 读 agent 文本/字段判路由 | 条件读 `nodes.a.output.<field>` | ✅ 文本条件可用 | 不消费文件；跨分支读 `artifacts` 同受**支配校验**——artifact 选择器要纳入 `referencedNodeSelectors` 扫描 |
| **HTTP** | 转发/投递文件 | url/file_id 模板进 url\|header\|body | ✅ 引用模板（VariableChipField 已支持） | **决策②：只投递链接**，不做「从 url 取流再 multipart 上传字节」（如需另列独立特性） |
| **MCP_TOOL / API_TOOL** | 工具消费文件 | 参数 = url 或 file_id（VariableMapEditor 映射） | ✅ 工具接 url/id 即可 | 工具若要 path/bytes 看工具契约；多数接 url/id |
| **CODE** | 脚本处理文件 | map url → 脚本内 `urllib` fetch | ✅ **决策③：靠沙箱出网取 url** | 要求 CODE 沙箱 `networkEnabled` 且可达 FileService 域；落到 §5.2 SSRF 出口边界 |
| **AGENT / LLM** | 下游 agent 读/再加工文件 | **决策①：prompt 内嵌 url**（<code v-pre>处理文件：{{ nodes.a.artifacts.0.url }}</code>），下游 agent 用自带 fetch/browse 工具去取 | ✅ url-in-prompt | 前提下游 agent 具备取文件能力；agent 入参仍是纯 string prompt，不新增「文件入口」 |

**三决策（已拍；2026-06 修订见 §5.3.2）**：
1. **Agent 收文件 = URL-in-prompt**：把取文件交给下游 agent 的工具能力，agent 入参维持纯 prompt，不做沙箱预拉。**→ §5.3.2 修订为平台 staging + path-in-prompt；url-in-prompt 降级为无沙箱 LLM 节点的回退路径。**
2. **HTTP = 投递链接**：模板传 url/file_id；「multipart 上传文件本体」不属 artifact 模型，单列为后续 HTTP 节点增强。**（保留不变）**
3. **CODE/agent 取文件 = 沙箱出网**：消费方沙箱开 `networkEnabled` 用 url 取，统一落到 §5.2 的 SSRF 出口策略，而非 L3 落地。**→ §5.3.2 修订为 staging 进 CODE 沙箱，出网前提随之消解。**

**共同含义**：三决策一致把文件流压成「引用 + 消费方自取」，于是 **L3 整体不需要**；artifact 模型边界清晰——平台只负责把**引用**抬进池/输出，**取字节是消费方（agent 工具 / CODE urllib / 第三方）的事**。唯一新增的平台侧前提是消费方沙箱出网（决策③）需要 §5.2 SSRF 边界覆盖到 FileService 域。

**前端联动（小，接变量选择器）**：`variables.ts` 的 `nodeOutputFields` 给 AGENT/LLM 增 `{ selector: nodes.<id>.artifacts, label:'artifacts', type:'array' }`，选择器/芯片即可直接选到。

**推演**：`reportAgent(AGENT)` 产 `report.pdf` → node-run.artifacts=[{file_id,…,url}] → END `output` 模板可写 <code v-pre>报告：{{ nodes.reportAgent.artifacts.0.url }}</code>，或 workflow 输出直接带 artifacts 数组；HTTP 节点 body 模板 <code v-pre>{ "file": "{{ nodes.reportAgent.artifacts.0.url }}" }</code> 转发。

**测试要点**：(a) gateway 把 `child.artifacts` 映射进 result；(b) 池解析 `nodes.x.artifacts` 整取与 `.0.url` 下标；(c) 多 artifact 顺序与 `file_id` 去重；(d) L2 并集到 `WorkflowRun`；(e) 无 artifact 时 `.artifacts`=`[]`、`.output` 不变（兼容回归）。

**已定决策**：① `WorkflowNodeRun.artifacts` 独立字段 vs 复用 output JSON 嵌字段 → 独立（兼容、对称）；② `ArtifactRef` 新建 lean record vs 直接复用 `AgentRunArtifact`（缺 url 需补算）→ lean record + 映射；③ L2 去重键 `file_id` vs `(node_id,file_id)` → `file_id`；④ Agent 收文件 → URL-in-prompt（**§5.3.2 修订**）；⑤ HTTP → 投递链接（不做 multipart 字节上传）；⑥ CODE/agent 取文件 → 沙箱出网（§5.2 SSRF 边界覆盖 FileService 域），不做 L3 沙箱落地（**§5.3.2 修订**）。

**唯一未决**：决策⑥意味着 CODE 沙箱默认是否 `networkEnabled`、以及 SSRF allowlist 是否需放行 FileService 内网域名——留到实现 CODE/HTTP 出口策略时一并定。**→ 2026-06：随 §5.3.2 的 staging 修订，文件读取不再依赖沙箱出网，此未决项对文件流转已消解（HTTP 节点自身的 SSRF 出口策略仍按 §5.2 推进）。**

### 5.3.2 文件流转 v2（2026-06 修订）：入向 staging + END 交付物

> **实现状态：A + B 已落地（2026-06-11）。** A：`ArtifactStaging`（引用闭包推导 + 路径约定 + 文件名消毒）/ `VariablePool.stagedView()` 注入瞬态 `path` / `SandboxService.addStagedFile` + `PendingFile` 双源（blob / FileRecord）/ `AgentRunner.WorkflowRunContext`（trace + stagedFiles，agent loop 前 `ensurePendingFilesUploaded` 同步确保、失败即 run FAILED）/ `AgentExecutor`（AGENT 节点 staging + path 渲染，LLM 回退 url）/ `CodeExecutor`（同通道，执行前 uploadFile，失败 Fail(retryable)）。B：`EndExecutor` 调 `OutputComposer.composeDeliverables`（默认前驱并集 + 显式 `artifacts` 选择器覆盖）/ `WorkflowRunner` 改取 END node-run 的 output+artifacts（删除全图 union `collectArtifacts`）/ 前端 RunTrace 交付物卡片（图标/大小/下载/图片预览）+ WorkflowRuns 文件数徽标。测试：`ArtifactStagingTest`（8）+ `EndExecutorTest`（4）。注意：staging 失败的确定性依赖 `ensurePendingFilesUploaded` 的同步调用路径——`LazySandbox.runPostAcquireHook` 会吞异常，不能只靠 onReady 钩子。
>
> **→ 2026-06-23 修订（交付物 v2.1）：** (1) **output 引用即交付**——把 `output` 模板里 <code v-pre>{{ nodes.&lt;id&gt;.artifacts }}</code> 引用到的文件按同款粒度（整数组/整对象/`.path` → 交付；`.url`/元数据 → 不交付）抬进交付物，粒度判定抽进 `ArtifactStaging.referencedFiles`（与入向 staging 共用一套规则）；(2) **提升下沉 `composeArtifacts`**——END 默认交付与 AGGREGATOR 共用同一操作，aggregator `output` 引用的非前驱文件也随之传播下游，消除两者不对称；(3) **显式 `artifacts` 清单恢复权威**——非空即「就这些」，不再被 output 引用强行扩宽，用户可收窄；(4) **END 配置面板暴露交付物选择器**（前端 `DeliverablesField`，写 `config.artifacts`，按 `nodes.&lt;id&gt;.artifacts` 形状过滤、残留项可删）。测试增量：`EndExecutorTest`（4→7）+ `AggregatorExecutorTest`（+1）。

**修订动机（两处实践暴露的问题）**：

1. **「消费方自取」把确定性管道交给了概率组件。** 决策①/③ 让下游 agent 用工具 fetch URL、CODE 脚本 `urllib` 拉文件——每个文件多花 1–2 轮 tool call 的 token 与延迟，且可能失败/跳过/写错路径；同时强依赖「消费方沙箱可出网达 FileService 域」这一始终未验证的前提（§5.3.1 唯一未决项）。原则修正：**确定性搬运归平台，LLM 只做决策**。引用进池的北极星不变（§4/§5.2 全保留），修订的只是最后一公里——**引用→字节的物化，从消费方 LLM 的责任改为平台的责任**。这正是 §5.3 预留的逃生舱首次行使，但形态更收敛：不是 `stage_artifacts` 手工配置，而是**按引用自动推导**。
2. **END 缺「交付物」概念。** 现状两处不一致：`EndExecutor` 只调 `compose()` 不调 `composeArtifacts()`——与 §5.3.1「END/AGGREGATOR 同一操作两个位置」的自我声明矛盾；run 级 `collectArtifacts` 做**全图** union——中间节点的草稿/临时文件全部混进最终交付，调用方无法区分「过程产物」与「结果」。

#### A. 入向 staging（修订决策①③⑥）

一个平台组件、两类消费者：

```java
// ArtifactStager（平台侧，确定性；与 SandboxService.uploadPendingFiles 同构——chat 文件上传已验证此模式）
// 输入：消费方节点 input 渲染中引用到的 ArtifactRef 集合
// 动作：FileService 取字节 -> sandbox.uploadFile("/tmp/inputs/<srcNodeId>/<fileName>", bytes)
// 时机：sandbox 就绪后、首轮执行前（复用 pendingFiles 的延迟队列时序，兼容 LazySandbox）
```

- **staging 集合 = 显式引用闭包**：发布期 `SelectorScanner` 已扫描 input 模板中的 `nodes.*.artifacts` 选择器（支配校验同款扫描），运行期把这些选择器命中的 `ArtifactRef` 作为 staging 集。**不引用不搬**——意图由 wiring 表达，集合有界且确定。引用粒度细化规则：引用**整数组/整对象/`.path` 字段** → stage；仅引用 `.url`/元数据字段 → 不 stage（意图是转发链接，非本地消费）。
- **路径约定**：`/tmp/inputs/<sourceNodeId>/<fileName>`——按源节点分目录防同名冲突；路径是纯函数（不依赖沙箱已存在），故渲染 prompt 时即可算出，无需等沙箱就绪。
- **渲染形态**：`nodes.<id>.artifacts` 在**有沙箱的消费方**上下文渲染时，每个 artifact 对象注入 `"path"` 字段（`url` 等保留）。`path` 是 per-consumer 瞬态字段，**不落库**——`ArtifactRef`/Mongo 形态不变。
- **消费方矩阵更新**：

  | 消费方 | v2 行为 | 原决策处置 |
  |---|---|---|
  | AGENT（有沙箱） | staging + **path-in-prompt**（<code v-pre>处理文件：{{ nodes.a.artifacts.0.path }}</code>），agent 直接读本地文件，内容不灌 context | 决策①修订；url-in-prompt 降级为回退 |
  | LLM（无沙箱） | url-in-prompt（原决策①原样保留为回退路径） | 不变 |
  | CODE | staging 进 CODE 沙箱，脚本按 path 读。**不破坏「CODE 无状态」**：staged 文件是调用入参的一部分（同 JSON 入参），执行间不残留状态，契约仍是 JSON 进/出。**收益：CODE 取文件不再要求 `networkEnabled`/SSRF 放行 FileService——§5.3.1 唯一未决项消解** | 决策③⑥修订 |
  | HTTP / MCP_TOOL / API_TOOL | 传 url/file_id，不变 | 决策②保留 |
  | END / AGGREGATOR / IF | JVM 内纯计算，无沙箱无 staging | 不变 |

- **失败语义**：staging 失败 = 节点失败（`retryable=true`，走 `RetryingNodeExecutor`）——确定性失败优于 agent 缺文件半瞎跑。
- **尺寸护栏**：staging 经 server 中转字节；当前 FileService = Mongo base64，本身有 16MB/doc 上限，v2 不引入新瓶颈。预留每节点 staging 总量上限配置（超限 fail-fast 并提示改走 url 转发），FileService 将来换 blob 存储时该护栏才真正承压。
- **出向不变**：agent 产文件仍走 `submit_artifacts` → `FileService.upload` → `ArtifactRef` 抬升；staging 只管**入向**。两条方向合起来：**平台负责字节的进出，池只走引用**——§5.2 原则未破，反而更彻底（消费方连 fetch 都不用做了）。

#### B. END 交付物（出口修订）

两层产物模型：

- **过程产物（trace 层）**：`WorkflowNodeRun.artifacts`，节点详情展示，调试/审计用——不变。
- **交付产物（result 层）**：END 节点的 artifacts 即交付物。
  1. `EndExecutor` 调 `OutputComposer.composeDeliverables(ctx)`；默认（无显式声明）= `composeArtifacts(ctx)` = **直接前驱 artifacts 的 `file_id` 去重并集 ∪ END `output` 模板引用到的文件**（2026-06-23 增量：output 引用即交付，粒度同入向 staging；提升逻辑下沉到 END/AGGREGATOR 共用的 `composeArtifacts`，修掉与 AGGREGATOR 的不对称）；
  2. END config 可选 `artifacts` 选择器列表（如 `["nodes.report.artifacts"]`）显式声明交付物——**权威**：就这些、可收窄，**不叠加** output 引用（2026-06-23 修订：早期 v2.1 会强行叠加 output 引用，导致显式清单无法收窄，已改为权威）；
  3. `WorkflowRun.artifacts` = END node-run 的 artifacts，**取代** `collectArtifacts` 的全图 union。全图信息不丢——trace 层逐节点已有，只是不再冒充交付物。

**前端联动**：run 结果面板（RunPanel/RunTrace）在 output 文本下方渲染 artifact 卡片——按 `content_type` 的文件图标、文件名、大小、下载按钮，`image/*` 内联缩略预览；WorkflowRuns 列表卡片加文件数徽标；节点详情继续展示该节点自己的过程产物，与结果面板区分。**END 配置面板新增 Deliverables 多选**（`DeliverablesField`）：候选 = 支配 END 且产文件的上游节点（按 `nodes.<id>.artifacts` 形状过滤，避开同名 `sys.input.artifacts`），勾选写 `config.artifacts`；空 = 默认（前驱 ∪ output 引用），勾选 = 权威清单；已删/断连节点的残留选择器以虚线项展示、可勾掉移除。

#### C. 对称缺口（记录在案，本次不做）

workflow 的**文件输入**：`WorkflowRun.input` 是纯 string，START 节点无文件入口（agent run 经 job 创建已支持文件，workflow 不对称）。将来点亮时直接复用本节机制：启动载荷带文件 → 挂为 `nodes.start.artifacts` → 同一 staging 通道进首个消费节点，零新概念。待有真实场景再做。

**测试要点（v2 增量）**：(a) `SelectorScanner` 提取 artifacts 选择器 → staging 集正确（含 `.0.path` 单文件、整数组、`.url`-only 不 stage 三种粒度）；(b) staged path 渲染与实际上传路径一致（纯函数性）；(c) staging 失败 → 节点 `FAILED_RETRYABLE`；(d) 无沙箱 LLM 节点回退 url 渲染（无 `path` 字段）；(e) END 默认 = 直接前驱并集 ∪ output 引用文件、显式 `artifacts` 选择器为**权威**（压制 output 引用、可收窄）、AGGREGATOR `output` 引用的非前驱文件被提升；(f) `run.artifacts` = END artifacts，中间文件不被 output 引用时不泄漏进交付物；(g) CODE 沙箱关网时仍能读 staged 文件。

**已定决策（v2）**：⑦ Agent 收文件 → 平台 staging + path-in-prompt（无沙箱回退 url）；⑧ CODE 收文件 → 同一 staging 通道（无状态契约不破）；⑨ staging 集 → 按引用自动推导（非手工 `stage_artifacts` 配置）；⑩ 交付物 → END 声明（默认前驱并集 + 可选显式选择器），`WorkflowRun.artifacts` 不再全图 union；⑪ `path` 为渲染期瞬态字段，不落库；⑫（2026-06-23）交付物 = 前驱并集 ∪ output 模板引用文件（粒度同 staging，提升逻辑下沉 `composeArtifacts`，与 AGGREGATOR 共用并传播下游）；显式 `artifacts` 清单为**权威**（可收窄、不叠加 output 引用）；END 配置面板暴露交付物选择器（`DeliverablesField`）。

### 5.4 HUMAN_INPUT 的 API 交互契约（2026-06 修订）

> **实现状态**：①② 已实现（`HumanInputProtocol` + `pending_inputs` + resume 校验）；③④ 规划中。

**本质**：HUMAN_INPUT 对 API 调用方是一个**远程表单**。设计原则——把表单契约作为一等公民暴露为数据，而不是让调用方拼三个接口再解析字符串。

**① PAUSED 自描述（已实现）**。单 run 读取（`GET /workflow-runs/{id}`、`run-sync` 返回）在 PAUSED 时附带 `pending_inputs`（列表——并行分支可能同时有多个 WAITING 节点）：

```json
{ "status": "PAUSED",
  "pending_inputs": [ { "node_id": "n_x", "mode": "approval|input",
                        "prompt": "渲染后的问题", "fields": [ {"name","type","label","required"} ] } ] }
```

- 组装：`WorkflowRunService.pendingInputs` = WAITING node-runs × pinned graph 的节点 config（`HumanInputProtocol.describe`）。prompt 取自暂停时刻的 ask 快照（已渲染），fields 取自 run 锁定的图版本——图不可变，即天然快照，无需把 schema 复制进 ask。
- 列表接口不带（避免 N+1 node-run 查询），只在单 run 读取时组装。

**② resume 校验前置（已实现）**。`HumanInputProtocol.validate` 在 settle 之前执行：approval 模式必须给 `approve` 且不收 `input`（反之亦然）；input 模式按 fields 校验 required/类型（number/boolean/string），坏答案在 HTTP 层 400 报错，而不是下游节点莫名失败。状态语义收紧：run 非 PAUSED / 节点已被并发 resume settle → **409 Conflict**（原 400）。

**③ 出站回调（规划）**。createRun 可选 `callback_url`，run 进 PAUSED/终态时 POST 事件（带 `pending_inputs` 负载 + HMAC 签名 + 退避重试），把轮询变事件驱动。与 Trigger 体系镜像（出站 webhook ↔ 入站 trigger）。

**④ 超时兜底（规划）**。节点 config 加 `timeout_hours` + `on_timeout: approve|reject|fail`，`pending_inputs` 暴露 `expires_at`；实现搭 runner 恢复 job 的周期扫描（PAUSED 且过期 → 按默认决策 resume），不引入新调度器。纯 API 触发的 run 没有"有人盯着页面"的前提，必须有这个兜底。

**前端联动**：ApiAccessPanel 在图含 HUMAN_INPUT 节点时展示 pause→pending_inputs→resume 协议说明，并按实际节点的 mode/fields 生成 resume curl 示例。

**远期**：基于 `pending_inputs` 快照结构做平台级审批收件箱（`GET /api/human-tasks` 列当前用户所有 PAUSED 待办）。

---

## 6. 15 特性 → 每个用到的那一个概念/executor

| # | 特性 | 引擎概念 / executor（除非标注，引擎核心不变） |
|---|------|------|
| 1 | 并行 | `plan` 返回 N 个就绪 → `Semaphore` 门控池。**纯引擎，无节点类型。** |
| 2 | Loop | `LoopExecutor` + `SubFold.foldSequential`。仅 executor。 |
| 3 | CODE 节点 | `CodeExecutor` → SandboxService。仅 executor。 |
| 4 | IF/ELSE | `IfElseExecutor` 返回 `Branch`。仅 executor。 |
| 5 | Note | 画布元数据，排除出图。零引擎。 |
| 6 | Env/Conversation/System 变量 | VariablePool 作用域（§4）。投影。 |
| 7 | Preview / test-run | `WorkflowRun(mode=WORKFLOW, triggeredBy=TEST)`；同一引擎。 |
| 8 | Run history | `workflow_runs` + `workflow_node_runs` 读模型（对照 `AgentRunService`）。 |
| 9 | Share API | 发布版本上的 `http().route()`；对照 `TriggerController`。 |
| 10 | Agent 调 Workflow | `ToolRefResolver` 新增 `ToolSourceType.WORKFLOW` → 一个启动 run 并等待的 ToolCall。Agent→WF→Agent 嵌套。 |
| 11 | Workflow trace | 每条 node-run → `Span(SpanType.FLOW)` via IngestService；`parentSpanId` 来自 scopePath 链。投影。 |
| 12 | Iteration | `IterationExecutor` + `SubFold.mapParallel`。仅 executor。 |
| 13 | Human-input（暂缓） | `NodeRunStatus.WAITING` + `plan` 让出；resume = 新 node-run。**基建已支持，未点亮。** |
| 14 | HTTP-request 节点 | `HttpExecutor`。仅 executor。 |
| 15 | Export-as-image | 前端渲染 Graph JSON。零引擎。 |

**结论**：15 个里 11 个只是"加一个 executor 或外围"；2 个是投影（trace、history）；#10 碰一处已可扩展的 seam；#1 需要改的代码是 **0**。planner 永远不变。

---

## 7. 一套引擎，两种模式

两模式调**同一个** `WorkflowRunner.advance`、跑**同一个** `plan`。全部差异 = 变量作用域接线 + 入口 + 哪个终端节点产出。

| | Workflow 模式 | Chatflow 模式 |
|---|---|---|
| 入口 | manual/API/webhook/schedule → `TriggerAction.execute(trigger, payload)`（现有契约） | `ChatSession` 里每条用户消息 = 一次完整 run |
| `sys.query` | 来自触发 payload | 来自聊天消息 |
| `conversation.*` | 空（各 run 隔离） | 从/向 `ChatSession.conversationVars` 水合/刷回 |
| 输出 | `END` → `run.output`，返回 | `ANSWER` → 现有 SSE `StreamingCallback` |
| `session_id` | null | ChatSession id |

Graph、日志、planner、executor、恢复、CAS **逐字节相同**。模式只是一个二值字段，选择水合哪个作用域、哪个终端节点接出。"有状态聊天机器人引擎"和"单次管线"是**同一个 fold、只切一个作用域**——这是抽象正确的最强证据。

---

## 8. 新类 → 现有 sibling 映射

| 新件 | 对照现有类 | 怎么照搬 |
|------|------|------|
| `WorkflowDefinition` / `WorkflowPublishedVersion` / `WorkflowDefinitionService.publish()` | `AgentDefinition` / `AgentPublishedConfig` / `AgentDefinitionService.publish()` | 不可变快照 + sha256；嵌入 Agent 的 `AgentPublishedConfig` 快照（无 agent_id 漂移） |
| `WorkflowRun`（status + lease） | `AgentRun`（status） + `AgentSchedule.nextRunAt`（CAS 字段） | `RunStatus` 逐字复用；`lease_until` CAS == `next_run_at` CAS |
| `WorkflowNodeRun`（+ 唯一索引） | `Span`（唯一索引、catch-11000 竞态安全插入） | `(run_id,node_id,scope_path_key)` 唯一 == `span_id` 去重 |
| `NodeRunStatus` | （无——`RunStatus` 缺 SKIPPED/FAILED_RETRYABLE） | 新枚举，`@MongoEnumValue` 约定 |
| `WorkflowRunner.advance()` + 进程内池 | `AgentRunner.run()` + `executorService`/`runningFutures` | CompletableFuture 后台；status 经 `collection.replace`；cancel 经 Mongo 状态 |
| `WorkflowRunnerJob`（认领+心跳+清扫） | `AgentSchedulerJob` + `AgentScheduler.processSchedule` | `schedule().fixedRate(...)`；CAS on `lease_until`；`updated==0` 退出 |
| `Planner.plan`（纯 fold） | （无——新纯核，约 200 行） | 零 mock 单测 |
| `NodeExecutor` 注册分发 | `ToolRefResolver` 按 `ToolSourceType` 分发 | `Map<NodeType, NodeExecutor>` 在 module 绑定 |
| `AgentExecutor` / `LlmExecutor` | `AgentRunner.buildAgent()` seam | 不变的"快照 → SDK Agent"翻译 |
| `CodeExecutor` | `SandboxService` + `Sandbox.execute` | `run_python_script` 路径逐字 |
| `node-run → Span(FLOW)` | `IngestService.saveSpan` | `parentSpanId` 来自 scopePath 链，OTLP |
| `ToolSourceType.WORKFLOW` + resolve 分支 | 现有 `ToolSourceType` + `ToolRefResolver` | 新枚举值 + resolve case → 启动 run 的 ToolCall |
| `RunWorkflowAction`（TriggerAction） | `RunAgentAction` | `TriggerAction.execute(trigger, payload)` |
| `WorkflowController`（run/publish/share/history） | `TriggerController` + agent 控制器 | `http().route()`，secret 校验 |
| `ChatSession.conversationVars` | `ChatSession.artifacts` / `loadedTools` | 搭现有 session 持久化 |
| Mongo 注册 | `ServerApp.registerMongo()` | `mongo.collection(WorkflowRun.class)` 等 |
| Job/executor 绑定 | `ServerModule` | 绑 executor + `schedule().fixedRate` |

熟悉 `AgentRunner` + `AgentScheduler` 的人读这套，是一份结构性影印件。

---

## 9. 完整推演示例：扩展版工单分流

**Graph（发布版本，sha256 钉死，NOTE 排除）：**

```
START ──> classify(AGENT) ──┬──> urgent?(IF/ELSE) ──ACTIVE-if-P1──> escalate(HTTP) ──┐
                            │                       ──ACTIVE-else──> autoReply(AGENT) ┤
                            │                                                          ├─ merge(AGGREGATOR) ──┐
                            └──> extractTasks(CODE) ──> perTask(ITERATION over          │                      ├──> END
                                    nodes.extractTasks.output.tasks[])                  │     perTask.output ──┘
                                    子图: doTask(AGENT) -> END(sub)
```

边：`e0` START→classify，`e1` classify→urgent?，`e2` classify→extractTasks，`e3` urgent?→escalate，`e4` urgent?→autoReply，`e5` escalate→merge，`e6` autoReply→merge，`e7` extractTasks→perTask，`e8` merge→END，`e9` perTask→END。

**逐 tick 推演（每行一次 `plan`）：**

| Tick | 就绪集 | 完成后边裁决 | 池增量 | 新建 node-run（status, scope_path） |
|------|------|------|------|------|
| t0 | `{START}`（播种） | — | `sys.query="Login broken, P1"` | START RUNNING `[]` |
| t1 | —（START 完成） | e0=ACTIVE | `nodes.START.output={ticket}` | START COMPLETED `[]` |
| t2 | `{classify}` | e1=ACTIVE, e2=ACTIVE（fan-out, NORMAL） | `nodes.classify.output={priority:"P1",category:"auth"}` | classify RUNNING→COMPLETED `[]` |
| t3 | `{urgent?, extractTasks}` **并发派发** | — | — | urgent? RUNNING `[]`；extractTasks RUNNING `[]` |
| t4 | —（urgent? BRANCH chosen=[e3]） | e3=ACTIVE, **e4=SKIPPED** | — | urgent? COMPLETED kind=BRANCH `[]` |
| t5 | `{escalate}`（e3 ACTIVE） | — | — | escalate RUNNING `[]`；autoReply 被 skip 传播 → **autoReply SKIPPED** `[]`，e6=SKIPPED |
| t6 | —（extractTasks CODE 完成） | e7=ACTIVE | `nodes.extractTasks.output.tasks=["a","b","c"]` | extractTasks COMPLETED `[]` |
| t7 | `{perTask}`（e7 ACTIVE）——**容器** | — | — | perTask RUNNING `[]` → 开子 fold，3 个子作用域（parallelCap=10） |
| t7a | 子：`{doTask}`×3，作用域 `[ITER:0/1/2]` | 子边 ACTIVE | `loop.item="a"/"b"/"c"`（作用域内） | doTask RUNNING/COMPLETED `[ITER:0/1/2]`；子 END COMPLETED `[ITER:i]` |
| t8 | —（escalate HTTP 完成） | e5=ACTIVE | `nodes.escalate.output={status:200}` | escalate COMPLETED `[]` |
| t9 | `{merge}`（e5 ACTIVE, e6 SKIPPED → 全终态, ≥1 ACTIVE） | e8=ACTIVE | `nodes.merge.output={escalated:true,status:200}` | merge RUNNING→COMPLETED `[]` |
| t10 | —（perTask 聚合子 END） | e9=ACTIVE | `nodes.perTask.output=[r_a,r_b,r_c]` | perTask COMPLETED `[]` |
| t11 | `{END}`（e8、e9 均 ACTIVE） | 终端 | `run.output={merge, perTask}` | END COMPLETED `[]` → run COMPLETED |

**展示的关键不变式：**
1. `classify` 的 fan-out 让 `urgent?` 与 `extractTasks` 同时进 frontier（t3），并发派发，无 barrier；
2. 死掉的 IF/ELSE 分支 `autoReply` 变 SKIPPED 并传播 `e6=SKIPPED`，AGGREGATOR `merge` 仍触发（≥1 ACTIVE）——**join 不死锁**；
3. ITERATION 按 `scope_path` `[ITER:i]` 建出互相区分的 node-run，递归同一引擎；
4. 若 t8 处 pod 崩溃，恢复重认领、重 fold：START/classify/urgent?/extractTasks/三个 doTask（COMPLETED）全部复用，只有在飞的非幂等 `escalate` 被标 `FAILED_RETRYABLE`。

---

## 10. 分阶段路线（状态模型一次到位，节点分阶段）

P0 把**状态模型 + planner + 恢复 + CAS** 按 durable/concurrent/container 目标建完；节点类型逐步点亮。每个类 < 500 行，无通配 import，注释/测试英文。

| 阶段 | 落地 | 类（各自聚焦，< 500 行） |
|------|------|------|
| **P0 引擎核（完整目标状态模型）** | Graph、纯 Planner、连续 ready-queue、边裁决推导、skip 传播、run 级 CAS、独立 heartbeat、恢复 re-fold、结构化 `scopePath`、`WorkflowNodeRun` + 唯一索引、`NodeRunStatus` | `WorkflowGraph`, `Planner`, `Frontier`, `WorkflowRunner`, `WorkflowRunnerJob`, `WorkflowRun`, `WorkflowNodeRun`, `NodeRunStatus`, `EdgeVerdict`, `ScopeFrame`, `RunState` |
| **P1 线性 executor + 发布 + Workflow 模式** | START/END/AGENT/LLM（经 `buildAgent` seam）；publish→sha256 版本；支配 + 类型校验；trace span；run history | `NodeExecutor`, `NodeContext`, `NodeOutcome`, `StartExecutor`, `EndExecutor`, `AgentExecutor`, `LlmExecutor`, `WorkflowDefinitionService`, `WorkflowPublishedVersion`, `DominatorValidator`, `WorkflowController` |
| **P2 分支 + 跨分支正确性** | IF/ELSE（`Branch`）、AGGREGATOR；支配+skip+聚合三件套全链路；并行 fan-out 端到端验证 | `IfElseExecutor`, `AggregatorExecutor`, `VariablePool`, `Selector` |
| **P3 容器** | ITERATION（`mapParallel`）、LOOP（`foldSequential` + carry + exit + maxIter）；`SubFold` 再入 | `IterationExecutor`, `LoopExecutor`, `SubFold` |
| **P4 副作用节点 + 嵌套** | CODE（SandboxService）、HTTP（HTTPClient）；`ToolSourceType.WORKFLOW`（Agent→WF as tool） | `CodeExecutor`, `HttpExecutor`, `WorkflowToolResolver`（ToolRefResolver 分支） |
| **P5 Chatflow + 外围** | ANSWER（SSE）、`conversation.*` 载入/持久化；share API；preview/test-run；export-as-image（前端） | `AnswerExecutor`, `ChatSession.conversationVars`, `WorkflowShareController` |

P0 即交付**可持久化、并发、可容器**的引擎，只点亮 START/END；之后每个阶段加 executor（或一处 resolver 分支），**永不改引擎**——分阶段本身就是优雅性的证明。

---

## 11. 测试要求（引擎核）

`Planner.plan` 是纯函数，**零 mock 单测**，这是本设计可测性的核心红利：

- **planner / 谓词**：fan-out 全 ACTIVE；BRANCH 选一其余 SKIPPED；skip 传播；join 在"一活一跳"下触发；全跳时 join 自身 SKIPPED；菱形不死锁。
- **支配校验**：跨分支引用在发布期被拒（含修复提示）；合法的支配引用通过；类型不匹配拒绝。
- **scope_path**：同一子图节点在 `[ITER:0/1/2]` 产出三条互相区分的 node-run。
- **恢复 re-fold**：崩溃后重认领，COMPLETED node-run 复用不重派；在飞非幂等节点标 FAILED_RETRYABLE；幂等节点重入安全。
- **CAS**：lease 未过期时第二 worker `updated==0` 不能接管；过期后可接管；独立 heartbeat 续租不被慢节点拖住。
- **两模式**：同一 graph 在 Workflow / Chatflow 下，仅 conversation 作用域与终端节点不同。

集成测试沿用 `design-workflow-server.md` §16（webhook/schedule/cancel 触发、非幂等不自动 retry 等）。

---

## 12. 为什么优雅 + 残留丑陋（诚实）

### 12.1 为什么优雅

只有**一个纯函数**（`plan` = fold 持久事实 → frontier）、**一个副作用面**（`NodeExecutor.execute`）、**一个持久认领**（run 级 CAS 租约）、**一个递归**（`SubFold` 再入 `plan`）、**一个投影机制**（同时产出控制 frontier 与数据 VariablePool）。并行、分支、join、skip、iteration、loop、恢复、trace、history、两模式，全是这个 fold 的**推论**，不是 bolt-on。边三态**派生、不存储**，因此没有边状态表要维护一致性、没有双写。恢复是代码的**缺席**：重认领、调 `plan`，COMPLETED node-run 是函数读到的事实因而永不重派——免费拿到"副作用恰好一次的意图（exactly-once-intent）"。新增任意 15 特性都是加 executor（或一处 resolver 分支），永不碰引擎；而每个新类都是现有 core-ai-server 习惯的结构性影印件。

### 12.2 残留丑陋（不回避）

1. **非幂等在飞副作用 → `FAILED_RETRYABLE` 人工闸门。** worker 在 `HTTP POST` / CODE 副作用 / AGENT 工具调用中途死掉，无法证明副作用是否已提交；我们隔离并要求人工 retry。这是"恢复永不重复执行"的唯一星号：durable 的是**控制流**恢复，不是**副作用**恢复。（缓解：HTTP/CODE 节点配置可选 idempotency-key，后续做。）
2. **流式 ANSWER 尽力幂等。** 中途崩溃 + 重放会重发已发出的 token；编排仍纯，但"发字节到客户端"这个副作用不完美幂等。与 human-input 一同列为后续项（ANSWER 偏移检查点），现在不做。
3. **whole-run 认领 = 仅 pod 内并行（已知约束 L1）。** 1000 项 ITERATION × 每项一个 AGENT，全跑在一个 pod 的 `Semaphore` 池里，不跨副本摊开。这是锁定的 claim 简化换来的（恢复只有 4 步、零 node 级 claim 竞争），代价是单 run 吞吐上限 = 一个 pod，且租约必须活过最慢的在飞节点（由独立 heartbeat 保证，永不挂在节点完成上）。**将来真顶到天花板时，这里是唯一要回头改的地方。**
4. **观测沿 `scope_path` 与跨 run 分散。** 进程内容器靠结构化 `scopePath` 保持干净；但 **AGENT/LLM 节点各起一条独立 `AgentRun`**（用 `child_run_id` 串），Agent→Workflow-as-tool（#10）又另起独立 `WorkflowRun`。所以"看这次 run"对纯进程内节点是一次查询，对子运行边界则要按 `traceId`（非 `runId`）做 trace 走查。这是"AGENT 节点解耦子运行"换来隔离/复用的代价——两层 run（`WorkflowNodeRun.child_run_id → AgentRun`）必须靠 trace 关联。
5. **P0 暂把 `FAILED_RETRYABLE` 当终态 FAIL。** 上面第 1 点承诺"隔离 + 人工 retry"，但 P0 的 `WorkflowAdvancer.classify` 目前把任何 `FAILED_RETRYABLE` 节点直接判 run 为终态 `FAILED`（盖 `completed_at`），claim filter 只认 `PENDING/RUNNING`，所以 spec 承诺的可恢复 retry 路径**暂不可达**。可恢复 retry 需要一个 `PAUSED` run 状态 + claim 接纳它 + retry 机制，耦合到 P5；在那之前，节点失败=run 终态 FAILED 是**有意的临时行为**，不是无意的。`drive` 不内联 agent 循环，故此约束只影响"失败后重试"语义，不影响正确性。

### 12.3 开放决策点（留给你拍）

- **D1（已在 §3.1 拍为"去掉"）**：是否保留派生 `edge_state` resume 缓存。本文默认**去掉**，node-run 为唯一真相。若你更看重大图 resume 延迟，可翻转。
- **L1**：whole-run 单 pod 并行天花板是否可接受为 v1 约束。本文默认**接受**。
- **#10 嵌套观测**：Agent→Workflow-as-tool 的 run 关联，v1 用 `traceId` 走查是否够，还是要在 `WorkflowRun` 上加 `parent_run_id` 显式串联。

---

*本文为引擎权威设计；平台集成（触发、权限、API、前端、风险）见 `design-workflow-server.md`。*
