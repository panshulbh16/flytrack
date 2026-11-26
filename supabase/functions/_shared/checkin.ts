// Web check-in reminders (docs/P1_CONTRACTS.md section 3). Pure logic, no I/O.
// The window itself lives in Postgres (trigger flights_checkin_window); MemoryRepo mirrors it with
// checkinWindow() below so tests and the simulator behave like the database.
import type { FlightEvent, FlightRow } from "./types.ts";

const MIN = 60_000;
/** CHECKIN_CLOSING goes out this long before the close (the apps use the same lead). */
export const CLOSING_LEAD_MIN = 30;

/** One row of public.checkin_rules. */
export interface CheckinRule {
  carrier_iata: string;
  opens_hours_before: number;
  closes_minutes_before: number;
  closes_minutes_before_intl: number;
  checkin_url: string | null;
  verified: boolean;
  notes?: string | null;
}

/** Same values as the seed in 20261126000010_share_checkin.sql. */
export const SEED_RULES: CheckinRule[] = [
  { carrier_iata: "6E", opens_hours_before: 48, closes_minutes_before: 60, closes_minutes_before_intl: 75, checkin_url: "https://www.goindigo.in/web-check-in.html", verified: true },
  { carrier_iata: "AI", opens_hours_before: 48, closes_minutes_before: 60, closes_minutes_before_intl: 75, checkin_url: "https://www.airindia.com/in/en/manage/web-checkin.html", verified: false },
  { carrier_iata: "IX", opens_hours_before: 48, closes_minutes_before: 60, closes_minutes_before_intl: 75, checkin_url: "https://www.airindiaexpress.com/checkin-home", verified: false },
  { carrier_iata: "QP", opens_hours_before: 48, closes_minutes_before: 60, closes_minutes_before_intl: 75, checkin_url: "https://www.akasaair.com/check-in", verified: false },
  { carrier_iata: "SG", opens_hours_before: 48, closes_minutes_before: 60, closes_minutes_before_intl: 75, checkin_url: "https://www.spicejet.com/check-in", verified: false },
];

/**
 * The window counted from the estimated departure when known, else the timetable. International
 * (countries differ, or one is unknown) uses the international close. Mirrors flights_set_checkin().
 */
export function checkinWindow(
  rule: CheckinRule | null | undefined,
  row: Pick<FlightRow, "est_dep" | "sched_dep">,
  international: boolean,
): { opensAt: string; closesAt: string } | null {
  const base = Date.parse(row.est_dep ?? row.sched_dep ?? "");
  if (!rule || Number.isNaN(base)) return null;
  const closeMin = international ? rule.closes_minutes_before_intl : rule.closes_minutes_before;
  return {
    opensAt: new Date(base - Math.max(rule.opens_hours_before, 0) * 60 * MIN).toISOString(),
    closesAt: new Date(base - Math.max(closeMin, 0) * MIN).toISOString(),
  };
}

const GONE = new Set(["cancelled", "diverted", "departed", "en_route", "approaching", "landed", "arrived"]);

/** Same predicate as public.due_checkin_flights(). */
export function isCheckinDue(r: FlightRow, now: Date): boolean {
  const opens = Date.parse(r.checkin_opens_at ?? "");
  const closes = Date.parse(r.checkin_closes_at ?? "");
  const t = now.getTime();
  if (Number.isNaN(closes) || r.checkin_closing_notified || closes <= t) return false;
  if (r.tracker_count <= 0 || r.act_dep || GONE.has(r.status)) return false;
  return (!r.checkin_open_notified && !Number.isNaN(opens) && opens <= t) || closes - CLOSING_LEAD_MIN * MIN <= t;
}

/** Only an absolute https link ever reaches a phone. */
export function safeCheckinUrl(url: unknown): string | null {
  if (typeof url !== "string") return null;
  const s = url.trim();
  if (!s || /\s/.test(s)) return null;
  try {
    const u = new URL(s);
    return u.protocol === "https:" && u.hostname ? s : null;
  } catch {
    return null;
  }
}

export interface CheckinDecision {
  event: FlightEvent;
  /** Flags to store once the event row exists. */
  patch: Pick<FlightRow, "checkin_open_notified" | "checkin_closing_notified"> | Pick<FlightRow, "checkin_open_notified">;
}

/**
 * Which reminder is due now, if any. Inside the last 30 minutes only CHECKIN_CLOSING is sent (an
 * "opens" alert next to a "closes soon" one is noise), and it also marks the open reminder done.
 * Dedupe keys are per flight and type: each reminder goes out once per leg, whatever retimes follow.
 */
export function decideCheckin(r: FlightRow, checkinUrl: string | null, now: Date): CheckinDecision | null {
  if (!isCheckinDue(r, now)) return null;
  const closes = Date.parse(r.checkin_closes_at!);
  const closing = closes - CLOSING_LEAD_MIN * MIN <= now.getTime();
  const type = closing ? "CHECKIN_CLOSING" : "CHECKIN_OPEN";
  const url = safeCheckinUrl(checkinUrl);
  const event: FlightEvent = {
    type,
    severity: closing ? "critical" : "info",
    proOnly: false,
    oldValue: null,
    newValue: r.checkin_closes_at ?? null,
    payload: {
      flight: `${r.carrier_iata} ${r.flight_number}`,
      dep: r.dep_iata,
      arr: r.arr_iata,
      checkin_url: url,
      opens_at: r.checkin_opens_at ?? null,
      closes_at: r.checkin_closes_at ?? null,
      estDep: r.est_dep ?? r.sched_dep,
      terminal: r.dep_terminal,
    },
    dedupeKey: `${r.id}:${type.toLowerCase()}`,
  };
  return {
    event,
    patch: closing ? { checkin_open_notified: true, checkin_closing_notified: true } : { checkin_open_notified: true },
  };
}
