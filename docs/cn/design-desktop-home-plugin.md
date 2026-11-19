# Desktop 插件「我的首页」（Home）— Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft / Implementing（设计与实现同批交付，见 §12） |
| 文档版本 | v1.0 |
| 日期 | 2026-11-19 |
| 作者 | core-ai |
| 需求提出者 / 决策人 | 用户 |
| 范围 | Desktop 插件包（我的首页）、插件 bridge 能力、core-ai-server Todo 模型扩展与每日摘要任务 |
| 事实来源 | 本工作区源码：`src/shared/pluginManifest.ts`、`src/main/plugins/*`、`src/main/remote/RemoteConversationClient.ts`、`core-ai-server` 的 report/for-you/session/schedule 代码、`core-ai-server-db-migration` V1–V46 |
| 交付边界 | 只改本功能相关文件；不部署 prod；dev 环境 E2E 按既有流程（CI 镜像）执行 |

## 0. 原始需求、需求拆解与文档治理

### 0.1 用户原始需求（原文）

> 设计并实现 Desktop 插件「我的首页」（Home）。
>
> 1. **Dataset Report 展示**：插件首页展示当前用户在 core-ai-server 上的 dataset reports（列表、可打开/预览详情，按权限过滤）。
> 2. **Todos**：展示、添加、完成/删除。
> 3. **Todo 自动生成**：每天定时任务总结**昨日历史会话**，生成 todo（幂等、可审计，避免重复）。
> 4. 先出技术设计，再实现；设计文档放 `docs/cn/design-desktop-home-plugin.md`。

### 0.2 需求拆解

| 编号 | 需求 | 验收要点 |
|---|---|---|
| H1 | 插件包与页面 | 通过现有插件机制安装/启用/授权后可打开「我的首页」页面；未授权时不可打开 |
| H2 | Dataset Report 列表 | 展示当前登录用户的报表；无 `report.view` 时给出明确无权限提示，而非空白 |
| H3 | Report 详情/预览 | 点击报表可查看元数据与数据视图（METRIC/TABLE/BAR/LINE） |
| H4 | Todos 展示 | 展示进行中/已完成待办，区分手动与自动来源 |
| H5 | Todos 增删改 | 新增、勾选完成/取消完成、删除 |
| H6 | 每日自动生成 | Asia/Shanghai 每天一次，总结昨日会话生成 todo；同日重跑不重复 |
| H7 | 可审计 | 每次生成可追溯到日期、会话、模型、结果计数与失败原因 |
| H8 | 安全边界 | 插件页面拿不到 API key / Node / 任意网络；只调用获批能力 |
| H9 | 降级 | 未登录、断网、服务端 5xx、无权限时页面可用且有重试入口 |

### 0.3 文档治理

- 本文件保留用户原始需求、需求编号、实现状态、待 Review 决策与 Review 记录。
- §13 的 Review 记录只陈述 AI 评审意见，不代表用户批准。
- 实现前已确认的关键决策写入 §11；实现落地后追加 §12。

## 1. 结论与范围

### 1.1 结论

1. **Home 是一个普通 Desktop 插件包**（`examples/plugins/home`），不新增宿主内置页面，不引入第二套插件加载路径。页面仍是 `ui.pages` + 独立 `WebContentsView`。
2. **数据全部走 server API**，由 **main 进程**持凭据代理。插件页面没有网络（CSP `connect-src 'none'`），也拿不到 API key、server 任意 URL 或 engine 全量 RPC。
3. **新增三个能力**：`reports.read`、`todos.read`、`todos.write`。它们只映射到 `PluginPageHost` 的固定方法表，每次调用重新校验 grant。
4. **Todo 复用现有 `/api/for-you/todos`**，扩展实体字段（来源、去重键、生成审计）而不是另建一套 Todo。
5. **每日摘要放在 server job**，不在 desktop 侧：desktop 可能整夜关闭，而 todo 必须与服务端会话数据同源、可审计。
6. **幂等由“确定性主键 + `insertIfAbsent` + 运行记录”三重保证**：同一用户、同一天、同一会话、同一标题最多一条；已完成的当日运行不再重跑。

### 1.2 非目标

- 不做报表编辑、导出、分页钻取或完整图表库；只做首页需要的最小预览。
- 不做跨用户报表、团队共享首页或首页布局拖拽。
- 不做自动 todo 的“审批”“撤回”“批量重新生成”。
- 不开放插件页面直连任意 server 路径（不允许页面自报 URL/路径）。
- 不改动 UAT/prod 配置，不发布 prod 版本。

## 2. 现状核查

### 2.1 Desktop 插件系统实际能力

| 事实 | 位置 | 对本设计的影响 |
|---|---|---|
| 插件页运行在独立 `WebContentsView`，`sandbox`/`contextIsolation`/无 Node，每实例独立 partition | `src/main/plugins/PluginPageHost.ts` | 页面无法直接读文件/网络，必须走 bridge |
| 页面 CSP `default-src 'none'`，`connect-src 'none'`，只允许包内资源与 `coreai-plugin-asset:` | `src/main/plugins/pluginPagePaths.ts` | 页面**不能**直接请求 `/api/reports`，证实必须由 main 代理 |
| bridge 是固定方法表，`pluginPage:call` 先校验实例、sender、再 `refreshPage` 重读 grant | `PluginPageHost.ts` | 新增能力时只需扩方法表，撤销天然生效 |
| 宿主已有 `sessions.read/write`、`schedules.*`、`memory.*`、`plugin.storage` 等能力 | `PluginPageHost.ts` / `pluginManifest.ts` | 本功能新增 `reports.read`、`todos.read`、`todos.write` |
| main 进程已有 auth 存储与带凭据 HTTP 客户端 | `src/main/auth/AuthStore.ts`、`src/main/remote/RemoteConversationClient.ts` | 复用 `activeAuthEntry` 与 `insecure` 语义，不把 key 暴露给 renderer/page |
| 插件安装是内容寻址包，安装/启用/授权分离 | `src/main/plugins/pluginStore.ts` | 未授权时页面 `resolvePage` 返回 null，导航仍可见但打开会被拒绝 |

**结论**：不需要新增 Electron 权限、不需要新窗口、不需要 renderer 内置面板。只需要 (a) 三个能力词、(b) 一组 `home.*` bridge 方法、(c) 一个 main 侧数据客户端。

### 2.2 Server 现状

| 能力 | 现状 | 复用/改动 |
|---|---|---|
| Dataset report 列表 | `GET /api/reports?offset=&limit=`，`@PermissionsRequired(report.view)`，返回 `total` + `reports` | 直接复用 |
| Report 详情 | `GET /api/reports/:id`，含 `spec_json`、`dataset_name`、`display_type`、`pin_*` | 直接复用 |
| Report 数据 | `GET /api/reports/:id/data?range_kind=&days=&page_size=&offset=`，返回 `columns_json` / `rows_json` / 状态/新鲜度 | 直接复用；`display_type` 取值 METRIC\|LINE\|BAR\|TABLE |
| Todo | `GET/POST/PUT/DELETE /api/for-you/todos`，类级 `dashboard.view`；实体 `user_todos` 只有 title/description/completed/priority/dueDate | 扩展字段 + 新审计表 + digest 查询 |
| 会话 | `chat_sessions`（`user_id`、`source`、`last_message_at`）与 `chat_messages`；PG 查询 `from` 含、`to` 不含 | 直接复用 |
| 定时任务 | core-ng `Job` + `schedule().fixedRate(...)`；`dailyAt` 存在但 `timeZone()` 必须在任何 trigger 之前设置，属全局配置 | 用 fixedRate + Asia/Shanghai 日历守卫，避免改全局时区 |
| PG 文档存储 | `PgDocumentStore.insertIfAbsent` = `INSERT ... ON CONFLICT DO NOTHING` | 作为幂等基础 |
| 迁移 | `core-ai-server-db-migration` V1–V46；dev 为 `SYS_STORE_MODE=pg` | 新增 V47 |

**关键发现**：`dailyAt` 的时区配置是一次性的全局开关（`SchedulerConfig.timeZone()` 只允许在任何 trigger 之前调用）。为了一个每日任务去改全局 scheduler 时区会影响所有既有 job，不可接受。因此采用 **15 分钟 fixedRate + 到达上海时间 07:30 后按日跑一次** 的方案（§7）。

## 3. 目标架构

```
┌────────────────────────────── Desktop ──────────────────────────────┐
│ 插件页面 home.html (WebContentsView, sandbox, 无网络)                │
│   window.coreaiPlugin.home.*                                        │
│        │ (固定 preload, token 绑定实例)                              │
│        ▼                                                            │
│ PluginPageHost  home.status/reports/report/reportData/todos/...      │
│   · 每次调用重读 grant（reports.read / todos.read / todos.write）     │
│   · 参数白名单、长度与频率限制                                        │
│        │                                                            │
│        ▼                                                            │
│ HomeDataClient (main)                                               │
│   · 只认 ~/.core-ai/auth.json 的 active entry                       │
│   · Authorization: Bearer <api_key>（不出 main）                     │
│        │ HTTPS                                                      │
└────────┼────────────────────────────────────────────────────────────┘
         ▼
┌──────────────────── core-ai-server ────────────────────┐
│ /api/reports, /api/reports/:id, /api/reports/:id/data  │  report.view
│ /api/for-you/todos (CRUD), /api/for-you/todos/digest   │  dashboard.view
│ HomeTodoDigestJob → LLMProviders → 写 user_todos        │  每日 07:30 后
└─────────────────────────────────────────────────────────┘
```

### 3.1 为什么不经 engine RPC

- report/todo 的所有权与鉴权都在 server（`report.view`、`dashboard.view`，按登录 token 确定用户），engine 的 RPC 面没有也不应该新增一套 server HTTP 代理。
- 页面只需要只读报表 + todo CRUD，不需要 agent 会话、工具审批或 sandbox 生命周期。
- 走 main 直连 server 时，凭据、TLS 选项（`insecure`）与 `remote:` 通道共用同一份 auth 状态，不会产生第二套登录。

### 3.2 为什么不把 todo 放进插件 `plugin.storage`

- 自动 todo 必须与昨日会话同源并由 server job 生成；客户端本地存储在 desktop 关闭时不存在。
- 跨设备/多端一致性与审计要求都由 server 承担。

## 4. 插件包设计

### 4.1 包结构

```
examples/plugins/home/
  .claude-plugin/plugin.json
  README.md
  pages/home.html
  pages/home.css
  pages/home.js
```

### 4.2 Manifest

```json
{
  "name": "My Home",
  "version": "0.1.0",
  "coreai": {
    "schemaVersion": 1,
    "id": "core-ai.home",
    "desktopApi": "^1.0.0",
    "capabilities": {
      "required": ["ui.pages", "reports.read", "todos.read", "todos.write", "plugin.storage"]
    },
    "contributes": {
      "pages": [{ "id": "home", "title": "My Home", "view": "pages/home.html", "icon": "home", "scope": "workspace" }]
    }
  }
}
```

说明：

- `ui.pages`：宿主允许打开页面。
- `reports.read` / `todos.read` / `todos.write`：本设计新增的 server 数据能力（§5）。
- `plugin.storage`：只用于缓存“最后一次成功快照 + 同步时间”，用于离线降级；不存凭据。
- `scope=workspace`：页面打开需要已选工作区（与现有 `openPluginPage` 前置条件一致）；数据本身按登录用户过滤，不读工作区文件。

### 4.3 页面信息架构

- 顶部：问候/用户、连接状态（server host）、最后同步时间、手动刷新。
- 左栏：**Reports** 列表（标题、数据集、更新时间、展示类型徽标）；无权限/空态/加载/错误分别渲染。
- 右栏（详情）：选中报表后展示
  - 元数据：标题、描述、dataset、revision、更新时间、新鲜度；
  - 数据：`METRIC` 大数字，`TABLE` 表格，`LINE`/`BAR` 轻量条形/折线（内联 SVG/CSS，不引第三方库）；
  - 范围切换：最近 7 天 / 30 天（复用 `range_kind=days`）。
- 下方：**Todos**
  - 快速新增（标题 + 可选描述/优先级）；
  - 列表：进行中在前、已完成折叠区；自动来源带 `Auto` 徽标并可展开“来自哪些会话/哪天生成”；
  - 操作：勾选完成、删除。写操作做乐观更新 + 失败回滚 + 错误提示。
- 每日摘要状态条：显示“昨日摘要：已生成 N 条 / 运行中 / 失败（原因）/ 今日尚未运行”。

### 4.4 页面 CSP 与资源

沿用现有插件页协议：包内 `pages/*` 由 `coreai-plugin-page://` 提供，`script-src 'self'`，无远程字体/图片。所有数据经 bridge 以 JSON 返回，页面自行转义（不 `innerHTML` 拼接用户数据）。

## 5. 能力与 bridge 契约

### 5.1 新增能力

| 能力 | 作用 | 宿主实现 | 服务端鉴权 |
|---|---|---|---|
| `reports.read` | 读取当前用户的 dataset reports 与数据 | `HomeDataClient.reports/report/reportData` | `report.view` |
| `todos.read` | 读取 todo 与每日摘要运行记录 | `HomeDataClient.todos/todoDigest` | `dashboard.view` |
| `todos.write` | 新增/更新/删除 todo | `HomeDataClient.createTodo/updateTodo/deleteTodo` | `dashboard.view` |

能力只决定“页面能否调用宿主方法”，**真正的用户权限仍由 server token 判定**。两者都要通过：撤销 grant 立即拒绝；无 `report.view` 时 server 返回 403，页面显示无权限态。

### 5.2 bridge 方法

| 方法 | 参数 | 返回 | 备注 |
|---|---|---|---|
| `home.status` | — | `{ signedIn, serverHost, userName, lastSyncAt }` | 不含 key、不含完整 URL 查询串；`userName` 为 auth 中的显示名 |
| `home.reports` | `{ offset?, limit? }` | `{ reports, total, fetchedAt }` | limit 1–50，默认 20 |
| `home.report` | `{ id }` | `{ report }` | id 白名单校验 |
| `home.reportData` | `{ id, rangeKind?, days?, pageSize?, offset? }` | `{ data }` | days 1–90，pageSize 1–200 |
| `home.todos` | — | `{ todos, fetchedAt }` | 服务端已按用户过滤 |
| `home.todo.create` | `{ title, description?, priority? }` | `{ todo }` | title 1–200；写频率 30/min |
| `home.todo.update` | `{ id, title?, description?, completed?, priority? }` | `{ todo }` | 不允许改 source/dedupe |
| `home.todo.delete` | `{ id }` | `{ deleted }` | — |
| `home.todoDigest` | `{ date? }` | `{ digest }` | `yyyy-MM-dd`，默认上海“今天” |

写方法统一限流（每页面实例 30 次/分钟），并对失败返回稳定错误码：`not_signed_in` / `forbidden` / `not_found` / `offline` / `server_error` / `invalid_request`。

### 5.3 错误映射

| 宿主错误 | 页面展示 |
|---|---|
| `not_signed_in` | “请先在应用内登录 core-ai-server”，提供重试 |
| `forbidden` | “当前账号缺少 report.view / dashboard.view 权限”，报表区与待办区分别提示 |
| `offline` | “无法连接服务器”，展示上次缓存快照 + 同步时间 |
| `server_error` | 展示状态码与 trace id（若响应头有），提供重试 |
| `invalid_request` | 不展示原始参数，只提示“请求参数无效” |

## 6. 数据访问与降级

### 6.1 HomeDataClient 规则

1. **只使用 active auth entry**：`activeAuthEntry(loadAuthEntries())`；没有则 `not_signed_in`。
2. **不接收 renderer 提供的 serverUrl**：避免插件把请求指向任意主机（SSRF/凭据外泄）。
3. **TLS**：沿用 entry 的 `insecure` 标志（仅本地/自签环境显式开启）。
4. **超时**：15s；错误分类：DNS/连接拒绝/超时 → `offline`，401 → `not_signed_in`，403 → `forbidden`。
5. **不记录 body**：日志只记录 method、路径模板、状态码、耗时；报表内容与 todo 文本不进日志。
6. **响应裁剪**：main 只向页面返回首页需要的字段，不透传未知字段（避免未来 DTO 扩张把敏感字段带进页面）。

### 6.2 降级策略

| 场景 | 行为 |
|---|---|
| 未登录 | 页面显示登录引导；不缓存不清空已有快照 |
| 网络失败 | 读取 `plugin.storage` 的最后成功快照（reports 标题列表 + todos 摘要），标注“离线数据 · 时间” |
| Report 无权限 | 报表卡片显示无权限说明；todos 不受影响 |
| Report data 失败 | 保留列表与元数据，详情区显示“数据加载失败”与重试 |
| 部分失败 | 两个区块独立刷新、独立错误态，不互相阻塞 |

缓存写入上限：reports ≤ 50 条摘要、todos ≤ 200 条摘要，总大小 < 64 KiB（与插件 storage 单值上限一致）。

## 7. Todo 模型

### 7.1 实体（`user_todos`，向后兼容扩展）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 手动：UUID；自动：确定性 id（§7.4） |
| `userId` | string | 所有者 |
| `title` | string | 1–200 字符 |
| `description` | string | 可选，≤ 2000 |
| `completed` | boolean | 完成状态 |
| `priority` | string | `high` / `normal` / `low`（缺省 `normal`） |
| `dueDate` | datetime | 可选 |
| `createdAt` / `updatedAt` | datetime | 现有 |
| `source` | string | `MANUAL` / `AUTO`（缺省 `MANUAL`，历史数据读作 MANUAL） |
| `originDate` | string | AUTO 专用：生成时总结的会话日期（`yyyy-MM-dd`，Asia/Shanghai） |
| `originSessionIds` | string[] | AUTO 专用：来源会话，最多 10 个 |
| `dedupeKey` | string | AUTO 专用：`auto:{date}:{sessionId}:{titleHash}` |
| `generatedBy` | string | AUTO 专用：模型 id |
| `generatedAt` | datetime | AUTO 专用：生成时间 |

约束：

- `source`、`origin*`、`dedupeKey`、`generatedBy` 只能由服务端生成任务写入；`ForYouTodoRequest` 不接受这些字段。
- 用户 `PUT` 更新仅允许 title/description/completed/priority/dueDate；即使请求带上 source 也会被忽略。
- 历史文档缺少新字段时按 `source=MANUAL`、其余为空处理，不做数据回填。

### 7.2 状态

不引入额外状态机：`completed=false` 为“进行中”，`completed=true` 为“已完成”。每日生成任务的“运行状态”属于审计实体，不是 todo 状态（§7.5）。

### 7.3 视图扩展

`ForYouTodoView` 增加：`source`、`origin_date`、`origin_session_ids`、`dedupe_key`、`generated_by`。首页据此展示来源徽标与溯源信息。

### 7.4 去重键与确定性 id

- `titleHash = sha256(normalizedTitle)[0..11]`，`normalizedTitle` = 去首尾空白、压缩连续空白、转小写。
- `dedupeKey = "auto:" + originDate + ":" + sessionId + ":" + titleHash`。
- `id = "auto-" + yyyyMMdd + "-" + sha1(userId + ":" + dedupeKey)[0..15]`。
- 同一用户、同一天、同一会话、同一标题 → 同一个 `id` → `insertIfAbsent` 永远只成功一次。
- 同一批次内再加一层内存集合：跨会话近似重复标题（归一化后相同）只保留第一条，避免两个会话各生成一条几乎一样的 todo。

### 7.5 生成审计实体（`home_todo_digest_runs`）

| 字段 | 说明 |
|---|---|
| `id` | `{userId}:{yyyy-MM-dd}`（每用户每天一条） |
| `userId` / `runDate` | 所有者与总结日期（上海时区） |
| `status` | `RUNNING` / `COMPLETED` / `FAILED` / `SKIPPED` |
| `model` | 实际使用的模型 id |
| `promptVersion` | 提示词版本（常量，例如 `v1`） |
| `sessionCount` / `messageCount` | 输入规模 |
| `todoCount` / `skippedCount` | 新写入条数与因去重跳过的条数 |
| `error` | 失败摘要（≤ 500 字符，不含会话内容） |
| `startedAt` / `completedAt` | 时间戳 |

审计 API：`GET /api/for-you/todos/digest?date=yyyy-MM-dd` 返回当前用户该日期的运行记录（无记录返回 `status=NOT_RUN` 视图）。首页状态条直接消费它。

## 8. 每日摘要任务

### 8.1 调度

- 类型：server job（`Job` + `schedule().fixedRate("home-todo-digest", job, Duration.ofMinutes(15))`）。
- 触发条件：`LocalTime.now(Asia/Shanghai) >= 07:30`；总结日期 = 上海时区的“昨天”（00:00:00 含，次日 00:00:00 不含）。
- 为什么不用 `dailyAt`：`SchedulerConfig.timeZone()` 是全局且必须在任何 trigger 之前调用；为单个任务改全局时区会改变所有既有 job 的语义。fixedRate + 日历守卫在 pod 重启/补跑时也更稳。
- 多副本：运行记录主键为 `{userId}:{date}`，`insertIfAbsent` 抢占；`COMPLETED` 直接跳过；`RUNNING` 未超时（30 分钟）跳过；`FAILED` 或超时的 `RUNNING` 允许重试。
- 补跑边界：只补“当天已过 07:30 但仍未完成”的情况；跨天不回溯（明确记录为限制）。

### 8.2 输入

1. 查询 `chat_sessions`：`timeField=last_message_at`，`from=yesterdayStart`，`to=todayStart`，`sources=["chat","scheduled"]`，排除 comparison variant B 与已删除会话。
2. 按 `userId` 分组；每用户最多 20 个会话，按最后消息时间倒序。
3. 每个会话读取该时间窗内的 `chat_messages`（`from`/`to` 同上，`sortField=created_at`），仅保留 user/agent 的文本与工具名摘要；单会话 ≤ 40 条消息、≤ 6000 字符。
4. 跳过没有 user 消息的会话（定时任务空跑、系统通知等）。
5. 单次 job 最多处理 200 个用户、总输入 ≤ 200k 字符；超出部分记日志并留待下次 tick（运行记录未创建）。

### 8.3 LLM 输出契约

提示词要求严格 JSON，且只输出“明日可执行的具体动作”，不重复会话中已完成的事：

```json
{
  "todos": [
    { "title": "跟进 A 客户的报价", "description": "会话里提到周五前回复", "priority": "high" }
  ]
}
```

- 最多 5 条；`title` 超长截断到 200；`priority` 非法回退 `normal`；空标题丢弃。
- 解析失败 → 该用户 `FAILED`，错误摘要记录“模型输出不是合法 JSON”，不写任何 todo。
- 模型：`SystemSettingsService.memoryExtractionModel()`（与记忆抽取共用便宜模型，管理员可在 Settings 修改）；`promptVersion` 常量随代码走。

### 8.4 写入与幂等

1. 为每个候选 todo 计算 `dedupeKey` 与确定性 `id`。
2. `todoStore.insertIfAbsent(todo)`；返回 false 计入 `skippedCount`。
3. 写运行记录：`COMPLETED` + 计数；异常时 `FAILED` + 摘要。
4. 所有写操作只发生在该用户自己的 `userId` 下；job 不修改用户已有 todo（不重排、不合并、不删除）。

### 8.5 可观测性

- `LOGGER.info("home todo digest completed, users={}, created={}, skipped={}, failed={}")`（job 级）。
- 每用户一行 debug/info：日期、会话数、生成数、跳过数、模型、耗时；失败为 `warn`。
- 不记录会话正文、todo 正文、用户 token。
- 运行记录本身就是最直接的可审计数据源；必要时通过 §7.5 的 API 查询。

## 9. 权限与安全边界

### 9.1 能力最小化

- 插件只声明 5 个能力；不含 `sessions.read`、`memory.*`、`terminal.exec`、`browser.control`、`agents.orchestrate`。
- `home.*` 不接受 serverUrl、workspace 路径、任意 HTTP 路径或请求头。
- 插件页面不能自报 `pluginId`/`workspace`：实例身份来自 token 绑定。

### 9.2 凭据

- API key 只存在于 main 进程（`~/.core-ai/auth.json`），页面与 renderer 都拿不到。
- `home.status` 只返回 `serverHost`（host[:port]），不返回完整 URL 的路径/查询串，也不返回 key。
- 错误信息不透传上游响应 body（避免把内部错误细节反馈给不可信页面）；只保留 status 与稳定错误码。

### 9.3 不可信内容

- Report 标题/描述、todo 文本均来自用户数据，页面按纯文本渲染（`textContent`），不拼接 HTML。
- 图表只使用页面内生成的数据，不加载远程图片/字体/脚本。

### 9.4 trusted / restricted workspace

- `ui.pages` 的打开前置条件与现有插件一致：需要已选 workspace；页面不读工作区文件，因此不受 workspace 文件信任等级影响。
- `terminal.exec` 的 `TRUST_REQUIRED` 逻辑与本功能无关；本插件不申请该能力。
- 未授权（awaiting-consent）/禁用/撤销后：`resolvePage` 返回 null，宿主关闭已打开页面，下一次 bridge 调用立即失败。

### 9.5 用户外部副作用

- 自动生成 todo 属于站内写入，不触发通知/外发；不调用 OCG、不发送消息。
- 删除 todo 是用户显式操作；页面无批量删除。

## 10. 失败处理与测试计划

### 10.1 失败处理矩阵

| 失败点 | 宿主行为 | 用户可见 |
|---|---|---|
| 未登录 | 抛 `not_signed_in` | 登录引导 + 重试 |
| Token 失效（401） | 抛 `not_signed_in` | 同上 |
| 缺少权限（403） | 抛 `forbidden` | 按区块提示缺失权限 |
| 网络/DNS/超时 | 抛 `offline` | 缓存快照 + 离线标识 |
| 5xx | 抛 `server_error` | 通用错误 + 重试 |
| 报表数据解析失败 | 页面局部错误态 | “数据格式不支持”，保留元数据 |
| job 模型失败 | 审计 `FAILED` | 状态条显示失败原因摘要 |

### 10.2 测试矩阵

| 层 | 用例 |
|---|---|
| Desktop 单测 | `HomeDataClient`：路径/查询参数、Authorization 头、401/403/网络错误分类、id 校验、不返回 key；`pluginManifest`：新能力可解析、缺失时拒绝；`pluginE2E`：home 包安装/授权/页面解析、撤销后不可解析 |
| Desktop check/build | `npm run check`、`npm run build`、`npm test` |
| 插件页面 smoke | 在 Electron smoke 中用 fixture 数据驱动 `home.*` 桩，断言报表表格/指标与 todo 增删的 DOM 行为 |
| Server 单测 | 摘要服务：窗口计算（上海时区）、无会话跳过、JSON 解析失败、去重键稳定、`insertIfAbsent` 计数、FAILED 后可重试、COMPLETED 不重跑；Todo 视图字段映射；权限注解 |
| Dev E2E | ① dev API：reports 列表/详情/数据（无权限与有权限两种情况按账号实际权限）；② 通过 `HomeDataClient` 对 dev 真实增删改 todo；③ 触发 job（或等待 tick）验证审计记录与自动 todo；④ 插件包在桌面安装→授权→打开页面（含离线/未登录态） |

### 10.3 非目标测试

- 不做 prod 验证；不做多用户并发压测；不验证 LLM 输出质量（只验证契约与幂等）。

## 11. 待 Review 决策

| 编号 | 决策 | 备选 | 当前选择与理由 |
|---|---|---|---|
| D1 | 数据通路 | 页面直连 server / engine RPC / main 代理 | **main 代理**：页面无网络且不能持凭据；engine RPC 不承担 server HTTP 代理 |
| D2 | 能力命名 | 复用 `sessions.read` / 新增域名 | **新增 `reports.read`/`todos.read`/`todos.write`**：语义清晰、可独立撤销 |
| D3 | 每日任务位置 | desktop agent schedule / server job | **server job**：desktop 可能关闭，审计与数据同源 |
| D4 | 调度方式 | 全局 `timeZone` + `dailyAt` / fixedRate + 守卫 | **fixedRate 15m + 07:30 守卫**：不污染全局 scheduler |
| D5 | 去重键 | 仅标题 / 标题+会话+日期 | **标题+会话+日期**：满足“同一会话/同一天不重复”，并允许不同会话各自提醒 |
| D6 | 模型 | 专用默认模型 / 复用记忆抽取模型 | **复用 `memoryExtractionModel()`**：已有管理员配置入口，成本可控 |
| D7 | 报表渲染范围 | 完整图表 / 最小预览 | **最小预览**：METRIC/TABLE/BAR/LINE，首页不承担完整报表工作台 |

## 12. 实现状态

### 12.1 已落地

| 项 | 落地位置 | 说明 |
|---|---|---|
| 插件包 | `core-ai-desktop/examples/plugins/home/` | manifest + `pages/home.html`、`home.css`、`home.js` + README；声明 5 个能力 |
| 能力词 | `core-ai-desktop/src/shared/pluginManifest.ts` | 新增 `reports.read` / `todos.read` / `todos.write` |
| main 数据客户端 | `core-ai-desktop/src/main/home/HomeDataClient.ts` | 只读 active auth entry；固定路径；401/403/网络分类错误；不向页面返回 key |
| bridge | `PluginPageHost.ts` / `pluginPagePreload.ts` | `home.status/reports/report/reportData/todos/todo.create/todo.update/todo.delete/todoDigest`；写限流 30/min；`{ok,value|error}` 信封 |
| main 接线 | `core-ai-desktop/src/main/main.ts` | `homeDataClient()` 单例，调用时重读 auth |
| Todo 模型 | `core-ai-server/.../domain/UserTodo.java` | `source`/`originDate`/`originSessionIds`/`dedupeKey`/`generatedBy`/`generatedAt`；历史文档读作 MANUAL |
| 视图/API | `core-ai-api/.../foryou/*` | `ForYouTodoView` 扩展；`GET /api/for-you/todos/digest?date=` |
| 审计实体 | `HomeTodoDigestRun` + PG/Mongo store | 每用户每天一条，id = `user:date` |
| 每日任务 | `ai.core.server.home.HomeTodoDigestService/Job` | 15 分钟 tick + 上海 07:30 守卫；虚拟线程并发；单用户失败不影响其他用户 |
| 调度接线 | `ForYouModule` | store/service/job 与 for-you 一起绑定；`fixedRate("home-todo-digest", 15m)` |
| 迁移 | `V47__home_todo_digest.sql` | `app.home_todo_digest_runs` + 两个索引 |
| Desktop 测试 | `test/homeDataClient.test.mjs`、`pluginE2E`、`pluginPageHost.smoke`、`homePage.smoke` | 14 项数据客户端用例 + 插件安装/授权 + 真实页面 DOM 增删改 |
| Server 测试 | `HomeTodoDigestServiceTest` | 9 项：确定性 id、同日不重跑、FAILED 重试、跳过空会话、非法 JSON、标题去重、会话归属、未知标签回退、历史数据兼容 |

### 12.2 与设计的偏差

1. **没有单独的 `HomeTodoModule`**：`ForYouService` 需要读 digest store，而 core-ng 的 `bind()` 会立即解析 `@Inject`；把 store/service/job 绑定进 `ForYouModule` 可避免模块顺序耦合，也避免 `ServerApp` 触达 checkstyle 文件长度上限。
2. **报表数据不返回 `display_type`**：服务端 `ReportDataView` 没有该字段；页面使用 `GET /api/reports/:id` 的 `displayType`。
3. **dev 环境报表数量为 0**：真实 dataset report 依赖会话生成链路。E2E 中报表区验证“无数据/无权限”真实行为；完整渲染由 `homePage.smoke` 的 fixture 覆盖。

## 13. Review 记录

| 日期 | 模型 | 范围 | 结论 |
|---|---|---|---|
| 2026-11-19 | deepseek-v4-flash（当前会话模型） | 设计自审 | 见下 |

自审要点：

1. `dailyAt` + 全局时区的冲突已核实（`SchedulerConfig.timeZone()` 必须在任何 trigger 之前），方案改为 fixedRate 守卫，避免影响既有 job。
2. 插件页面无法联网已由 CSP 源码确认；因此“页面直连 `/api/reports`”不可行，main 代理是必要条件而非偏好。
3. 幂等不是只靠去重键：`COMPLETED` 运行记录阻止同日重跑，`insertIfAbsent` 阻止并发重复写入，两者缺一不可。
4. 风险：dev 环境当前报表数量为 0，真实报表展示需要一条已生成的 dataset report；E2E 中若无法造数，将以“空态 + fixture 渲染”覆盖，并在报告中标注。

## 14. 修改记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-19 | 初稿：插件包、bridge、Todo 模型、每日任务与测试计划 |
