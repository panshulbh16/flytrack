// POST { "flight": "6E 2341", "date": "2026-11-25", "mode"?: "past" }  (Authorization: Bearer <user JWT>, anonymous OK)
// mode "past" (docs/P3_CONTRACTS.md B6) accepts dates up to 365 days back; those legs are never polled.
// Returns matching legs from our DB cache, calling the provider only when the cache is stale and the
// caller still has search quota (reserve_search_quota). The app then tracks a leg by inserting into
// user_flights (RLS) — no flight API call from the app.
// The response carries everything Add-flight needs (flight_view's columns, whitelisted, read with the
// service role): flight_view itself only shows flights the caller already tracks.
import { createClient } from "npm:@supabase/supabase-js@2";
import { SearchError, searchFlight } from "../_shared/engine.ts";
import { buildDeps } from "../_shared/deps.ts";
import { searchPastFlight } from "../_shared/past-search.ts";
import { cors, json, log } from "../_shared/http.ts";
import { publishableKey } from "../_shared/supabase-keys.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import { searchResponse } from "./respond.ts";
import { withSearchQuota } from "./view.ts";

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

  let body: { flight?: string; date?: string; mode?: string };
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

    if (body.mode != null && body.mode !== "past" && body.mode !== "upcoming") {
      return json({ error: "bad_input", message: 'mode must be "past" or "upcoming"' }, 400);
    }
    const query = { flight: body.flight ?? "", date: body.date ?? "" };
    const searchLimits = { perUserPerDay: limit, freshForMinutes: 10 };
    const rows = body.mode === "past"
      ? await searchPastFlight(deps, user.id, query, searchLimits)
      : await searchFlight(deps, user.id, query, searchLimits);

    return json(await searchResponse(db, user.id, rows));
  } catch (e) {
    if (e instanceof SearchError) return json({ error: e.code, message: e.message }, STATUS[e.code]);
    log("search failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
});
