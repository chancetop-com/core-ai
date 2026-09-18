# Tech Design：默认 Assistant 个人化（懒 Fork + 按用户 Memory）

> 状态：待评审（v1）
>
> 日期：2026-08-28
>
> 范围：core-ai-server 为主，core-ai-frontend / core-ai-cli 配合改造

## 1. 背景与目标

### 1.1 现状问题

当前"默认助手"是 `agents` 集合中**一条全平台共享的记录**（`_id = "default-assistant"`，`user_id = "system"`，`system_default = true`），由 `SchemaMigrationVDefaultAgent` 幂等 seed。共享通过"读放行 + 写收紧"实现：

- `AgentQueryHelper.buildAccessFilter()`：`system_default = true` 对所有人可见；
- `AgentDependencyAccessPolicy.isOwnedEditable()`：系统 agent 永远不可编辑，所有人走已发布快照执行；
- `AgentDefinitionService.requireAdminForSystemDefault()`：只有 admin 能改。

这带来两个无法绕过的限制：

1. **无法开启 memory**。memory 存储（`agent_memories` / `agent_memory_extraction_cursors` / memory 实验两张表）**只有 `agent_id` 一个维度，没有 user 维度**。全平台共用一个 `agent_id`，开 memory 意味着 A 用户的领域知识/踩坑/轨迹会注入 B 用户的 system prompt——跨用户信息泄漏。代码里已有两处补丁承认了这个事实：
   - `AgentMemoryConsolidationJob:67` 按 agent 名字 `"assistant"` 硬编码排除记忆抽取；
   - 被注释掉的 `AgentDefinitionService.createFromSession()` 里写死 `enableMemory = FALSE`。
2. **用户无法个性化**。普通用户不能换模型、调 prompt、挂自己的工具（例如个人 QQ/微信 channel 推送工具，让 agent 主动给自己发消息）——因为写权限收紧到 admin。

### 1.2 目标

把 `default-assistant` 从"所有人直接使用的实例"重新定位为**模板（template）**：

- **懒 fork**：用户首次使用 assistant 时，从模板 fork 出一份归该用户所有的个人 assistant（后称 **personal assistant**）；
- **按用户 memory**：personal assistant 默认开启 memory，每个用户的 memory 落在自己 fork 的 `agent_id` 桶里，天然隔离，**不改 memory 存储模型**；
- **可自定义**：personal assistant 就是一条普通的用户 agent，owner 可自由换模型、改 prompt、增删工具（含私有 channel 工具）、配置 sandbox 等；
- **兼容**：CLI / 前端 / API 中硬编码 `"default-assistant"` 的入口继续可用（服务端透明重定向到个人副本）。

### 1.3 非目标（v1 范围外）

| 项 | 说明 |
|---|---|
| 其它系统 agent 的 fork | `agent-builder`、`llm-call-builder`、`builtin-project-*` 保持现状（共享、memory 关闭）；机制预留但不启用 |
| 模板变更自动同步到已 fork 副本 | v1 不做（见第 7 章），仅提供"重置为最新模板"（P2） |
| memory 数据模型加 user 维度 | 不需要——fork 后 agent_id 天然按用户隔离 |
| 用户私有 ToolRegistry 体系改造 | 沿用现有 ToolRegistry / channel 配置机制，本设计只保证 personal assistant 可挂载 |

## 2. 现状关键事实（已核实）

| 事实 | 位置 |
|---|---|
| 模板 seed：`$setOnInsert` 幂等插入，`_id="default-assistant"` | `migration/SchemaMigrationVDefaultAgent.java:15,165-190,266-271` |
| `AgentDefinition` 已有 `userId` / `systemDefault` / `enableMemory` 字段 | `domain/AgentDefinition.java:20-22,76-80` |
| memory 四张表均只按 `agent_id` 查询，零 user 维度 | `memory/AgentMemory.java`、`AgentMemoryService.java` |
| memory 开关是 opt-out：仅显式 `false` 关闭 | `memory/AgentMemoryService.java:24-27` |
| 记忆抽取 job 按名字 `"assistant"` 排除（误伤所有同名 agent） | `memory/AgentMemoryConsolidationJob.java:67,138-142` |
| 无任何 agent fork/clone API；`createFromSession()` 整段被注释 | `agent/AgentDefinitionService.java:333-360` |
| 可复用的 clone 范式 | `workflow/WorkflowDefinitionService.clone()`（L233-256）、`AgentExecutableConfigFactory`（深拷贝）、`AgentDependencyAccessPolicy.detachedConfig()`（字段清单） |
| 列表置顶按 `_id=="default-assistant"` 硬匹配 | `agent/AgentListHelper.java:21,53-65` |
| 前端默认选中 = 列表第一项（依赖服务端置顶） | `core-ai-frontend/src/pages/chat/Chat.tsx:543-554` |
| 硬编码 `"default-assistant"` 的客户端 | `QuickActionDialog.tsx:150,199`、`CliApp.java:294`、`RemoteCommandHandler.java:53-55,92-93` |
| `(user_id, name)` 重名校验 | `AgentDefinitionService.create():58-63` |
| dataset 与 memory 同样按 `agent_id` 共享 | `domain/DatasetRecord.java:24-25` |

## 3. 方案概览

```text
                        ┌─────────────────────────────────────┐
                        │  agents 集合                         │
                        │                                     │
   admin 编辑 ────────▶ │  default-assistant (模板)            │
                        │  user_id=system, system_default=true│
                        │  enable_memory=false                │
                        └──────────────┬──────────────────────┘
                                       │ 懒 fork（首次使用时，深拷贝 publishedConfig）
              ┌────────────────────────┼────────────────────────┐
              ▼                        ▼                        ▼
   ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐
   │ assistant:alice@x  │  │ assistant:bob@x    │  │ assistant:carol@x  │
   │ user_id=alice@x    │  │ user_id=bob@x      │  │ user_id=carol@x    │
   │ system_default=null│  │ enable_memory=true │  │ + 自定义模型/工具   │
   │ enable_memory=true │  │                    │  │ + 私有 QQ channel  │
   └─────────┬──────────┘  └─────────┬──────────┘  └─────────┬──────────┘
             ▼                       ▼                       ▼
      agent_memories           agent_memories          agent_memories
      (agent_id 隔离)          (agent_id 隔离)         (agent_id 隔离)
```

核心机制 = 一个集中的解析器 + 一次幂等 fork：

1. 所有以 `"default-assistant"` 为 agentId 的使用入口（会话创建、run 触发、A2A、channel/schedule 执行）先经过 `PersonalAssistantResolver.resolve(agentId, userId)`；
2. 解析器发现是模板 ID 时，查/建该用户的 personal assistant（懒 fork，幂等），返回个人副本的 id；
3. 之后的权限校验、执行、memory 注入/抽取全部作用在个人副本上；
4. 模板本身保留：admin 继续在模板上维护"出厂配置"，只影响**之后新 fork** 的用户。

## 4. 数据模型

### 4.1 `AgentDefinition` 扩展（`agents` 集合）

| 字段 | Mongo field | 类型 | 说明 |
|---|---|---|---|
| `forkedFrom` | `forked_from` | String（新，可空） | 来源模板的 agent id（v1 恒为 `"default-assistant"`）；用于识别 personal assistant、支持后续"重置为模板"和统计 |
| `forkedAt` | `forked_at` | ZonedDateTime（新，可空） | fork 时间，配合模板 `updatedAt` 可判断副本是否落后于模板 |

不新增 collection，不改 memory 表。

### 4.2 personal assistant 的 ID 规则

**确定性 ID：`"assistant:" + userId`**（userId 为标准化小写邮箱）。

选择理由：

- **并发幂等**：懒 fork 用 `$setOnInsert` upsert 固定 `_id`，两个并发请求（如用户同时开两个会话）不会产生重复副本，无需分布式锁——与 `SchemaMigrationVDefaultAgent.upsert()` 同一范式；
- **可寻址**：客户端/运维可以不经查询直接推导出某用户的 assistant id；
- **一人一份的硬保证**：`_id` 即唯一性约束本身，配合 9.2 的"不可删除"决策，用户与 personal assistant 一一对应、与账号同生命周期。

### 4.3 fork 时的字段拷贝清单

以模板的 `publishedConfig` 为源（与运行期 `detachedPublished` 语义一致——用户 fork 到的是"当前对外生效"的配置，而不是 admin 编辑中的草稿），复用 `AgentExecutableConfigFactory.fromPublishedConfig()` 的 JSON 深拷贝：

| 字段 | 处理 |
|---|---|
| `systemPrompt/model/multiModalModel/preferCaptionPath/temperature/thinkingEffort/maxTurns/timeoutSeconds/tools/skillIds/subAgentIds/inputTemplate/variables/responseSchema/sandboxConfig` | 深拷贝自模板 publishedConfig |
| `systemPromptId` | 不拷贝（publish 时已解析为字面量并清空，publishedConfig 里即为字面 prompt） |
| `id` | `"assistant:" + userId` |
| `userId` | 使用者本人 |
| `name` / `nameKey` | `"{用户显示名}'s Assistant"`（显示名缺失时用邮箱 local-part，如 `"alice's Assistant"`）；若该用户已有同名 agent（`(user_id, name)` 校验），追加数字后缀重试 |
| `description` | 模板 description |
| `systemDefault` | **null**（普通用户 agent，owner 可完全编辑） |
| `enableMemory` | **true**（本次重构的核心目的） |
| `type` | `AGENT` |
| `status` | `PUBLISHED`，并同步生成自己的 `publishedConfig`（`fromEditableDefinition()`），保证 fork 完立即可用、可被 schedule/webhook 引用 |
| `datasetConfig` | **不拷贝（置 null）**。`DatasetRecord.agentId` 与 memory 同为 agent 维度共享模型，拷贝 datasetId 会造成跨用户数据共享；v1 模板本身无 dataset 配置，此项为防御性决策 |
| `forkedFrom` / `forkedAt` | `"default-assistant"` / now |
| `webhookSecret` 等实例级字段 | 重新生成，不继承 |

依赖可达性：模板当前 `tools = [builtin-all]`、无 skill/sub-agent，fork 后 publish 校验必然通过。防御性处理：fork 时按 `requireAccessibleSkills` / `requirePublishedSubAgentDependencies` 同套校验跑一遍，不可达的依赖**剔除并记 warn 日志**（而不是让 fork 失败——用户第一次打开聊天不该被 500 挡住）。这是 workflow clone 已记录踩坑（clone 后引用原作者资源导致 republish 失败）的规避。

## 5. 核心流程

### 5.1 新组件 `PersonalAssistantService`（core-ai-server）

```java
public class PersonalAssistantService {
    // 模板 id 集合，v1 只有 default-assistant，预留扩展
    static final Set<String> FORKABLE_TEMPLATE_IDS = Set.of("default-assistant");

    /** 入口统一调用：模板 id → 个人副本 id；其它 id 原样返回 */
    public String resolve(String agentId, String userId) {
        if (!FORKABLE_TEMPLATE_IDS.contains(agentId)) return agentId;
        return getOrFork(agentId, userId).id;
    }

    /** 懒 fork：确定性 _id + $setOnInsert，幂等 */
    AgentDefinition getOrFork(String templateId, String userId) { ... }
}
```

要点：

- `getOrFork` 先按 `_id = "assistant:" + userId` 直查（热路径一次点查，代价可忽略）；miss 时构建副本文档后 `$setOnInsert` upsert，再回读——并发下两边都拿到同一条；
- `resolve` 只对平台真实用户生效；`userId = "system"`、API manager、API sub user（`ctk_`）均直接返回原 id，继续走模板的已发布快照（sub user 不需要 memory，也避免为业务侧终端用户批量产生 agents 文档）；
- fork 成功后为新 agent 写入 owner 的 `RESOURCE_TYPE_AGENT` 权限（与用户自建 agent 一致的路径），保证后续 `permissionService.check` 通过。

### 5.2 接入点（server 侧透明重定向）

| 入口 | 文件 | 改动 |
|---|---|---|
| 交互式会话创建 | `web/SessionCreateHelper.createSessionFromAgent()`（`AgentSessionWebServiceImpl:113-133` 之前） | 进入权限校验前先 `resolve(request.agentId, userId)`，**权限校验与会话记录都使用解析后的 id**（`ChatSession.agentId` 落个人副本 id） |
| Run 触发 | `run/AgentRunService`（L57/100/120 各入口） | 同上 |
| A2A | `a2a/A2AAgentIdResolver` 之后 | 同上 |
| Channel 消息、Schedule、Webhook 执行 | 各执行入口 | 按记录 owner 的 userId resolve；已指向个人副本 id 的记录原样执行 |

重定向对客户端透明：老 CLI（`CliApp.java:294` 兜底 `"default-assistant"`）、前端 `QuickActionDialog` 无需改动即可获得个人副本，会话/trace 中记录的是个人副本 id。

### 5.3 列表与默认选中

- `AgentListHelper`：`listWithDefaultAssistantFirst()` 的置顶目标从 `_id=="default-assistant"` 改为**当前用户的 personal assistant**（`_id=="assistant:"+userId`）；若用户尚未 fork，列表请求即视为"使用"，**在 list 时懒 fork**（保证前端 `Chat.tsx:551` "选中列表第一项"的既有行为直接得到个人副本，前端零改动也正确）；
- `AgentQueryHelper.buildAccessFilter()`：`my_agents=true` 时**排除 `_id ∈ FORKABLE_TEMPLATE_IDS` 的模板本身**（个人副本已在列表中，同时出现模板 + 副本会让用户困惑）；admin 通过现有 agent 管理页（`my_agents=false` / 直接按 id）继续维护模板；
- 前端 `AgentSelector.tsx` 的 `system_default` 分组逻辑：personal assistant 的 `system_default=null`，会自然落入 "owned" 分组并且置顶，符合预期；可选优化（P2）：按 `forked_from != null` 展示 "Personal" 徽标。

### 5.4 Memory 生效链路（本次重构的收益点）

fork 后无需改 memory 存储，只需修一处硬编码：

1. **`AgentMemoryConsolidationJob:67` 删除 `EXCLUDED_AGENT_NAME = "assistant"` 按名排除**，改为按 id 排除模板：`FORKABLE_TEMPLATE_IDS.contains(trace.agentId)`（同时修复了它误伤所有名为 "assistant" 的子 agent 的既有 bug；`SubAgentAssembler:195` 的 fallback 子 agent 若需排除，应按其真实 id/无 id 特征处理，不再按名字）；
2. personal assistant `enableMemory=true` → `AgentRunBuilder` / `AgentSessionManager` 的既有注入逻辑（`prepareInjection(definition.id)`、`SearchMemoryTool(agentId)`）直接生效，`agent_id` = 个人副本 id，天然按用户隔离；
3. 模板保持 `enable_memory=false`（seed 已插入的存量记录需一次 migration 显式补 `false`，防 opt-out 语义下 null 被判定为开启——见 6.1）。

### 5.5 用户自定义（含私有 channel 工具）

personal assistant 就是普通用户 agent，无需新增编辑能力：

- `isOwnedEditable()` 对它返回 true（owner 且非 system_default）→ 换模型、改 prompt、调 temperature/maxTurns、增删 tools/skills/sub-agents、配 sandbox，全部走现有 AgentEditor / update+publish 流程；
- 用户可挂载自己注册的 MCP/API 工具（如个人 QQ/微信推送 channel 工具），让 agent 主动给自己发消息；工具凭证跟随 ToolRegistry/channel 配置的既有归属与权限模型，**不会回流到模板**（fork 是单向拷贝，模板永不读取副本）；
- 唯一新增约束：**禁止用户删除后"降级"体验的保护不做**——owner 可以随意改坏自己的 assistant，随时可通过"重置为模板"（P2）恢复。

## 6. Migration 与兼容性

### 6.1 SchemaMigration（新增一个版本）

1. 模板 `default-assistant`：`$set { enable_memory: false }`（存量记录该字段为 null，而 memory 语义是 opt-out，必须显式关死）；`agent-builder` / `llm-call-builder` / `builtin-project-*` 同样补 `false`；
2. 不回填存量用户的 fork——完全依赖懒 fork，第一次使用时创建；
3. 存量 `chat_sessions` / `agent_runs` / `traces` 中 `agent_id = "default-assistant"` 的历史记录**不做任何处理**：模板记录仍存在，历史查看不受影响；历史会话继续按原 agent_id 行为运作，不迁移、不重定向（已决策）。

### 6.2 兼容性矩阵

| 引用点 | 现状 | 重构后 |
|---|---|---|
| CLI 兜底 `"default-assistant"`（`CliApp:294`、`RemoteCommandHandler:53,92`） | 直接使用模板 | 服务端 resolve 透明转个人副本，**CLI 零改动** |
| 前端 `QuickActionDialog.tsx:150,199` | 同上 | 同上，零改动 |
| 前端 `Chat.tsx:543-554` 选列表第一项 | 依赖服务端置顶模板 | 服务端置顶改为个人副本（list 时懒 fork），零改动 |
| `AgentSelector.tsx` system_default 分组 | 模板在 default 组 | 个人副本落 owned 组置顶；default 组内不再出现 assistant 模板 |
| `agent-builder` / `llm-call-builder` 引用（QuickActionDialog、AgentList） | 共享系统 agent | 不在 v1 fork 范围，行为不变 |
| A2A / Channel / Schedule 存量 `agent_id` | 指向模板 | 执行时按 owner resolve；新建时即落个人副本 id |
| 文档（cli-modes.md 等） | 描述 `default-assistant` 默认值 | 语义不变（仍可传该 id），补一句"服务端会解析为你的个人助手" |

### 6.3 权限

- 模板：维持 `requireAdminForSystemDefault`，admin 独占写；普通用户对模板 id 的"使用"经 resolve 落到自己副本上，**不再需要**对模板本身的执行授权（RBAC 白名单里对 `default-assistant` 的既有授权在 resolve 后自动等效于对个人副本的授权——fork 时同步写 owner 权限，见 5.1）;
- memory API（`AgentMemoryWebServiceImpl`）现状只校验 RBAC 动作不校验归属，个人副本上线后用户 memory 属敏感数据，**需补 agent 归属校验**（owner 或 admin 才能读/删某 agent 的 memory）——列入 P1 必做。

## 7. 模板更新策略（关键取舍）

**决策：v1 不自动同步，模板更新只影响新 fork。**

| 方案 | 说明 | 结论 |
|---|---|---|
| A. 不同步（选定） | fork 即分叉；admin 改模板只影响之后首次使用的用户 | 语义最简单、零冲突合并问题；与"用户可自由改配置"的目标一致——自动同步必然与用户自定义冲突 |
| B. 字段级覆盖跟踪 | 记录用户改过哪些字段，未改字段跟随模板 | 实现和心智成本高，字段间存在耦合（prompt 与工具集配套），收益不成比例 |
| C. 提示 + 手动重置 | 副本 `forkedAt` 早于模板 `updatedAt` 时前端提示"模板已更新"，提供一键"重置为最新模板"（保留 memory，重拷配置） | 作为 P2 增强，补足 A 的运营缺口（如模板 prompt 重大修复无法触达存量用户） |

## 8. API 变更清单

| API | 变更 |
|---|---|
| `POST /api/sessions`（agent 会话创建）等既有入口 | 无契约变更；服务端内部 resolve |
| `GET /api/agents?my_agents=true` | 无契约变更；返回结果中模板被个人副本替换（副本带新字段 `forked_from`/`forked_at`，`AgentDefinitionView` 增加两个可空字段） |
| `GET /api/agents/my-assistant`（新，可选） | 显式获取（并懒创建）当前用户 personal assistant；给前端"设置我的助手"入口用，P1 可不做（list 已覆盖） |
| `PUT /api/agents/:id/reset-to-template`（新，P2） | 方案 C 的重置端点 |

## 9. 边界与异常

### 9.1 并发首次使用

确定性 `_id` + `$setOnInsert`：并发 fork 收敛为一条记录，双方回读同一文档。无锁、无重试循环。

### 9.2 个人 assistant 的删除（已决策：不允许用户删除）

personal assistant 与用户账号同生命周期：fork 出来就是该用户的常驻助手，**owner 不可删除**（`AgentDefinitionService.delete()` 对 `forked_from != null` 的 agent 拒绝，除 admin 外）；session 本身也没有关联删除机制，删副本只会留下悬挂引用。仅当用户账号被删除时，由账号清理流程一并删除其 personal assistant 及 `agent_memories` / extraction cursor。

### 9.3 非终端用户身份（已决策：只有平台真实用户 fork）

- schedule/webhook 的系统上下文、`userId="system"`：resolve 直接透传原 id，不产生 fork；
- API manager / API sub user（`ctk_`）：不 fork、不开 memory，继续使用模板的已发布快照（sub user 是业务侧 API 用户，不需要个人记忆；也避免 agents 文档随 sub user 数膨胀）。

### 9.4 模板被 admin 误删

resolve 时模板 miss 且用户副本也不存在 → 明确报 NotFound（与现状一致）；已 fork 用户不受影响（副本独立存在）。

### 9.5 数据规模

每活跃用户新增 1 条 `agents` 文档 + 若干 memory 文档。`(user_id, type, name_key, _id)` 既有索引覆盖列表查询；memory 表已有 agent_id 过滤模式，无新索引需求（如 memory 量增长，属 memory 系统自身容量话题）。

## 10. 实施拆分

**P1（本次）**

1. `AgentDefinition` 加 `forked_from` / `forked_at` 字段 + `AgentDefinitionView` 透出；
2. `PersonalAssistantService`（resolve + getOrFork，含依赖校验、权限写入）；
3. 接入点改造：SessionCreateHelper、AgentRunService、A2A、channel/schedule 执行入口；
4. `AgentListHelper` 置顶逻辑改个人副本 + list 时懒 fork；`AgentQueryHelper` 列表排除模板；
5. `AgentMemoryConsolidationJob` 去掉按名排除，改按模板 id 排除；
6. SchemaMigration：系统 agent 补 `enable_memory=false`；
7. memory API 补 agent 归属校验；
8. 测试：并发 fork 幂等、老 CLI 硬编码 id 全链路、memory 抽取只落个人桶、模板不可被普通用户改。

**P2**

- "模板已更新"提示 + `reset-to-template`；
- 前端 Personal 徽标、"我的助手"设置入口（`GET /api/agents/my-assistant`）；
- `agent-builder` 等其它系统 agent 是否纳入 fork 机制的评估。

## 11. 已决策事项（评审记录 2026-08-28）

1. **存量会话**：不处理，历史 `chat_sessions.agent_id="default-assistant"` 保持原状，不迁移不重定向；
2. **副本删除**：owner 不可删除，personal assistant 与用户账号同生命周期，账号删除时级联清理（见 9.2）；
3. **API sub user**：不 fork、不开 memory，继续使用模板已发布快照（见 9.3）；
4. **命名**：`"{用户显示名}'s Assistant"`，显示名缺失时用邮箱 local-part。
