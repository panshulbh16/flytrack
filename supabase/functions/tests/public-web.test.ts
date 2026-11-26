// Pure helpers of the public airport pages and the yearly report (web/airports, web/report; plain JS, no build).
import { assert, assertEquals } from "@std/assert";
import {
  formatCount,
  iataFromHash,
  levelInfo,
  METROS,
  todayTiles,
  updatedAgo,
  weatherLine,
} from "../../../web/airports/airports.js";
import {
  earthLaps,
  formatBig,
  indianYear,
  rankRows,
  routeEnd,
  shareText,
  yearFromLocation,
} from "../../../web/report/report.js";

Deno.test("airports page: hash, levels, tiles, weather", () => {
  assertEquals(iataFromHash("#del"), "DEL");
  assertEquals(iataFromHash(" #BOM "), null);
  assertEquals(iataFromHash("#%20blr%20"), "BLR");
  assertEquals(iataFromHash("#DELHI"), null);
  assertEquals(iataFromHash("#%E0%A4"), null);
  assertEquals(iataFromHash(""), null);
  assertEquals(levelInfo("severe").tone, "bad");
  assertEquals(levelInfo("busy").short, "Busy");
  assertEquals(levelInfo("normal").tone, "ok");
  assertEquals(levelInfo(undefined).tone, "none");
  assertEquals(formatCount(123456), "1,23,456");
  assertEquals(formatCount(NaN), "–");
  const tiles = todayTiles({ departures: 410, departed: 200, delayed_pct: 44, cancelled: 3 });
  assertEquals(tiles.map((t) => t.value), ["410", "200", "44%", "3"]);
  assertEquals(todayTiles({ delayed_pct: null })[2], {
    label: "Left 15+ min late",
    value: "–",
    sub: "too few departures yet",
  });
  assertEquals(todayTiles(null)[0].value, "0");
  const now = Date.parse("2026-11-26T10:00:00Z");
  assertEquals(updatedAgo("2026-11-26T09:56:00Z", now), "Updated 4 min ago");
  assertEquals(updatedAgo("2026-11-26T10:00:20Z", now), "Updated just now");
  assertEquals(updatedAgo("2026-11-26T07:00:00Z", now), "Updated 3 h ago");
  assertEquals(updatedAgo(null, now), null);
  assertEquals(weatherLine(null), null);
  assertEquals(
    weatherLine({ summary: "Fog: visibility 300 m", visibility_m: 300 }),
    "Fog: visibility 300 m · visibility 300 m",
  );
  assertEquals(weatherLine({ summary: null, visibility_m: 9999 }), "Good flying weather · visibility 10 km or more");
  assert(METROS.length >= 8 && METROS.every((m) => /^[A-Z]{3}$/.test(m.iata)));
  assertEquals(new Set(METROS.map((m) => m.iata)).size, METROS.length);
});

Deno.test("report page: year, numbers, rankings, share text", () => {
  const now = Date.parse("2026-12-31T19:00:00Z"); // 1 Jan 2027 in India
  assertEquals(indianYear(now), 2027);
  assertEquals(yearFromLocation("?year=2025", "", now), 2025);
  assertEquals(yearFromLocation("", "#2024", now), 2024);
  assertEquals(yearFromLocation("?year=2023", "", now), 2027, "before the first year");
  assertEquals(yearFromLocation("?year=2030", "", now), 2027, "future");
  assertEquals(yearFromLocation("?year=abc", "#2025", now), 2027, "a bad ?year wins over the hash");
  assertEquals(yearFromLocation("", "", now), 2027);
  assertEquals(formatBig(12345), "12,345");
  assertEquals(formatBig(420000), "4.2 lakh");
  assertEquals(formatBig(13_000_000), "1.3 crore");
  assertEquals(formatBig(undefined), "–");
  assertEquals(earthLaps(80_150), 2);
  assertEquals(earthLaps(0), 0);
  const rows = rankRows([{ carrier: "6E", name: "IndiGo", on_time_pct: 88, avg_dep_delay: 8 }], "airline", "ok");
  assertEquals(rows, [{ title: "IndiGo (6E)", value: "88% on time · 8 min avg delay", pct: 88, tone: "ok" }]);
  assertEquals(rankRows([{ iata: "DEL", city: "New Delhi", on_time_pct: 140 }], "airport", "bad")[0].pct, 100);
  assertEquals(
    rankRows([{ iata: "DEL", city: "New Delhi", on_time_pct: 70, avg_dep_delay: null }], "airport", "bad")[0].title,
    "New Delhi DEL",
  );
  assertEquals(rankRows(undefined, "airline", "ok"), []);
  assertEquals(
    shareText({
      year: 2026,
      totals: { flights_tracked: 52000 },
      airlines: { most_punctual: [{ carrier: "6E", name: "IndiGo" }] },
      fog: { airport: "DEL", fog_days: 21 },
    }),
    "2026 in flights on HeyGate · 52,000 flights tracked · most punctual: IndiGo · 21 fog days at DEL",
  );
  assertEquals(shareText({ year: 2026, totals: {}, airlines: {}, fog: { fog_days: 0 } }), "2026 in flights on HeyGate");
});

Deno.test("report page: route ends use the report's coordinates, then the built-in table", () => {
  assertEquals(routeEnd({ dep: "XYZ", dep_lat: 10.5, dep_lon: 20.25 }, "dep"), [20.25, 10.5]);
  assertEquals(routeEnd({ dep: "DEL" }, "dep"), [77.1, 28.56], "fallback table");
  assertEquals(routeEnd({ arr: "QQQ", arr_lat: null, arr_lon: 3 }, "arr"), null, "unknown and incomplete");
});
