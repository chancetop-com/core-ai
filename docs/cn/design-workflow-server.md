# Core AI Server Workflow 机制设计

> **执行引擎已独立到 `design-workflow-engine.md`（权威）。** 本文的 §6.4 图模型、§6.5/§6.6 Run/NodeRun、§7 节点语义、§8 变量模型、§9 执行语义，以及 §3.2 中"不做并行/循环/iteration/代码节点"等条目，**已被引擎文档取代**（设计已升级为 durable + concurrent + container 图引擎）。本文保留有效的**平台集成**部分：触发入口（§10）、权限与凭证（§11）、API（§13）、前端（§14）、测试（§16）、风险（§17）。阅读时请以引擎文档为准。

## 1. 背景

Core AI Server 现在已经具备 Agent 定义、发布、运行、Webhook、定时调度、工具注册、Sandbox、Trace 等平台能力。用户下一步自然会需要类似 Dify / 扣子的固定业务流程能力：把多个 Agent、工具、条件判断和外部触发器组合成一个可发布、可重复执行、可观测的业务过程。

当前 `core-ai` 库中已有 `ai.core.flow` 包，但它不应直接作为 `core-ai-server` Workflow v1 的产品运行时：

- 它是 SDK 侧的 in-memory Flow 执行模型，不是 server 侧的持久化业务流程。
- 执行方式是递归单路径，缺少 server 运行所需的 run / node-run 持久化 checkpoint。
- 条件路由依赖节点输出文本与 edge value 匹配，缺少 typed schema 和显式变量模型。
- 多个节点仍是 stub，例如 `OperatorIfFlowNode.execute()` 返回 `null`。
- 没有发布快照、权限边界、Webhook/Schedule/API 入口、失败恢复、重试、取消、审计和 UI 管理语义。

因此本设计把 `Workflow` 定义为 `core-ai-server` 的新领域能力。旧 `ai.core.flow` 可以作为概念和命名参考，但不进入 v1 主运行路径。

## 2. 产品定位

Workflow 的核心不是画布，而是：

> 用户定义、发布、执行、观察、恢复一套可重复的固定业务过程。

它与现有 Agent 的关系：

```
AgentDefinition
  - 解决一个智能体如何思考和调用工具
  - 已有 draft / published config
  - 单次 run 由 AgentRunner 执行

WorkflowDefinition
  - 解决多个步骤如何被确定性编排
  - 自己拥有 draft / published config
  - 单次 run 由 WorkflowRunner 执行
  - Agent 只是 Workflow 的一种节点
```

它与 SDK Flow 的关系：

```
ai.core.flow
  - Java SDK 内部可嵌入的流程模型
  - 可以继续存在
  - v1 不作为 server workflow runtime

core-ai-server workflow
  - Mongo 持久化
  - 发布快照
  - run / node-run 状态机
  - Webhook / API / Schedule 入口
  - 权限、Trace、恢复、审计
```

## 3. 目标与非目标

### 3.1 目标

- 新建 server-level Workflow 领域模型。
- 支持草稿编辑和发布快照，运行时只使用不可变 `WorkflowPublishedConfig`。
- 支持最小可靠 DAG：串行执行、条件分支、单一 active path。
- 支持手动、API、Webhook、Schedule 触发。
- 支持 `Start/Input`、`Agent`、`Tool`、`If`、`Output` 五类 MVP 节点。
- 每次运行持久化 `WorkflowRun`，每个节点持久化 `WorkflowNodeRun`。
- 每个节点执行前保存 input snapshot，执行后保存 output snapshot。
- 支持基础变量模型、JSON Schema 校验和简单模板映射。
- 支持 timeout、cancel、有限 retry、失败状态和基础恢复。
- 接入现有 Trace，节点执行生成 `FLOW` span，并关联 run / node-run。
- 复用现有 `AgentRunner`、`ToolRegistryService`、Trigger、Schedule、Sandbox、Trace 能力。
- 从第一版定义权限、租户隔离、凭证引用和敏感日志边界。

### 3.2 非目标

- 不做 Dify / 扣子完整画布体验。
- 不做并行执行、循环、iteration、map/reduce。
- 不做人审节点、暂停等待、长事务补偿。
- 不做子 Workflow、Workflow 模板市场、协同编辑。
- 不做任意代码节点或通用 Sandbox Code 节点。
- 不做复杂表达式语言、JavaScript / SpEL / Python 表达式执行。
- 不承诺从旧 `ai.core.flow` 自动迁移。
- 不把 Agent 的草稿配置作为 Workflow 运行兜底。
- 不在 v1 做分布式 durable execution 引擎；先做单进程后台执行 + 持久化 checkpoint。

## 4. MVP 验收场景

v1 必须至少跑通下面的端到端场景：

1. 用户创建 Workflow 草稿。
2. 添加 `Start/Input -> Agent -> If -> Tool -> Output` 流程。
3. 发布 Workflow，生成不可变版本快照。
4. 手动触发运行，立即返回 `runId`。
5. 后台执行每个节点，并持久化 `WorkflowRun` 与 `WorkflowNodeRun`。
6. `If` 节点根据 Agent 输出中的结构化字段选择分支。
7. `Tool` 节点通过现有 ToolRegistry 调用工具。
8. `Output` 节点产出最终 JSON / text 结果。
9. UI 或 API 能查看 run 状态、每个节点输入输出、错误、耗时和 Trace 链接。
10. 相同 Workflow 再通过 Webhook 和 Schedule 触发。
11. 修改草稿后不影响已开始或已发布版本的运行。
12. 取消运行后，run 和当前 node-run 都进入明确状态。

示例业务：

```
客户提交工单 Webhook
  -> Agent 分类问题类型和紧急程度
  -> If 判断是否高优先级
  -> Tool 写入外部工单系统或 Dataset
  -> Output 返回处理结果和 runId
```

## 5. 总体架构

```
HTTP / API
  - WorkflowWebService
  - WorkflowRunWebService
  - WorkflowScheduleWebService
  - WorkflowWebhookController

Service
  - WorkflowDefinitionService
  - WorkflowPublishService
  - WorkflowValidationService
  - WorkflowRunner
  - WorkflowNodeExecutorRegistry
  - WorkflowToolExecutor
  - WorkflowVariableResolver
  - WorkflowTraceService
  - WorkflowRecoveryJob

Node Executors
  - StartNodeExecutor
  - AgentNodeExecutor
  - ToolNodeExecutor
  - IfNodeExecutor
  - OutputNodeExecutor

Existing Services
  - AgentDefinitionService / AgentRunner
  - ToolRegistryService
  - TriggerController / TriggerAction
  - AgentScheduler pattern
  - SandboxService
  - Trace ingest / SpanType.FLOW

Mongo
  - workflows
  - workflow_published_versions
  - workflow_runs
  - workflow_node_runs
  - workflow_schedules
  - triggers
  - traces / spans
```

v1 建议新增独立 `workflow_schedules`，结构可以参考 `agent_schedules`，避免第一期重构现有 Agent 调度。Webhook 触发优先复用现有 Trigger 框架，增加 `RUN_WORKFLOW` action type；如果要支持 header/query 映射，Trigger action contract 必须从只传 payload string 升级为传 `TriggerRequestContext`。

## 6. 领域模型

### 6.1 WorkflowDefinition

Mongo collection: `workflows`

| 字段 | 说明 |
|------|------|
| `id` | Workflow ID |
| `user_id` | 所属用户，权限边界 |
| `name` | 名称，同一用户下唯一 |
| `description` | 描述 |
| `status` | `DRAFT` / `PUBLISHED` / `ARCHIVED` |
| `draft_graph` | 可编辑草稿图 |
| `draft_variables` | 草稿变量声明 |
| `published_config` | 最新发布快照的便捷副本，用于详情展示，不作为历史运行的权威来源 |
| `published_version_id` | 最新 `WorkflowPublishedVersion` ID |
| `published_version` | 最新发布版本号 |
| `webhook_secret` | 可选，Workflow 级 webhook secret |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `published_at` | 最新发布时间 |

硬性规则：

- 用户编辑只能修改 `draft_graph` 和草稿字段。
- `publish()` 捕获当时的草稿、引用对象和校验结果，创建一条不可变 `WorkflowPublishedVersion`。
- `published_config` 只是最新版本的便捷副本；`WorkflowRunner` 不能从它恢复历史 run。
- `WorkflowRunner` 只能从 `workflow_published_versions` 加载 `WorkflowRun.workflow_version_id` 指向的版本。
- 已经创建的 `WorkflowRun` 必须保存 `workflow_version_id`、版本号和快照摘要，不能被后续发布影响。

### 6.2 WorkflowPublishedVersion

Mongo collection: `workflow_published_versions`

`WorkflowPublishedVersion` 是 Workflow 历史运行的权威配置来源。它解决两个问题：

- Workflow 重新发布后，旧 run 仍能加载当时的完整配置。
- server 重启恢复 run 时，不需要依赖 `workflows.published_config` 这个最新版本字段。

| 字段 | 说明 |
|------|------|
| `id` | Published version ID |
| `workflow_id` | Workflow ID |
| `version` | 每次发布递增 |
| `config` | 完整 `WorkflowPublishedConfig` |
| `config_sha256` | 标准化 config 摘要 |
| `published_by` | 发布人 |
| `published_at` | 发布时间 |
| `archived` | 是否归档，归档不影响已有 run 恢复 |

硬性规则：

- `WorkflowPublishedVersion` 一经创建不可修改，只能追加新版本。
- 删除或归档 Workflow 不能删除仍被 `workflow_runs` 引用的 published version。
- `WorkflowRun` 创建时保存 `workflow_version_id` 和 `workflow_config_sha256`。
- `WorkflowRunner` 加载版本后必须校验 `config_sha256`，不匹配则 fail fast。

### 6.3 WorkflowPublishedConfig

`WorkflowPublishedConfig` 是运行时唯一可信配置。

| 字段 | 说明 |
|------|------|
| `version` | 每次发布递增 |
| `graph` | 规范化后的图 |
| `variables` | 变量声明和默认值 |
| `input_schema` | Workflow 输入 JSON Schema |
| `output_schema` | Workflow 输出 JSON Schema |
| `referenced_agents` | 发布时捕获的 Agent published config 摘要或副本 |
| `referenced_tools` | 发布时解析的 Tool ref 元数据和摘要 |
| `config_sha256` | 发布快照摘要 |
| `published_by` | 发布人 |
| `published_at` | 发布时间 |

重要设计点：Workflow 发布时如果引用 Agent，不能只保存 `agent_id`。当前 Agent 的 `published_config` 会被下一次 publish 覆盖，单纯按 `agent_id` 运行会造成 Workflow 版本漂移。

v1 推荐策略：

- 在 `WorkflowPublishedConfig.referenced_agents` 中嵌入该 Agent 当时的 `AgentPublishedConfig` 副本或标准化摘要。
- Agent 节点运行时使用这个快照构建执行配置，而不是读取 Agent 当前草稿。
- 如果短期内 `AgentRunner` 只能接收 `AgentDefinition`，则由 WorkflowRunner 构造一个只包含发布快照的 transient definition。

Tool 也需要类似处理：

- Workflow 快照保存 tool ref、source type、name、version / digest、权限摘要。
- 不把凭证明文复制进 Workflow 快照。
- 运行时仍从 ToolRegistry 获取凭证和连接信息，并再次做权限校验。

### 6.4 WorkflowGraph

Graph 是发布快照的一部分，使用 JSON 结构存储。

```json
{
  "format": "core-ai-workflow/v1",
  "nodes": [
    {
      "id": "start",
      "type": "START",
      "name": "Input",
      "config": {},
      "input_mapping": {},
      "output_schema": {
        "type": "object"
      },
      "timeout_seconds": 30,
      "retry_policy": {
        "max_attempts": 1
      },
      "position": {
        "x": 80,
        "y": 120
      }
    }
  ],
  "edges": [
    {
      "id": "edge-1",
      "source": "start",
      "target": "agent_classify"
    }
  ]
}
```

节点 ID 规则：

- v1 节点 ID 必须匹配 `[A-Za-z_][A-Za-z0-9_]*`。
- 不允许 `-`、`.`、空格或其他需要转义的字符。
- 这样变量路径可以稳定使用 <code v-pre>{{nodes.agent_classify.output.priority}}</code>，不需要 bracket path。
- UI 可以显示任意 `name`，但执行和变量引用只使用稳定 `id`。

Node 字段：

| 字段 | 说明 |
|------|------|
| `id` | 节点稳定 ID，发布后不可在 run 中改变 |
| `type` | `START` / `AGENT` / `TOOL` / `IF` / `OUTPUT` |
| `name` | 用户可读名称 |
| `config` | 节点类型专属配置 |
| `input_mapping` | 从上下文映射到节点输入 |
| `output_schema` | 节点输出 JSON Schema |
| `timeout_seconds` | 节点级超时 |
| `retry_policy` | 节点级重试策略 |
| `position` | UI 用坐标，不参与执行语义 |

Edge 字段：

| 字段 | 说明 |
|------|------|
| `id` | 边 ID |
| `source` | 上游节点 ID |
| `target` | 下游节点 ID |
| `condition` | 可选，仅用于 `IF` 节点出边，作为分支判断的唯一权威来源 |
| `default` | 可选，仅用于 `IF` 节点出边，最多一条 |
| `label` | UI 显示 |

IF 节点的条件统一放在出边上。`IF` node config 不再保存 target 列表，避免 node config 和 edge condition 两套来源互相冲突。

### 6.5 WorkflowRun

Mongo collection: `workflow_runs`

| 字段 | 说明 |
|------|------|
| `id` | Run ID |
| `workflow_id` | Workflow ID |
| `workflow_version_id` | `WorkflowPublishedVersion` ID |
| `workflow_version` | 发布版本 |
| `workflow_config_sha256` | 发布快照摘要 |
| `user_id` | 运行用户 |
| `triggered_by` | `MANUAL` / `API` / `WEBHOOK` / `SCHEDULE` |
| `trigger_ref` | scheduleId / triggerId / apiKeyId 等 |
| `status` | run 状态 |
| `input` | 原始输入 |
| `variables_snapshot` | 最近一次成功 checkpoint 的变量上下文 |
| `current_node_id` | 当前或最近节点 |
| `output` | 最终输出 |
| `error` | 错误摘要 |
| `trace_id` | Trace ID |
| `idempotency_key` | 外部触发幂等键，可选 |
| `owner_id` | 当前认领该 run 的 server instance |
| `claim_token` | 当前执行认领 token |
| `claim_until` | claim 过期时间 |
| `heartbeat_at` | 当前执行心跳 |
| `started_at` | 开始时间 |
| `completed_at` | 结束时间 |

状态机：

```text
PENDING
  -> RUNNING
  -> COMPLETED
  -> FAILED
  -> TIMEOUT
  -> CANCELLED
```

v1 不引入 `WAITING_FOR_HUMAN`。如果未来做人审节点，再单独扩展。

### 6.6 WorkflowNodeRun

Mongo collection: `workflow_node_runs`

| 字段 | 说明 |
|------|------|
| `id` | NodeRun ID |
| `run_id` | WorkflowRun ID |
| `workflow_id` | Workflow ID |
| `workflow_version_id` | `WorkflowPublishedVersion` ID |
| `workflow_version` | 发布版本 |
| `node_id` | 节点 ID |
| `node_type` | 节点类型 |
| `node_name` | 发布时节点名称 |
| `attempt` | 第几次尝试 |
| `status` | node-run 状态 |
| `input_snapshot` | 执行前输入 |
| `output_snapshot` | 执行后输出 |
| `variables_before` | 执行前变量上下文摘要或完整快照 |
| `variables_after` | 执行后变量上下文摘要或完整快照 |
| `error` | 错误详情 |
| `span_id` | Trace span ID |
| `side_effect_key` | 幂等和外部副作用追踪键 |
| `child_agent_run_id` | `AGENT` 节点产生的子 AgentRun ID，可选 |
| `external_call_ref` | Tool/API/MCP 外部调用引用，可选 |
| `owner_id` | 当前认领该 node-run 的 server instance |
| `claim_token` | 当前节点执行认领 token |
| `claim_until` | claim 过期时间 |
| `started_at` | 开始时间 |
| `completed_at` | 结束时间 |

状态机：

```text
PENDING
  -> RUNNING
  -> COMPLETED
  -> SKIPPED
  -> FAILED
  -> TIMEOUT
  -> CANCELLED
```

硬性规则：

- 任何可能产生副作用的节点，在调用外部系统前必须先创建 `RUNNING` node-run，并保存 `input_snapshot`。
- 节点成功后必须先写 `output_snapshot` 和 `variables_after`，再推进下游节点。
- 已经 `COMPLETED` 的 node-run 不得在恢复时重复执行，只能复用 output snapshot。
- 只有 `IF` 等纯计算节点可以默认自动重试。
- `AGENT` / `TOOL` 节点默认 `max_attempts=1`，除非节点或工具元数据声明幂等。
- 多副本恢复时，执行 node-run 前必须通过 `run_id + node_id + attempt + status` 做原子 claim，不能只靠内存锁。

## 7. 节点语义

### 7.1 START

职责：

- 校验 Workflow 输入。
- 初始化变量上下文。
- 输出标准化后的 `input` 对象。

配置：

```json
{
  "input_schema": {
    "type": "object"
  }
}
```

### 7.2 AGENT

职责：

- 调用一个已发布 Agent 或 LLM_CALL definition。
- 接收映射后的输入。
- 输出 Agent 最终结果、结构化响应、token usage 和 artifact 摘要。

配置示例：

```json
{
  "agent_id": "agent-123",
  "agent_config_sha256": "sha256:...",
  "input_template": "{{nodes.start.output.ticket_text}}",
  "response_schema": {
    "type": "object",
    "properties": {
      "category": { "type": "string" },
      "priority": { "type": "string" }
    }
  }
}
```

约束：

- 发布 Workflow 时，Agent 必须已经发布。
- Workflow 发布快照必须记录该 Agent 的 effective published config。
- 运行时不得读取 Agent 草稿。
- Agent 的工具、skill、sandbox config、dataset permission 必须随 Agent published config 一起被校验。

v1 执行契约：

- `AGENT` 节点复用现有 `AgentRunner`，但必须显式建模为子运行。
- `AgentNodeExecutor` 调用 `AgentRunner.run()` 后，把返回的 `agentRunId` 写入 `WorkflowNodeRun.child_agent_run_id`。
- `AgentNodeExecutor` 等待子 AgentRun 结束，再把 output、token usage、artifact 摘要和 transcript 摘要复制到 node output。
- Workflow cancel 时，如果当前节点是 `AGENT`，必须调用 `AgentRunner.cancel(child_agent_run_id)`。
- Workflow timeout 时，如果子 AgentRun 仍在运行，也必须 best-effort cancel。
- 为避免草稿兜底，`AgentNodeExecutor` 必须用发布快照构造 snapshot-only `AgentDefinition`，或者先抽出只接收 `AgentPublishedConfig` 的执行接口；不能把当前数据库里的 Agent draft 字段传给 runtime。

这个模型会产生两层 run：

```
WorkflowRun
  -> WorkflowNodeRun(node_type=AGENT, child_agent_run_id=...)
     -> AgentRun
```

两层 run 都保留是可接受的：`WorkflowNodeRun` 负责编排状态，`AgentRun` 负责 Agent transcript、artifact、token usage 和现有 Agent 观测能力。

### 7.3 TOOL

职责：

- 通过 ToolRegistry 调用内置工具、MCP 工具或 API 工具。
- 输入输出必须是 JSON 可序列化对象。
- 工具调用结果写入节点输出。

配置示例：

```json
{
  "tool_ref": {
    "source_type": "MCP",
    "server_id": "jira",
    "tool_name": "create_issue"
  },
  "input_mapping": {
    "title": "{{nodes.agent_classify.output.title}}",
    "priority": "{{nodes.agent_classify.output.priority}}"
  },
  "idempotent": false
}
```

约束：

- 发布时验证 tool ref 是否存在、用户是否有权限使用。
- 快照保存 tool 元数据摘要，不保存凭证明文。
- 默认不自动重试非幂等工具。
- 工具返回过大时需要截断展示，但完整结果可按现有 artifact / file 机制扩展保存。

v1 必须新增 `WorkflowToolExecutor`，不要让每个节点自己拼工具调用逻辑。它负责：

- 从 published config 的 tool ref 加载 tool metadata。
- 运行时再次从 ToolRegistry / MCP connection / API loader 解析实际调用对象。
- 统一 builtin / MCP / API 工具的 direct call 接口。
- 构造 `ExecutionContext`，传入 `userId`、`workflowRunId`、`workflowNodeRunId`、`side_effect_key` 和可选 Sandbox。
- 对参数做 JSON Schema 校验。
- 对结果做 JSON 化、大小限制、secret redaction 和 error normalization。
- 对支持幂等键的工具传递 `side_effect_key`。

现有 `ToolRegistryService.resolveToolRefs()` 主要服务于 Agent 构建工具列表，不能单独承担 Workflow Tool 节点的全部执行语义。

### 7.4 IF

职责：

- 对变量上下文做纯判断。
- 根据结果选择一条下游边。

配置示例：

```json
{
  "mode": "first_match"
}
```

对应出边示例：

```json
[
  {
    "source": "check_priority",
    "target": "tool_create_urgent_ticket",
    "condition": "{{nodes.agent_classify.output.priority}} == \"HIGH\"",
    "label": "High priority"
  },
  {
    "source": "check_priority",
    "target": "output_normal",
    "default": true,
    "label": "Default"
  }
]
```

v1 表达式限制：

- 只支持变量引用、字符串/数字/布尔比较、`and` / `or` / `not`。
- 不支持函数调用、循环、正则执行、脚本执行。
- 表达式必须在发布时通过静态校验。
- `IF` 节点条件只允许写在 outgoing edge 上，node config 不保存 target，避免双来源冲突。

### 7.5 OUTPUT

职责：

- 组装最终输出。
- 校验 `output_schema`。
- 标记 WorkflowRun `COMPLETED`。

配置示例：

```json
{
  "output_mapping": {
    "category": "{{nodes.agent_classify.output.category}}",
    "ticket_id": "{{nodes.tool_create_ticket.output.id}}",
    "message": "ticket routed"
  }
}
```

## 8. 变量与数据模型

Workflow 上下文是一个 JSON object：

```json
{
  "input": {},
  "vars": {},
  "nodes": {
    "node-id": {
      "input": {},
      "output": {},
      "status": "COMPLETED"
    }
  },
  "run": {
    "id": "run-123",
    "triggered_by": "WEBHOOK"
  }
}
```

引用语法：

| 语法 | 含义 |
|------|------|
| <code v-pre>{{input.field}}</code> | Workflow 输入字段 |
| <code v-pre>{{vars.name}}</code> | Workflow 变量 |
| <code v-pre>{{nodes.node_id.output.field}}</code> | 某节点输出字段 |
| <code v-pre>{{run.id}}</code> | 当前 run id |

v1 只支持简单 JSON path 和字符串模板。禁止执行用户提供脚本。

路径规则：

- 节点 ID 必须是 dot path 友好的 identifier。
- 推荐使用 `agent_classify`，不允许 `agent-classify`。
- v1 不支持 <code v-pre>{{nodes["agent-classify"].output.priority}}</code> 这类 bracket path。
- 字符串模板如果整体只有一个变量引用，返回原始 JSON 值；如果还包含其他文本，则返回字符串。

Schema 规则：

- Workflow 可以声明 `input_schema` 和 `output_schema`。
- 每个节点可以声明 `input_schema` 和 `output_schema`。
- 发布时校验 graph 拓扑和可解析引用。
- 运行时校验实际输入输出。
- Schema 校验失败是明确的 node failure，不进入下游。

变量快照策略：

- `WorkflowRun.variables_snapshot` 保存最近一次成功 checkpoint。
- `WorkflowNodeRun.variables_before` / `variables_after` 保存节点边界状态。
- 大对象后续可外置到文件或 artifact，v1 先设大小上限并截断 UI 展示。

## 9. 执行语义

### 9.1 基本流程

```
POST /api/workflows/{id}/runs
  -> WorkflowDefinitionService load workflow
  -> require published_config
  -> load latest WorkflowPublishedVersion
  -> create WorkflowRun(PENDING, workflow_version_id, workflow_config_sha256)
  -> submit background task
  -> return 202 { runId }

WorkflowRunner
  -> atomically claim run and mark RUNNING
  -> load WorkflowPublishedVersion by run.workflow_version_id
  -> verify config_sha256
  -> find START node
  -> execute node
     -> create or claim WorkflowNodeRun(RUNNING, input_snapshot)
     -> call NodeExecutor
     -> persist output_snapshot and variables_after
     -> emit FLOW span
  -> select next edge
  -> repeat
  -> OUTPUT node completes run
```

### 9.2 图校验

发布时必须校验：

- 有且只有一个 `START` 节点。
- 至少一个 `OUTPUT` 节点。
- 所有边的 source / target 都存在。
- 不允许环。
- 除 `OUTPUT` 外，每个可执行路径必须可达一个 `OUTPUT`。
- `IF` 节点的每个非 default 分支都有 condition。
- `IF` 节点最多一个 default 分支。
- 节点引用的 Agent 已发布。
- 节点引用的 Tool 存在且用户有权限。
- 变量引用能在拓扑顺序上解析，不能引用未来节点。
- 节点 schema 是合法 JSON Schema。
- 节点 ID 在同一 graph 内唯一且稳定。

### 9.3 恢复和重试

v1 不承诺完整 Temporal / Durable Functions 级别能力，但必须避免重复副作用。

恢复规则：

- 如果 server 进程重启，`WorkflowRecoveryJob` 扫描 `RUNNING` 且 `claim_until < now` 的 WorkflowRun。
- 恢复 worker 必须先用 Mongo CAS 更新 `owner_id`、`claim_token`、`claim_until`，认领成功后才能继续执行。
- 已 `COMPLETED` 的 node-run 不重新执行，直接从 `output_snapshot` 恢复变量。
- 如果某节点停留在 `RUNNING`，且没有完成 output snapshot：
  - 纯计算节点可以按 retry policy 重试。
  - `AGENT` / `TOOL` 节点默认标记为 `FAILED` 或 `FAILED_RETRYABLE`，不自动重复执行。
  - 只有节点声明幂等、或工具元数据声明 safe retry，才允许自动重试。
- 手动 retry 必须生成新的 attempt，并保留旧 attempt 记录。

最小 claim 规则：

- `WorkflowRun` claim filter: `_id = runId AND status = RUNNING AND (claim_until missing OR claim_until < now OR claim_token = currentToken)`。
- `WorkflowNodeRun` claim filter: `run_id + node_id + attempt + status`，且同样要求 claim 过期或属于当前 token。
- 执行中 worker 定期刷新 `heartbeat_at` 和 `claim_until`。
- claim 失败说明其他副本已经接管，当前 worker 必须停止。
- 没有 claim/CAS 的恢复扫描只能作为人工诊断工具，不能自动执行节点。

重试规则：

| 节点类型 | 默认自动重试 |
|----------|--------------|
| `START` | 可以 |
| `IF` | 可以 |
| `OUTPUT` | 可以 |
| `AGENT` | 不可以，除非声明幂等 |
| `TOOL` | 不可以，除非 tool metadata 声明幂等 |

`side_effect_key` 生成规则：

```
workflowRunId + ":" + nodeId + ":" + attempt
```

如果外部工具支持幂等键，应把这个 key 传入工具上下文。

### 9.4 Timeout 和 Cancel

- Workflow 可以有全局 timeout。
- 节点可以有 node-level timeout。
- 节点 timeout 后，node-run 进入 `TIMEOUT`，run 默认进入 `FAILED` 或 `TIMEOUT`。
- Cancel 是 best-effort：
  - 未开始节点标记 `CANCELLED`。
  - 当前 Agent 节点调用 `AgentRunner.cancel()` 或等价取消接口。
  - 当前 Tool 节点如果无法中断，完成后不再推进下游。
- Cancel 不做外部副作用回滚。

### 9.5 并发策略

Workflow 运行入口需要支持并发策略：

| 策略 | 说明 |
|------|------|
| `PARALLEL` | 允许同一 Workflow 多个 run 同时执行 |
| `SKIP` | 如果已有 RUNNING run，则跳过新触发 |

v1 默认 `PARALLEL`。Schedule 可以默认 `SKIP`，避免定时任务堆积。

## 10. 触发入口

### 10.1 手动/API

```
POST /api/workflows/{workflowId}/runs
Authorization: Bearer coreai_xxx
Content-Type: application/json

{
  "input": {
    "ticket_text": "..."
  },
  "idempotency_key": "optional-client-key"
}
```

返回：

```json
{
  "run_id": "run-123",
  "status": "RUNNING"
}
```

### 10.2 Webhook

v1 推荐复用现有 Trigger 框架，新增 `RUN_WORKFLOW` action：

```
Trigger
  action_type = RUN_WORKFLOW
  action_config.workflow_id = ...
  action_config.input_template = ...
```

但现有 Trigger action 只接收 payload string。如果 Workflow 需要 header/query 映射，必须先升级 action contract：

```java
TriggerActionResult execute(Trigger trigger, TriggerRequestContext context)
```

`TriggerRequestContext` 至少包含：

| 字段 | 说明 |
|------|------|
| `payload` | 原始 body 字符串 |
| `parsed_payload` | JSON body 解析结果，可选 |
| `headers` | 请求 header，必须过滤敏感字段 |
| `query` | query 参数 |
| `received_at` | 接收时间 |
| `remote_addr` | 可选，用于审计 |

输入模板支持：

| 占位符 | 含义 |
|--------|------|
| <code v-pre>{{payload}}</code> | 完整请求体 |
| <code v-pre>{{headers.name}}</code> | 指定 header |
| <code v-pre>{{query.name}}</code> | 指定 query 参数 |

如果 v1 不升级 Trigger action contract，则输入模板只允许 <code v-pre>{{payload}}</code>，不能在文档或 UI 暴露 `headers/query` 占位符。

Webhook 必须校验 secret，触发结果必须记录 `trigger_ref`。

### 10.3 Schedule

v1 新增 `WorkflowSchedule`，字段参考 `AgentSchedule`：

| 字段 | 说明 |
|------|------|
| `workflow_id` | 目标 Workflow |
| `cron_expression` | 5 段 cron |
| `input` | 固定输入或模板 |
| `enabled` | 是否启用 |
| `concurrency_policy` | `SKIP` / `PARALLEL` |
| `next_run_at` | 下次触发时间 |
| `last_run_at` | 上次触发时间 |
| `last_run_id` | 上次 run |

Schedule 触发时必须要求 Workflow 已发布。

## 11. 权限、租户和凭证边界

权限规则：

- Workflow 属于 `user_id`。
- 用户只能读取、编辑、运行自己有权限的 Workflow。
- Workflow 引用 Agent 时，用户必须有该 Agent 的读取/运行权限。
- Workflow 引用 Tool 时，用户必须有该 Tool 的使用权限。
- 发布时做一次权限校验，运行时还要再次校验，避免引用对象被撤权后继续运行。
- 系统默认 Agent / Tool 可以通过现有 `system_default` 或 registry 权限模型共享。

凭证规则：

- Workflow snapshot 不保存 API key、OAuth token、MCP secret 等凭证明文。
- Tool / MCP 凭证仍由 ToolRegistry 或连接管理服务持有。
- NodeRun 日志和 output 默认不展示 secret 字段。
- Webhook secret 独立于用户 API key。

审计规则：

- 发布、运行、取消、retry、webhook 触发都应有 audit log 或至少 server log。
- `WorkflowRun.trigger_ref` 记录来源。
- Trace span 属性中不要写入原始 secret。

## 12. Trace 和观测

Workflow 观测必须是节点级别，而不是只看最终输出。

Trace 结构：

```
FLOW span: workflow run
  FLOW span: node START
  FLOW span: node AGENT
    AGENT span / LLM span / TOOL span
  FLOW span: node IF
  FLOW span: node TOOL
    TOOL span
  FLOW span: node OUTPUT
```

Span 属性建议：

| 属性 | 说明 |
|------|------|
| `workflow.id` | Workflow ID |
| `workflow.name` | Workflow 名称 |
| `workflow.version` | 发布版本 |
| `workflow.run_id` | Run ID |
| `workflow.node_id` | 节点 ID |
| `workflow.node_type` | 节点类型 |
| `workflow.node_run_id` | NodeRun ID |
| `trigger.type` | 触发类型 |

UI 需要能从 WorkflowRun 跳到 Trace，也能从 Trace 回到 WorkflowRun。

## 13. API 草案

Workflow 定义：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/workflows` | 创建 Workflow |
| `GET` | `/api/workflows` | 列表 |
| `GET` | `/api/workflows/{id}` | 详情 |
| `PATCH` | `/api/workflows/{id}` | 更新草稿 |
| `DELETE` | `/api/workflows/{id}` | 删除或归档 |
| `POST` | `/api/workflows/{id}/validate` | 校验草稿 |
| `POST` | `/api/workflows/{id}/publish` | 发布 |
| `GET` | `/api/workflows/{id}/versions` | 发布版本列表 |
| `GET` | `/api/workflow-versions/{versionId}` | 发布版本详情 |

运行：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/workflows/{id}/runs` | 手动/API 触发 |
| `GET` | `/api/workflows/{id}/runs` | Workflow run 列表 |
| `GET` | `/api/workflow-runs/{runId}` | Run 详情 |
| `GET` | `/api/workflow-runs/{runId}/nodes` | NodeRun 列表 |
| `POST` | `/api/workflow-runs/{runId}/cancel` | 取消 |
| `POST` | `/api/workflow-runs/{runId}/retry` | 手动 retry |

Schedule：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/workflows/{id}/schedules` | 创建定时触发 |
| `GET` | `/api/workflows/{id}/schedules` | 列表 |
| `PATCH` | `/api/workflow-schedules/{scheduleId}` | 更新 |
| `DELETE` | `/api/workflow-schedules/{scheduleId}` | 删除 |

Webhook：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/triggers/webhook/{secret}` | 复用 Trigger 框架 |
| `POST` | `/api/workflows/{id}/webhook` | 可选专用入口 |

## 14. 前端范围

v1 前端目标是让用户能管理和验证 Workflow，而不是复制 Dify 画布体验。

页面：

- `/workflows`：Workflow 列表、状态、最近运行结果。
- `/workflows/{id}`：详情、发布状态、最近 runs。
- `/workflows/{id}/editor`：草稿编辑。
- `/workflow-runs/{runId}`：运行详情和节点执行结果。

编辑器 v1：

- 左侧节点列表。
- 中间轻量 graph preview 或简化画布。
- 右侧属性面板。
- 支持添加、删除、连接节点。
- 支持配置 input mapping、schema、timeout、retry。
- 支持 validate 和 publish。

明确不做：

- 实时协同。
- 节点拖拽高级交互。
- 全量运行 replay 动画。
- 模板市场。

如果第一期为了压缩实现成本，可以先用表单化编辑器：

```
Nodes table
  -> selected node config form
Edges table
  -> source / target / condition
Graph preview
  -> readonly rendering
```

后续再替换为 React Flow / xyflow，不影响后端 schema。

## 15. 实施阶段

### Phase 0: 设计冻结

- 新增本设计文档。
- 明确 graph JSON schema。
- 明确状态机和 API contract。
- 明确 Agent config snapshot 策略。
- 明确 Tool ref / credential 策略。

### Phase 1: 后端领域和 API

- 新增 domain：
  - `WorkflowDefinition`
  - `WorkflowPublishedVersion`
  - `WorkflowPublishedConfig`
  - `WorkflowRun`
  - `WorkflowNodeRun`
  - `WorkflowSchedule`
- 新增 API request / response。
- 注册 Mongo collection。
- 实现 CRUD、validate、publish。
- 发布时创建不可变 `WorkflowPublishedVersion`。
- 发布时捕获 Agent published config 副本和 Tool 引用摘要。

### Phase 2: 串行运行时

- 实现 `WorkflowRunner`。
- 实现节点 executor registry。
- 实现 `START`、`IF`、`OUTPUT`。
- 实现 `AGENT` 节点，明确创建并关联子 AgentRun。
- 实现 `WorkflowToolExecutor`，统一 builtin / MCP / API direct call。
- 实现 `TOOL` 节点，调用 `WorkflowToolExecutor`。
- 实现 node-run 持久化、变量快照、timeout、cancel。
- 实现 run / node-run claim token 和 heartbeat。

### Phase 3: 触发和观测

- Trigger 增加 `RUN_WORKFLOW` action。
- 如果需要 header/query 映射，先升级 `TriggerAction` 为 `TriggerRequestContext`。
- 新增 `WorkflowSchedule` 和 scheduler job。
- 接入 Trace `FLOW` span。
- Run detail 展示 node-run、trace link、error。

### Phase 4: 前端

- 新增 Workflow 列表。
- 新增详情和发布按钮。
- 新增表单化或轻画布编辑器。
- 新增 run detail 和节点结果视图。

### Phase 5: 硬化

- `WorkflowRecoveryJob` 扫描 claim 过期的 RUNNING run，并通过 CAS 接管。
- 幂等和 retry 策略完善。
- 权限和凭证边界测试。
- 大 output 截断和 artifact 外置。
- 操作审计。

## 16. 测试要求

单元测试：

- Graph validate：
  - 无 START。
  - 多 START。
  - 无 OUTPUT。
  - dangling edge。
  - cycle。
  - IF 无 default。
  - IF 多 default。
  - IF 条件写在 node config 而不是 edge。
  - node id 包含 `-` 或 `.`。
  - 引用未来节点变量。
  - 引用未发布 Agent。
  - 引用无权限 Tool。
- Variable resolver：
  - <code v-pre>{{input.field}}</code>。
  - <code v-pre>{{nodes.node.output.field}}</code>。
  - <code v-pre>{{nodes.agent-classify.output.field}}</code> 应拒绝。
  - 缺失字段。
  - schema mismatch。
- If expression：
  - 字符串比较。
  - 数字比较。
  - default branch。

集成测试：

- 创建、更新、发布 Workflow。
- 发布后修改草稿，不影响已发布 run。
- 手动运行 `START -> IF -> OUTPUT`。
- 手动运行 `START -> AGENT -> OUTPUT`，使用测试 LLM / fake Agent executor。
- 手动运行 `START -> TOOL -> OUTPUT`，使用 fake Tool。
- Agent 节点创建子 AgentRun，并把 `child_agent_run_id` 写入 node-run。
- Tool 节点通过 `WorkflowToolExecutor` 执行 direct call。
- Webhook 触发 Workflow。
- Webhook header/query 模板只在 `TriggerRequestContext` 已实现时启用。
- Schedule 触发 Workflow。
- Cancel 正在运行的 Workflow。
- 非幂等 Tool 默认不自动 retry。
- claim 过期后恢复 RUNNING run，已完成 node-run 不重复执行。
- claim 未过期时第二个 worker 不能接管同一 run。

回归测试：

- 旧 Agent CRUD / publish / run 不受影响。
- 旧 Trigger `RUN_AGENT` 不受影响。
- 旧 AgentSchedule 不受影响。
- `ai.core.flow` 现有测试不因 server Workflow 变化而改变。

## 17. 风险和待定问题

### 17.1 Agent 发布版本漂移

当前 Agent 只有一个最新 `published_config`，没有历史版本表。Workflow 如果只保存 `agent_id`，后续 Agent 重新发布会改变旧 Workflow 版本行为。

v1 解决：

- Workflow 发布时在 `WorkflowPublishedConfig.referenced_agents` 中嵌入 Agent published config 副本或规范化快照。
- `WorkflowPublishedVersion` 保存完整 Workflow config。
- `WorkflowRun` 保存 `workflow_version_id`、`workflow_version` 和 `workflow_config_sha256`，运行时通过 version ID 加载历史快照。

后续可演进：

- 为 Agent 引入 `agent_published_versions` collection。
- Workflow 节点引用具体 Agent version。

### 17.2 Tool 版本和凭证

ToolRegistry 可能会变更工具 schema、MCP server 配置或凭证。

v1 解决：

- 发布时保存 tool schema / metadata digest。
- 运行时由 `WorkflowToolExecutor` 再次解析 ToolRegistry。
- 如果 digest 不匹配，按策略 fail fast 或给出 warning。
- 不复制 secret。

### 17.3 Agent 节点嵌套运行

复用 `AgentRunner` 会产生子 `AgentRun`。这比复制 Agent runtime 更稳，但会带来两层运行记录、两套 timeout/cancel 和两层 Trace 的关联问题。

v1 解决：

- `WorkflowNodeRun.child_agent_run_id` 是强制字段。
- Workflow cancel / timeout 必须转发到 child AgentRun。
- Node output 从 child AgentRun 归并，不直接读取 Agent 内部对象。
- Trace 使用 `workflow.run_id`、`workflow.node_run_id`、`agent.run_id` 关联。

后续可演进：

- 抽出 `AgentExecutionService`，支持不创建 `AgentRun` 的嵌入式执行。

### 17.4 副作用和幂等

Agent 和 Tool 可能写外部系统。自动重试会造成重复写入。

v1 解决：

- 非幂等节点默认不自动重试。
- node-run 调用前持久化。
- 传递 `side_effect_key` 给支持幂等的工具。
- 恢复时只自动重试纯计算节点，`AGENT` / `TOOL` 默认失败等待人工 retry。

### 17.5 恢复重复执行

多副本部署下，如果恢复扫描没有 claim/CAS，会导致同一 RUNNING run 被多个 pod 重复接管。

v1 解决：

- `WorkflowRun` 和 `WorkflowNodeRun` 都有 `owner_id`、`claim_token`、`claim_until`。
- 执行和恢复都必须先 CAS claim。
- claim 未过期时不能接管。
- worker 必须刷新 heartbeat。

### 17.6 UI 范围膨胀

完整画布会吞掉后端状态机和执行语义的实现预算。

v1 解决：

- 后端 schema 和 API 优先。
- 前端先表单化编辑 + graph preview。
- 等运行闭环稳定后再引入高级画布。

## 18. 第一批工程任务

建议从下面的最小闭环开始：

1. 在 `core-ai-api` 定义 Workflow request / response 和 web service 接口。
2. 在 `core-ai-server` 新增 domain 和 Mongo collection 注册，包括 `workflow_published_versions`。
3. 实现 `WorkflowDefinitionService.create/update/get/list/delete`。
4. 实现 `WorkflowValidationService`。
5. 实现 `WorkflowPublishService.publish()`，生成 `WorkflowPublishedConfig`。
6. 实现 `WorkflowPublishedVersion` 创建和按 version ID 加载。
7. 实现 `WorkflowRunner`，先支持 `START -> IF -> OUTPUT`。
8. 实现 run / node-run claim token、heartbeat 和基础恢复接管。
9. 增加 `WorkflowRun` / `WorkflowNodeRun` 查询 API。
10. 接入 `AGENT` 节点，创建并关联子 AgentRun。
11. 实现 `WorkflowToolExecutor`。
12. 接入 `TOOL` 节点。
13. 增加 `RUN_WORKFLOW` trigger action。
14. 如需 header/query 映射，升级 Trigger action contract 为 `TriggerRequestContext`。
15. 增加 `WorkflowSchedule`。
16. 增加前端 `/workflows` 管理页。

第一批代码完成的判断标准不是“画布像 Dify”，而是：

```
publish snapshot stable
run loads immutable published version
manual run works
node-run persisted
claim prevents duplicate execution
branch selection deterministic
Agent/Tool integration works
trace linked
draft edit does not affect published run
```
