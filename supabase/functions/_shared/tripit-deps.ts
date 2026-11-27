// Production wiring for the tripit-* Edge Functions (logic and tests live in ./tripit.ts).
import { createClient } from "npm:@supabase/supabase-js@2";
import { buildDeps } from "./deps.ts";
import { SearchError, searchFlight } from "./engine.ts";
import { log } from "./http.ts";
import { publishableKey } from "./supabase-keys.ts";
import { serviceClient } from "./supabase-repo.ts";
import { parseTokenKey, SEARCH_LIMIT, SupabaseTripItStore, tokenCipher, tripItApi, type TripItDeps } from "./tripit.ts";
import { withSearchQuota } from "../search-flight/view.ts";

let warnedPlain = false;

export async function buildTripItDeps(): Promise<TripItDeps> {
  const db = serviceClient();
  const key = Deno.env.get("TRIPIT_CONSUMER_KEY")?.trim();
  const secret = Deno.env.get("TRIPIT_CONSUMER_SECRET")?.trim();
  const cipher = await tokenCipher(parseTokenKey(Deno.env.get("TRIPIT_TOKEN_KEY")));
  if (!cipher.encrypted && !warnedPlain) {
    warnedPlain = true;
    log("tripit: TRIPIT_TOKEN_KEY not set (or not 32 bytes); tokens are stored unencrypted");
  }
  return {
    store: new SupabaseTripItStore(db),
    api: key && secret ? tripItApi({ consumerKey: key, consumerSecret: secret }) : null,
    cipher,
    async isPro(userId) {
      const { data, error } = await db.rpc("is_pro", { p_user: userId });
      if (error) throw new Error(`is_pro: ${error.message}`);
      return data === true;
    },
    async search(userId, flight, date) {
      const deps = buildDeps({ userId });
      deps.repo = withSearchQuota(deps.repo, async () => {
        const { data, error } = await db.rpc("reserve_search_quota", { p_user: userId, p_limit: SEARCH_LIMIT });
        if (error) throw new Error(`reserve_search_quota: ${error.message}`);
        return data === true;
      }, SEARCH_LIMIT);
      try {
        const rows = await searchFlight(deps, userId, { flight, date }, {
          perUserPerDay: SEARCH_LIMIT,
          freshForMinutes: 60,
        });
        return { ok: true, legs: rows.map((r) => ({ id: r.id, dep_iata: r.dep_iata, arr_iata: r.arr_iata })) };
      } catch (e) {
        if (e instanceof SearchError) return { ok: false, error: e.code };
        throw e;
      }
    },
    async track(userId, flightId) {
      const { data, error } = await db.rpc("tripit_track_flight", { p_user: userId, p_flight: flightId });
      if (error) throw new Error(`tripit_track_flight: ${error.message}`);
      return data as "added" | "exists" | "limit";
    },
    now: () => new Date(),
    log,
  };
}

/** The caller's user id from `Authorization: Bearer <JWT>`, or null. */
export async function userIdFromAuth(authorization: string | null): Promise<string | null> {
  const jwt = (authorization ?? "").replace(/^Bearer\s+/i, "").trim();
  const url = Deno.env.get("SUPABASE_URL");
  const key = publishableKey();
  if (!jwt || !url || !key) return null;
  const client = createClient(url, key, { auth: { persistSession: false } });
  const { data: { user }, error } = await client.auth.getUser(jwt);
  return error || !user ? null : user.id;
}

/** Where TripIt sends the browser back: TRIPIT_CALLBACK_URL, else this project's tripit-callback. */
export function tripItCallbackUrl(): string | null {
  const explicit = Deno.env.get("TRIPIT_CALLBACK_URL")?.trim();
  if (explicit) return explicit;
  const base = Deno.env.get("SUPABASE_URL")?.trim();
  return base ? `${base.replace(/\/+$/, "")}/functions/v1/tripit-callback` : null;
}
