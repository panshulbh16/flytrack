import { assert, assertEquals, assertRejects } from "@std/assert";
import {
  type AirportWeatherRow,
  BATCH_SIZE,
  ceilingFromRaw,
  fetchMetars,
  flightCategory,
  isFog,
  type MetarJson,
  metarBody,
  metarRequestUrl,
  normaliseMetar,
  refreshAirportWeather,
  USER_AGENT,
  visibilityFromJson,
  visibilityFromRaw,
  type WeatherTarget,
  windFromRaw,
  wxFromRaw,
} from "../_shared/weather.ts";
import fixture from "./fixtures/metar-india-fog.json" with { type: "json" };

const FIXTURE = fixture as MetarJson[];
const FETCHED = new Date("2026-11-26T00:40:00Z");
const IATA: Record<string, string> = {
  VIDP: "DEL", VILK: "LKO", VIAR: "ATQ", VABB: "BOM", VOBL: "BLR", VOMM: "MAA", OMDB: "DXB", KJFK: "JFK",
};
const byIcao = (icao: string) => FIXTURE.find((m) => m.icaoId === icao)!;
const norm = (icao: string) => normaliseMetar(byIcao(icao), IATA[icao], FETCHED)!;

Deno.test("weather: Delhi dense fog (0050, RVR, FG, VV001) → 50 m, ceiling 100 ft, LIFR, fog", () => {
  const r = norm("VIDP");
  assertEquals(r, {
    iata: "DEL", icao: "VIDP",
    observed_at: "2026-11-26T00:30:00.000Z", fetched_at: "2026-11-26T00:40:00.000Z",
    raw_metar: "VIDP 260030Z 00000KT 0050 R28/0150N R29/0175N FG VV001 09/09 Q1019 NOSIG",
    visibility_m: 50, ceiling_ft: 100, wind_kts: 0, gust_kts: null, wx: "FG", flight_category: "LIFR", fog: true,
  });
});

Deno.test("weather: the task's own example METAR", () => {
  const raw = "VIDP 260030Z 00000KT 0050 R28/0150N FG VV001";
  assertEquals(visibilityFromRaw(raw), 50);
  assertEquals(ceilingFromRaw(raw), 100);
  assertEquals(wxFromRaw(raw), "FG");
  assertEquals(windFromRaw(raw), { wind: 0, gust: null });
});

Deno.test("weather: Lucknow shallow fog patches with mist; TEMPO trend is not current weather", () => {
  const r = norm("VILK");
  assertEquals(r.visibility_m, 400);
  assertEquals(r.wx, "BCFG BR");
  assertEquals(r.ceiling_ft, null); // NSC
  assertEquals(r.flight_category, "LIFR");
  assert(r.fog);
});

Deno.test("weather: Amritsar 800 m mist is fog by the visibility rule; BECMG 9999 ignored", () => {
  const r = norm("VIAR");
  assertEquals(r.visibility_m, 800);
  assertEquals(r.wx, "BR");
  assertEquals(r.ceiling_ft, null); // FEW is not a ceiling
  assertEquals(r.wind_kts, 3);
  assertEquals(r.flight_category, "LIFR");
  assert(r.fog);
});

Deno.test("weather: Mumbai haze 3000 m is IFR but not fog", () => {
  const r = norm("VABB");
  assertEquals([r.visibility_m, r.wx, r.flight_category, r.fog], [3000, "HZ", "IFR", false]);
});

Deno.test("weather: CAVOK → 9999, no ceiling, VFR", () => {
  const r = norm("VOBL");
  assertEquals([r.visibility_m, r.ceiling_ft, r.wind_kts, r.wx, r.flight_category, r.fog], [9999, null, 8, null, "VFR", false]);
});

Deno.test("weather: Chennai SPECI thunderstorm with gusts, BKN020 ceiling", () => {
  const r = norm("VOMM");
  assertEquals(r.visibility_m, 4000);
  assertEquals([r.wind_kts, r.gust_kts], [12, 25]);
  assertEquals(r.wx, "-TSRA");
  assertEquals(r.ceiling_ft, 2000);
  assertEquals(r.flight_category, "IFR"); // 4000 m = 2.5 SM
  assertEquals(r.fog, false);
});

Deno.test("weather: Dubai VRB wind; New York US-style 1 1/2SM and OVC004, remarks ignored", () => {
  const d = norm("OMDB");
  assertEquals([d.visibility_m, d.wind_kts, d.flight_category, d.observed_at], [9999, 2, "VFR", "2026-11-26T00:00:00.000Z"]);
  const j = norm("KJFK");
  assertEquals(j.visibility_m, 2414); // 1.5 × 1609.344
  assertEquals(j.ceiling_ft, 400);
  assertEquals(j.wx, "BR");
  assertEquals(j.flight_category, "LIFR");
  assertEquals(j.fog, false);
});

Deno.test("weather: visibility formats", () => {
  assertEquals(visibilityFromRaw("VIDP 260030Z 00000KT 9999 NSC"), 9999);
  assertEquals(visibilityFromRaw("VIDP 260030Z 00000KT 0000 FG"), 0);
  assertEquals(visibilityFromRaw("VILK 260030Z 00000KT 0400="), 400, "a trailing = still parses");
  assertEquals(wxFromRaw("VILK 260030Z 00000KT 0400 FG="), "FG");
  assertEquals(visibilityFromRaw("VIDP 260030Z 00000KT 1500NE BR"), 1500);
  assertEquals(visibilityFromRaw("KSFO 260056Z 28012KT 10SM FEW008"), 9999);
  assertEquals(visibilityFromRaw("KSFO 260056Z 28012KT P6SM FEW008"), 9999);
  assertEquals(visibilityFromRaw("KSFO 260056Z 28012KT 1/2SM FG"), 805);
  assertEquals(visibilityFromRaw("KSFO 260056Z 28012KT M1/4SM FG"), 402);
  assertEquals(visibilityFromRaw("KSFO 260056Z 28012KT 3SM BR"), 4828);
  assertEquals(visibilityFromRaw("VIDP 260030Z 00000KT"), null); // missing group
  assertEquals(visibilityFromRaw("VIDP 260030Z 00000KT //// FG"), null);
  assertEquals(visibilityFromJson("10+"), 9999);
  assertEquals(visibilityFromJson("6+"), 9999);
  assertEquals(visibilityFromJson(0.25), 402);
  assertEquals(visibilityFromJson("1/2"), 805);
  assertEquals(visibilityFromJson(null), null);
  assertEquals(visibilityFromJson("x"), null);
});

Deno.test("weather: wind in MPS converts to knots; trend and remark sections are cut", () => {
  assertEquals(windFromRaw("UUEE 260030Z 05004MPS 9999"), { wind: 8, gust: null });
  assertEquals(metarBody("VIDP 260030Z 00000KT 0800 BR TEMPO 0200 FG RMK X"), ["VIDP", "260030Z", "00000KT", "0800", "BR"]);
  assertEquals(metarBody("VILK 260030Z 00000KT 0400 FG VV001 11/10 Q1018="), [
    "VILK", "260030Z", "00000KT", "0400", "FG", "VV001", "11/10", "Q1018",
  ]);
  assertEquals(wxFromRaw("VIDP 260030Z 00000KT 0800 BR TEMPO 0200 FG"), "BR");
  assertEquals(wxFromRaw("VIDP 260030Z 00000KT 9999 NSC 20/10 Q1015 NOSIG"), null);
  assertEquals(wxFromRaw("VOMM 260030Z 04012KT 6000 VCTS FU"), "VCTS FU");
});

Deno.test("weather: categories and the fog rule", () => {
  assertEquals(flightCategory(9999, null), "VFR");
  assertEquals(flightCategory(9999, 3500), "VFR");
  assertEquals(flightCategory(9999, 3000), "MVFR");
  assertEquals(flightCategory(6000, null), "MVFR"); // 3.7 SM
  assertEquals(flightCategory(4000, null), "IFR");
  assertEquals(flightCategory(9999, 900), "IFR");
  assertEquals(flightCategory(1500, null), "LIFR"); // < 1 SM
  assertEquals(flightCategory(9999, 400), "LIFR");
  assertEquals(flightCategory(null, null), null);
  assert(isFog("FG", 9999));
  assert(isFog("BCFG BR", 2000));
  assert(isFog("HZ", 999));
  assert(!isFog("HZ", 1000));
  assert(!isFog(null, null));
  assert(!isFog("VCFG", 6000), "fog in the vicinity is not fog at the airport");
  assert(isFog("VCFG", 800), "but visibility below 1000 m still is");
  assert(isFog("MIFG", 5000));
});

Deno.test("weather: entries without a report or time are dropped; JSON fills gaps", () => {
  assertEquals(normaliseMetar({ icaoId: "VIDP", rawOb: "", obsTime: 1795653000 }, "DEL", FETCHED), null);
  assertEquals(normaliseMetar({ icaoId: "VIDP", rawOb: "VIDP 260030Z 00000KT 0050 FG" }, "DEL", FETCHED), null);
  const r = normaliseMetar({
    icaoId: "VIDP", reportTime: "2026-11-26 00:30:00", rawOb: "VIDP 260030Z ///// ////", visib: 0.25, wspd: 3, wgst: null,
    wxString: "fg", clouds: [{ cover: "VV", base: 100 }],
  }, "DEL", FETCHED)!;
  assertEquals([r.observed_at, r.visibility_m, r.wind_kts, r.wx, r.ceiling_ft, r.fog], [
    "2026-11-26T00:30:00.000Z", 402, 3, "FG", 100, true,
  ]);
});

// ---------- refresh step ----------

class FakeStore {
  rows: AirportWeatherRow[] = [];
  constructor(public list: WeatherTarget[]) {}
  targets() {
    return Promise.resolve(this.list);
  }
  upsert(rows: AirportWeatherRow[]) {
    this.rows.push(...rows);
    return Promise.resolve(rows.length);
  }
}

function fakeFetch(handler: (ids: string[]) => Response | Promise<Response>) {
  const calls: { url: string; ua: string | null; ids: string[] }[] = [];
  const fn = ((input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const ids = new URL(url).searchParams.get("ids")!.split(",");
    calls.push({ url, ua: new Headers(init?.headers).get("user-agent"), ids });
    return Promise.resolve(handler(ids));
  }) as typeof fetch;
  return { fn, calls };
}

const fixtureFor = (ids: string[]) => FIXTURE.filter((m) => ids.includes(m.icaoId));

Deno.test("refresh: one request per 30 ICAO codes, User-Agent FlyTrack/1.0, newest report per station", async () => {
  // 8 fixture stations + 57 more = 65 targets → 3 batches (30, 30, 5)
  const targets: WeatherTarget[] = Object.entries(IATA).map(([icao, iata]) => ({ iata, icao }));
  for (let i = 0; i < 57; i++) targets.push({ iata: `X${String(i).padStart(2, "0")}`, icao: `ZZ${String(i).padStart(2, "0")}` });
  targets.push({ iata: "DEL", icao: "vidp" }); // duplicate (case) → once
  targets.push({ iata: "BAD", icao: "" }); // no ICAO → skipped
  const store = new FakeStore(targets);
  const older = { ...byIcao("VIDP"), obsTime: 1795653000 - 1800, rawOb: "VIDP 260000Z 00000KT 0400 FG VV002" };
  const unknown = { ...byIcao("VABB"), icaoId: "VOGO" }; // not requested
  const f = fakeFetch((ids) => new Response(JSON.stringify([...fixtureFor(ids), ...(ids.includes("VIDP") ? [older, unknown] : [])])));
  const stats = await refreshAirportWeather({ store, fetch: f.fn, now: () => FETCHED });

  assertEquals(f.calls.length, 3);
  assertEquals(f.calls.map((c) => c.ids.length), [BATCH_SIZE, BATCH_SIZE, 5]);
  assert(f.calls.every((c) => c.ua === USER_AGENT));
  assert(f.calls.every((c) => c.url.startsWith("https://aviationweather.gov/api/data/metar?ids=") && c.url.endsWith("&format=json")));
  assertEquals(stats, { targets: 65, batches: 3, failedBatches: 0, reports: 9, upserted: 8 });
  const del = store.rows.find((r) => r.iata === "DEL")!;
  assertEquals(del.visibility_m, 50); // 00:30 report beats the 00:00 one
  assertEquals(store.rows.map((r) => r.iata).sort(), ["ATQ", "BLR", "BOM", "DEL", "DXB", "JFK", "LKO", "MAA"]);
});

Deno.test("refresh: a failed batch is skipped, the others are stored", async () => {
  const targets = Object.entries(IATA).map(([icao, iata]) => ({ iata, icao }));
  for (let i = 0; i < 30; i++) targets.push({ iata: `Y${String(i).padStart(2, "0")}`, icao: `YY${String(i).padStart(2, "0")}` });
  const store = new FakeStore(targets);
  const logs: string[] = [];
  const f = fakeFetch((ids) => ids.includes("VIDP") ? new Response("busy", { status: 503 }) : new Response(null, { status: 204 }));
  const stats = await refreshAirportWeather({ store, fetch: f.fn, now: () => FETCHED, log: (m) => logs.push(m) });
  assertEquals(stats, { targets: 38, batches: 2, failedBatches: 1, reports: 0, upserted: 0 });
  assertEquals(store.rows, []);
  assertEquals(logs, ["metar batch failed"]);
});

Deno.test("refresh: no targets → no requests", async () => {
  const f = fakeFetch(() => new Response("[]"));
  const stats = await refreshAirportWeather({ store: new FakeStore([]), fetch: f.fn, now: () => FETCHED });
  assertEquals(f.calls.length, 0);
  assertEquals(stats.upserted, 0);
});

Deno.test("fetchMetars: 204 and empty body → [], non-array → error, junk entries dropped", async () => {
  assertEquals(await fetchMetars({ fetch: fakeFetch(() => new Response(null, { status: 204 })).fn }, ["VIDP"]), []);
  assertEquals(await fetchMetars({ fetch: fakeFetch(() => new Response("")).fn }, ["VIDP"]), []);
  await assertRejects(() => fetchMetars({ fetch: fakeFetch(() => new Response('{"error":"x"}')).fn }, ["VIDP"]));
  await assertRejects(() => fetchMetars({ fetch: fakeFetch(() => new Response("x", { status: 400 })).fn }, ["VIDP"]));
  const out = await fetchMetars({ fetch: fakeFetch(() => new Response(JSON.stringify([null, 3, { icaoId: "VIDP" }]))).fn }, ["VIDP"]);
  assertEquals(out, [{ icaoId: "VIDP" }]);
  assertEquals(metarRequestUrl(["VIDP", "VABB"]), "https://aviationweather.gov/api/data/metar?ids=VIDP,VABB&format=json");
});
