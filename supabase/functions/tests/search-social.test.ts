// P3 backend-search-social (docs/P3_CONTRACTS.md): search by route (B5), past flight search (B6), crew mode
// in the push fan-out (B12), the public airport status and global report handlers (B8, B11) and Pro via a
// family in sync-entitlement (B13).
import { assert, assertEquals, assertFalse, assertRejects, assertThrows } from "@std/assert";
import { applyFamilyPro, type EntitlementResponse } from "../_shared/billing.ts";
import { CREW_MUTED_EVENTS, crewMutes } from "../_shared/crew.ts";
import { fanOut, ingest, SearchError, searchFlight } from "../_shared/engine.ts";
import {
  hasOutcome,
  isFinishedRow,
  PAST_SEARCH_MAX_DAYS,
  searchPastFlight,
  validatePastDate,
} from "../_shared/past-search.ts";
import {
  bucketUsers,
  currentIndianYear,
  handleAirportStatus,
  handleGlobalReport,
  type PublicAirportStatus,
  type PublicGlobalReport,
  REPORT_MIN_LEGS,
  toPublicAirportStatus,
  toPublicGlobalReport,
  TtlCache,
} from "../_shared/public-stats.ts";
import {
  filterDepartures,
  isRouteCacheFresh,
  normalizeRouteQuery,
  ROUTE_CACHE_HOURS,
  ROUTE_SEARCH_COST,
  type RouteCacheEntry,
  type RouteSearchStore,
  searchRoute,
} from "../_shared/route-search.ts";
import { EVENT_TYPES, type EventType, type FlightEvent, type FlightSnapshot } from "../_shared/types.ts";
import { makeDeps, snap } from "./helpers.ts";

const NOW = "2026-11-25T00:00:00Z";
const DAY = "2026-11-26";

// ================================================================================================
// B5: search by route
// ================================================================================================

/** route_search_cache + a per-user unit quota, in memory. */
class MemRouteStore implements RouteSearchStore {
  cache = new Map<string, RouteCacheEntry>();
  used = 0;
  reservations: number[] = [];
  writes = 0;
  constructor(public limit = 30) {}
  getCache(dep: string, date: string) {
    return Promise.resolve(this.cache.get(`${dep}:${date}`) ?? null);
  }
  putCache(dep: string, date: string, payload: FlightSnapshot[], fetchedAt: string) {
    this.writes++;
    this.cache.set(`${dep}:${date}`, { payload: structuredClone(payload), fetchedAt });
    return Promise.resolve();
  }
  reserveUnits(cost: number) {
    this.reservations.push(cost);
    if (this.used + cost > this.limit) return Promise.resolve(false);
    this.used += cost;
    return Promise.resolve(true);
  }
}

Deno.test("route query: validation and normalisation", () => {
  const now = new Date(NOW);
  assertEquals(normalizeRouteQuery({ dep: " del", arr: "bom ", date: DAY, airline: "6e" }, now), {
    dep: "DEL",
    arr: "BOM",
    date: DAY,
    airline: "6E",
  });
  assertEquals(normalizeRouteQuery({ dep: "DEL", arr: "", date: DAY, airline: null }, now), {
    dep: "DEL",
    arr: null,
    date: DAY,
    airline: null,
  });
  const bad = (input: Parameters<typeof normalizeRouteQuery>[0]) =>
    assertThrows(() => normalizeRouteQuery(input, now), SearchError);
  bad({ dep: "DELHI", date: DAY });
  bad({ dep: "", date: DAY });
  bad({ dep: "DEL", arr: "BOMB", date: DAY });
  bad({ dep: "DEL", arr: "DEL", date: DAY });
  bad({ dep: "DEL", airline: "IGO", date: DAY });
  bad({ dep: "DEL", date: "26-11-2026" });
  bad({ dep: "DEL", date: "2026-11-20" }); // too far back
  bad({ dep: "DEL", date: "2027-01-30" }); // more than 30 days ahead
  normalizeRouteQuery({ dep: "DEL", date: "2026-11-24" }, now); // yesterday is fine
  normalizeRouteQuery({ dep: "DEL", date: "2026-12-24" }, now); // within 30 days
});

Deno.test("route search: cache miss fetches the airport-day once, costs 2 units, filters by arr + airline", async () => {
  const h = makeDeps(NOW);
  const store = new MemRouteStore();
  const rows = await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  assertEquals(h.provider.calls, 1, "one provider call for the whole day");
  assertEquals(store.reservations, [ROUTE_SEARCH_COST]);
  assertEquals(store.used, 2);
  assertEquals(store.writes, 1);
  assert(rows.length > 10, `several DEL→BOM departures, got ${rows.length}`);
  for (const r of rows) {
    assertEquals([r.dep_iata, r.arr_iata, r.flight_date], ["DEL", "BOM", DAY]);
    assert(r.id, "rows are stored flights the apps can track");
    assert(h.repo.flights.has(r.id));
  }
  const times = rows.map((r) => Date.parse(r.sched_dep!));
  assertEquals(times, [...times].sort((a, b) => a - b), "earliest first");
  // the cached day holds every departure (DEL→DXB too), without the provider's raw payload
  const cached = store.cache.get(`DEL:${DAY}`)!;
  assert(cached.payload.some((s) => s.arrIata === "DXB"));
  assert(cached.payload.every((s) => !("raw" in s)));

  // same airport-day, other filters: served from the cache, no quota, no provider call
  const sixE = await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY, airline: "6E" });
  assertEquals(h.provider.calls, 1);
  assertEquals(store.used, 2);
  assert(sixE.length > 0 && sixE.length < rows.length);
  assert(sixE.every((r) => r.carrier_iata === "6E"));
  const whole = await searchRoute(h.deps, store, { dep: "DEL", date: DAY });
  assert(whole.some((r) => r.arr_iata === "DXB") && whole.some((r) => r.arr_iata === "BOM"), "airport-only search");
  assertEquals(h.provider.calls, 1);
});

Deno.test("route search: the cache is fresh for 6 h, then the day is fetched (and paid) again", async () => {
  const h = makeDeps(NOW);
  const store = new MemRouteStore();
  await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  h.clock.advance(ROUTE_CACHE_HOURS * 60 - 1);
  await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  assertEquals(h.provider.calls, 1, "still fresh at 5 h 59 min");
  h.clock.advance(2);
  await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  assertEquals(h.provider.calls, 2, "stale after 6 h");
  assertEquals(store.used, 4);
  assert(isRouteCacheFresh(store.cache.get(`DEL:${DAY}`)!, h.clock.now()));
  assertFalse(isRouteCacheFresh(null, h.clock.now()));
  assertFalse(isRouteCacheFresh({ payload: [], fetchedAt: "garbage" }, h.clock.now()));
});

Deno.test("route search: out of quota → stale cache if any, else rate_limited; provider error likewise", async () => {
  const h = makeDeps(NOW);
  const store = new MemRouteStore(3); // room for one route search (2 units), not two
  await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  h.clock.advance(7 * 60);
  const stale = await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  assert(stale.length > 0, "stale day served when the quota is used up");
  assertEquals(h.provider.calls, 1);
  const err = await assertRejects(() => searchRoute(h.deps, store, { dep: "BOM", date: DAY }), SearchError);
  assertEquals(err.code, "rate_limited");
  assertEquals(store.used, 2, "a refused reservation uses nothing");

  const h2 = makeDeps(NOW);
  const store2 = new MemRouteStore();
  h2.provider.getAirportDepartures = () => Promise.reject(new Error("boom"));
  const e2 = await assertRejects(() => searchRoute(h2.deps, store2, { dep: "DEL", date: DAY }), SearchError);
  assertEquals(e2.code, "provider_error");
  // with a stale entry the provider failure is hidden
  store2.cache.set(`DEL:${DAY}`, {
    payload: [snap({ date: DAY, number: "11", schedDep: `${DAY}T04:00:00Z` })],
    fetchedAt: "2026-11-24T00:00:00Z",
  });
  const rows = await searchRoute(h2.deps, store2, { dep: "DEL", date: DAY });
  assertEquals(rows.map((r) => r.flight_number), ["11"]);
  // a row made from the stale snapshot keeps the snapshot's time, so search-flight won't trust it as fresh
  assertEquals(Date.parse(rows[0].last_polled_at!), Date.parse("2026-11-24T00:00:00Z"));
});

Deno.test("route search: rows made from a fresh fetch carry the fetch time", async () => {
  const h = makeDeps(NOW);
  const store = new MemRouteStore();
  const [row] = await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY });
  assertEquals(Date.parse(row.last_polled_at!), Date.parse(NOW));
  // served from the (fresh) cache 2 h later, a new row is dated by the fetch, not "now"
  h.clock.advance(120);
  store.cache.set(`BOM:${DAY}`, {
    payload: [snap({ date: DAY, number: "12", depIata: "BOM", arrIata: "DEL", schedDep: `${DAY}T05:00:00Z` })],
    fetchedAt: NOW,
  });
  const [fromCache] = await searchRoute(h.deps, store, { dep: "BOM", date: DAY });
  assertEquals(Date.parse(fromCache.last_polled_at!), Date.parse(NOW));
});

Deno.test("route search: cached snapshots never overwrite a newer stored row", async () => {
  const h = makeDeps(NOW);
  const store = new MemRouteStore();
  const old = snap({ date: DAY, number: "77", schedDep: `${DAY}T04:00:00Z`, depGate: "1" });
  store.cache.set(`DEL:${DAY}`, { payload: [old], fetchedAt: NOW });
  const existing = (await ingest(h.deps, null, { ...old, depGate: "42" }, "search")).row;
  const [row] = await searchRoute(h.deps, store, { dep: "DEL", date: DAY });
  assertEquals(row.id, existing.id);
  assertEquals(row.dep_gate, "42", "the stored row wins over the cached snapshot");
});

/** Counts Repo calls searchRoute makes. Internal calls (ingest → updateFlight) are not counted. */
function spyRepo(repo: ReturnType<typeof makeDeps>["repo"]) {
  const counts: Record<string, number> = {};
  const args: Record<string, unknown[][]> = {};
  const wrapped = new Proxy(repo, {
    get(target, prop, recv) {
      if (typeof prop !== "string") return Reflect.get(target, prop, recv);
      const value = Reflect.get(target, prop, target);
      if (typeof value !== "function") return value;
      return (...a: unknown[]) => {
        counts[prop] = (counts[prop] ?? 0) + 1;
        (args[prop] ??= []).push(a);
        return (value as (...xs: unknown[]) => unknown).apply(target, a);
      };
    },
  });
  return { repo: wrapped, counts, args };
}

const call = (counts: Record<string, number>, name: string) => counts[name] ?? 0;

Deno.test("route search: cache miss of ~200 flights bulk-ingests only the requested arr and airline", async () => {
  const h = makeDeps(NOW);
  const spy = spyRepo(h.repo);
  h.deps.repo = spy.repo;
  const store = new MemRouteStore();
  const matching = Array.from({ length: 200 }, (_, i) => snap({
    date: DAY,
    number: String(i + 1),
    carrier: "6E",
    arrIata: "BOM",
    schedDep: new Date(Date.parse(`${DAY}T00:00:00Z`) + i * 60_000).toISOString(),
  }));
  const excluded = [
    ...Array.from({ length: 15 }, (_, i) => snap({
      date: DAY, number: String(201 + i), carrier: "6E", arrIata: "DXB",
      schedDep: `${DAY}T18:00:00Z`,
    })),
    ...Array.from({ length: 15 }, (_, i) => snap({
      date: DAY, number: String(i + 1), carrier: "AI", airlineName: "Air India", arrIata: "BOM",
      schedDep: `${DAY}T18:10:00Z`,
    })),
    ...Array.from({ length: 10 }, (_, i) => snap({
      date: DAY, number: String(16 + i), carrier: "AI", airlineName: "Air India", arrIata: "DXB",
      schedDep: `${DAY}T18:20:00Z`,
    })),
  ];
  h.provider.getAirportDepartures = () => Promise.resolve([...matching, ...excluded]);

  const rows = await searchRoute(h.deps, store, { dep: "DEL", arr: "BOM", date: DAY, airline: "6E" });

  assertEquals(rows.length, 200, "the matching legs, capped at the result list");
  assert(rows.every((r) => r.carrier_iata === "6E" && r.arr_iata === "BOM" && r.flight_date === DAY));
  assertEquals(h.repo.flights.size, 200, "excluded legs were not written");
  for (const f of h.repo.flights.values()) {
    assertEquals(f.carrier_iata, "6E");
    assertEquals(f.arr_iata, "BOM");
  }
  const cached = store.cache.get(`DEL:${DAY}`)!;
  assertEquals(cached.payload.length, 240, "the cache still holds the whole airport-day");
  assert(cached.payload.some((s) => s.arrIata === "DXB"));
  assert(cached.payload.some((s) => s.carrier === "AI"));

  assertEquals(call(spy.counts, "insertFlights"), 1);
  assertEquals((spy.args.insertFlights[0][0] as unknown[]).length, 200);
  assertEquals(call(spy.counts, "insertHistories"), 1);
  assertEquals((spy.args.insertHistories[0][0] as unknown[]).length, 200);
  assertEquals(call(spy.counts, "getFlightsByAirportDate"), 1);
  // The old path was one get + one insert + one history row per flight (~600 calls).
  assertEquals(call(spy.counts, "insertFlight"), 0);
  assertEquals(call(spy.counts, "getFlightByKey"), 0);
  assertEquals(call(spy.counts, "insertHistory"), 0);
  assertEquals(call(spy.counts, "updateFlight"), 0);
  const total = Object.values(spy.counts).reduce((a, b) => a + b, 0);
  assert(total <= 5, `cache-miss DB calls must stay bounded, got ${total}: ${JSON.stringify(spy.counts)}`);
});

Deno.test("route search: refreshing 200 stored untracked legs is still one bulk write", async () => {
  const h = makeDeps(NOW);
  const store = new MemRouteStore();
  const snaps = Array.from({ length: 200 }, (_, i) => snap({
    date: DAY,
    number: String(i + 1),
    schedDep: new Date(Date.parse(`${DAY}T00:00:00Z`) + i * 60_000).toISOString(),
    estDep: new Date(Date.parse(`${DAY}T00:20:00Z`) + i * 60_000).toISOString(),
  }));
  h.provider.getAirportDepartures = () => Promise.resolve(snaps);
  await searchRoute(h.deps, store, { dep: "DEL", date: DAY });
  assertEquals(h.repo.flights.size, 200);

  h.clock.advance(7 * 60); // cache expired
  const spy = spyRepo(h.repo);
  h.deps.repo = spy.repo;
  const again = await searchRoute(h.deps, store, { dep: "DEL", date: DAY });
  assertEquals(again.length, 200);
  assertEquals(h.repo.flights.size, 200, "no duplicates");
  assertEquals(call(spy.counts, "insertFlights"), 0);
  assertEquals(call(spy.counts, "insertFlight"), 0);
  assertEquals(call(spy.counts, "getFlightByKey"), 0);
  assertEquals(call(spy.counts, "updateFlights"), 1);
  assertEquals((spy.args.updateFlights[0][0] as unknown[]).length, 200);
  assertEquals(call(spy.counts, "updateFlight"), 0);
  const total = Object.values(spy.counts).reduce((a, b) => a + b, 0);
  assert(total <= 6, `refresh DB calls must stay bounded, got ${total}: ${JSON.stringify(spy.counts)}`);
  // tracker_count stays 0: a bulk update must not look like a fresh insert
  for (const f of h.repo.flights.values()) assertEquals(f.tracker_count, 0);
});

Deno.test("route search: a new bulk-stored leg matches what ingest would have written", async () => {
  const s = snap({ date: DAY, number: "77", depGate: "12", schedDep: `${DAY}T04:00:00Z` });
  const h = makeDeps(NOW);
  h.provider.getAirportDepartures = () => Promise.resolve([s]);
  const [row] = await searchRoute(h.deps, new MemRouteStore(), { dep: "DEL", date: DAY });
  const viaH = makeDeps(NOW);
  const via = (await ingest(viaH.deps, null, s, "search")).row;
  for (const f of ["status", "dep_gate", "sched_dep", "arr_iata", "last_polled_at", "next_poll_at", "live_seen", "provider"] as const) {
    assertEquals(row[f], via[f], f);
  }
  assertEquals(row.alert_state, via.alert_state);
  assertEquals(h.repo.history.map((x) => [x.source, x.changed]), viaH.repo.history.map((x) => [x.source, x.changed]));
  assert(h.repo.history[0].changed.includes("depGate"));
});

Deno.test("route search: a tracked leg still alerts, and that ingest can finish after the response", async () => {
  const h = makeDeps(NOW);
  const old = snap({
    date: DAY, number: "77", depGate: "1", schedDep: `${DAY}T04:00:00Z`,
    providerUpdatedAt: `${DAY}T00:00:00Z`,
  });
  const existing = (await ingest(h.deps, null, old, "search")).row;
  h.repo.track(existing.id, { userId: "u", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok" }] });
  const next = { ...old, depGate: "42", providerUpdatedAt: `${DAY}T01:00:00Z` };
  h.provider.getAirportDepartures = () => Promise.resolve([next]);

  const done = await searchRoute(h.deps, new MemRouteStore(), { dep: "DEL", date: DAY });
  assertEquals(done[0].id, existing.id);
  assertEquals(done[0].dep_gate, "42");
  assertEquals(h.repo.flights.get(existing.id)!.dep_gate, "42", "without defer, ingest finishes before return");
  assert(h.pusher.sent.some((m) => m.token === "tok" && m.data?.event_type === "GATE_CHANGE"));

  const h2 = makeDeps(NOW);
  const existing2 = (await ingest(h2.deps, null, old, "search")).row;
  h2.repo.track(existing2.id, { userId: "u", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok" }] });
  h2.provider.getAirportDepartures = () => Promise.resolve([next]);
  const later: { start: (() => Promise<void>) | null } = { start: null };
  const preview = await searchRoute(h2.deps, new MemRouteStore(), { dep: "DEL", date: DAY }, {
    defer: (fn) => {
      later.start = fn;
    },
  });
  assertEquals(preview[0].dep_gate, "42", "the response already shows the fresh gate");
  assertEquals(h2.repo.flights.get(existing2.id)!.dep_gate, "1", "ingest has not run yet");
  assertEquals(h2.pusher.sent.length, 0);
  const start = later.start;
  if (!start) throw new Error("tracked ingest was handed back to the caller");
  await start();
  assertEquals(h2.repo.flights.get(existing2.id)!.dep_gate, "42");
  assert(h2.pusher.sent.some((m) => m.token === "tok" && m.data?.event_type === "GATE_CHANGE"));
  assertEquals(h2.repo.flights.get(existing2.id)!.tracker_count, 1, "ingest must not reset trackers");
});

Deno.test("route search: filterDepartures keeps that day's legs from dep, sorted, capped", () => {
  const q = { dep: "DEL", arr: null, date: DAY, airline: null };
  const s = (n: string, over: Partial<FlightSnapshot>) => snap({ date: DAY, number: n, ...over });
  const out = filterDepartures([
    s("3", { schedDep: `${DAY}T09:00:00Z` }),
    s("1", { schedDep: `${DAY}T03:00:00Z` }),
    s("2", { schedDep: `${DAY}T03:00:00Z`, carrier: "AI" }),
    s("4", { depIata: "BOM" }),
    s("5", { date: "2026-11-27" }),
  ], q);
  assertEquals(out.map((x) => `${x.carrier}${x.number}`), ["6E1", "AI2", "6E3"]);
  const many = Array.from({ length: 250 }, (_, i) => s(String(i + 1), { schedDep: `${DAY}T03:00:00Z` }));
  assertEquals(filterDepartures(many, q).length, 200);
});

// ================================================================================================
// B6: past flight search
// ================================================================================================

Deno.test("past search: dates up to 365 days back, not the future", () => {
  const now = new Date("2026-11-26T10:00:00Z");
  validatePastDate("2026-11-26", now);
  validatePastDate("2025-11-26", now); // exactly 365 days
  validatePastDate("2026-11-27", now); // a time-zone day of slack
  assertThrows(() => validatePastDate("2025-11-25", now), SearchError);
  assertThrows(() => validatePastDate("2026-11-29", now), SearchError);
  assertThrows(() => validatePastDate("yesterday", now), SearchError);
  assertEquals(PAST_SEARCH_MAX_DAYS, 365);
});

Deno.test("past search: a new past leg is stored unpolled and goes into leg history at once", async () => {
  const h = makeDeps("2026-11-26T10:00:00Z");
  const rows = await searchPastFlight(h.deps, "u1", { flight: "6E 2340", date: "2026-06-01" });
  assertEquals(rows.length, 1);
  const r = rows[0];
  assertEquals(r.next_poll_at, null, "never polled");
  assert(r.act_dep && r.act_arr, "the provider's actuals are stored");
  assert(isFinishedRow(r));
  const hist = h.repo.legHistory.get(r.id);
  assert(hist, "flight_leg_history row written");
  assertEquals(hist.final_status, r.status);
  assertEquals(typeof hist.dep_delay_min, "number", "delay from the actuals");
  assertEquals(h.provider.calls, 1);

  // a second search for the finished leg never calls the provider again
  const again = await searchPastFlight(h.deps, "u1", { flight: "6E2340", date: "2026-06-01" });
  assertEquals(again.map((x) => x.id), [r.id]);
  assertEquals(h.provider.calls, 1);

  // the claim loop never picks it up
  assertEquals((await h.repo.claimDueFlights(10, 60)).length, 0);
});

Deno.test("past search: the normal mode still refuses old dates; past mode honours the quota", async () => {
  const h = makeDeps("2026-11-26T10:00:00Z");
  const e = await assertRejects(
    () => searchFlight(h.deps, "u1", { flight: "6E 2340", date: "2026-06-01" }),
    SearchError,
  );
  assertEquals(e.code, "bad_input");
  const limits = { perUserPerDay: 1, freshForMinutes: 10 };
  // the caller's provider logs calls with the user id (what buildDeps does in production)
  h.provider.onCall = (info) => void h.repo.logProviderCall({ provider: "mock", ...info, userId: "u2" });
  await searchPastFlight(h.deps, "u2", { flight: "6E 2340", date: "2026-06-01" }, limits);
  const e2 = await assertRejects(
    () => searchPastFlight(h.deps, "u2", { flight: "6E 2350", date: "2026-06-02" }, limits),
    SearchError,
  );
  assertEquals(e2.code, "rate_limited");
  const e3 = await assertRejects(
    () => searchPastFlight(h.deps, "u2", { flight: "hello", date: "2026-06-02" }),
    SearchError,
  );
  assertEquals(e3.code, "bad_input");
});

Deno.test("past search: a leg someone tracks keeps its own lifecycle", async () => {
  const h = makeDeps("2026-11-26T10:00:00Z");
  const tracked = (await ingest(
    h.deps,
    null,
    snap({ number: "2340", date: "2026-11-26", schedDep: "2026-11-26T12:00:00Z" }),
    "search",
  )).row;
  h.repo.track(tracked.id, { userId: "t", isPro: false, prefs: {}, devices: [] });
  const before = h.repo.flights.get(tracked.id)!.next_poll_at;
  const [row] = await searchPastFlight(h.deps, "u1", { flight: "6E 2340", date: "2026-11-26" });
  assertEquals(row.id, tracked.id);
  assertEquals(h.repo.flights.get(tracked.id)!.next_poll_at, before, "polling untouched");
});

Deno.test("past search: hasOutcome", () => {
  assert(hasOutcome({ actDep: "x", actArr: null, status: "departed" }));
  assert(hasOutcome({ actDep: null, actArr: null, status: "cancelled" }));
  assertFalse(hasOutcome({ actDep: null, actArr: null, status: "scheduled" }));
});

// ================================================================================================
// B12: crew mode
// ================================================================================================

function ev(type: EventType): FlightEvent {
  return {
    type,
    severity: "info",
    proOnly: false,
    oldValue: null,
    newValue: null,
    payload: {},
    dedupeKey: `k:${type}`,
  };
}

Deno.test("crew mode: check-in and boarding are muted, everything else is not", () => {
  assertEquals([...CREW_MUTED_EVENTS].sort(), ["BOARDING", "CHECKIN_CLOSING", "CHECKIN_OPEN", "GATE_CLOSED"]);
  for (const t of EVENT_TYPES) {
    assertEquals(crewMutes({ crew_mode: true }, ev(t)), CREW_MUTED_EVENTS.has(t), t);
    assertFalse(crewMutes({ crew_mode: false }, ev(t)));
    assertFalse(crewMutes({}, ev(t)), "missing = off");
  }
});

Deno.test("crew mode: the fan-out skips CHECKIN_* / BOARDING for crew users only", async () => {
  const h = makeDeps(NOW);
  const row = (await ingest(h.deps, null, snap(), "search")).row;
  h.repo.track(row.id, {
    userId: "pilot",
    isPro: false,
    prefs: {},
    devices: [{ id: "dP", token: "tok-pilot" }],
    profile: { crew_mode: true },
  });
  h.repo.track(row.id, { userId: "pax", isPro: false, prefs: {}, devices: [{ id: "dX", token: "tok-pax" }] });
  const events = (["BOARDING", "CHECKIN_OPEN", "CHECKIN_CLOSING", "GATE_CHANGE", "DELAY"] as EventType[])
    .map((t, i) => ({ ...ev(t), id: `e${i}` }));
  await fanOut(h.deps, row, events, false);
  const got = (tok: string) => h.pusher.sent.filter((m) => m.token === tok).map((m) => m.data!.event_id).sort();
  assertEquals(got("tok-pax"), ["e0", "e1", "e2", "e3", "e4"]);
  assertEquals(got("tok-pilot"), ["e3", "e4"], "crew get gate and delay alerts, not check-in / boarding");
});

// ================================================================================================
// B8 / B11: public statistics
// ================================================================================================

Deno.test("public stats: TtlCache expires and stays bounded", () => {
  const c = new TtlCache<number>(1000, 2);
  c.set("a", 1, 0);
  assertEquals(c.get("a", 999), 1);
  assertEquals(c.get("a", 1000), undefined);
  c.set("a", 1, 0);
  c.set("b", 2, 0);
  c.set("c", 3, 0);
  assertEquals(c.get("a", 1), undefined, "oldest evicted at the bound");
  assertEquals(c.get("c", 1), 3);
});

const RAW_STATUS = {
  iata: "DEL",
  name: "Indira Gandhi International Airport",
  city: "New Delhi",
  tz: "Asia/Kolkata",
  lat: 28.5665,
  lon: 77.1031,
  level: "severe",
  headline: "Fog: visibility 300 m",
  updated_at: "2026-11-25T00:00:00Z",
  weather: {
    summary: "Fog: visibility 300 m",
    visibility_m: 300,
    category: "LIFR",
    fog: true,
    observed_at: "x",
    raw_metar: "VIDP",
  },
  today: { date: "2026-11-25", departures: 410, departed: 200, delayed_pct: 44.4, cancelled: 12, user_id: "leak" },
  detail: { avg_dep_delay_min: 50 },
  user_id: "00000000-0000-0000-0000-000000000001",
};

Deno.test("airport-status: whitelisted public shape", () => {
  const s = toPublicAirportStatus(RAW_STATUS);
  assertEquals(Object.keys(s).sort(), [
    "city",
    "headline",
    "iata",
    "lat",
    "level",
    "lon",
    "name",
    "today",
    "tz",
    "updated_at",
    "weather",
  ]);
  assertEquals([s.lat, s.lon], [28.5665, 77.1031]);
  assertEquals(s.today, { date: "2026-11-25", departures: 410, departed: 200, delayed_pct: 44, cancelled: 12 });
  assertEquals(Object.keys(s.weather!).sort(), ["category", "fog", "observed_at", "summary", "visibility_m"]);
  assertFalse(JSON.stringify(s).includes("leak") || JSON.stringify(s).includes("user_id"));
  assertEquals(toPublicAirportStatus({ ...RAW_STATUS, level: "<script>", weather: null }).level, "normal");
  assertEquals(toPublicAirportStatus({ ...RAW_STATUS, weather: null }).weather, null);
});

Deno.test("airport-status: GET ?iata, 5-minute cache, CORS, errors", async () => {
  let t = Date.parse(NOW);
  let lookups = 0;
  const deps = {
    lookup: (iata: string) => {
      lookups++;
      return Promise.resolve(iata === "DEL" ? RAW_STATUS : null);
    },
    now: () => new Date(t),
    cache: new TtlCache<PublicAirportStatus | null>(300_000),
  };
  const get = (q: string, method = "GET") =>
    handleAirportStatus(new Request(`https://x/airport-status${q}`, { method }), deps);

  const res = await get("?iata=del");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("access-control-allow-origin"), "*");
  assertEquals(res.headers.get("cache-control"), "public, max-age=300");
  assertEquals((await res.json()).airport.iata, "DEL");
  await (await get("?iata=DEL")).body?.cancel();
  assertEquals(lookups, 1, "served from the 5-minute cache");
  t += 300_000;
  await (await get("?iata=DEL")).body?.cancel();
  assertEquals(lookups, 2, "refreshed after 5 minutes");

  assertEquals((await get("?iata=ZZZ")).status, 404);
  assertEquals((await get("?iata=DELHI")).status, 400);
  assertEquals((await get("")).status, 400);
  assertEquals((await get("?iata=DEL", "POST")).status, 405);
  assertEquals((await get("?iata=DEL", "OPTIONS")).status, 204);
  const failing = {
    ...deps,
    cache: new TtlCache<PublicAirportStatus | null>(1),
    lookup: () => Promise.reject(new Error("db")),
  };
  const r503 = await handleAirportStatus(new Request("https://x/airport-status?iata=BOM"), failing);
  assertEquals(r503.status, 503);
  assertEquals(r503.headers.get("cache-control"), "no-store");
  await r503.body?.cancel();
});

const RAW_REPORT = {
  year: 2026,
  generated_at: "2026-11-25T00:00:00Z",
  totals: { flights_tracked: 52000, distance_km: 61000000, unique_users: 12345, legs: 90000 },
  airlines: {
    most_delayed: [
      { carrier: "SG", name: "SpiceJet", legs: 5000, on_time_pct: 61, avg_dep_delay: 27 },
      { carrier: "ZZ", name: "Tiny Air", legs: 199, on_time_pct: 10, avg_dep_delay: 90 },
    ],
    most_punctual: [{ carrier: "6E", name: "IndiGo", legs: 30000, on_time_pct: 88, avg_dep_delay: 8, user_id: "x" }],
  },
  airports: {
    most_delayed: [{ iata: "DEL", name: "Delhi", city: "New Delhi", legs: 9000, on_time_pct: 70, avg_dep_delay: 21 }],
    most_punctual: [{
      iata: "IXC",
      name: "Chandigarh",
      city: "Chandigarh",
      legs: 150,
      on_time_pct: 99,
      avg_dep_delay: 2,
    }],
  },
  busiest_routes: [
    { dep: "DEL", arr: "BOM", legs: 4000, dep_lat: 28.56, dep_lon: 77.1, arr_lat: 19.09, arr_lon: 72.87, extra: "x" },
    { dep: "IXC", arr: "DEL", legs: 12 },
  ],
  fog: { airport: "DEL", fog_days: 21, fog_delay_days: 14 },
};

Deno.test("global report: k-anonymity is re-applied on the way out", () => {
  const r = toPublicGlobalReport(RAW_REPORT, 2026);
  assertEquals(r.totals.unique_users, 12300, "nearest 100");
  assertEquals(r.airlines.most_delayed.map((a) => a.carrier), ["SG"], "rows under 200 legs dropped");
  assertEquals(r.airports.most_punctual, [], "IXC (150 legs) dropped");
  assertEquals(r.busiest_routes, [{
    dep: "DEL",
    arr: "BOM",
    legs: 4000,
    dep_lat: 28.56,
    dep_lon: 77.1,
    arr_lat: 19.09,
    arr_lon: 72.87,
  }], "whitelisted fields only");
  assertEquals(Object.keys(r.airlines.most_punctual[0]).sort(), [
    "avg_dep_delay",
    "carrier",
    "legs",
    "name",
    "on_time_pct",
  ]);
  assertEquals(r.fog, { airport: "DEL", fog_days: 21, fog_delay_days: 14 });
  assertEquals(bucketUsers(999), null);
  assertEquals(bucketUsers(1000), 1000);
  assertEquals(bucketUsers(1049), 1000);
  assertEquals(bucketUsers(1050), 1100);
  assertEquals(bucketUsers(null), null);
  assertEquals(REPORT_MIN_LEGS, 200);
  const small = toPublicGlobalReport({ ...RAW_REPORT, totals: { ...RAW_REPORT.totals, unique_users: 640 } }, 2026);
  assertEquals(small.totals.unique_users, null);
});

Deno.test("global report: GET ?year (default this year in India), 1-hour cache, validation", async () => {
  let t = Date.parse("2026-12-31T19:00:00Z"); // already 1 Jan 2027 in India
  const years: string[] = [];
  const deps = {
    lookup: (y: string) => {
      years.push(y);
      return Promise.resolve({ ...RAW_REPORT, year: Number(y) });
    },
    now: () => new Date(t),
    cache: new TtlCache<PublicGlobalReport | null>(3_600_000),
  };
  const get = (q: string) => handleGlobalReport(new Request(`https://x/global-report${q}`), deps);
  assertEquals(currentIndianYear(new Date(t)), 2027);
  const res = await get("");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("cache-control"), "public, max-age=3600");
  assertEquals((await res.json()).report.year, 2027);
  await (await get("?year=2027")).body?.cancel();
  assertEquals(years, ["2027"], "cached for the hour");
  t += 3_600_000;
  await (await get("?year=2027")).body?.cancel();
  assertEquals(years, ["2027", "2027"]);
  assertEquals((await get("?year=2023")).status, 400);
  assertEquals((await get("?year=2028")).status, 400);
  assertEquals((await get("?year=20x6")).status, 400);
});

// ================================================================================================
// B13: Pro via a family in sync-entitlement
// ================================================================================================

Deno.test("family: applyFamilyPro", () => {
  const now = new Date(NOW);
  const free: EntitlementResponse = { plan: "free", pro_until: null, trial_flight_id: null };
  const own: EntitlementResponse = { plan: "pro", pro_until: "2027-01-01T00:00:00.000Z", trial_flight_id: null };
  const fam = { owner_id: "o", owner_name: "Owen", pro_until: "2027-06-01T00:00:00+00:00" };
  assertEquals(applyFamilyPro(free, fam, now), {
    plan: "pro",
    pro_until: "2027-06-01T00:00:00.000Z",
    trial_flight_id: null,
    via_family: { owner_name: "Owen" },
  });
  assertEquals(applyFamilyPro(own, fam, now), { ...own, via_family: null }, "own Pro wins");
  assertEquals(applyFamilyPro(free, null, now), { ...free, via_family: null });
  assertEquals(applyFamilyPro(free, { ...fam, pro_until: null }, now).pro_until, null, "lifetime owner");
  assertEquals(applyFamilyPro(free, { ...fam, pro_until: "2026-01-01T00:00:00Z" }, now).plan, "free", "lapsed owner");
  assertEquals(applyFamilyPro(free, { ...fam, owner_name: null }, now).via_family, { owner_name: null });
});
