# Dev E2E — Dataset report typed business time (2026-11-24)

## Deployment

- Image: `chancetop/core-ai-server:2.0.352`
- CI: `Build Server` run `45353127539`, commit `47413dd2d`
- Namespace: `dev-ai`; deployment 2/2 Ready
- Pod digest: `sha256:42f97957ab0b77e6b77b04e79baad87de315e6ff278a4773d2ea45f1ff9dc1f6`
- Rolled with `kubectl set image`; the `fbr-env-project` dev manifest was not touched.

## What was verified

A fixture GENERAL dataset was created through `POST /api/datasets` with schema:

| field | type |
|---|---|
| business_date | DATE |
| store | STRING |
| channel | STRING |
| impressions | NUMBER |
| actions | NUMBER |

Six records were inserted for Store A / Store B across 2026-11-16..2026-11-19. The Agent-Builder
created the report through `create_dataset_report` with:

- `time_field = data.business_date`
- `time_range = LAST_N_COMPLETE_DAYS 30`
- `bucket = DAY`, `timezone = Asia/Shanghai`
- `filters = [{field: "store", op: "IN", value: ["Store A"]}]`
- `measures = [{id: "imp", field: "data.impressions", op: "SUM"}]`
- `display = LINE`

Report data result:

- status `READY`, `matched_record_count = 4`
- columns `bucket`, `imp`
- rows: `2026-11-15T16:00:00Z = 150`, `2026-11-16T16:00:00Z = 120`, `2026-11-18T16:00:00Z = 200`
- `source_time = 2026-11-18T16:00:00Z`, `invalid_value_count = 0`

This confirms the DATE time axis, local-date range, typed `IN` filter, bucket conversion to the
report timezone, and query compilation against the typed dataset view.

## Cleanup

The report was deleted with `DELETE /api/reports/:id?expected_revision=1`; the fixture dataset was
deleted with `DELETE /api/datasets/:id` (204). The fixture rows and view were removed with the dataset.

## Not covered

- UAT/prod were not changed. UAT still runs 2.0.346; the `gbp_daily.date` field on UAT is still
  declared STRING and must be migrated to DATE before a native business-date report can be created.
- The homepage pin remains a human UI action; the agent correctly reports that a newly created
  report is not pinned automatically.
