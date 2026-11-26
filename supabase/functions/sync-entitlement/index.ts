// POST (Authorization: Bearer <user JWT>). The apps call it after a purchase or restore.
// Re-fetches RevenueCat for the caller, updates their profile and returns
// { plan: "pro"|"free", pro_until: iso|null, trial_flight_id: uuid|null }.
import { createClient } from "npm:@supabase/supabase-js@2";
import { revenueCatApi, SupabaseBillingStore, syncEntitlement } from "../_shared/billing.ts";
import { cors, json, log } from "../_shared/http.ts";
import { publishableKey } from "../_shared/supabase-keys.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);

  const auth = req.headers.get("authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL");
  const key = publishableKey();
  if (!url || !key) return json({ error: "not_configured" }, 503);
  const userClient = createClient(url, key, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
  const { data: { user }, error } = await userClient.auth.getUser(auth.replace(/^Bearer\s+/i, ""));
  if (error || !user) return json({ error: "unauthorized" }, 401);

  const rcKey = Deno.env.get("REVENUECAT_SECRET_API_KEY")?.trim();
  try {
    const result = await syncEntitlement({
      store: new SupabaseBillingStore(serviceClient()),
      rc: rcKey ? revenueCatApi(rcKey) : null,
      now: () => new Date(),
      log,
    }, user.id);
    if (!result) return json({ error: "no_profile" }, 404);
    return json(result);
  } catch (e) {
    log("sync-entitlement failed", { error: String(e) });
    return json({ error: "upstream" }, 502);
  }
});
