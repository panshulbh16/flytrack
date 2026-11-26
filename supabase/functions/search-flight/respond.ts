// The { flights: [...] } response of search-flight and search-route (docs/P3_CONTRACTS.md B5: the apps reuse
// one result list). Airports are joined in; Pro-only fields follow is_pro (family Pro included) or the
// caller's first-flight trial, like flight_view.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { SupabaseBillingStore } from "../_shared/billing.ts";
import type { FlightRow } from "../_shared/types.ts";
import { AIRPORT_COLUMNS, type AirportInfo, toSearchFlight } from "./view.ts";

export async function searchResponse(
  db: SupabaseClient,
  userId: string,
  rows: FlightRow[],
): Promise<{ flights: Record<string, unknown>[] }> {
  const iatas = [...new Set(rows.flatMap((r) => [r.dep_iata, r.arr_iata]))];
  const [airportsRes, profile, proRes] = await Promise.all([
    iatas.length
      ? db.from("airports").select(AIRPORT_COLUMNS).in("iata", iatas)
      : Promise.resolve({ data: [] as AirportInfo[], error: null }),
    new SupabaseBillingStore(db).getProfile(userId),
    // is_pro, not the profile's plan: Pro through a family (P3 B13) counts too
    db.rpc("is_pro", { p_user: userId }),
  ]);
  if (airportsRes.error) throw new Error(airportsRes.error.message);
  if (proRes.error) throw new Error(`is_pro: ${proRes.error.message}`);
  const airports = new Map((airportsRes.data as AirportInfo[]).map((a) => [a.iata, a]));
  const pro = proRes.data === true;
  const trial = profile?.trialFlightId ?? null;
  return { flights: rows.map((r) => toSearchFlight(r, airports, (id) => pro || id === trial)) };
}
