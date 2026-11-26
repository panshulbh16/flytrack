// POST { "dep": "DEL", "arr"?: "BOM", "date": "2026-11-28", "airline"?: "6E" }  (Authorization: Bearer <user JWT>)
// docs/P3_CONTRACTS.md B5: that day's departures from `dep`, filtered by arrival airport and airline.
// Response { flights: [<search-flight row shape>] }, so the apps reuse the add-flight result list.
// The airport-day is cached for 6 h in route_search_cache; a provider fetch costs 2 units of the same daily
// search quota as search-flight (reserve_search_units).
import { createClient } from "npm:@supabase/supabase-js@2";
import { SearchError } from "../_shared/engine.ts";
import { buildDeps } from "../_shared/deps.ts";
import { cors, json, log } from "../_shared/http.ts";
import { type RouteCacheEntry, type RouteSearchStore, searchRoute } from "../_shared/route-search.ts";
import { publishableKey } from "../_shared/supabase-keys.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import type { FlightSnapshot } from "../_shared/types.ts";
import { searchResponse } from "../search-flight/respond.ts";

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

  let body: { dep?: string; arr?: string | null; date?: string; airline?: string | null };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }

  try {
    const db = serviceClient();
    const limit = user.is_anonymous ? 10 : 30; // same daily quota as search-flight
    const store: RouteSearchStore = {
      async getCache(dep, date): Promise<RouteCacheEntry | null> {
        const { data, error: e } = await db.from("route_search_cache").select("payload, fetched_at")
          .eq("dep", dep).eq("date", date).maybeSingle();
        if (e) throw new Error(`route_search_cache read: ${e.message}`);
        if (!data) return null;
        const r = data as { payload: FlightSnapshot[]; fetched_at: string };
        return { payload: Array.isArray(r.payload) ? r.payload : [], fetchedAt: r.fetched_at };
      },
      async putCache(dep, date, payload, fetchedAt) {
        const { error: e } = await db.from("route_search_cache")
          .upsert({ dep, date, payload, fetched_at: fetchedAt }, { onConflict: "dep,date" });
        if (e) throw new Error(`route_search_cache write: ${e.message}`);
        // rows for past dates are dead weight; trim now and then (cheap, indexed)
        if (Math.random() < 0.05) await db.rpc("trim_route_search_cache");
      },
      async reserveUnits(cost) {
        const { data, error: e } = await db.rpc("reserve_search_units", {
          p_user: user.id,
          p_limit: limit,
          p_cost: cost,
        });
        if (e) throw new Error(`reserve_search_units: ${e.message}`);
        return data === true;
      },
    };

    const rows = await searchRoute(buildDeps({ userId: user.id }), store, {
      dep: body.dep ?? "",
      arr: body.arr ?? null,
      date: body.date ?? "",
      airline: body.airline ?? null,
    });
    return json(await searchResponse(db, user.id, rows));
  } catch (e) {
    if (e instanceof SearchError) return json({ error: e.code, message: e.message }, STATUS[e.code]);
    log("route search failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
});
