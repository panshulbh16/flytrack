import { assert, assertEquals, assertRejects } from "@std/assert";
import { runPollCycle, SearchError, searchFlight, parseFlightNumber, handleWebhook } from "../_shared/engine.ts";
import { MockProvider } from "../_shared/providers/mock.ts";
import { makeDeps, runUntil } from "./helpers.ts";
import type { FlightDataProvider } from "../_shared/providers/provider.ts";

const DATE = "2026-11-25";
const START = "2026-11-21T00:00:00Z"; // > 72h before every mock departure on DATE

/** Simulate one flight end-to-end: search → free+pro users track → poll every 30s until done. */
async function simulateFlight(n: number) {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "searcher", { flight: `6E ${n}`, date: DATE });
  h.repo.track(row.id, { userId: "free", isPro: false, prefs: {}, devices: [{ id: "d1", token: "tok-free-xxxxxxxxxxxxxxxx" }] });
  h.repo.track(row.id, { userId: "pro", isPro: true, prefs: {}, devices: [{ id: "d2", token: "tok-pro-xxxxxxxxxxxxxxxxx" }] });
  const callsBefore = h.provider.calls;
  await runUntil(h, "2026-11-26T06:00:00Z", 0.5, (d) => runPollCycle(d, 5_000));
  const alertsFor = (tok: string) =>
    h.pusher.sent.filter((m) => m.token === tok && m.data?.kind === "alert").map((m) => m.data!.event_type);
  return {
    h, row,
    free: alertsFor("tok-free-xxxxxxxxxxxxxxxx"),
    pro: alertsFor("tok-pro-xxxxxxxxxxxxxxxxx"),
    calls: h.provider.calls - callsBefore,
    final: (await h.repo.getFlight(row.id))!,
  };
}

const BASE = ["GATE_ASSIGNED", "BOARDING", "DEPARTED", "LANDED", "BELT_ASSIGNED"];

Deno.test("scenario 0 (on time): full journey, each alert exactly once, polling stops", async () => {
  const r = await simulateFlight(2340);
  assertEquals(r.free, BASE);
  assertEquals(r.pro, BASE);
  assertEquals(r.final.next_poll_at, null, "polling stopped after belt window");
  assertEquals(r.final.status, "arrived");
  // Budget: one free+one Pro tracker, incl. the inbound leg for "Where's my plane".
  assert(r.calls <= 180, `API calls per flight too high: ${r.calls}`);
  console.log(`  on-time flight with a Pro tracker: ${r.calls} provider calls total (shared by ALL trackers)`);
});

Deno.test("scenario 3 (delayed 45 → 70)", async () => {
  const r = await simulateFlight(2343);
  assertEquals(r.free, ["DELAY", "GATE_ASSIGNED", "DELAY", ...BASE.slice(1)]);
  const delays = r.h.pusher.sent.filter((m) => m.data?.event_type === "DELAY" && m.token.startsWith("tok-free"));
  assert(delays[0].data!.title.includes("45"), delays[0].data!.title);
  assert(delays[1].data!.title.includes("70"), delays[1].data!.title);
});

Deno.test("scenario 4 (delay reduced)", async () => {
  const r = await simulateFlight(2344);
  // delay 60 at T-4h, gate at T-2h, reduced to 25 at T-90m
  assertEquals(r.free.slice(0, 3), ["DELAY", "GATE_ASSIGNED", "DELAY_REDUCED"]);
});

Deno.test("scenario 5 (gate change) — pushed within one poll interval", async () => {
  const r = await simulateFlight(2345);
  assertEquals(r.free, ["GATE_ASSIGNED", "GATE_CHANGE", ...BASE.slice(1)]);
  const ev = r.h.repo.events.find((e) => e.type === "GATE_CHANGE")!;
  const sched = Date.parse(r.final.sched_dep!);
  const latencyMin = (Date.parse(ev.at) - (sched - 50 * 60_000)) / 60_000; // mock changes gate at T-50m
  console.log(`  gate change latency: ${latencyMin.toFixed(1)} min (Pro cadence in gate window = 2 min)`);
  assert(latencyMin <= 2.5, `gate change too slow: ${latencyMin} min`);
  const msg = r.h.pusher.sent.find((m) => m.data?.event_type === "GATE_CHANGE")!;
  const n = msg.android!.notification as Record<string, string>;
  assertEquals(n.channel_id, "flight_critical");
  assertEquals(msg.android!.priority, "HIGH");
});

Deno.test("scenario 6 (cancelled) — single alert, polling winds down", async () => {
  const r = await simulateFlight(2346);
  assertEquals(r.free, ["CANCELLED"]);
  assertEquals(r.final.status, "cancelled");
  assertEquals(r.final.next_poll_at, null);
});

Deno.test("scenario 7 (inbound late) — Pro warned BEFORE the airline delay; free user is not", async () => {
  const r = await simulateFlight(2347);
  assert(r.pro.includes("INBOUND_LATE"), `pro alerts: ${r.pro}`);
  assert(!r.free.includes("INBOUND_LATE"), "free user must not get Pro alert");
  assert(r.pro.indexOf("INBOUND_LATE") < r.pro.indexOf("DELAY"), "inbound warning must come before the official delay");
  const inboundEv = r.h.repo.events.find((e) => e.type === "INBOUND_LATE")!;
  const delayEv = r.h.repo.events.find((e) => e.type === "DELAY")!;
  const leadMin = (Date.parse(delayEv.at) - Date.parse(inboundEv.at)) / 60_000;
  console.log(`  inbound-late warning arrived ${leadMin.toFixed(0)} min before the airline's delay`);
  assert(r.final.inbound_flight_id, "inbound leg linked");
  assertEquals((r.final.prediction as Record<string, unknown>).inboundReg, "VT-M2347");
});

Deno.test("scenario 8 (belt change) and 9 (diverted)", async () => {
  const b = await simulateFlight(2348);
  assertEquals(b.free.slice(-2), ["BELT_ASSIGNED", "BELT_CHANGE"]);
  const d = await simulateFlight(2349);
  assert(d.free.includes("DIVERTED"), `diverted alerts: ${d.free}`);
  assertEquals(d.final.arr_iata, "BLR"); // HYD→MAA diverted to BLR
});

Deno.test("cost: free-only flight stays within budget", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: DATE });
  h.repo.track(row.id, { userId: "f", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok-f-xxxxxxxxxxxxxxxxxxxx" }] });
  await runUntil(h, "2026-11-26T06:00:00Z", 0.5, (d) => runPollCycle(d, 5_000));
  console.log(`  on-time flight, free trackers only: ${h.provider.calls} provider calls`);
  assert(h.provider.calls <= 110, `free flight calls: ${h.provider.calls}`);
});

Deno.test("muted alert types are respected per user", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E2340", date: DATE });
  h.repo.track(row.id, { userId: "m", isPro: false, prefs: { DEPARTED: false, LANDED: false }, devices: [{ id: "d", token: "tok-m-xxxxxxxxxxxxxxxxxxxx" }] });
  await runUntil(h, "2026-11-26T06:00:00Z", 1, (d) => runPollCycle(d, 5_000));
  const got = h.pusher.sent.filter((m) => m.data?.kind === "alert").map((m) => m.data!.event_type);
  assertEquals(got, ["GATE_ASSIGNED", "BOARDING", "BELT_ASSIGNED"]);
});

Deno.test("untracked flights are never polled (cost)", async () => {
  const h = makeDeps(START);
  await searchFlight(h.deps, "u", { flight: "6E 2341", date: DATE });
  const before = h.provider.calls;
  await runUntil(h, "2026-11-26T06:00:00Z", 5, (d) => runPollCycle(d, 5_000));
  assertEquals(h.provider.calls, before);
});

Deno.test("search: input parsing, cache, rate limit, not found", async () => {
  assertEquals(parseFlightNumber("6e-0234"), { carrier: "6E", number: "234" });
  assertEquals(parseFlightNumber("AI 101A"), { carrier: "AI", number: "101A" });
  assertEquals(parseFlightNumber("12 345"), null);
  const h = makeDeps(START);
  await searchFlight(h.deps, "u1", { flight: "QP 1372", date: DATE });
  const c = h.provider.calls;
  await searchFlight(h.deps, "u2", { flight: "QP1372", date: DATE });
  assertEquals(h.provider.calls, c, "second search served from cache");
  await assertRejects(() => searchFlight(h.deps, "u1", { flight: "6E 9999", date: DATE }), SearchError, "No flight");
  await assertRejects(() => searchFlight(h.deps, "u1", { flight: "6E 1", date: "2030-01-01" }), SearchError, "Date");
  // rate limit: provider calls are logged with userId by the per-request provider (simulate)
  for (let i = 0; i < 30; i++) await h.repo.logProviderCall({ provider: "mock", endpoint: "x", status: 200, units: 1, durationMs: 0, userId: "abuser" });
  await assertRejects(() => searchFlight(h.deps, "abuser", { flight: "SG 8", date: DATE }), SearchError, "Too many");
});

Deno.test("provider outage → backoff, then recovers without duplicate alerts", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2345", date: DATE });
  h.repo.track(row.id, { userId: "x", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok-x-xxxxxxxxxxxxxxxxxxxx" }] });
  const real = h.deps.provider;
  let down = false;
  const flaky: FlightDataProvider = {
    name: "mock", supportsWebhooks: false,
    getFlight: (k) => (down ? Promise.reject(new Error("503")) : real.getFlight(k)),
    getFlightsByRegistration: (r, d) => real.getFlightsByRegistration(r, d),
  };
  h.deps.provider = flaky;
  // outage from T-60m to T-40m (straddles the gate change at T-50m)
  const S = Date.parse(row.sched_dep!);
  await runUntil(h, new Date(S - 60 * 60_000).toISOString(), 1, (d) => runPollCycle(d, 5_000));
  down = true;
  await runUntil(h, new Date(S - 40 * 60_000).toISOString(), 0.5, (d) => runPollCycle(d, 5_000));
  const f = (await h.repo.getFlight(row.id))!;
  assert(f.poll_error_count > 0 && f.last_error, "errors recorded");
  down = false;
  await runUntil(h, "2026-11-26T06:00:00Z", 1, (d) => runPollCycle(d, 5_000));
  const got = h.pusher.sent.filter((m) => m.data?.kind === "alert").map((m) => m.data!.event_type);
  assertEquals(got, ["GATE_ASSIGNED", "GATE_CHANGE", "BOARDING", "DEPARTED", "LANDED", "BELT_ASSIGNED"]);
});

Deno.test("webhook + poll race on the same change → exactly one push", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2345", date: DATE });
  h.repo.track(row.id, { userId: "x", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok-x-xxxxxxxxxxxxxxxxxxxx" }] });
  const S = Date.parse(row.sched_dep!);
  await runUntil(h, new Date(S - 49 * 60_000).toISOString(), 1, (d) => runPollCycle(d, 5_000));
  // provider "webhook" delivering the same snapshot the poll already ingested
  const snaps = await new MockProvider(h.clock.now).getFlight({ carrier: "6E", number: "2345", date: DATE });
  h.deps.provider = { ...h.deps.provider, name: "mock", supportsWebhooks: true, parseWebhook: () => snaps } as FlightDataProvider;
  const res = await handleWebhook(h.deps, {});
  assertEquals(res.matched, 1);
  const gateChanges = h.pusher.sent.filter((m) => m.data?.event_type === "GATE_CHANGE");
  assertEquals(gateChanges.length, 1);
});

Deno.test("invalid FCM token is deleted", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: DATE });
  h.repo.track(row.id, { userId: "x", isPro: false, prefs: {}, devices: [{ id: "d", token: "dead-token-xxxxxxxxxxxxxx" }] });
  h.deps.pusher = { send: () => Promise.resolve({ ok: false, invalidToken: true, error: "404 UNREGISTERED" }) };
  await runUntil(h, "2026-11-25T23:00:00Z", 2, (d) => runPollCycle(d, 5_000));
  assertEquals((await h.repo.getRecipients(row.id)).length, 0);
});

Deno.test("review fix: stale webhook (older providerUpdatedAt) cannot overwrite newer data or alert", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2345", date: DATE });
  h.repo.track(row.id, { userId: "x", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok-x-xxxxxxxxxxxxxxxxxxxx" }] });
  const S = Date.parse(row.sched_dep!);
  await runUntil(h, new Date(S - 100 * 60_000).toISOString(), 1, (d) => runPollCycle(d, 5_000)); // gate known
  const old = (await new MockProvider(() => new Date(S - 200 * 60_000)).getFlight({ carrier: "6E", number: "2345", date: DATE }))[0];
  h.deps.provider = { ...h.deps.provider, name: "mock", supportsWebhooks: true, parseWebhook: () => [old] } as FlightDataProvider;
  const before = h.pusher.sent.length;
  await handleWebhook(h.deps, {});
  assertEquals(h.pusher.sent.length, before, "no push from stale data");
  assert((await h.repo.getFlight(row.id))!.dep_gate, "gate not wiped by stale snapshot");
});

Deno.test("review fix: event inserted but never pushed (crash) is delivered by the sweeper", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: DATE });
  h.repo.track(row.id, { userId: "x", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok-x-xxxxxxxxxxxxxxxxxxxx" }] });
  await h.repo.insertEventIfNew(row.id, {
    type: "GATE_CHANGE", severity: "critical", proOnly: false, oldValue: "1", newValue: "2", dedupeKey: "crash-1",
    payload: { flight: "6E 2340", dep: "BLR", arr: "DEL" },
  });
  h.clock.advance(3);
  await runPollCycle(h.deps, 5_000);
  assert(h.pusher.sent.some((m) => m.data?.event_type === "GATE_CHANGE"), "swept");
  assert(h.repo.events.every((e) => e.pushed));
});
