// POST from RevenueCat (verify_jwt = false). Authorization must equal REVENUECAT_WEBHOOK_AUTH.
// Idempotent by event.id (billing_events). With REVENUECAT_SECRET_API_KEY set, every event re-fetches
// the subscriber and applies the current state; otherwise the state is derived from the event.
// Gift products (docs/P4_CONTRACTS.md section 3) create one gift code per store transaction, or void it on a
// refund, and never change the purchaser's plan; redeemed gift time is kept by both paths.
import { handleRevenueCatWebhook, revenueCatApi, SupabaseBillingStore } from "../_shared/billing.ts";
import { json, log } from "../_shared/http.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const rcKey = Deno.env.get("REVENUECAT_SECRET_API_KEY")?.trim();
  try {
    const result = await handleRevenueCatWebhook(
      {
        store: new SupabaseBillingStore(serviceClient()),
        rc: rcKey ? revenueCatApi(rcKey) : null,
        now: () => new Date(),
        log,
      },
      Deno.env.get("REVENUECAT_WEBHOOK_AUTH")?.trim() || null,
      req.headers.get("authorization"),
      await req.text(),
    );
    return json(result.body, result.status);
  } catch (e) {
    log("revenuecat-webhook failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
});
