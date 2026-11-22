import type { FlightSnapshot } from "./types.ts";

/**
 * Adaptive polling: how long until we ask the provider about this flight again?
 * Returns null = stop polling (flight finished / cancelled long ago).
 *
 * Cost thinking (one unique flight, all users together):
 *   far away → almost nothing; "gate window" (T-90m → off-block) and "belt window"
 *   (landing → belt known) are the hot zones where users want INSTANT alerts.
 */
export interface ScheduleContext {
  now: Date;
  hasPro: boolean; // at least one Pro user tracks it (or its dependent flight)
  webhookActive: boolean; // provider will push changes; polling is only a safety net
  changedThisPoll: boolean; // something changed → re-check soon (changes come in bursts)
  errorCount: number;
  /** Row is polled only because it is the INBOUND aircraft of a Pro-tracked flight (no own trackers). */
  inboundOnly?: boolean;
}

const MIN = 60_000;
const H = 60 * MIN;
/** Never poll later than this after the last known departure/arrival time. */
export const MAX_POLL_AFTER_LAST_KNOWN_H = 12;

/** Tunables in one place (minutes). Free vs Pro differ only near departure/arrival. */
export const CADENCE = {
  beyond72h: null as null, // no repeated polling; we schedule the first poll at T-72h
  h72to24: 360,
  h24to6: 90,
  h6to3: 20,
  h3to90m: { pro: 5, free: 10 },
  gateWindow: { pro: 2, free: 3 }, // T-90m → departed: gate changes, boarding
  airborne: 15,
  approach: { pro: 3, free: 4 }, // last 40 min before ETA
  beltWindow: { pro: 2, free: 2 }, // landed → belt known: belt alerts
  afterBelt: 5, // keep watching for belt changes until arrival+45m
  cancelledRecheck: 30, // a cancellation can be withdrawn (AeroDataBox: CanceledUncertain)
  unknown: 60,
  burst: 2, // after any change
  // Inbound legs: we only need their departure/arrival ETA, not gates or belts.
  inbound: { far: 30, near: 15, nearDep: 10, airborne: 20, approach: 10 },
} as const;

function ms(iso: string | null): number | null {
  return iso ? Date.parse(iso) : null;
}

export function nextPollAt(s: FlightSnapshot, ctx: ScheduleContext): Date | null {
  const now = ctx.now.getTime();
  const tier = ctx.hasPro ? "pro" : "free";
  const dep = ms(s.actDep) ?? ms(s.estDep) ?? ms(s.schedDep);
  const arr = ms(s.actArr) ?? ms(s.estArr) ?? ms(s.schedArr);

  let minutes: number | null;

  // Hard stop, whatever the status/errors: 12h after the latest known dep/arr time (or the flight date).
  // Covers "departed but never landed in the data", "arrived" without a time, endless provider errors.
  const lastKnown = dep != null || arr != null
    ? Math.max(dep ?? -Infinity, arr ?? -Infinity)
    : s.date ? Date.parse(`${s.date}T00:00:00Z`) + 24 * H : NaN; // no times at all → end of the flight date
  if (Number.isFinite(lastKnown) && now > lastKnown + MAX_POLL_AFTER_LAST_KNOWN_H * H) return null;
  if ((s.status === "arrived" || s.status === "landed") && !s.actArr && arr != null && now > arr + 2 * H) return null;

  if (ctx.inboundOnly && ctx.errorCount === 0) {
    if (s.actArr || s.status === "landed" || s.status === "arrived" || s.status === "cancelled") return null; // ETA final
    const I = CADENCE.inbound;
    if (s.actDep || ["departed", "en_route", "approaching", "diverted"].includes(s.status)) {
      minutes = arr != null && now >= arr - 40 * MIN ? I.approach : I.airborne;
    } else if (dep != null && dep - now <= 90 * MIN) minutes = I.nearDep;
    else if (dep != null && dep - now <= 3 * H) minutes = I.near;
    else minutes = I.far;
    if (ctx.changedThisPoll) minutes = Math.min(minutes, CADENCE.burst);
    return new Date(now + minutes * MIN);
  }

  if (ctx.errorCount > 0) {
    // exponential backoff 1,2,4,8,15 min — but never longer than the normal cadence would be
    const backoff = Math.min(2 ** (ctx.errorCount - 1), 15);
    const normal = nextPollAt(s, { ...ctx, errorCount: 0 });
    const t = now + backoff * MIN;
    return normal ? new Date(Math.min(t, normal.getTime())) : new Date(t);
  }

  if (s.status === "cancelled") {
    // re-check twice within ~1h, then stop (handled by caller via sched time)
    if (dep != null && now > dep + 2 * H) return null;
    minutes = CADENCE.cancelledRecheck;
  } else if (s.actArr || s.status === "landed" || s.status === "arrived") {
    const landed = ms(s.actArr) ?? now;
    if (now > landed + 45 * MIN) return null; // done: belt window closed
    minutes = s.baggageBelt ? CADENCE.afterBelt : CADENCE.beltWindow[tier];
  } else if (s.actDep || ["departed", "en_route", "approaching", "diverted"].includes(s.status)) {
    if (arr != null && now >= arr - 40 * MIN) minutes = CADENCE.approach[tier];
    else {
      minutes = CADENCE.airborne;
      // wake up right at the approach window if it's sooner
      if (arr != null) minutes = Math.min(minutes, Math.max(1, (arr - 40 * MIN - now) / MIN));
    }
    // safety: a flight "airborne" 6h past ETA with no landing data → slow down
    if (arr != null && now > arr + 6 * H) minutes = CADENCE.unknown;
  } else if (dep == null) {
    minutes = CADENCE.unknown;
  } else {
    const until = dep - now; // ms until (estimated) departure
    if (until > 72 * H) {
      return new Date(dep - 72 * H); // first real poll at T-72h
    } else if (until > 24 * H) minutes = CADENCE.h72to24;
    else if (until > 6 * H) minutes = CADENCE.h24to6;
    else if (until > 3 * H) minutes = CADENCE.h6to3;
    else if (until > 90 * MIN) minutes = CADENCE.h3to90m[tier];
    else minutes = CADENCE.gateWindow[tier]; // includes "past STD but not yet departed"

    // Don't overshoot the next band boundary (e.g. sleeping 6h at T-25h would skip T-24h band).
    const boundaries = [72 * H, 24 * H, 6 * H, 3 * H, 90 * MIN].map((b) => dep - b).filter((t) => t > now);
    if (boundaries.length) {
      const nextBoundary = Math.min(...boundaries);
      minutes = Math.min(minutes, Math.max(1, (nextBoundary - now) / MIN));
    }
    // Stale schedule: 12h past departure and still nothing → stop
    if (now > dep + 12 * H) return null;
  }

  if (minutes == null) return null;
  if (ctx.changedThisPoll) minutes = Math.min(minutes, CADENCE.burst);
  if (ctx.webhookActive && !ctx.changedThisPoll) {
    // Webhooks are best-effort: keep polling, but 2x slower (hot windows max 5 min).
    minutes = minutes <= 5 ? Math.min(minutes * 2, 5) : minutes * 2;
  }
  return new Date(now + minutes * MIN);
}
