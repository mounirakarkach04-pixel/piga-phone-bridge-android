const SECURITY_HEADERS = Object.freeze({
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "referrer-policy": "strict-origin-when-cross-origin",
  "permissions-policy": "camera=(), microphone=(), geolocation=()",
  "cross-origin-opener-policy": "same-origin",
  "cross-origin-resource-policy": "same-site",
  "strict-transport-security": "max-age=63072000; includeSubDomains; preload",
});

function governed(response, noStore = false) {
  const headers = new Headers(response.headers);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) headers.set(name, value);
  headers.set("x-piga-edge", "preview-governed");
  headers.set("x-piga-authority", "none");
  if (noStore) headers.set("cache-control", "no-store");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/healthz") {
      return governed(Response.json({
        status: "ok",
        service: "piga-pocket-interface-mesh-preview",
        environment: env.APP_ENV,
        sourceCommit: env.SOURCE_COMMIT,
        previewMode: "static-interface-mesh",
        authority: "none",
        engineCount: 5,
        engines: [
          "Product & Market Factory",
          "Sales & Outreach",
          "Deal Desk & Client Operations",
          "Backoffice & Institutional",
          "Control & Daily Close"
        ],
        a7semReverse: "continuous-meta-operator",
        a7semReverseIsEngine: false,
        apiConfigured: false,
        productionDomainChanged: false
      }), true);
    }

    if (url.pathname.startsWith("/api/")) {
      return governed(Response.json({
        error: "PREVIEW_HAS_NO_EXECUTION_AUTHORITY",
        authority: "none",
        gate: "closed"
      }, { status: 503 }), true);
    }

    return governed(await env.ASSETS.fetch(request));
  }
};
