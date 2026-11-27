// Public, user-free statistics (docs/P3_CONTRACTS.md B8 and B11):
//   GET /functions/v1/airport-status?iata=DEL  → { airport: <public Airport Health + today's counts> }  (5 min)
//   GET /functions/v1/global-report?year=2026  → { report: <anonymous yearly aggregates> }            (1 h)
// Both are verify_jwt = false and CORS *. The SQL functions behind them (airport_status_public,
// global_report) are service-role only and already aggregate; the response is still rebuilt field by
// field from a whitelist here, and global_report's k-anonymity rules are re-applied, so a later SQL change
// can never leak more than this file allows.

export const AIRPORT_STATUS_TTL_S = 300;
export const GLOBAL_REPORT_TTL_S = 3600;
/** k-anonymity: every ranked airline / airport / route row stands for at least this many legs. */
export const REPORT_MIN_LEGS = 200;
/** Unique users are shown rounded to the nearest 100, and not at all below this. */
export const REPORT_MIN_USERS = 1000;

const IATA_RE = /^[A-Z0-9]{3}$/;
const LEVELS = new Set(["normal", "busy", "disrupted", "severe"]);

// ---------- small value helpers -------------------------------------------------------------------

const str = (v: unknown): string | null => (typeof v === "string" && v !== "" ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const int = (v: unknown): number | null => {
  const n = num(v);
  return n === null ? null : Math.round(n);
};
const bool = (v: unknown): boolean | null => (typeof v === "boolean" ? v : null);
const obj = (v: unknown): Record<string, unknown> =>
  v && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : {};
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Tiny TTL cache per Edge Function instance (cuts DB load; the CDN / browser cache via Cache-Control too). */
export class TtlCache<T> {
  private readonly items = new Map<string, { at: number; value: T }>();
  constructor(private readonly ttlMs: number, private readonly max = 500) {}
  get(key: string, now: number): T | undefined {
    const hit = this.items.get(key);
    if (!hit) return undefined;
    if (now - hit.at >= this.ttlMs) {
      this.items.delete(key);
      return undefined;
    }
    return hit.value;
  }
  set(key: string, value: T, now: number) {
    if (this.items.size >= this.max) this.items.delete(this.items.keys().next().value!);
    this.items.set(key, { at: now, value });
  }
}

// ---------- airport status ------------------------------------------------------------------------

export interface PublicAirportStatus {
  iata: string;
  name: string | null;
  city: string | null;
  tz: string | null;
  lat: number | null;
  lon: number | null;
  level: string;
  headline: string;
  updated_at: string | null;
  weather: {
    summary: string | null;
    visibility_m: number | null;
    category: string | null;
    fog: boolean | null;
    observed_at: string | null;
  } | null;
  today: {
    date: string | null;
    departures: number;
    departed: number;
    delayed_pct: number | null;
    cancelled: number;
  };
}

export function toPublicAirportStatus(raw: Record<string, unknown>): PublicAirportStatus {
  const w = raw.weather == null ? null : obj(raw.weather);
  const t = obj(raw.today);
  const level = str(raw.level) ?? "normal";
  return {
    iata: str(raw.iata) ?? "",
    name: str(raw.name),
    city: str(raw.city),
    tz: str(raw.tz),
    lat: num(raw.lat),
    lon: num(raw.lon),
    level: LEVELS.has(level) ? level : "normal",
    headline: str(raw.headline) ?? "Operating normally",
    updated_at: str(raw.updated_at),
    weather: w === null ? null : {
      summary: str(w.summary),
      visibility_m: int(w.visibility_m),
      category: str(w.category),
      fog: bool(w.fog),
      observed_at: str(w.observed_at),
    },
    today: {
      date: str(t.date),
      departures: int(t.departures) ?? 0,
      departed: int(t.departed) ?? 0,
      delayed_pct: int(t.delayed_pct),
      cancelled: int(t.cancelled) ?? 0,
    },
  };
}

// ---------- global report -------------------------------------------------------------------------

export interface ReportAirline {
  carrier: string;
  name: string | null;
  legs: number;
  on_time_pct: number | null;
  avg_dep_delay: number | null;
}
export interface ReportAirport {
  iata: string;
  name: string | null;
  city: string | null;
  legs: number;
  on_time_pct: number | null;
  avg_dep_delay: number | null;
}
/** A busiest route; the airports' public coordinates feed the report page's routes globe. */
export interface ReportRoute {
  dep: string;
  arr: string;
  legs: number;
  dep_lat: number | null;
  dep_lon: number | null;
  arr_lat: number | null;
  arr_lon: number | null;
}
export interface PublicGlobalReport {
  year: number;
  generated_at: string | null;
  totals: { flights_tracked: number; distance_km: number; unique_users: number | null; legs: number };
  airlines: { most_delayed: ReportAirline[]; most_punctual: ReportAirline[] };
  airports: { most_delayed: ReportAirport[]; most_punctual: ReportAirport[] };
  busiest_routes: ReportRoute[];
  fog: { airport: string; fog_days: number; fog_delay_days: number };
}

/** Rounded to the nearest 100, null under REPORT_MIN_USERS (small counts would identify people). */
export function bucketUsers(n: number | null): number | null {
  if (n === null || n < REPORT_MIN_USERS) return null;
  return Math.round(n / 100) * 100;
}

const enoughLegs = (r: { legs: number }) => r.legs >= REPORT_MIN_LEGS;

function airline(v: unknown): ReportAirline {
  const r = obj(v);
  return {
    carrier: str(r.carrier) ?? "",
    name: str(r.name),
    legs: int(r.legs) ?? 0,
    on_time_pct: int(r.on_time_pct),
    avg_dep_delay: int(r.avg_dep_delay),
  };
}

function airport(v: unknown): ReportAirport {
  const r = obj(v);
  return {
    iata: str(r.iata) ?? "",
    name: str(r.name),
    city: str(r.city),
    legs: int(r.legs) ?? 0,
    on_time_pct: int(r.on_time_pct),
    avg_dep_delay: int(r.avg_dep_delay),
  };
}

export function toPublicGlobalReport(raw: Record<string, unknown>, year: number): PublicGlobalReport {
  const totals = obj(raw.totals);
  const airlines = obj(raw.airlines);
  const airports = obj(raw.airports);
  const fog = obj(raw.fog);
  const users = int(totals.unique_users);
  return {
    year,
    generated_at: str(raw.generated_at),
    totals: {
      flights_tracked: int(totals.flights_tracked) ?? 0,
      distance_km: int(totals.distance_km) ?? 0,
      // the SQL already buckets; bucketing an already-bucketed number is a no-op
      unique_users: bucketUsers(users),
      legs: int(totals.legs) ?? 0,
    },
    airlines: {
      most_delayed: arr(airlines.most_delayed).map(airline).filter(enoughLegs).slice(0, 5),
      most_punctual: arr(airlines.most_punctual).map(airline).filter(enoughLegs).slice(0, 5),
    },
    airports: {
      most_delayed: arr(airports.most_delayed).map(airport).filter(enoughLegs).slice(0, 5),
      most_punctual: arr(airports.most_punctual).map(airport).filter(enoughLegs).slice(0, 5),
    },
    busiest_routes: arr(raw.busiest_routes)
      .map((v) => {
        const r = obj(v);
        return {
          dep: str(r.dep) ?? "",
          arr: str(r.arr) ?? "",
          legs: int(r.legs) ?? 0,
          dep_lat: num(r.dep_lat),
          dep_lon: num(r.dep_lon),
          arr_lat: num(r.arr_lat),
          arr_lon: num(r.arr_lon),
        };
      })
      .filter(enoughLegs)
      .slice(0, 10),
    fog: {
      airport: str(fog.airport) ?? "DEL",
      fog_days: int(fog.fog_days) ?? 0,
      fog_delay_days: int(fog.fog_delay_days) ?? 0,
    },
  };
}

/** The report's default year: the current year in India. */
export function currentIndianYear(now: Date): number {
  return new Date(now.getTime() + 330 * 60_000).getUTCFullYear();
}

// ---------- HTTP ----------------------------------------------------------------------------------

function headers(maxAgeS: number | null): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
    "access-control-allow-methods": "GET, OPTIONS",
    "cache-control": maxAgeS === null ? "no-store" : `public, max-age=${maxAgeS}`,
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
  };
}

function reply(body: unknown, status: number, maxAgeS: number | null, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...headers(maxAgeS), "content-type": "application/json", ...extra },
  });
}

export interface PublicStatsDeps<T> {
  lookup(key: string): Promise<Record<string, unknown> | null>;
  now: () => Date;
  cache: TtlCache<T | null>;
  log?: (msg: string, extra?: Record<string, unknown>) => void;
}

export async function handleAirportStatus(
  req: Request,
  deps: PublicStatsDeps<PublicAirportStatus>,
): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: headers(null) });
  if (req.method !== "GET") return reply({ error: "method_not_allowed" }, 405, null, { allow: "GET, OPTIONS" });
  const iata = (new URL(req.url).searchParams.get("iata") ?? "").trim().toUpperCase();
  if (!IATA_RE.test(iata)) return reply({ error: "bad_input", message: "iata must be a 3-letter code" }, 400, null);

  const now = deps.now().getTime();
  let value = deps.cache.get(iata, now);
  if (value === undefined) {
    try {
      const raw = await deps.lookup(iata);
      value = raw ? toPublicAirportStatus(raw) : null;
    } catch (e) {
      deps.log?.("airport-status lookup failed", { iata, error: String(e) });
      return reply({ error: "unavailable" }, 503, null, { "retry-after": "30" });
    }
    deps.cache.set(iata, value, now);
  }
  if (!value) return reply({ error: "not_found" }, 404, AIRPORT_STATUS_TTL_S);
  return reply({ airport: value }, 200, AIRPORT_STATUS_TTL_S);
}

export async function handleGlobalReport(
  req: Request,
  deps: PublicStatsDeps<PublicGlobalReport>,
): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: headers(null) });
  if (req.method !== "GET") return reply({ error: "method_not_allowed" }, 405, null, { allow: "GET, OPTIONS" });
  const param = new URL(req.url).searchParams.get("year");
  const current = currentIndianYear(deps.now());
  const year = param == null || param === "" ? current : /^\d{4}$/.test(param) ? Number(param) : NaN;
  if (!Number.isInteger(year) || year < 2024 || year > current) {
    return reply({ error: "bad_input", message: `year must be between 2024 and ${current}` }, 400, null);
  }

  const now = deps.now().getTime();
  const key = String(year);
  let value = deps.cache.get(key, now);
  if (value === undefined) {
    try {
      const raw = await deps.lookup(key);
      value = raw ? toPublicGlobalReport(raw, year) : null;
    } catch (e) {
      deps.log?.("global-report lookup failed", { year, error: String(e) });
      return reply({ error: "unavailable" }, 503, null, { "retry-after": "60" });
    }
    deps.cache.set(key, value, now);
  }
  if (!value) return reply({ error: "not_found" }, 404, GLOBAL_REPORT_TTL_S);
  return reply({ report: value }, 200, GLOBAL_REPORT_TTL_S);
}
