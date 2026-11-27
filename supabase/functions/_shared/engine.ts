import { changedFields, evaluateAlerts, evaluateInbound, snapshotState } from "./diff.ts";
import {
  buildAlertMessage,
  buildLiveActivityMessage,
  buildSyncMessage,
  liveActivityDone,
  type Tz,
  withFlightContext,
} from "./notify.ts";
import type { Pusher } from "./fcm.ts";
import type { FlightDataProvider } from "./providers/provider.ts";
import { ProviderError } from "./providers/provider.ts";
import { shiftDate } from "./providers/mock.ts";
import type { PushLogRow, Repo } from "./repo.ts";
import { nextPollAt } from "./schedule.ts";
import { decideCheckin } from "./checkin.ts";
import { isQuietFor } from "./quiet-hours.ts";
import { legHistoryRow, shouldFinalize } from "./history.ts";
import { fogEvent } from "./health.ts";
import { isFriendEvent } from "./friend-alerts.ts";
import { crewMutes } from "./crew.ts";
import { aircraftRow, aircraftStale, normalizeReg } from "./aircraft.ts";
import { decideConnection, groupConnections } from "./connections.ts";
import type { FlightEvent, FlightRow, FlightSnapshot } from "./types.ts";

const MIN = 60_000;

export interface EngineConfig {
  leaseSeconds: number;
  batchSize: number;
  concurrency: number;
  minTurnMin: number; // minimum aircraft turnaround for inbound prediction
  inboundLookaheadHours: number; // start "where's my plane" this many hours before departure
  webhookCallbackUrl: string | null; // set => subscribe to provider webhooks
}

export const DEFAULT_CONFIG: EngineConfig = {
  leaseSeconds: 120,
  batchSize: 10,
  concurrency: 6,
  minTurnMin: 35,
  inboundLookaheadHours: 10,
  webhookCallbackUrl: null,
};

export interface Deps {
  repo: Repo;
  provider: FlightDataProvider;
  pusher: Pusher;
  now: () => Date;
  config: EngineConfig;
  log?: (msg: string, extra?: Record<string, unknown>) => void;
}

// ---------- mapping between snake_case rows and normalized snapshots ----------

export function rowToSnapshot(r: FlightRow): FlightSnapshot {
  return {
    carrier: r.carrier_iata, number: r.flight_number, date: r.flight_date, depIata: r.dep_iata, arrIata: r.arr_iata,
    carrierIcao: r.carrier_icao, airlineName: r.airline_name, status: r.status,
    schedDep: r.sched_dep, estDep: r.est_dep, actDep: r.act_dep,
    depTerminal: r.dep_terminal, depGate: r.dep_gate, depCheckin: r.dep_checkin,
    schedArr: r.sched_arr, estArr: r.est_arr, actArr: r.act_arr,
    arrTerminal: r.arr_terminal, arrGate: r.arr_gate, baggageBelt: r.baggage_belt,
    aircraftModel: r.aircraft_model, aircraftReg: r.aircraft_reg, delayReason: r.delay_reason,
    depOffblockAt: r.dep_offblock_at ?? null, depRunwayAt: r.dep_runway_at ?? null,
    arrRunwayAt: r.arr_runway_at ?? null, arrOnblockAt: r.arr_onblock_at ?? null,
    live: r.live_lat != null && r.live_lon != null
      ? { lat: r.live_lat, lon: r.live_lon, altFt: r.live_alt_ft, headingDeg: r.live_heading, speedKts: r.live_speed_kts, at: r.live_updated_at ?? "" }
      : null,
    providerRef: r.provider_ref, providerUpdatedAt: r.provider_updated_at,
  };
}

/** Columns written from a provider snapshot. NOTE: never alert_state/prediction (owned elsewhere). */
export function snapshotToPatch(s: FlightSnapshot, provider: string): Partial<FlightRow> {
  return {
    carrier_iata: s.carrier, flight_number: s.number, flight_date: s.date, dep_iata: s.depIata, arr_iata: s.arrIata,
    carrier_icao: s.carrierIcao, airline_name: s.airlineName, status: s.status,
    sched_dep: s.schedDep, est_dep: s.estDep, act_dep: s.actDep,
    dep_terminal: s.depTerminal, dep_gate: s.depGate, dep_checkin: s.depCheckin,
    sched_arr: s.schedArr, est_arr: s.estArr, act_arr: s.actArr,
    arr_terminal: s.arrTerminal, arr_gate: s.arrGate, baggage_belt: s.baggageBelt,
    aircraft_model: s.aircraftModel, aircraft_reg: s.aircraftReg, delay_reason: s.delayReason,
    dep_offblock_at: s.depOffblockAt ?? null, dep_runway_at: s.depRunwayAt ?? null,
    arr_runway_at: s.arrRunwayAt ?? null, arr_onblock_at: s.arrOnblockAt ?? null,
    live_lat: s.live?.lat ?? null, live_lon: s.live?.lon ?? null, live_alt_ft: s.live?.altFt ?? null,
    live_heading: s.live?.headingDeg ?? null, live_speed_kts: s.live?.speedKts ?? null,
    live_updated_at: s.live?.at ?? null,
    provider, provider_ref: s.providerRef, provider_updated_at: s.providerUpdatedAt,
  };
}

/**
 * Providers sometimes drop a field they sent before (gate → null). Keep the last known
 * gate/terminal/belt/tail so the card doesn't flicker, unless the flight was cancelled.
 */
function mergeSticky(prev: FlightSnapshot | null, next: FlightSnapshot): FlightSnapshot {
  if (!prev || next.status === "cancelled") return next;
  const out = { ...next };
  for (const k of ["depTerminal", "depGate", "arrTerminal", "baggageBelt", "aircraftReg", "aircraftModel", "schedDep", "schedArr"] as const) {
    if (out[k] == null && prev[k] != null) out[k] = prev[k];
  }
  // actual times never go back to null once known
  for (const k of ["actDep", "actArr", "depOffblockAt", "depRunwayAt", "arrRunwayAt", "arrOnblockAt"] as const) {
    if (out[k] == null && prev[k] != null) out[k] = prev[k];
  }
  return out;
}

/** True when the provider says this snapshot is older than what we already stored. */
export function isStaleSnapshot(existing: FlightRow, snap: FlightSnapshot): boolean {
  if (!snap.providerUpdatedAt || !existing.provider_updated_at) return false;
  return Date.parse(snap.providerUpdatedAt) < Date.parse(existing.provider_updated_at);
}

const isInboundOnly = (r: FlightRow, now: Date) =>
  r.tracker_count === 0 && r.inbound_watch_until != null && Date.parse(r.inbound_watch_until) > now.getTime();

const hasProInterest = (r: FlightRow, now: Date) =>
  r.pro_tracker_count > 0 || (r.inbound_watch_until != null && Date.parse(r.inbound_watch_until) > now.getTime());

function isNearFlight(r: FlightRow, now: Date): boolean {
  const dep = Date.parse(r.est_dep ?? r.sched_dep ?? "");
  const arr = Date.parse(r.act_arr ?? r.est_arr ?? r.sched_arr ?? "");
  const t = now.getTime();
  return !Number.isNaN(dep) && t >= dep - 3 * 60 * MIN && (Number.isNaN(arr) || t <= arr + 60 * MIN);
}

// ---------- ingest: the ONE path every snapshot goes through (poll, webhook, search) ----------

export interface IngestResult {
  row: FlightRow;
  events: (FlightEvent & { id: string })[];
  changed: string[];
}

export async function ingest(
  deps: Deps,
  existing: FlightRow | null,
  rawSnap: FlightSnapshot,
  source: "poll" | "webhook" | "search" | "inbound",
): Promise<IngestResult> {
  const now = deps.now();
  const nowIso = now.toISOString();
  const prev = existing ? rowToSnapshot(existing) : null;
  const snap = mergeSticky(prev, rawSnap);
  const changed = changedFields(prev, snap);

  if (!existing) {
    const row = await deps.repo.insertFlight({
      ...snapshotToPatch(snap, deps.provider.name),
      alert_state: snapshotState(snap),
      live_seen: !!snap.live,
      last_polled_at: nowIso,
      next_poll_at: nextPollAt(snap, { now, hasPro: false, webhookActive: false, changedThisPoll: false, errorCount: 0 })
        ?.toISOString() ?? null,
    });
    await deps.repo.insertHistory(row.id, source, changed, snap);
    return { row, events: [], changed };
  }

  // A late webhook / registration lookup / search can carry OLDER data than the row → never let it win.
  if (isStaleSnapshot(existing, rawSnap)) {
    await deps.repo.insertHistory(existing.id, `${source}_stale`, [], rawSnap);
    if (source === "poll") {
      const next = nextPollAt(prev!, {
        now, hasPro: hasProInterest(existing, now), webhookActive: false, changedThisPoll: false, errorCount: 0,
        inboundOnly: isInboundOnly(existing, now),
      });
      const row = await deps.repo.updateFlight(existing.id, {
        lease_until: null, last_polled_at: nowIso, next_poll_at: next?.toISOString() ?? null,
      });
      await finalizeIfStopped(deps, existing, row);
    }
    return { row: existing, events: [], changed: [] };
  }

  const { events, state } = evaluateAlerts(existing.id, existing.alert_state ?? {}, prev, snap);
  const webhookActive = !!existing.webhook_subscription_id &&
    (!existing.webhook_expires_at || Date.parse(existing.webhook_expires_at) > now.getTime());
  const next = nextPollAt(snap, {
    now, hasPro: hasProInterest(existing, now), webhookActive, changedThisPoll: changed.length > 0, errorCount: 0,
    inboundOnly: isInboundOnly(existing, now),
  });

  // Write event rows BEFORE moving alert_state forward: if we crash in between, the next poll re-derives
  // the same keys (dedupe) and the sweeper (sweepUnpushedEvents) delivers anything inserted-but-unpushed.
  const inserted: (FlightEvent & { id: string })[] = [];
  for (const e of events) {
    const id = await deps.repo.insertEventIfNew(existing.id, e); // DB unique key = exactly-once
    if (id) inserted.push({ ...e, id });
  }

  const row = await deps.repo.updateFlight(existing.id, {
    ...snapshotToPatch(snap, deps.provider.name),
    alert_state: state,
    last_polled_at: nowIso,
    next_poll_at: next?.toISOString() ?? null,
    lease_until: null,
    poll_error_count: 0,
    last_error: null,
    ...(snap.live && !existing.live_seen ? { live_seen: true } : {}),
  });
  if (changed.length) await deps.repo.insertHistory(row.id, source, changed, snap);
  await finalizeIfStopped(deps, existing, row);

  if (inserted.length || changed.length) await fanOut(deps, row, inserted, changed.length > 0);
  return { row, events: inserted, changed };
}

// ---------- push fan-out (sent immediately, inside the same invocation = "instant") ----------

/** onlyUsers: alerts go to these users only (CONNECTION_AT_RISK concerns the users who have that connection). */
export async function fanOut(
  deps: Deps,
  row: FlightRow,
  events: (FlightEvent & { id: string })[],
  sendSync: boolean,
  target: { onlyUsers?: ReadonlySet<string> } = {},
) {
  const recipients = await deps.repo.getRecipients(row.id);
  // A Live Activity the user started keeps updating even if they muted notifications on that phone.
  const activities = sendSync ? await deps.repo.getLiveActivities(row.id) : [];
  if (!recipients.length && !activities.length) {
    if (events.length) await deps.repo.markEventsPushed(events.map((e) => e.id));
    return;
  }
  const tz: Tz = { dep: await deps.repo.airportTz(row.dep_iata), arr: await deps.repo.airportTz(row.arr_iata) };
  const jobs: {
    kind: PushLogRow["kind"];
    eventId: string | null;
    r: { device_id: string; user_id: string; fcm_token: string };
    liveActivityId?: string;
    msg: ReturnType<typeof buildSyncMessage>;
  }[] = [];

  for (const e of events) {
    for (const r of recipients) {
      const friend = r.role === "friend";
      // Friends of a tracker get only the free milestone events (P2 wave 2 section 1), never Pro ones.
      if (friend ? !isFriendEvent(e) : e.proOnly && !r.is_pro) continue;
      if (target.onlyUsers && !target.onlyUsers.has(r.user_id)) continue;
      if (r.notify_prefs?.[e.type] === false) continue; // user muted this alert type
      if (crewMutes(r, e)) continue; // crew mode: no check-in / boarding alerts (P3 B12)
      // Each recipient gets their own language; info alerts go silent in their quiet hours (P2 sections 3, 4).
      const opts = {
        lang: r.alert_language ?? "en",
        quiet: isQuietFor(r, deps.now()),
        ...(friend ? { friend: { name: r.friend_name ?? null } } : {}),
      };
      const msg = buildAlertMessage(r.fcm_token, row.id, e.id, withFlightContext(e, row), tz, opts);
      jobs.push({ kind: "alert", eventId: e.id, r, msg });
    }
  }
  if (sendSync) {
    const near = isNearFlight(row, deps.now());
    const seen = new Set<string>();
    for (const r of recipients) {
      // Sync messages feed the app's cache of the user's OWN flights: a friend's flight must not land there.
      if (r.role === "friend") continue;
      if (seen.has(r.fcm_token)) continue;
      seen.add(r.fcm_token);
      jobs.push({ kind: "sync", eventId: null, r, msg: buildSyncMessage(r.fcm_token, row, near) });
    }
    for (const a of activities) {
      const msg = buildLiveActivityMessage(a.fcm_token, a.activity_token, row, deps.now(), events.length > 0);
      jobs.push({ kind: "live_activity", eventId: null, r: a, liveActivityId: a.id, msg });
    }
  }

  // Alerts first (they are what users feel), all in parallel.
  const rank = (k: PushLogRow["kind"]) => (k === "alert" ? 0 : 1);
  jobs.sort((a, b) => rank(a.kind) - rank(b.kind));
  const results = await Promise.all(jobs.map((j) => deps.pusher.send(j.msg)));
  const logs: PushLogRow[] = [];
  const dead = new Set<string>();
  const doneActivities: string[] = [];
  const ended = liveActivityDone(row);
  results.forEach((res, i) => {
    const j = jobs[i];
    logs.push({ eventId: j.eventId, deviceId: j.r.device_id, userId: j.r.user_id, kind: j.kind, result: res });
    if (j.liveActivityId) {
      // A rejected token here is usually the activity's (dismissed on the phone), not the FCM token:
      // drop the activity, keep the device. A failed "end" stays for the next change to retry.
      if (res.invalidToken || (ended && res.ok)) doneActivities.push(j.liveActivityId);
    } else if (res.invalidToken) dead.add(j.r.fcm_token);
  });
  await deps.repo.logPushes(logs);
  for (const t of dead) await deps.repo.deleteDeviceByToken(t);
  await deps.repo.deleteLiveActivities(doneActivities);
  if (events.length) await deps.repo.markEventsPushed(events.map((e) => e.id));
}

// ---------- polling ----------

function pickLeg(snaps: FlightSnapshot[], row: FlightRow): FlightSnapshot | null {
  return snaps.find((s) => s.depIata === row.dep_iata) ?? (snaps.length === 1 && !row.dep_iata ? snaps[0] : null);
}

export async function pollFlight(deps: Deps, row: FlightRow): Promise<{ ok: boolean; events: number }> {
  const now = deps.now();
  let snaps: FlightSnapshot[];
  try {
    snaps = await deps.provider.getFlight({
      carrier: row.carrier_iata, number: row.flight_number, date: row.flight_date, depIata: row.dep_iata,
    });
  } catch (e) {
    await recordPollError(deps, row, e instanceof ProviderError ? `${e.status ?? ""} ${e.message}` : String(e));
    return { ok: false, events: 0 };
  }
  const snap = pickLeg(snaps, row);
  if (!snap) {
    await recordPollError(deps, row, "not_found");
    return { ok: false, events: 0 };
  }
  const res = await ingest(deps, row, snap, "poll");
  let events = res.events.length;

  try {
    events += await maybeLinkInbound(deps, res.row);
    if (res.row.inbound_watch_until && Date.parse(res.row.inbound_watch_until) > now.getTime()) {
      events += await updateDependents(deps, res.row);
    }
    await maybeManageWebhook(deps, res.row);
  } catch (e) {
    deps.log?.("inbound/webhook step failed", { flight: row.id, error: String(e) });
  }
  try {
    await maybeFetchAircraft(deps, res.row);
  } catch (e) {
    deps.log?.("aircraft lookup failed", { flight: row.id, error: String(e) }); // never fail a poll for it
  }
  return { ok: true, events };
}

async function recordPollError(deps: Deps, row: FlightRow, message: string) {
  const errorCount = (row.poll_error_count ?? 0) + 1;
  const next = nextPollAt(rowToSnapshot(row), {
    now: deps.now(), hasPro: hasProInterest(row, deps.now()), webhookActive: false, changedThisPoll: false, errorCount,
  });
  const updated = await deps.repo.updateFlight(row.id, {
    poll_error_count: errorCount, last_error: message.slice(0, 500), lease_until: null,
    last_polled_at: deps.now().toISOString(), next_poll_at: next?.toISOString() ?? null,
  });
  deps.log?.("poll error", { flight: row.id, errorCount, message });
  await finalizeIfStopped(deps, row, updated);
}

/** Polling just stopped (next_poll_at → null): keep the finished leg for history and coverage stats. */
async function finalizeIfStopped(deps: Deps, before: FlightRow, after: FlightRow) {
  if (!shouldFinalize(before, after)) return;
  try {
    await deps.repo.upsertLegHistory(legHistoryRow(after, deps.now()));
  } catch (e) {
    deps.log?.("leg history failed", { flight: after.id, error: String(e) }); // never fail a poll for stats
  }
}

/**
 * Pro: age and type of the tail (P3 A5). Looked up once per registration and 30 days, the first time a
 * Pro-tracked flight has a tail; a tail swap brings a new registration and so a new lookup.
 */
export async function maybeFetchAircraft(deps: Deps, row: FlightRow): Promise<boolean> {
  const reg = normalizeReg(row.aircraft_reg);
  if (row.pro_tracker_count <= 0 || !reg || !deps.provider.getAircraft) return false;
  const now = deps.now();
  if (!aircraftStale(await deps.repo.getAircraftFetchedAt(reg), now)) return false;
  const info = await deps.provider.getAircraft(reg);
  await deps.repo.upsertAircraft(aircraftRow(reg, info, now));
  return true;
}

/** Pro: find the aircraft's previous leg ("Where's my plane?") and link it. Returns events sent. */
const inboundRegChanged = (r: FlightRow) =>
  !!r.inbound_flight_id && !!r.aircraft_reg && !!r.prediction?.inboundReg && r.prediction.inboundReg !== r.aircraft_reg;

export async function maybeLinkInbound(deps: Deps, row: FlightRow): Promise<number> {
  const now = deps.now();
  const reg = row.aircraft_reg;
  if (row.pro_tracker_count <= 0 || !reg || row.act_dep || row.status === "cancelled") return 0;
  const dep = Date.parse(row.est_dep ?? row.sched_dep ?? "");
  if (Number.isNaN(dep) || dep - now.getTime() > deps.config.inboundLookaheadHours * 60 * MIN) return 0;

  // Tail swap: the airline put a different aircraft on our flight → the old inbound link is wrong NOW.
  if (inboundRegChanged(row)) {
    row = await deps.repo.updateFlight(row.id, { inbound_flight_id: null, prediction: null, inbound_checked_at: null });
  }
  // Re-check at most hourly while unlinked, every 3h while linked.
  const last = row.inbound_checked_at ? Date.parse(row.inbound_checked_at) : 0;
  const linked = !!row.inbound_flight_id && row.prediction?.inboundReg === row.aircraft_reg;
  if (now.getTime() - last < (linked ? 180 : 60) * MIN) return 0;

  let legs = await deps.provider.getFlightsByRegistration(reg, row.flight_date);
  // NB: through-flights reuse the same number for the inbound leg (6E 123 BOM→DEL→BLR) → compare dep airport too.
  const isSameLeg = (l: FlightSnapshot) =>
    l.carrier === row.carrier_iata && l.number === row.flight_number && l.date === row.flight_date && l.depIata === row.dep_iata;
  const pick = (ls: FlightSnapshot[]) =>
    ls.filter((l) =>
      l.arrIata === row.dep_iata && l.schedArr && row.sched_dep && Date.parse(l.schedArr) < Date.parse(row.sched_dep) &&
      !isSameLeg(l)
    ).sort((a, b) => Date.parse(b.schedArr!) - Date.parse(a.schedArr!))[0];
  let leg = pick(legs);
  if (!leg) {
    legs = await deps.provider.getFlightsByRegistration(reg, shiftDate(row.flight_date, -1));
    leg = pick(legs);
  }

  if (!leg) {
    await deps.repo.updateFlight(row.id, { inbound_checked_at: now.toISOString() });
    return 0;
  }
  const existing = await deps.repo.getFlightByKey(leg.carrier, leg.number, leg.date, leg.depIata);
  const { row: inbound } = await ingest(deps, existing, leg, "inbound");
  const watchUntil = new Date(dep + 30 * MIN).toISOString();
  await deps.repo.updateFlight(inbound.id, {
    inbound_watch_until: inbound.inbound_watch_until && Date.parse(inbound.inbound_watch_until) > Date.parse(watchUntil)
      ? inbound.inbound_watch_until
      : watchUntil,
    // poll the inbound leg soon so the prediction is fresh
    next_poll_at: inbound.next_poll_at && Date.parse(inbound.next_poll_at) < now.getTime() + 5 * MIN
      ? inbound.next_poll_at
      : new Date(now.getTime() + 5 * MIN).toISOString(),
  });
  await deps.repo.updateFlight(row.id, {
    inbound_flight_id: inbound.id,
    inbound_checked_at: now.toISOString(),
    prediction: { ...(row.prediction ?? {}), inboundReg: row.aircraft_reg },
  });
  const fresh = await deps.repo.getFlight(inbound.id);
  return fresh ? await updateDependents(deps, fresh) : 0;
}

/** The inbound flight changed → re-predict every Pro-tracked flight it will operate next. */
export async function updateDependents(deps: Deps, inbound: FlightRow): Promise<number> {
  const now = deps.now();
  let sent = 0;
  for (const d of await deps.repo.getDependentsOfInbound(inbound.id)) {
    if (d.pro_tracker_count <= 0 || inboundRegChanged(d)) continue; // stale link after tail swap → no alerts
    const res = evaluateInbound(d.id, d.prediction, rowToSnapshot(d), rowToSnapshot(inbound), now, deps.config.minTurnMin);
    const eventId = res.event ? await deps.repo.insertEventIfNew(d.id, res.event) : null; // before state write
    const row = await deps.repo.updateFlight(d.id, { prediction: { ...res.prediction, inboundReg: d.prediction?.inboundReg ?? d.aircraft_reg } });
    if (res.event && eventId) {
      await fanOut(deps, row, [{ ...res.event, id: eventId }], true);
      sent++;
    }
  }
  return sent;
}

/** Webhooks are best-effort, so they only ever ADD speed; polling stays as the safety net. */
async function maybeManageWebhook(deps: Deps, row: FlightRow) {
  const p = deps.provider;
  const url = deps.config.webhookCallbackUrl;
  if (!p.supportsWebhooks || !url || !p.subscribe) return;
  const finished = row.next_poll_at == null;
  if (row.webhook_subscription_id && (finished || row.tracker_count === 0)) {
    await p.unsubscribe?.(row.webhook_subscription_id);
    await deps.repo.updateFlight(row.id, { webhook_subscription_id: null, webhook_expires_at: null });
    return;
  }
  const dep = Date.parse(row.est_dep ?? row.sched_dep ?? "");
  const within = !Number.isNaN(dep) && dep - deps.now().getTime() < 30 * 60 * MIN;
  if (!row.webhook_subscription_id && row.tracker_count > 0 && within && !finished) {
    const sub = await p.subscribe({ carrier: row.carrier_iata, number: row.flight_number, date: row.flight_date }, url);
    await deps.repo.updateFlight(row.id, { webhook_subscription_id: sub.id, webhook_expires_at: sub.expiresAt });
  }
}

/** Deliver events that were inserted but never pushed (crash/timeout between insert and fan-out). */
export async function sweepUnpushedEvents(deps: Deps): Promise<number> {
  const stuck = await deps.repo.getUnpushedEvents(2, 60, 50); // older than 2 min, younger than 60 min
  // A check-in reminder whose window already closed is useless (the airline page says "closed"): drop it.
  const now = deps.now().getTime();
  const expired = stuck.filter((e) =>
    (e.type === "CHECKIN_OPEN" || e.type === "CHECKIN_CLOSING") &&
    Date.parse(String((e.payload as Record<string, unknown>)?.closes_at ?? "")) <= now
  );
  if (expired.length) await deps.repo.markEventsPushed(expired.map((e) => e.id));
  const byFlight = new Map<string, (FlightEvent & { id: string })[]>();
  for (const e of stuck) {
    if (expired.includes(e)) continue;
    byFlight.set(e.flightId, [...(byFlight.get(e.flightId) ?? []), e]);
  }
  let n = 0;
  for (const [flightId, evs] of byFlight) {
    const row = await deps.repo.getFlight(flightId);
    if (!row) continue;
    // CONNECTION_AT_RISK concerns only the users who have that connection: the trackers of both legs
    // (runConnectionAlerts targets them the same way), never every Pro tracker of the arriving flight.
    const conn = evs.filter((e) => e.type === "CONNECTION_AT_RISK");
    const rest = evs.filter((e) => e.type !== "CONNECTION_AT_RISK");
    if (rest.length) await fanOut(deps, row, rest, false);
    for (const e of conn) {
      const outId = (e.payload as Record<string, unknown> | null)?.out_flight_id;
      const users = typeof outId === "string"
        ? new Set((await deps.repo.getRecipients(outId)).filter((r) => r.role !== "friend").map((r) => r.user_id))
        : new Set<string>();
      if (users.size) await fanOut(deps, row, [e], false, { onlyUsers: users });
      else await deps.repo.markEventsPushed([e.id]); // nobody to tell (connection gone): don't retry it
    }
    n += evs.length;
  }
  if (n) deps.log?.("swept unpushed events", { n });
  return n;
}

/**
 * Check-in reminders (CHECKIN_OPEN / CHECKIN_CLOSING). DB only: the window comes from checkin_rules via
 * the flights trigger, so this never calls the provider. Event row first, then the flags, then the push:
 * a crash in between is covered by the dedupe key and the sweeper, like every other alert.
 */
export async function runCheckinReminders(deps: Deps, limit = 100): Promise<number> {
  const now = deps.now();
  let sent = 0;
  for (const row of await deps.repo.getDueCheckins(limit)) {
    try {
      const rule = await deps.repo.getCheckinRule(row.carrier_iata);
      const d = decideCheckin(row, rule?.checkin_url ?? null, now);
      if (!d) continue;
      const id = await deps.repo.insertEventIfNew(row.id, d.event);
      const updated = await deps.repo.updateFlight(row.id, d.patch);
      if (id) {
        await fanOut(deps, updated, [{ ...d.event, id }], false);
        sent++;
      }
    } catch (e) {
      deps.log?.("check-in reminder failed", { flight: row.id, error: String(e) });
    }
  }
  if (sent) deps.log?.("check-in reminders", { sent });
  return sent;
}

/**
 * Fog alerts (FOG_ALERT). DB only: candidates come from airport_weather (kept fresh by
 * refresh-airport-weather) joined with tracked flights, so this never calls the provider. The dedupe key
 * {flight}:FOG:{iata}:{local date} makes it once per flight and airport; the sweeper covers a crash
 * between the event insert and the push.
 */
export async function runFogAlerts(deps: Deps, limit = 100): Promise<number> {
  let sent = 0;
  for (const c of await deps.repo.getFogCandidates(limit)) {
    try {
      // Load the flight first so the stored payload carries flight/dep/arr like every other event
      // (the in-app event list reads the stored payload, not the push).
      const row = await deps.repo.getFlight(c.flight_id);
      if (!row) continue;
      const base = fogEvent(c);
      const event = {
        ...base,
        payload: { flight: `${row.carrier_iata} ${row.flight_number}`, dep: row.dep_iata, arr: row.arr_iata, ...base.payload },
      };
      const id = await deps.repo.insertEventIfNew(c.flight_id, event);
      if (!id) continue;
      await fanOut(deps, row, [{ ...event, id }], false);
      sent++;
    } catch (e) {
      deps.log?.("fog alert failed", { flight: c.flight_id, airport: c.airport, error: String(e) });
    }
  }
  if (sent) deps.log?.("fog alerts", { sent });
  return sent;
}

/**
 * Connection alerts (CONNECTION_AT_RISK, Pro). DB only: candidates come from connection_alert_candidates()
 * (trips and risk derived in SQL from tracked flights). Event row first, then the stored state, then the push
 * to the users who have that connection; a crash in between is covered by the dedupe key and the sweeper.
 */
export async function runConnectionAlerts(deps: Deps, limit = 100): Promise<number> {
  let sent = 0;
  for (const { c, users } of groupConnections(await deps.repo.getConnectionCandidates(limit))) {
    try {
      const d = decideConnection(c);
      if (!d.event) {
        await deps.repo.saveConnectionState(c.in_flight_id, c.out_flight_id, d.risk, d.gen);
        continue;
      }
      const row = await deps.repo.getFlight(c.in_flight_id);
      if (!row) continue;
      const event = {
        ...d.event,
        payload: { flight: `${row.carrier_iata} ${row.flight_number}`, dep: row.dep_iata, arr: row.arr_iata, ...d.event.payload },
      };
      const id = await deps.repo.insertEventIfNew(row.id, event);
      await deps.repo.saveConnectionState(c.in_flight_id, c.out_flight_id, d.risk, d.gen);
      if (!id) continue;
      await fanOut(deps, row, [{ ...event, id }], false, { onlyUsers: users });
      sent++;
    } catch (e) {
      deps.log?.("connection alert failed", { in: c.in_flight_id, out: c.out_flight_id, error: String(e) });
    }
  }
  if (sent) deps.log?.("connection alerts", { sent });
  return sent;
}

/** One cron tick: claim due flights in small batches until the time budget is used. */
export async function runPollCycle(deps: Deps, timeBudgetMs = 25_000) {
  const started = Date.now();
  const stats = { polled: 0, errors: 0, events: 0, batches: 0, swept: 0, checkins: 0, fog: 0, connections: 0 };
  try {
    stats.swept = await sweepUnpushedEvents(deps);
  } catch (e) {
    deps.log?.("sweep failed", { error: String(e) });
  }
  try {
    stats.checkins = await runCheckinReminders(deps);
  } catch (e) {
    deps.log?.("check-in step failed", { error: String(e) });
  }
  try {
    stats.fog = await runFogAlerts(deps);
  } catch (e) {
    deps.log?.("fog step failed", { error: String(e) });
  }
  while (Date.now() - started < timeBudgetMs) {
    const batch = await deps.repo.claimDueFlights(deps.config.batchSize, deps.config.leaseSeconds);
    if (!batch.length) break;
    stats.batches++;
    await pool(batch, deps.config.concurrency, async (f) => {
      try {
        const r = await pollFlight(deps, f);
        stats.polled++;
        stats.events += r.events;
        if (!r.ok) stats.errors++;
      } catch (e) {
        stats.errors++;
        deps.log?.("pollFlight crashed", { flight: f.id, error: String(e) });
        await recordPollError(deps, f, String(e)).catch(() => {});
      }
    });
  }
  // After the polls, so a delay ingested in this tick is judged in the same tick.
  try {
    stats.connections = await runConnectionAlerts(deps);
  } catch (e) {
    deps.log?.("connection step failed", { error: String(e) });
  }
  return stats;
}

async function pool<T>(items: T[], n: number, fn: (t: T) => Promise<void>) {
  let i = 0;
  const workers = Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) await fn(items[i++]);
  });
  await Promise.all(workers);
}

// ---------- webhook ingest ----------

export async function handleWebhook(deps: Deps, body: unknown) {
  const snaps = deps.provider.parseWebhook?.(body) ?? [];
  let matched = 0, events = 0;
  for (const s of snaps) {
    const row = await deps.repo.getFlightByKey(s.carrier, s.number, s.date, s.depIata);
    if (!row) continue; // not a flight anyone tracks (subscriptions are per number, not per date)
    matched++;
    const res = await ingest(deps, row, s, "webhook");
    events += res.events.length;
    if (res.row.inbound_watch_until && Date.parse(res.row.inbound_watch_until) > deps.now().getTime()) {
      events += await updateDependents(deps, res.row);
    }
  }
  return { received: snaps.length, matched, events };
}

// ---------- search (add-flight screen) ----------

export class SearchError extends Error {
  constructor(public readonly code: "bad_input" | "rate_limited" | "not_found" | "provider_error", msg: string) {
    super(msg);
  }
}

/** "6E 2341", "6e-2341", "AI 101", "QP1372" → { carrier, number } */
export function parseFlightNumber(input: string): { carrier: string; number: string } | null {
  const m = /^\s*([A-Z0-9]{2})\s*-?\s*(\d{1,4})([A-Z]?)\s*$/i.exec(input);
  if (!m) return null;
  const carrier = m[1].toUpperCase();
  if (/^\d\d$/.test(carrier)) return null; // IATA airline codes are never two digits
  return { carrier, number: `${Number(m[2])}${m[3].toUpperCase()}` };
}

export interface SearchLimits {
  perUserPerDay: number;
  freshForMinutes: number;
}

export async function searchFlight(
  deps: Deps,
  userId: string,
  input: { flight: string; date: string },
  limits: SearchLimits = { perUserPerDay: 30, freshForMinutes: 10 },
): Promise<FlightRow[]> {
  const fn = parseFlightNumber(input.flight ?? "");
  if (!fn) throw new SearchError("bad_input", "Flight number looks wrong. Example: 6E 2341");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.date ?? "")) throw new SearchError("bad_input", "Date must be YYYY-MM-DD");
  const now = deps.now();
  const day = Date.parse(`${input.date}T00:00:00Z`);
  if (Number.isNaN(day) || day < now.getTime() - 2 * 86400_000 || day > now.getTime() + 365 * 86400_000) {
    throw new SearchError("bad_input", "Date must be between yesterday and one year ahead");
  }

  // 1) Cache: if we polled this flight recently, don't spend an API call.
  const cached = await deps.repo.getFlightsByNumberDate(fn.carrier, fn.number, input.date);
  const fresh = cached.length > 0 && cached.every((r) => {
    const dep = Date.parse(r.est_dep ?? r.sched_dep ?? "");
    const far = !Number.isNaN(dep) && dep - now.getTime() > 24 * 60 * MIN; // far-future data barely changes
    const maxAge = (far ? 360 : limits.freshForMinutes) * MIN;
    return r.last_polled_at && now.getTime() - Date.parse(r.last_polled_at) < maxAge;
  });
  if (fresh) return cached;

  // 2) Per-user rate limit protects our API bill from abuse.
  const since = new Date(now.getTime() - 86400_000).toISOString();
  if (await deps.repo.countUserProviderCalls(userId, since) >= limits.perUserPerDay) {
    if (cached.length) return cached;
    throw new SearchError("rate_limited", "Too many searches today. Try again later.");
  }

  // NOTE: the caller builds a per-request provider whose onCall logs { userId } → rate limit + cost log.
  let snaps: FlightSnapshot[];
  try {
    snaps = await deps.provider.getFlight({ carrier: fn.carrier, number: fn.number, date: input.date });
  } catch (e) {
    if (cached.length) return cached;
    throw new SearchError("provider_error", `Flight data provider error: ${String(e)}`);
  }
  if (!snaps.length) throw new SearchError("not_found", `No flight ${fn.carrier} ${fn.number} on ${input.date}`);

  const out: FlightRow[] = [];
  for (const s of snaps) {
    const existing = await deps.repo.getFlightByKey(s.carrier, s.number, s.date, s.depIata);
    out.push((await ingest(deps, existing, s, "search")).row);
  }
  return out;
}
