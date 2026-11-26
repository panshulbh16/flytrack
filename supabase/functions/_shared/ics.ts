// Calendar feed (docs/P2_WAVE2_CONTRACTS.md section 3): the user's tracked flights as an RFC 5545 iCalendar.
// GET /functions/v1/calendar-feed?t=<32-char token>. The token is the only key, so the feed carries flight
// data only: no PNR, seat, user or device data. Every time is UTC ("Z"), so no VTIMEZONE is needed.
//
// RFC 5545 details handled here:
//   * lines end with CRLF (3.1)
//   * lines longer than 75 octets are folded: CRLF + one space, never inside a UTF-8 sequence (3.1)
//   * TEXT values escape backslash, semicolon, comma and newline (3.3.11)
//   * DATE-TIME in UTC form YYYYMMDDTHHMMSSZ (3.3.5); DTSTAMP and UID on every VEVENT (3.6.1)

export const CALENDAR_TOKEN_RE = /^[A-Za-z0-9]{32}$/;
export const CALENDAR_NAME = "HeyGate flights";
export const PRODID = "-//HeyGate//Flight calendar 1.0//EN";
/** Used when the arrival time is unknown or not after the departure. */
const DEFAULT_EVENT_MIN = 60;

export interface CalendarAirport {
  iata: string;
  name: string | null;
  city: string | null;
  tz: string | null;
  terminal: string | null;
  gate: string | null;
}

/** One row of public.calendar_feed(token). */
export interface CalendarFlight {
  flight_id: string;
  carrier: string;
  number: string;
  date: string | null;
  airline: string | null;
  status: string;
  sched_dep: string | null;
  est_dep: string | null;
  act_dep: string | null;
  sched_arr: string | null;
  est_arr: string | null;
  act_arr: string | null;
  dep: CalendarAirport;
  arr: CalendarAirport & { belt: string | null };
  updated_at: string | null;
}

// ---- RFC 5545 primitives ----------------------------------------------------------------------

/** Escapes a TEXT value (RFC 5545 3.3.11). Bare CRs are dropped; CRLF and LF become "\n". */
export function escapeText(value: string): string {
  return value
    .replace(/\r\n?/g, "\n")
    .replace(/\\/g, "\\\\")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,")
    .replace(/\n/g, "\\n");
}

const enc = new TextEncoder();

/**
 * Folds one content line so no physical line exceeds 75 octets (the leading space of a continuation
 * line counts). Splits only between code points, so multi-byte UTF-8 characters stay whole.
 */
export function foldLine(line: string): string {
  if (enc.encode(line).length <= 75) return line;
  const parts: string[] = [];
  let current = "";
  let bytes = 0;
  let limit = 75;
  for (const ch of line) {
    const n = enc.encode(ch).length;
    if (bytes + n > limit) {
      parts.push(current);
      current = "";
      bytes = 0;
      limit = 74; // the continuation line starts with one space
    }
    current += ch;
    bytes += n;
  }
  parts.push(current);
  return parts.join("\r\n ");
}

/** ISO-8601 → RFC 5545 UTC DATE-TIME (YYYYMMDDTHHMMSSZ), or null when unparseable. */
export function icsDateTime(value: string | Date | null | undefined): string | null {
  if (value == null) return null;
  const t = value instanceof Date ? value.getTime() : Date.parse(value);
  if (Number.isNaN(t)) return null;
  return new Date(t).toISOString().replace(/\.\d{3}Z$/, "Z").replace(/[-:]/g, "");
}

/** "NAME:value" with the value escaped as TEXT. */
function textProp(name: string, value: string): string {
  return `${name}:${escapeText(value)}`;
}

// ---- flight → VEVENT ----------------------------------------------------------------------------

const STATUS_WORD: Record<string, string> = {
  unknown: "Scheduled",
  scheduled: "Scheduled",
  check_in: "Check-in open",
  boarding: "Boarding",
  gate_closed: "Gate closed",
  departed: "Departed",
  en_route: "In the air",
  approaching: "Landing soon",
  landed: "Landed",
  arrived: "Arrived",
  cancelled: "Cancelled",
  diverted: "Diverted",
};

const NOT_YET_DEPARTED = new Set(["unknown", "scheduled", "check_in", "boarding", "gate_closed"]);

function ms(v: string | null): number | null {
  if (!v) return null;
  const t = Date.parse(v);
  return Number.isNaN(t) ? null : t;
}

/** "Delayed 25 min" before departure when the estimate is 15+ min late, else the status word. */
export function statusLine(f: CalendarFlight): string {
  const word = STATUS_WORD[f.status] ?? "Scheduled";
  if (NOT_YET_DEPARTED.has(f.status)) {
    const sched = ms(f.sched_dep), est = ms(f.est_dep);
    if (sched != null && est != null) {
      const late = Math.round((est - sched) / 60_000);
      if (late >= 15) return `Delayed ${late} min`;
    }
  }
  return word;
}

/** "1" / "T1" → "Terminal 1"; anything else is kept after "Terminal". */
export function terminalLabel(t: string | null): string | null {
  const v = t?.trim();
  if (!v) return null;
  const m = /^(?:T|Terminal\s*)?(\w{1,4})$/i.exec(v);
  return `Terminal ${m ? m[1].toUpperCase() : v}`;
}

/** "25 Nov, 13:05 IST" in the airport's time zone (UTC when the zone is unknown or invalid). */
export function localStamp(iso: string, tz: string | null): string {
  const t = new Date(iso);
  const fmt = (zone: string) => {
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: zone,
      day: "numeric",
      month: "short",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZoneName: "shortOffset",
    }).formatToParts(t);
    const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
    const offset = get("timeZoneName"); // "GMT+5:30", "GMT"
    const label = zone === "Asia/Kolkata" || zone === "Asia/Calcutta"
      ? "IST"
      : offset.replace(/^GMT$/, "UTC").replace(/^GMT/, "UTC");
    return `${get("day")} ${get("month")}, ${get("hour")}:${get("minute")} ${label}`;
  };
  try {
    return fmt(tz || "UTC");
  } catch {
    return fmt("UTC");
  }
}

export function eventSummary(f: CalendarFlight): string {
  return `✈ ${f.carrier} ${f.number} ${f.dep.iata}→${f.arr.iata}`;
}

export function eventLocation(f: CalendarFlight): string {
  const name = f.dep.name?.trim() || f.dep.iata;
  const terminal = terminalLabel(f.dep.terminal);
  return terminal ? `${name}, ${terminal}` : name;
}

export function eventDescription(f: CalendarFlight): string {
  const lines = [
    `${f.airline ? `${f.airline} ` : ""}${f.carrier} ${f.number}, ${f.dep.iata} to ${f.arr.iata}`,
    `Status: ${statusLine(f)}`,
  ];
  if (f.dep.gate) lines.push(`Gate: ${f.dep.gate}`);
  if (f.arr.belt) lines.push(`Belt: ${f.arr.belt}`);
  if (f.updated_at && ms(f.updated_at) != null) lines.push(`Last updated: ${localStamp(f.updated_at, f.dep.tz)}`);
  lines.push("Live updates in the HeyGate app.");
  return lines.join("\n");
}

/** Best known departure and arrival (actual, else estimated, else scheduled), in epoch ms. */
export function eventTimes(f: CalendarFlight): { start: number; end: number } | null {
  const start = ms(f.act_dep) ?? ms(f.est_dep) ?? ms(f.sched_dep);
  if (start == null) return null;
  const arr = ms(f.act_arr) ?? ms(f.est_arr) ?? ms(f.sched_arr);
  const end = arr != null && arr > start ? arr : start + DEFAULT_EVENT_MIN * 60_000;
  return { start, end };
}

/** Unfolded content lines of one VEVENT, or [] when the flight has no departure time at all. */
export function flightEventLines(f: CalendarFlight, now: Date): string[] {
  const times = eventTimes(f);
  if (!times) return [];
  const lines = [
    "BEGIN:VEVENT",
    `UID:${f.flight_id}@heygate.app`,
    `DTSTAMP:${icsDateTime(now)}`,
    `DTSTART:${icsDateTime(new Date(times.start))}`,
    `DTEND:${icsDateTime(new Date(times.end))}`,
    textProp("SUMMARY", eventSummary(f)),
    textProp("LOCATION", eventLocation(f)),
    textProp("DESCRIPTION", eventDescription(f)),
    `STATUS:${f.status === "cancelled" ? "CANCELLED" : "CONFIRMED"}`,
    "TRANSP:OPAQUE",
  ];
  const modified = icsDateTime(f.updated_at);
  if (modified) lines.push(`LAST-MODIFIED:${modified}`);
  lines.push("END:VEVENT");
  return lines;
}

/** The whole VCALENDAR, folded, CRLF line endings, ending with CRLF. */
export function buildCalendar(flights: CalendarFlight[], now: Date): string {
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    `PRODID:${PRODID}`,
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    textProp("X-WR-CALNAME", CALENDAR_NAME),
    "REFRESH-INTERVAL;VALUE=DURATION:PT1H",
    "X-PUBLISHED-TTL:PT1H",
  ];
  for (const f of flights) lines.push(...flightEventLines(f, now));
  lines.push("END:VCALENDAR");
  return lines.map(foldLine).join("\r\n") + "\r\n";
}

// ---- input validation ---------------------------------------------------------------------------

const str = (v: unknown): string | null => (typeof v === "string" && v.trim() !== "" ? v : null);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" ? v as Record<string, unknown> : {});

function airport(v: unknown): CalendarAirport & { belt: string | null } {
  const a = obj(v);
  return {
    iata: str(a.iata)?.trim().toUpperCase() ?? "???",
    name: str(a.name),
    city: str(a.city),
    tz: str(a.tz),
    terminal: str(a.terminal),
    gate: str(a.gate),
    belt: str(a.belt),
  };
}

/** Rebuilds calendar_feed()'s rows from a whitelist; rows without an id, carrier or number are dropped. */
export function toCalendarFlights(raw: unknown): CalendarFlight[] {
  if (!Array.isArray(raw)) return [];
  const out: CalendarFlight[] = [];
  for (const item of raw) {
    const r = obj(item);
    const id = str(r.flight_id), carrier = str(r.carrier), number = str(r.number);
    if (!id || !carrier || !number) continue;
    const { belt: _belt, ...dep } = airport(r.dep);
    out.push({
      flight_id: id,
      carrier,
      number,
      date: str(r.date),
      airline: str(r.airline),
      status: str(r.status) ?? "unknown",
      sched_dep: str(r.sched_dep),
      est_dep: str(r.est_dep),
      act_dep: str(r.act_dep),
      sched_arr: str(r.sched_arr),
      est_arr: str(r.est_arr),
      act_arr: str(r.act_arr),
      dep,
      arr: airport(r.arr),
      updated_at: str(r.updated_at),
    });
  }
  return out;
}

// ---- HTTP -----------------------------------------------------------------------------------------

export interface CalendarFeedDeps {
  /** public.calendar_feed(token): the rows, or null for an unknown token. */
  lookup(token: string): Promise<unknown | null>;
  now(): Date;
  log(msg: string, extra?: Record<string, unknown>): void;
}

const plain = (body: string, status: number) =>
  new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });

export async function handleCalendarFeed(req: Request, deps: CalendarFeedDeps): Promise<Response> {
  if (req.method !== "GET" && req.method !== "HEAD") return plain("method not allowed", 405);
  const token = new URL(req.url).searchParams.get("t") ?? "";
  if (!CALENDAR_TOKEN_RE.test(token)) return plain("not found", 404);
  try {
    const rows = await deps.lookup(token);
    if (rows == null) return plain("not found", 404);
    const body = buildCalendar(toCalendarFlights(rows), deps.now());
    return new Response(req.method === "HEAD" ? null : body, {
      status: 200,
      headers: {
        "content-type": "text/calendar; charset=utf-8",
        "content-disposition": 'inline; filename="heygate.ics"',
        // Calendar apps poll on their own schedule; a short private cache absorbs bursts.
        "cache-control": "private, max-age=300",
      },
    });
  } catch (e) {
    deps.log("calendar-feed failed", { error: e instanceof Error ? e.message : "error" }); // never the token
    return plain("error", 500);
  }
}
