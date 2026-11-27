// Connection assistant alerts (docs/P3_CONTRACTS.md B4). The trips and the risk come from SQL
// (connections_for_user / connection_alert_candidates in migration 25); this file decides the alert.
import type { FlightEvent } from "./types.ts";

export const CONNECTION_RISKS = ["ok", "tight", "at_risk", "missed"] as const;
export type ConnectionRisk = typeof CONNECTION_RISKS[number];

/** One row of public.connection_alert_candidates(): a Pro user's connection whose risk changed. */
export interface ConnectionCandidate {
  user_id: string;
  in_flight_id: string;
  out_flight_id: string;
  airport: string;
  out_flight: string; // "6E 512"
  out_dep: string | null; // best known departure of the connecting flight
  layover_min: number;
  mct_min: number;
  buffer_min: number;
  risk: ConnectionRisk;
  last_risk: ConnectionRisk | null; // null = never seen before
  gen: number;
}

export interface ConnectionDecision {
  /** The CONNECTION_AT_RISK event to raise on the arriving flight, or null. */
  event: FlightEvent | null;
  /** State to store: the risk seen now, and the generation for the next alert. */
  risk: ConnectionRisk;
  gen: number;
}

/** Risk from the buffer (layover − MCT), as the SQL computes it (missed is decided from actual times). */
export function riskFromBuffer(bufferMin: number): Exclude<ConnectionRisk, "missed"> {
  return bufferMin >= 30 ? "ok" : bufferMin >= 0 ? "tight" : "at_risk";
}

/**
 * CONNECTION_AT_RISK goes out only when a connection we had seen as ok or tight becomes at_risk. A connection
 * first seen at risk (booked that way, or added late), missed ones, and at_risk → at_risk raise nothing.
 * The dedupe key carries the generation, so ok → at_risk → ok → at_risk alerts twice, and two racing poll
 * cycles on the same change insert one event.
 */
export function decideConnection(c: ConnectionCandidate): ConnectionDecision {
  const alert = (c.last_risk === "ok" || c.last_risk === "tight") && c.risk === "at_risk";
  if (!alert) return { event: null, risk: c.risk, gen: c.gen };
  return {
    event: {
      type: "CONNECTION_AT_RISK",
      severity: "critical",
      proOnly: true,
      oldValue: c.last_risk,
      newValue: c.risk,
      dedupeKey: `${c.in_flight_id}:${c.out_flight_id}:CONN:${c.gen}`,
      payload: {
        out_flight: c.out_flight,
        out_dep: c.out_dep,
        airport: c.airport,
        buffer_min: c.buffer_min,
        layover_min: c.layover_min,
        mct_min: c.mct_min,
        out_flight_id: c.out_flight_id,
      },
    },
    risk: c.risk,
    gen: c.gen + 1,
  };
}

/** Candidate rows are per user; alerts are per connection. Groups rows by (in, out), keeping every user. */
export function groupConnections(rows: ConnectionCandidate[]): { c: ConnectionCandidate; users: Set<string> }[] {
  const byPair = new Map<string, { c: ConnectionCandidate; users: Set<string> }>();
  for (const r of rows) {
    const key = `${r.in_flight_id}:${r.out_flight_id}`;
    const g = byPair.get(key);
    if (g) g.users.add(r.user_id);
    else byPair.set(key, { c: r, users: new Set([r.user_id]) });
  }
  return [...byPair.values()];
}
