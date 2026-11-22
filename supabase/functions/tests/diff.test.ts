import { assertEquals } from "@std/assert";
import { changedFields, evaluateAlerts, evaluateInbound, snapshotState } from "../_shared/diff.ts";
import { snap } from "./helpers.ts";

const ID = "f1";
const types = (r: ReturnType<typeof evaluateAlerts>) => r.events.map((e) => e.type);

Deno.test("first sighting = baseline, no alerts", () => {
  const r = evaluateAlerts(ID, {}, null, snap({ depGate: "12", estDep: "2026-11-25T14:20:00Z" }));
  assertEquals(r.events, []);
  assertEquals(r.state.depGate, "12");
  assertEquals(r.state.depDelayMin, 60);
});

Deno.test("gate assigned, then changed; flapping to null and back is silent", () => {
  const s0 = snap();
  let st = snapshotState(s0);
  let r = evaluateAlerts(ID, st, s0, snap({ depGate: "12" }));
  assertEquals(types(r), ["GATE_ASSIGNED"]);
  st = r.state;
  r = evaluateAlerts(ID, st, snap({ depGate: "12" }), snap({ depGate: null }));
  assertEquals(types(r), []);
  r = evaluateAlerts(ID, r.state, snap({ depGate: null }), snap({ depGate: "12" }));
  assertEquals(types(r), [], "same gate as last told → no alert");
  r = evaluateAlerts(ID, r.state, snap({ depGate: "12" }), snap({ depGate: "29" }));
  assertEquals(types(r), ["GATE_CHANGE"]);
  assertEquals(r.events[0].oldValue, "12");
  assertEquals(r.events[0].severity, "critical");
});

Deno.test("delay alerts only on >=15 min steps; reduction alert; small jitter ignored", () => {
  const s0 = snap();
  let st = snapshotState(s0);
  const est = (m: number) => new Date(Date.parse(s0.schedDep!) + m * 60_000).toISOString();
  let r = evaluateAlerts(ID, st, s0, snap({ estDep: est(10) }));
  assertEquals(types(r), []);
  r = evaluateAlerts(ID, r.state, s0, snap({ estDep: est(20) }));
  assertEquals(types(r), ["DELAY"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ estDep: est(30) }));
  assertEquals(types(r), [], "+10 since last told");
  r = evaluateAlerts(ID, r.state, s0, snap({ estDep: est(40) }));
  assertEquals(types(r), ["DELAY"]);
  assertEquals(r.events[0].payload.delayMin, 40);
  r = evaluateAlerts(ID, r.state, s0, snap({ estDep: est(0) }));
  assertEquals(types(r), ["DELAY_REDUCED"]);
  st = r.state;
  assertEquals(st.depDelayMin, 0);
});

Deno.test("boarding → departed → landed → belt → belt change, each once", () => {
  const s0 = snap({ depGate: "12" });
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ depGate: "12", status: "boarding" }));
  assertEquals(types(r), ["BOARDING"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ depGate: "12", status: "boarding" }));
  assertEquals(types(r), []);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "en_route", actDep: "2026-11-25T13:30:00Z" }));
  assertEquals(types(r), ["DEPARTED"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "landed", actDep: "x", actArr: "2026-11-25T15:28:00Z" }));
  assertEquals(types(r), ["LANDED"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "arrived", actDep: "x", actArr: "2026-11-25T15:28:00Z", baggageBelt: "5" }));
  assertEquals(types(r), ["BELT_ASSIGNED"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "arrived", actDep: "x", actArr: "2026-11-25T15:28:00Z", baggageBelt: "7" }));
  assertEquals(types(r), ["BELT_CHANGE"]);
});

Deno.test("departure without seeing boarding → no late boarding alert", () => {
  const s0 = snap();
  const r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ status: "departed", actDep: "2026-11-25T13:30:00Z" }));
  assertEquals(types(r), ["DEPARTED"]);
  assertEquals(evaluateAlerts(ID, r.state, s0, snap({ status: "boarding" })).events.length, 0);
});

Deno.test("cancel → reinstate → cancel again produces 3 distinct alerts", () => {
  const s0 = snap();
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ status: "cancelled" }));
  assertEquals(types(r), ["CANCELLED"]);
  const k1 = r.events[0].dedupeKey;
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "scheduled" }));
  assertEquals(types(r), ["REINSTATED"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "cancelled" }));
  assertEquals(types(r), ["CANCELLED"]);
  if (r.events[0].dedupeKey === k1) throw new Error("second cancel must have a new dedupe key");
});

Deno.test("diversion detected by destination change while airborne", () => {
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:30:00Z" });
  const st = { ...snapshotState(air) };
  const r = evaluateAlerts(ID, st, air, { ...air, arrIata: "AMD" });
  assertEquals(types(r), ["DIVERTED"]);
});

Deno.test("changedFields ignores second-level jitter and live position", () => {
  const a = snap({ estDep: "2026-11-25T14:00:00Z" });
  const b = snap({ estDep: "2026-11-25T14:00:20Z", live: { lat: 1, lon: 2, altFt: 1, headingDeg: 1, speedKts: 1, at: "" } });
  assertEquals(changedFields(a, b), []);
  assertEquals(changedFields(a, snap({ estDep: "2026-11-25T14:05:00Z" })), ["estDep"]);
});

Deno.test("inbound late → Pro event once per 15-min bucket", () => {
  const mine = snap();
  const inbound = snap({ number: "2342", depIata: "BOM", arrIata: "DEL", schedArr: "2026-11-25T12:25:00Z" });
  const now = new Date("2026-11-25T10:00:00Z");
  // inbound ETA 13:05 + 35 min turn = 13:40 vs our 13:20 → 20 min late
  let r = evaluateInbound("f1", null, mine, { ...inbound, estArr: "2026-11-25T13:05:00Z" }, now);
  assertEquals(r.event?.type, "INBOUND_LATE");
  assertEquals(r.event?.proOnly, true);
  assertEquals(r.prediction.lateMin, 20);
  assertEquals(r.prediction.predictedDep, "2026-11-25T13:40:00.000Z");
  r = evaluateInbound("f1", r.prediction, mine, { ...inbound, estArr: "2026-11-25T13:08:00Z" }, now);
  assertEquals(r.event, null, "same 30-min bucket → no repeat");
  r = evaluateInbound("f1", r.prediction, mine, { ...inbound, estArr: "2026-11-25T13:30:00Z" }, now);
  assertEquals(r.event?.newValue, "45");
  const ok = evaluateInbound("f1", null, mine, { ...inbound, estArr: "2026-11-25T12:30:00Z" }, now);
  assertEquals(ok.event, null);
  assertEquals(ok.prediction.lateMin, 0);
});

Deno.test("review fix: a value that comes back still alerts (gate 12→15→12→15, delay 60→40→60)", () => {
  const s0 = snap();
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ depGate: "12" }));
  const keys = new Set(r.events.map((e) => e.dedupeKey));
  for (const g of ["15", "12", "15"]) {
    r = evaluateAlerts(ID, r.state, s0, snap({ depGate: g }));
    assertEquals(types(r), ["GATE_CHANGE"], `gate → ${g}`);
    for (const e of r.events) {
      if (keys.has(e.dedupeKey)) throw new Error(`dedupe collision ${e.dedupeKey}`);
      keys.add(e.dedupeKey);
    }
  }
  const est = (m: number) => new Date(Date.parse(s0.schedDep!) + m * 60_000).toISOString();
  let d = evaluateAlerts(ID, snapshotState(s0), s0, snap({ estDep: est(60) }));
  const k1 = d.events[0].dedupeKey;
  d = evaluateAlerts(ID, d.state, s0, snap({ estDep: est(40) }));
  assertEquals(types(d), ["DELAY_REDUCED"]);
  d = evaluateAlerts(ID, d.state, s0, snap({ estDep: est(60) }));
  assertEquals(types(d), ["DELAY"]);
  if (d.events[0].dedupeKey === k1) throw new Error("delay key collided with the first one");
});

Deno.test("review fix: two racing ingests of the SAME snapshot still produce the same keys", () => {
  const s0 = snap({ depGate: "12" });
  const st = snapshotState(s0);
  const a = evaluateAlerts(ID, st, s0, snap({ depGate: "15" }));
  const b = evaluateAlerts(ID, st, s0, snap({ depGate: "15" }));
  assertEquals(a.events[0].dedupeKey, b.events[0].dedupeKey);
});

Deno.test("review fix: Canceled ↔ CanceledUncertain wobble does not send 'back on' + 'cancelled' again", () => {
  const s0 = snap();
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ status: "cancelled" }));
  assertEquals(types(r), ["CANCELLED"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "unknown" })); // CanceledUncertain maps to unknown
  assertEquals(types(r), []);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "cancelled" }));
  assertEquals(types(r), []);
  r = evaluateAlerts(ID, r.state, s0, snap({ status: "scheduled" })); // real reinstatement
  assertEquals(types(r), ["REINSTATED"]);
});

Deno.test("review fix: airline retime (reschedule) alerts, without a follow-up 'delay reduced'", () => {
  const s0 = snap({ estDep: new Date(Date.parse("2026-11-25T13:20:00Z") + 60 * 60_000).toISOString() });
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ schedDep: "2026-11-25T14:20:00Z", estDep: "2026-11-25T14:20:00Z" }));
  assertEquals(types(r), ["SCHEDULE_CHANGE"]);
  assertEquals(r.events[0].payload.shiftMin, 60);
  r = evaluateAlerts(ID, r.state, s0, snap({ schedDep: "2026-11-25T14:20:00Z", estDep: "2026-11-25T14:20:00Z" }));
  assertEquals(types(r), []);
  const earlier = evaluateAlerts(ID, snapshotState(snap()), snap(), snap({ schedDep: "2026-11-25T12:50:00Z" }));
  assertEquals(earlier.events[0].payload.direction, "earlier");
});

Deno.test("review fix: diversion with unknown new destination doesn't claim the original one", () => {
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:30:00Z" });
  const r = evaluateAlerts(ID, snapshotState(air), air, { ...air, status: "diverted" });
  assertEquals(types(r), ["DIVERTED"]);
  assertEquals(r.events[0].payload.newArr, null);
  assertEquals(r.events[0].payload.origArr, "BOM");
});
