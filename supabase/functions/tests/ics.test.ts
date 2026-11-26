import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  buildCalendar,
  type CalendarFeedDeps,
  type CalendarFlight,
  escapeText,
  eventTimes,
  foldLine,
  handleCalendarFeed,
  icsDateTime,
  localStamp,
  statusLine,
  terminalLabel,
  toCalendarFlights,
} from "../_shared/ics.ts";

const NOW = new Date("2026-11-26T10:00:00Z");
const TOKEN = "Ab3dEf6hIj9kLm2nOp5qRs8tUv1wXy4z";

function flight(over: Partial<CalendarFlight> = {}): CalendarFlight {
  return {
    flight_id: "1b2c3d4e-0000-4000-8000-000000000001",
    carrier: "6E",
    number: "2341",
    date: "2026-12-03",
    airline: "IndiGo",
    status: "scheduled",
    sched_dep: "2026-12-03T00:15:00Z",
    est_dep: null,
    act_dep: null,
    sched_arr: "2026-12-03T02:30:00Z",
    est_arr: null,
    act_arr: null,
    dep: {
      iata: "DEL",
      name: "Indira Gandhi International Airport",
      city: "Delhi",
      tz: "Asia/Kolkata",
      terminal: "1",
      gate: "42B",
    },
    arr: {
      iata: "BOM",
      name: "Chhatrapati Shivaji Maharaj International Airport",
      city: "Mumbai",
      tz: "Asia/Kolkata",
      terminal: "2",
      gate: null,
      belt: "5",
    },
    updated_at: "2026-11-26T07:35:00Z",
    ...over,
  };
}

/** Physical lines of an iCalendar body (split on CRLF, last empty element dropped). */
function physicalLines(ics: string): string[] {
  const lines = ics.split("\r\n");
  assertEquals(lines.pop(), "", "the body ends with CRLF");
  return lines;
}

/** Content lines after unfolding (RFC 5545 3.1). */
function unfold(ics: string): string[] {
  return ics.replace(/\r\n[ \t]/g, "").split("\r\n").filter((l) => l !== "");
}

function prop(ics: string, name: string): string[] {
  return unfold(ics).filter((l) => l.startsWith(`${name}:`) || l.startsWith(`${name};`)).map((l) =>
    l.slice(l.indexOf(":") + 1)
  );
}

Deno.test("ics: TEXT escaping per RFC 5545 3.3.11", () => {
  assertEquals(escapeText("a,b;c\\d"), "a\\,b\\;c\\\\d");
  assertEquals(escapeText("line1\nline2\r\nline3\rline4"), "line1\\nline2\\nline3\\nline4");
  assertEquals(escapeText("Terminal 1: Gate 42B"), "Terminal 1: Gate 42B", "colons are not escaped");
  assertEquals(escapeText("\\,"), "\\\\\\,", "backslash first, then the comma");
});

Deno.test("ics: folding keeps every physical line within 75 octets and never splits a character", () => {
  const short = "SUMMARY:✈ 6E 2341 DEL→BOM";
  assertEquals(foldLine(short), short);
  const exact = "X".repeat(75);
  assertEquals(foldLine(exact), exact, "75 octets fit");
  const ascii = "DESCRIPTION:" + "abcdefghij".repeat(30);
  const folded = foldLine(ascii);
  for (const l of folded.split("\r\n")) assert(new TextEncoder().encode(l).length <= 75, l);
  assertEquals(folded.split("\r\n")[0].length, 75, "the first line is filled to 75");
  assert(folded.split("\r\n").slice(1).every((l) => l.startsWith(" ")), "continuations start with one space");
  assertEquals(folded.replace(/\r\n /g, ""), ascii, "unfolding restores the line");

  // multi-byte: ✈ (3 bytes), → (3 bytes), Devanagari (3 bytes each), emoji (4 bytes)
  const mixed = "DESCRIPTION:" + "✈→दिल्ली😀".repeat(20);
  const f2 = foldLine(mixed);
  const strict = new TextDecoder("utf-8", { fatal: true });
  for (const l of f2.split("\r\n")) {
    const bytes = new TextEncoder().encode(l);
    assert(bytes.length <= 75, `${bytes.length} octets`);
    strict.decode(bytes); // throws if a sequence were cut
  }
  assertEquals(f2.replace(/\r\n /g, ""), mixed);
});

Deno.test("ics: UTC DATE-TIME", () => {
  assertEquals(icsDateTime("2026-12-03T00:15:00Z"), "20261203T001500Z");
  assertEquals(icsDateTime("2026-12-03T05:45:00+05:30"), "20261203T001500Z", "offsets become UTC");
  assertEquals(icsDateTime("2026-12-03T00:15:00.678Z"), "20261203T001500Z", "milliseconds dropped");
  assertEquals(icsDateTime(new Date(Date.UTC(2026, 0, 2, 3, 4, 5))), "20260102T030405Z");
  assertEquals(icsDateTime("nonsense"), null);
  assertEquals(icsDateTime(null), null);
});

Deno.test("ics: a full calendar is well formed", () => {
  const ics = buildCalendar([flight()], NOW);
  assert(!/[^\r]\n/.test(ics), "every line break is CRLF");
  const lines = physicalLines(ics);
  for (const l of lines) assert(new TextEncoder().encode(l).length <= 75, `too long: ${l}`);
  const content = unfold(ics);
  assertEquals(content[0], "BEGIN:VCALENDAR");
  assertEquals(content[content.length - 1], "END:VCALENDAR");
  assertEquals(prop(ics, "VERSION"), ["2.0"]);
  assertEquals(prop(ics, "PRODID").length, 1);
  assertEquals(prop(ics, "X-WR-CALNAME"), ["HeyGate flights"]);
  assert(content.includes("REFRESH-INTERVAL;VALUE=DURATION:PT1H"));
  assertEquals(content.filter((l) => l === "BEGIN:VEVENT").length, 1);
  assertEquals(content.filter((l) => l === "END:VEVENT").length, 1);
  assertEquals(prop(ics, "UID"), ["1b2c3d4e-0000-4000-8000-000000000001@heygate.app"]);
  assertEquals(prop(ics, "DTSTAMP"), ["20261126T100000Z"]);
  assertEquals(prop(ics, "DTSTART"), ["20261203T001500Z"]);
  assertEquals(prop(ics, "DTEND"), ["20261203T023000Z"]);
  assertEquals(prop(ics, "SUMMARY"), ["✈ 6E 2341 DEL→BOM"]);
  assertEquals(prop(ics, "LOCATION"), ["Indira Gandhi International Airport\\, Terminal 1"]);
  assertEquals(prop(ics, "STATUS"), ["CONFIRMED"]);
  assertEquals(prop(ics, "LAST-MODIFIED"), ["20261126T073500Z"]);
  const desc = prop(ics, "DESCRIPTION")[0];
  assertEquals(
    desc,
    "IndiGo 6E 2341\\, DEL to BOM\\nStatus: Scheduled\\nGate: 42B\\nBelt: 5\\nLast updated: 26 Nov\\, 13:05 IST\\n" +
      "Live updates in the HeyGate app.",
  );
  // every VEVENT property the RFC requires (UID, DTSTAMP) and nothing personal
  assert(!ics.includes("PNR") && !ics.includes("seat"));
});

Deno.test("ics: best known times, delays, cancellations and missing data", () => {
  // estimate beats schedule, actual beats estimate
  assertEquals(eventTimes(flight({ est_dep: "2026-12-03T00:45:00Z", est_arr: "2026-12-03T03:00:00Z" })), {
    start: Date.parse("2026-12-03T00:45:00Z"),
    end: Date.parse("2026-12-03T03:00:00Z"),
  });
  assertEquals(
    eventTimes(
      flight({ act_dep: "2026-12-03T00:50:00Z", est_dep: "2026-12-03T00:45:00Z", act_arr: "2026-12-03T02:58:00Z" }),
    ),
    {
      start: Date.parse("2026-12-03T00:50:00Z"),
      end: Date.parse("2026-12-03T02:58:00Z"),
    },
  );
  // no arrival, or an arrival before departure → one hour
  assertEquals(
    eventTimes(flight({ sched_arr: null }))!.end - eventTimes(flight({ sched_arr: null }))!.start,
    3_600_000,
  );
  assertEquals(eventTimes(flight({ sched_arr: "2026-12-02T00:00:00Z" }))!.end, Date.parse("2026-12-03T01:15:00Z"));
  assertEquals(eventTimes(flight({ sched_dep: null })), null);

  assertEquals(statusLine(flight({ est_dep: "2026-12-03T00:40:00Z" })), "Delayed 25 min");
  assertEquals(statusLine(flight({ est_dep: "2026-12-03T00:25:00Z" })), "Scheduled", "under 15 min is not a delay");
  assertEquals(statusLine(flight({ status: "en_route", est_dep: "2026-12-03T00:40:00Z" })), "In the air");
  assertEquals(statusLine(flight({ status: "weird" })), "Scheduled");

  const ics = buildCalendar([
    flight({ status: "cancelled" }),
    flight({ flight_id: "no-times", sched_dep: null }),
    flight({
      flight_id: "f3",
      dep: { ...flight().dep, name: null, terminal: null, gate: null },
      arr: { ...flight().arr, belt: null },
      updated_at: null,
      airline: null,
    }),
  ], NOW);
  assertEquals(prop(ics, "UID").length, 2, "a flight without any departure time is skipped");
  assertEquals(prop(ics, "STATUS"), ["CANCELLED", "CONFIRMED"]);
  assertEquals(prop(ics, "LOCATION")[1], "DEL", "the IATA code stands in for an unknown name");
  assertEquals(
    prop(ics, "DESCRIPTION")[1],
    "6E 2341\\, DEL to BOM\\nStatus: Scheduled\\nLive updates in the HeyGate app.",
  );
  assertEquals(prop(ics, "LAST-MODIFIED").length, 1);

  const empty = buildCalendar([], NOW);
  assertEquals(unfold(empty).filter((l) => l.startsWith("BEGIN:VEVENT")).length, 0);
  assertStringIncludes(empty, "END:VCALENDAR\r\n");
});

Deno.test("ics: user-controlled text cannot break out of a property", () => {
  const ics = buildCalendar(
    [flight({ dep: { ...flight().dep, name: "Evil\r\nBEGIN:VEVENT\nUID:x", gate: "1;X=2" } })],
    NOW,
  );
  assertEquals(unfold(ics).filter((l) => l === "BEGIN:VEVENT").length, 1);
  assertEquals(prop(ics, "LOCATION"), ["Evil\\nBEGIN:VEVENT\\nUID:x\\, Terminal 1"]);
  assert(prop(ics, "DESCRIPTION")[0].includes("Gate: 1\\;X=2"));
});

Deno.test("ics: terminal labels and local stamps", () => {
  assertEquals(terminalLabel("1"), "Terminal 1");
  assertEquals(terminalLabel("T2"), "Terminal 2");
  assertEquals(terminalLabel("Terminal 1D"), "Terminal 1D");
  assertEquals(terminalLabel(" "), null);
  assertEquals(terminalLabel(null), null);
  assertEquals(localStamp("2026-11-26T07:35:00Z", "Asia/Kolkata"), "26 Nov, 13:05 IST");
  assertEquals(localStamp("2026-11-26T07:35:00Z", "Asia/Dubai"), "26 Nov, 11:35 UTC+4");
  assertEquals(localStamp("2026-11-26T23:35:00Z", null), "26 Nov, 23:35 UTC");
  assertEquals(localStamp("2026-11-26T23:35:00Z", "Not/AZone"), "26 Nov, 23:35 UTC");
});

Deno.test("ics: feed rows are rebuilt from a whitelist", () => {
  const rows = toCalendarFlights([
    { ...flight(), pnr: "Q7XK2M", user_id: "u1" },
    { carrier: "6E" }, // no id
    "junk",
  ]);
  assertEquals(rows.length, 1);
  assert(!("pnr" in rows[0]) && !("user_id" in rows[0]));
  assert(!("belt" in rows[0].dep));
  assertEquals(rows[0].arr.belt, "5");
  assertEquals(toCalendarFlights(null), []);
});

function feedDeps(over: Partial<CalendarFeedDeps> = {}) {
  const logs: string[] = [];
  const lookups: string[] = [];
  const d: CalendarFeedDeps = {
    lookup: (t) => {
      lookups.push(t);
      return Promise.resolve(t === TOKEN ? [flight()] : null);
    },
    now: () => NOW,
    log: (msg, extra) => logs.push(JSON.stringify({ msg, ...extra })),
    ...over,
  };
  return { d, logs, lookups };
}

Deno.test("calendar-feed: serves text/calendar for a valid token", async () => {
  const { d } = feedDeps();
  const r = await handleCalendarFeed(new Request(`https://x/functions/v1/calendar-feed?t=${TOKEN}`), d);
  assertEquals(r.status, 200);
  assertEquals(r.headers.get("content-type"), "text/calendar; charset=utf-8");
  const body = await r.text();
  assert(body.startsWith("BEGIN:VCALENDAR\r\n"));
  assertStringIncludes(body, "UID:1b2c3d4e-0000-4000-8000-000000000001@heygate.app");

  const head = await handleCalendarFeed(new Request(`https://x/calendar-feed?t=${TOKEN}`, { method: "HEAD" }), d);
  assertEquals(head.status, 200);
  assertEquals(await head.text(), "");
});

Deno.test("calendar-feed: unknown or malformed tokens are 404 without a lookup for bad formats", async () => {
  const { d, lookups } = feedDeps();
  for (const t of ["", "short", `${TOKEN}x`, TOKEN.replace("A", "-"), "%27%20or%201=1"]) {
    assertEquals((await handleCalendarFeed(new Request(`https://x/calendar-feed?t=${t}`), d)).status, 404, t);
  }
  assertEquals(lookups.length, 0);
  const other = "Zz3dEf6hIj9kLm2nOp5qRs8tUv1wXy4z";
  assertEquals((await handleCalendarFeed(new Request(`https://x/calendar-feed?t=${other}`), d)).status, 404);
  assertEquals(
    (await handleCalendarFeed(new Request(`https://x/calendar-feed?t=${TOKEN}`, { method: "POST" }), d)).status,
    405,
  );
});

Deno.test("calendar-feed: a failing lookup is a 500 and the token is not logged", async () => {
  const { d, logs } = feedDeps({ lookup: () => Promise.reject(new Error("db down")) });
  const r = await handleCalendarFeed(new Request(`https://x/calendar-feed?t=${TOKEN}`), d);
  assertEquals(r.status, 500);
  assertEquals(logs.length, 1);
  assert(!logs[0].includes(TOKEN));
});
