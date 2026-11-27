// Search by route or airport (docs/P3_CONTRACTS.md B5). Pure flow over small interfaces, so the cache,
// the quota and the provider are unit-tested without Supabase.
//
// One airport-day of departures is fetched from the provider at most every 6 h (route_search_cache), costs
// 2 units of the caller's daily search quota, and is then filtered by arrival airport and airline. Only the
// legs that match are written, in bulk (one upsert, one history insert), so a 200-flight cache miss does
// not do 200 round trips before it can answer. A leg somebody already tracks still goes through ingest,
// so delay / gate / cancellation alerts stay exactly-once; that work can run after the response.
// Results are flights rows (the same rows search-flight returns), so the apps track them with one
// user_flights insert, exactly like a flight-number result.
import { changedFields, evaluateAlerts, snapshotState } from "./diff.ts";
import {
  type Deps,
  hasProInterest,
  ingest,
  isInboundOnly,
  isStaleSnapshot,
  mergeSticky,
  rowToSnapshot,
  SearchError,
  snapshotToPatch,
} from "./engine.ts";
import type { FlightUpdate, HistoryWrite } from "./repo.ts";
import { nextPollAt } from "./schedule.ts";
import type { AlertState, FlightRow, FlightSnapshot } from "./types.ts";

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

/**
 * Tracked-leg ingest is the only part of a cache miss that still walks flights one by one (alerts).
 * `start` does not run until called, so the handler can hand it to EdgeRuntime.waitUntil and tests can
 * prove the rows came back first.
 */
export interface RouteSearchOptions {
  defer?: (start: () => Promise<void>) => void;
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
 * than a cached snapshot). A fresh snapshot of a new or untracked leg is stored in bulk, with the same
 * columns ingest would write. A fresh snapshot of a tracked leg goes through ingest (alerts); pass
 * `opts.defer` to let that finish after the rows are returned.
 */
export async function searchRoute(
  deps: Deps,
  store: RouteSearchStore,
  input: RouteSearchInput,
  opts?: RouteSearchOptions,
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

  return await materializeDepartures(deps, snaps, q, fresh, snapsAt, opts?.defer);
}

function snapKey(s: FlightSnapshot): string {
  return `${s.carrier}|${s.number}|${s.date}|${s.depIata}`;
}

function rowKey(r: Pick<FlightRow, "carrier_iata" | "flight_number" | "flight_date" | "dep_iata">): string {
  return `${r.carrier_iata}|${r.flight_number}|${r.flight_date}|${r.dep_iata}`;
}

/** What ingest writes for a flight it has never seen: baseline alert state, no events. */
function newFlightInsert(snap: FlightSnapshot, provider: string, now: Date, polledAt: string): Partial<FlightRow> {
  return {
    ...snapshotToPatch(snap, provider),
    alert_state: snapshotState(snap),
    live_seen: !!snap.live,
    last_polled_at: polledAt,
    next_poll_at: nextPollAt(snap, {
      now, hasPro: false, webhookActive: false, changedThisPoll: false, errorCount: 0,
    })?.toISOString() ?? null,
  };
}

/**
 * The flight row ingest would persist for an existing untracked leg. Tracker counts, the inbound link
 * and webhook columns are absent on purpose: the bulk update leaves them stored.
 */
function untrackedPatch(
  existing: FlightRow,
  merged: FlightSnapshot,
  provider: string,
  now: Date,
  polledAt: string,
  state: AlertState,
  changed: readonly string[],
): FlightUpdate {
  const webhookActive = !!existing.webhook_subscription_id &&
    (!existing.webhook_expires_at || Date.parse(existing.webhook_expires_at) > now.getTime());
  return {
    ...snapshotToPatch(merged, provider),
    id: existing.id,
    carrier_iata: merged.carrier,
    flight_number: merged.number,
    flight_date: merged.date,
    dep_iata: merged.depIata,
    alert_state: state,
    last_polled_at: polledAt,
    next_poll_at: nextPollAt(merged, {
      now,
      hasPro: hasProInterest(existing, now),
      webhookActive,
      changedThisPoll: changed.length > 0,
      errorCount: 0,
      inboundOnly: isInboundOnly(existing, now),
    })?.toISOString() ?? null,
    lease_until: null,
    poll_error_count: 0,
    last_error: null,
    live_seen: !!(existing.live_seen || merged.live),
  };
}

/** Public columns of the row ingest is about to write, so the response can leave before that write. */
function previewTracked(
  existing: FlightRow,
  merged: FlightSnapshot,
  provider: string,
  now: Date,
  polledAt: string,
): FlightRow {
  const prev = rowToSnapshot(existing);
  const changed = changedFields(prev, merged);
  const patch = untrackedPatch(existing, merged, provider, now, polledAt, existing.alert_state, changed);
  return { ...existing, ...patch, updated_at: polledAt };
}

/**
 * Turn the filtered snapshots into flights rows.
 * Cache hit on a leg we already store: return it untouched.
 * Fresh snapshot, nobody tracking it: one bulk upsert + one bulk history write (no alerts to send).
 * Fresh snapshot, somebody tracking it (or a Live Activity): real ingest, so alerts stay exactly-once.
 */
async function materializeDepartures(
  deps: Deps,
  snaps: FlightSnapshot[],
  q: NormalizedRouteQuery,
  fresh: boolean,
  snapsAt: string | null,
  defer?: RouteSearchOptions["defer"],
): Promise<FlightRow[]> {
  const wanted = filterDepartures(snaps, q);
  if (!wanted.length) return [];

  const now = deps.now();
  const polledAt = !fresh && snapsAt ? snapsAt : now.toISOString();
  const provider = deps.provider.name;
  const stored = await deps.repo.getFlightsByAirportDate(q.dep, q.date);
  const byKey = new Map(stored.map((r) => [rowKey(r), r]));

  const activityIds = new Set<string>();
  if (fresh) {
    const existingIds = [...new Set(wanted.map((s) => byKey.get(snapKey(s))?.id).filter((id): id is string => !!id))];
    if (existingIds.length) {
      for (const id of await deps.repo.liveActivityFlightIds(existingIds)) activityIds.add(id);
    }
  }

  const toInsert: Partial<FlightRow>[] = [];
  const toUpdate: FlightUpdate[] = [];
  const histories: HistoryWrite[] = [];
  const tracked: { existing: FlightRow; raw: FlightSnapshot }[] = [];
  const result = new Map<string, FlightRow>();
  const seen = new Set<string>();

  for (const raw of wanted) {
    const key = snapKey(raw);
    if (seen.has(key)) continue;
    seen.add(key);
    const existing = byKey.get(key) ?? null;
    if (existing && !fresh) {
      result.set(key, existing);
      continue;
    }
    if (existing && isStaleSnapshot(existing, raw)) {
      histories.push({ flightId: existing.id, source: "search_stale", changed: [], snapshot: raw });
      result.set(key, existing);
      continue;
    }
    const prev = existing ? rowToSnapshot(existing) : null;
    const merged = mergeSticky(prev, raw);
    if (!existing) {
      toInsert.push(newFlightInsert(merged, provider, now, polledAt));
      continue;
    }
    const watched = existing.tracker_count > 0 || existing.pro_tracker_count > 0 || activityIds.has(existing.id);
    if (fresh && watched) {
      tracked.push({ existing, raw });
      continue;
    }
    const changed = changedFields(prev, merged);
    const { state } = evaluateAlerts(existing.id, existing.alert_state ?? {}, prev, merged);
    toUpdate.push(untrackedPatch(existing, merged, provider, now, polledAt, state, changed));
    if (changed.length) histories.push({ flightId: existing.id, source: "search", changed, snapshot: merged });
  }

  if (toInsert.length) {
    const known = new Set(stored.map((r) => r.id));
    const inserted = await deps.repo.insertFlights(toInsert);
    for (const row of inserted) {
      const key = rowKey(row);
      if (!known.has(row.id)) {
        const raw = wanted.find((s) => snapKey(s) === key);
        const merged = raw ? mergeSticky(null, raw) : rowToSnapshot(row);
        histories.push({
          flightId: row.id, source: "search", changed: changedFields(null, merged), snapshot: merged,
        });
      }
      result.set(key, row);
    }
  }

  if (toUpdate.length) {
    for (const row of await deps.repo.updateFlights(toUpdate)) result.set(rowKey(row), row);
  }
  if (histories.length) await deps.repo.insertHistories(histories);

  const runTracked = async () => {
    let first: unknown = null;
    for (const t of tracked) {
      try {
        await ingest(deps, t.existing, t.raw, "search");
      } catch (e) {
        // One bad leg must not drop the alerts for the rest. The search already answered.
        deps.log?.("route ingest failed", { flight: t.existing.id, error: String(e) });
        first ??= e;
      }
    }
    if (first) throw first;
  };

  if (tracked.length) {
    if (defer) {
      for (const t of tracked) {
        const merged = mergeSticky(rowToSnapshot(t.existing), t.raw);
        result.set(snapKey(t.raw), previewTracked(t.existing, merged, provider, now, polledAt));
      }
      defer(runTracked);
    } else {
      for (const t of tracked) {
        const res = await ingest(deps, t.existing, t.raw, "search");
        result.set(rowKey(res.row), res.row);
      }
    }
  }

  return wanted.map((s) => {
    const row = result.get(snapKey(s));
    if (!row) throw new Error(`route search lost ${snapKey(s)}`);
    return row;
  });
}
