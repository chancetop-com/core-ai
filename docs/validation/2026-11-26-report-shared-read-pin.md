# Report shared read + shared home pin (2026-11-26)

## Deployment

- Image: `chancetop/core-ai-server:2.0.390` (commits `79173a92b` → `6036270a7`)
- CI `Build Server` run `45757118265` success (rerun after the known
  `MediaTaskRunIntegrationTest` flake)
- Dev: revision **817**, 2/2 Ready; digest
  `sha256:0684383d922d6fb45fee7084659f92191135cd353578648a7b1105ea7bc173f6`
- UAT: revision **307**, 2/2 Ready, same digest; env commit `fbr-env-project` **`476c370b`**
- No migration for this change.

## Behavior

- Web report read is global for users with `report.view`: `GET /api/reports`, `/:id`,
  `/:id/data` use `listAll` / `getShared` / `dataBundleShared`; no owner filter.
- `ReportView` now returns `created_by` (UserStore name, fallback email) and `owned`.
- Update/delete stay owner-only (`getOwned` / `lockOwned`); non-owner delete returns 404.
- Any user with `dashboard.view` + `report.view` can pin someone else's report to their own home
  (`HomeReportService` uses report lookup by id, not owner).
- Deleting a report iterates `pinsByReport(reportId)`, removes every user's pin, and bumps every
  affected layout revision. Pins do not become orphans.
- Pin cards carry the first component's `display_type` and `display_json`; a multi-component
  dashboard pin renders its first component (GBP: METRIC_GROUP with five series), not a line chart
  built from a DASHBOARD type.
- Chat / self-harness report tools remain owner-scoped; only the web read path is shared.

## Dev E2E (2026-11-26)

Fixture: a small GENERAL dataset (`business_date DATE`, `value NUMBER`) with 3 rows; one report
owned by another user, one owned by the caller.

- List: other-owned report `owned=false`, own report `owned=true`.
- Detail/data of the other-owned report: 200, `owned=false`, creator fallback email, value `6`.
- Pin the other-owned report: 200; `POST /api/for-you/report-pins/data` returns `READY`.
- Delete the other-owned report as the caller: **404 REPORT_NOT_FOUND**.
- Pin the caller's own report from a second reader row; delete the report as its owner:
  pins for that report `0`, the reader's layout revision bumped to `1`.
- Cleanup: report rows and the fixture dataset deleted.

## UAT verification (2026-11-26)

- Stephen (`stephenzeng@chancetop.com`, admin) lists all 5 reports:
  Joanne's four (`64e5f8bc…`, `954e2b98…`, `23283c40…`, `78c13a96…`) with `owned=false` /
  `created_by=JJ`; his HR report `owned=true`.
- `GET /api/reports/64e5f8bc…` → 200, `owned=false`, `display_type=DASHBOARD`; data → 200,
  KPI impressions 219,051.
- Pin `64e5f8bc…` to Stephen's home → 200; pin view `display_type=METRIC_GROUP`,
  `display_json` has 5 series; pin data `READY`. The pin stays on Stephen's home; Joanne's report
  is unchanged.
- Non-owner delete of `64e5f8bc…` → 404.

## Not covered

- UI screenshot of the shared report / pinned KPI card; API and display metadata verified.
- Updating another user's report (not exposed by design; owner-only).
- Prod unchanged.

## Related

- `core-ai-server/src/main/java/ai/core/server/report/application/ReportService.java`
- `core-ai-server/src/main/java/ai/core/server/report/application/HomeReportService.java`
- `core-ai-server/src/main/java/ai/core/server/report/store/PgDatasetReportStore.java`
- `core-ai-server/src/main/java/ai/core/server/report/store/PgHomeReportLayoutStore.java`
- `core-ai-frontend/src/pages/reports/ReportList.tsx`
- `core-ai-frontend/src/pages/reports/ReportDetail.tsx`
- `core-ai-frontend/src/pages/reports/PinnedReportsSection.tsx`
