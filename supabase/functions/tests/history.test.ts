import { assert, assertEquals } from "@std/assert";
import { runPollCycle, searchFlight } from "../_shared/engine.ts";
import { legHistoryRow, shouldFinalize } from "../_shared/history.ts";
import type { FlightRow } from "../_shared/types.ts";
import { makeDeps, runUntil } from "./helpers.ts";

const START = "2026-11-21T00:00:00Z";
const DATE = "2026-11-25";

async function flyDay(n: number) {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: `6E ${n}`, date: DATE });
  h.repo.track(row.id, { userId: "u", isPro: false, prefs: {}, devices: [{ id: "d", token: "tok-hist-xxxxxxxxxxxxxxxxx" }] });
  let sawLive = false;
  await runUntil(h, "2026-11-26T06:00:00Z", 0.5, async (d) => {
    await runPollCycle(d, 5_000);
    const f = await h.repo.getFlight(row.id);
    if (f?.live_lat != null) sawLive = true;
  });
  return { h, row, final: (await h.repo.getFlight(row.id))!, sawLive };
}

Deno.test("history: one row per finished leg, written when polling stops", async () => {
  const { h, row, final, sawLive } = await flyDay(2348); // belt change scenario
  assertEquals(final.next_poll_at, null);
  assertEquals(h.repo.legHistory.size, 1, "inbound-only legs are not recorded");
  const leg = h.repo.legHistory.get(row.id)!;
  assertEquals([leg.carrier, leg.number, leg.date, leg.dep, leg.arr], ["6E", "2348", DATE, final.dep_iata, final.arr_iata]);
  assertEquals(leg.final_status, final.status);
  assertEquals(leg.baggage_belt, final.baggage_belt);
  assert(leg.had_belt && leg.had_gate && leg.had_tail, JSON.stringify(leg));
  assertEquals(leg.had_position, sawLive);
  assertEquals(final.live_seen, sawLive);
  assertEquals(leg.provider, "mock");
  assertEquals(leg.dep_delay_min, Math.round((Date.parse(final.act_dep!) - Date.parse(final.sched_dep!)) / 60_000));
  assert(Date.parse(leg.finalized_at) >= Date.parse(final.act_arr!));
});

Deno.test("history: cancelled legs are finalized too", async () => {
  const { h, row } = await flyDay(2346);
  const leg = h.repo.legHistory.get(row.id)!;
  assertEquals(leg.final_status, "cancelled");
  assertEquals(leg.act_dep, null);
  assertEquals(leg.dep_delay_min, null);
});

Deno.test("history: live_seen sticks after the position is gone", async () => {
  const h = makeDeps(START);
  const [row] = await searchFlight(h.deps, "u", { flight: "6E 2340", date: DATE });
  h.repo.track(row.id, { userId: "u", isPro: false, prefs: {}, devices: [] });
  assertEquals((await h.repo.getFlight(row.id))!.live_seen, false);
  await runUntil(h, "2026-11-26T06:00:00Z", 1, (d) => runPollCycle(d, 5_000));
  const f = (await h.repo.getFlight(row.id))!;
  assert(f.live_seen, "mock flight reports positions while airborne");
});

Deno.test("history: row shape and finalize rule", () => {
  const r = {
    id: "f1", carrier_iata: "AI", flight_number: "101", flight_date: DATE, dep_iata: "DEL", arr_iata: "BOM",
    status: "arrived", sched_dep: "2026-11-25T10:00:00Z", act_dep: "2026-11-25T10:25:00Z",
    sched_arr: "2026-11-25T12:10:00Z", act_arr: "2026-11-25T12:05:00Z", dep_terminal: "3", dep_gate: " ",
    arr_terminal: "1", baggage_belt: "7", aircraft_reg: null, aircraft_model: "B787", live_lat: null, live_lon: null,
    live_seen: true, provider: "aerodatabox", tracker_count: 2, next_poll_at: null,
  } as unknown as FlightRow;
  const leg = legHistoryRow(r, new Date("2026-11-25T13:00:00Z"));
  assertEquals([leg.dep_delay_min, leg.arr_delay_min], [25, -5]);
  assertEquals([leg.had_gate, leg.had_belt, leg.had_tail, leg.had_position], [false, true, false, true]);
  assertEquals(leg.finalized_at, "2026-11-25T13:00:00.000Z");
  assert(shouldFinalize({ next_poll_at: "2026-11-25T12:50:00Z" }, r));
  assert(!shouldFinalize({ next_poll_at: null }, r), "already stopped");
  assert(!shouldFinalize({ next_poll_at: "x" }, { ...r, tracker_count: 0 }), "inbound-only");
  assert(!shouldFinalize({ next_poll_at: "x" }, { ...r, next_poll_at: "y" }), "still polling");
});
