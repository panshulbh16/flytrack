// P3 provider additions (docs/P3_CONTRACTS.md A2, A5, B3, B5): block times from the mock and AeroDataBox,
// airport departures, aircraft lookup, the aircraft row, and taxi minutes in leg history.
import { assert, assertEquals, assertRejects, assertThrows } from "@std/assert";
import {
  AeroDataBoxProvider,
  ageYearsSince,
  departureWindows,
  mapAdbAircraft,
  mapAdbFlight,
} from "../_shared/providers/aerodatabox.ts";
import { MOCK_CARRIERS, MOCK_TAXI_IN, MOCK_TAXI_OUT, MockProvider } from "../_shared/providers/mock.ts";
import { ProviderError } from "../_shared/providers/provider.ts";
import { aircraftRow, aircraftStale, normalizeReg } from "../_shared/aircraft.ts";
import { legHistoryRow, taxiMinutes } from "../_shared/history.ts";
import { runPollCycle, searchFlight } from "../_shared/engine.ts";
import type { FlightRow } from "../_shared/types.ts";
import { makeDeps, runUntil } from "./helpers.ts";

const MIN = 60_000;
const DATE = "2026-11-25";

function fakeFetch(handler: (url: URL) => { status: number; body: unknown }, seen: URL[] = []) {
  return ((input: string | URL | Request) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    seen.push(url);
    const { status, body } = handler(url);
    return Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body), { status }));
  }) as typeof fetch;
}
const cfg = { apiKey: "k", baseUrl: "https://adb.test", keyHeader: "X-RapidAPI-Key", hostHeader: null, webhooks: false };

const adb = (over: Record<string, unknown> = {}) => ({
  number: "6E 2341", status: "Expected", codeshareStatus: "IsOperator", isCargo: false,
  departure: {
    airport: { iata: "DEL" },
    scheduledTime: { utc: "2026-11-25 13:20Z", local: "2026-11-25 18:50+05:30" },
    revisedTime: { utc: "2026-11-25 13:31Z", local: "2026-11-25 19:01+05:30" },
  },
  arrival: {
    airport: { iata: "BOM" },
    scheduledTime: { utc: "2026-11-25 15:30Z", local: "2026-11-25 21:00+05:30" },
    revisedTime: { utc: "2026-11-25 15:45Z", local: "2026-11-25 21:15+05:30" },
  },
  airline: { iata: "6E", name: "IndiGo" },
  ...over,
});

// ---------------------------------------------------------------- block times

Deno.test("ADB block times: off-block = revised once departed, runway times, on-block = revised once Arrived", () => {
  const before = mapAdbFlight(adb())!;
  assertEquals([before.depOffblockAt, before.depRunwayAt, before.arrRunwayAt, before.arrOnblockAt], [null, null, null, null]);

  const base = adb();
  const enRoute = mapAdbFlight({
    ...base, status: "EnRoute",
    departure: { ...base.departure, runwayTime: { utc: "2026-11-25 13:44Z" } },
  })!;
  assertEquals(enRoute.depOffblockAt, "2026-11-25T13:31:00Z");
  assertEquals(enRoute.depRunwayAt, "2026-11-25T13:44:00Z");
  assertEquals(enRoute.actDep, "2026-11-25T13:44:00Z", "act_dep keeps its meaning (runway first)");
  assertEquals(enRoute.arrRunwayAt, null);
  assertEquals(enRoute.arrOnblockAt, null, "the arrival revised time is an estimate until Arrived");

  const landed = mapAdbFlight({
    ...base, status: "Approaching",
    departure: { ...base.departure, runwayTime: { utc: "2026-11-25 13:44Z" } },
    arrival: { ...base.arrival, runwayTime: { utc: "2026-11-25 15:39Z" } },
  })!;
  assertEquals([landed.status, landed.arrRunwayAt, landed.arrOnblockAt], ["landed", "2026-11-25T15:39:00Z", null]);

  const arrived = mapAdbFlight({
    ...base, status: "Arrived",
    departure: { ...base.departure, runwayTime: { utc: "2026-11-25 13:44Z" } },
    arrival: { ...base.arrival, runwayTime: { utc: "2026-11-25 15:39Z" } },
  })!;
  assertEquals(arrived.arrRunwayAt, "2026-11-25T15:39:00Z");
  assertEquals(arrived.arrOnblockAt, "2026-11-25T15:45:00Z");
  assertEquals(arrived.actArr, "2026-11-25T15:39:00Z");
});

Deno.test("mock block times follow the clock: pushback, takeoff after taxi-out, touchdown, on-block", async () => {
  const n = 2341; // scenario 1: on time
  const p0 = new MockProvider(() => new Date(0));
  const sched = Date.parse((await p0.getFlight({ carrier: "6E", number: String(n), date: DATE }))[0].schedDep!);
  const at = async (offsetMin: number) =>
    (await new MockProvider(() => new Date(sched + offsetMin * MIN)).getFlight({ carrier: "6E", number: String(n), date: DATE }))[0];
  const iso = (offsetMin: number) => new Date(sched + offsetMin * MIN).toISOString().replace(".000Z", "Z");

  const gate = await at(-10);
  assertEquals(gate.status, "gate_closed");
  assertEquals([gate.depOffblockAt, gate.depRunwayAt], [null, null]);

  const taxi = await at(3);
  assertEquals(taxi.depOffblockAt, iso(2));
  assertEquals(taxi.depOffblockAt, taxi.actDep);
  assertEquals(taxi.depRunwayAt, null, "still taxiing");

  const up = await at(2 + MOCK_TAXI_OUT(n));
  assertEquals(up.depRunwayAt, iso(2 + MOCK_TAXI_OUT(n)));
  assertEquals(MOCK_TAXI_OUT(n), 11);

  const dur = (Date.parse(up.schedArr!) - sched) / MIN;
  const down = await at(dur + 3);
  assertEquals(down.arrRunwayAt, down.actArr);
  assertEquals(down.arrOnblockAt, null);
  const parked = await at(dur + MOCK_TAXI_IN + 1);
  assertEquals(parked.status, "arrived");
  assertEquals(parked.arrOnblockAt, new Date(Date.parse(parked.arrRunwayAt!) + MOCK_TAXI_IN * MIN).toISOString().replace(".000Z", "Z"));

  const late = () => new Date("2026-11-25T18:00:00Z"); // after 6E 2346 was cancelled
  const cancelled = (await new MockProvider(late).getFlight({ carrier: "6E", number: "2346", date: DATE }))[0];
  assertEquals(cancelled.status, "cancelled");
  assertEquals([cancelled.depOffblockAt, cancelled.depRunwayAt, cancelled.arrRunwayAt, cancelled.arrOnblockAt], [null, null, null, null]);
});

Deno.test("history: taxi minutes from the block times; implausible values are dropped", async () => {
  assertEquals(taxiMinutes("2026-11-25T10:00:00Z", "2026-11-25T10:17:20Z"), 17);
  assertEquals(taxiMinutes("2026-11-25T10:00:00Z", "2026-11-25T09:59:00Z"), null);
  assertEquals(taxiMinutes("2026-11-25T10:00:00Z", "2026-11-25T14:00:00Z"), null);
  assertEquals(taxiMinutes(null, "2026-11-25T10:00:00Z"), null);

  // a whole mock journey: the finished leg carries the mock's taxi times
  const h = makeDeps("2026-11-21T00:00:00Z");
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2342", date: DATE });
  h.repo.track(row.id, { userId: "u", isPro: false, prefs: {}, devices: [] });
  await runUntil(h, "2026-11-26T06:00:00Z", 1, (d) => runPollCycle(d, 5_000));
  const leg = h.repo.legHistory.get(row.id)!;
  assertEquals(leg.taxi_out_min, MOCK_TAXI_OUT(2342));
  assertEquals(leg.taxi_in_min, MOCK_TAXI_IN);
  const final = (await h.repo.getFlight(row.id))!;
  assert(final.dep_offblock_at && final.dep_runway_at && final.arr_runway_at && final.arr_onblock_at, JSON.stringify(final));

  const r = legHistoryRow({ ...final, dep_runway_at: null } as FlightRow, new Date());
  assertEquals(r.taxi_out_min, null);
});

// ---------------------------------------------------------------- airport departures

Deno.test("departureWindows: 12-hour windows, inclusive, validated", () => {
  assertEquals(departureWindows("2026-11-28T00:00", "2026-11-28T23:59"), [
    ["2026-11-28T00:00", "2026-11-28T11:59"],
    ["2026-11-28T12:00", "2026-11-28T23:59"],
  ]);
  assertEquals(departureWindows("2026-11-28T06:00", "2026-11-28T09:00"), [["2026-11-28T06:00", "2026-11-28T09:00"]]);
  assertThrows(() => departureWindows("2026-11-28T10:00", "2026-11-28T09:00"), ProviderError);
  assertThrows(() => departureWindows("2026-11-28", "2026-11-28T09:00"), ProviderError);
});

Deno.test("ADB getAirportDepartures: FIDS path per window, codeshares and cargo dropped, implied airport, sorted", async () => {
  const seen: URL[] = [];
  const late = adb({
    number: "AI 805", airline: { iata: "AI" },
    departure: { scheduledTime: { utc: "2026-11-28 14:00Z", local: "2026-11-28 19:30+05:30" } }, // no airport: implied
    arrival: { airport: { iata: "BLR" }, scheduledTime: { utc: "2026-11-28 16:45Z", local: "2026-11-28 22:15+05:30" } },
  });
  const early = adb({
    departure: { airport: { iata: "DEL" }, scheduledTime: { utc: "2026-11-28 01:00Z", local: "2026-11-28 06:30+05:30" } },
  });
  const codeshare = { ...early, number: "UK 9001", codeshareStatus: "IsCodeshared", airline: { iata: "UK" } };
  const cargo = { ...early, number: "6E 9", isCargo: true };
  const p = new AeroDataBoxProvider(
    cfg,
    fakeFetch((u) => ({
      status: 200,
      body: { departures: u.pathname.includes("T00:00") ? [early, codeshare, cargo] : [late, early], arrivals: [] },
    }), seen),
  );
  const r = await p.getAirportDepartures("del", "2026-11-28T00:00", "2026-11-28T23:59");
  assertEquals(seen.map((u) => u.pathname), [
    "/flights/airports/iata/DEL/2026-11-28T00:00/2026-11-28T11:59",
    "/flights/airports/iata/DEL/2026-11-28T12:00/2026-11-28T23:59",
  ]);
  assertEquals(seen[0].searchParams.get("withLeg"), "true");
  assertEquals(seen[0].searchParams.get("direction"), "Departure");
  assertEquals(seen[0].searchParams.get("withCodeshared"), "false");
  assertEquals(r.map((s) => `${s.carrier}${s.number} ${s.depIata}→${s.arrIata}`), ["6E2341 DEL→BOM", "AI805 DEL→BLR"],
    "duplicates across windows merged, codeshare + cargo removed, ordered by departure");
  await assertRejects(() => p.getAirportDepartures("DELHI", "2026-11-28T00:00", "2026-11-28T01:00"), ProviderError);
  const empty = new AeroDataBoxProvider(cfg, fakeFetch(() => ({ status: 204, body: null })));
  assertEquals(await empty.getAirportDepartures("DEL", "2026-11-28T00:00", "2026-11-28T01:00"), []);
});

Deno.test("mock getAirportDepartures: departures in the local window, consistent with getFlight", async () => {
  const p = new MockProvider(() => new Date("2026-11-26T00:00:00Z"));
  const r = await p.getAirportDepartures("DEL", "2026-11-28T00:00", "2026-11-28T23:59");
  assert(r.length > 10, `${r.length} departures`);
  assert(r.every((s) => s.depIata === "DEL" && s.date === "2026-11-28"));
  for (let i = 1; i < r.length; i++) assert(Date.parse(r[i - 1].schedDep!) <= Date.parse(r[i].schedDep!), "sorted");
  assert(new Set(r.map((s) => s.carrier)).size > 1, "several airlines");
  assert(r.every((s) => (MOCK_CARRIERS as readonly string[]).includes(s.carrier)));
  const one = r[3];
  const [same] = await p.getFlight({ carrier: one.carrier, number: one.number, date: one.date });
  assertEquals(same.schedDep, one.schedDep);
  assertEquals(same.arrIata, one.arrIata);

  const morning = await p.getAirportDepartures("DEL", "2026-11-28T06:00", "2026-11-28T08:59");
  const ist = (iso: string) => new Date(Date.parse(iso) + 330 * MIN).toISOString().slice(11, 16);
  assert(morning.length > 0 && morning.every((s) => ist(s.schedDep!) >= "06:00" && ist(s.schedDep!) <= "08:59"));
  assertEquals(await p.getAirportDepartures("XXX", "2026-11-28T00:00", "2026-11-28T23:59"), []);
  await assertRejects(() => p.getAirportDepartures("DEL", "2026-11-28T09:00", "2026-11-28T08:00"), ProviderError);
});

// ---------------------------------------------------------------- aircraft

Deno.test("ADB aircraft: mapping, age from the first flight when ADB sends none, 404 = unknown tail", async () => {
  const now = new Date("2026-11-26T00:00:00Z");
  assertEquals(
    mapAdbAircraft({
      reg: "VT-ISR", typeName: "Airbus A320neo", icaoCode: "a20n", firstFlightDate: "2018-05-02T00:00:00",
      deliveryDate: "2018-06-01", numSeats: 186, ageYears: 8.47, airline: { iata: "6e" },
    }, "VT-ISR", now),
    {
      reg: "VT-ISR", model: "Airbus A320neo", icaoType: "A20N", firstFlightDate: "2018-05-02", deliveryDate: "2018-06-01",
      ageYears: 8.5, airlineIata: "6E", seatConfig: "186 seats",
    },
  );
  assertEquals(mapAdbAircraft({ model: "A321", firstFlightDate: "2016-11-26" }, "vt-abc", now).ageYears, 10);
  assertEquals(ageYearsSince("2030-01-01", now), null, "future dates are no age");

  const seen: URL[] = [];
  const p = new AeroDataBoxProvider(cfg, fakeFetch((u) => ({ status: u.pathname.endsWith("VT-NONE") ? 404 : 200, body: { reg: "VT-ISR", icaoCode: "A20N" } }), seen));
  assertEquals((await p.getAircraft("vt-isr"))?.icaoType, "A20N");
  assertEquals(seen[0].pathname, "/aircrafts/reg/VT-ISR");
  assertEquals(await p.getAircraft("VT-NONE"), null);
  assertEquals(await p.getAircraft("not a reg!"), null);
  assertEquals(seen.length, 2, "an invalid registration costs no call");
});

Deno.test("mock aircraft: the fixed aircraft, aged against the mock clock", async () => {
  const p = new MockProvider(() => new Date("2026-03-14T00:00:00Z"));
  const a = (await p.getAircraft("vt-m2341"))!;
  assertEquals(a.reg, "VT-M2341");
  assertEquals([a.model, a.icaoType, a.ageYears], ["Airbus A320neo", "A20N", 8]);
});

Deno.test("aircraft row: normalized, table checks respected, unknown tail stored as nulls, 30-day TTL", () => {
  const now = new Date("2026-11-26T00:00:00Z");
  assertEquals(normalizeReg(" vt-isr "), "VT-ISR");
  assertEquals(normalizeReg("VT ISR"), null);
  const row = aircraftRow("VT-ISR", {
    reg: "VT-ISR", model: " Airbus A320neo ", icaoType: "A320NEO", firstFlightDate: "2018-13-40", deliveryDate: "2018-06-01",
    ageYears: 140, airlineIata: "IGO", seatConfig: null,
  }, now);
  assertEquals(row, {
    reg: "VT-ISR", model: "Airbus A320neo", icao_type: null, first_flight_date: null, delivery_date: "2018-06-01",
    age_years: null, airline_iata: null, seat_config: null, fetched_at: now.toISOString(),
  });
  assertEquals(aircraftRow("VT-XX", null, now).model, null);
  assert(aircraftStale(null, now));
  assert(!aircraftStale("2026-11-01T00:00:00Z", now));
  assert(aircraftStale("2026-10-27T00:00:00Z", now));
});
