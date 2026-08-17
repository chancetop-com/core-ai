# SEO Ops API

SEO Ops is an internal operations control plane for teams that manage SEO on behalf of many merchants. It is disabled by default and never dispatches an Agent or mutates a merchant's website or Google Business Profile.

## Enablement and permissions

```properties
sys.seoops.enabled=true
sys.seoops.copilot.agent-id=published-advisory-agent-id
```

The environment override is `SYS_SEOOPS_ENABLED`. The Copilot endpoint is enabled only when the configured definition is a published `AGENT` whose published configuration has no tools, skills, sub-agents, datasets, sandbox, or memory.

Permissions are `seoops.view`, `seoops.manage`, and `seoops.approve`. Data visibility is additionally constrained by each merchant's `operator_user_ids`.

## Resource model

- `seo_merchants` is the customer boundary and stores visible internal operators.
- `seo_locations` stores location identity, external identifiers, and onboarding readiness.
- `seo_tasks` is an aggregate. Revisions, evidence references, approval decisions, conversation links, Agent Run links, and events are append-only.
- Large reports, files, chat transcripts, and run payloads remain in their existing stores; SEO Ops keeps IDs, hashes, status summaries, and source references only.

Each command accepts a nonblank `idempotency_key`. Replaying the same key and content returns the existing result; reusing the key for different content returns `409`. Task mutations require `expected_state_version`. Approval also requires the current `task_revision` and `execution_spec_hash`.

## Examples

Create an execution task:

```http
POST /api/seo-ops/tasks
Content-Type: application/json

{
  "merchant_id": "merchant-1",
  "location_id": "location-1",
  "idempotency_key": "task-20260817-001",
  "definition": {
    "title": "Correct GBP primary category",
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

Append independently verifiable evidence:

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

Preview approval blockers without changing state:

```http
POST /api/seo-ops/tasks/task-1/approval-previews
Content-Type: application/json

{"task_revision": 1, "expected_state_version": 3}
```

Approve the exact reviewed revision:

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

If another writer changes the task first, the response is `409`; reload `GET /api/seo-ops/tasks/task-1`, review the new evidence and hash, then submit a new decision. After success, use the same GET endpoint and `/events` to independently read back the stored decision and ordered audit trail.

Approval changes the task to `APPROVED` and appends a decision plus event in one conditional Mongo update. It does not create an Agent Run or invoke any external mutation.

## Operational views

- `GET /portfolio` returns multi-merchant health, workload, owners, and location readiness.
- `GET /inbox` returns the paged execution queue.
- `GET /reviews` classifies review evidence as `INSUFFICIENT_EVIDENCE`, `FACTUAL`, `CORRELATIONAL`, or `CAUSAL_READY`. It never emits a causal effect estimate.
- `GET /reports` returns report evidence and `FRESH`, `AGING`, or `STALE` freshness.
- `GET /tasks/:id/events` is the immutable audit timeline.
