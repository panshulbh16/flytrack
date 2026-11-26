// P2 wave 2 section 1: friend alerts ("Asha's flight 6E 2341 has landed in Mumbai").
// Which events go to friends, the friend templates in en / hi / hinglish, the FCM data (role, friend_name),
// quiet hours and language of the friend, and the fan-out next to the flight's own trackers.
import { assert, assertEquals, assertFalse } from "@std/assert";
import { fanOut, ingest } from "../_shared/engine.ts";
import {
  cleanFriendName,
  FRIEND_DELAY_MIN,
  FRIEND_EVENT_TYPES,
  FRIEND_TEMPLATES,
  friendNameForData,
  isFriendCritical,
  isFriendEvent,
  renderFriendAlert,
} from "../_shared/friend-alerts.ts";
import { buildAlertMessage, CHANNEL_CRITICAL, CHANNEL_QUIET, CHANNEL_UPDATES } from "../_shared/notify.ts";
import { ALERT_LANGUAGES, EVENT_TYPES, type EventType, type FlightEvent } from "../_shared/types.ts";
import { makeDeps, snap } from "./helpers.ts";

const IST = { dep: "Asia/Kolkata", arr: "Asia/Kolkata" };
const DEVANAGARI = /[ऀ-ॿ]/;
const BAD = /undefined|null|NaN|--:--|\[object|\$\{/;

function ev(type: EventType, payload: Record<string, unknown> = {}, over: Partial<FlightEvent> = {}): FlightEvent {
  return {
    type,
    severity: "critical",
    proOnly: false,
    oldValue: null,
    newValue: null,
    payload: { flight: "6E 2341", dep: "DEL", arr: "BOM", ...payload },
    dedupeKey: `f:${type}`,
    ...over,
  };
}

const FULL: Record<(typeof FRIEND_EVENT_TYPES)[number], FlightEvent> = {
  DEPARTED: ev("DEPARTED", { actDep: "2026-11-25T13:32:00Z", estArr: "2026-11-25T15:35:00Z" }),
  LANDED: ev("LANDED", { actArr: "2026-11-25T15:31:00Z", belt: "7" }),
  CANCELLED: ev("CANCELLED"),
  REINSTATED: ev("REINSTATED", { estDep: "2026-11-25T14:20:00Z" }),
  DIVERTED: ev("DIVERTED", { origArr: "BOM", newArr: "AMD", estArr: "2026-11-25T15:50:00Z" }),
  DIVERTED_DEST: ev("DIVERTED_DEST", { origArr: "BOM", newArr: "AMD", estArr: "2026-11-25T15:50:00Z" }),
  DELAY: ev("DELAY", { delayMin: 45, schedDep: "2026-11-25T13:20:00Z", estDep: "2026-11-25T14:05:00Z" }, {
    newValue: "45",
  }),
};

// ---------------------------------------------------------------- which events

Deno.test("friends: only DEPARTED, LANDED, CANCELLED, REINSTATED, DIVERTED, DIVERTED_DEST and DELAY >= 30", () => {
  const allowed = new Set<EventType>(["DEPARTED", "LANDED", "CANCELLED", "REINSTATED", "DIVERTED", "DIVERTED_DEST"]);
  for (const t of EVENT_TYPES) {
    if (t === "DELAY") continue;
    assertEquals(isFriendEvent(ev(t)), allowed.has(t), t);
  }
  assertEquals(FRIEND_DELAY_MIN, 30);
  assertFalse(isFriendEvent(ev("DELAY", { delayMin: 29 })), "29 min is not enough");
  assert(isFriendEvent(ev("DELAY", { delayMin: 30 })), "30 min is");
  assert(isFriendEvent(ev("DELAY", {}, { newValue: "50" })), "falls back to newValue");
  assertFalse(isFriendEvent(ev("DELAY")), "unknown delay → no friend alert");
  assertFalse(isFriendEvent(ev("DELAY_REDUCED", { delayMin: 60 })));
  assertFalse(isFriendEvent(ev("INBOUND_LATE", { lateMin: 90 }, { proOnly: true })), "Pro-only never");
  assertFalse(isFriendEvent(ev("LANDED", {}, { proOnly: true })), "any Pro-only event never");
});

// ---------------------------------------------------------------- texts

Deno.test("friend templates: the contract examples", () => {
  const landed = ev("LANDED");
  assertEquals(renderFriendAlert(landed, IST, "en", "Asha")!.title, "Asha's flight 6E 2341 has landed in Mumbai");
  assertEquals(renderFriendAlert(landed, IST, "hi", "Asha")!.title, "Asha की फ़्लाइट 6E 2341 मुंबई पहुँच गई");
  assertEquals(renderFriendAlert(landed, IST, "hinglish", "Asha")!.title, "Asha ki flight 6E 2341 Mumbai land ho gayi");
});

Deno.test("friend templates: every type, every language, full and sparse payloads", () => {
  for (const lang of ALERT_LANGUAGES) {
    assertEquals(Object.keys(FRIEND_TEMPLATES[lang]).sort(), [...FRIEND_EVENT_TYPES].sort(), lang);
    for (const t of FRIEND_EVENT_TYPES) {
      for (const name of ["Asha", null]) {
        for (const e of [FULL[t], ev(t, t === "DELAY" ? { delayMin: 30 } : {})]) {
          const r = renderFriendAlert(e, IST, lang, name)!;
          assert(r.title && r.body, `${lang} ${t} has text`);
          assertFalse(BAD.test(r.title) || BAD.test(r.body), `${lang} ${t}: ${r.title} / ${r.body}`);
          assert(r.title.includes("6E 2341"), `${lang} ${t} names the flight: ${r.title}`);
          if (name) assert(r.title.startsWith("Asha"), `${lang} ${t} names the friend: ${r.title}`);
          if (lang === "hi") assert(DEVANAGARI.test(r.title), `${lang} ${t} in Devanagari`);
          else assertFalse(DEVANAGARI.test(r.title + r.body), `${lang} ${t} in Latin script`);
        }
      }
    }
  }
});

Deno.test("friend templates: other texts read naturally", () => {
  const en = (t: keyof typeof FULL) => renderFriendAlert(FULL[t], IST, "en", "Asha")!;
  assertEquals(en("DEPARTED").title, "Asha's flight 6E 2341 has departed from Delhi");
  assertEquals(en("DEPARTED").body, "Took off 19:02. Expected in Mumbai 21:05.");
  assertEquals(en("LANDED").body, "Landed 21:01. Baggage belt 7.");
  assertEquals(en("CANCELLED").title, "Asha's flight 6E 2341 has been cancelled");
  assertEquals(en("CANCELLED").body, "DEL→BOM. Check with Asha about new plans.");
  assertEquals(en("DIVERTED").body, "Now heading to Ahmedabad instead of Mumbai.");
  assertEquals(en("DIVERTED_DEST").title, "Asha's flight 6E 2341 is diverting to Ahmedabad");
  assertEquals(en("DELAY").title, "Asha's flight 6E 2341 is delayed 45 min");
  assertEquals(en("DELAY").body, "DEL→BOM now departs 19:35.");
  assertEquals(renderFriendAlert(FULL.DELAY, IST, "hi", "Asha")!.title, "Asha की फ़्लाइट 6E 2341 45 मिनट लेट है");
  assertEquals(renderFriendAlert(FULL.DELAY, IST, "hinglish", "Asha")!.title, "Asha ki flight 6E 2341 45 min late hai");
  assertEquals(
    renderFriendAlert(FULL.DEPARTED, IST, "hinglish", "Asha")!.title,
    "Asha ki flight 6E 2341 Delhi se ud gayi",
  );
  // no name → "Your friend"
  assertEquals(
    renderFriendAlert(FULL.LANDED, IST, "en", null)!.title,
    "Your friend's flight 6E 2341 has landed in Mumbai",
  );
  assertEquals(renderFriendAlert(FULL.LANDED, IST, "hi", "  ")!.title, "आपके दोस्त की फ़्लाइट 6E 2341 मुंबई पहुँच गई");
  assertEquals(
    renderFriendAlert(FULL.LANDED, IST, "hinglish", null)!.title,
    "Aapke dost ki flight 6E 2341 Mumbai land ho gayi",
  );
  // an airport without a city name prints its code; unknown language → English
  assertEquals(
    renderFriendAlert(ev("LANDED", { arr: "IXB" }), IST, "fr", "Asha")!.title,
    "Asha's flight 6E 2341 has landed in IXB",
  );
  assertEquals(renderFriendAlert(ev("GATE_CHANGE"), IST, "en", "Asha"), null, "not a friend event");
});

Deno.test("friend names: cleaned, capped, with a localised fallback", () => {
  assertEquals(cleanFriendName("  Asha\n\tSharma  "), "Asha Sharma");
  assertEquals(cleanFriendName(""), null);
  assertEquals(cleanFriendName(null), null);
  assertEquals(cleanFriendName(42), null);
  // bidi overrides / isolates are dropped (they could reverse the rest of the alert); ZWJ / ZWNJ stay (Hindi)
  assertEquals(cleanFriendName("As\u202eha\u2066"), "Asha");
  assertEquals(cleanFriendName("\u202e\u2069 "), null);
  assertEquals(cleanFriendName("A\u2028B"), "A B");
  assertEquals(cleanFriendName("क्\u200dष"), "क्\u200dष");
  const long = cleanFriendName("A".repeat(80))!;
  assertEquals(Array.from(long).length, 40);
  assert(long.endsWith("…"));
  assertEquals(Array.from(cleanFriendName("माँ".repeat(30))!).length <= 40, true);
  assertEquals(friendNameForData(null, "en"), "Your friend");
  assertEquals(friendNameForData(null, "hinglish"), "Aapka dost");
  assertEquals(friendNameForData(" ", "hi"), "आपका दोस्त");
  assertEquals(friendNameForData("Asha", "hi"), "Asha");
});

// ---------------------------------------------------------------- FCM message

Deno.test("friend FCM: role + friend_name in data; only disruptions stay loud; quiet hours respected", () => {
  const msg = buildAlertMessage("tok", "f1", "e1", FULL.LANDED, IST, { lang: "en", friend: { name: "Asha" } });
  assertEquals(msg.data!.role, "friend");
  assertEquals(msg.data!.friend_name, "Asha");
  assertEquals(msg.data!.title, "Asha's flight 6E 2341 has landed in Mumbai");
  assertEquals((msg.android!.notification as Record<string, unknown>).title, msg.data!.title);
  const aps = msg.apns!.payload as Record<string, unknown>;
  assertEquals(aps.role, "friend", "iOS userInfo carries the role too");
  assertEquals(aps.friend_name, "Asha");

  const ch = (e: FlightEvent, quiet: boolean) =>
    (buildAlertMessage("tok", "f1", "e1", e, IST, { quiet, friend: { name: null } }).android!.notification as Record<
      string,
      unknown
    >).channel_id;
  assertEquals(ch(FULL.LANDED, false), CHANNEL_UPDATES);
  assertEquals(ch(FULL.LANDED, true), CHANNEL_QUIET);
  assertEquals(ch(FULL.DELAY, false), CHANNEL_UPDATES, "a friend's delay is not an emergency");
  assertEquals(ch(FULL.DELAY, true), CHANNEL_QUIET, "and it goes silent at night");
  for (const t of ["CANCELLED", "DIVERTED", "DIVERTED_DEST"] as const) {
    assert(isFriendCritical(t));
    assertEquals(ch(FULL[t], true), CHANNEL_CRITICAL, `${t} stays loud`);
  }
  // fallback name in the data follows the recipient's language
  const hi = buildAlertMessage("tok", "f1", "e1", FULL.LANDED, IST, { lang: "hi", friend: { name: null } });
  assertEquals(hi.data!.friend_name, "आपका दोस्त");
  // tracker messages carry no role
  const tracker = buildAlertMessage("tok", "f1", "e1", FULL.LANDED, IST, { lang: "en" });
  assertFalse("role" in tracker.data!);
  assertFalse("friend_name" in tracker.data!);
  assertEquals(tracker.data!.title, "6E 2341 landed at BOM");
});

// ---------------------------------------------------------------- fan-out

async function setup() {
  const h = makeDeps("2026-11-25T12:00:00Z"); // 17:30 IST
  const row = (await ingest(h.deps, null, snap(), "search")).row;
  h.repo.tz = { DEL: "Asia/Kolkata", BOM: "Asia/Kolkata" };
  h.repo.track(row.id, { userId: "asha", isPro: true, prefs: {}, devices: [{ id: "dA", token: "tok-asha" }] });
  h.repo.track(row.id, { userId: "ravi", isPro: false, prefs: {}, devices: [{ id: "dR", token: "tok-ravi" }] });
  h.repo.addFriend(row.id, {
    userId: "mom",
    friendName: "Asha",
    devices: [{ id: "dM1", token: "tok-mom-1" }, { id: "dM2", token: "tok-mom-2" }],
    profile: {
      alert_language: "hi",
      quiet_hours_enabled: true,
      quiet_start: "17:00:00", // 17:30 IST is inside
      quiet_end: "18:00:00",
      quiet_tz: "Asia/Kolkata",
    },
  });
  h.repo.addFriend(row.id, { userId: "dad", friendName: null, devices: [{ id: "dD", token: "tok-dad" }] });
  // Ravi is Asha's friend too but tracks the flight himself → tracker alerts only
  h.repo.addFriend(row.id, { userId: "ravi", friendName: "Asha", devices: [{ id: "dR", token: "tok-ravi" }] });
  return { h, row };
}

Deno.test("fan-out: friends get friend texts for friend events only, never Pro-only ones", async () => {
  const { h, row } = await setup();
  const events = [
    { ...FULL.LANDED, id: "e-landed" },
    { ...ev("GATE_CHANGE", { terminal: "1" }, { oldValue: "12", newValue: "29" }), id: "e-gate" },
    { ...ev("DELAY", { delayMin: 20 }, { newValue: "20" }), id: "e-delay20" },
    { ...FULL.DELAY, id: "e-delay45" },
    { ...ev("INBOUND_LATE", { lateMin: 60 }, { proOnly: true, newValue: "60" }), id: "e-inbound" },
  ];
  await fanOut(h.deps, row, events, false);
  const to = (tok: string) => h.pusher.sent.filter((m) => m.token === tok).map((m) => m.data!.event_id).sort();

  assertEquals(to("tok-asha"), ["e-delay20", "e-delay45", "e-gate", "e-inbound", "e-landed"], "Pro tracker: all");
  assertEquals(to("tok-ravi"), ["e-delay20", "e-delay45", "e-gate", "e-landed"], "free tracker, once, no friend copy");
  assertEquals(to("tok-mom-1"), ["e-delay45", "e-landed"], "friend: LANDED + delay >= 30 only");
  assertEquals(to("tok-mom-2"), ["e-delay45", "e-landed"], "every device of the friend");
  assertEquals(to("tok-dad"), ["e-delay45", "e-landed"]);

  const msg = (tok: string, id: string) => h.pusher.sent.find((m) => m.token === tok && m.data!.event_id === id)!;
  const mom = msg("tok-mom-1", "e-landed");
  assertEquals(mom.data!.title, "Asha की फ़्लाइट 6E 2341 मुंबई पहुँच गई", "the friend's own language");
  assertEquals(mom.data!.role, "friend");
  assertEquals(mom.data!.friend_name, "Asha");
  assertEquals(
    (mom.android!.notification as Record<string, unknown>).channel_id,
    CHANNEL_QUIET,
    "friend's quiet hours",
  );
  const dad = msg("tok-dad", "e-landed");
  assertEquals(dad.data!.title, "Your friend's flight 6E 2341 has landed in Mumbai");
  assertEquals(dad.data!.friend_name, "Your friend");
  assertEquals((dad.android!.notification as Record<string, unknown>).channel_id, CHANNEL_UPDATES);
  assertFalse("role" in msg("tok-asha", "e-landed").data!, "trackers keep the normal message");
  assertFalse("role" in msg("tok-ravi", "e-landed").data!);

  // push_log has one row per message, under the friend's own user id
  assertEquals(h.repo.pushes.filter((p) => p.userId === "mom").length, 4);
});

Deno.test("fan-out: a real poll sends DEPARTED and LANDED to friends, but sync only to trackers", async () => {
  const { h, row } = await setup();
  const landed = snap({
    status: "arrived",
    actDep: "2026-11-25T13:32:00Z",
    estArr: "2026-11-25T15:35:00Z",
    actArr: "2026-11-25T15:31:00Z",
  });
  const res = await ingest(h.deps, row, landed, "poll");
  assert(res.events.some((e) => e.type === "LANDED") && res.events.some((e) => e.type === "DEPARTED"));
  const dad = h.pusher.sent.filter((m) => m.token === "tok-dad");
  assertEquals(dad.map((m) => m.data!.event_type).sort(), ["DEPARTED", "LANDED"]);
  assert(dad.every((m) => m.data!.kind === "alert"), "no sync message for a friend's flight");
  assert(h.pusher.sent.some((m) => m.token === "tok-asha" && m.data!.kind === "sync"), "trackers still get sync");
  assertEquals(
    dad.find((m) => m.data!.event_type === "LANDED")!.data!.title,
    "Your friend's flight 6E 2341 has landed in Mumbai",
  );
});

Deno.test("fan-out: a dead friend token is removed like any other", async () => {
  const { h, row } = await setup();
  h.deps.pusher = {
    send: (m) => Promise.resolve(m.token === "tok-dad" ? { ok: false, invalidToken: true } : { ok: true }),
  };
  await fanOut(h.deps, row, [{ ...FULL.CANCELLED, id: "e1" }], false);
  const recips = await h.repo.getRecipients(row.id);
  assertFalse(recips.some((r) => r.fcm_token === "tok-dad"));
  assert(recips.some((r) => r.fcm_token === "tok-mom-1" && r.role === "friend"));
});
