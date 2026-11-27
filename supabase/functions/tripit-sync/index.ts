// POST. Two callers (verify_jwt = false, both checked here):
//   * the app ("Sync now"): Authorization: Bearer <user JWT>; Pro only → SyncStats
//     { segments, added, existing, not_found, skipped, errors }
//   * pg_cron hourly (migration 32): x-cron-secret = CRON_SECRET → every due connected Pro user
// Upcoming TripIt air segments are searched and tracked with source 'tripit'; handled segments are
// remembered in tripit_segments, so repeated syncs are idempotent and cheap.
// Manual calls are limited to one per 60 s per user: 429 {error: "cooldown", retry_after_s} (migration 35).
// Errors: unauthorized 401, pro_required 402, not_connected / tripit_revoked 409, tripit_error 502,
// not_configured 503.
import { cors, json, log, safeEqual } from "../_shared/http.ts";
import { cronSyncTripIt, syncTripIt } from "../_shared/tripit.ts";
import { buildTripItDeps, userIdFromAuth } from "../_shared/tripit-deps.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const cronHeader = req.headers.get("x-cron-secret");
    if (cronHeader !== null) {
      const secret = Deno.env.get("CRON_SECRET");
      if (!secret || !safeEqual(cronHeader, secret)) return json({ error: "unauthorized" }, 401);
      const stats = await cronSyncTripIt(await buildTripItDeps(), 50_000);
      log("tripit cron sync", stats);
      return json({ ok: true, ...stats });
    }
    const userId = await userIdFromAuth(req.headers.get("authorization"));
    if (!userId) return json({ error: "unauthorized" }, 401);
    const r = await syncTripIt(await buildTripItDeps(), userId, { manual: true });
    return json(r.body, r.status);
  } catch (e) {
    log("tripit-sync failed", { error: e instanceof Error ? e.message : String(e) });
    return json({ error: "internal" }, 500);
  }
});
