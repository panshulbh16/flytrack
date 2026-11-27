// Radar request handling (docs/P4_CONTRACTS.md section 1), free of Supabase so it is unit-tested with
// fakes. index.ts wires the real auth, Pro check, quota RPC, airports / flights reads and feed.
import { type AircraftPosition, type FeedChoice, FeedError, roundToCell } from "../_shared/positions.ts";

export const GROUND_RADIUS_KM = 5;
export const PROXIMITY_RADIUS_KM = 75;
export const CACHE_TTL_MS = 10_000;
const CACHE_MAX_ENTRIES = 500;

export type RadarAircraft = AircraftPosition & { flight?: string };

export interface RadarResponse {
  center: { lat: number; lon: number };
  radius_km: number;
  aircraft: RadarAircraft[];
  feed: string;
  as_of: string;
}

export type RadarQuery =
  | { mode: "ground"; iata: string }
  | { mode: "proximity"; lat: number; lon: number };

export type RadarErrorCode =
  | "unauthorized"
  | "bad_input"
  | "not_found"
  | "pro_required"
  | "feed_unavailable"
  | "feed_unlicensed"
  | "rate_limited"
  | "feed_error"
  | "internal";

export const RADAR_STATUS: Record<RadarErrorCode, number> = {
  unauthorized: 401,
  bad_input: 400,
  not_found: 404,
  pro_required: 402,
  feed_unavailable: 503,
  feed_unlicensed: 503,
  rate_limited: 429,
  feed_error: 502,
  internal: 500,
};

/** One `flights` row as the callsign matcher needs it. */
export interface FlightCandidate {
  carrier_iata: string;
  carrier_icao: string | null;
  flight_number: string;
  flight_date: string;
  status: string;
  sched_dep: string | null;
}

export interface RadarDeps {
  /** The caller's user id from the JWT, or null. */
  authUser(authorization: string): Promise<string | null>;
  isPro(userId: string): Promise<boolean>;
  /** Counts one request; false when the caller is over 60 requests per 10 minutes. */
  takeQuota(userId: string): Promise<boolean>;
  airport(iata: string): Promise<{ lat: number; lon: number } | null>;
  feed(): FeedChoice;
  /** Flights that could carry one of these (callsign-derived) flight numbers around `now`. */
  flightCandidates(numbers: string[], now: Date): Promise<FlightCandidate[]>;
  cache: RadarCache;
  now: () => Date;
  log: (msg: string, extra?: Record<string, unknown>) => void;
}

// ---------- request parsing ---------------------------------------------------------------------

export function parseRadarQuery(url: URL): RadarQuery | null {
  const mode = url.searchParams.get("mode");
  if (mode === "ground") {
    const iata = (url.searchParams.get("iata") ?? "").trim().toUpperCase();
    return /^[A-Z]{3}$/.test(iata) ? { mode, iata } : null;
  }
  if (mode === "proximity") {
    const latS = url.searchParams.get("lat"), lonS = url.searchParams.get("lon");
    if (!latS || !lonS) return null;
    const lat = Number(latS), lon = Number(lonS);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    // Rounded before anything else uses it: the precise location never reaches logs, cache or feed.
    return { mode, lat: roundToCell(lat), lon: roundToCell(lon) };
  }
  return null;
}

// ---------- cache --------------------------------------------------------------------------------

interface CacheEntry {
  at: number;
  value: Promise<RadarResponse>;
}

/** 10 s per (mode, cell). Concurrent misses for one key share a single feed call. */
export class RadarCache {
  private readonly entries = new Map<string, CacheEntry>();
  constructor(private readonly ttlMs = CACHE_TTL_MS, private readonly max = CACHE_MAX_ENTRIES) {}

  get size() {
    return this.entries.size;
  }

  async getOrLoad(key: string, nowMs: number, load: () => Promise<RadarResponse>): Promise<RadarResponse> {
    const hit = this.entries.get(key);
    if (hit && nowMs - hit.at < this.ttlMs) return await hit.value;
    const value = load();
    this.entries.set(key, { at: nowMs, value });
    value.catch(() => {
      if (this.entries.get(key)?.value === value) this.entries.delete(key);
    });
    this.evict(nowMs);
    return await value;
  }

  private evict(nowMs: number) {
    if (this.entries.size <= this.max) return;
    for (const [k, e] of this.entries) {
      if (nowMs - e.at >= this.ttlMs) this.entries.delete(k);
    }
    while (this.entries.size > this.max) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
  }
}

export function cacheKey(q: RadarQuery): string {
  return q.mode === "ground" ? `ground:${q.iata}` : `proximity:${q.lat.toFixed(2)}:${q.lon.toFixed(2)}`;
}

// ---------- callsign → flight -------------------------------------------------------------------

/** ICAO airline designator → IATA, for carriers whose flights rows may lack carrier_icao. */
export const ICAO_TO_IATA: Readonly<Record<string, string>> = {
  IGO: "6E",
  AIC: "AI",
  AXB: "IX",
  AKJ: "QP",
  SEJ: "SG",
  VTI: "UK",
  LLR: "9I",
  FLG: "S9",
  BLS: "OG",
  UAE: "EK",
  QTR: "QR",
  ETD: "EY",
  FDB: "FZ",
  ABY: "G9",
  GFA: "GF",
  OMA: "WY",
  SVA: "SV",
  KAC: "KU",
  SIA: "SQ",
  MAS: "MH",
  THA: "TG",
  ALK: "UL",
  BAW: "BA",
  DLH: "LH",
  AFR: "AF",
  KLM: "KL",
  THY: "TK",
  VIR: "VS",
  UAL: "UA",
  AAL: "AA",
  ACA: "AC",
  CPA: "CX",
  ANA: "NH",
  JAL: "JL",
  QFA: "QF",
  BBC: "BG",
  RNA: "RA",
  FFM: "8M",
  AFL: "SU",
  ETH: "ET",
  KQA: "KQ",
  AXM: "AK",
  TGW: "TR",
  JZR: "J9",
};

/** "IGO2341" → { icao: "IGO", number: "2341" }; "AIC0101" → number "101". Non-airline callsigns → null. */
export function parseCallsign(callsign: string): { icao: string; number: string } | null {
  const m = /^([A-Z]{3})(\d{1,4})([A-Z]?)$/.exec(callsign.trim().toUpperCase());
  if (!m) return null;
  const n = Number(m[2]);
  if (n === 0) return null;
  return { icao: m[1], number: `${n}${m[3]}` };
}

const AIRBORNE = new Set(["departed", "en_route", "approaching"]);
const FINISHED = new Set(["arrived", "cancelled"]);

/**
 * Picks the flight each callsign most likely is: same airline (carrier_icao, or the ICAO→IATA table)
 * and number; airborne legs first, then the leg whose departure is closest to now. Finished or
 * cancelled legs only match when nothing else does.
 */
export function matchFlight(callsign: string, candidates: FlightCandidate[], now: Date): string | null {
  const cs = parseCallsign(callsign);
  if (!cs) return null;
  const iata = ICAO_TO_IATA[cs.icao];
  const same = candidates.filter((c) =>
    c.flight_number === cs.number && (c.carrier_icao?.toUpperCase() === cs.icao || (iata && c.carrier_iata === iata))
  );
  if (!same.length) return null;
  const t = now.getTime();
  const score = (c: FlightCandidate) => {
    const rank = AIRBORNE.has(c.status) ? 0 : FINISHED.has(c.status) ? 2 : 1;
    const dep = c.sched_dep ? Date.parse(c.sched_dep) : NaN;
    return rank * 1e13 + (Number.isNaN(dep) ? 1e12 : Math.abs(dep - t));
  };
  const best = same.reduce((a, b) => (score(b) < score(a) ? b : a));
  return `${best.carrier_iata}${best.flight_number}`;
}

export function callsignNumbers(aircraft: AircraftPosition[]): string[] {
  const out = new Set<string>();
  for (const a of aircraft) {
    const cs = parseCallsign(a.callsign);
    if (cs) out.add(cs.number);
  }
  return [...out];
}

// ---------- handler ------------------------------------------------------------------------------

export interface RadarResult {
  status: number;
  body: RadarResponse | { error: RadarErrorCode; message?: string };
}

function fail(code: RadarErrorCode, message?: string): RadarResult {
  return { status: RADAR_STATUS[code], body: message ? { error: code, message } : { error: code } };
}

async function load(
  deps: RadarDeps,
  q: RadarQuery,
  center: { lat: number; lon: number },
  feed: { name: string; nearby: (lat: number, lon: number, r: number) => Promise<AircraftPosition[]> },
): Promise<RadarResponse> {
  const radius = q.mode === "ground" ? GROUND_RADIUS_KM : PROXIMITY_RADIUS_KM;
  const now = deps.now();
  let list = await feed.nearby(center.lat, center.lon, radius);
  if (q.mode === "ground") list = list.filter((a) => a.on_ground);
  const numbers = callsignNumbers(list);
  let candidates: FlightCandidate[] = [];
  if (numbers.length) {
    try {
      candidates = await deps.flightCandidates(numbers, now);
    } catch (e) {
      // the radar still works without flight labels
      deps.log("radar: flight match failed", { error: e instanceof Error ? e.message : String(e) });
    }
  }
  const aircraft: RadarAircraft[] = list.map((a) => {
    const flight = a.callsign ? matchFlight(a.callsign, candidates, now) : null;
    return flight ? { ...a, flight } : a;
  });
  return { center, radius_km: radius, aircraft, feed: feed.name, as_of: now.toISOString() };
}

export async function handleRadar(
  deps: RadarDeps,
  method: string,
  url: URL,
  authorization: string,
): Promise<RadarResult> {
  if (method !== "GET") return fail("bad_input", "GET only");
  const userId = await deps.authUser(authorization);
  if (!userId) return fail("unauthorized");
  const q = parseRadarQuery(url);
  if (!q) return fail("bad_input", "use ?mode=ground&iata=DEL or ?mode=proximity&lat=..&lon=..");
  if (!await deps.isPro(userId)) return fail("pro_required");

  const choice = deps.feed();
  if (!choice.ok) return fail(choice.error);
  if (!await deps.takeQuota(userId)) return fail("rate_limited");

  let center: { lat: number; lon: number };
  if (q.mode === "ground") {
    const ap = await deps.airport(q.iata);
    if (!ap) return fail("not_found", "unknown airport");
    center = { lat: ap.lat, lon: ap.lon };
  } else {
    center = { lat: q.lat, lon: q.lon };
  }

  try {
    const body = await deps.cache.getOrLoad(
      cacheKey(q),
      deps.now().getTime(),
      () => load(deps, q, center, choice.feed),
    );
    return { status: 200, body };
  } catch (e) {
    if (e instanceof FeedError && (e.code === "feed_unavailable" || e.code === "feed_unlicensed")) return fail(e.code);
    deps.log("radar: feed failed", { feed: choice.feed.name, error: e instanceof Error ? e.message : String(e) });
    return fail("feed_error");
  }
}
