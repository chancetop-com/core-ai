# UAT E2E — GBP dashboard on typed business time (2026-11-24)

## Deployment

- Image: `chancetop/core-ai-server:2.0.362`
- CI: `Build Server` run `45369641474`, commit `7e06c3881`
- Namespace: `uat-ai`; deployment 2/2 Ready
- Pod/image index digest: `sha256:629bd4421362453247543fbe913446bca0d5cb9ddfc31b773650e4a9f48caebb`
  (matches `docker buildx imagetools inspect chancetop/core-ai-server:2.0.362`)
- Env repo `fbr-env-project` main: `5157ace7` (`uat: release core-ai-server 2.0.362`)

## Dataset change

`gbp_daily` (`753e355e-1ab6-4bfa-ba96-4c8356091042`) was updated through
`PUT /api/datasets/:id`: the `date` field changed from STRING to DATE, all other fields unchanged.
The generated view `dataset.ds_753e355e1ab64bfaba964c8356091042` rebuilt with
`information_schema.columns.data_type = date`; the 7,253 ISO-date rows parse via `dataset_to_date`.

## Report E2E

Agent-Builder created one 3-component dashboard on `gbp_daily`:

- parameters: `stores` (STRING, multiple, default `[]` = all stores), `range` (DATE_RANGE,
  default `LAST_N_COMPLETE_DAYS 30`)
- components: KPI impressions (METRIC), daily impressions (LINE, bucket DAY), actions by store
  (BAR, category `location_name`)

Results (`LAST_N_COMPLETE_DAYS 30`, Asia/Shanghai, 2026-10-25..2026-11-20, 486 records):

- KPI: **193,736** impressions
- trend: 27 daily buckets summing to 193,736
- actions: 18 store rows summing to 43,155
- store filter `Choice Brooklyn - Upper West Side`: KPI **56,118**, 27 buckets

The report was deleted afterwards. The dataset and its DATE schema remain.

## Notes

- The two reports created on 2026-11-23 (`23283c40…`, `78c13a96…`) still use `meta.created_at` and
  describe the full 549-day history as "last 30 days"; they need to be updated or deleted.
- Pin remains a human action in `/datasets/reports/:id`; the dashboard is one report, so the existing
  pin endpoint works without a new tool.
- Rollback: `kubectl --context FBRDevAKSCluster -n uat-ai rollout undo deploy/core-ai-server` returns to
  the 2.0.346 revision. Reverting the DATE schema is a separate data change and does not affect the old
  `meta.*` reports.
