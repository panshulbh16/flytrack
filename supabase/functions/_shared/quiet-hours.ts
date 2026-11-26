// Quiet hours (docs/P2_CONTRACTS.md section 3): during the recipient's quiet window, INFO alerts are
// delivered silently. Critical alerts are always loud; sync messages are never affected.
import type { Recipient } from "./types.ts";

export const DEFAULT_QUIET_TZ = "Asia/Kolkata";
export const DEFAULT_QUIET_START = "23:00";
export const DEFAULT_QUIET_END = "07:00";

/** "23:00", "23:00:00" or "23:00:00.000" (Postgres time) → minutes after midnight, or null. */
export function parseClock(v: unknown): number | null {
  if (typeof v !== "string") return null;
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/.exec(v.trim());
  if (!m) return null;
  const h = Number(m[1]), min = Number(m[2]);
  if (h > 24 || min > 59 || (h === 24 && min !== 0)) return null;
  return (h * 60 + min) % 1440;
}

/** Minutes after local midnight of `now` in the IANA zone `tz` (an unknown zone falls back to India). */
export function localMinutes(now: Date, tz: string | null | undefined): number {
  const fmt = (zone: string) =>
    new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: zone })
      .formatToParts(now);
  let parts: Intl.DateTimeFormatPart[];
  try {
    parts = fmt(tz || DEFAULT_QUIET_TZ);
  } catch {
    parts = fmt(DEFAULT_QUIET_TZ);
  }
  const h = Number(parts.find((p) => p.type === "hour")?.value ?? 0) % 24;
  const m = Number(parts.find((p) => p.type === "minute")?.value ?? 0);
  return h * 60 + m;
}

/**
 * Is `now` inside [start, end) in the zone `tz`? A window whose start is after its end runs across midnight
 * (23:00–07:00 = 23:00..23:59 and 00:00..06:59). start == end is an empty window, never "all day".
 */
export function inQuietWindow(now: Date, start: unknown, end: unknown, tz: string | null | undefined): boolean {
  const s = parseClock(start ?? DEFAULT_QUIET_START), e = parseClock(end ?? DEFAULT_QUIET_END);
  if (s == null || e == null || s === e) return false;
  const t = localMinutes(now, tz);
  return s < e ? t >= s && t < e : t >= s || t < e;
}

/** True when this recipient has quiet hours on and `now` falls in their window. */
export function isQuietFor(
  r: Pick<Recipient, "quiet_hours_enabled" | "quiet_start" | "quiet_end" | "quiet_tz">,
  now: Date,
): boolean {
  return r.quiet_hours_enabled === true && inQuietWindow(now, r.quiet_start, r.quiet_end, r.quiet_tz);
}
