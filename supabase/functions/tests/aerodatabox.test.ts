import { assertEquals, assertRejects } from "@std/assert";
import { adbUtc, AeroDataBoxProvider, mapAdbFlight } from "../_shared/providers/aerodatabox.ts";
import { ProviderError } from "../_shared/providers/provider.ts";

// Shape per AeroDataBox OpenAPI (values illustrative).
const ENROUTE = {
  number: "6E 2341", callSign: "IGO2341", status: "EnRoute", codeshareStatus: "IsOperator", isCargo: false,
  lastUpdatedUtc: "2026-11-25 13:40Z",
  departure: {
    airport: { icao: "VIDP", iata: "DEL", name: "Delhi", timeZone: "Asia/Kolkata" },
    scheduledTime: { utc: "2026-11-25 13:20Z", local: "2026-11-25 18:50+05:30" },
    revisedTime: { utc: "2026-11-25 13:31Z", local: "2026-11-25 19:01+05:30" },
    runwayTime: { utc: "2026-11-25 13:44Z", local: "2026-11-25 19:14+05:30" },
    terminal: "1", gate: "42B", quality: ["Basic", "Live"],
  },
  arrival: {
    airport: { icao: "VABB", iata: "BOM", name: "Mumbai", timeZone: "Asia/Kolkata" },
    scheduledTime: { utc: "2026-11-25 15:30Z", local: "2026-11-25 21:00+05:30" },
    predictedTime: { utc: "2026-11-25 15:41Z", local: "2026-11-25 21:11+05:30" },
    terminal: "2", quality: ["Basic"],
  },
  aircraft: { reg: "VT-ISR", modeS: "800C8A", model: "Airbus A320 NEO" },
  airline: { name: "IndiGo", iata: "6E", icao: "IGO" },
  location: { lat: 25.1, lon: 75.2, altitude: { feet: 34975 }, groundSpeed: { kt: 452 }, trueTrack: { deg: 212 }, reportedAtUtc: "2026-11-25 14:10Z" },
};

Deno.test("adbUtc handles AeroDataBox time format", () => {
  assertEquals(adbUtc({ utc: "2026-11-25 13:20Z" }), "2026-11-25T13:20:00Z");
  assertEquals(adbUtc({ utc: "garbage" }), null);
  assertEquals(adbUtc(undefined), null);
});

Deno.test("maps an en-route flight: actual dep = runway time, ETA from predicted, live position", () => {
  const s = mapAdbFlight(ENROUTE)!;
  assertEquals([s.carrier, s.number, s.date, s.depIata, s.arrIata], ["6E", "2341", "2026-11-25", "DEL", "BOM"]);
  assertEquals(s.status, "en_route");
  assertEquals(s.actDep, "2026-11-25T13:44:00Z");
  assertEquals(s.estDep, null);
  assertEquals(s.estArr, "2026-11-25T15:41:00Z");
  assertEquals(s.depGate, "42B");
  assertEquals(s.aircraftReg, "VT-ISR");
  assertEquals(s.live?.altFt, 34975);
  assertEquals(s.live?.speedKts, 452);
});

Deno.test("status mapping: Canceled, CanceledUncertain (no false cancel), Arrived with belt", () => {
  assertEquals(mapAdbFlight({ ...ENROUTE, status: "Canceled" })!.status, "cancelled");
  assertEquals(mapAdbFlight({ ...ENROUTE, status: "CanceledUncertain" })!.status, "unknown");
  const arrived = mapAdbFlight({
    ...ENROUTE, status: "Arrived",
    arrival: { ...ENROUTE.arrival, runwayTime: { utc: "2026-11-25 15:39Z" }, baggageBelt: " 7 " },
  })!;
  assertEquals(arrived.status, "arrived");
  assertEquals(arrived.actArr, "2026-11-25T15:39:00Z");
  assertEquals(arrived.baggageBelt, "7");
  assertEquals(arrived.estArr, null);
});

Deno.test("expected flight: revised time is the estimate; leading zeros stripped", () => {
  const s = mapAdbFlight({
    ...ENROUTE, number: "AI 0101", status: "Expected", airline: { iata: "AI", name: "Air India" },
    departure: { ...ENROUTE.departure, runwayTime: undefined },
  })!;
  assertEquals(s.number, "101");
  assertEquals(s.estDep, "2026-11-25T13:31:00Z");
  assertEquals(s.actDep, null);
});

function fakeFetch(status: number, body: unknown, seen: Request[] = []) {
  return ((input: string | URL | Request, init?: RequestInit) => {
    seen.push(new Request(input, init));
    return Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body), { status }));
  }) as typeof fetch;
}
const cfg = { apiKey: "k", baseUrl: "https://adb.test", keyHeader: "X-RapidAPI-Key", hostHeader: "aerodatabox.p.rapidapi.com", webhooks: false };

Deno.test("provider: request path/headers, codeshare dedupe, depIata filter, 204 = not found", async () => {
  const seen: Request[] = [];
  const codeshare = { ...ENROUTE, number: "AI 9876", codeshareStatus: "IsCodeshared", airline: { iata: "AI" } };
  const p = new AeroDataBoxProvider(cfg, fakeFetch(200, [ENROUTE, codeshare], seen));
  const r = await p.getFlight({ carrier: "6E", number: "2341", date: "2026-11-25" });
  assertEquals(r.length, 1);
  assertEquals(new URL(seen[0].url).pathname, "/flights/number/6E2341/2026-11-25");
  assertEquals(seen[0].headers.get("x-rapidapi-key"), "k");
  assertEquals((await p.getFlight({ carrier: "6E", number: "2341", date: "2026-11-25", depIata: "BOM" })).length, 0);
  assertEquals(await new AeroDataBoxProvider(cfg, fakeFetch(204, null)).getFlight({ carrier: "6E", number: "1", date: "2026-11-25" }), []);
});

Deno.test("provider: 429 is retryable, 401 is not", async () => {
  const e1 = await assertRejects(() => new AeroDataBoxProvider(cfg, fakeFetch(429, {})).getFlight({ carrier: "6E", number: "1", date: "2026-11-25" }), ProviderError);
  assertEquals(e1.retryable, true);
  const e2 = await assertRejects(() => new AeroDataBoxProvider(cfg, fakeFetch(401, {})).getFlight({ carrier: "6E", number: "1", date: "2026-11-25" }), ProviderError);
  assertEquals(e2.retryable, false);
});

Deno.test("webhook payload {flights, subscription, balance} parses to snapshots", () => {
  const p = new AeroDataBoxProvider(cfg, fakeFetch(200, []));
  const snaps = p.parseWebhook({ flights: [ENROUTE], subscription: { id: "s1" }, balance: { creditsRemaining: 10 } });
  assertEquals(snaps.length, 1);
  assertEquals(snaps[0].depGate, "42B");
  assertEquals(p.parseWebhook({}), []);
});
