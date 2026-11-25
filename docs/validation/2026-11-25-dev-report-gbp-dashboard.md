# Dev E2E — GBP dashboard report capabilities (2026-11-25)

## Deployment

- Image: `chancetop/core-ai-server:2.0.385` (includes 2.0.382–2.0.385)
- Commit: `9ed244814`; CI `Build Server` run `45669170878` success
- Namespace: `dev-ai`; deployment 2/2 Ready; both pods report OCI index digest
  `sha256:ade737d4a2902149b77fef74195ad6a27c3d9254fcc4d0362814e5e20561099b`,
  matching `docker buildx imagetools inspect chancetop/core-ai-server:2.0.385`
- No schema migration; report tables are the existing V46 objects.

## Fixture

The 7,253 rows embedded in `D:\gbp_dashboard.html` were used as the dataset:

- 15 fields: `date DATE`, `location_name STRING`, 13 NUMBER measures
- 18 locations, 549 business dates (2025-05-21 … 2026-11-20), snapshot written 2026-11-23
- Dataset created through `POST /api/datasets`; rows bulk-inserted into
  `dataset.dataset_records` via `psql` so the generated typed view is exercised exactly like ingest.
- The report spec was inserted directly into `app.dataset_reports` to pin the deterministic
  multi-component definition. This bypasses `create_dataset_report` on purpose; the new spec shape is
  covered by `ReportSpecValidatorTest`, and the create-tool path itself was already exercised in the
  2.0.360/2.0.362 E2E. The dataset/report were deleted after the run.

Report definition (7 components, 10 components budget unused):

- parameters: `stores` (STRING, multiple, default `[]`, `options_field=location_name`),
  `range` (DATE_RANGE, default `LAST_N_DAYS_OF_DATA 30`)
- `kpi_main` METRIC_GROUP: impressions / desktop (ADD dm+ds) / mobile (ADD mm+ms) / actions / direction
- `kpi_meta` METRIC_GROUP: `COUNT_DISTINCT(date)`, `COUNT_DISTINCT(location_name)`, desktop share
  (`RATIO(ADD(dm,ds), impressions)`, PERCENT)
- `impressions_trend` LINE: SUM(impressions) + 7-day `MOVING_AVG`
- `devices` LINE: 4 device SUM series
- `actions_trend` LINE: SUM(actions)
- `action_composition` MEASURE_BAR: 7 action measures
- `store_ranking` BAR HORIZONTAL: category `location_name`, `order_by=impressions DESC`, `limit=10`

## Results

Default window (`LAST_N_DAYS_OF_DATA 30`, source max 2026-11-20):

- window `2026-10-21T16:00:00Z` … `2026-11-20T16:00:00Z` (local 10-22 … 11-21, 30 buckets)
- KPI: **215,523** impressions, **23,105** desktop, **192,418** mobile, **47,843** actions,
  **18,449** direction requests, **10.72%** desktop share
- coverage: **30** days, **18** stores
- trend: 30 buckets, 7-day moving average starts at the 7th bucket; bucket sum 215,523
- device chart: 30 points × 4 series
- action composition: 18,449 / 16,141 / 7,658 / 3,558 / 2,037 (0-value conversations and
  food orders are filtered)
- store ranking: 10 rows, first `Choice Brooklyn - Upper West Side = 63,002`, strictly descending

Parameter and range checks:

- `parameters_json={"stores":["Choice Brooklyn - Upper West Side"]}` → 63,002 impressions
- `parameters_json={"range":{"kind":"SOURCE_RANGE"}}` → 549 buckets, **2,062,949** impressions
- `GET /api/reports/:id` → `parameter_options_json` with 18 sorted stores

UI checks on the deployed web console:

- Last 30 days is the default; Last 7 days → 49,739 impressions; All data → 2,062,949
- multi-select: “only” selects one store → 63,002; Clear → 0 / 18 and `NO_DATA`; Select all → 215,523
- KPI group renders 5 cards in one row at a wide viewport (3+2 at the 959 px browser pane),
  all labels and NUMBER/PERCENT formats come from measure metadata
- ranking and composition are full-width; horizontal ranking labels are truncated with tooltips
- screenshots: `docs/validation/2026-11-25-dev-report-gbp-dashboard.png`,
  `docs/validation/2026-11-25-dev-report-gbp-dashboard-charts.png`

## Not covered

- `create_dataset_report` tool call with the new spec shape; validator + prior create E2E cover the
  contract, but a future agent regression should create the GBP dashboard through the tool.
- Pin/home rendering of a multi-component dashboard still shows the first component; pinning the whole
  dashboard is P2.
- KPI subtitle templates (`{days} 天 · {stores} 门店`), moving-average/device toggles, TopN “others”
  are not implemented.
- UAT and prod were not changed. UAT remains on 2.0.370; deploying 2.0.385 there needs separate
  approval.

## Related

- `core-ai-server/src/main/java/ai/core/server/report/application/ReportRangeResolver.java`
- `core-ai-server/src/main/java/ai/core/server/report/application/ReportValueAggregator.java`
- `core-ai-server/src/main/java/ai/core/server/report/application/ReportDerivedMeasureValidator.java`
- `core-ai-frontend/src/pages/reports/ReportDetail.tsx`
- `core-ai-frontend/src/pages/reports/ReportRenderer.tsx`
- `.core-ai/tasks/dev-report-gbp-dashboard-e2e.py`
