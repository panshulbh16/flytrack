// GET from TripIt after the user approved (verify_jwt = false): ?oauth_token=<request token>[&oauth_verifier=..].
// The request token is the only key: it was issued to this user's tripit-connect call and is single use
// (cleared on exchange) and short lived (30 minutes). Exchanges it for an access token, starts a first
// sync in the background and redirects to heygate://tripit/connected (or heygate://tripit/failed?error=..).
import { log } from "../_shared/http.ts";
import { completeTripIt, syncTripIt } from "../_shared/tripit.ts";
import { buildTripItDeps } from "../_shared/tripit-deps.ts";

declare const EdgeRuntime: { waitUntil(p: Promise<unknown>): void } | undefined;

function redirect(to: string): Response {
  return new Response(null, { status: 302, headers: { location: to, "cache-control": "no-store" } });
}

Deno.serve(async (req) => {
  if (req.method !== "GET") return new Response("method", { status: 405 });
  try {
    const deps = await buildTripItDeps();
    const r = await completeTripIt(deps, new URL(req.url).searchParams);
    if (r.userId) {
      const first = syncTripIt(deps, r.userId).catch((e) =>
        log("tripit-callback: first sync failed", { error: e instanceof Error ? e.message : String(e) })
      );
      if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(first);
    }
    return redirect(r.redirect);
  } catch (e) {
    log("tripit-callback failed", { error: e instanceof Error ? e.message : String(e) });
    return redirect("heygate://tripit/failed?error=internal");
  }
});
