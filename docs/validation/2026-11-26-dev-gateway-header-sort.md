# Dev gateway model header sorting E2E (2026-11-26)

## Release

- Version **2.0.393**, commit `1d30fbdc0` (`frontend: sort gateway models from the table headers`).
- CI `Build Server` run **45780000658** succeeded; published digest
  `sha256:ef7f459ba64c7a614660c68deedc08a0a03247384ff883d8fe69b51ecd9f5c98`.
- Dev (`FBRDevAKSCluster`, namespace `dev-ai`) rolled with `kubectl set image`; both pods report the
  same digest, 0 restarts. The `fbr-env-project` dev manifest was not touched. The image also carries
  2.0.392 (server workspace async-hold) and 2.0.391 (Context Window editor).

## Change

- `core-ai-frontend/src/pages/settings/GatewayProviders.tsx`: the top-of-page `Sort models`
  dropdown is gone. **Alias**, **Provider** and **Official Model** headers are now sort buttons:
  one click sorts ascending, the next descending, the next restores the server order; the selection
  persists in `gateway.modelSort` and `aria-sort` mirrors it for accessibility.
- `GatewayProviders.test.tsx`: rewritten around header clicks; covers alias/provider/official in
  both directions, the three-state cycle and persistence across remount.

## Verification

- `npx vitest run src/pages/settings/GatewayProviders.test.tsx`: **8/8 passed**.
- `npx tsc -b` and `npx eslint` on the changed files: clean.
- Deployed chunk `web-assets/assets/GatewayProviders-BSHhWClH.js` contains `aria-sort` and
  `Sort by`; the old `Sort: Default` option is absent.
- Dev UI E2E on `https://core-ai-server.connexup-dev.net/gateway` (Models tab):
  - no `select[aria-label="Sort models"]` in the toolbar;
  - all three headers report `aria-sort=none` in server order;
  - clicking Provider → `ascending` and the Azure rows (`gpt-5-mini`, `gpt-5.6-luna`,
    `gpt-6-luna`, `gpt-6.1-sol`) first;
  - clicking again → `descending`; a third click → `none` and the original server order.
- Screenshot: `docs/validation/2026-11-26-dev-gateway-header-sort.png`.

## UAT follow-up

UAT still runs 2.0.391, so it keeps the old dropdown until 2.0.393 is deployed there with
explicit authorization.
