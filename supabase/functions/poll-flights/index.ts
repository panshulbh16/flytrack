// Called by pg_cron every 30s (see migrations/*_cron.sql). NOT callable by app users.
// Claims due flights (deduped: one row per unique flight, shared by all users), polls the provider,
// diffs, stores history, and sends FCM pushes immediately.
import { runPollCycle } from "../_shared/engine.ts";
import { buildDeps } from "../_shared/deps.ts";
import { json, log, safeEqual } from "../_shared/http.ts";

Deno.serve(async (req) => {
  const secret = Deno.env.get("CRON_SECRET");
  if (!secret || !safeEqual(req.headers.get("x-cron-secret") ?? "", secret)) {
    return json({ error: "unauthorized" }, 401);
  }
  const started = Date.now();
  try {
    const stats = await runPollCycle(buildDeps(), 25_000); // < 30s cron interval
    log("poll cycle", { ...stats, ms: Date.now() - started });
    return json({ ok: true, ...stats, ms: Date.now() - started });
  } catch (e) {
    log("poll cycle failed", { error: String(e) });
    return json({ ok: false, error: String(e) }, 500);
  }
});
