// Finished-leg history (docs/P1_CONTRACTS.md section 4). One row per leg, written when polling stops;
// public.provider_coverage_daily turns these rows into per-airport coverage numbers.
import type { FlightRow } from "./types.ts";

/** One row of public.flight_leg_history. */
export interface LegHistoryRow {
  flight_id: string;
  carrier: string;
  number: string;
  date: string;
  dep: string;
  arr: string;
  sched_dep: string | null;
  act_dep: string | null;
  sched_arr: string | null;
  act_arr: string | null;
  dep_delay_min: number | null;
  arr_delay_min: number | null;
  dep_terminal: string | null;
  dep_gate: string | null;
  arr_terminal: string | null;
  baggage_belt: string | null;
  aircraft_reg: string | null;
  aircraft_model: string | null;
  final_status: string;
  had_gate: boolean;
  had_belt: boolean;
  had_tail: boolean;
  had_position: boolean;
  provider: string | null;
  finalized_at: string;
  /** Runway − off-block and on-block − runway, minutes (P3 B3); null when unknown or implausible. */
  taxi_out_min?: number | null;
  taxi_in_min?: number | null;
}

function minutesLate(actual: string | null, scheduled: string | null): number | null {
  const a = Date.parse(actual ?? ""), s = Date.parse(scheduled ?? "");
  return Number.isNaN(a) || Number.isNaN(s) ? null : Math.round((a - s) / 60_000);
}

/** Taxi minutes between two block times; outside 1..180 min it is a data error, not a taxi time. */
export function taxiMinutes(from: string | null | undefined, to: string | null | undefined): number | null {
  const a = Date.parse(from ?? ""), b = Date.parse(to ?? "");
  if (Number.isNaN(a) || Number.isNaN(b)) return null;
  const m = Math.round((b - a) / 60_000);
  return m >= 1 && m <= 180 ? m : null;
}

const present = (v: string | null | undefined) => v != null && v.trim() !== "";

/**
 * The finished leg as stored. Gate, belt and tail are sticky on the row (mergeSticky), so "the row has
 * it at the end" means "the provider gave it at some point". had_position comes from flights.live_seen,
 * because the live position itself is cleared once the plane is on the ground.
 */
export function legHistoryRow(r: FlightRow, now: Date): LegHistoryRow {
  return {
    flight_id: r.id,
    carrier: r.carrier_iata,
    number: r.flight_number,
    date: r.flight_date,
    dep: r.dep_iata,
    arr: r.arr_iata,
    sched_dep: r.sched_dep,
    act_dep: r.act_dep,
    sched_arr: r.sched_arr,
    act_arr: r.act_arr,
    dep_delay_min: minutesLate(r.act_dep, r.sched_dep),
    arr_delay_min: minutesLate(r.act_arr, r.sched_arr),
    dep_terminal: r.dep_terminal,
    dep_gate: r.dep_gate,
    arr_terminal: r.arr_terminal,
    baggage_belt: r.baggage_belt,
    aircraft_reg: r.aircraft_reg,
    aircraft_model: r.aircraft_model,
    final_status: r.status,
    had_gate: present(r.dep_gate),
    had_belt: present(r.baggage_belt),
    had_tail: present(r.aircraft_reg),
    had_position: !!r.live_seen || (r.live_lat != null && r.live_lon != null),
    provider: r.provider,
    finalized_at: now.toISOString(),
    taxi_out_min: taxiMinutes(r.dep_offblock_at, r.dep_runway_at),
    taxi_in_min: taxiMinutes(r.arr_runway_at, r.arr_onblock_at),
  };
}

/**
 * Polling just stopped for a leg real users follow. Inbound-only rows (watched for "Where's my plane",
 * polled slowly, no gates or belts asked for) would drag the coverage numbers down, so they are skipped.
 */
export function shouldFinalize(before: Pick<FlightRow, "next_poll_at">, after: FlightRow): boolean {
  return before.next_poll_at != null && after.next_poll_at == null && after.tracker_count > 0;
}
