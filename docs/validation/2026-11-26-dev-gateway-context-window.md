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

## UAT follow-up

UAT still runs the previous image, so the field is missing there until 2.0.391 (or later) is
deployed with explicit authorization. Immediate workaround for the UAT model:

```http
PUT /api/gateway/models/{id}
{"contextWindow": 1000000}
```
