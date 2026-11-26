import type { AlertLanguage, EventType, FlightEvent, FlightRow } from "./types.ts";
import { safeCheckinUrl } from "./checkin.ts";
import { renderAlert, type Tz } from "./alert-text.ts";
import { friendNameForData, isFriendCritical, renderFriendAlert } from "./friend-alerts.ts";

/** Android notification channels (created by the app with the same ids). */
export const CHANNEL_CRITICAL = "flight_critical"; // gate/belt/delay/cancel/boarding — heads-up + sound
export const CHANNEL_UPDATES = "flight_updates"; // departed/landed/gate assigned — normal
/** Info alerts during the recipient's quiet hours: low importance, no sound (P2 section 3). */
export const CHANNEL_QUIET = "flight_quiet";

/** Events that are always user-visible and time-critical. */
const CRITICAL: EventType[] = [
  "GATE_CHANGE", "TERMINAL_CHANGE", "BOARDING", "BELT_ASSIGNED", "BELT_CHANGE",
  "DELAY", "SCHEDULE_CHANGE", "CANCELLED", "DIVERTED", "REINSTATED", "INBOUND_LATE", "CHECKIN_CLOSING",
  "FOG_ALERT", "DIVERTED_DEST",
];

/** How long FCM should keep trying to deliver — a stale gate change is worse than none. */
const TTL_SECONDS: Partial<Record<EventType, number>> = {
  GATE_ASSIGNED: 2 * 3600, GATE_CHANGE: 2 * 3600, BOARDING: 3600, TERMINAL_CHANGE: 3 * 3600,
  BELT_ASSIGNED: 2 * 3600, BELT_CHANGE: 2 * 3600, DEPARTED: 3 * 3600, LANDED: 2 * 3600,
  CHECKIN_CLOSING: 30 * 60, // after the close it is useless
  FOG_ALERT: 4 * 3600, // sent at most 4 h before the departure/arrival it is about
};

/** Same tag → a newer notification REPLACES the older one in the shade (no 3 stale gate alerts). */
function tagGroup(t: EventType): string {
  if (t === "TERMINAL_CHANGE") return "terminal"; // own tag: must never be replaced by a gate alert
  if (t.startsWith("GATE")) return "gate";
  if (t.startsWith("BELT")) return "belt";
  if (t.startsWith("DELAY") || t === "INBOUND_LATE" || t === "SCHEDULE_CHANGE") return "delay";
  if (t === "DIVERTED_DEST") return "diverted"; // replaces "new destination not announced yet"
  return t.toLowerCase();
}

export type { Tz };

/**
 * Title and body of one alert in the recipient's language (profiles.alert_language).
 * Templates live in alert-text.ts; every event type has one in en, hi and hinglish.
 */
export function renderEvent(e: FlightEvent, tz: Tz, lang: AlertLanguage | string | null = "en"): { title: string; body: string } {
  return renderAlert(e, tz, lang);
}

/**
 * Fills the flight, dep and arr every template uses from the flight row when the event's payload lacks them
 * (FOG_ALERT payloads carry only the weather fields). Values already in the payload win.
 */
export function withFlightContext(e: FlightEvent, row: Pick<FlightRow, "carrier_iata" | "flight_number" | "dep_iata" | "arr_iata">): FlightEvent {
  const p = (e.payload ?? {}) as Record<string, unknown>;
  if (p.flight && p.dep && p.arr) return e;
  return {
    ...e,
    payload: { flight: `${row.carrier_iata} ${row.flight_number}`, dep: row.dep_iata, arr: row.arr_iata, ...p },
  };
}

/** Is this alert type always loud (critical channel, time-sensitive on iOS)? */
export function isCriticalAlert(t: EventType): boolean {
  return CRITICAL.includes(t);
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

/**
 * flight_critical → time-sensitive (breaks through Focus). flight_updates → a normal active alert.
 * quiet (info alert in the recipient's quiet hours) → passive and without sound.
 */
function apnsAlert(
  title: string, body: string, tag: string, critical: boolean, extra: Record<string, string>, quiet = false,
): Record<string, unknown> {
  const collapse = apnsCollapseId(tag);
  const silent = quiet && !critical;
  return {
    headers: {
      "apns-push-type": "alert",
      "apns-priority": "10",
      "apns-collapse-id": collapse,
    },
    payload: {
      aps: {
        alert: { title, body },
        ...(silent ? {} : { sound: "default" }),
        "thread-id": collapse,
        "interruption-level": critical ? "time-sensitive" : silent ? "passive" : "active",
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
export interface AlertOptions {
  /** profiles.alert_language of the recipient; unknown → English. */
  lang?: AlertLanguage | string | null;
  /** The recipient is inside their quiet hours (see quiet-hours.ts). Only info alerts get quieter. */
  quiet?: boolean;
  /**
   * Set when the recipient is a friend of a tracker (get_flight_recipients role "friend"): the text names that
   * friend ("Asha's flight 6E 2341 has landed in Mumbai") and the data carries role="friend" + friend_name.
   * Only CANCELLED / DIVERTED / DIVERTED_DEST stay critical for friends. P2 wave 2 section 1.
   */
  friend?: { name: string | null };
}

export function buildAlertMessage(
  token: string, flightId: string, eventId: string, e: FlightEvent, tz: Tz, opts: AlertOptions = {},
): FcmMessage {
  const lang = opts.lang ?? "en";
  const friendText = opts.friend ? renderFriendAlert(e, tz, lang, opts.friend.name) : null;
  const { title, body } = friendText ?? renderEvent(e, tz, lang);
  const critical = opts.friend ? isFriendCritical(e.type) : CRITICAL.includes(e.type);
  const silent = !!opts.quiet && !critical; // critical alerts are always loud
  const tag = `${flightId}:${tagGroup(e.type)}`;
  const data: Record<string, string> = { kind: "alert", flight_id: flightId, event_id: eventId, event_type: e.type, title, body };
  if (opts.friend) {
    data.role = "friend";
    data.friend_name = friendNameForData(opts.friend.name, lang);
  }
  // The app posts foreground alerts itself: tell it to use the quiet channel too.
  if (silent) data.quiet = "1";
  // Check-in reminders: a tap opens the airline's page (top-level data key, also top level of APNs userInfo).
  if (e.type === "CHECKIN_OPEN" || e.type === "CHECKIN_CLOSING") {
    const url = safeCheckinUrl((e.payload as Record<string, unknown>)?.checkin_url);
    if (url) data.checkin_url = url;
  }
  return {
    token,
    data,
    android: {
      priority: "HIGH",
      ttl: `${TTL_SECONDS[e.type] ?? 6 * 3600}s`,
      notification: {
        title,
        body,
        channel_id: critical ? CHANNEL_CRITICAL : silent ? CHANNEL_QUIET : CHANNEL_UPDATES,
        tag,
        notification_priority: critical ? "PRIORITY_MAX" : silent ? "PRIORITY_LOW" : "PRIORITY_DEFAULT",
        default_sound: !silent,
        click_action: "OPEN_FLIGHT", // app's intent-filter action
      },
    },
    apns: apnsAlert(title, body, tag, critical, data, silent),
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

// ---- iOS Live Activity ------------------------------------------------------------------------

const IOS_BUNDLE_ID = "app.heygate.flytrack";
/** Seconds between 1970-01-01 and 2001-01-01, the epoch of Swift's Date. */
const APPLE_EPOCH_OFFSET_S = 978_307_200;
/** How long an ended activity stays on the lock screen: long enough to walk to the belt. */
const LIVE_ACTIVITY_LINGER_S = 3600;

/** ActivityKit decodes content-state with JSONDecoder's default Date strategy: seconds since 2001-01-01. */
function appleDate(iso: string | null | undefined): number | null {
  const ms = iso ? Date.parse(iso) : NaN;
  return Number.isNaN(ms) ? null : ms / 1000 - APPLE_EPOCH_OFFSET_S;
}

/** Nothing left to wait for: landed with the belt known, or cancelled. The app uses the same rule. */
export function liveActivityDone(r: FlightRow): boolean {
  return r.status === "cancelled" || ((r.status === "landed" || r.status === "arrived") && !!r.baggage_belt);
}

/** Keys and types match FlightActivityAttributes.ContentState (ios/FlightActivity/FlightActivityAttributes.swift). */
export function liveActivityState(r: FlightRow, now: Date): Record<string, unknown> {
  return {
    status: r.status,
    schedDep: appleDate(r.sched_dep),
    estDep: appleDate(r.act_dep ?? r.est_dep), // best known time: the actual one once it happened
    schedArr: appleDate(r.sched_arr),
    estArr: appleDate(r.act_arr ?? r.est_arr),
    depTerminal: r.dep_terminal,
    depGate: r.dep_gate,
    belt: r.baggage_belt,
    updatedAt: appleDate(r.updated_at) ?? appleDate(now.toISOString()),
  };
}

/**
 * Updates (or ends) one Live Activity through FCM: message.token is the phone's FCM token, the activity's
 * own APNs token rides in apns.live_activity_token. Priority 10 is budgeted by iOS, so it is kept for
 * changes that also raised an alert, and for the end; everything else goes at 5.
 */
export function buildLiveActivityMessage(
  fcmToken: string, activityToken: string, row: FlightRow, now: Date, urgent: boolean,
): FcmMessage {
  const end = liveActivityDone(row);
  const timestamp = Math.floor(now.getTime() / 1000);
  return {
    token: fcmToken,
    apns: {
      live_activity_token: activityToken,
      headers: {
        "apns-push-type": "liveactivity",
        "apns-topic": `${IOS_BUNDLE_ID}.push-type.liveactivity`,
        "apns-priority": end || urgent ? "10" : "5",
      },
      payload: {
        aps: {
          timestamp,
          event: end ? "end" : "update",
          "content-state": liveActivityState(row, now),
          ...(end ? { "dismissal-date": timestamp + LIVE_ACTIVITY_LINGER_S } : {}),
        },
      },
    },
  };
}
