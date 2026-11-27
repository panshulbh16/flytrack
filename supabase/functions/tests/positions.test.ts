import { assert, assertAlmostEquals, assertEquals, assertRejects } from "@std/assert";
import {
  adsbxFeed,
  boundingBox,
  FeedError,
  feedFromEnv,
  haversineKm,
  noneFeed,
  OPENSKY_TOKEN_URL,
  openSkyFeed,
  parseAdsbx,
  parseOpenSkyStates,
  roundToCell,
} from "../_shared/positions.ts";

import openskyDel from "./fixtures/radar/opensky-states-del.json" with { type: "json" };
import adsbxBom from "./fixtures/radar/adsbx-v2-bom.json" with { type: "json" };

const FIXTURES: Record<string, unknown> = { "opensky-states-del.json": openskyDel, "adsbx-v2-bom.json": adsbxBom };
const fixture = (name: string) => structuredClone(FIXTURES[name]);
const DEL = { lat: 28.5562, lon: 77.1 };
const BOM = { lat: 19.0896, lon: 72.8656 };

function jsonRes(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

interface Call {
  url: string;
  init?: RequestInit;
}

function recorder(handler: (url: string, init?: RequestInit) => Response) {
  const calls: Call[] = [];
  const f = ((input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    calls.push({ url, init });
    return Promise.resolve(handler(url, init));
  }) as typeof fetch;
  return { calls, f };
}

// ---------- geometry ----------

Deno.test("positions: haversine, bounding box and 0.05° rounding", () => {
  assertAlmostEquals(haversineKm(28.5562, 77.1, 19.0896, 72.8656), 1137, 5); // DEL–BOM
  const bb = boundingBox(28.6, 77.2, 75);
  assert(bb.lamin < 28.6 - 0.67 && bb.lamax > 28.6 + 0.67);
  assert(haversineKm(28.6, 77.2, 28.6, bb.lomax) >= 75);
  assertEquals(roundToCell(28.6139), 28.6);
  assertEquals(roundToCell(77.2090), 77.2);
  assertEquals(roundToCell(77.226), 77.25);
  assertEquals(roundToCell(-0.024), -0);
  assertEquals(roundToCell(12.975), 13);
  assertEquals(boundingBox(89.9, 0, 75).lamax, 90);
});

// ---------- OpenSky ----------

Deno.test("positions: OpenSky states rows map to AircraftPosition (units, trimming, ground)", () => {
  const list = parseOpenSkyStates(fixture("opensky-states-del.json"));
  assertEquals(list.length, 4); // the row without a position is dropped
  const [igo, aic, anon] = list;
  assertEquals(igo, {
    icao24: "800c1b",
    callsign: "IGO2341",
    lat: 28.5562,
    lon: 77.1031,
    alt_ft: 0,
    gs_kts: 8,
    track_deg: 93,
    on_ground: true,
    seen_at: new Date(1795769998 * 1000).toISOString(),
  });
  assertEquals(aic.alt_ft, 5000);
  assertEquals(aic.gs_kts, 160);
  assertEquals(aic.vrate_fpm, 1535);
  assertEquals(aic.on_ground, false);
  assertEquals(anon.callsign, "");
  assertEquals(anon.seen_at, new Date(1795769990 * 1000).toISOString()); // falls back to last_contact
  assertEquals(parseOpenSkyStates({ time: 1, states: null }), []);
});

Deno.test("positions: OpenSky gets a client-credentials token, caches it, filters by radius", async () => {
  let t = 1_000_000;
  const { calls, f } = recorder((url) =>
    url === OPENSKY_TOKEN_URL
      ? jsonRes({ access_token: "tok-1", expires_in: 1800, token_type: "Bearer" })
      : jsonRes(fixture("opensky-states-del.json"))
  );
  const feed = openSkyFeed({ clientId: "cid", clientSecret: "c s", fetch: f, now: () => t });
  const list = await feed.nearby(DEL.lat, DEL.lon, 5);
  assertEquals(list.map((a) => a.icao24), ["800c1b", "8015d3", "800b8a"]); // SWR147 is ~170 km away
  assertEquals(calls.length, 2);
  assertEquals(calls[0].init?.method, "POST");
  assertEquals(String(calls[0].init?.body), "grant_type=client_credentials&client_id=cid&client_secret=c+s");
  const q = new URL(calls[1].url);
  assertEquals(q.pathname, "/api/states/all");
  assert(Number(q.searchParams.get("lamin")) < DEL.lat && Number(q.searchParams.get("lamax")) > DEL.lat);
  assert(Number(q.searchParams.get("lomin")) < DEL.lon && Number(q.searchParams.get("lomax")) > DEL.lon);
  assertEquals((calls[1].init?.headers as Record<string, string>).authorization, "Bearer tok-1");

  await feed.nearby(DEL.lat, DEL.lon, 5);
  assertEquals(calls.filter((c) => c.url === OPENSKY_TOKEN_URL).length, 1, "token reused");
  t += 1800_000;
  await feed.nearby(DEL.lat, DEL.lon, 5);
  assertEquals(calls.filter((c) => c.url === OPENSKY_TOKEN_URL).length, 2, "token refreshed after expiry");
});

Deno.test("positions: OpenSky retries once with a new token on 401, maps HTTP errors to feed_error", async () => {
  let n = 0;
  const { calls, f } = recorder((url) => {
    if (url === OPENSKY_TOKEN_URL) return jsonRes({ access_token: `tok-${++n}`, expires_in: 1800 });
    const auth = calls[calls.length - 1].init?.headers as Record<string, string>;
    return auth.authorization === "Bearer tok-1" ? jsonRes({}, 401) : jsonRes({ time: 1, states: [] });
  });
  const feed = openSkyFeed({ clientId: "a", clientSecret: "b", fetch: f });
  assertEquals(await feed.nearby(1, 1, 5), []);
  assertEquals(n, 2);

  const down = openSkyFeed({
    clientId: "a",
    clientSecret: "b",
    fetch: recorder((url) => url === OPENSKY_TOKEN_URL ? jsonRes({ access_token: "x" }) : jsonRes({}, 503)).f,
  });
  const err = await assertRejects(() => down.nearby(1, 1, 5), FeedError);
  assertEquals(err.code, "feed_error");
  assertEquals(err.status, 503);
});

// ---------- ADS-B Exchange ----------

Deno.test("positions: ADS-B Exchange v2 maps ground, registration, rates and seen time", () => {
  const list = parseAdsbx(fixture("adsbx-v2-bom.json"), 0);
  assertEquals(list.length, 4);
  assertEquals(list[0], {
    icao24: "800c2f",
    callsign: "IGO5318",
    reg: "VT-IZK",
    lat: 19.0896,
    lon: 72.8656,
    alt_ft: 0,
    gs_kts: 12,
    track_deg: 140,
    on_ground: true,
    seen_at: new Date(1795770000000 - 1200).toISOString(),
  });
  assertEquals(list[1].icao24, "2b1c03"); // TIS-B "~" prefix stripped
  assertEquals(list[1].callsign, "");
  assertEquals(list[2].reg, "VT-AXH");
  assertEquals(list[2].vrate_fpm, -704);
  assertEquals(list[2].alt_ft, 3250);
  assertEquals(list[3].track_deg, 12); // true_heading fallback
});

Deno.test("positions: ADS-B Exchange URL uses nautical miles and the api-auth header", async () => {
  const { calls, f } = recorder(() => jsonRes(fixture("adsbx-v2-bom.json")));
  const feed = adsbxFeed({ apiKey: "k-123", fetch: f, baseUrl: "https://adsbx.test/v2/" });
  const list = await feed.nearby(BOM.lat, BOM.lon, 75);
  assertEquals(calls[0].url, "https://adsbx.test/v2/lat/19.0896/lon/72.8656/dist/41/");
  assertEquals((calls[0].init?.headers as Record<string, string>)["api-auth"], "k-123");
  assertEquals(list.map((a) => a.callsign), ["IGO5318", "", "AXB1144"]); // SEJ8169 is ~300 km out
  await feed.nearby(BOM.lat, BOM.lon, 5);
  assertEquals(calls[1].url.endsWith("/dist/3/"), true);
});

// ---------- selection / licence guard ----------

Deno.test("positions: feedFromEnv defaults to none and needs RADAR_FEED_LICENSED for real feeds", async () => {
  const env = (vars: Record<string, string>) => (n: string) => vars[n];
  assertEquals(feedFromEnv(env({})), { ok: false, error: "feed_unavailable" });
  assertEquals(feedFromEnv(env({ RADAR_FEED: "none", RADAR_FEED_LICENSED: "true" })), {
    ok: false,
    error: "feed_unavailable",
  });
  assertEquals(feedFromEnv(env({ RADAR_FEED: "flightradar" })), { ok: false, error: "feed_unavailable" });
  for (const lic of [undefined, "", "false", "yes", "1"]) {
    const vars: Record<string, string> = { RADAR_FEED: "opensky", OPENSKY_CLIENT_ID: "a", OPENSKY_CLIENT_SECRET: "b" };
    if (lic !== undefined) vars.RADAR_FEED_LICENSED = lic;
    assertEquals(feedFromEnv(env(vars)), { ok: false, error: "feed_unlicensed" }, `licensed=${lic}`);
  }
  assertEquals(feedFromEnv(env({ RADAR_FEED: "adsbx" })), { ok: false, error: "feed_unlicensed" });
  assertEquals(feedFromEnv(env({ RADAR_FEED: "adsbx", RADAR_FEED_LICENSED: "true" })), {
    ok: false,
    error: "feed_unavailable",
  }, "no key");
  const os = feedFromEnv(env({
    RADAR_FEED: "OpenSky",
    RADAR_FEED_LICENSED: "TRUE",
    OPENSKY_CLIENT_ID: "a",
    OPENSKY_CLIENT_SECRET: "b",
  }));
  assert(os.ok && os.feed.name === "opensky");
  const ax = feedFromEnv(env({ RADAR_FEED: "adsbx", RADAR_FEED_LICENSED: "true", ADSBX_API_KEY: "k" }));
  assert(ax.ok && ax.feed.name === "adsbx");
  const err = await assertRejects(() => noneFeed.nearby(0, 0, 1), FeedError);
  assertEquals(err.code, "feed_unavailable");
});
