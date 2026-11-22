// Human-readable simulation of the real polling engine against the mock provider.
// Usage (from supabase/functions):  deno run tests/simulate.ts 2345 2347
//   last digit picks the scenario: 0-2 on time · 3/4 delays · 5 gate change · 6 cancel · 7 inbound late · 8 belt change · 9 diverted
import { runPollCycle, searchFlight } from "../_shared/engine.ts";
import { makeDeps } from "./helpers.ts";

const nums = Deno.args.length ? Deno.args.map(Number) : [2345, 2347];
const DATE = "2026-11-25";
const ist = (t: number) =>
  new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit", hour12: false })
    .format(new Date(t));

for (const n of nums) {
  const h = makeDeps("2026-11-21T00:00:00Z");
  const [row] = await searchFlight(h.deps, "me", { flight: `6E ${n}`, date: DATE });
  h.repo.track(row.id, { userId: "free", isPro: false, prefs: {}, devices: [{ id: "d1", token: "FREE-user-device-token-xx" }] });
  h.repo.track(row.id, { userId: "pro", isPro: true, prefs: {}, devices: [{ id: "d2", token: "PRO--user-device-token-xx" }] });
  console.log(`\n✈  6E ${n}  ${row.dep_iata} → ${row.arr_iata}  scheduled ${ist(Date.parse(row.sched_dep!))} IST`);
  console.log("─".repeat(78));
  let shown = 0;
  const end = Date.parse("2026-11-26T06:00:00Z");
  while (h.clock.t < end) {
    await runPollCycle(h.deps, 5_000);
    for (const m of h.pusher.sent.slice(shown)) {
      if (m.data?.kind !== "alert") continue;
      const who = m.token.startsWith("PRO") ? "PRO " : "FREE";
      console.log(`${ist(h.clock.t)}  [${who}]  ${m.data.title}\n${" ".repeat(23)}${m.data.body}`);
    }
    shown = h.pusher.sent.length;
    h.clock.advance(0.5);
  }
  const syncs = h.pusher.sent.filter((m) => m.data?.kind === "sync").length;
  console.log("─".repeat(78));
  console.log(`provider API calls: ${h.provider.calls} (shared by every user tracking this flight) · silent sync pushes: ${syncs}`);
}
