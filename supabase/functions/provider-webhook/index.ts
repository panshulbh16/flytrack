// Flight-alert webhooks from the provider (AeroDataBox). Best-effort, so polling stays on as a safety net.
// Auth: secret token in the URL (?token=...) because the provider does not sign payloads.
import { handleWebhook } from "../_shared/engine.ts";
import { buildDeps } from "../_shared/deps.ts";
import { json, log, safeEqual } from "../_shared/http.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const secret = Deno.env.get("WEBHOOK_SECRET");
  const token = new URL(req.url).searchParams.get("token") ?? "";
  if (!secret || !safeEqual(token, secret)) return json({ error: "unauthorized" }, 401);
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad json" }, 400);
  }
  try {
    const res = await handleWebhook(buildDeps(), body);
    log("webhook", res);
    return json({ ok: true, ...res }); // always 2xx quickly so the provider doesn't retry-storm
  } catch (e) {
    log("webhook failed", { error: String(e) });
    return json({ ok: false }, 500);
  }
});
