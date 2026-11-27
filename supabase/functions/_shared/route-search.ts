// Search by route or airport (docs/P3_CONTRACTS.md B5). Pure flow over small interfaces, so the cache,
// the quota and the provider are unit-tested without Supabase.
//
// One airport-day of departures is fetched from the provider at most every 6 h (route_search_cache), costs
// 2 units of the caller's daily search quota, and is then filtered by arrival airport and airline. Results
// are flights rows (the same rows search-flight returns), so the apps track them with one user_flights
// insert, exactly like a flight-number result.
import { type Deps, ingest } from "./engine.ts";
import { SearchError } from "./engine.ts";
import type { FlightRow, FlightSnapshot } from "./types.ts";

export const ROUTE_SEARCH_COST = 2;
export const ROUTE_CACHE_HOURS = 6;
/** Dates searchable by route: yesterday (late-night departures) up to this many days ahead. */
export const ROUTE_SEARCH_MAX_DAYS_AHEAD = 30;
/** A whole airport-day can hold hundreds of departures; the list the apps show is capped. */
export const ROUTE_SEARCH_MAX_RESULTS = 200;

export interface RouteSearchInput {
  dep: string;
  arr?: string | null;
  date: string;
  airline?: string | null;
}

export interface NormalizedRouteQuery {
  dep: string;
  arr: string | null;
  date: string;
  airline: string | null;
}

export interface RouteCacheEntry {
  payload: FlightSnapshot[];
  fetchedAt: string;
}

/** route_search_cache + the search quota (reserve_search_units), per caller. */
export interface RouteSearchStore {
  getCache(dep: string, date: string): Promise<RouteCacheEntry | null>;
  putCache(dep: string, date: string, payload: FlightSnapshot[], fetchedAt: string): Promise<void>;
  /** Atomically reserves `cost` quota units for the caller; false when fewer are left. */
  reserveUnits(cost: number): Promise<boolean>;
}

const IATA_RE = /^[A-Z0-9]{3}$/;
const AIRLINE_RE = /^[A-Z0-9]{2}$/;
const DAY_MS = 86_400_000;

/** Validates and upper-cases the body. Throws SearchError("bad_input") with a user-facing message. */
export function normalizeRouteQuery(input: RouteSearchInput, now: Date): NormalizedRouteQuery {
  const dep = String(input.dep ?? "").trim().toUpperCase();
  const arrRaw = input.arr == null ? "" : String(input.arr).trim().toUpperCase();
  const airlineRaw = input.airline == null ? "" : String(input.airline).trim().toUpperCase();
  const date = String(input.date ?? "").trim();
  if (!IATA_RE.test(dep)) throw new SearchError("bad_input", "Departure airport must be a 3-letter code, e.g. DEL");
  if (arrRaw && !IATA_RE.test(arrRaw)) {
    throw new SearchError("bad_input", "Arrival airport must be a 3-letter code, e.g. BOM");
  }
  if (arrRaw && arrRaw === dep) throw new SearchError("bad_input", "Departure and arrival airports must differ");
  if (airlineRaw && !AIRLINE_RE.test(airlineRaw)) {
    throw new SearchError("bad_input", "Airline must be a 2-character code, e.g. 6E");
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new SearchError("bad_input", "Date must be YYYY-MM-DD");
  const day = Date.parse(`${date}T00:00:00Z`);
  if (
    Number.isNaN(day) || day < now.getTime() - 2 * DAY_MS ||
    day > now.getTime() + ROUTE_SEARCH_MAX_DAYS_AHEAD * DAY_MS
  ) {
    throw new SearchError("bad_input", `Date must be between yesterday and ${ROUTE_SEARCH_MAX_DAYS_AHEAD} days ahead`);
  }
  return { dep, arr: arrRaw || null, date, airline: airlineRaw || null };
}

export function isRouteCacheFresh(entry: RouteCacheEntry | null, now: Date): boolean {
  if (!entry) return false;
  const t = Date.parse(entry.fetchedAt);
  return !Number.isNaN(t) && now.getTime() - t < ROUTE_CACHE_HOURS * 3_600_000;
}

/** That day's departures matching the filters, earliest first (only legs leaving `dep` on `date`). */
export function filterDepartures(snaps: readonly FlightSnapshot[], q: NormalizedRouteQuery): FlightSnapshot[] {
  return snaps
    .filter((s) => s.depIata === q.dep && s.date === q.date)
    .filter((s) => !q.arr || s.arrIata === q.arr)
    .filter((s) => !q.airline || s.carrier === q.airline)
    .sort((a, b) =>
      (Date.parse(a.schedDep ?? "") || 0) - (Date.parse(b.schedDep ?? "") || 0) ||
      `${a.carrier}${a.number}`.localeCompare(`${b.carrier}${b.number}`)
    )
    .slice(0, ROUTE_SEARCH_MAX_RESULTS);
}

/**
 * Runs one route search. Cache fresh → no provider call and no quota. Otherwise reserve 2 units and fetch
 * the whole airport-day; when the quota is used up (or the provider fails) a stale cache entry is still
 * served, else rate_limited / provider_error.
 *
 * Rows: a leg already in flights is returned as stored when it comes from the cache (our row may be newer
 * than a cached snapshot); a fresh provider snapshot goes through ingest like a flight-number search.
 */
export async function searchRoute(
  deps: Deps,
  store: RouteSearchStore,
  input: RouteSearchInput,
): Promise<FlightRow[]> {
  const now = deps.now();
  const q = normalizeRouteQuery(input, now);
  const cached = await store.getCache(q.dep, q.date);

  let snaps: FlightSnapshot[];
  let fresh = false;
  // When the snapshots come from the cache, this is when the provider produced them.
  let snapsAt = cached?.fetchedAt ?? null;
  if (cached && isRouteCacheFresh(cached, now)) {
    snaps = cached.payload;
  } else if (!(await store.reserveUnits(ROUTE_SEARCH_COST))) {
    if (!cached) throw new SearchError("rate_limited", "Too many searches today. Try again later.");
    snaps = cached.payload; // out of quota: a stale day is better than nothing
  } else {
    let fetched: FlightSnapshot[] | null = null;
    try {
      fetched = await deps.provider.getAirportDepartures(q.dep, `${q.date}T00:00`, `${q.date}T23:59`);
    } catch (e) {
      if (!cached) throw new SearchError("provider_error", `Flight data provider error: ${String(e)}`);
      deps.log?.("route search provider failed, serving stale cache", { dep: q.dep, date: q.date, error: String(e) });
    }
    if (fetched) {
      fresh = true;
      snapsAt = now.toISOString();
      // Only what the result list needs is kept: the provider's raw payload can be large.
      snaps = fetched.map(({ raw: _raw, ...s }) => s as FlightSnapshot);
      try {
        await store.putCache(q.dep, q.date, snaps, now.toISOString());
      } catch (e) {
        deps.log?.("route cache write failed", { dep: q.dep, date: q.date, error: String(e) });
      }
    } else {
      snaps = cached!.payload;
    }
  }

  const out: FlightRow[] = [];
  for (const s of filterDepartures(snaps, q)) {
    const existing = await deps.repo.getFlightByKey(s.carrier, s.number, s.date, s.depIata);
    if (existing && !fresh) {
      out.push(existing);
      continue;
    }
    let row = (await ingest(deps, existing, s, "search")).row;
    // A new row made from a cached snapshot must not look freshly polled (search-flight would then trust
    // up to 6 h old data as "polled just now"): keep the snapshot's own time.
    if (!fresh && snapsAt && row.last_polled_at && Date.parse(snapsAt) < Date.parse(row.last_polled_at)) {
      row = await deps.repo.updateFlight(row.id, { last_polled_at: snapsAt });
    }
    out.push(row);
  }
  return out;
}
