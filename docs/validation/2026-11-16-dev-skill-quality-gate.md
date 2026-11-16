# Dev E2E — Skill Publish Quality Gate (2.0.246)

Date: 2026-11-16
Environment: dev (`dev-ai`), `https://core-ai-server.connexup-dev.net`
Image: `chancetop/core-ai-server:2.0.246`
Image digest: `sha256:f39cb352f5f8ad730b097bac54594bb72bfd074906c3280f063cfdf234c8dbed`
Auth: dev API key from `~/.core-ai/auth.json`; no auth file was modified.

## Result

PASS. The gate runs on the real dev deployment and the write path behaves as designed for the tested cases.

| Case | Request | Result |
|---|---|---|
| Valid upload | `skill_dir=e2e-quality-gate`, SKILL.md + eval/eval.json + README | `200`; skill `Stephen/e2e-quality-gate` created |
| Missing eval | same skill, no eval/eval.json | `422 SKILL_QUALITY_REJECTED`; issue `SKILL.EVAL_DATASET / EVAL_DATASET_NOT_PACKAGED` |
| Undeclared SDK use | Python fence `s.mcp.github.search_issues(...)`, no `allowed-tools` | `422`; issue `SKILL.PERMISSION_DECLARATION / PERMISSION_NOT_DECLARED` (`mcp:github/search-issues`) |
| Broken reference | `[missing](references/missing.md)` not packaged | `422`; issue `SKILL.PROGRESSIVE_DISCLOSURE / REFERENCE_NOT_PACKAGED` |
| Missing skill_dir | multipart without `skill_dir` | `400 SKILL_DIRECTORY_REQUIRED` |
| Directory mismatch | `skill_dir=wrong-dir`, frontmatter name `e2e-quality-gate` | `422`; issue `SKILL.NAME_IDENTITY / SKILL_DIRECTORY_MISMATCH` |
| Valid update | version 1.0.0 → 1.0.1, content changed | `200`; PG row `revision=2`, `qualityReceipt.gateResult=PASSED`, `directoryName=e2e-quality-gate`, `metadata.version=1.0.1` |
| Failed update after success | missing eval again | `422`; PG revision and receipt run id unchanged (`revision=2`, same `runId`) |
| Success headers | valid update to 1.0.2 | `x-skill-quality-gate-result: PASSED`, `x-skill-quality-warn-count: 0`, `x-skill-quality-run-id` present |

The typed error body includes the full `quality_report` with rule IDs, revisions, status, enforcement, field path, reason and suggestion. A failed publish did not change the stored skill.

## Bug found and fixed during E2E

`PgSkillStore.replaceIfRevision` used a literal JSON key in SQL:

```sql
... COALESCE((data ->> 'revision')::int, 0) = ?
```

core-ng rejects single quotes in SQL, so every editor upload returned `500 INTERNAL_ERROR`. The query now uses a bound key (`data ->> ?`) and was verified by the successful revision-2 update above. Fixed in `2.0.246` (commit `ecc2e021`).

## Not covered

- Semantic decision rules are not implemented yet; this run only exercises the deterministic gate.
- Repo sync and marketplace install were not exercised end to end.
- Existing skills missing eval/metadata were not dry-run audited.
