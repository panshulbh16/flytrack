// P3 alerts (docs/P3_CONTRACTS.md A2, A5, B4, C): GATE_CLOSED / TAKEOFF / AT_GATE milestones, AIRCRAFT_SWAP
// (Pro, only before departure), lazy aircraft lookup for Pro-tracked flights, CONNECTION_AT_RISK transitions and
// fan-out, and how the new events are delivered (channel, tag).
import { assert, assertEquals } from "@std/assert";
import { evaluateAlerts, snapshotState } from "../_shared/diff.ts";
import {
  ingest, maybeFetchAircraft, rowToSnapshot, runConnectionAlerts, searchFlight, sweepUnpushedEvents,
} from "../_shared/engine.ts";
import { type ConnectionCandidate, decideConnection, groupConnections, riskFromBuffer } from "../_shared/connections.ts";
import { buildAlertMessage, isCriticalAlert } from "../_shared/notify.ts";
import { EVENT_TYPES, type FlightSnapshot } from "../_shared/types.ts";
import { makeDeps, snap } from "./helpers.ts";

const ID = "f1";
const types = (r: ReturnType<typeof evaluateAlerts>) => r.events.map((e) => e.type);
const IST = { dep: "Asia/Kolkata", arr: "Asia/Kolkata" };

Deno.test("event types: the five P3 types are appended in the contract order", () => {
  assertEquals(EVENT_TYPES.slice(-5), ["GATE_CLOSED", "TAKEOFF", "AT_GATE", "AIRCRAFT_SWAP", "CONNECTION_AT_RISK"]);
  assertEquals(EVENT_TYPES.slice(0, 2), ["DELAY", "DELAY_REDUCED"], "older types keep their place");
});

// ---------------------------------------------------------------- timeline milestones

Deno.test("GATE_CLOSED: once, info; jumping straight to gate_closed sends boarding too; never after departure", () => {
  const s0 = snap({ depGate: "12" });
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ depGate: "12", status: "boarding" }));
  assertEquals(types(r), ["BOARDING"]);
  r = evaluateAlerts(ID, r.state, s0, snap({ depGate: "12", status: "gate_closed" }));
  assertEquals(types(r), ["GATE_CLOSED"]);
  assertEquals(r.events[0].severity, "info");
  assertEquals(r.events[0].payload.gate, "12");
  assertEquals(r.events[0].dedupeKey, "f1:GATE_CLOSED");
  assertEquals(types(evaluateAlerts(ID, r.state, s0, snap({ depGate: "12", status: "gate_closed" }))), []);

  r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ status: "gate_closed" }));
  assertEquals(types(r), ["BOARDING", "GATE_CLOSED"]);

  r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ status: "departed", actDep: "2026-11-25T13:25:00Z" }));
  assertEquals(types(r), ["DEPARTED"]);
  assert(r.state.milestones!.includes("GATE_CLOSED"), "no late gate-closed alert");
});

Deno.test("TAKEOFF when the runway time is known; skipped when it only shows up after landing", () => {
  const s0 = snap();
  const off = snap({ status: "departed", actDep: "2026-11-25T13:25:00Z", depOffblockAt: "2026-11-25T13:25:00Z" });
  let r = evaluateAlerts(ID, snapshotState(s0), s0, off);
  assertEquals(types(r), ["DEPARTED"]);
  const up = { ...off, status: "en_route" as const, depRunwayAt: "2026-11-25T13:41:00Z", estArr: "2026-11-25T15:35:00Z" };
  r = evaluateAlerts(ID, r.state, off, up);
  assertEquals(types(r), ["TAKEOFF"]);
  assertEquals(r.events[0].payload.runwayAt, "2026-11-25T13:41:00Z");
  assertEquals(r.events[0].newValue, "2026-11-25T13:41:00Z");
  assertEquals(types(evaluateAlerts(ID, r.state, up, up)), []);

  // runway time first seen together with the landing
  r = evaluateAlerts(ID, snapshotState(s0), s0, off);
  r = evaluateAlerts(ID, r.state, off, { ...off, status: "landed", depRunwayAt: "2026-11-25T13:41:00Z", actArr: "2026-11-25T15:30:00Z" });
  assertEquals(types(r), ["LANDED"]);
  assert(r.state.milestones!.includes("TAKEOFF"));
});

Deno.test("AT_GATE once the on-block time is known (with gate and belt in the payload)", () => {
  const landed = snap({ status: "landed", actDep: "2026-11-25T13:25:00Z", actArr: "2026-11-25T15:30:00Z", arrRunwayAt: "2026-11-25T15:30:00Z" });
  const st = snapshotState(landed);
  const parked = { ...landed, status: "arrived" as const, arrOnblockAt: "2026-11-25T15:38:00Z", arrGate: "B4" };
  const r = evaluateAlerts(ID, st, landed, parked);
  assertEquals(types(r), ["AT_GATE"]);
  assertEquals(r.events[0].payload.onblockAt, "2026-11-25T15:38:00Z");
  assertEquals(r.events[0].payload.arrGate, "B4");
  assertEquals(types(evaluateAlerts(ID, r.state, parked, parked)), []);
});

Deno.test("baseline: a flight first seen in the air or parked raises no milestone alerts later", () => {
  const s = snap({
    status: "arrived", actDep: "2026-11-25T13:25:00Z", depRunwayAt: "2026-11-25T13:38:00Z", actArr: "2026-11-25T15:30:00Z",
    arrOnblockAt: "2026-11-25T15:38:00Z",
  });
  const st = snapshotState(s);
  for (const m of ["BOARDING", "GATE_CLOSED", "DEPARTED", "TAKEOFF", "LANDED", "AT_GATE"]) assert(st.milestones!.includes(m), m);
  assertEquals(types(evaluateAlerts(ID, st, s, s)), []);
});

Deno.test("deploy: state from before P3 gets no TAKEOFF / AT_GATE for milestones the flight already passed", () => {
  // A state persisted before P3 (no aircraftReg key), flight already airborne with DEPARTED sent.
  const air = snap({ status: "en_route", actDep: "2026-11-25T13:25:00Z", depRunwayAt: "2026-11-25T13:38:00Z" });
  const legacy = { depGate: null, milestones: ["BOARDING", "DEPARTED"], alertSeq: 0 };
  let r = evaluateAlerts(ID, legacy, air, air);
  assertEquals(types(r), [], "no late takeoff alert after the deploy");
  assert(r.state.milestones!.includes("TAKEOFF") && r.state.milestones!.includes("GATE_CLOSED"));
  assert("aircraftReg" in r.state, "the state is now marked as P3");
  // a legacy state that has departed but whose runway time arrives later still gets TAKEOFF
  const off = snap({ status: "departed", actDep: "2026-11-25T13:25:00Z" });
  r = evaluateAlerts(ID, { depGate: null, milestones: ["BOARDING", "DEPARTED"], alertSeq: 0 }, off, off);
  assertEquals(types(r), []);
  r = evaluateAlerts(ID, r.state, off, { ...off, depRunwayAt: "2026-11-25T13:40:00Z" });
  assertEquals(types(r), ["TAKEOFF"]);
  // landed before the deploy: on-block already known → no AT_GATE
  const parked = snap({ status: "arrived", actDep: "2026-11-25T13:25:00Z", actArr: "2026-11-25T15:30:00Z", arrOnblockAt: "2026-11-25T15:36:00Z" });
  r = evaluateAlerts(ID, { depGate: null, milestones: ["BOARDING", "DEPARTED", "LANDED"], alertSeq: 0 }, parked, parked);
  assertEquals(types(r), []);
});

// ---------------------------------------------------------------- aircraft swap

Deno.test("AIRCRAFT_SWAP: Pro + critical, only when a known tail changes before departure", () => {
  const s0 = snap({ aircraftReg: null, aircraftModel: null });
  let r = evaluateAlerts(ID, snapshotState(s0), s0, snap({ aircraftReg: "VT-ISR", aircraftModel: "A320neo" }));
  assertEquals(types(r), [], "the first tail is no swap");
  r = evaluateAlerts(ID, r.state, s0, snap({ aircraftReg: "vt-isr", aircraftModel: "A320neo" }));
  assertEquals(types(r), [], "same tail, other case");
  r = evaluateAlerts(ID, r.state, s0, snap({ aircraftReg: "VT-IJK", aircraftModel: "A321neo" }));
  assertEquals(types(r), ["AIRCRAFT_SWAP"]);
  const e = r.events[0];
  assertEquals([e.severity, e.proOnly, e.oldValue, e.newValue], ["critical", true, "VT-ISR", "VT-IJK"]);
  assertEquals(e.payload.old_reg, "VT-ISR");
  assertEquals(e.payload.new_reg, "VT-IJK");
  assertEquals(e.payload.old_model, "A320neo");
  assertEquals(e.payload.new_model, "A321neo");
  const back = evaluateAlerts(ID, r.state, s0, snap({ aircraftReg: "VT-ISR", aircraftModel: "A320neo" }));
  assertEquals(types(back), ["AIRCRAFT_SWAP"]);
  assert(back.events[0].dedupeKey !== e.dedupeKey, "swapping back is news again");

  const flying = snap({ aircraftReg: "VT-XYZ", status: "en_route", actDep: "2026-11-25T13:25:00Z" });
  const after = evaluateAlerts(ID, r.state, s0, flying);
  assertEquals(types(after).filter((t) => t === "AIRCRAFT_SWAP"), [], "after departure the tail is what flew");
});

Deno.test("engine: the swap reaches Pro trackers only, and the new tail is looked up", async () => {
  const h = makeDeps("2026-11-21T00:00:00Z");
  const endpoints: string[] = [];
  h.provider.onCall = (i) => endpoints.push(i.endpoint);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: "2026-11-25" });
  h.repo.track(row.id, { userId: "free", isPro: false, prefs: {}, devices: [{ id: "d1", token: "tok-free-xxxxxxxxxxxxxxx" }] });
  h.repo.track(row.id, { userId: "pro", isPro: true, prefs: {}, devices: [{ id: "d2", token: "tok-pro-xxxxxxxxxxxxxxxx" }] });
  const at = async (over: Partial<FlightSnapshot>) => {
    const cur = (await h.repo.getFlight(row.id))!;
    return (await ingest(h.deps, cur, { ...rowToSnapshot(cur), providerUpdatedAt: null, ...over }, "poll")).row;
  };
  let r = await at({ aircraftReg: "VT-AAA", aircraftModel: "Airbus A320neo" });
  assertEquals(await maybeFetchAircraft(h.deps, r), true);
  assertEquals(h.repo.aircraft.get("VT-AAA")?.icao_type, "A20N");
  assertEquals(await maybeFetchAircraft(h.deps, r), false, "cached for 30 days");
  r = await at({ aircraftReg: "VT-BBB", aircraftModel: "Airbus A321neo" });
  const alerts = h.pusher.sent.filter((m) => m.data?.event_type === "AIRCRAFT_SWAP");
  assertEquals(alerts.map((m) => m.token), ["tok-pro-xxxxxxxxxxxxxxxx"]);
  assertEquals(alerts[0].data!.title, "Aircraft change: 6E 2340");
  assertEquals((alerts[0].android!.notification as Record<string, string>).channel_id, "flight_critical");
  assertEquals(await maybeFetchAircraft(h.deps, r), true, "a new tail is a new lookup");
  assertEquals(endpoints.filter((e) => e === "aircraft_by_registration").length, 2);
});

Deno.test("engine: aircraft lookup only for Pro-tracked flights", async () => {
  const h = makeDeps("2026-11-21T00:00:00Z");
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: "2026-11-25" });
  h.repo.track(row.id, { userId: "free", isPro: false, prefs: {}, devices: [] });
  const f = (await h.repo.getFlight(row.id))!;
  assertEquals(await maybeFetchAircraft(h.deps, { ...f, aircraft_reg: "VT-M2340" }), false);
  assertEquals(await maybeFetchAircraft(h.deps, { ...f, aircraft_reg: null, pro_tracker_count: 1 }), false);
  assertEquals(h.repo.aircraft.size, 0);
});

// ---------------------------------------------------------------- connections

const cand = (over: Partial<ConnectionCandidate> = {}): ConnectionCandidate => ({
  user_id: "u1", in_flight_id: "fin", out_flight_id: "fout", airport: "DEL", out_flight: "6E 512",
  out_dep: "2026-11-25T16:30:00Z", layover_min: 110, mct_min: 120, buffer_min: -10, risk: "at_risk",
  last_risk: "ok", gen: 0, ...over,
});

Deno.test("connection risk transitions: only ok/tight → at_risk alerts, with a fresh generation", () => {
  assertEquals([riskFromBuffer(30), riskFromBuffer(29), riskFromBuffer(0), riskFromBuffer(-1)], ["ok", "tight", "tight", "at_risk"]);
  const fire = decideConnection(cand({ last_risk: "ok" }));
  assertEquals(fire.event?.type, "CONNECTION_AT_RISK");
  assertEquals(fire.event?.dedupeKey, "fin:fout:CONN:0");
  assertEquals([fire.event?.severity, fire.event?.proOnly], ["critical", true]);
  assertEquals(fire.event?.payload, {
    out_flight: "6E 512", out_dep: "2026-11-25T16:30:00Z", airport: "DEL", buffer_min: -10, layover_min: 110,
    mct_min: 120, out_flight_id: "fout",
  });
  assertEquals([fire.risk, fire.gen], ["at_risk", 1]);
  assertEquals(decideConnection(cand({ last_risk: "tight", gen: 3 })).event?.dedupeKey, "fin:fout:CONN:3");
  for (const c of [
    cand({ last_risk: null }), // first seen at risk
    cand({ last_risk: "at_risk" }),
    cand({ last_risk: "ok", risk: "tight", buffer_min: 10 }),
    cand({ last_risk: "tight", risk: "missed" }),
    cand({ last_risk: "at_risk", risk: "ok", buffer_min: 40 }),
  ]) {
    const d = decideConnection(c);
    assertEquals(d.event, null, JSON.stringify(c));
    assertEquals([d.risk, d.gen], [c.risk, c.gen]);
  }
  const g = groupConnections([cand(), cand({ user_id: "u2" }), cand({ out_flight_id: "other" })]);
  assertEquals(g.map((x) => [...x.users]), [["u1", "u2"], ["u1"]]);
});

Deno.test("runConnectionAlerts: alerts the users who have the connection, once per transition", async () => {
  const h = makeDeps("2026-11-21T00:00:00Z");
  const [inRow] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: "2026-11-25" });
  const [outRow] = await searchFlight(h.deps, "u", { flight: "6E 2347", date: "2026-11-25" });
  const dev = (id: string) => [{ id, token: `tok-${id}-xxxxxxxxxxxxxxxxxx` }];
  h.repo.track(inRow.id, { userId: "a", isPro: true, prefs: {}, devices: dev("a") });
  h.repo.track(inRow.id, { userId: "b", isPro: true, prefs: {}, devices: dev("b") });
  h.repo.track(inRow.id, { userId: "c", isPro: true, prefs: {}, devices: dev("c") }); // no connection
  h.pusher.sent.length = 0;
  const base = {
    in_flight_id: inRow.id, out_flight_id: outRow.id, airport: inRow.arr_iata, out_flight: "6E 2347",
    out_dep: outRow.sched_dep, layover_min: 150, mct_min: 120,
  };
  const set = (buffer: number) => {
    const risk = riskFromBuffer(buffer);
    h.repo.connections = ["a", "b"].map((u) => ({ ...base, user_id: u, buffer_min: buffer, risk }));
  };
  const pushed = () => h.pusher.sent.filter((m) => m.data?.event_type === "CONNECTION_AT_RISK").map((m) => m.token);

  set(30);
  assertEquals(await runConnectionAlerts(h.deps), 0, "first sighting stores ok");
  set(-10);
  assertEquals(await runConnectionAlerts(h.deps), 1);
  assertEquals(pushed(), ["tok-a-xxxxxxxxxxxxxxxxxx", "tok-b-xxxxxxxxxxxxxxxxxx"]);
  const ev = h.repo.events.find((e) => e.type === "CONNECTION_AT_RISK")!;
  assertEquals(ev.flightId, inRow.id);
  assertEquals(ev.dedupeKey, `${inRow.id}:${outRow.id}:CONN:0`);
  assertEquals(ev.payload.flight, "6E 2340");
  const msg = h.pusher.sent.find((m) => m.data?.event_type === "CONNECTION_AT_RISK")!;
  assertEquals(msg.data!.title, `Connection at risk: 6E 2340 → 6E 2347`);
  assertEquals((msg.android!.notification as Record<string, string>).channel_id, "flight_critical");

  assertEquals(await runConnectionAlerts(h.deps), 0, "state stored: no repeat");
  set(40);
  assertEquals(await runConnectionAlerts(h.deps), 0);
  set(-5);
  assertEquals(await runConnectionAlerts(h.deps), 1, "at risk again after recovering");
  assertEquals(h.repo.events.filter((e) => e.type === "CONNECTION_AT_RISK").map((e) => e.dedupeKey.split(":").pop()), ["0", "1"]);
  assertEquals(pushed().length, 4);
  assertEquals(h.repo.connectionState.get(`${inRow.id}:${outRow.id}`), { risk: "at_risk", gen: 2 });
});

Deno.test("sweeper: an unpushed CONNECTION_AT_RISK goes only to the trackers of both legs", async () => {
  const h = makeDeps("2026-11-21T00:00:00Z");
  const [inRow] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: "2026-11-25" });
  const [outRow] = await searchFlight(h.deps, "u", { flight: "6E 2347", date: "2026-11-25" });
  const dev = (id: string) => [{ id, token: `tok-${id}-xxxxxxxxxxxxxxxxxx` }];
  h.repo.track(inRow.id, { userId: "a", isPro: true, prefs: {}, devices: dev("a") });
  h.repo.track(outRow.id, { userId: "a", isPro: true, prefs: {}, devices: dev("a") });
  h.repo.track(inRow.id, { userId: "c", isPro: true, prefs: {}, devices: dev("c") }); // a stranger on the arriving leg
  const base = {
    in_flight_id: inRow.id, out_flight_id: outRow.id, airport: inRow.arr_iata, out_flight: "6E 2347",
    out_dep: outRow.sched_dep, layover_min: 150, mct_min: 120, user_id: "a",
  };
  h.repo.connections = [{ ...base, buffer_min: 30, risk: "ok" }];
  await runConnectionAlerts(h.deps);
  h.repo.connections = [{ ...base, buffer_min: -10, risk: "at_risk" }];
  assertEquals(await runConnectionAlerts(h.deps), 1);
  const ev = h.repo.events.find((e) => e.type === "CONNECTION_AT_RISK")!;
  ev.pushed = false; // crash before the push was recorded
  h.pusher.sent.length = 0;
  h.clock.advance(3);
  assertEquals(await sweepUnpushedEvents(h.deps), 1);
  assertEquals(h.pusher.sent.filter((m) => m.data?.event_type === "CONNECTION_AT_RISK").map((m) => m.token),
    ["tok-a-xxxxxxxxxxxxxxxxxx"], "not the stranger");
  assert(ev.pushed);

  // the connecting leg is no longer tracked by anyone: nothing to send, not retried
  ev.pushed = false;
  h.repo.trackers.delete(outRow.id);
  h.pusher.sent.length = 0;
  assertEquals(await sweepUnpushedEvents(h.deps), 1);
  assertEquals(h.pusher.sent.length, 0);
  assert(ev.pushed, "marked done so it is not retried");
});

// ---------------------------------------------------------------- delivery

Deno.test("delivery: info milestones on flight_updates with replacing tags, Pro alerts critical", () => {
  const ev = (type: (typeof EVENT_TYPES)[number]) => ({
    type, severity: isCriticalAlert(type) ? "critical" as const : "info" as const, proOnly: false,
    oldValue: null, newValue: null, payload: { flight: "6E 2341", dep: "DEL", arr: "BOM" }, dedupeKey: `k:${type}`,
  });
  const tagAndChannel = (type: (typeof EVENT_TYPES)[number]) => {
    const n = buildAlertMessage("tok", "f9", "e1", ev(type), IST).android!.notification as Record<string, string>;
    return `${n.tag} ${n.channel_id}`;
  };
  assertEquals(tagAndChannel("GATE_CLOSED"), "f9:boarding flight_updates");
  assertEquals(tagAndChannel("BOARDING"), "f9:boarding flight_critical");
  assertEquals(tagAndChannel("TAKEOFF"), "f9:departed flight_updates");
  assertEquals(tagAndChannel("AT_GATE"), "f9:at_gate flight_updates");
  assertEquals(tagAndChannel("AIRCRAFT_SWAP"), "f9:aircraft_swap flight_critical");
  assertEquals(tagAndChannel("CONNECTION_AT_RISK"), "f9:connection flight_critical");
  const quiet = buildAlertMessage("tok", "f9", "e1", ev("TAKEOFF"), IST, { quiet: true }).android!.notification as Record<string, string>;
  assertEquals(quiet.channel_id, "flight_quiet");
});
