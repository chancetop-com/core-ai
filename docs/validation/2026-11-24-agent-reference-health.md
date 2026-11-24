# Agent reference health — implementation validation (2026-11-24)

## Scope

Implementation of `docs/cn/design-agent-reference-health.md`: derived health badge for Agent lists, draft/published
reference detection, skill-deletion and Service API resync notifications, PostgreSQL issue/notification ledger, and
latest author-side quality status.

## Verification performed

- Clean verification worktree at `8c0a7bea8`, containing only this feature's files (not the concurrent persistent
  workspace changes):
  - `:core-ai-server:compileTestJava` — PASS
  - `:core-ai-server:test --tests ai.core.server.agenthealth.AgentHealthServiceTest --tests ai.core.server.agenthealth.PgAgentReferenceIssueStoreTest --tests ai.core.server.agent.PgAgentDefinitionStoreTest --tests ai.core.server.quality.PgAgentQualityStoreBatchTest` — PASS
  - `:core-ai-api:check` — PASS
  - `:core-ai-server:pmdMain` / `pmdTest` — this feature's files have no violations; the task still fails on
    `WorkspaceService.java` and `WorkspaceSandboxCRBuilderTest.java`, which belong to the concurrent workspace change.
  - `:core-ai-server:checkstyleMain` / `checkstyleTest` — this feature's files have no violations; the task still fails on
    workspace files (`WorkspaceService`, `WorkspaceIds`, `sys.properties`, etc.), which belong to the concurrent change.
- Working tree frontend:
  - `npm --prefix core-ai-frontend test -- agentHealth` — 5/5 PASS
  - `npm --prefix core-ai-frontend run build` — PASS
- Local PostgreSQL: `V53__agent_reference_health.sql` objects and indexes applied and queried successfully. The migration
  was renumbered from V51 to V53 because V52 is already taken by the concurrent persistent-workspace change; V53 avoids
  Flyway out-of-order failure if V52 reaches an environment first.

## Not performed

- Dev deployment / dev E2E: HEAD itself currently fails the repository quality gates because of the concurrent
  persistent-workspace commit (`WorkspaceService`, `WorkspaceModule`, `WorkspaceSandboxCRBuilderTest`). A CI image
  built from this tree would fail `gradle check`, so no dev image was produced or deployed.
- UAT/prod validation.
- Mongo-mode ledger notification path (PostgreSQL is the active store; Mongo keeps the in-memory/derived badge behavior).
