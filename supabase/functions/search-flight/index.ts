// POST { "flight": "6E 2341", "date": "2026-11-25" }  (Authorization: Bearer <user JWT>, anonymous OK)
// Returns matching legs from our DB cache, calling the provider only when the cache is stale and the
// caller still has search quota (reserve_search_quota). The app then tracks a leg by inserting into
// user_flights (RLS) — no flight API call from the app.
// The response carries everything Add-flight needs (flight_view's columns, whitelisted, read with the
// service role): flight_view itself only shows flights the caller already tracks.
import { createClient } from "npm:@supabase/supabase-js@2";
import { isActive, SupabaseBillingStore } from "../_shared/billing.ts";
import { SearchError, searchFlight } from "../_shared/engine.ts";
import { buildDeps } from "../_shared/deps.ts";
import { cors, json, log } from "../_shared/http.ts";
import { publishableKey } from "../_shared/supabase-keys.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import { AIRPORT_COLUMNS, type AirportInfo, toSearchFlight, withSearchQuota } from "./view.ts";

const STATUS: Record<SearchError["code"], number> = {
  bad_input: 400,
  rate_limited: 429,
  not_found: 404,
  provider_error: 502,
};

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

  let body: { flight?: string; date?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }

  try {
    const db = serviceClient();
    const limit = user.is_anonymous ? 10 : 30; // anonymous accounts are cheap to create → lower limit
    const deps = buildDeps({ userId: user.id });
    deps.repo = withSearchQuota(deps.repo, async () => {
      const { data, error: qErr } = await db.rpc("reserve_search_quota", { p_user: user.id, p_limit: limit });
      if (qErr) throw new Error(`reserve_search_quota: ${qErr.message}`);
      return data === true;
    }, limit);

    const rows = await searchFlight(deps, user.id, { flight: body.flight ?? "", date: body.date ?? "" }, {
      perUserPerDay: limit,
      freshForMinutes: 10,
    });

    const iatas = [...new Set(rows.flatMap((r) => [r.dep_iata, r.arr_iata]))];
    const [airportsRes, profile] = await Promise.all([
      iatas.length
        ? db.from("airports").select(AIRPORT_COLUMNS).in("iata", iatas)
        : Promise.resolve({ data: [] as AirportInfo[], error: null }),
      new SupabaseBillingStore(db).getProfile(user.id),
    ]);
    if (airportsRes.error) throw new Error(airportsRes.error.message);
    const airports = new Map((airportsRes.data as AirportInfo[]).map((a) => [a.iata, a]));
    const pro = profile !== null && isActive(profile, new Date());
    const trial = profile?.trialFlightId ?? null;
    return json({ flights: rows.map((r) => toSearchFlight(r, airports, (id) => pro || id === trial)) });
  } catch (e) {
    if (e instanceof SearchError) return json({ error: e.code, message: e.message }, STATUS[e.code]);
    log("search failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
});
