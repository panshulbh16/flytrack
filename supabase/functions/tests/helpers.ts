import { DEFAULT_CONFIG, type Deps } from "../_shared/engine.ts";
import { LogPusher } from "../_shared/fcm.ts";
import { MockProvider } from "../_shared/providers/mock.ts";
import { MemoryRepo } from "../_shared/repo.ts";
import type { FlightSnapshot } from "../_shared/types.ts";

export class FakeClock {
  constructor(public t: number) {}
  now = () => new Date(this.t);
  advance(min: number) {
    this.t += min * 60_000;
  }
}

export function makeDeps(startIso: string) {
  const clock = new FakeClock(Date.parse(startIso));
  const repo = new MemoryRepo(clock.now);
  const provider = new MockProvider(clock.now);
  const pusher = new LogPusher(false);
  const deps: Deps = { repo, provider, pusher, now: clock.now, config: { ...DEFAULT_CONFIG } };
  return { clock, repo, provider, pusher, deps };
}

export function snap(over: Partial<FlightSnapshot> = {}): FlightSnapshot {
  return {
    carrier: "6E", number: "2341", date: "2026-11-25", depIata: "DEL", arrIata: "BOM",
    carrierIcao: "IGO", airlineName: "IndiGo", status: "scheduled",
    schedDep: "2026-11-25T13:20:00Z", estDep: null, actDep: null,
    depTerminal: "1", depGate: null, depCheckin: null,
    schedArr: "2026-11-25T15:30:00Z", estArr: null, actArr: null,
    arrTerminal: "2", arrGate: null, baggageBelt: null,
    aircraftModel: "A320neo", aircraftReg: "VT-ISR", delayReason: null, live: null,
    providerRef: null, providerUpdatedAt: null, ...over,
  };
}

/** Advance the fake clock in steps, running the same poll cycle the Edge Function runs. */
export async function runUntil(
  h: ReturnType<typeof makeDeps>,
  untilIso: string,
  stepMin = 0.5,
  run: (d: Deps) => Promise<unknown>,
) {
  const end = Date.parse(untilIso);
  while (h.clock.t < end) {
    await run(h.deps);
    h.clock.advance(stepMin);
  }
}
