import type { AlertState, FlightEvent, FlightSnapshot, FlightStatus, TrackedField } from "./types.ts";
import { TRACKED_FIELDS } from "./types.ts";

const MIN = 60_000;

/** Which tracked fields differ between two snapshots (for flight_status_history). */
export function changedFields(prev: FlightSnapshot | null, next: FlightSnapshot): TrackedField[] {
  if (!prev) return [...TRACKED_FIELDS].filter((f) => next[f] != null);
  return TRACKED_FIELDS.filter((f) => normalize(prev[f]) !== normalize(next[f]));
}

function normalize(v: unknown): string | null {
  if (v == null || v === "") return null;
  if (typeof v === "string") {
    // time strings: compare at minute precision to avoid churn on seconds
    if (/^\d{4}-\d{2}-\d{2}T/.test(v)) {
      const t = Date.parse(v);
      return Number.isNaN(t) ? v : String(Math.round(t / MIN));
    }
    return v.trim().toUpperCase();
  }
  return String(v);
}

function sameValue(a: string | null | undefined, b: string | null | undefined): boolean {
  return normalize(a ?? null) === normalize(b ?? null);
}

function minutesBetween(a: string | null, b: string | null): number | null {
  if (!a || !b) return null;
  return Math.round((Date.parse(a) - Date.parse(b)) / MIN);
}

const isDeparted = (s: FlightSnapshot) =>
  !!s.actDep || ["departed", "en_route", "approaching", "landed", "arrived", "diverted"].includes(s.status);
const isLanded = (s: FlightSnapshot) => !!s.actArr || s.status === "landed" || s.status === "arrived";

export interface AlertOptions {
  delayThresholdMin: number; // notify when delay grows/shrinks by at least this much
}
export const DEFAULT_ALERT_OPTIONS: AlertOptions = { delayThresholdMin: 15 };

/** Statuses that PROVE a cancelled flight operates again. "unknown" (e.g. ADB CanceledUncertain) never does. */
const REINSTATE_CONFIRMING: ReadonlySet<FlightStatus> = new Set<FlightStatus>([
  "scheduled", "check_in", "boarding", "gate_closed", "departed", "en_route", "approaching", "landed", "arrived", "diverted",
]);

/**
 * Decide which push-worthy events happened, comparing against what we LAST TOLD users (state).
 * - prev == null (first time we see the flight) → baseline only, no alerts.
 * - Returns a new state; caller persists it with the flight row.
 */
export function evaluateAlerts(
  flightId: string,
  state: AlertState,
  prev: FlightSnapshot | null,
  next: FlightSnapshot,
  opts: AlertOptions = DEFAULT_ALERT_OPTIONS,
): { events: FlightEvent[]; state: AlertState } {
  const st: AlertState = { ...state, milestones: [...(state.milestones ?? [])] };
  const has = (m: string) => st.milestones!.includes(m);
  const mark = (m: string) => {
    if (!has(m)) st.milestones!.push(m);
  };
  const events: FlightEvent[] = [];
  const base = {
    flight: `${next.carrier} ${next.number}`,
    dep: next.depIata,
    arr: next.arrIata,
  };
  const push = (e: Omit<FlightEvent, "payload" | "proOnly"> & { payload?: Record<string, unknown>; proOnly?: boolean }) =>
    events.push({ proOnly: false, ...e, payload: { ...base, ...(e.payload ?? {}) } });

  const baseline = prev == null && Object.keys(state).length === 0;
  if (baseline) {
    return { events: [], state: snapshotState(next) };
  }
  // Generation counter in every value-based dedupe key: a value that COMES BACK (gate 12→15→12→15)
  // gets a new key, while two racing ingests of the same snapshot still collide (exactly-once).
  const gen = st.alertSeq ?? 0;

  // State written before P3 has no aircraftReg key (snapshotState and this function always set it now). For
  // such a flight the block-time milestones it has already passed are history, not news: no TAKEOFF / AT_GATE
  // for a flight that was airborne or on the ground when this shipped.
  if (!("aircraftReg" in state)) {
    if (has("DEPARTED")) {
      mark("GATE_CLOSED");
      if (next.depRunwayAt) mark("TAKEOFF");
    }
    if (has("LANDED")) {
      mark("TAKEOFF");
      if (next.arrOnblockAt) mark("AT_GATE");
    }
    st.aircraftReg = null;
    st.aircraftModel = null;
  }
  const done = () => {
    if (events.length) st.alertSeq = gen + 1;
    return { events, state: st };
  };

  // --- Cancellation / reinstatement (overrides everything else) ---
  if (next.status === "cancelled") {
    if (!has("CANCELLED")) {
      mark("CANCELLED");
      push({
        type: "CANCELLED", severity: "critical", oldValue: null, newValue: "cancelled",
        dedupeKey: `${flightId}:CANCELLED:${st.milestones!.filter((m) => m === "REINSTATED").length}`,
        payload: { schedDep: next.schedDep },
      });
    }
    return done();
  }
  if (has("CANCELLED")) {
    // Provider wobble (Canceled ↔ CanceledUncertain/Unknown) must not produce "back on" + "cancelled" again.
    if (!REINSTATE_CONFIRMING.has(next.status) && !next.actDep) return done();
    st.milestones = st.milestones!.filter((m) => m !== "CANCELLED");
    st.milestones.push("REINSTATED");
    push({
      type: "REINSTATED", severity: "critical", oldValue: "cancelled", newValue: next.status,
      dedupeKey: `${flightId}:REINSTATED:${gen}`,
      payload: { estDep: next.estDep ?? next.schedDep },
    });
  }

  const departed = isDeparted(next);

  // --- Terminal & gate (only meaningful before departure) ---
  if (!departed) {
    if (next.depTerminal) {
      if (st.depTerminal && !sameValue(st.depTerminal, next.depTerminal)) {
        push({
          type: "TERMINAL_CHANGE", severity: "critical", oldValue: st.depTerminal, newValue: next.depTerminal,
          dedupeKey: `${flightId}:TERMINAL_CHANGE:${gen}:${st.depTerminal}>${next.depTerminal}`,
          payload: { airport: next.depIata },
        });
      }
      st.depTerminal = next.depTerminal;
    }
    if (next.depGate && !sameValue(st.depGate, next.depGate)) {
      const isChange = !!st.depGate;
      push({
        type: isChange ? "GATE_CHANGE" : "GATE_ASSIGNED",
        severity: isChange ? "critical" : "info",
        oldValue: st.depGate ?? null, newValue: next.depGate,
        dedupeKey: `${flightId}:GATE:${gen}:${st.depGate ?? ""}>${next.depGate}`,
        payload: { terminal: next.depTerminal, estDep: next.estDep ?? next.schedDep },
      });
      st.depGate = next.depGate;
    }
    // gate disappearing (null) is NOT an alert — providers flap. State keeps last told gate.
  }

  // --- Aircraft swap (Pro): a DIFFERENT tail before departure. The first tail we learn is not a swap. ---
  if (!departed && next.aircraftReg) {
    if (st.aircraftReg && !sameValue(st.aircraftReg, next.aircraftReg)) {
      push({
        type: "AIRCRAFT_SWAP", severity: "critical", proOnly: true, oldValue: st.aircraftReg, newValue: next.aircraftReg,
        dedupeKey: `${flightId}:AIRCRAFT_SWAP:${gen}:${normalize(st.aircraftReg)}>${normalize(next.aircraftReg)}`,
        payload: {
          old_reg: st.aircraftReg, new_reg: next.aircraftReg,
          old_model: st.aircraftModel ?? null, new_model: next.aircraftModel ?? null,
        },
      });
    }
    if (!sameValue(st.aircraftReg, next.aircraftReg)) {
      st.aircraftReg = next.aircraftReg;
      st.aircraftModel = next.aircraftModel ?? null;
    } else if (next.aircraftModel && !st.aircraftModel) {
      st.aircraftModel = next.aircraftModel; // the type often shows up after the tail
    }
  }

  // --- Airline retime / reschedule: the SCHEDULED time itself moved (no "delay" would show) ---
  if (!departed && next.schedDep) {
    const shift = st.schedDep ? minutesBetween(next.schedDep, st.schedDep) ?? 0 : 0;
    if (st.schedDep && Math.abs(shift) >= opts.delayThresholdMin) {
      push({
        type: "SCHEDULE_CHANGE", severity: "critical", oldValue: st.schedDep, newValue: next.schedDep,
        dedupeKey: `${flightId}:SCHEDULE_CHANGE:${gen}:${normalize(next.schedDep)}`,
        payload: {
          oldDep: st.schedDep, newDep: next.schedDep, estDep: next.estDep ?? next.schedDep, shiftMin: shift,
          direction: shift < 0 ? "earlier" : "later",
        },
      });
      // delay is now measured against the new schedule; don't follow up with a "delay reduced"
      st.depDelayMin = next.estDep ? Math.max(0, minutesBetween(next.estDep, next.schedDep) ?? 0) : 0;
    }
    st.schedDep = next.schedDep;
  }

  // --- Delay (departure), only before departure and only when we have an estimate ---
  if (!departed && next.estDep && next.schedDep) {
    const delay = Math.max(0, minutesBetween(next.estDep, next.schedDep) ?? 0);
    const last = st.depDelayMin ?? 0;
    const T = opts.delayThresholdMin;
    if (delay >= T && delay - last >= T) {
      push({
        type: "DELAY", severity: "critical", oldValue: String(last), newValue: String(delay),
        dedupeKey: `${flightId}:DELAY:${gen}:${normalize(next.estDep)}`,
        payload: { delayMin: delay, schedDep: next.schedDep, estDep: next.estDep, reason: next.delayReason },
      });
      st.depDelayMin = delay;
    } else if (last >= T && last - delay >= T) {
      push({
        type: "DELAY_REDUCED", severity: "info", oldValue: String(last), newValue: String(delay),
        dedupeKey: `${flightId}:DELAY_REDUCED:${gen}:${normalize(next.estDep)}`,
        payload: { delayMin: delay, schedDep: next.schedDep, estDep: next.estDep },
      });
      st.depDelayMin = delay;
    }
  }

  // --- Milestones ---
  if (!departed && (next.status === "boarding" || next.status === "gate_closed") && !has("BOARDING")) {
    mark("BOARDING");
    push({
      type: "BOARDING", severity: "critical", oldValue: null, newValue: next.status,
      dedupeKey: `${flightId}:BOARDING`, payload: { gate: next.depGate, terminal: next.depTerminal },
    });
  }
  if (!departed && next.status === "gate_closed" && !has("GATE_CLOSED")) {
    mark("GATE_CLOSED");
    push({
      type: "GATE_CLOSED", severity: "info", oldValue: null, newValue: next.status,
      dedupeKey: `${flightId}:GATE_CLOSED`,
      payload: { gate: next.depGate, terminal: next.depTerminal, estDep: next.estDep ?? next.schedDep },
    });
  }
  if (departed) mark("GATE_CLOSED"); // never a late "gate closed" after pushback
  if (departed && !has("DEPARTED")) {
    mark("DEPARTED");
    mark("BOARDING"); // don't send a late "boarding" after takeoff
    push({
      type: "DEPARTED", severity: "info", oldValue: null, newValue: next.actDep,
      dedupeKey: `${flightId}:DEPARTED`, payload: { actDep: next.actDep, estArr: next.estArr ?? next.schedArr },
    });
  }
  const planned = st.plannedArr ?? prev?.arrIata ?? next.arrIata;
  const destChanged = !sameValue(planned, next.arrIata);
  if ((next.status === "diverted" || (departed && destChanged)) && !has("DIVERTED")) {
    mark("DIVERTED");
    push({
      type: "DIVERTED", severity: "critical", oldValue: planned, newValue: destChanged ? next.arrIata : null,
      dedupeKey: `${flightId}:DIVERTED`,
      payload: { origArr: planned, newArr: destChanged ? next.arrIata : null, estArr: destChanged ? next.estArr : null },
    });
    // The DIVERTED alert already named the new airport: no second alert for the same news.
    if (destChanged) mark("DIVERTED_DEST");
  } else if (has("DIVERTED") && !has("DIVERTED_DEST") && destChanged && next.arrIata) {
    // DIVERTED went out as "new destination not announced yet"; the airport is known now. Sent once.
    mark("DIVERTED_DEST");
    push({
      type: "DIVERTED_DEST", severity: "critical", oldValue: planned, newValue: next.arrIata,
      dedupeKey: `${flightId}:DIVERTED_DEST`,
      payload: { origArr: planned, newArr: next.arrIata, estArr: next.estArr },
    });
  }
  if (next.depRunwayAt && !has("TAKEOFF")) {
    mark("TAKEOFF");
    // A runway time that only shows up after landing is history, not news.
    if (!isLanded(next)) {
      push({
        type: "TAKEOFF", severity: "info", oldValue: null, newValue: next.depRunwayAt,
        dedupeKey: `${flightId}:TAKEOFF`,
        payload: { runwayAt: next.depRunwayAt, estArr: next.estArr ?? next.schedArr },
      });
    }
  }
  if (isLanded(next) && !has("LANDED")) {
    mark("LANDED");
    push({
      type: "LANDED", severity: "info", oldValue: null, newValue: next.actArr,
      dedupeKey: `${flightId}:LANDED`,
      payload: { actArr: next.actArr, arrTerminal: next.arrTerminal, arrGate: next.arrGate, belt: next.baggageBelt },
    });
  }

  if (next.arrOnblockAt && !has("AT_GATE")) {
    mark("AT_GATE");
    push({
      type: "AT_GATE", severity: "info", oldValue: null, newValue: next.arrOnblockAt,
      dedupeKey: `${flightId}:AT_GATE`,
      payload: { onblockAt: next.arrOnblockAt, arrGate: next.arrGate, arrTerminal: next.arrTerminal, belt: next.baggageBelt },
    });
  }

  // --- Baggage belt (any time; usually after landing) ---
  if (next.baggageBelt && !sameValue(st.baggageBelt, next.baggageBelt)) {
    const isChange = !!st.baggageBelt;
    push({
      type: isChange ? "BELT_CHANGE" : "BELT_ASSIGNED", severity: "critical",
      oldValue: st.baggageBelt ?? null, newValue: next.baggageBelt,
      dedupeKey: `${flightId}:BELT:${gen}:${st.baggageBelt ?? ""}>${next.baggageBelt}`,
      payload: { arrTerminal: next.arrTerminal },
    });
    st.baggageBelt = next.baggageBelt;
  }

  return done();
}

/** Initial "what the user already sees" state when a flight is first added. */
export function snapshotState(s: FlightSnapshot): AlertState {
  const milestones: string[] = [];
  if (s.status === "cancelled") milestones.push("CANCELLED");
  if (s.status === "boarding" || s.status === "gate_closed") milestones.push("BOARDING");
  if (s.status === "gate_closed") milestones.push("GATE_CLOSED");
  if (isDeparted(s)) milestones.push("BOARDING", "GATE_CLOSED", "DEPARTED");
  if (s.depRunwayAt || isLanded(s)) milestones.push("TAKEOFF");
  if (s.status === "diverted") milestones.push("DIVERTED");
  if (isLanded(s)) milestones.push("LANDED");
  if (s.arrOnblockAt) milestones.push("AT_GATE");
  const delay = s.estDep && s.schedDep ? Math.max(0, minutesBetween(s.estDep, s.schedDep) ?? 0) : 0;
  return {
    depGate: s.depGate,
    depTerminal: s.depTerminal,
    baggageBelt: s.baggageBelt,
    depDelayMin: delay,
    schedDep: s.schedDep,
    plannedArr: s.status === "diverted" ? null : s.arrIata,
    aircraftReg: s.aircraftReg ?? null, // always present: marks the state as written after P3 (see evaluateAlerts)
    aircraftModel: s.aircraftModel ?? null,
    milestones: [...new Set(milestones)],
    alertSeq: 0,
  };
}

export interface InboundResult {
  event: FlightEvent | null;
  /** Stored in flights.prediction (owned ONLY by the inbound evaluator → no write races with own poll). */
  prediction: Record<string, unknown> & { alertedBucket: number; reason: string | null };
}

/**
 * "Where's my plane?" — will the aircraft operating MY flight arrive in time?
 * projectedReady = inbound arrival (actual/est/sched) + minimum turnaround.
 */
export function evaluateInbound(
  flightId: string,
  prevPrediction: Record<string, unknown> | null,
  mine: FlightSnapshot,
  inbound: FlightSnapshot,
  now: Date,
  minTurnMin = 35,
): InboundResult {
  const prevBucket = Number(prevPrediction?.alertedBucket ?? 0) || 0;
  const inboundArr = inbound.actArr ?? inbound.estArr ?? inbound.schedArr;
  const myDep = mine.estDep ?? mine.schedDep;
  const base = {
    computedAt: now.toISOString(),
    inbound: {
      flight: `${inbound.carrier} ${inbound.number}`,
      from: inbound.depIata,
      status: inbound.status,
      schedArr: inbound.schedArr,
      estArr: inbound.estArr,
      actArr: inbound.actArr,
      live: inbound.live,
    },
  };
  if (!inboundArr || !myDep || mine.status === "cancelled" || isDeparted(mine)) {
    return { event: null, prediction: { ...base, predictedDep: null, lateMin: 0, alertedBucket: prevBucket, reason: null } };
  }
  const ready = Date.parse(inboundArr) + minTurnMin * MIN;
  const lateMin = Math.max(0, Math.round((ready - Date.parse(myDep)) / MIN));
  const predictedDep = new Date(Math.max(ready, Date.parse(myDep))).toISOString();
  const inboundLateVsSched = inbound.schedArr ? minutesBetween(inboundArr, inbound.schedArr) ?? 0 : 0;
  const reason = lateMin >= 10
    ? `Inbound aircraft (${base.inbound.flight} from ${inbound.depIata}) is running ${Math.max(inboundLateVsSched, lateMin)} min late`
    : null;

  let event: FlightEvent | null = null;
  let alertedBucket = prevBucket;
  if (lateMin >= 10) {
    const bucket = Math.ceil(lateMin / 15) * 15;
    if (bucket > prevBucket) {
      alertedBucket = bucket;
      event = {
        type: "INBOUND_LATE", severity: "critical", proOnly: true,
        oldValue: null, newValue: String(lateMin),
        dedupeKey: `${flightId}:INBOUND_LATE:${bucket}`,
        payload: {
          flight: `${mine.carrier} ${mine.number}`, dep: mine.depIata, arr: mine.arrIata,
          lateMin, predictedDep, inbound: base.inbound,
        },
      };
    }
  }
  return { event, prediction: { ...base, predictedDep, lateMin, alertedBucket, reason } };
}
