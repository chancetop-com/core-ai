# SEO Ops API

SEO Ops 是服务内部代运营团队的控制面，面向“一名运营人员管理几十家商户”的场景。功能默认关闭，并且不会自动调度 Agent，也不会修改商户网站或 Google Business Profile。

## 开启方式与权限

```properties
sys.seoops.enabled=true
sys.seoops.copilot.agent-id=published-advisory-agent-id
```

环境变量覆盖项为 `SYS_SEOOPS_ENABLED`。只有当配置指向已发布的 `AGENT`，且其发布配置不含工具、技能、子 Agent、数据集、沙箱和记忆时，Copilot 才会开放。

权限分为 `seoops.view`、`seoops.manage` 和 `seoops.approve`。数据可见性还受每个商户 `operator_user_ids` 的约束。

## 资源模型

- `seo_merchants` 是客户边界，记录可见的内部操作员。
- `seo_locations` 记录地点身份、外部标识与接入就绪状态。
- `seo_tasks` 是任务聚合；任务版本、证据引用、审批决定、聊天链接、Agent Run 链接及事件均只追加、不覆盖。
- 大型报告、文件、聊天正文和运行载荷继续保存在 Core AI 原有存储中；SEO Ops 只保存 ID、哈希、状态摘要与来源引用。

所有变更命令都要求非空 `idempotency_key`。同一键和相同内容会返回原结果，同一键对应不同内容则返回 `409`。任务变更要求 `expected_state_version`；审批还必须提交当前的 `task_revision` 与 `execution_spec_hash`。

## 调用示例

创建执行任务：

```http
POST /api/seo-ops/tasks
Content-Type: application/json

{
  "merchant_id": "merchant-1",
  "location_id": "location-1",
  "idempotency_key": "task-20260817-001",
  "definition": {
    "title": "修正 GBP 主分类",
    "task_type": "GBP_PROFILE",
    "source": "AUDIT",
    "priority": "HIGH",
    "impact": "HIGH",
    "owner_id": "operator-7",
    "due_at": "2026-08-20T10:00:00+08:00",
    "execution_spec": "{\"from\":\"Restaurant\",\"to\":\"Sichuan restaurant\"}",
    "required_evidence_types": ["BEFORE_SCREENSHOT", "AFTER_SCREENSHOT"]
  }
}
```

追加可独立校验的证据：

```http
POST /api/seo-ops/tasks/task-1/evidence
Content-Type: application/json

{
  "type": "AFTER_SCREENSHOT",
  "artifact_id": "artifact-9",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "captured_at": "2026-08-17T09:00:00+08:00",
  "verification_status": "VERIFIED",
  "requirement_key": "AFTER_SCREENSHOT",
  "expected_state_version": 2,
  "idempotency_key": "evidence-20260817-001"
}
```

审批预览只计算阻塞项，不改变状态：

```http
POST /api/seo-ops/tasks/task-1/approval-previews
Content-Type: application/json

{"task_revision": 1, "expected_state_version": 3}
```

批准经过复核的精确版本：

```http
POST /api/seo-ops/tasks/task-1/approval-decisions
Content-Type: application/json

{
  "decision": "APPROVE",
  "task_revision": 1,
  "execution_spec_hash": "sha256:reviewed-content-hash",
  "expected_state_version": 3,
  "idempotency_key": "approval-20260817-001"
}
```

若其他写入者先修改了任务，接口返回 `409`。此时必须重新调用 `GET /api/seo-ops/tasks/task-1`，复核新的证据和哈希，再使用新的幂等键提交决定。成功后，应通过同一个 GET 接口和 `/events` 独立回读审批记录及有序审计轨迹。

审批会在一次条件 Mongo 更新中把任务改为 `APPROVED`，并追加审批记录和事件；它不会创建 Agent Run，也不会触发任何外部写操作。

## 运营视图

- `GET /portfolio`：多商户健康度、工作量、负责人及地点就绪状态。
- `GET /inbox`：分页执行队列。
- `GET /reviews`：把复盘证据分为 `INSUFFICIENT_EVIDENCE`、`FACTUAL`、`CORRELATIONAL` 或 `CAUSAL_READY`，但不生成因果效应估计。
- `GET /reports`：报告证据及 `FRESH`、`AGING`、`STALE` 新鲜度。
- `GET /tasks/:id/events`：不可变审计时间线。
