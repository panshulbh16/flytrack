import { assert, assertEquals } from "@std/assert";
import { runCheckinReminders, runPollCycle, searchFlight, sweepUnpushedEvents } from "../_shared/engine.ts";
import { checkinWindow, decideCheckin, isCheckinDue, safeCheckinUrl, SEED_RULES } from "../_shared/checkin.ts";
import { buildAlertMessage, renderEvent } from "../_shared/notify.ts";
import { EVENT_TYPES, type FlightRow } from "../_shared/types.ts";
import { makeDeps } from "./helpers.ts";

const DATE = "2026-11-25";
const indigo = SEED_RULES.find((r) => r.carrier_iata === "6E")!;
const MIN = 60_000;

function withRules(startIso: string) {
  const h = makeDeps(startIso);
  for (const r of SEED_RULES) h.repo.checkinRules.set(r.carrier_iata, r);
  return h;
}

async function trackedFlight(h: ReturnType<typeof withRules>, flight = "6E 2340", prefs: Record<string, boolean> = {}) {
  const [row] = await searchFlight(h.deps, "u", { flight, date: DATE });
  h.repo.track(row.id, { userId: "u", isPro: false, prefs, devices: [{ id: "d1", token: "tok-checkin-xxxxxxxxxxxxxxxx" }] });
  return (await h.repo.getFlight(row.id))!;
}

const alerts = (h: ReturnType<typeof withRules>) => h.pusher.sent.filter((m) => m.data?.kind === "alert");

Deno.test("check-in: EVENT_TYPES carry both reminders", () => {
  assert(EVENT_TYPES.includes("CHECKIN_OPEN"));
  assert(EVENT_TYPES.includes("CHECKIN_CLOSING"));
});

Deno.test("check-in window: est_dep wins, domestic vs international close", () => {
  const dep = "2026-11-25T10:00:00Z";
  assertEquals(checkinWindow(indigo, { sched_dep: dep, est_dep: null }, false), {
    opensAt: "2026-11-23T10:00:00.000Z", closesAt: "2026-11-25T09:00:00.000Z",
  });
  assertEquals(checkinWindow(indigo, { sched_dep: dep, est_dep: null }, true)!.closesAt, "2026-11-25T08:45:00.000Z");
  assertEquals(checkinWindow(indigo, { sched_dep: dep, est_dep: "2026-11-25T12:00:00Z" }, false)!.closesAt, "2026-11-25T11:00:00.000Z");
  assertEquals(checkinWindow(null, { sched_dep: dep, est_dep: null }, false), null);
  assertEquals(checkinWindow(indigo, { sched_dep: null, est_dep: null }, false), null);
});

Deno.test("check-in: safe URLs only", () => {
  assertEquals(safeCheckinUrl("https://www.goindigo.in/web-check-in.html"), "https://www.goindigo.in/web-check-in.html");
  assertEquals(safeCheckinUrl("http://www.goindigo.in/"), null);
  assertEquals(safeCheckinUrl("javascript:alert(1)"), null);
  assertEquals(safeCheckinUrl("https://a b"), null);
  assertEquals(safeCheckinUrl(null), null);
  for (const r of SEED_RULES) assert(safeCheckinUrl(r.checkin_url), r.carrier_iata);
});

Deno.test("check-in: MemoryRepo mirrors the trigger (domestic, DXB international, no rule)", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const dom = await trackedFlight(h, "6E 2340"); // DEL→BOM
  const base = Date.parse(dom.est_dep ?? dom.sched_dep!);
  assertEquals(Date.parse(dom.checkin_opens_at!), base - 48 * 60 * MIN);
  assertEquals(Date.parse(dom.checkin_closes_at!), base - 60 * MIN);
  const intl = await trackedFlight(h, "6E 2344"); // 2344 % 6 = 4 → DEL→DXB
  assertEquals(intl.arr_iata, "DXB");
  assertEquals(Date.parse(intl.checkin_closes_at!), Date.parse(intl.est_dep ?? intl.sched_dep!) - 75 * MIN);
  const none = await trackedFlight(h, "UK 2340");
  assertEquals(none.checkin_opens_at, null);
});

Deno.test("check-in: OPEN once when the window opens, CLOSING once 30 min before the close, with checkin_url", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const row = await trackedFlight(h);
  const opens = Date.parse(row.checkin_opens_at!);
  const closes = Date.parse(row.checkin_closes_at!);

  h.clock.t = opens - MIN;
  assertEquals(await runCheckinReminders(h.deps), 0, "not yet open");
  h.clock.t = opens;
  assertEquals(await runCheckinReminders(h.deps), 1);
  assertEquals(await runCheckinReminders(h.deps), 0, "sent once");

  h.clock.t = closes - 31 * MIN;
  assertEquals(await runCheckinReminders(h.deps), 0);
  h.clock.t = closes - 30 * MIN;
  assertEquals(await runCheckinReminders(h.deps), 1);
  h.clock.t = closes - 10 * MIN;
  assertEquals(await runCheckinReminders(h.deps), 0, "closing sent once");

  const sent = alerts(h);
  assertEquals(sent.map((m) => m.data!.event_type), ["CHECKIN_OPEN", "CHECKIN_CLOSING"]);
  for (const m of sent) {
    assertEquals(m.data!.kind, "alert");
    assertEquals(m.data!.flight_id, row.id);
    assert(m.data!.event_id);
    assert(m.data!.title && m.data!.body);
    assertEquals(m.data!.checkin_url, "https://www.goindigo.in/web-check-in.html");
    // APNs userInfo: checkin_url at the top level, next to aps
    const payload = (m.apns as { payload: Record<string, unknown> }).payload;
    assertEquals(payload.checkin_url, "https://www.goindigo.in/web-check-in.html");
    assertEquals(payload.event_type, m.data!.event_type);
  }
  const channel = (i: number) => (sent[i].android!.notification as Record<string, string>).channel_id;
  assertEquals(channel(0), "flight_updates");
  assertEquals(channel(1), "flight_critical");
  assertEquals((sent[0].android!.notification as Record<string, string>).tag, `${row.id}:checkin_open`);
  assertEquals((sent[1].android!.notification as Record<string, string>).tag, `${row.id}:checkin_closing`);
  assertEquals(sent[1].android!.ttl, "1800s");
  const aps = (i: number) => ((sent[i].apns as { payload: { aps: Record<string, unknown> } }).payload.aps)["interruption-level"];
  assertEquals(aps(0), "active");
  assertEquals(aps(1), "time-sensitive");

  const ev = h.repo.events.filter((e) => e.type.startsWith("CHECKIN"));
  assertEquals(ev.map((e) => [e.type, e.severity, e.proOnly]), [["CHECKIN_OPEN", "info", false], ["CHECKIN_CLOSING", "critical", false]]);
  assertEquals(ev[0].payload.closes_at, row.checkin_closes_at);
  assertEquals(ev[0].payload.checkin_url, "https://www.goindigo.in/web-check-in.html");
  const f = (await h.repo.getFlight(row.id))!;
  assert(f.checkin_open_notified && f.checkin_closing_notified);
  assertEquals(h.provider.calls, 1, "reminders never call the provider (1 = the search)");
});

Deno.test("check-in: tracked inside the last 30 min → only CLOSING; after the close → nothing", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const row = await trackedFlight(h);
  const closes = Date.parse(row.checkin_closes_at!);
  h.clock.t = closes - 20 * MIN;
  assertEquals(await runCheckinReminders(h.deps), 1);
  assertEquals(alerts(h).map((m) => m.data!.event_type), ["CHECKIN_CLOSING"]);
  assertEquals(await runCheckinReminders(h.deps), 0, "the open reminder is not sent late");

  const h2 = withRules("2026-11-21T00:00:00Z");
  const row2 = await trackedFlight(h2);
  h2.clock.t = Date.parse(row2.checkin_closes_at!) + MIN;
  assertEquals(await runCheckinReminders(h2.deps), 0);
});

Deno.test("check-in: muted via notify_prefs, untracked, cancelled and departed flights get nothing", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const row = await trackedFlight(h, "6E 2340", { CHECKIN_OPEN: false, CHECKIN_CLOSING: false });
  h.clock.t = Date.parse(row.checkin_opens_at!);
  assertEquals(await runCheckinReminders(h.deps), 1, "event row is written");
  assertEquals(alerts(h).length, 0, "but the muted user gets no push");

  const base = { ...row, checkin_open_notified: false, checkin_closing_notified: false };
  const now = new Date(Date.parse(row.checkin_opens_at!) + MIN);
  assert(isCheckinDue(base, now));
  assert(!isCheckinDue({ ...base, tracker_count: 0 }, now));
  assert(!isCheckinDue({ ...base, status: "cancelled" }, now));
  assert(!isCheckinDue({ ...base, act_dep: now.toISOString() }, now));
  assertEquals(decideCheckin({ ...base, status: "departed" } as FlightRow, indigo.checkin_url, now), null);
});

Deno.test("check-in: runs inside the poll cycle (after the sweeper), and an unpushed reminder is swept", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const row = await trackedFlight(h);
  h.clock.t = Date.parse(row.checkin_opens_at!);
  const stats = await runPollCycle(h.deps, 5_000);
  assertEquals(stats.checkins, 1);

  // crash between insert and push: the event stays unpushed and the sweeper delivers it with its URL
  const ev = h.repo.events.find((e) => e.type === "CHECKIN_OPEN")!;
  ev.pushed = false;
  h.pusher.sent.length = 0;
  h.clock.advance(3);
  assertEquals(await sweepUnpushedEvents(h.deps), 1);
  assertEquals(h.pusher.sent[0].data!.checkin_url, "https://www.goindigo.in/web-check-in.html");
});

Deno.test("check-in: the sweeper drops a reminder whose window already closed", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const row = await trackedFlight(h);
  h.clock.t = Date.parse(row.checkin_closes_at!) - 30 * MIN;
  assertEquals(await runCheckinReminders(h.deps), 1);
  const ev = h.repo.events.find((e) => e.type === "CHECKIN_CLOSING")!;
  ev.pushed = false; // crash before the push
  h.pusher.sent.length = 0;
  h.clock.advance(35); // now past the close
  assertEquals(await sweepUnpushedEvents(h.deps), 0);
  assertEquals(h.pusher.sent.length, 0, "no 'closes soon' after the close");
  assert(ev.pushed, "marked done so it is not retried");
});

Deno.test("check-in: full simulated day sends each reminder exactly once, before departure", async () => {
  const h = withRules("2026-11-21T00:00:00Z");
  const row = await trackedFlight(h);
  const end = Date.parse("2026-11-26T06:00:00Z");
  while (h.clock.t < end) {
    await runPollCycle(h.deps, 5_000);
    h.clock.advance(1);
  }
  const types = alerts(h).map((m) => m.data!.event_type);
  assertEquals(types.filter((t) => t === "CHECKIN_OPEN").length, 1);
  assertEquals(types.filter((t) => t === "CHECKIN_CLOSING").length, 1);
  assert(types.indexOf("CHECKIN_CLOSING") < types.indexOf("DEPARTED"), types.join(","));
  const f = (await h.repo.getFlight(row.id))!;
  assertEquals(f.next_poll_at, null);
});

Deno.test("check-in: rendered text uses departure-airport local time and never prints a placeholder", () => {
  const e = decideCheckin({
    id: "f1", carrier_iata: "6E", flight_number: "2341", dep_iata: "DEL", arr_iata: "BOM", status: "scheduled",
    sched_dep: "2026-11-25T13:20:00Z", est_dep: null, act_dep: null, tracker_count: 1, dep_terminal: "1",
    checkin_opens_at: "2026-11-23T13:20:00Z", checkin_closes_at: "2026-11-25T12:20:00Z",
    checkin_open_notified: false, checkin_closing_notified: false,
  } as FlightRow, indigo.checkin_url, new Date("2026-11-24T00:00:00Z"))!.event;
  const t = renderEvent(e, { dep: "Asia/Kolkata", arr: "Asia/Kolkata" });
  assertEquals(t.title, "Web check-in is open: 6E 2341");
  assertEquals(t.body, "DEL→BOM departs 18:50. Check in online before 17:50.");
  const closing = renderEvent({ ...e, type: "CHECKIN_CLOSING", payload: { ...e.payload, closes_at: null } }, { dep: null, arr: null });
  assert(!closing.body.includes("--:--"), closing.body);
  const noUrl = buildAlertMessage("tok", "f1", "e1", { ...e, payload: { ...e.payload, checkin_url: "http://x" } }, { dep: null, arr: null });
  assertEquals(noUrl.data!.checkin_url, undefined, "unsafe URL is dropped");
});
