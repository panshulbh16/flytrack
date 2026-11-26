// Past flight search (docs/P3_CONTRACTS.md B6): search-flight with mode "past" accepts dates up to 365
// days back, for adding old trips to Passport.
//
// A past leg is stored with next_poll_at = null (never polled) and goes into flight_leg_history at once when
// the provider has actual times (or it was cancelled). The apps then track it with source 'manual' and
// archived_at = now(), so it lands straight in Passport (archived rows don't count against the tracking cap,
// don't take the Pro trial and don't restart polling).
import { type Deps, ingest, parseFlightNumber, SearchError, type SearchLimits } from "./engine.ts";
import { legHistoryRow } from "./history.ts";
import type { FlightRow, FlightSnapshot } from "./types.ts";

export const PAST_SEARCH_MAX_DAYS = 365;
const DAY_MS = 86_400_000;

/** Done legs: nothing about them will change any more, so a stored row is as good as a provider call. */
const FINAL_STATUSES = new Set(["arrived", "landed", "cancelled", "diverted"]);

export function isFinishedRow(r: FlightRow): boolean {
  return FINAL_STATUSES.has(r.status) || r.act_arr != null;
}

/** The provider had real outcome data for the leg: worth a flight_leg_history row. */
export function hasOutcome(s: Pick<FlightSnapshot, "actDep" | "actArr" | "status">): boolean {
  return s.actDep != null || s.actArr != null || s.status === "cancelled";
}

/** "YYYY-MM-DD" check for past mode: from 365 days back up to today (UTC day, plus one for time zones). */
export function validatePastDate(date: string, now: Date): void {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date ?? "")) throw new SearchError("bad_input", "Date must be YYYY-MM-DD");
  const day = Date.parse(`${date}T00:00:00Z`);
  const today = Date.parse(`${now.toISOString().slice(0, 10)}T00:00:00Z`);
  if (Number.isNaN(day) || day < today - PAST_SEARCH_MAX_DAYS * DAY_MS || day > today + DAY_MS) {
    throw new SearchError("bad_input", `Date must be within the last ${PAST_SEARCH_MAX_DAYS} days`);
  }
}

export async function searchPastFlight(
  deps: Deps,
  userId: string,
  input: { flight: string; date: string },
  limits: SearchLimits = { perUserPerDay: 30, freshForMinutes: 10 },
): Promise<FlightRow[]> {
  const fn = parseFlightNumber(input.flight ?? "");
  if (!fn) throw new SearchError("bad_input", "Flight number looks wrong. Example: 6E 2341");
  const now = deps.now();
  validatePastDate(input.date, now);

  // 1) Cache: a finished leg we already have never needs another API call.
  const cached = await deps.repo.getFlightsByNumberDate(fn.carrier, fn.number, input.date);
  if (cached.length > 0 && cached.every(isFinishedRow)) return cached;

  // 2) Same per-user quota as every search (the caller wraps it in reserve_search_quota).
  const since = new Date(now.getTime() - DAY_MS).toISOString();
  if (await deps.repo.countUserProviderCalls(userId, since) >= limits.perUserPerDay) {
    if (cached.length) return cached;
    throw new SearchError("rate_limited", "Too many searches today. Try again later.");
  }

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
    // A row someone is following (polling, trackers) keeps its own lifecycle; only new rows are ours to shape.
    if (existing && (existing.next_poll_at != null || existing.tracker_count > 0)) {
      out.push(existing);
      continue;
    }
    let row = (await ingest(deps, existing, s, "search")).row;
    if (row.next_poll_at != null) row = await deps.repo.updateFlight(row.id, { next_poll_at: null, lease_until: null });
    if (hasOutcome(s)) {
      try {
        await deps.repo.upsertLegHistory(legHistoryRow(row, now));
      } catch (e) {
        deps.log?.("past leg history failed", { flight: row.id, error: String(e) }); // never fail a search for stats
      }
    }
    out.push(row);
  }
  return out;
}
