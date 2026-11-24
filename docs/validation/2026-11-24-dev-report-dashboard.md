# Dev E2E — Parameterized multi-component dataset report (2026-11-24)

## Deployment

- Image: `chancetop/core-ai-server:2.0.360`
- CI: `Build Server` run `45363987868`, commit `076ad103a`
- Namespace: `dev-ai`; deployment 2/2 Ready
- Rolled with `kubectl set image`; the `fbr-env-project` dev manifest was not touched.

2.0.360 contains the report parameters/multi-component work (2.0.355) plus the SpotBugs gate fix,
on top of the typed business-time/filter work (2.0.352).

## Fixture

GENERAL dataset with `business_date DATE`, `store STRING`, `impressions NUMBER`, `actions NUMBER`.
Six records: Store A on 2026-11-16 (100/50 impressions), 2026-11-17 (120), 2026-11-19 (200);
Store B on 2026-11-17 (80) and 2026-11-18 (60).

The dashboard spec used:

- parameters: `stores` (STRING, multiple, default `["Store A"]`), `range` (DATE_RANGE, default
  `LAST_N_COMPLETE_DAYS 30`)
- components:
  - `kpi`: METRIC, time_field `data.business_date`, `time_range.param=range`, filter
    `store IN param stores`, measure `SUM(data.impressions)`
  - `trend`: LINE, same parameters, bucket DAY
  - `actions`: BAR, category `store`, measure `SUM(data.actions)`

## Result

`GET /api/reports/:id/data` for the defaults:

- components: `kpi` (METRIC), `trend` (LINE), `actions` (BAR)
- KPI = 470, trend = 150 / 120 / 200, actions = 47

`GET /api/reports/:id/data?parameters_json={"stores":["Store B"]}`:

- KPI = 140, trend = 80 / 60

This confirms parameter defaults, execution-time bindings, shared parameters across components, and the
component bundle response.

## Cleanup

The report was deleted with `DELETE /api/reports/:id?expected_revision=1`; the fixture dataset was
deleted with `DELETE /api/datasets/:id` (204).

## Not covered

- Home pin: unchanged and still a human action on the report page; the dashboard is stored as one report,
  so the existing pin works without a new tool or schema change.
- UAT/prod were not changed. UAT still runs 2.0.346 and requires the 2.0.360 upgrade plus the
  `gbp_daily.date` STRING→DATE migration before the original GBP dashboard can be recreated.
