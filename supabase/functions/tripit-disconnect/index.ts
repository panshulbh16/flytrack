// POST (Authorization: Bearer <user JWT>). Deletes the caller's TripIt connection (and the remembered
// segments). Flights already tracked stay. Allowed without Pro, so a lapsed user can always disconnect.
import { cors, json, log } from "../_shared/http.ts";
import { disconnectTripIt } from "../_shared/tripit.ts";
import { buildTripItDeps, userIdFromAuth } from "../_shared/tripit-deps.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const userId = await userIdFromAuth(req.headers.get("authorization"));
  if (!userId) return json({ error: "unauthorized" }, 401);
  try {
    const r = await disconnectTripIt(await buildTripItDeps(), userId);
    return json(r.body, r.status);
  } catch (e) {
    log("tripit-disconnect failed", { error: e instanceof Error ? e.message : String(e) });
    return json({ error: "internal" }, 500);
  }
});
