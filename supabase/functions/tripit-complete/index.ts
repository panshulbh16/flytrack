// POST {code} (Authorization: Bearer <user JWT>). docs/P4_CONTRACTS.md section 2.
// Redeems the one-time code tripit-callback put in heygate://tripit/callback?code=...: the code is single use and
// destroys the pending request token whoever sends it; only the user who started tripit-connect gets the token
// exchange. Then a first sync starts in the background.
// 200 {connected: true}. Errors: unauthorized 401, bad_request 400, expired 410 (unknown, used or expired code),
// not_your_link 403 (the link was started from another account), denied 409 (TripIt refused the exchange),
// tripit_error 502, not_configured 503.
import { cors, json, log } from "../_shared/http.ts";
import { completeTripIt, syncTripIt } from "../_shared/tripit.ts";
import { buildTripItDeps, userIdFromAuth } from "../_shared/tripit-deps.ts";

declare const EdgeRuntime: { waitUntil(p: Promise<unknown>): void } | undefined;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const userId = await userIdFromAuth(req.headers.get("authorization"));
  if (!userId) return json({ error: "unauthorized" }, 401);
  let code: unknown;
  try {
    code = ((await req.json()) as { code?: unknown } | null)?.code;
  } catch {
    return json({ error: "bad_request" }, 400);
  }
  try {
    const deps = await buildTripItDeps();
    const r = await completeTripIt(deps, userId, code);
    if (r.status === 200) {
      const first = syncTripIt(deps, userId).catch((e) =>
        log("tripit-complete: first sync failed", { error: e instanceof Error ? e.message : String(e) })
      );
      if (typeof EdgeRuntime !== "undefined") EdgeRuntime.waitUntil(first);
    }
    return json(r.body, r.status);
  } catch (e) {
    log("tripit-complete failed", { error: e instanceof Error ? e.message : String(e) });
    return json({ error: "internal" }, 500);
  }
});
