import type { FlightEvent, FlightRow, Recipient, TrackedField } from "./types.ts";
import type { FcmMessage } from "./notify.ts";
import type { PushResult } from "./fcm.ts";

export interface ProviderCallLog {
  provider: string;
  endpoint: string;
  status: number | null;
  units: number;
  durationMs: number;
  flightId?: string | null;
  userId?: string | null;
}

export interface PushLogRow {
  eventId: string | null;
  deviceId: string;
  userId: string;
  kind: "alert" | "sync" | "live_activity";
  result: PushResult;
}

/** One iOS Live Activity following a flight on one phone (table live_activities). */
export interface LiveActivityTarget {
  id: string;
  user_id: string;
  device_id: string;
  fcm_token: string;
  activity_token: string;
}

/** Storage abstraction: SupabaseRepo in production, MemoryRepo in tests/simulator. */
export interface Repo {
  claimDueFlights(limit: number, leaseSeconds: number): Promise<FlightRow[]>;
  getFlight(id: string): Promise<FlightRow | null>;
  getFlightByKey(carrier: string, number: string, date: string, depIata: string): Promise<FlightRow | null>;
  getFlightsByNumberDate(carrier: string, number: string, date: string): Promise<FlightRow[]>;
  /** Insert (or return existing on unique-key conflict). */
  insertFlight(row: Partial<FlightRow>): Promise<FlightRow>;
  updateFlight(id: string, patch: Partial<FlightRow>): Promise<FlightRow>;
  insertHistory(flightId: string, source: string, changed: TrackedField[], snapshot: unknown): Promise<void>;
  /** Returns the new event id, or null if dedupe_key already existed (already sent). */
  insertEventIfNew(flightId: string, e: FlightEvent): Promise<string | null>;
  markEventsPushed(ids: string[]): Promise<void>;
  /** Events with pushed_at null, created between maxAgeMin and minAgeMin ago. */
  getUnpushedEvents(minAgeMin: number, maxAgeMin: number, limit: number): Promise<(FlightEvent & { id: string; flightId: string })[]>;
  getRecipients(flightId: string): Promise<Recipient[]>;
  getDependentsOfInbound(inboundFlightId: string): Promise<FlightRow[]>;
  logPushes(rows: PushLogRow[]): Promise<void>;
  deleteDeviceByToken(token: string): Promise<void>;
  getLiveActivities(flightId: string): Promise<LiveActivityTarget[]>;
  deleteLiveActivities(ids: string[]): Promise<void>;
  airportTz(iata: string): Promise<string | null>;
  logProviderCall(c: ProviderCallLog): Promise<void>;
  countUserProviderCalls(userId: string, sinceIso: string): Promise<number>;
}

// ------------------------------------------------------------------------------------------------
// In-memory implementation (mirrors the SQL semantics closely enough for tests & simulation)
// ------------------------------------------------------------------------------------------------

const DEFAULT_ROW: Omit<FlightRow, "id" | "carrier_iata" | "flight_number" | "flight_date" | "dep_iata" | "arr_iata"> = {
  carrier_icao: null, airline_name: null, status: "unknown",
  sched_dep: null, est_dep: null, act_dep: null, dep_terminal: null, dep_gate: null, dep_checkin: null,
  sched_arr: null, est_arr: null, act_arr: null, arr_terminal: null, arr_gate: null, baggage_belt: null,
  aircraft_model: null, aircraft_reg: null, delay_reason: null,
  live_lat: null, live_lon: null, live_alt_ft: null, live_heading: null, live_speed_kts: null, live_updated_at: null,
  inbound_flight_id: null, inbound_checked_at: null, inbound_watch_until: null, prediction: null,
  provider: null, provider_ref: null, provider_updated_at: null, alert_state: {},
  tracker_count: 0, pro_tracker_count: 0, last_polled_at: null, next_poll_at: null, lease_until: null,
  poll_error_count: 0, last_error: null, webhook_subscription_id: null, webhook_expires_at: null,
};

export interface MemTracker {
  userId: string;
  isPro: boolean;
  prefs: Record<string, boolean>;
  devices: { id: string; token: string }[];
}

export class MemoryRepo implements Repo {
  flights = new Map<string, FlightRow>();
  history: { flightId: string; source: string; changed: TrackedField[]; at: string }[] = [];
  events: (FlightEvent & { id: string; flightId: string; at: string; pushed: boolean })[] = [];
  pushes: PushLogRow[] = [];
  liveActivities: (LiveActivityTarget & { flightId: string })[] = [];
  providerCalls: (ProviderCallLog & { at: string })[] = [];
  trackers = new Map<string, MemTracker[]>(); // flightId -> trackers
  tz: Record<string, string> = {};
  private seq = 0;

  constructor(private readonly now: () => Date) {}

  /** Test helper = what the user_flights trigger does in SQL. */
  track(flightId: string, t: MemTracker) {
    const list = this.trackers.get(flightId) ?? [];
    list.push(t);
    this.trackers.set(flightId, list);
    const f = this.flights.get(flightId)!;
    f.tracker_count = list.length;
    f.pro_tracker_count = list.filter((x) => x.isPro).length;
    const nowIso = this.now().toISOString();
    if (!f.next_poll_at || Date.parse(f.next_poll_at) > Date.parse(nowIso)) f.next_poll_at = nowIso;
  }

  claimDueFlights(limit: number, leaseSeconds: number): Promise<FlightRow[]> {
    const now = this.now();
    const t = now.getTime();
    const ts = (v: string | null) => (v == null ? null : Date.parse(v));
    const due = [...this.flights.values()]
      .filter((f) =>
        f.next_poll_at != null && ts(f.next_poll_at)! <= t &&
        (f.tracker_count > 0 || (f.inbound_watch_until != null && ts(f.inbound_watch_until)! > t)) &&
        (f.lease_until == null || ts(f.lease_until)! < t)
      )
      .sort((a, b) => ts(a.next_poll_at)! - ts(b.next_poll_at)!)
      .slice(0, limit);
    for (const f of due) f.lease_until = new Date(now.getTime() + leaseSeconds * 1000).toISOString();
    return Promise.resolve(due.map((f) => structuredClone(f)));
  }

  getFlight(id: string) {
    const f = this.flights.get(id);
    return Promise.resolve(f ? structuredClone(f) : null);
  }

  getFlightByKey(c: string, n: string, d: string, dep: string) {
    const f = [...this.flights.values()].find((x) =>
      x.carrier_iata === c && x.flight_number === n && x.flight_date === d && x.dep_iata === dep
    );
    return Promise.resolve(f ? structuredClone(f) : null);
  }

  getFlightsByNumberDate(c: string, n: string, d: string) {
    return Promise.resolve(
      [...this.flights.values()].filter((x) => x.carrier_iata === c && x.flight_number === n && x.flight_date === d)
        .map((f) => structuredClone(f)),
    );
  }

  insertFlight(row: Partial<FlightRow>): Promise<FlightRow> {
    const existing = [...this.flights.values()].find((x) =>
      x.carrier_iata === row.carrier_iata && x.flight_number === row.flight_number &&
      x.flight_date === row.flight_date && x.dep_iata === row.dep_iata
    );
    if (existing) return Promise.resolve(structuredClone(existing));
    const id = `f${++this.seq}`;
    const full = { ...DEFAULT_ROW, ...row, id, updated_at: this.now().toISOString() } as FlightRow;
    this.flights.set(id, full);
    return Promise.resolve(structuredClone(full));
  }

  updateFlight(id: string, patch: Partial<FlightRow>): Promise<FlightRow> {
    const f = this.flights.get(id);
    if (!f) throw new Error(`no flight ${id}`);
    Object.assign(f, structuredClone(patch), { updated_at: this.now().toISOString() });
    return Promise.resolve(structuredClone(f));
  }

  insertHistory(flightId: string, source: string, changed: TrackedField[]) {
    this.history.push({ flightId, source, changed, at: this.now().toISOString() });
    return Promise.resolve();
  }

  insertEventIfNew(flightId: string, e: FlightEvent): Promise<string | null> {
    if (this.events.some((x) => x.dedupeKey === e.dedupeKey)) return Promise.resolve(null);
    const id = `e${++this.seq}`;
    this.events.push({ ...e, id, flightId, at: this.now().toISOString(), pushed: false });
    return Promise.resolve(id);
  }

  markEventsPushed(ids: string[]) {
    for (const e of this.events) if (ids.includes(e.id)) e.pushed = true;
    return Promise.resolve();
  }

  getUnpushedEvents(minAgeMin: number, maxAgeMin: number, limit: number) {
    const t = this.now().getTime();
    return Promise.resolve(this.events.filter((e) => {
      const age = (t - Date.parse(e.at)) / 60_000;
      return !e.pushed && age >= minAgeMin && age <= maxAgeMin;
    }).slice(0, limit).map((e) => structuredClone(e)));
  }

  getRecipients(flightId: string): Promise<Recipient[]> {
    const out: Recipient[] = [];
    for (const t of this.trackers.get(flightId) ?? []) {
      for (const d of t.devices) {
        out.push({ user_id: t.userId, is_pro: t.isPro, notify_prefs: t.prefs, device_id: d.id, fcm_token: d.token });
      }
    }
    return Promise.resolve(out);
  }

  getDependentsOfInbound(inboundId: string) {
    return Promise.resolve(
      [...this.flights.values()].filter((f) => f.inbound_flight_id === inboundId).map((f) => structuredClone(f)),
    );
  }

  logPushes(rows: PushLogRow[]) {
    this.pushes.push(...rows);
    return Promise.resolve();
  }

  deleteDeviceByToken(token: string) {
    for (const list of this.trackers.values()) {
      for (const t of list) t.devices = t.devices.filter((d) => d.token !== token);
    }
    return Promise.resolve();
  }

  getLiveActivities(flightId: string): Promise<LiveActivityTarget[]> {
    return Promise.resolve(this.liveActivities.filter((a) => a.flightId === flightId).map(({ flightId: _, ...a }) => a));
  }

  deleteLiveActivities(ids: string[]) {
    this.liveActivities = this.liveActivities.filter((a) => !ids.includes(a.id));
    return Promise.resolve();
  }

  airportTz(iata: string) {
    return Promise.resolve(this.tz[iata] ?? (iata === "DXB" ? "Asia/Dubai" : "Asia/Kolkata"));
  }

  logProviderCall(c: ProviderCallLog) {
    this.providerCalls.push({ ...c, at: this.now().toISOString() });
    return Promise.resolve();
  }

  countUserProviderCalls(userId: string, sinceIso: string) {
    return Promise.resolve(this.providerCalls.filter((c) => c.userId === userId && Date.parse(c.at) >= Date.parse(sinceIso)).length);
  }
}

export type { FcmMessage };
