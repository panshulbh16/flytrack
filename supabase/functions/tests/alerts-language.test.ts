// P2 sections 3 + 4: alert texts in en / hi / hinglish, quiet hours, DIVERTED_DEST, fan-out per recipient.
import { assert, assertEquals, assertFalse } from "@std/assert";
import { alertLanguage, TEMPLATES } from "../_shared/alert-text.ts";
import { evaluateAlerts, snapshotState } from "../_shared/diff.ts";
import { fanOut, searchFlight } from "../_shared/engine.ts";
import {
  buildAlertMessage,
  CHANNEL_CRITICAL,
  CHANNEL_QUIET,
  CHANNEL_UPDATES,
  isCriticalAlert,
  renderEvent,
  withFlightContext,
} from "../_shared/notify.ts";
import { inQuietWindow, isQuietFor, localMinutes, parseClock } from "../_shared/quiet-hours.ts";
import { ALERT_LANGUAGES, EVENT_TYPES, type EventType, type FlightEvent } from "../_shared/types.ts";
import { makeDeps, snap } from "./helpers.ts";

const IST = { dep: "Asia/Kolkata", arr: "Asia/Kolkata" };
const DEVANAGARI = /[ऀ-ॿ]/;
const BAD = /undefined|null|NaN|--:--|\[object|\$\{/;

/** A full, realistic payload for every event type (what diff.ts / engine / health.ts produce). */
function fullEvent(type: EventType): FlightEvent {
  const base = { flight: "6E 2341", dep: "DEL", arr: "BOM" };
  const p: Record<EventType, { old?: string | null; new?: string | null; payload: Record<string, unknown> }> = {
    DELAY: {
      old: "0",
      new: "45",
      payload: {
        delayMin: 45,
        schedDep: "2026-11-25T13:20:00Z",
        estDep: "2026-11-25T14:05:00Z",
        reason: "Late arrival of aircraft",
      },
    },
    DELAY_REDUCED: {
      old: "45",
      new: "20",
      payload: { delayMin: 20, schedDep: "2026-11-25T13:20:00Z", estDep: "2026-11-25T13:40:00Z" },
    },
    SCHEDULE_CHANGE: {
      payload: {
        oldDep: "2026-11-25T13:20:00Z",
        newDep: "2026-11-25T14:20:00Z",
        estDep: "2026-11-25T14:20:00Z",
        shiftMin: 60,
        direction: "later",
      },
    },
    GATE_ASSIGNED: { new: "12", payload: { terminal: "1", estDep: "2026-11-25T13:20:00Z" } },
    GATE_CHANGE: { old: "12", new: "29", payload: { terminal: "1", estDep: "2026-11-25T13:20:00Z" } },
    TERMINAL_CHANGE: { old: "1", new: "3", payload: { airport: "DEL" } },
    BOARDING: { payload: { gate: "29", terminal: "1" } },
    DEPARTED: { payload: { actDep: "2026-11-25T13:32:00Z", estArr: "2026-11-25T15:35:00Z" } },
    LANDED: { payload: { actArr: "2026-11-25T15:31:00Z", arrTerminal: "2", arrGate: "B4", belt: "7" } },
    BELT_ASSIGNED: { new: "7", payload: { arrTerminal: "2" } },
    BELT_CHANGE: { old: "7", new: "9", payload: { arrTerminal: "2" } },
    CANCELLED: { new: "cancelled", payload: { schedDep: "2026-11-25T13:20:00Z" } },
    DIVERTED: { old: "BOM", new: "AMD", payload: { origArr: "BOM", newArr: "AMD", estArr: "2026-11-25T15:50:00Z" } },
    REINSTATED: { old: "cancelled", new: "scheduled", payload: { estDep: "2026-11-25T13:20:00Z" } },
    INBOUND_LATE: {
      new: "40",
      payload: { lateMin: 40, predictedDep: "2026-11-25T14:00:00Z", inbound: { flight: "6E 2340", from: "BLR" } },
    },
    CHECKIN_OPEN: {
      payload: {
        closes_at: "2026-11-25T12:20:00Z",
        estDep: "2026-11-25T13:20:00Z",
        checkin_url: "https://www.goindigo.in/web-check-in.html",
      },
    },
    CHECKIN_CLOSING: { payload: { closes_at: "2026-11-25T12:20:00Z", estDep: "2026-11-25T13:20:00Z" } },
    FOG_ALERT: { new: "300", payload: { airport: "DEL", visibility_m: 300, wx: "FG", side: "dep" } },
    DIVERTED_DEST: {
      old: "BOM",
      new: "AMD",
      payload: { origArr: "BOM", newArr: "AMD", estArr: "2026-11-25T15:50:00Z" },
    },
    GATE_CLOSED: { new: "gate_closed", payload: { gate: "29", terminal: "1", estDep: "2026-11-25T13:20:00Z" } },
    TAKEOFF: { new: "2026-11-25T13:44:00Z", payload: { runwayAt: "2026-11-25T13:44:00Z", estArr: "2026-11-25T15:35:00Z" } },
    AT_GATE: {
      new: "2026-11-25T15:40:00Z",
      payload: { onblockAt: "2026-11-25T15:40:00Z", arrGate: "B4", arrTerminal: "2", belt: "7" },
    },
    AIRCRAFT_SWAP: {
      old: "VT-ISR",
      new: "VT-IJK",
      payload: { old_reg: "VT-ISR", new_reg: "VT-IJK", old_model: "Airbus A320neo", new_model: "Airbus A321neo" },
    },
    CONNECTION_AT_RISK: {
      new: "at_risk",
      payload: { out_flight: "6E 512", out_dep: "2026-11-25T16:30:00Z", airport: "BOM", buffer_min: -15 },
    },
  };
  const x = p[type];
  return {
    type,
    severity: isCriticalAlert(type) ? "critical" : "info",
    proOnly: type === "INBOUND_LATE" || type === "AIRCRAFT_SWAP" || type === "CONNECTION_AT_RISK",
    oldValue: x.old ?? null,
    newValue: x.new ?? null,
    payload: { ...base, ...x.payload },
    dedupeKey: `k:${type}`,
  };
}

/** Same event with every time and optional field missing (providers often send no estimate). */
function sparseEvent(type: EventType): FlightEvent {
  const e = fullEvent(type);
  const keep = [
    "flight",
    "dep",
    "arr",
    "airport",
    "side",
    "origArr",
    "newArr",
    "delayMin",
    "lateMin",
    "shiftMin",
    "direction",
  ];
  const payload = Object.fromEntries(Object.entries(e.payload).filter(([k]) => keep.includes(k)));
  return { ...e, payload };
}

Deno.test("templates: every event type exists in every language", () => {
  for (const lang of ALERT_LANGUAGES) {
    for (const t of EVENT_TYPES) assert(typeof TEMPLATES[lang][t] === "function", `${lang}/${t} missing`);
    assertEquals(Object.keys(TEMPLATES[lang]).sort(), [...EVENT_TYPES].sort(), `${lang}: no extra or missing keys`);
  }
});

Deno.test("templates: full and sparse payloads never print undefined / null / --:-- in any language", () => {
  for (const lang of ALERT_LANGUAGES) {
    for (const t of EVENT_TYPES) {
      for (const [kind, e] of [["full", fullEvent(t)], ["sparse", sparseEvent(t)]] as const) {
        for (const tz of [IST, { dep: null, arr: null }, { dep: "Not/AZone", arr: "Asia/Dubai" }]) {
          const r = renderEvent(e, tz, lang);
          const where = `${lang}/${t}/${kind}`;
          assert(r.title.trim().length > 3, `${where}: empty title`);
          assert(r.body.trim().length > 1, `${where}: empty body "${r.body}"`);
          assertFalse(BAD.test(r.title) || BAD.test(r.body), `${where}: "${r.title}" / "${r.body}"`);
          assertFalse(
            /\s{2,}|\s[.।,]/.test(r.title + " " + r.body),
            `${where}: stray spaces "${r.title}" / "${r.body}"`,
          );
          assert(
            r.title.includes("6E 2341") || t === "FOG_ALERT" && r.body.includes("6E 2341"),
            `${where}: flight number`,
          );
          if (lang === "hi") assert(DEVANAGARI.test(r.title + r.body), `${where}: Hindi uses Devanagari`);
          else assertFalse(DEVANAGARI.test(r.title + r.body), `${where}: no Devanagari in ${lang}`);
        }
      }
    }
  }
});

Deno.test("templates: times, codes and numbers stay Latin in Hindi", () => {
  const d = renderEvent(fullEvent("DELAY"), IST, "hi");
  assertEquals(d.title, "6E 2341: 45 मिनट की देरी"); // never "6E 2341 45 ..." (two numbers in a row)
  assertEquals(renderEvent(fullEvent("DELAY"), IST, "hinglish").title, "6E 2341: 45 min late hai");
  assertEquals(d.body, "DEL→BOM अब 19:35 बजे रवाना होगी (पहले 18:50)। कारण: Late arrival of aircraft");
  const g = renderEvent(fullEvent("GATE_CHANGE"), IST, "hi");
  assertEquals(g.title, "गेट बदला: 6E 2341 अब गेट 29 से (T1)");
  assertEquals(g.body, "पहले गेट 12 था। DEL→BOM, 18:50 बजे रवाना होगी।");
  for (const t of EVENT_TYPES) {
    const r = renderEvent(fullEvent(t), IST, "hi");
    assertFalse(/[०-९]/.test(r.title + r.body), `${t}: Devanagari digits`);
  }
});

Deno.test("templates: Hinglish reads like the contract example", () => {
  const g = renderEvent(fullEvent("GATE_CHANGE"), IST, "hinglish");
  assertEquals(g.title, "Gate change: 6E 2341 ab Gate 29 se (T1)");
  assertEquals(g.body, "Pehle gate 12 tha. DEL→BOM, 18:50 baje departure.");
  const c = renderEvent(fullEvent("CANCELLED"), IST, "hinglish");
  assertEquals(c.body, "DEL→BOM flight cancel ho gayi hai. Rebooking/refund ke liye airline se baat karein.");
});

Deno.test("templates: English keeps the existing texts; unknown language falls back to English", () => {
  const g = renderEvent(fullEvent("GATE_CHANGE"), IST);
  assertEquals(g.title, "Gate change: 6E 2341 → Gate 29 (T1)");
  assertEquals(g.body, "Was gate 12. DEL→BOM, departs 18:50.");
  assertEquals(renderEvent(fullEvent("GATE_CHANGE"), IST, "fr"), g);
  assertEquals(renderEvent(fullEvent("GATE_CHANGE"), IST, null), g);
  assertEquals(alertLanguage("hi"), "hi");
  assertEquals(alertLanguage(undefined), "en");
  const sparse = renderEvent(sparseEvent("DELAY"), IST, "en");
  assertEquals(sparse.body, "DEL→BOM is delayed.");
});

Deno.test("templates: fog and diversion texts", () => {
  const fog = renderEvent(fullEvent("FOG_ALERT"), IST, "en");
  assertEquals(`${fog.title}. ${fog.body}`, "Fog at Delhi: visibility 300 m. Delays likely for 6E 2341.");
  assertEquals(renderEvent(fullEvent("FOG_ALERT"), IST, "hi").title, "दिल्ली में कोहरा: विज़िबिलिटी 300 m");
  assertEquals(renderEvent(fullEvent("FOG_ALERT"), IST, "hinglish").title, "Delhi mein fog: visibility 300 m");
  const arr = {
    ...fullEvent("FOG_ALERT"),
    payload: { ...fullEvent("FOG_ALERT").payload, side: "arr", airport: "XYZ", visibility_m: 1200 },
  };
  assertEquals(renderEvent(arr, IST, "en").title, "Fog at XYZ: visibility 1.2 km");
  assert(renderEvent(arr, IST, "en").body.startsWith("Arrival delays"));
  // health.ts payloads carry no flight: the fan-out fills it from the row
  const bare: FlightEvent = {
    ...fullEvent("FOG_ALERT"),
    payload: { airport: "DEL", visibility_m: 300, wx: "FG", side: "dep" },
  };
  const filled = withFlightContext(bare, {
    carrier_iata: "6E",
    flight_number: "2341",
    dep_iata: "DEL",
    arr_iata: "BOM",
  });
  assertEquals(renderEvent(filled, IST, "en").body, "Delays likely for 6E 2341.");
  assertEquals(filled.payload.airport, "DEL");
  assertEquals(renderEvent(bare, IST, "en").body, "Delays likely.");

  const dd = renderEvent(fullEvent("DIVERTED_DEST"), { dep: "Asia/Kolkata", arr: "Asia/Kolkata" }, "en");
  assertEquals(dd.title, "6E 2341 diverted to AMD");
  assertEquals(dd.body, "Was going to BOM. Now landing at AMD, ETA 21:20 local.");
  assertEquals(renderEvent(fullEvent("DIVERTED_DEST"), IST, "hinglish").title, "6E 2341 ab AMD ja rahi hai");
});

// ---------------------------------------------------------------- quiet hours

Deno.test("quiet hours: window across midnight in the user's zone", () => {
  const at = (iso: string) => new Date(iso);
  // 23:00–07:00 IST = 17:30Z–01:30Z
  assertFalse(inQuietWindow(at("2026-11-25T17:29:00Z"), "23:00:00", "07:00:00", "Asia/Kolkata"), "22:59 IST");
  assert(inQuietWindow(at("2026-11-25T17:30:00Z"), "23:00:00", "07:00:00", "Asia/Kolkata"), "23:00 IST starts it");
  assert(inQuietWindow(at("2026-11-25T18:45:00Z"), "23:00:00", "07:00:00", "Asia/Kolkata"), "00:15 IST next day");
  assert(inQuietWindow(at("2026-11-26T01:29:00Z"), "23:00:00", "07:00:00", "Asia/Kolkata"), "06:59 IST");
  assertFalse(inQuietWindow(at("2026-11-26T01:30:00Z"), "23:00:00", "07:00:00", "Asia/Kolkata"), "07:00 IST ends it");
  // the same instant is 21:45 in Dubai: not quiet there
  assertFalse(inQuietWindow(at("2026-11-25T17:45:00Z"), "23:00", "07:00", "Asia/Dubai"));
  assert(inQuietWindow(at("2026-11-25T19:15:00Z"), "23:00", "07:00", "Asia/Dubai"), "23:15 Dubai");
  // same-day window (afternoon nap) and empty window
  assert(inQuietWindow(at("2026-11-25T08:30:00Z"), "13:00", "15:00", "Asia/Kolkata"), "14:00 IST");
  assertFalse(inQuietWindow(at("2026-11-25T10:00:00Z"), "13:00", "15:00", "Asia/Kolkata"), "15:30 IST");
  assertFalse(inQuietWindow(at("2026-11-25T18:45:00Z"), "07:00", "07:00", "Asia/Kolkata"), "start == end is empty");
  // a bad zone falls back to India; bad clock values mean no quiet window
  assertEquals(localMinutes(at("2026-11-25T18:45:00Z"), "Not/AZone"), 15);
  assertFalse(inQuietWindow(at("2026-11-25T18:45:00Z"), "25:00", "07:00", "Asia/Kolkata"));
  assertEquals([parseClock("23:00:00"), parseClock("7:05"), parseClock("24:00"), parseClock("x")], [
    1380,
    425,
    0,
    null,
  ]);
  // DST zone: 23:30 in London in summer (BST) is 22:30Z
  assert(inQuietWindow(at("2026-07-01T22:30:00Z"), "23:00", "07:00", "Europe/London"));
  assertFalse(inQuietWindow(at("2026-12-01T22:30:00Z"), "23:00", "07:00", "Europe/London"), "22:30 GMT in winter");
});

Deno.test("quiet hours: only when enabled; defaults 23:00–07:00 India", () => {
  const night = new Date("2026-11-25T20:00:00Z"); // 01:30 IST
  assertFalse(isQuietFor({ quiet_hours_enabled: false }, night));
  assertFalse(isQuietFor({}, night));
  assert(isQuietFor({ quiet_hours_enabled: true }, night));
  assert(
    isQuietFor(
      { quiet_hours_enabled: true, quiet_start: "23:00:00", quiet_end: "07:00:00", quiet_tz: "Asia/Kolkata" },
      night,
    ),
  );
});

Deno.test("quiet hours: info alerts go silent, critical stay loud", () => {
  const info = fullEvent("DEPARTED");
  const quiet = buildAlertMessage("tok", "f1", "e1", info, IST, { quiet: true, lang: "hi" });
  const n = quiet.android!.notification as Record<string, unknown>;
  assertEquals([n.channel_id, n.notification_priority, n.default_sound], [CHANNEL_QUIET, "PRIORITY_LOW", false]);
  const aps = (quiet.apns!.payload as Record<string, Record<string, unknown>>).aps;
  assertEquals(aps["interruption-level"], "passive");
  assertFalse("sound" in aps);
  assertEquals(quiet.data!.quiet, "1");
  assert(DEVANAGARI.test(quiet.data!.title), "rendered in the recipient's language");

  const loud = buildAlertMessage("tok", "f1", "e1", info, IST, { quiet: false });
  assertEquals((loud.android!.notification as Record<string, unknown>).channel_id, CHANNEL_UPDATES);
  assertEquals((loud.apns!.payload as Record<string, Record<string, unknown>>).aps["interruption-level"], "active");
  assertFalse("quiet" in loud.data!);

  for (const t of ["GATE_CHANGE", "FOG_ALERT", "DIVERTED_DEST", "CANCELLED"] as EventType[]) {
    const m = buildAlertMessage("tok", "f1", "e1", fullEvent(t), IST, { quiet: true });
    const cn = m.android!.notification as Record<string, unknown>;
    assertEquals([cn.channel_id, cn.default_sound], [CHANNEL_CRITICAL, true], t);
    const caps = (m.apns!.payload as Record<string, Record<string, unknown>>).aps;
    assertEquals([caps["interruption-level"], caps.sound], ["time-sensitive", "default"], t);
    assertFalse("quiet" in m.data!, t);
  }
});

// ---------------------------------------------------------------- DIVERTED_DEST

Deno.test("DIVERTED_DEST: once, after DIVERTED, when the diversion airport becomes known", () => {
  const ID = "f1";
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:30:00Z" });
  let r = evaluateAlerts(ID, snapshotState(air), air, { ...air, status: "diverted" });
  assertEquals(r.events.map((e) => e.type), ["DIVERTED"]);
  const prev = { ...air, status: "diverted" as const };
  const known = { ...prev, arrIata: "AMD", estArr: "2026-11-25T15:50:00Z" };
  r = evaluateAlerts(ID, r.state, prev, known);
  assertEquals(r.events.map((e) => e.type), ["DIVERTED_DEST"]);
  assertEquals(r.events[0].severity, "critical");
  assertEquals(r.events[0].payload.origArr, "BOM");
  assertEquals(r.events[0].payload.newArr, "AMD");
  assertEquals(r.events[0].payload.estArr, "2026-11-25T15:50:00Z");
  assertEquals(r.events[0].dedupeKey, "f1:DIVERTED_DEST");
  // further polls: nothing new
  r = evaluateAlerts(ID, r.state, known, known);
  assertEquals(r.events.map((e) => e.type), []);
  r = evaluateAlerts(ID, r.state, known, { ...known, arrIata: "BDQ" });
  assertEquals(r.events.map((e) => e.type), [], "sent once");
});

Deno.test("DIVERTED_DEST: not sent when DIVERTED already named the new airport", () => {
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:30:00Z" });
  let r = evaluateAlerts("f1", snapshotState(air), air, { ...air, arrIata: "AMD" });
  assertEquals(r.events.map((e) => e.type), ["DIVERTED"]);
  assertEquals(r.events[0].payload.newArr, "AMD");
  const d = { ...air, arrIata: "AMD", status: "diverted" as const };
  r = evaluateAlerts("f1", r.state, d, d);
  assertEquals(r.events.map((e) => e.type), []);
});

// ---------------------------------------------------------------- fan-out

Deno.test("fan-out: each recipient gets their language; quiet hours silence info alerts only", async () => {
  const h = makeDeps("2026-11-25T20:00:00Z"); // 01:30 IST
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2341", date: "2026-11-25" });
  h.repo.track(row.id, {
    userId: "hi-night",
    isPro: false,
    prefs: {},
    devices: [{ id: "d1", token: "tok-hi" }],
    profile: {
      alert_language: "hi",
      quiet_hours_enabled: true,
      quiet_start: "23:00:00",
      quiet_end: "07:00:00",
      quiet_tz: "Asia/Kolkata",
    },
  });
  h.repo.track(row.id, {
    userId: "hinglish-awake",
    isPro: false,
    prefs: {},
    devices: [{ id: "d2", token: "tok-hg" }],
    profile: {
      alert_language: "hinglish",
      quiet_hours_enabled: true,
      quiet_start: "23:00:00",
      quiet_end: "07:00:00",
      quiet_tz: "America/New_York",
    },
  });
  h.repo.track(row.id, { userId: "default", isPro: false, prefs: {}, devices: [{ id: "d3", token: "tok-en" }] });

  const info = { ...fullEvent("GATE_ASSIGNED"), id: "e1" };
  const crit = { ...fullEvent("GATE_CHANGE"), id: "e2" };
  const fog = {
    ...fullEvent("FOG_ALERT"),
    payload: { airport: "DEL", visibility_m: 300, wx: "FG", side: "dep" },
    id: "e3",
  };
  await fanOut(h.deps, row, [info, crit, fog], false);

  const by = (tok: string, ev: string) => h.pusher.sent.find((m) => m.token === tok && m.data?.event_id === ev)!;
  const ch = (tok: string, ev: string) => (by(tok, ev).android!.notification as Record<string, unknown>).channel_id;
  assertEquals(by("tok-hi", "e1").data!.title, `${row.carrier_iata} ${row.flight_number}: गेट 12`);
  assertEquals(ch("tok-hi", "e1"), CHANNEL_QUIET, "info alert inside the quiet window");
  assertEquals(ch("tok-hi", "e2"), CHANNEL_CRITICAL, "critical stays loud");
  assert(by("tok-hg", "e1").data!.title.includes("Gate 12"));
  assertEquals(ch("tok-hg", "e1"), CHANNEL_UPDATES, "15:00 in New York is not quiet");
  assert(
    by("tok-hg", "e2").data!.title.startsWith("Gate change:") &&
      by("tok-hg", "e2").data!.title.includes(" ab Gate 29 se"),
  );
  assertEquals(by("tok-en", "e1").data!.title, `${row.carrier_iata} ${row.flight_number}: Gate 12`);
  assertEquals(ch("tok-en", "e1"), CHANNEL_UPDATES, "quiet hours off by default");
  assertEquals(by("tok-en", "e3").data!.body, `Delays likely for ${row.carrier_iata} ${row.flight_number}.`);
  assertEquals(by("tok-hi", "e3").data!.title, "दिल्ली में कोहरा: विज़िबिलिटी 300 m");
});
