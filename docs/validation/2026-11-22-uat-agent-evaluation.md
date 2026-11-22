# UAT — Agent Evaluation deployment and smoke (2.0.332)

Date: 2026-11-22
Environment: UAT (`uat-ai`, FBRDevAKSCluster), `https://core-ai-server.connexup-uat.net`
Image: `chancetop/core-ai-server:2.0.332`
Namespace/deployment: `uat-ai/core-ai-server`
Previous image: `chancetop/core-ai-server:2.0.329`

## Deployment

- Applied V48 (`agent evaluation`) to the UAT PostgreSQL cluster before rollout.
  - Flyway history: `app` schema, version 47 → 48, success.
  - 12 `eval_*` tables created.
- Rolled the deployment to `chancetop/core-ai-server:2.0.332`.
- Both replicas are `Running` on 2.0.332; the deployment reports a successful rollout.

## Smoke result

| Check | Result |
|---|---|
| `https://core-ai-server.connexup-uat.net/` | 200 |
| `/api/capabilities` | 200 |
| `/api/auth/me` with the existing UAT admin key | `Stephen` / `admin` / `*` |
| `/api/evaluations` | 200, `{"evaluations":[],"total":0}` |
| UI `/experiments/evaluations` | loads; Experiment → Evaluations nav present; empty state shown |

Evidence: [UAT evaluations page](assets/agent-evaluation-uat-20261122/uat-evaluations.png)

## Not covered

No UAT evaluation data or run was created. The full import → run → report → baseline →
compare flow was already exercised in dev (see `2026-11-21-dev-agent-evaluation.md`); UAT
acceptance testing is left to the user. Prod was not touched.
