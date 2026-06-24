# WORKFLOW 节点设计 —— workflow 调用 workflow

> 状态:设计已评审(LLM Council 反思 + 修订)
> 日期:2026-06-24
> 关联:`docs/cn/design-workflow-engine.md`(引擎总设计)

## 1. 目标与非目标

### 目标
- 让一个 workflow 通过一个 **WORKFLOW 节点** 调用另一个**已发布**的 workflow,把子 workflow 的输出接回父图后续节点。
- 沿用引擎"类型无关 Planner + 单一 NodeExecutor + 新功能=新 executor"的扩展范式,不改引擎主循环。

### 非目标(本期不做)
- 内联子图展开(把子 workflow 的节点摊进父图同一个 run)。保留为未来"私有、非发布、纯内部复用"场景的第二种节点类型。
- 子 workflow 含 HUMAN_INPUT 的透传挂起(P2)。
- 子 workflow 失败后自动重跑整个子图(需要节点级幂等,未来再做)。
- 输入映射的 schema 类型校验与自动生成表单(M2,见 §6)。

## 2. 背景:为什么不是 AGENT 节点的 1:1 复刻

AGENT 节点通过 `AgentRunGateway` 起一个**解耦的子 AgentRun**,然后**阻塞轮询**到终态(`awaitResult`)。这在 AGENT 上成立,因为 AgentRun 跑在**独立的 AgentRunner 池**——父 workflow 占着 WorkflowRunner 的 slot 等子 agent,两个池不争用。

WORKFLOW 节点不同:子 WorkflowRun 跑在**同一个 WorkflowRunner 池**。若照搬阻塞轮询,父 run 占着 CAS 租约 slot 等子 run,子 run 又要在同一个池里抢 slot —— fan-out 下 N 个父占满所有租约,子 run 一个都拿不到,**整池死锁**。

因此 WORKFLOW 节点改用引擎**已有的挂起/恢复**机制(HUMAN_INPUT 节点同款):父 run 提交子 run 后**挂起(PAUSED)并释放租约**,子 run 终态时回调唤醒父 run 续跑。父等待期间不占任何 slot,死锁从结构上消除。

## 3. 执行模型(核心)

### 3.1 提交—挂起—回调唤醒

```
父 run 跑到 WORKFLOW 节点
  │
  ├─ WorkflowExecutor.execute(ctx)
  │     1. 按 inputMappings 渲染输入,组装子 START 的 input(JSON)
  │     2. 校验 depth+1 <= MAX_DEPTH,否则返回 Fail
  │     3. gateway.submitChildRun(parentRun, node, input, depth+1) → childRunId
  │     4. 返回 NodeOutcome.Suspended(childRunId)
  │
  ├─ 引擎:父 run 进 PAUSED,该 WORKFLOW node-run 标记为 WAITING,释放 CAS 租约
  │     (PAUSED 被排除在 claim 之外,WorkflowRunnerJob 不再调度它)
  │
  ▼
子 run 独立被 WorkflowRunnerJob claim、运行、到达终态
  │
  ├─ 子 run finalization:若 parentRunId 非空
  │     1. 结算父侧 WAITING 的 WORKFLOW node-run(成功=子 output;失败=子 error)
  │     2. 把父 run PAUSED → PENDING,设置 resumeFromNodeId = 父 WORKFLOW 节点
  │
  ▼
父 run 被重新 claim、从 WORKFLOW 节点之后继续推进,后续节点读到子输出
```

### 3.2 `NodeOutcome` 新变体

新增 `Suspended(String childRunId)`,语义类似 `Waiting`(都让 run 进 PAUSED),区别在**唤醒者**:`Waiting` 由人工 resume 端点唤醒,`Suspended` 由子 run 终态回调唤醒。引擎对两者的挂起处理一致;`childRunId` 用于建立父子链接与级联取消。

> 备选:复用 `Waiting` 并加 `reason` 字段。倾向新增 `Suspended`,语义更清晰、避免人工 resume 端点误唤醒一个等子 run 的节点。

### 3.3 `WorkflowRunGateway` 接口

```java
public interface WorkflowRunGateway {
    // Start a decoupled child WorkflowRun from the node's embedded published snapshot.
    // Records parentRunId / parentNodeId / depth on the child. Returns the child run id.
    // NOTE: non-blocking — there is no awaitResult; the child wakes the parent on terminal.
    String submitChildRun(WorkflowRun parent, WorkflowNode node, String input, int depth);

    // Best-effort recursive cancel of the child run subtree (forwarded on parent cancel).
    void cancelSubtree(String childRunId);
}
```

与 `AgentRunGateway` 的关键差异:**没有 `awaitResult`**。父不阻塞等待,由子 run 的 finalization 反向唤醒。

## 4. 引用与快照

- 保存父 workflow 时,把所选子 workflow 当时的 `WorkflowPublishedVersion` **快照内嵌**进节点 config(**只嵌一层**:子节点里指向孙子的部分仍是子发布时自带的引用/快照,运行时逐层 load,不套娃)。
- 节点同时记录 `sourceWorkflowId` + `version`。
- UI 比对子 workflow 当前 published 版本,有更新则提示"有新版本可更新";更新动作 = 重新选取并固化新快照(可能需要用户重连改动过的输入映射)。

理由:快照保证父行为**可复现、不漂移**,与 AGENT 节点的反漂移保证一致,也契合 Explore 页"子 workflow 独立发布、独立演进"的复用定位。

## 5. 递归防护(两道防线,主次已重排)

### 5.1 运行期深度上限(主防线)
- `WorkflowRun` 新增 `parentRunId`、`parentNodeId`、`depth`。
- `submitChildRun` 时 `childDepth = parentDepth + 1`;`childDepth > MAX_DEPTH`(默认 5,可配)→ 节点 `Fail("workflow nesting too deep")`。
- 这是**唯一可靠**兜住所有环的防线,包括"孙子 workflow 在快照之后被重新发布、引回一个环"这类保存期看不见的环。

### 5.2 保存期检测(降级为尽力而为)
- **直接自引用**(节点 <code v-pre>sourceWorkflowId == 当前 workflow id</code>):保存期**硬拒**,抛 `WorkflowValidationException`。
- **间接环**:遍历内嵌快照引用树做静态检测,发现疑似环 → **UI 提示**,不作为安全保证(快照只冻结可见层,保证不了发布后引入的环)。

## 6. 输入 / 输出映射

### 6.1 输入
- 节点 config:<code v-pre>inputMappings: { &lt;childStartField&gt;: &lt;模板表达式串&gt; }</code>。
- 运行时逐字段用现有 `VariablePool.render()`(AgentExecutor 已在用)渲染父侧变量池,组装成子 START 的 `input`(JSON)。

### 6.2 输出
- 子 END 的结构化 output 整体收进 `NodeOutcome.Normal.output`;下游用 <code v-pre>{{ nodes.&lt;workflowNodeId&gt;.output.&lt;field&gt; }}</code> 取字段。
- deliverables/artifacts 沿用 AGENT 节点的 `artifacts` 通道(`NodeOutcome.Normal.artifacts`)。

### 6.3 里程碑拆分
- **M1(脊柱)**:用现成 `render()` 做字段级映射,无 UI、无 schema 校验。渲染引擎已存在,所以 M1 不重。
- **M2(表单)**:照子 workflow START schema 自动生成 UI 映射表单 + 类型转换/校验 + 缺字段/类型不匹配的失败模式。这是真正的增量工作,排在脊柱之后。

## 7. 取消、失败、可观测性

### 7.1 级联取消
- `workflow_runs` 对 `parentRunId` 建索引(迁移)。
- 父 run 取消时,按索引找直接子 run,递归 `cancelSubtree`,避免孤儿 RUNNING 子 run(历史 stuck-RUNNING 事故的成因)。

### 7.2 失败语义(不照搬 AGENT 的整图重跑)
- 子 run 失败 → 父 WORKFLOW node-run 结算为失败 → 节点 `Fail(retryable=false)`,**不自动重跑整个子图**(避免重放有副作用的子节点)。
- 节点级幂等 opt-in 留作未来。

### 7.3 可观测性 / run 树
- `parentRunId` 索引同时提供 **run 树**:trace/UI 中父 WORKFLOW 节点 ↔ 子 run 可下钻,子的节点级进度、token/成本沿树向上归因。
- 运维可按 `parentRunId` 定位并杀掉某棵子树。

## 8. HUMAN_INPUT(P1 禁止)

- 保存期校验:被引用 workflow(及其快照子孙)含 HUMAN_INPUT 节点 → 拒绝/警告。P1 的 WORKFLOW 节点只调**全自动**子 workflow。
- P2 透传挂起在本模型下几乎免费:子 run 挂起(非终态)时父自然继续 PAUSED 等待,人工在子 run 上 resume,子终态再回调唤醒父;只差"让用户发现存在一个挂起的子 run"的 UX。先禁止以保证 P1 语义干净。

## 9. 改动清单

| 模块 | 改动 |
| --- | --- |
| `NodeType` | + `WORKFLOW` |
| `NodeOutcome` | + `Suspended(childRunId)` 变体 |
| `WorkflowExecutor`(新) | submit 子 run + 返回 Suspended,不阻塞 |
| `WorkflowRunGateway`(新) | `submitChildRun` / `cancelSubtree`(无 awaitResult) |
| `MongoWorkflowRunGateway`(新) | 从内嵌快照建子 WorkflowRun、写 parent 链接、级联取消 |
| `WorkflowRun`(域) | + `parentRunId` / `parentNodeId` / `depth` |
| `WorkflowRunner` | 子 run finalization 处:终态回调唤醒父(结算父 node-run + PAUSED→PENDING) |
| `NodeExecutorRegistry` | 注册 WORKFLOW → `WorkflowExecutor` |
| `WorkflowValidator` | + 直接自引用硬拒;+ HUMAN_INPUT 检测;环检测为 UI 提示 |
| 迁移 | `workflow_runs` 加 `parentRunId` 索引 |
| 前端 | WORKFLOW 节点类型 + 子 workflow 选择器 +(M2)映射表单 + run 树下钻 + "有新版本"提示 |

> 约束:Java 文件不超过 500 行;新增 executor/gateway 体量小,符合;`MongoWorkflowRunGateway` 注意拆分以免超限。

## 10. 测试策略

### 10.1 单元测试
- `WorkflowExecutor`:用 fake `WorkflowRunGateway` 断言"submit + 返回 Suspended(childRunId)";depth 边界用例断言超限返回 Fail;`inputMappings` 渲染断言。
- `WorkflowValidator`:直接自引用拒绝;HUMAN_INPUT 检测;环检测产出提示(非异常)。

### 10.2 集成测试(第一件该做的事 —— 脊柱)
两个真实 `WorkflowRun`、单字段透传、零 UI,跑通:
`submit → 父 PAUSED 释放租约 → 子终态回调 → 父 PENDING 续跑 → collect`。
断言:
1. 父输出正确接到子 END output;
2. `parentRunId` / `depth` 正确写入并自增,上限触发返回 Fail;
3. **打满 runner 池时父等子不死锁**(验证挂起释放租约的核心收益);
4. 父取消时子 run 被级联取消,无孤儿 RUNNING。

这个集成测试证伪/证实约 80% 的未知(租约饥饿、depth 穿线、回调唤醒)。表单、表达式校验、Explore 集成排在它之后。

## 11. 设计评审纪要(LLM Council)

本设计经 5 视角反思 + 修订,关键修正:
1. **从阻塞等待改为挂起父 run / 子终态回调唤醒** —— 消除 runner-slot 死锁(头号风险)。
2. **深度上限提为主防线**,保存期环检测降级为 UI 提示(快照过期使静态检测不可靠)。
3. **补 `parentRunId` 反向索引** —— 级联取消 + run 树可观测性,堵住孤儿 RUNNING。
4. **失败不照搬 AGENT 的整图重跑** —— 默认 `retryable=false`,避免重放副作用。
5. **输入映射拆里程碑**,但确认 `render()` 已存在,M1 不重。

未采纳但记录在案:First Principles 提出的"内联子图"方案 —— 会牺牲子 workflow 的独立版本管理/复用(Explore 定位的核心价值),故不作为 P1;保留为未来"私有内部复用"场景的第二种节点类型。
