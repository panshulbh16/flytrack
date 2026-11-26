// search-flight's own response shape. It mirrors flight_view's columns (the apps decode both with one
// DTO), but is built from service-role rows, because flight_view only shows flights the caller
// already tracks (can_read_flight) and search results are, by definition, not tracked yet.
import type { Repo } from "../_shared/repo.ts";
import type { FlightRow } from "../_shared/types.ts";

export interface AirportInfo {
  iata: string;
  name: string | null;
  city: string | null;
  lat: number | null;
  lon: number | null;
  tz: string | null;
}

/** Columns of flights that search results may expose. Internal scheduler/alert/provider columns never leave. */
export const PUBLIC_FLIGHT_COLUMNS = [
  "id",
  "carrier_iata",
  "flight_number",
  "flight_date",
  "dep_iata",
  "arr_iata",
  "airline_name",
  "status",
  "sched_dep",
  "est_dep",
  "act_dep",
  "dep_terminal",
  "dep_gate",
  "dep_checkin",
  "sched_arr",
  "est_arr",
  "act_arr",
  "arr_terminal",
  "arr_gate",
  "baggage_belt",
  "aircraft_model",
  "aircraft_reg",
  "delay_reason",
  "live_lat",
  "live_lon",
  "live_alt_ft",
  "live_heading",
  "live_speed_kts",
  "live_updated_at",
  "last_polled_at",
  "updated_at",
] as const satisfies readonly (keyof FlightRow)[];

export const AIRPORT_COLUMNS = "iata, name, city, lat, lon, tz";

/** Same rule as flight_view.has_pro_insight: null when the prediction has no lateMin. */
function hasProInsight(prediction: Record<string, unknown> | null): boolean | null {
  if (!prediction) return false;
  const late = prediction.lateMin;
  if (late === null || late === undefined) return null;
  const n = Number(late);
  return Number.isFinite(n) ? Math.trunc(n) >= 10 : null;
}

/**
 * One search result. `proForFlight` decides the Pro-only fields (inbound plane, prediction), like
 * flight_view's masking with is_pro_for_flight.
 */
export function toSearchFlight(
  row: FlightRow,
  airports: ReadonlyMap<string, AirportInfo>,
  proForFlight: (flightId: string) => boolean,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const c of PUBLIC_FLIGHT_COLUMNS) out[c] = row[c] ?? null;
  const dep = airports.get(row.dep_iata);
  const arr = airports.get(row.arr_iata);
  out.dep_name = dep?.name ?? null;
  out.dep_city = dep?.city ?? null;
  out.dep_lat = dep?.lat ?? null;
  out.dep_lon = dep?.lon ?? null;
  out.dep_tz = dep?.tz ?? null;
  out.arr_name = arr?.name ?? null;
  out.arr_city = arr?.city ?? null;
  out.arr_lat = arr?.lat ?? null;
  out.arr_lon = arr?.lon ?? null;
  out.arr_tz = arr?.tz ?? null;
  const pro = proForFlight(row.id);
  out.inbound_flight_id = pro ? row.inbound_flight_id ?? null : null;
  if (pro && row.prediction) {
    const { alertedBucket: _b, inboundReg: _r, ...visible } = row.prediction;
    out.prediction = visible;
  } else {
    out.prediction = null;
  }
  out.has_pro_insight = hasProInsight(row.prediction);
  return out;
}

/**
 * The engine checks the per-user limit (countUserProviderCalls) exactly once per search, right before
 * it calls the provider, and only on a cache miss. This adapter turns that check into an atomic
 * reservation (reserve_search_quota), so parallel searches cannot all pass a "29 of 30" count.
 */
export function withSearchQuota(repo: Repo, reserve: () => Promise<boolean>, limit: number): Repo {
  const wrapped = Object.create(repo) as Repo;
  wrapped.countUserProviderCalls = async () => (await reserve()) ? 0 : limit;
  return wrapped;
}
