// GET ?mode=ground&iata=DEL | ?mode=proximity&lat=28.6&lon=77.2   (Authorization: Bearer <user JWT>, Pro)
// docs/P4_CONTRACTS.md section 1. Live aircraft around an airport (on the ground, 5 km) or a point
// (75 km). Coordinates are rounded to 0.05° first; results are cached 10 s per (mode, cell) in this
// isolate; 60 requests per user per 10 minutes (radar_quota). The feed comes from RADAR_FEED and is only
// used with RADAR_FEED_LICENSED=true.
import { createClient } from "npm:@supabase/supabase-js@2";
import { cors, log } from "../_shared/http.ts";
import { feedFromEnv } from "../_shared/positions.ts";
import { publishableKey } from "../_shared/supabase-keys.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import { type FlightCandidate, handleRadar, RadarCache } from "./handler.ts";

const QUOTA_LIMIT = 60;
const QUOTA_WINDOW_SECONDS = 600;
const headers = { ...cors, "access-control-allow-methods": "GET, OPTIONS", "content-type": "application/json" };

// Module scope: shared by every request this isolate serves.
const cache = new RadarCache();
const feed = feedFromEnv((n) => Deno.env.get(n));

function isoDate(d: Date, deltaDays: number): string {
  return new Date(d.getTime() + deltaDays * 86_400_000).toISOString().slice(0, 10);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  const url = Deno.env.get("SUPABASE_URL");
  const key = publishableKey();
  if (!url || !key) return new Response(JSON.stringify({ error: "not_configured" }), { status: 503, headers });

  try {
    const db = serviceClient();
    const result = await handleRadar(
      {
        async authUser(authorization) {
          const jwt = authorization.replace(/^Bearer\s+/i, "");
          if (!jwt) return null;
          const client = createClient(url, key, { auth: { persistSession: false } });
          const { data: { user }, error } = await client.auth.getUser(jwt);
          return error || !user ? null : user.id;
        },
        async isPro(userId) {
          const { data, error } = await db.rpc("is_pro", { p_user: userId });
          if (error) throw new Error(`is_pro: ${error.message}`);
          return data === true;
        },
        async takeQuota(userId) {
          const { data, error } = await db.rpc("radar_take_quota", {
            p_user: userId,
            p_limit: QUOTA_LIMIT,
            p_window_seconds: QUOTA_WINDOW_SECONDS,
          });
          if (error) throw new Error(`radar_take_quota: ${error.message}`);
          return data === true;
        },
        async airport(iata) {
          const { data, error } = await db.from("airports").select("lat, lon").eq("iata", iata).maybeSingle();
          if (error) throw new Error(`airports: ${error.message}`);
          return (data as { lat: number; lon: number } | null) ?? null;
        },
        feed: () => feed,
        async flightCandidates(numbers, now) {
          const { data, error } = await db.from("flights")
            .select("carrier_iata, carrier_icao, flight_number, flight_date, status, sched_dep")
            .in("flight_number", numbers.slice(0, 300))
            .gte("flight_date", isoDate(now, -1))
            .lte("flight_date", isoDate(now, 1))
            .limit(2000);
          if (error) throw new Error(`flights: ${error.message}`);
          return (data ?? []) as FlightCandidate[];
        },
        cache,
        now: () => new Date(),
        log,
      },
      req.method,
      new URL(req.url),
      req.headers.get("authorization") ?? "",
    );
    return new Response(JSON.stringify(result.body), { status: result.status, headers });
  } catch (e) {
    log("radar failed", { error: e instanceof Error ? e.message : String(e) });
    return new Response(JSON.stringify({ error: "internal" }), { status: 500, headers });
  }
});
