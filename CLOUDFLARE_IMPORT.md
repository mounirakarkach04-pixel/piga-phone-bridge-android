# Cloudflare Workers Builds import contract

Use these values when importing this repository into Cloudflare Workers Builds.

- Repository: `mounirakarkach04-pixel/piga-phone-bridge-android`
- Production branch: `cloudflare-live`
- Root directory: `/`
- Worker name: `piga-pocket-interface-mesh-preview`
- Build command: *(none required)*
- Deploy command: `npx wrangler deploy`
- Non-production branch deploy command: `npx wrangler versions upload`
- `workers.dev`: enabled by `wrangler.jsonc`

## Safety boundary

This is an authority-free visual preview. `/api/*` is intentionally fail-closed. No production-domain route, payment capability, account mutation, machine command, or device-side action is granted by this branch.

## Verification after deploy

1. Open the generated `*.workers.dev` URL.
2. Open `/healthz` and verify: `status=ok`, `engineCount=5`, `a7semReverseIsEngine=false`, `authority=none`.
3. Open any `/api/test` path and verify HTTP `503` with `PREVIEW_HAS_NO_EXECUTION_AUTHORITY`.
