import { assert, assertEquals } from "@std/assert";
import { type AircraftPosition, type FeedChoice, FeedError, type PositionFeed } from "../_shared/positions.ts";
import {
  cacheKey,
  type FlightCandidate,
  handleRadar,
  matchFlight,
  parseCallsign,
  parseRadarQuery,
  RadarCache,
  type RadarDeps,
  type RadarResponse,
} from "../radar/handler.ts";

const NOW = Date.parse("2026-11-27T06:00:00Z");
const U = "11111111-2222-4333-8444-555555555555";

function ac(over: Partial<AircraftPosition>): AircraftPosition {
  return {
    icao24: "800c1b",
    callsign: "IGO2341",
    lat: 28.556,
    lon: 77.1,
    alt_ft: 0,
    gs_kts: 5,
    track_deg: 90,
    on_ground: true,
    seen_at: new Date(NOW).toISOString(),
    ...over,
  };
}

function flight(over: Partial<FlightCandidate>): FlightCandidate {
  return {
    carrier_iata: "6E",
    carrier_icao: "IGO",
    flight_number: "2341",
    flight_date: "2026-11-27",
    status: "scheduled",
    sched_dep: "2026-11-27T06:30:00Z",
    ...over,
  };
}

function harness(over: Partial<RadarDeps> = {}, aircraft: AircraftPosition[] = []) {
  const calls: { lat: number; lon: number; r: number }[] = [];
  const quota: string[] = [];
  const asked: string[][] = [];
  let t = NOW;
  const feed: PositionFeed = {
    name: "opensky",
    nearby(lat, lon, r) {
      calls.push({ lat, lon, r });
      return Promise.resolve(aircraft);
    },
  };
  const logs: string[] = [];
  const deps: RadarDeps = {
    authUser: (a) => Promise.resolve(a === "Bearer good" ? U : null),
    isPro: () => Promise.resolve(true),
    takeQuota: (u) => {
      quota.push(u);
      return Promise.resolve(quota.length <= 60);
    },
    airport: (iata) => Promise.resolve(iata === "DEL" ? { lat: 28.5562, lon: 77.1 } : null),
    feed: (): FeedChoice => ({ ok: true, feed }),
    flightCandidates: (nums) => {
      asked.push(nums);
      return Promise.resolve([flight({}), flight({ carrier_iata: "AI", carrier_icao: null, flight_number: "101" })]);
    },
    cache: new RadarCache(),
    now: () => new Date(t),
    log: (m) => logs.push(m),
    ...over,
  };
  return { deps, calls, quota, asked, logs, advance: (ms: number) => (t += ms) };
}

const run = (deps: RadarDeps, qs: string, auth = "Bearer good", method = "GET") =>
  handleRadar(deps, method, new URL(`https://x.test/functions/v1/radar?${qs}`), auth);

// ---------- parsing ----------

Deno.test("radar: query parsing rounds proximity coordinates to 0.05°", () => {
  assertEquals(parseRadarQuery(new URL("https://x/?mode=ground&iata=del")), { mode: "ground", iata: "DEL" });
  assertEquals(parseRadarQuery(new URL("https://x/?mode=proximity&lat=28.6139&lon=77.2090")), {
    mode: "proximity",
    lat: 28.6,
    lon: 77.2,
  });
  assertEquals(parseRadarQuery(new URL("https://x/?mode=proximity&lat=28.63&lon=77.23")), {
    mode: "proximity",
    lat: 28.65,
    lon: 77.25,
  });
  for (
    const bad of [
      "mode=ground",
      "mode=ground&iata=DELH",
      "mode=proximity&lat=91&lon=0",
      "mode=proximity&lat=1",
      "mode=proximity&lat=x&lon=1",
      "mode=air&lat=1&lon=1",
      "",
    ]
  ) assertEquals(parseRadarQuery(new URL(`https://x/?${bad}`)), null, bad);
  assertEquals(cacheKey({ mode: "proximity", lat: 28.6, lon: 77.25 }), "proximity:28.60:77.25");
});

Deno.test("radar: callsign parsing and flight matching", () => {
  assertEquals(parseCallsign("IGO2341"), { icao: "IGO", number: "2341" });
  assertEquals(parseCallsign("aic0101"), { icao: "AIC", number: "101" });
  assertEquals(parseCallsign("AXB12A"), { icao: "AXB", number: "12A" });
  assertEquals(parseCallsign("VTABC"), null); // a registration used as callsign
  assertEquals(parseCallsign("IGO0000"), null);
  const now = new Date(NOW);
  // carrier_icao match
  assertEquals(matchFlight("IGO2341", [flight({})], now), "6E2341");
  // IATA via table when carrier_icao is missing
  assertEquals(
    matchFlight("AIC101", [flight({ carrier_iata: "AI", carrier_icao: null, flight_number: "101" })], now),
    "AI101",
  );
  // other airline with the same number does not match
  assertEquals(matchFlight("SEJ2341", [flight({})], now), null);
  // airborne beats scheduled; finished only as a last resort
  const legs = [
    flight({ flight_date: "2026-11-26", status: "arrived", sched_dep: "2026-11-26T06:00:00Z", carrier_iata: "6E" }),
    flight({ status: "en_route", sched_dep: "2026-11-27T03:00:00Z", flight_number: "2341" }),
    flight({ status: "scheduled", sched_dep: "2026-11-27T06:05:00Z" }),
  ];
  assertEquals(matchFlight("IGO2341", legs, now), "6E2341");
  assertEquals(
    matchFlight("IGO2341", [legs[0], { ...legs[2], carrier_iata: "6E", flight_number: "2341" }], now),
    "6E2341",
  );
  assertEquals(matchFlight("", [flight({})], now), null);
});

// ---------- handler ----------

Deno.test("radar: auth, input, Pro, feed and quota errors in contract order", async () => {
  assertEquals((await run(harness().deps, "mode=ground&iata=DEL", "Bearer bad")).status, 401);
  assertEquals((await run(harness().deps, "mode=ground&iata=DEL", "Bearer good", "POST")).status, 400);
  assertEquals((await run(harness().deps, "mode=ground")).body, {
    error: "bad_input",
    message: "use ?mode=ground&iata=DEL or ?mode=proximity&lat=..&lon=..",
  });
  const free = await run(harness({ isPro: () => Promise.resolve(false) }).deps, "mode=ground&iata=DEL");
  assertEquals([free.status, free.body], [402, { error: "pro_required" }]);

  const h = harness({ feed: () => ({ ok: false, error: "feed_unavailable" }) });
  const un = await run(h.deps, "mode=ground&iata=DEL");
  assertEquals([un.status, un.body], [503, { error: "feed_unavailable" }]);
  assertEquals(h.quota.length, 0, "no quota spent when there is no feed");
  const lic = await run(
    harness({ feed: () => ({ ok: false, error: "feed_unlicensed" }) }).deps,
    "mode=ground&iata=DEL",
  );
  assertEquals([lic.status, lic.body], [503, { error: "feed_unlicensed" }]);

  const unknown = await run(harness().deps, "mode=ground&iata=XYZ");
  assertEquals(unknown.status, 404);
});

Deno.test("radar: ground mode keeps only aircraft on the ground and labels flights", async () => {
  const h = harness({}, [
    ac({}),
    ac({ icao24: "8015d3", callsign: "AIC101", on_ground: false, alt_ft: 5000 }),
    ac({ icao24: "800b8a", callsign: "" }),
  ]);
  const res = await run(h.deps, "mode=ground&iata=DEL");
  assertEquals(res.status, 200);
  const body = res.body as RadarResponse;
  assertEquals(body.center, { lat: 28.5562, lon: 77.1 });
  assertEquals(body.radius_km, 5);
  assertEquals(body.feed, "opensky");
  assertEquals(body.as_of, new Date(NOW).toISOString());
  assertEquals(body.aircraft.map((a) => [a.icao24, a.flight]), [["800c1b", "6E2341"], ["800b8a", undefined]]);
  assert(!("flight" in body.aircraft[1]));
  assertEquals(h.calls, [{ lat: 28.5562, lon: 77.1, r: 5 }]);
  assertEquals(h.asked, [["2341"]]);
});

Deno.test("radar: proximity uses the rounded cell, 75 km, and a 10 s cache per cell", async () => {
  const h = harness({}, [ac({ on_ground: false, callsign: "AIC101" })]);
  const a = await run(h.deps, "mode=proximity&lat=28.6139&lon=77.2090");
  const body = a.body as RadarResponse;
  assertEquals(body.center, { lat: 28.6, lon: 77.2 });
  assertEquals(body.radius_km, 75);
  assertEquals(body.aircraft[0].flight, "AI101");
  assertEquals(h.calls, [{ lat: 28.6, lon: 77.2, r: 75 }]);

  // same cell → cached (still counts against the quota)
  await run(h.deps, "mode=proximity&lat=28.61&lon=77.19");
  assertEquals(h.calls.length, 1);
  assertEquals(h.quota.length, 2);
  // another cell → new call
  await run(h.deps, "mode=proximity&lat=28.7&lon=77.2");
  assertEquals(h.calls.length, 2);
  // after 10 s → refreshed
  h.advance(10_000);
  await run(h.deps, "mode=proximity&lat=28.6&lon=77.2");
  assertEquals(h.calls.length, 3);
});

Deno.test("radar: concurrent misses share one feed call; failures are not cached", async () => {
  let n = 0;
  let fail = true;
  const feed: PositionFeed = {
    name: "adsbx",
    async nearby() {
      n++;
      await new Promise((r) => setTimeout(r, 5));
      if (fail) throw new FeedError("feed_error", "HTTP 500", 500);
      return [];
    },
  };
  const h = harness({ feed: () => ({ ok: true, feed }) });
  const [r1, r2] = await Promise.all([run(h.deps, "mode=ground&iata=DEL"), run(h.deps, "mode=ground&iata=DEL")]);
  assertEquals(n, 1);
  assertEquals([r1.status, r1.body, r2.status], [502, { error: "feed_error" }, 502]);
  assert(h.logs.includes("radar: feed failed"));
  fail = false;
  const ok = await run(h.deps, "mode=ground&iata=DEL");
  assertEquals(ok.status, 200);
  assertEquals(n, 2);
});

Deno.test("radar: the 61st request in the window is rate_limited", async () => {
  const h = harness({}, []);
  for (let i = 0; i < 60; i++) assertEquals((await run(h.deps, "mode=ground&iata=DEL")).status, 200);
  const r = await run(h.deps, "mode=ground&iata=DEL");
  assertEquals([r.status, r.body], [429, { error: "rate_limited" }]);
});

Deno.test("radar: a flight-match failure still returns positions; cache evicts beyond its size", async () => {
  const h = harness({ flightCandidates: () => Promise.reject(new Error("db down")) }, [ac({})]);
  const r = await run(h.deps, "mode=ground&iata=DEL");
  assertEquals(r.status, 200);
  assertEquals((r.body as RadarResponse).aircraft[0].flight, undefined);
  assert(h.logs.includes("radar: flight match failed"));

  const c = new RadarCache(10_000, 3);
  const empty = () => Promise.resolve({} as RadarResponse);
  for (let i = 0; i < 5; i++) await c.getOrLoad(`k${i}`, NOW, empty);
  assertEquals(c.size, 3);
});
