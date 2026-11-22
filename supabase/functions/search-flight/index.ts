// POST { "flight": "6E 2341", "date": "2026-11-25" }  (Authorization: Bearer <user JWT>, anonymous OK)
// Returns matching legs from our DB cache, calling the provider only when the cache is stale.
// The app then tracks a leg by inserting into user_flights (RLS) — no flight API call from the app.
import { createClient } from "npm:@supabase/supabase-js@2";
import { SearchError, searchFlight } from "../_shared/engine.ts";
import { buildDeps } from "../_shared/deps.ts";
import { cors, json, log } from "../_shared/http.ts";

const STATUS: Record<SearchError["code"], number> = { bad_input: 400, rate_limited: 429, not_found: 404, provider_error: 502 };

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

  let body: { flight?: string; date?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }

  try {
    const deps = buildDeps({ userId: user.id });
    const limit = user.is_anonymous ? 10 : 30; // anonymous accounts are cheap to create → lower limit
    const rows = await searchFlight(deps, user.id, { flight: body.flight ?? "", date: body.date ?? "" }, {
      perUserPerDay: limit,
      freshForMinutes: 10,
    });
    // Return the public view (no internal columns like alert_state / leases / raw).
    const { data, error: vErr } = await userClient.from("flight_view").select("*").in("id", rows.map((r) => r.id));
    if (vErr) throw new Error(vErr.message);
    return json({ flights: data });
  } catch (e) {
    if (e instanceof SearchError) return json({ error: e.code, message: e.message }, STATUS[e.code]);
    log("search failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
});
