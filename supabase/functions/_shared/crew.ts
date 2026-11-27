// Crew mode (docs/P3_CONTRACTS.md B12): pilots and cabin crew don't check in, board through the gate or need
// "time to leave". For a recipient with profiles.crew_mode on, the push fan-out skips these alerts (they
// still exist as flight_events for other trackers). When-to-leave is computed on the device, which reads
// crew_mode itself.
import type { EventType, FlightEvent, Recipient } from "./types.ts";

export const CREW_MUTED_EVENTS: ReadonlySet<EventType> = new Set<EventType>([
  "CHECKIN_OPEN",
  "CHECKIN_CLOSING",
  "BOARDING",
  "GATE_CLOSED", // the gate-closed alert replaces the boarding one; crew already know
]);

/** True when this alert must not be pushed to this recipient because they are in crew mode. */
export function crewMutes(r: Pick<Recipient, "crew_mode">, e: Pick<FlightEvent, "type">): boolean {
  return r.crew_mode === true && CREW_MUTED_EVENTS.has(e.type);
}
