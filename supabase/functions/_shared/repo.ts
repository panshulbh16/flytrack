import type { FlightEvent, FlightRow, Recipient, TrackedField } from "./types.ts";
import type { FcmMessage } from "./notify.ts";
import type { PushResult } from "./fcm.ts";
import { type CheckinRule, checkinWindow, isCheckinDue } from "./checkin.ts";
import type { LegHistoryRow } from "./history.ts";
import { type FogCandidate, fogCandidates, type FogWeather } from "./health.ts";
import type { AircraftRow } from "./aircraft.ts";
import type { ConnectionCandidate, ConnectionRisk } from "./connections.ts";

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
  kind: "alert" | "sync" | "test" | "live_activity";
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
  /** public.due_checkin_flights(): tracked flights with a check-in reminder due now (DB only). */
  getDueCheckins(limit: number): Promise<FlightRow[]>;
  /** The carrier's row of checkin_rules, or null. */
  getCheckinRule(carrier: string): Promise<CheckinRule | null>;
  /** Insert or replace the finished leg (flight_leg_history, keyed by flight_id). */
  upsertLegHistory(row: LegHistoryRow): Promise<void>;
  /** public.get_shared_flight(token): public fields, or null for an unknown / revoked / expired token. */
  getSharedFlight(token: string): Promise<Record<string, unknown> | null>;
  /** public.fog_alert_candidates(): tracked flights near a foggy airport without a FOG_ALERT yet (DB only). */
  getFogCandidates(limit: number): Promise<FogCandidate[]>;
  /** fetched_at of the public.aircraft row for this registration, or null when there is none (P3 A5). */
  getAircraftFetchedAt(reg: string): Promise<string | null>;
  /** Insert or replace one public.aircraft row. */
  upsertAircraft(row: AircraftRow): Promise<void>;
  /** public.connection_alert_candidates(): Pro users' connections whose risk differs from the stored one (P3 B4). */
  getConnectionCandidates(limit: number): Promise<ConnectionCandidate[]>;
  /** public.save_connection_state(): the risk seen and the alert generation of one connection. */
  saveConnectionState(inFlightId: string, outFlightId: string, risk: ConnectionRisk, gen: number): Promise<void>;
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
  checkin_opens_at: null, checkin_closes_at: null, checkin_open_notified: false, checkin_closing_notified: false,
  live_seen: false,
};

/** A share link as MemoryRepo keeps it (table flight_shares). */
export interface MemShare {
  token: string;
  userId: string;
  flightId: string;
  revokedAt: string | null;
  expiresAt: string;
}

export interface MemTracker {
  userId: string;
  isPro: boolean;
  prefs: Record<string, boolean>;
  devices: { id: string; token: string }[];
  /** profiles fields returned by get_flight_recipients (P2): language and quiet hours. */
  profile?: Pick<
    Recipient,
    "alert_language" | "quiet_hours_enabled" | "quiet_start" | "quiet_end" | "quiet_tz" | "crew_mode"
  >;
}

/**
 * A friend of one of the flight's trackers, as get_flight_recipients returns them (role "friend"): alerts on,
 * the tracker shares the flight. Users who track the flight themselves are dropped, as in SQL.
 */
export interface MemFriend {
  userId: string;
  /** The tracker's display name (friend_name), null when unset. */
  friendName: string | null;
  devices: { id: string; token: string }[];
  profile?: MemTracker["profile"];
}

export class MemoryRepo implements Repo {
  flights = new Map<string, FlightRow>();
  history: { flightId: string; source: string; changed: TrackedField[]; at: string }[] = [];
  events: (FlightEvent & { id: string; flightId: string; at: string; pushed: boolean })[] = [];
  pushes: PushLogRow[] = [];
  liveActivities: (LiveActivityTarget & { flightId: string })[] = [];
  providerCalls: (ProviderCallLog & { at: string })[] = [];
  trackers = new Map<string, MemTracker[]>(); // flightId -> trackers
  friends = new Map<string, MemFriend[]>(); // flightId -> friends of its trackers (friend alerts)
  tz: Record<string, string> = {};
  /** Airport countries for the domestic/international check-in close (unknown → "IN", DXB → "AE"). */
  country: Record<string, string> = {};
  /** checkin_rules. Empty by default so flows that don't test check-in see no reminders. */
  checkinRules = new Map<string, CheckinRule>();
  legHistory = new Map<string, LegHistoryRow>();
  shares: MemShare[] = [];
  /** airport_weather rows by IATA (fog alerts). */
  weather = new Map<string, FogWeather>();
  /** public.aircraft rows by registration. */
  aircraft = new Map<string, AircraftRow>();
  /**
   * Connections as connections_for_user() would compute them for Pro users (tests set these; risk included).
   * getConnectionCandidates() joins them with connectionState like the SQL function does.
   */
  connections: Omit<ConnectionCandidate, "last_risk" | "gen">[] = [];
  /** public.connection_alert_state by "in:out". */
  connectionState = new Map<string, { risk: ConnectionRisk; gen: number }>();
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

  /** Test helper: a friend of a tracker of this flight, with friend alerts on (get_flight_recipients). */
  addFriend(flightId: string, f: MemFriend) {
    this.friends.set(flightId, [...(this.friends.get(flightId) ?? []), f]);
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
    this.applyCheckinWindow(full);
    this.flights.set(id, full);
    return Promise.resolve(structuredClone(full));
  }

  updateFlight(id: string, patch: Partial<FlightRow>): Promise<FlightRow> {
    const f = this.flights.get(id);
    if (!f) throw new Error(`no flight ${id}`);
    Object.assign(f, structuredClone(patch), { updated_at: this.now().toISOString() });
    this.applyCheckinWindow(f);
    return Promise.resolve(structuredClone(f));
  }

  /** What the flights_checkin_window trigger does in SQL. */
  private applyCheckinWindow(f: FlightRow) {
    const countryOf = (iata: string) => this.country[iata] ?? (iata === "DXB" ? "AE" : "IN");
    const w = checkinWindow(this.checkinRules.get(f.carrier_iata), f, countryOf(f.dep_iata) !== countryOf(f.arr_iata));
    f.checkin_opens_at = w?.opensAt ?? null;
    f.checkin_closes_at = w?.closesAt ?? null;
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
        out.push({
          user_id: t.userId, is_pro: t.isPro, notify_prefs: t.prefs, device_id: d.id, fcm_token: d.token, ...t.profile,
          role: "tracker", friend_name: null,
        });
      }
    }
    const trackerIds = new Set((this.trackers.get(flightId) ?? []).map((t) => t.userId));
    const seen = new Set<string>();
    for (const f of this.friends.get(flightId) ?? []) {
      if (trackerIds.has(f.userId)) continue; // tracking it themselves: the tracker alert only
      for (const d of f.devices) {
        if (seen.has(d.id)) continue; // two friends track it: one row per device (the first one's name)
        seen.add(d.id);
        out.push({
          user_id: f.userId, is_pro: false, notify_prefs: {}, device_id: d.id, fcm_token: d.token, ...f.profile,
          role: "friend", friend_name: f.friendName,
        });
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
    for (const list of this.friends.values()) {
      for (const f of list) f.devices = f.devices.filter((d) => d.token !== token);
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

  getDueCheckins(limit: number): Promise<FlightRow[]> {
    const now = this.now();
    return Promise.resolve(
      [...this.flights.values()].filter((f) => isCheckinDue(f, now))
        .sort((a, b) => Date.parse(a.checkin_closes_at!) - Date.parse(b.checkin_closes_at!))
        .slice(0, limit).map((f) => structuredClone(f)),
    );
  }

  getCheckinRule(carrier: string) {
    const r = this.checkinRules.get(carrier);
    return Promise.resolve(r ? structuredClone(r) : null);
  }

  upsertLegHistory(row: LegHistoryRow) {
    this.legHistory.set(row.flight_id, structuredClone(row));
    return Promise.resolve();
  }

  /** Test helper = create_flight_share() without the auth checks. */
  share(token: string, userId: string, flightId: string, expiresAt: string) {
    this.shares.push({ token, userId, flightId, revokedAt: null, expiresAt });
  }

  getSharedFlight(token: string): Promise<Record<string, unknown> | null> {
    const now = this.now().getTime();
    const s = this.shares.find((x) => x.token === token && x.revokedAt == null);
    const f = s && this.flights.get(s.flightId);
    if (!s || !f) return Promise.resolve(null);
    const arr = Date.parse(f.act_arr ?? f.est_arr ?? f.sched_arr ?? "");
    const until = Math.max(Date.parse(s.expiresAt), Number.isNaN(arr) ? -Infinity : arr + 2 * 86400_000);
    if (until <= now) return Promise.resolve(null);
    const ap = (iata: string) => ({ iata, name: null, city: null, lat: null, lon: null, tz: this.tz[iata] ?? null });
    return Promise.resolve({
      carrier: f.carrier_iata, number: f.flight_number, date: f.flight_date, airline: f.airline_name, status: f.status,
      sched_dep: f.sched_dep, est_dep: f.est_dep, act_dep: f.act_dep, sched_arr: f.sched_arr, est_arr: f.est_arr, act_arr: f.act_arr,
      dep: { ...ap(f.dep_iata), terminal: f.dep_terminal, gate: f.dep_gate },
      arr: { ...ap(f.arr_iata), terminal: f.arr_terminal, gate: f.arr_gate, belt: f.baggage_belt },
      aircraft: { model: f.aircraft_model },
      live: f.live_lat != null && f.live_lon != null
        ? { lat: f.live_lat, lon: f.live_lon, alt_ft: f.live_alt_ft, speed_kts: f.live_speed_kts, heading: f.live_heading, at: f.live_updated_at }
        : null,
      updated_at: f.updated_at ?? null,
    });
  }

  getFogCandidates(limit: number): Promise<FogCandidate[]> {
    const sent = new Set(this.events.map((e) => e.dedupeKey));
    const tz = (iata: string) => this.tz[iata] ?? (iata === "DXB" ? "Asia/Dubai" : "Asia/Kolkata");
    return Promise.resolve(
      fogCandidates([...this.flights.values()], (iata) => this.weather.get(iata), tz, sent, this.now(), limit),
    );
  }

  getAircraftFetchedAt(reg: string): Promise<string | null> {
    return Promise.resolve(this.aircraft.get(reg)?.fetched_at ?? null);
  }

  upsertAircraft(row: AircraftRow) {
    this.aircraft.set(row.reg, structuredClone(row));
    return Promise.resolve();
  }

  getConnectionCandidates(limit: number): Promise<ConnectionCandidate[]> {
    const out: ConnectionCandidate[] = [];
    for (const c of this.connections) {
      const st = this.connectionState.get(`${c.in_flight_id}:${c.out_flight_id}`);
      if (st?.risk === c.risk) continue;
      out.push({ ...structuredClone(c), last_risk: st?.risk ?? null, gen: st?.gen ?? 0 });
    }
    return Promise.resolve(out.slice(0, limit));
  }

  saveConnectionState(inFlightId: string, outFlightId: string, risk: ConnectionRisk, gen: number) {
    const key = `${inFlightId}:${outFlightId}`;
    const prev = this.connectionState.get(key);
    this.connectionState.set(key, { risk, gen: Math.max(prev?.gen ?? 0, gen) });
    return Promise.resolve();
  }
}

export type { FcmMessage };
