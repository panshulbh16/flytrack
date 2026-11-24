// POST { "token": "<this phone's FCM token>" }  (Authorization: Bearer <user JWT>, anonymous OK)
// Sends a fixed test alert to that one device, through the same FCM sender and message settings as real
// alerts, and logs it in push_log (kind 'test'). The device must belong to the caller, so the test answers
// "do alerts reach THIS phone?" and nobody can push to a device that is not theirs.
import { createClient } from "npm:@supabase/supabase-js@2";
import { pusherFromEnv } from "../_shared/fcm.ts";
import { cors, json, log } from "../_shared/http.ts";
import { buildTestMessage } from "../_shared/notify.ts";
import { serviceClient, SupabaseRepo } from "../_shared/supabase-repo.ts";

// ponytail: count-then-send is not atomic, so parallel calls can slip a few extra sends past the limit.
// Harmless while a caller can only reach their own device.
const MAX_SENDS_PER_10_MIN = 6;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);

  const auth = req.headers.get("authorization") ?? "";
  const userClient = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
  const { data: { user }, error } = await userClient.auth.getUser(auth.replace(/^Bearer\s+/i, ""));
  if (error || !user) return json({ error: "unauthorized" }, 401);

  let body: { token?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }
  const token = typeof body.token === "string" ? body.token : "";
  if (token.length < 20 || token.length > 4096) return json({ error: "bad_input", message: "token" }, 400);

  try {
    const db = serviceClient();
    const since = new Date(Date.now() - 10 * 60_000).toISOString();
    const recent = await db.from("push_log").select("id", { count: "exact", head: true })
      .eq("user_id", user.id).eq("kind", "test").gte("created_at", since);
    if (recent.error) throw new Error(`count: ${recent.error.message}`);
    if ((recent.count ?? 0) >= MAX_SENDS_PER_10_MIN) return json({ error: "rate_limited" }, 429);

    const found = await db.from("devices").select("id").eq("user_id", user.id).eq("fcm_token", token).maybeSingle();
    if (found.error) throw new Error(`device: ${found.error.message}`);
    if (!found.data) return json({ error: "not_registered" }, 404);

    const result = await pusherFromEnv().send(buildTestMessage(token));
    const repo = new SupabaseRepo(db);
    await repo.logPushes([{ eventId: null, deviceId: found.data.id, userId: user.id, kind: "test", result }]);
    if (result.invalidToken) await repo.deleteDeviceByToken(token); // same cleanup as real alerts

    // A dry run (PUSH_DRY_RUN=true) delivers nothing, so it is not "sent".
    return json({ sent: result.ok && !result.dryRun, error: result.error ?? null });
  } catch (e) {
    log("send-test-push failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
});
