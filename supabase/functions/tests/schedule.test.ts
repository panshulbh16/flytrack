import { assert, assertEquals } from "@std/assert";
import { nextPollAt } from "../_shared/schedule.ts";
import { snap } from "./helpers.ts";

const DEP = Date.parse("2026-11-25T13:20:00Z");
const ctx = (minsBeforeDep: number, over = {}) => ({
  now: new Date(DEP - minsBeforeDep * 60_000), hasPro: false, webhookActive: false, changedThisPoll: false, errorCount: 0, ...over,
});
const gapMin = (d: Date | null, now: Date) => (d!.getTime() - now.getTime()) / 60_000;

Deno.test("far future: first poll scheduled exactly at T-72h", () => {
  const c = ctx(10 * 24 * 60);
  assertEquals(nextPollAt(snap(), c)!.toISOString(), new Date(DEP - 72 * 3600_000).toISOString());
});

Deno.test("cadence tightens towards departure; never jumps over a band boundary", () => {
  assertEquals(gapMin(nextPollAt(snap(), ctx(48 * 60)), ctx(48 * 60).now), 360);
  assertEquals(gapMin(nextPollAt(snap(), ctx(24 * 60 + 30)), ctx(24 * 60 + 30).now), 30); // clamps to T-24h
  assertEquals(gapMin(nextPollAt(snap(), ctx(12 * 60)), ctx(12 * 60).now), 90);
  assertEquals(gapMin(nextPollAt(snap(), ctx(4 * 60)), ctx(4 * 60).now), 20);
  assertEquals(gapMin(nextPollAt(snap(), ctx(2 * 60)), ctx(2 * 60).now), 10);
  assertEquals(gapMin(nextPollAt(snap(), ctx(2 * 60, { hasPro: true })), ctx(2 * 60).now), 5);
});

Deno.test("gate window: free 3 min, Pro 2 min, also when past STD but not departed", () => {
  assertEquals(gapMin(nextPollAt(snap(), ctx(60)), ctx(60).now), 3);
  assertEquals(gapMin(nextPollAt(snap(), ctx(60, { hasPro: true })), ctx(60).now), 2);
  assertEquals(gapMin(nextPollAt(snap(), ctx(-20)), ctx(-20).now), 3);
});

Deno.test("delay moves the window: estimated departure drives cadence", () => {
  const s = snap({ estDep: new Date(DEP + 5 * 3600_000).toISOString() }); // 5h late
  assertEquals(gapMin(nextPollAt(s, ctx(60)), ctx(60).now), 20); // real departure still ~6h away
});

Deno.test("airborne: 15 min, then approach 3-4 min in last 40 min", () => {
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:25:00Z" });
  const mid = { ...ctx(-30) };
  assertEquals(gapMin(nextPollAt(air, mid), mid.now), 15);
  const late = ctx(-115); // ETA 15:30 = DEP+130 → 15 min before ETA
  assertEquals(gapMin(nextPollAt(air, late), late.now), 4);
});

Deno.test("belt window 2 min until belt known, then 5; stops 45 min after landing", () => {
  const landedAt = "2026-11-25T15:30:00Z";
  const l = snap({ status: "landed", actDep: "2026-11-25T13:25:00Z", actArr: landedAt });
  const now = new Date(Date.parse(landedAt) + 5 * 60_000);
  assertEquals(gapMin(nextPollAt(l, { ...ctx(0), now }), now), 2);
  assertEquals(gapMin(nextPollAt({ ...l, baggageBelt: "5" }, { ...ctx(0), now }), now), 5);
  assertEquals(nextPollAt(l, { ...ctx(0), now: new Date(Date.parse(landedAt) + 50 * 60_000) }), null);
});

Deno.test("change → burst re-check; errors → capped backoff; webhook → slower safety net", () => {
  const c = ctx(12 * 60, { changedThisPoll: true });
  assertEquals(gapMin(nextPollAt(snap(), c), c.now), 2);
  const e = ctx(12 * 60, { errorCount: 3 });
  assertEquals(gapMin(nextPollAt(snap(), e), e.now), 4);
  const w = ctx(60, { webhookActive: true });
  assertEquals(gapMin(nextPollAt(snap(), w), w.now), 5);
  const w2 = ctx(12 * 60, { webhookActive: true });
  assertEquals(gapMin(nextPollAt(snap(), w2), w2.now), 180);
});

Deno.test("inbound-only legs: coarse cadence, stop once landed", () => {
  const c = ctx(2 * 60, { inboundOnly: true });
  assertEquals(gapMin(nextPollAt(snap(), c), c.now), 15);
  const c2 = ctx(60, { inboundOnly: true });
  assertEquals(gapMin(nextPollAt(snap(), c2), c2.now), 10);
  assertEquals(nextPollAt(snap({ status: "landed", actArr: "2026-11-25T15:30:00Z" }), c2), null);
});

Deno.test("cancelled: re-check every 30 min, stop 2h after departure", () => {
  const s = snap({ status: "cancelled" });
  assertEquals(gapMin(nextPollAt(s, ctx(60)), ctx(60).now), 30);
  assertEquals(nextPollAt(s, ctx(-150)), null);
  assert(true);
});

Deno.test("review fix: hard stop — departed but never landed / endless errors stop 12h after last known time", () => {
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:25:00Z" });
  const late = { ...ctx(0), now: new Date(Date.parse("2026-11-26T04:00:00Z")) }; // ETA 15:30Z + 12.5h
  assertEquals(nextPollAt(air, late), null);
  assertEquals(nextPollAt(air, { ...late, errorCount: 5 }), null);
  const arrivedNoTime = snap({ status: "arrived" });
  assertEquals(nextPollAt(arrivedNoTime, { ...ctx(0), now: new Date(Date.parse("2026-11-25T18:00:00Z")) }), null);
});
