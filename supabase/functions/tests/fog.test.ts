import { assert, assertEquals } from "@std/assert";
import { runFogAlerts, runPollCycle, searchFlight } from "../_shared/engine.ts";
import { fogCandidates, fogDedupeKey, fogEvent, isFoggy, localDate } from "../_shared/health.ts";
import { EVENT_TYPES, type FlightRow } from "../_shared/types.ts";
import { makeDeps } from "./helpers.ts";

const MIN = 60_000;
const FOG = (at: string, vis = 300) => ({ visibility_m: vis, wx: vis < 1000 ? "FG" : null, fog: vis < 1000, observed_at: at });

async function tracked(h: ReturnType<typeof makeDeps>, flight: string, date: string, prefs: Record<string, boolean> = {}) {
  const [row] = await searchFlight(h.deps, "u", { flight, date });
  h.repo.track(row.id, { userId: "u", isPro: false, prefs, devices: [{ id: "d1", token: "tok-fog-xxxxxxxxxxxxxxxxxxxxx" }] });
  return (await h.repo.getFlight(row.id))!;
}

const fogAlerts = (h: ReturnType<typeof makeDeps>) => h.pusher.sent.filter((m) => m.data?.kind === "alert" && m.data?.event_type === "FOG_ALERT");

Deno.test("fog: FOG_ALERT is a known event type", () => {
  assert(EVENT_TYPES.includes("FOG_ALERT"));
});

Deno.test("fog: dedupe key uses the airport-local date of the scheduled time", () => {
  // 20:00 UTC = 01:30 IST next day
  assertEquals(localDate("2026-11-25T20:00:00Z", "Asia/Kolkata"), "2026-11-26");
  assertEquals(fogDedupeKey("f1", "DEL", "2026-11-25T20:00:00Z", "Asia/Kolkata"), "f1:FOG:DEL:2026-11-26");
  assertEquals(fogDedupeKey("f1", "DXB", "2026-11-25T20:00:00Z", "Asia/Dubai"), "f1:FOG:DXB:2026-11-26");
  assertEquals(fogDedupeKey("f1", "DXB", "2026-11-25T19:00:00Z", "Asia/Dubai"), "f1:FOG:DXB:2026-11-25");
});

Deno.test("fog: weather older than 2 h or clear does not count", () => {
  const now = new Date("2026-11-26T01:00:00Z");
  assert(isFoggy(FOG("2026-11-26T00:30:00Z"), now));
  assert(isFoggy({ visibility_m: 800, wx: "BR", fog: false, observed_at: "2026-11-26T00:30:00Z" }, now));
  assert(!isFoggy({ visibility_m: 1000, wx: "BR", fog: false, observed_at: "2026-11-26T00:30:00Z" }, now));
  assert(!isFoggy(FOG("2026-11-25T22:59:00Z"), now));
  assert(!isFoggy(null, now));
});

Deno.test("fog: event shape follows the contract", () => {
  const e = fogEvent({ flight_id: "f1", side: "arr", airport: "DEL", visibility_m: 300, wx: "FG", dedupe_key: "f1:FOG:DEL:2026-11-26" });
  assertEquals(e, {
    type: "FOG_ALERT", severity: "critical", proOnly: false, oldValue: null, newValue: "300",
    payload: { airport: "DEL", visibility_m: 300, wx: "FG", side: "arr" }, dedupeKey: "f1:FOG:DEL:2026-11-26",
  });
});

Deno.test("fog: departure airport fog within 4 h → one critical alert, sent once", async () => {
  const h = makeDeps("2026-11-24T00:00:00Z");
  const row = await tracked(h, "6E 2340", "2026-11-25"); // mock: DEL→BOM
  const dep = Date.parse(row.sched_dep!);

  // fog at DEL, but departure is more than 4 h away → nothing
  h.clock.t = dep - 5 * 60 * MIN;
  h.repo.weather.set(row.dep_iata, FOG(h.clock.now().toISOString()));
  assertEquals(await runFogAlerts(h.deps), 0);

  // 3 h before departure → alert
  h.clock.t = dep - 3 * 60 * MIN;
  h.repo.weather.set(row.dep_iata, FOG(new Date(h.clock.t - 10 * MIN).toISOString()));
  assertEquals(await runFogAlerts(h.deps), 1);
  const ev = h.repo.events.find((e) => e.type === "FOG_ALERT")!;
  assertEquals(ev.severity, "critical");
  assertEquals(ev.payload, {
    flight: `${row.carrier_iata} ${row.flight_number}`, dep: row.dep_iata, arr: row.arr_iata,
    airport: row.dep_iata, visibility_m: 300, wx: "FG", side: "dep",
  });
  assertEquals(ev.dedupeKey, fogDedupeKey(row.id, row.dep_iata, row.sched_dep!, "Asia/Kolkata"));
  assert(ev.pushed);
  assertEquals(fogAlerts(h).length, 1);
  const { title, body } = fogAlerts(h)[0].data!;
  const text = `${title} ${body}`; // rendered by notify.ts
  assert(text.includes("300 m") && text.includes("6E 2340"), text);

  // still foggy 30 min later, new observation → no second alert for this flight and airport
  h.clock.advance(30);
  h.repo.weather.set(row.dep_iata, FOG(h.clock.now().toISOString(), 100));
  assertEquals(await runFogAlerts(h.deps), 0);
  assertEquals(fogAlerts(h).length, 1);
});

Deno.test("fog: arrival airport side; departed flights and muted users", async () => {
  const h = makeDeps("2026-11-24T00:00:00Z");
  const row = await tracked(h, "6E 2340", "2026-11-25", { FOG_ALERT: false }); // muted
  const arr = Date.parse(row.sched_arr!);
  h.clock.t = arr - 2 * 60 * MIN;
  // the flight has left; fog at the destination is still news for the arrival
  await h.repo.updateFlight(row.id, { act_dep: new Date(h.clock.t - 30 * MIN).toISOString(), status: "en_route" });
  h.repo.weather.set(row.dep_iata, FOG(h.clock.now().toISOString()));
  h.repo.weather.set(row.arr_iata, FOG(h.clock.now().toISOString(), 600));
  assertEquals(await runFogAlerts(h.deps), 1);
  const evs = h.repo.events.filter((e) => e.type === "FOG_ALERT");
  assertEquals(evs.length, 1); // departure side is over
  assertEquals((evs[0].payload as Record<string, unknown>).side, "arr");
  assertEquals((evs[0].payload as Record<string, unknown>).airport, row.arr_iata);
  assertEquals(fogAlerts(h).length, 0); // muted with notify_prefs.FOG_ALERT = false
  assert(evs[0].pushed);
});

Deno.test("fog: cancelled, untracked and arrived flights get nothing", async () => {
  const h = makeDeps("2026-11-24T00:00:00Z");
  const row = await tracked(h, "6E 2340", "2026-11-25");
  h.clock.t = Date.parse(row.sched_dep!) - 60 * MIN;
  h.repo.weather.set(row.dep_iata, FOG(h.clock.now().toISOString()));
  h.repo.weather.set(row.arr_iata, FOG(h.clock.now().toISOString()));
  await h.repo.updateFlight(row.id, { status: "cancelled" });
  assertEquals(await runFogAlerts(h.deps), 0);
  await h.repo.updateFlight(row.id, { status: "arrived", act_dep: row.sched_dep, act_arr: row.sched_arr });
  assertEquals(await runFogAlerts(h.deps), 0);
  await h.repo.updateFlight(row.id, { status: "scheduled", act_dep: null, act_arr: null, tracker_count: 0 });
  assertEquals(await runFogAlerts(h.deps), 0);
});

Deno.test("fog: a flight held on the ground past its slot still gets the alert", () => {
  const now = new Date("2026-11-26T02:00:00Z");
  const f = {
    id: "f9", tracker_count: 1, status: "scheduled", dep_iata: "DEL", arr_iata: "BOM",
    sched_dep: "2026-11-26T00:00:00Z", est_dep: null, act_dep: null, sched_arr: "2026-11-26T02:10:00Z", est_arr: null, act_arr: null,
  } as unknown as FlightRow;
  const w = (iata: string) => (iata === "DEL" ? FOG("2026-11-26T01:30:00Z") : undefined);
  const c = fogCandidates([f], w, () => "Asia/Kolkata", new Set(), now, 10);
  assertEquals(c.map((x) => [x.side, x.airport, x.dedupe_key]), [["dep", "DEL", "f9:FOG:DEL:2026-11-26"]]);
  assertEquals(fogCandidates([f], w, () => "Asia/Kolkata", new Set(["f9:FOG:DEL:2026-11-26"]), now, 10), []);
  // more than 3 h past the slot → out of scope
  assertEquals(fogCandidates([f], w, () => "Asia/Kolkata", new Set(), new Date("2026-11-26T03:01:00Z"), 10), []);
});

Deno.test("fog: runPollCycle runs the fog step and reports it", async () => {
  const h = makeDeps("2026-11-24T00:00:00Z");
  const row = await tracked(h, "6E 2340", "2026-11-25");
  h.clock.t = Date.parse(row.sched_dep!) - 2 * 60 * MIN;
  h.repo.weather.set(row.dep_iata, FOG(h.clock.now().toISOString()));
  const stats = await runPollCycle(h.deps, 1000);
  assertEquals(stats.fog, 1);
  assertEquals(fogAlerts(h).length, 1);
});
