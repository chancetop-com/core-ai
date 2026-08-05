# Workflow 私有 Agent 快照与可搜索选择器设计

- 日期：2026-08-05
- 状态：已确认，待实现计划
- 范围：`core-ai-frontend`、`core-ai-server`

## 1. 背景

当前 Workflow 的 Agent 节点只允许选择 `PUBLISHED` Agent：

- Workflow 编辑器会一次性加载 Agent 列表，并在前端过滤掉非 `PUBLISHED` Agent。
- 节点配置使用原生下拉框，没有搜索、分页和按名称排序。
- Workflow 发布服务只会把 Agent 的 `publishedConfig` 冻结到 Workflow Version；即使 Agent 属于当前用户，只要尚未发布也无法使用。
- Workflow 运行时已经从 `WorkflowPublishedVersion.agentSnapshots` 读取冻结快照，而不是动态读取 Agent 当前配置。

这导致两个产品问题：

1. 只在某个 Workflow 内使用的小 Agent 也必须发布，公共 Agents 列表因此不断膨胀。
2. Agent 数量较多后，现有下拉框很难定位目标，且当前顺序不是按名称排列。

## 2. 目标

本次设计同时解决可用性和版本稳定性：

1. 用户可以在自己的 Workflow 中使用自己尚未发布的 Agent。
2. Test、Save Version 和 Publish Workflow 都使用不可变 Agent 快照，后续修改或删除源 Agent 不影响已有 Workflow Version。
3. 其他用户的 Agent 仍然只有在已发布时才可选择。
4. Agent 选择器支持分组、搜索、分页、类型过滤、状态展示和稳定的名称排序。
5. Public Workflow 可以包含创建者自有未发布 Agent 的配置快照，但不会把该 Agent 变成公开 Agent，也不会泄露其配置或继承创建者权限。
6. 保持现有 Workflow Version 和已发布 Workflow 的兼容性。

## 3. 非目标

本次不做以下事项：

- 不新增 `PRIVATE` AgentStatus。当前产品中的“私有 Agent”指当前用户拥有且未公开的 `DRAFT` Agent。
- 不把 Agent 配置直接内联到 Workflow 图节点中。
- 不递归打包私有 Sub-agent、Tool、Dataset 或 Secret。
- 不提供 Agent 最近使用、收藏、标签或文件夹能力。
- 不改变 Tool、Dataset、Secret 和其他运行资源的既有授权模型。
- 不允许通过公开 Workflow 向调用者委托创建者的凭据或私有资源权限。

## 4. 已确认的方案

采用“选择引用、版本冻结”的方案：

- 编辑态 Workflow 图继续保存 `agent_id` 引用，避免产生两套 Agent 编辑模型。
- 创建 Workflow Version 时，把可执行 Agent 配置冻结到服务端的 `agentSnapshots`。
- 自有 Agent 使用当前可编辑配置生成快照，不要求先发布 Agent。
- 非自有 Agent 和 System Default Agent 只能从可用的 `publishedConfig` 生成快照。
- Runtime 始终执行 Workflow Version 中的不可变快照。

没有采用以下方案：

- 只优化前端下拉框：无法解决小 Agent 被迫发布造成的列表污染。
- 把完整 Agent 配置内联进图节点：会复制 Agent Editor 的能力，带来双向同步、导入导出和权限模型复杂度。

## 5. 权限与配置来源

“可在编辑器中看到”和“可创建可执行快照”都必须由服务端授权，前端过滤不构成安全边界。

| Agent 归属与状态 | 选择器可见 | 快照配置来源 | 允许执行 |
| --- | --- | --- | --- |
| 当前用户的 DRAFT Agent | 是，显示 `Draft` | 当前可编辑配置 | 是 |
| 当前用户的 PUBLISHED Agent | 是，显示 `Published` | 当前可编辑配置 | 是 |
| 其他用户的 PUBLISHED Agent | 是，显示 `Published` | `publishedConfig` | 是 |
| 其他用户的 DRAFT Agent | 否 | 无 | 否 |
| 可用的 System Default Agent | 是，归入 Shared | `publishedConfig` | 是 |
| 已删除或无权限 Agent | 否；已选节点显示失效 | 无 | 否 |

自有 PUBLISHED Agent 使用“当前可编辑配置”是有意行为：用户在修改自己的 Agent 后，可以直接测试或保存 Workflow Version，而不需要先重新发布该 Agent。Workflow Version 会记录本次实际冻结的来源时间，避免审计歧义。

## 6. Agent 选择器体验

Agent 节点配置中的原生 `<select>` 替换为可搜索的选择器。

### 6.1 分组

选择器提供两个明确分组：

- `My Agents`：当前用户拥有的 DRAFT 和 PUBLISHED Agent。
- `Shared Agents`：其他用户已发布的 Agent，以及可用的 System Default Agent。

两个分组分别请求和分页，避免把全量 Agent 下载到浏览器。首次打开默认展示 `My Agents`，用户可以切换到 `Shared Agents`。

### 6.2 展示字段

每个选项只展示选择所需信息：

- Agent 名称
- 类型：`AGENT` 或 `LLM_CALL`
- 状态：`Draft` 或 `Published`
- 归属：`Mine`、`Shared` 或 `System`

不向选择器接口返回 prompt、model config、tools、environment variables 或其他 Agent 配置。

### 6.3 查询与排序

- 节点类型固定查询类型：Agent 节点只请求 `AGENT`，LLM 节点只请求 `LLM_CALL`。
- 输入搜索词后以 250ms debounce 请求服务端。
- 名称使用大小写无关的前缀搜索。
- 默认按规范化名称升序排列，并以 Agent ID 作为稳定的次级排序键。
- 每页 20 条，滚动到底加载下一页。
- 新搜索必须取消或忽略旧请求，避免慢响应覆盖新结果。

### 6.4 已选项与异常状态

- 已选 Agent 即使不在当前搜索页，也固定显示在选择器顶部。
- 如果 Agent 在编辑期间被删除、取消授权或类型改变，节点保留原 ID，但显示 `Unavailable — replace this agent`。
- 列表加载失败时保留已有选择，不清空节点配置，并提供重试入口。
- 无搜索结果时分别显示 My/Shared 范围的空状态，不把“无权限”误报为“网络失败”。

## 7. Agent Options API

新增面向 Workflow 编辑器的最小权限接口，而不是继续让前端组合通用 Agent 列表：

```http
GET /api/workflows/agent-options
  ?scope=mine|shared
  &type=AGENT|LLM_CALL
  &query=<optional-prefix>
  &page=1
  &limit=20
```

响应示例：

```json
{
  "items": [
    {
      "id": "agent-id",
      "name": "Review Assistant",
      "type": "AGENT",
      "status": "DRAFT",
      "ownership": "MINE",
      "updated_at": "2026-08-05T08:00:00Z"
    }
  ],
  "page": 1,
  "limit": 20,
  "total": 1
}
```

服务端规则：

- `scope=mine`：只查询 `user_id == caller`，允许 DRAFT 和 PUBLISHED，不包含 System Default。
- `scope=shared`：只返回 `status == PUBLISHED` 且 `publishedConfig` 有效的非自有 Agent；可用 System Default 也在此范围。
- `type` 必填，非法类型返回 400。
- `limit` 最大为 50。
- 返回 DTO 不复用完整 AgentDefinition，防止以后序列化字段扩张造成配置泄露。
- Service 在返回结果和创建快照时重复执行访问校验，不能信任客户端提交的 `agent_id`。

### 7.1 名称规范化与索引

为了在 MongoDB 的严格查询设置下稳定支持名称排序和前缀搜索，AgentDefinition 新增持久化字段 `name_key`：

- 值为 trim 后的名称按 `Locale.ROOT` 转为小写。
- create、update、import 和其他写入 Agent 名称的路径必须同步维护。
- 部署迁移先回填历史数据，再创建索引，最后启用新接口。

建议索引：

```text
{ user_id: 1, type: 1, name_key: 1, _id: 1 }
{ status: 1, type: 1, name_key: 1, _id: 1 }
```

搜索条件使用经过正则转义的 `^<name_key-prefix>`，不支持任意子串扫描。Shared 查询在 `status + type` 索引范围内排除当前用户，并在返回前再次校验 `publishedConfig`。

## 8. Workflow Version 快照

### 8.1 统一快照构建器

Agent 发布和 Workflow 快照不得各自复制一套字段映射。服务端提取统一的配置构建器，例如：

```text
AgentExecutableConfigFactory.fromEditableDefinition(agent)
AgentExecutableConfigFactory.fromPublishedConfig(agent)
```

其中：

- `fromEditableDefinition` 将当前 AgentDefinition 的可执行字段构建成与 `AgentPublishedConfig` 等价的不可变配置。
- `fromPublishedConfig` 读取并复制已发布配置。
- Agent Publish、Workflow Preview/Test、Workflow Save Version 共用该构建逻辑和校验规则。
- 构建失败必须返回包含 Workflow node ID、Agent ID 和可操作原因的错误，不允许静默退回旧 publishedConfig。

### 8.2 捕获时机

以下操作均创建新的 Workflow Version 并立即冻结快照：

- Test/Preview
- Save Version
- 直接 Publish 时隐式创建的版本

发布一个已经保存的 Workflow Version 时，继续使用该 Version 已冻结的快照，不重新读取 Agent。否则从 Save 到 Publish 之间的 Agent 修改会破坏版本确定性。

### 8.3 存储结构

保留现有字段及 JSON 格式：

```text
agentSnapshots: Map<node_id, serialized AgentPublishedConfig>
```

新增可选的审计元数据：

```text
agentSnapshotSources: Map<node_id, {
  agent_id,
  source_kind: OWNED_EDITABLE | PUBLISHED,
  source_updated_at,
  captured_at
}>
```

要求：

- Runtime 只依赖 `agentSnapshots`，不依赖审计元数据。
- 老版本没有 `agentSnapshotSources` 仍可正常执行。
- Agent 改名、修改、取消发布或删除后，已保存 Version 的快照保持不变。
- 快照只保存在服务端 Version 文档中，不写回公开图节点。

### 8.4 顶层依赖边界

本期只冻结所选顶层 Agent 的配置。若自有 DRAFT Agent 引用了不能按现有运行契约解析的私有 Sub-agent、Tool、Dataset 或 Skill，Version 创建必须失败并指出具体依赖。

不允许出现“顶层 Agent 已冻结，但内部依赖在运行时悄悄漂移或越权”的半快照状态。递归依赖打包需要独立设计，不纳入本期。

## 9. Public Workflow 安全边界

发布 Workflow 是 Workflow 自身的公开行为，不等于发布其引用的 Agent：

- 自有 DRAFT Agent 的 `status` 和 `publishedConfig` 不发生变化。
- 该 Agent 不进入 Shared Agents，不出现在其他用户的 Agent 搜索结果中。
- Public Workflow 的读取、详情、图导出接口不得返回 `agentSnapshots` 或私有来源详情。
- 原 Public Workflow 的运行可以使用服务端保存的快照，但执行身份仍是本次 Workflow Run 的调用者。
- Tool、Dataset、Secret 和其他能力继续按调用者授权解析，不继承 Workflow 创建者的身份、令牌或凭据。

### 9.1 Public Snapshot Safety Validator

公开发布前，对来源为 `OWNED_EDITABLE` 的快照执行 fail-closed 校验：

- 禁止嵌入创建者的明文 secret 或 sandbox environment value。
- 禁止引用只有创建者可访问的 Tool、Dataset、Sub-agent 或其他运行资源。
- 允许调用者绑定的变量、公开资源，以及能由现有授权系统在运行时重新校验的 capability reference。
- 校验失败时阻止发布并定位到具体节点和字段；Private Test/Preview 是否可运行仍遵循当前用户自身权限。

这保证简单的 prompt/model 型小 Agent 可以随 Public Workflow 发布，同时不会把 Workflow 发布变成隐式凭据委托机制。

## 10. Clone、Export 与 Import

### 10.1 Clone Public Workflow

- Clone 只复制可公开的 Workflow 图和配置，不复制原 Version 的私有 `agentSnapshots`。
- 如果图中的 Agent ID 对克隆者不可访问，节点保留结构但标记为 unresolved。
- 编辑器明确提示 `Private embedded agent is not available — choose a replacement`，保存新 Version 前必须替换。
- 不允许通过 clone 响应、错误消息或调试字段恢复原私有 Agent 的 prompt、model、tools 或其他配置。

### 10.2 Export/Import

- 默认 Workflow export 不包含服务端 Agent 快照。
- Import 后使用现有引用检查；不可访问或未发布的非自有 Agent 标记为 unresolved。
- 本期不新增“把私有 Agent 一并打包导出”的格式。

## 11. 错误处理

创建 Version 时按节点收集并返回确定性错误：

- Agent 不存在或已删除。
- Agent 类型与节点类型不匹配。
- Agent 不属于当前用户且未发布。
- Shared Agent 的 `publishedConfig` 缺失或损坏。
- Editable Agent 配置不完整，无法构建可执行快照。
- 私有嵌套依赖不满足既有运行契约。
- Public Workflow 包含不可移植的 secret 或 owner-only capability。

错误响应不得包含无权限 Agent 的配置内容。前端应聚焦第一个失败节点，并在节点上展示同一错误摘要。

## 12. 兼容性与迁移

- 现有仅使用 PUBLISHED Agent 的 Workflow 行为不变。
- 现有 `agentSnapshots` 格式不变，Runtime 不需要数据迁移。
- `agentSnapshotSources` 为可选字段，旧 Version 无需回填。
- AgentDefinition 的 `name_key` 需要一次性回填；迁移应可重复执行。
- 在回填和索引完成前，不启用依赖 `name_key` 的新 Options API。
- 前端上线应与服务端 API 同一发布批次；接口不可用时显示加载错误，不退回全量且仅前端过滤的旧安全模型。

## 13. 测试要求

### 13.1 服务端单元与集成测试

Agent Options：

- mine 返回当前用户的 DRAFT/PUBLISHED，且不返回其他用户 DRAFT。
- shared 只返回其他用户 PUBLISHED 和可用 System Default。
- 类型过滤、名称前缀搜索、大小写无关排序、稳定分页正确。
- DTO 不包含 Agent 配置字段。
- 伪造 `agent_id` 无法绕过创建 Version 时的访问校验。

快照：

- 自有 DRAFT 和 PUBLISHED 都冻结当前可编辑配置。
- 其他用户 PUBLISHED 冻结 `publishedConfig`。
- 其他用户 DRAFT、损坏 publishedConfig 和类型不匹配均被拒绝。
- 保存 Version 后修改、取消发布或删除源 Agent，旧 Version 运行结果仍使用原快照。
- 发布已保存 Version 不重新捕获 Agent。
- 老 Version 在没有 `agentSnapshotSources` 时仍可运行。

公开边界：

- 发布 Workflow 不改变私有 Agent 状态。
- Public Workflow API、clone 和 export 不泄露私有快照。
- Clone 后不可访问 Agent 节点必须替换。
- owner-only secret/capability 会阻止公开发布。
- Public Workflow Run 不继承 Workflow 创建者权限。

### 13.2 前端测试

- My/Shared 分组、状态标签和类型过滤正确。
- 搜索 debounce、分页追加和陈旧响应隔离正确。
- 结果按名称升序展示。
- 已选项不在当前页时仍可见。
- Agent 失效时节点显示替换提示，不静默清空。
- 加载错误、空结果和 Version 校验错误能被区分。

### 13.3 端到端验收

1. 创建一个未发布的小 Agent。
2. 在 Workflow 中搜索并选择它，Test 成功。
3. Save Version，然后修改或删除源 Agent；旧 Version 仍按原快照运行。
4. 发布包含该 Agent 的安全 Public Workflow；Agent 本身仍未发布。
5. 另一用户能运行该 Public Workflow，但在 Agent 列表中看不到该私有 Agent。
6. 另一用户 Clone Workflow 后，该节点要求选择替代 Agent，且无法读取原快照配置。

## 14. 完成标准

以下条件全部满足才视为功能完成：

- 用户无需发布自有小 Agent 即可 Test、保存和发布 Workflow Version。
- Shared Agent 仍严格要求可用的 publishedConfig。
- Agent 选择器不再全量加载，支持搜索、分页和按名称稳定排序。
- 已保存 Workflow Version 对 Agent 后续修改、取消发布和删除保持确定性。
- Public Workflow 不公开私有 Agent，也不传递创建者权限或秘密。
- Clone、Export、Import 和旧 Version 行为有自动化测试覆盖。
