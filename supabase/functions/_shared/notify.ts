import type { EventType, FlightEvent, FlightRow } from "./types.ts";

/** Android notification channels (created by the app with the same ids). */
export const CHANNEL_CRITICAL = "flight_critical"; // gate/belt/delay/cancel/boarding — heads-up + sound
export const CHANNEL_UPDATES = "flight_updates"; // departed/landed/gate assigned — normal

/** Events that are always user-visible and time-critical. */
const CRITICAL: EventType[] = [
  "GATE_CHANGE", "TERMINAL_CHANGE", "BOARDING", "BELT_ASSIGNED", "BELT_CHANGE",
  "DELAY", "SCHEDULE_CHANGE", "CANCELLED", "DIVERTED", "REINSTATED", "INBOUND_LATE",
];

/** How long FCM should keep trying to deliver — a stale gate change is worse than none. */
const TTL_SECONDS: Partial<Record<EventType, number>> = {
  GATE_ASSIGNED: 2 * 3600, GATE_CHANGE: 2 * 3600, BOARDING: 3600, TERMINAL_CHANGE: 3 * 3600,
  BELT_ASSIGNED: 2 * 3600, BELT_CHANGE: 2 * 3600, DEPARTED: 3 * 3600, LANDED: 2 * 3600,
};

/** Same tag → a newer notification REPLACES the older one in the shade (no 3 stale gate alerts). */
function tagGroup(t: EventType): string {
  if (t === "TERMINAL_CHANGE") return "terminal"; // own tag: must never be replaced by a gate alert
  if (t.startsWith("GATE")) return "gate";
  if (t.startsWith("BELT")) return "belt";
  if (t.startsWith("DELAY") || t === "INBOUND_LATE" || t === "SCHEDULE_CHANGE") return "delay";
  return t.toLowerCase();
}

export function fmtTime(iso: unknown, tz: string | null): string {
  if (typeof iso !== "string" || !iso) return "--:--";
  return new Intl.DateTimeFormat("en-IN", {
    hour: "2-digit", minute: "2-digit", hour12: false, timeZone: tz ?? "Asia/Kolkata",
  }).format(new Date(iso));
}

/** null when the time is unknown — we never print a made-up or placeholder time in an alert. */
function fmtTimeOrNull(iso: unknown, tz: string | null): string | null {
  if (typeof iso !== "string" || !iso || Number.isNaN(Date.parse(iso))) return null;
  return fmtTime(iso, tz);
}

export interface Tz {
  dep: string | null;
  arr: string | null;
}

export function renderEvent(e: FlightEvent, tz: Tz): { title: string; body: string } {
  const p = e.payload as Record<string, unknown>;
  const f = String(p.flight ?? "");
  const route = `${p.dep}→${p.arr}`;
  switch (e.type) {
    case "GATE_CHANGE":
      return {
        title: `Gate change: ${f} → Gate ${e.newValue}${p.terminal ? ` (T${p.terminal})` : ""}`,
        body: `Was gate ${e.oldValue}. ${route}, departs ${fmtTime(p.estDep, tz.dep)}.`,
      };
    case "GATE_ASSIGNED":
      return { title: `${f}: Gate ${e.newValue}`, body: `${p.terminal ? `Terminal ${p.terminal}, ` : ""}departs ${fmtTime(p.estDep, tz.dep)}.` };
    case "TERMINAL_CHANGE":
      return { title: `Terminal change: ${f} now T${e.newValue}`, body: `Was T${e.oldValue} at ${p.airport}. Leave extra time.` };
    case "DELAY":
      return {
        title: `Delayed ${p.delayMin} min: ${f}`,
        body: `${route} now departs ${fmtTime(p.estDep, tz.dep)} (was ${fmtTime(p.schedDep, tz.dep)}).${p.reason ? ` Reason: ${p.reason}` : ""}`,
      };
    case "SCHEDULE_CHANGE":
      return {
        title: `Rescheduled: ${f} now ${fmtTime(p.newDep, tz.dep)}`,
        body: `The airline moved ${route} ${Math.abs(Number(p.shiftMin))} min ${p.direction} (was ${fmtTime(p.oldDep, tz.dep)}).`,
      };
    case "DELAY_REDUCED":
      return {
        title: Number(p.delayMin) < 5 ? `${f} back on time` : `${f}: delay reduced to ${p.delayMin} min`,
        body: `${route} now departs ${fmtTime(p.estDep, tz.dep)}.`,
      };
    case "BOARDING":
      return { title: `Boarding now: ${f}`, body: p.gate ? `Gate ${p.gate}${p.terminal ? `, T${p.terminal}` : ""}.` : `${route}` };
    case "DEPARTED": {
      const off = fmtTimeOrNull(p.actDep, tz.dep), eta = fmtTimeOrNull(p.estArr, tz.arr);
      return {
        title: `${f} has departed`,
        body: [off && `Took off ${off}.`, eta ? `Expected at ${p.arr} ${eta}.` : `Heading to ${p.arr}.`].filter(Boolean).join(" "),
      };
    }
    case "LANDED": {
      const on = fmtTimeOrNull(p.actArr, tz.arr);
      return {
        title: `${f} landed at ${p.arr}`,
        body: [on && `Landed ${on}.`, p.belt && `Baggage belt ${p.belt}.`].filter(Boolean).join(" ") || `Welcome to ${p.arr}.`,
      };
    }
    case "BELT_ASSIGNED":
      return { title: `Baggage belt ${e.newValue}: ${f}`, body: `${p.arrTerminal ? `Terminal ${p.arrTerminal}, ` : ""}${p.arr}.` };
    case "BELT_CHANGE":
      return { title: `Belt change: ${f} → Belt ${e.newValue}`, body: `Was belt ${e.oldValue}.` };
    case "CANCELLED":
      return { title: `Cancelled: ${f}`, body: `${route} has been cancelled. Check with the airline for rebooking/refund.` };
    case "REINSTATED":
      return { title: `${f} is back on`, body: `The cancellation was withdrawn. Departs ${fmtTime(p.estDep, tz.dep)}.` };
    case "DIVERTED": {
      // tz.arr is the row's CURRENT arrival airport (= diversion airport once known)
      const eta = p.newArr ? fmtTimeOrNull(p.estArr, tz.arr) : null;
      return {
        title: `Diverted: ${f}`,
        body: p.newArr
          ? `Not landing at ${p.origArr}. Now heading to ${p.newArr}${eta ? `, ETA ${eta} local` : ""}.`
          : `Not landing at ${p.origArr} as planned. New destination not announced yet.`,
      };
    }
    case "INBOUND_LATE": {
      const ib = p.inbound as Record<string, unknown>;
      return {
        title: `${f} likely ~${p.lateMin} min late`,
        body: `Your plane (${ib.flight} from ${ib.from}) is running late. Expected departure ~${fmtTime(p.predictedDep, tz.dep)}. Airline hasn't announced yet.`,
      };
    }
  }
}

// ---- FCM HTTP v1 message builders -------------------------------------------------------------

export interface FcmMessage {
  token: string;
  data?: Record<string, string>; // FCM data values must be strings
  android?: Record<string, unknown>;
  /** APNs config. FCM applies this only when the token belongs to an iOS app; Android ignores it. */
  apns?: Record<string, unknown>;
}

/**
 * APNs collapse ids are at most 64 bytes. A flight UUID plus a short group stays under that.
 * The same string is the thread id, so a newer alert replaces the previous one instead of stacking.
 */
function apnsCollapseId(value: string): string {
  return new TextEncoder().encode(value).length <= 64 ? value : value.slice(-64);
}

/** flight_critical → time-sensitive (breaks through Focus). flight_updates → a normal active alert. */
function apnsAlert(title: string, body: string, tag: string, critical: boolean, extra: Record<string, string>): Record<string, unknown> {
  const collapse = apnsCollapseId(tag);
  return {
    headers: {
      "apns-push-type": "alert",
      "apns-priority": "10",
      "apns-collapse-id": collapse,
    },
    payload: {
      aps: {
        alert: { title, body },
        sound: "default",
        "thread-id": collapse,
        "interruption-level": critical ? "time-sensitive" : "active",
        category: "OPEN_FLIGHT",
      },
      ...extra,
    },
  };
}

/** Data-only sync. Priority 5 is what Apple accepts for a background content-available push. */
function apnsSync(collapse: string, extra: Record<string, string>): Record<string, unknown> {
  const id = apnsCollapseId(collapse);
  return {
    headers: {
      "apns-push-type": "background",
      "apns-priority": "5",
      "apns-collapse-id": id,
    },
    payload: {
      aps: { "content-available": 1 },
      ...extra,
    },
  };
}

/**
 * Alert = NOTIFICATION message (+data).
 * Why not data-only? Many Indian phones (Xiaomi/Redmi, Vivo, Oppo, Realme) kill swiped-away apps,
 * so data messages may never wake our code. Notification messages are shown by Google Play
 * services itself → the user sees the gate change even if our app is dead.
 */
export function buildAlertMessage(token: string, flightId: string, eventId: string, e: FlightEvent, tz: Tz): FcmMessage {
  const { title, body } = renderEvent(e, tz);
  const critical = CRITICAL.includes(e.type);
  const tag = `${flightId}:${tagGroup(e.type)}`;
  const data = { kind: "alert", flight_id: flightId, event_id: eventId, event_type: e.type, title, body };
  return {
    token,
    data,
    android: {
      priority: "HIGH",
      ttl: `${TTL_SECONDS[e.type] ?? 6 * 3600}s`,
      notification: {
        title,
        body,
        channel_id: critical ? CHANNEL_CRITICAL : CHANNEL_UPDATES,
        tag,
        notification_priority: critical ? "PRIORITY_MAX" : "PRIORITY_DEFAULT",
        default_sound: true,
        click_action: "OPEN_FLIGHT", // app's intent-filter action
      },
    },
    apns: apnsAlert(title, body, tag, critical, data),
  };
}

/**
 * Fixed alert for send-test-push: same channel, priority and APNs level as a critical flight alert, so it
 * proves the real delivery path. kind "test" carries no flight, so both apps ignore it as data; the system
 * shows it while the app is in the background, and the iOS app also shows it in the foreground.
 * ponytail: an Android app in the foreground shows nothing (onMessageReceived ignores kind "test");
 * handle it there when Android gets the "Send a test alert" button.
 */
export function buildTestMessage(token: string): FcmMessage {
  const title = "HeyGate test alert";
  const body = "Alerts reach this phone. Gate changes and delays will arrive like this.";
  const data = { kind: "test", title, body };
  return {
    token,
    data,
    android: {
      priority: "HIGH",
      ttl: "600s", // a test that arrives 10 minutes late is a failed test
      notification: {
        title,
        body,
        channel_id: CHANNEL_CRITICAL,
        tag: "test",
        notification_priority: "PRIORITY_MAX",
        default_sound: true,
      },
    },
    apns: apnsAlert(title, body, "test", true, data),
  };
}

/** Compact flight state for the app's Room cache / widget / live notification (data-only, collapsible). */
export function compactFlight(r: FlightRow): Record<string, unknown> {
  return {
    id: r.id, c: r.carrier_iata, n: r.flight_number, d: r.flight_date, dep: r.dep_iata, arr: r.arr_iata,
    st: r.status, sd: r.sched_dep, ed: r.est_dep, ad: r.act_dep, sa: r.sched_arr, ea: r.est_arr, aa: r.act_arr,
    dt: r.dep_terminal, dg: r.dep_gate, at: r.arr_terminal, ag: r.arr_gate, bb: r.baggage_belt,
    ac: r.aircraft_model, reg: r.aircraft_reg,
    lat: r.live_lat, lon: r.live_lon, alt: r.live_alt_ft, lu: r.live_updated_at,
    u: r.updated_at ?? new Date().toISOString(),
  };
}

export function buildSyncMessage(token: string, row: FlightRow, nearFlight: boolean): FcmMessage {
  const flight = JSON.stringify(compactFlight(row));
  const data = { kind: "sync", flight_id: row.id, flight };
  return {
    token,
    data,
    android: {
      // HIGH only when the live notification is on screen (user-visible update); NORMAL otherwise
      priority: nearFlight ? "HIGH" : "NORMAL",
      ttl: "3600s",
      collapse_key: `sync:${row.id}`, // only the latest state is delivered if the phone was offline
    },
    // Background push (priority 5). A priority-10 content-available message is throttled or dropped by APNs.
    apns: apnsSync(`sync:${row.id}`, { kind: "sync", flight_id: row.id }),
  };
}
