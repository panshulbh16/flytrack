// Normalized, provider-agnostic flight model.
// Every provider adapter (mock, AeroDataBox, FlightAware...) must map into this shape.
// All timestamps are ISO-8601 UTC strings (e.g. "2026-11-25T13:20:00Z") or null.

export const FLIGHT_STATUSES = [
  "unknown",
  "scheduled",
  "check_in",
  "boarding",
  "gate_closed",
  "departed", // left the gate / took off
  "en_route",
  "approaching",
  "landed",
  "arrived", // at the gate
  "cancelled",
  "diverted",
] as const;
export type FlightStatus = typeof FLIGHT_STATUSES[number];

/** Identity of one flight leg. flight_date = LOCAL departure date at origin. */
export interface FlightKey {
  carrier: string; // IATA airline code, e.g. "6E", "AI", "QP", "SG", "IX"
  number: string; // "2341" (no leading zeros), may carry a suffix letter e.g. "123A"
  date: string; // "YYYY-MM-DD" local departure date
  depIata?: string; // needed to disambiguate multi-leg flights with the same number
}

export interface LivePosition {
  lat: number;
  lon: number;
  altFt: number | null;
  headingDeg: number | null;
  speedKts: number | null;
  at: string; // when the position was observed (UTC)
}

export interface FlightSnapshot {
  carrier: string;
  number: string;
  date: string;
  depIata: string;
  arrIata: string;
  carrierIcao: string | null;
  airlineName: string | null;

  status: FlightStatus;

  schedDep: string | null;
  estDep: string | null; // provider's revised / estimated departure
  actDep: string | null; // actual (off-block or runway)
  depTerminal: string | null;
  depGate: string | null;
  depCheckin: string | null;

  schedArr: string | null;
  estArr: string | null;
  actArr: string | null;
  arrTerminal: string | null;
  arrGate: string | null;
  baggageBelt: string | null;

  aircraftModel: string | null;
  aircraftReg: string | null; // tail number, e.g. "VT-ISR"

  delayReason: string | null; // only if the provider/airline gives one
  live: LivePosition | null;

  providerRef: string | null;
  providerUpdatedAt: string | null;
  raw?: unknown;
}

/** Fields whose change is recorded in flight_status_history (live position excluded on purpose). */
export const TRACKED_FIELDS = [
  "status",
  "schedDep",
  "estDep",
  "actDep",
  "depTerminal",
  "depGate",
  "depCheckin",
  "schedArr",
  "estArr",
  "actArr",
  "arrTerminal",
  "arrGate",
  "baggageBelt",
  "aircraftModel",
  "aircraftReg",
  "delayReason",
  "arrIata", // diversion can change the destination
] as const satisfies readonly (keyof FlightSnapshot)[];
export type TrackedField = typeof TRACKED_FIELDS[number];

export const EVENT_TYPES = [
  "DELAY", // departure delayed further (>= threshold)
  "DELAY_REDUCED", // back on time / earlier than last told
  "SCHEDULE_CHANGE", // airline retimed the flight (scheduled time moved earlier/later)
  "GATE_ASSIGNED",
  "GATE_CHANGE",
  "TERMINAL_CHANGE",
  "BOARDING",
  "DEPARTED",
  "LANDED",
  "BELT_ASSIGNED",
  "BELT_CHANGE",
  "CANCELLED",
  "DIVERTED",
  "REINSTATED", // a cancellation was withdrawn (happens!)
  "INBOUND_LATE", // Pro: the aircraft operating your flight is late
] as const;
export type EventType = typeof EVENT_TYPES[number];

export interface FlightEvent {
  type: EventType;
  severity: "critical" | "info";
  proOnly: boolean;
  oldValue: string | null;
  newValue: string | null;
  payload: Record<string, unknown>;
  dedupeKey: string; // unique per (flight, meaningful change); DB enforces once-only
}

/** "What users were last told" — alerts are computed against this, not the raw previous poll.
 *  This avoids alert spam when a provider flaps (gate -> null -> same gate). */
export interface AlertState {
  depGate?: string | null;
  depTerminal?: string | null;
  baggageBelt?: string | null;
  depDelayMin?: number; // last delay value we notified (0 = on time)
  milestones?: string[]; // BOARDING, DEPARTED, LANDED, CANCELLED, DIVERTED already sent
  schedDep?: string | null; // scheduled departure users were last told (detects airline retimes)
  plannedArr?: string | null; // original destination (diversion messages)
  alertSeq?: number; // generation counter mixed into dedupe keys
}

/** A flight row as stored in Postgres (snake_case columns), as much as the backend needs. */
export interface FlightRow {
  id: string;
  carrier_iata: string;
  flight_number: string;
  flight_date: string;
  dep_iata: string;
  arr_iata: string;
  carrier_icao: string | null;
  airline_name: string | null;
  status: FlightStatus;
  sched_dep: string | null;
  est_dep: string | null;
  act_dep: string | null;
  dep_terminal: string | null;
  dep_gate: string | null;
  dep_checkin: string | null;
  sched_arr: string | null;
  est_arr: string | null;
  act_arr: string | null;
  arr_terminal: string | null;
  arr_gate: string | null;
  baggage_belt: string | null;
  aircraft_model: string | null;
  aircraft_reg: string | null;
  delay_reason: string | null;
  live_lat: number | null;
  live_lon: number | null;
  live_alt_ft: number | null;
  live_heading: number | null;
  live_speed_kts: number | null;
  live_updated_at: string | null;
  inbound_flight_id: string | null;
  inbound_checked_at: string | null;
  inbound_watch_until: string | null;
  prediction: Record<string, unknown> | null;
  provider: string | null;
  provider_ref: string | null;
  provider_updated_at: string | null;
  alert_state: AlertState;
  tracker_count: number;
  pro_tracker_count: number;
  last_polled_at: string | null;
  next_poll_at: string | null;
  lease_until: string | null;
  poll_error_count: number;
  last_error: string | null;
  webhook_subscription_id: string | null;
  webhook_expires_at: string | null;
  created_at?: string;
  updated_at?: string;
}

export interface Recipient {
  user_id: string;
  is_pro: boolean;
  notify_prefs: Record<string, boolean>;
  device_id: string;
  fcm_token: string;
}
