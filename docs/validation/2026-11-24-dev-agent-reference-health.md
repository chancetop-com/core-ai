# 2026-11-24 Dev E2E — Agent reference health (2.0.354)

## 环境

- Server: `https://core-ai-server.connexup-dev.net`（namespace `dev-ai`）
- Image: `chancetop/core-ai-server:2.0.354`
- CI: `Build Server` run `45356449758`（commit `80e7cfe91`），构建成功
- 运行 pod：2/2 Ready；两个 pod 的 `imageID` 均为
  `docker.io/chancetop/core-ai-server@sha256:52b5915312102e47bc2dcaf2cc171db744713e922d45be7faf05e1cba27faab4`
- 部署方式：`kubectl -n dev-ai set image` + `rollout status`；`fbr-env-project` manifest 未改动
- 数据库迁移：dev 的 Flyway head 仍是 V48（V49/V50 及 V52 的一部分由其他工作流手工应用）；
  V52 `app.workspaces*` 对象已存在，V53 `agent_reference_issues` / `agent_health_state` 由本次手工执行；
  V53 两张表的 owner 已改为 `core_ai`

## 结果

### 1. 派生 Badge 与引用问题

创建临时 Agent `agent-health-e2e-1795508364`（无 Skill，`tools` 指向不存在的
`api-operation:no-such-app:no-such-service:no-such-op`）：

- `POST /api/agents` 返回 `health.status=ATTENTION`、`needs_attention=true`，
  `reference_issues[0]` 为 `API / BROKEN / API_TOOL_NOT_FOUND / sources=[DRAFT]`
- `GET /api/agents/:id` 与 `GET /api/agents?limit=50` 都返回同样的 health
- 说明 Badge 是读取时派生，不依赖台账或通知

### 2. 质量失败 Badge

对同一临时 Agent 执行 `POST /api/agents/:id/quality-checks`：

- HTTP 200，`gate_result=REJECTED`，`block_fail_count=2`（`CFG.REFERENCE_AVAILABLE` +
  `DESC.FUNCTION_SUMMARY_REQUIRED`）
- 随后 `GET /api/agents/:id` 返回 `quality.state=FAILED`、`block_fail_count=2`、
  `content_changed=false`、`policy_changed=false`
- `health.status` 仍为 `ATTENTION`，说明质量失败与引用问题合并为同一个 Badge

### 3. 通知与台账

- 通知列表出现 `AGENT_ATTENTION`，`agent_id` 指向临时 Agent，状态 `UNREAD`
- `app.agent_reference_issues` 中该 Agent 的 active issue `notifiedAt` 已写入，
  `refType=API`、`reason=API_TOOL_NOT_FOUND`
- 通知与台账只用于去重/审计；Badge 不读取它们

### 4. 删除 Agent 与 view 注册缺陷

首次删除临时 Agent 返回 500：

```
view class is not registered, viewClass=ai.core.server.agenthealth.PgAgentReferenceIssueStore.IdRow
```

删除动作已经移除 Agent，但 `resolveByAgent` 在查询 issue id 时失败。修复：在
`AgentHealthModule` 的 PostgreSQL 分支注册 `PgAgentReferenceIssueStore.IdRow`，
发布 2.0.354（commit `80e7cfe91`）。重新创建临时 Agent `b23f14d4-...` 后删除返回
`204`，`agent_reference_issues` 中对应行已写入 `AGENT_DELETED` 解决记录。

### 5. 清理

- 两个临时 Agent 已删除
- 对应的 `AGENT_ATTENTION` 通知与 `agent_reference_issues` 测试行已从 dev 数据库删除
- 未改动其他 Agent / Skill / Service API 数据

## 回归

- `Build Server` run `45356449758`：`gradle check` 通过（checkstyle / PMD / SpotBugs / 全量 tests）
- 本功能在 `2.0.354` 的干净 worktree 上已通过 `:core-ai-server:checkstyleMain`、
  `:core-ai-server:pmdMain`、`:core-ai-server:spotbugsMain`、`:core-ai-server:spotbugsTest`
- 前端 `agentHealth.test.ts` 5/5、`npm run build` 通过

## 备注

- 删除后直接 `GET /api/agents/:id` 仍返回 500（不是 404）；这是既有错误映射，不属于本次修复范围。
- V53 未写入 Flyway history（与 dev 当前的 V45/V48 手工迁移方式一致），文件为幂等 SQL。
