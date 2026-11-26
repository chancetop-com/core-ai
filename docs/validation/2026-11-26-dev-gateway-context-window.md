# Dev gateway model context window editor E2E (2026-11-26)

## Release

- Version **2.0.391**, commit `be046fa64` (`frontend: restore the gateway model context window editor`).
- CI `Build Server` run **45772854710** succeeded; published digest
  `sha256:cfaaa2c93477e0f7de05de711000c77ef94edb70b527860dc7b3142861532a7d`.
- Dev (`FBRDevAKSCluster`, namespace `dev-ai`) rolled with `kubectl set image`; both pods report the
  same digest. The `fbr-env-project` dev manifest was not touched.

## Root cause

Commit `e3e13653e` (gateway model discovery import) removed the editable **Context Window** input
from the gateway model panel and replaced it with a read-only capability summary. The server has
always accepted `contextWindow` in `GatewayModelRequest`, and `GatewayModelConfig.contextWindow`
is published on `/api/gateway/v1/models` (`context_window`) and `/api/hub/models`
(`contextWindow`); discovery import only fills it when the provider reports one. A manually
registered model whose provider does not publish a context window — such as
`deepseek-flash-3p-promax` on UAT — therefore had no UI path to 1M and fell back to catalog
resolution.

## Fix

- `core-ai-frontend/src/pages/settings/GatewayProviders.tsx`: the model editor again offers a
  **Context Window** field. Saving sends `contextWindow`; emptying it sends an explicit `null`
  (partial updates already distinguish field presence from null).
- `core-ai-frontend/src/pages/settings/GatewayProviders.test.tsx`: covers saving `1000000` and
  clearing an existing `922000`.

## Verification

- `npx vitest run src/pages/settings/GatewayProviders.test.tsx`: **6/6 passed** (edit to
  `1000000`, clear to `null`).
- Full frontend suite: 319/321 passed; two unrelated timeouts
  (`App.sandbox-settings.test.tsx`, `SharedSession.test.tsx`) passed on rerun.
- `npx tsc -b` and `npx eslint` on the changed files: clean.
- Deployed chunk `web-assets/assets/GatewayProviders-BqhLjZpY.js` contains `Context Window`,
  the hint and the `e.g. 1000000` placeholder.
- Dev UI E2E on `https://core-ai-server.connexup-dev.net/gateway`: edited `gpt-6-luna`,
  changed Context Window from `922000` to `1000000`, clicked Save. `GET /api/gateway/models`
  returned `contextWindow=1000000`; `GET /api/gateway/v1/models` returned
  `context_window=1000000`. Restored to `922000` afterwards and the list shows `922000 ctx`.
- Screenshot: `docs/validation/2026-11-26-dev-gateway-context-window.png`.

## UAT release and model setting

- UAT is on the same AKS cluster (`FBRDevAKSCluster`, namespace `uat-ai`). The deployment was
  rolled with `kubectl set image` to 2.0.391; env manifest
  `azure/uat/kube/resource/app/ai/20-core-ai-server.yaml` was committed as
  `fbr-env-project` **`cf93b444`** (`uat: release core-ai-server 2.0.391`).
- UAT revision **308**, deployment **2/2 Ready**, both pods
  `sha256:cfaaa2c93477e0f7de05de711000c77ef94edb70b527860dc7b3142861532a7d`, 0 restarts;
  `/api/capabilities` returns 200. Startup logs show only the pre-existing MCP auth/404 warnings.
- `deepseek-flash-3p-promax` (`id=fb6748ff-17ba-46e1-8fbe-2351dd7ebd12`) had
  `contextWindow=null`. It was set to **1000000** through
  `PUT /api/gateway/models/{id} {"contextWindow":1000000}`; reads then returned 1,000,000 from
  both `GET /api/gateway/models` and `GET /api/hub/models` (`contextWindow`), and the published
  `/api/gateway/v1/models` reports `context_window=1000000`.
- UAT UI check: the Models row shows `vision, 1000000 ctx`; the editor's **Context Window**
  field reads `1000000`. Screenshot: `docs/validation/2026-11-26-uat-gateway-context-window.png`.
- Rollback: `kubectl --context FBRDevAKSCluster -n uat-ai rollout undo deploy/core-ai-server`
  returns to the 2.0.390 revision; the context-window value is data and can be cleared with an
  explicit JSON `null`.

## UAT follow-up

UAT now runs 2.0.391. The previous image lacked the field; the API workaround below remains
useful for any older environment:

```http
PUT /api/gateway/models/{id}
{"contextWindow": 1000000}
```

