// POST (Authorization: Bearer <user JWT>, Pro). docs/P4_CONTRACTS.md section 2.
// Gets a TripIt request token and returns { authorize_url } for the app to open in a browser / Custom Tab.
// Errors: unauthorized 401, pro_required 402, not_configured 503 (no TRIPIT_CONSUMER_KEY/SECRET),
// tripit_error 502.
import { cors, json, log } from "../_shared/http.ts";
import { connectTripIt } from "../_shared/tripit.ts";
import { buildTripItDeps, tripItCallbackUrl, userIdFromAuth } from "../_shared/tripit-deps.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const userId = await userIdFromAuth(req.headers.get("authorization"));
  if (!userId) return json({ error: "unauthorized" }, 401);
  const callback = tripItCallbackUrl();
  if (!callback) return json({ error: "not_configured" }, 503);
  try {
    const r = await connectTripIt(await buildTripItDeps(), userId, callback);
    return json(r.body, r.status);
  } catch (e) {
    log("tripit-connect failed", { error: e instanceof Error ? e.message : String(e) });
    return json({ error: "internal" }, 500);
  }
});
