// Airport fog alerts (docs/P2_CONTRACTS.md section 1, event FOG_ALERT). Pure logic, no I/O.
// Production candidates come from public.fog_alert_candidates() (migration 13); MemoryRepo uses
// fogCandidates() below, which mirrors that SQL so tests and the simulator behave like the database.
import type { FlightEvent, FlightRow } from "./types.ts";

const MIN = 60_000;
/** A flight is in scope this long before its departure (dep side) or arrival (arr side). */
export const FOG_WINDOW_HOURS = 4;
/** Still in scope this long after the planned time while it has not departed / arrived (fog delays it). */
export const FOG_LOOKBACK_HOURS = 3;
/** Older observations are not used for alerts. */
export const FOG_WEATHER_MAX_AGE_MIN = 120;
/** Visibility below this is an alert even without FG in the report (e.g. thick haze or smoke). */
export const FOG_VIS_M = 1000;

/** The airport_weather fields the fog step needs. */
export interface FogWeather {
  visibility_m: number | null;
  wx: string | null;
  fog: boolean;
  observed_at: string;
}

/** One row of public.fog_alert_candidates(). */
export interface FogCandidate {
  flight_id: string;
  side: "dep" | "arr";
  airport: string;
  visibility_m: number | null;
  wx: string | null;
  dedupe_key: string;
}

const NOT_DEPARTED_GONE = new Set(["departed", "en_route", "approaching", "landed", "arrived", "cancelled", "diverted"]);
const NOT_ARRIVED_GONE = new Set(["landed", "arrived", "cancelled", "diverted"]);

/** YYYY-MM-DD of an instant in an IANA zone (en-CA formats dates as ISO). */
export function localDate(iso: string, tz: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date(iso));
}

/**
 * Contract key {flight}:FOG:{iata}:{local date}. The date is the airport-local date of the flight's
 * scheduled time on that side (estimate if there is no schedule), so a flight gets one alert per airport
 * even when the fog check runs on both sides of midnight.
 */
export function fogDedupeKey(flightId: string, iata: string, plannedIso: string, tz: string): string {
  return `${flightId}:FOG:${iata}:${localDate(plannedIso, tz)}`;
}

export function isFoggy(w: FogWeather | null | undefined, now: Date): boolean {
  if (!w) return false;
  const age = now.getTime() - Date.parse(w.observed_at);
  if (Number.isNaN(age) || age > FOG_WEATHER_MAX_AGE_MIN * MIN) return false;
  return w.fog || (w.visibility_m != null && w.visibility_m < FOG_VIS_M);
}

function inWindow(iso: string | null, now: Date): boolean {
  const t = Date.parse(iso ?? "");
  if (Number.isNaN(t)) return false;
  return t <= now.getTime() + FOG_WINDOW_HOURS * 60 * MIN && t >= now.getTime() - FOG_LOOKBACK_HOURS * 60 * MIN;
}

/** Mirror of public.fog_alert_candidates(): which (flight, side) pairs need an alert now. */
export function fogCandidates(
  flights: FlightRow[],
  weather: (iata: string) => FogWeather | null | undefined,
  tzOf: (iata: string) => string,
  sentKeys: Set<string>,
  now: Date,
  limit: number,
): FogCandidate[] {
  const out: (FogCandidate & { at: number })[] = [];
  for (const f of flights) {
    if (f.tracker_count <= 0) continue;
    const sides: { side: "dep" | "arr"; iata: string; time: string | null; planned: string | null; open: boolean }[] = [
      {
        side: "dep", iata: f.dep_iata, time: f.est_dep ?? f.sched_dep, planned: f.sched_dep ?? f.est_dep,
        open: f.act_dep == null && !NOT_DEPARTED_GONE.has(f.status),
      },
      {
        side: "arr", iata: f.arr_iata, time: f.est_arr ?? f.sched_arr, planned: f.sched_arr ?? f.est_arr,
        open: f.act_arr == null && !NOT_ARRIVED_GONE.has(f.status),
      },
    ];
    for (const s of sides) {
      if (!s.open || !s.planned || !inWindow(s.time, now)) continue;
      const w = weather(s.iata);
      if (!w || !isFoggy(w, now)) continue;
      const key = fogDedupeKey(f.id, s.iata, s.planned, tzOf(s.iata));
      if (sentKeys.has(key)) continue;
      out.push({
        flight_id: f.id, side: s.side, airport: s.iata, visibility_m: w.visibility_m, wx: w.wx, dedupe_key: key,
        at: Date.parse(s.time!),
      });
    }
  }
  // Soonest first, like the SQL (order by the departure / arrival time on that side).
  return out.sort((a, b) => a.at - b.at).slice(0, limit).map(({ at: _at, ...c }) => c);
}

/** The FOG_ALERT event for a candidate. Critical and free; muted per flight with notify_prefs.FOG_ALERT. */
export function fogEvent(c: FogCandidate): FlightEvent {
  return {
    type: "FOG_ALERT",
    severity: "critical",
    proOnly: false,
    oldValue: null,
    newValue: c.visibility_m != null ? String(c.visibility_m) : null,
    payload: { airport: c.airport, visibility_m: c.visibility_m, wx: c.wx, side: c.side },
    dedupeKey: c.dedupe_key,
  };
}
