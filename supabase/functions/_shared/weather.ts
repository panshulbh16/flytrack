// Airport weather (docs/P2_CONTRACTS.md section 1): aviationweather.gov METAR JSON → airport_weather rows.
// Pure parsing/normalising plus the refresh step behind the refresh-airport-weather Edge Function.
// The source is US public data (no key). Requests carry only ICAO codes and the User-Agent FlyTrack/1.0.

export const METAR_URL = "https://aviationweather.gov/api/data/metar";
export const USER_AGENT = "FlyTrack/1.0";
/** At most this many ICAO codes per request (contract). */
export const BATCH_SIZE = 30;
/** Contract value for "10 km or more" (CAVOK, 9999, 10SM, P6SM, "10+"). */
export const VIS_10KM_PLUS = 9999;
/** Indian metros that are always refreshed, tracked flights or not. */
export const METRO_IATA = ["DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "COK", "AMD", "PNQ", "GOI", "GOX", "LKO", "ATQ", "JAI"] as const;

const METRES_PER_SM = 1609.344;
const MPS_TO_KT = 1.943844;

export type FlightCategory = "VFR" | "MVFR" | "IFR" | "LIFR";
const CATEGORIES: readonly string[] = ["VFR", "MVFR", "IFR", "LIFR"];

/** One element of the aviationweather.gov /api/data/metar?format=json array (fields we use). */
export interface MetarJson {
  icaoId: string;
  obsTime?: number | null; // unix seconds
  reportTime?: string | null; // "2026-11-26 00:30:00" or ISO
  rawOb?: string | null;
  visib?: number | string | null; // statute miles, or "10+" / "6+"
  wdir?: number | string | null; // degrees, or "VRB"
  wspd?: number | null; // knots
  wgst?: number | null; // knots
  wxString?: string | null;
  clouds?: { cover: string; base?: number | null }[] | null;
  fltCat?: string | null;
}

/** A row of public.airport_weather (what the refresh function upserts). */
export interface AirportWeatherRow {
  iata: string;
  icao: string;
  observed_at: string;
  fetched_at: string;
  raw_metar: string;
  visibility_m: number | null;
  ceiling_ft: number | null;
  wind_kts: number | null;
  gust_kts: number | null;
  wx: string | null;
  flight_category: FlightCategory | null;
  fog: boolean;
}

// ---------- raw METAR groups ----------

/** The main body of a METAR: trend forecasts (TEMPO/BECMG/NOSIG) and remarks are not current weather. */
export function metarBody(raw: string): string[] {
  // Many Indian reports end with "=" ("... Q1018 NOSIG="): it terminates the message, it is not part of a group.
  const tokens = raw.trim().toUpperCase().split(/\s+/).map((t) => t.replace(/=+$/, "")).filter(Boolean);
  const end = tokens.findIndex((t) => t === "TEMPO" || t === "BECMG" || t === "NOSIG" || t === "RMK");
  return end >= 0 ? tokens.slice(0, end) : tokens;
}

/** Index of the first group after the observation time ("260030Z"), skipping METAR/SPECI, station, COR/AUTO. */
function afterTime(t: string[]): number {
  const i = t.findIndex((x) => /^\d{6}Z$/.test(x));
  return i >= 0 ? i + 1 : Math.min(2, t.length);
}

function parseFraction(s: string): number | null {
  const m = /^(\d+)\/(\d+)$/.exec(s);
  if (!m || Number(m[2]) === 0) return null;
  return Number(m[1]) / Number(m[2]);
}

/** Statute miles → metres; 6 SM and more counts as 10 km+ (that is what "P6SM" / "10+" mean in practice). */
export function smToMetres(sm: number): number {
  if (sm >= 6) return VIS_10KM_PLUS;
  return Math.round(sm * METRES_PER_SM);
}

/**
 * Prevailing visibility in metres from the raw METAR.
 * ICAO style: "0050", "1500", "9999", "0800NE", "CAVOK". US style: "10SM", "P6SM", "1/2SM", "M1/4SM", "1 1/2SM".
 */
export function visibilityFromRaw(raw: string): number | null {
  const t = metarBody(raw);
  for (let i = afterTime(t); i < t.length; i++) {
    const tok = t[i];
    if (tok === "CAVOK") return VIS_10KM_PLUS;
    const icao = /^(\d{4})(N|NE|E|SE|S|SW|W|NW|NDV)?$/.exec(tok);
    if (icao) return Number(icao[1]) >= 9999 ? VIS_10KM_PLUS : Number(icao[1]);
    const sm = /^([PM])?(\d+(?:\/\d+)?)SM$/.exec(tok);
    if (sm) {
      let miles = sm[2].includes("/") ? parseFraction(sm[2]) : Number(sm[2]);
      if (miles == null) return null;
      // "1 1/2SM": whole miles in the previous token
      if (sm[2].includes("/") && i > 0 && /^\d$/.test(t[i - 1])) miles += Number(t[i - 1]);
      if (sm[1] === "P") return VIS_10KM_PLUS;
      return smToMetres(miles);
    }
  }
  return null;
}

/** The JSON visib field: statute miles as a number, or "10+" / "6+" / "1/2". */
export function visibilityFromJson(visib: MetarJson["visib"]): number | null {
  if (visib == null || visib === "") return null;
  if (typeof visib === "number") return Number.isFinite(visib) && visib >= 0 ? smToMetres(visib) : null;
  const s = visib.trim();
  if (/^\d+(\.\d+)?\+$/.test(s)) return VIS_10KM_PLUS;
  const frac = parseFraction(s);
  if (frac != null) return smToMetres(frac);
  const n = Number(s);
  return Number.isFinite(n) && n >= 0 ? smToMetres(n) : null;
}

/** Lowest broken / overcast layer or vertical visibility, in feet. Null = no ceiling. */
export function ceilingFromRaw(raw: string): number | null {
  let lowest: number | null = null;
  for (const tok of metarBody(raw)) {
    const m = /^(BKN|OVC|VV)(\d{3})(CB|TCU)?$/.exec(tok);
    if (!m) continue;
    const ft = Number(m[2]) * 100;
    if (lowest == null || ft < lowest) lowest = ft;
  }
  return lowest;
}

export function ceilingFromJson(clouds: MetarJson["clouds"]): number | null {
  let lowest: number | null = null;
  for (const c of clouds ?? []) {
    if (!["BKN", "OVC", "OVX", "VV"].includes(String(c.cover).toUpperCase())) continue;
    if (typeof c.base !== "number" || !Number.isFinite(c.base)) continue;
    if (lowest == null || c.base < lowest) lowest = c.base;
  }
  return lowest;
}

/** Wind and gust in knots from the raw group ("00000KT", "27008G18KT", "VRB03KT", "05004MPS"). */
export function windFromRaw(raw: string): { wind: number; gust: number | null } | null {
  for (const tok of metarBody(raw)) {
    const m = /^(\d{3}|VRB)(\d{2,3})(?:G(\d{2,3}))?(KT|MPS)$/.exec(tok);
    if (!m) continue;
    const k = m[4] === "MPS" ? MPS_TO_KT : 1;
    return { wind: Math.round(Number(m[2]) * k), gust: m[3] ? Math.round(Number(m[3]) * k) : null };
  }
  return null;
}

const WX_GROUP =
  /^(\+|-|VC)?(MI|PR|BC|DR|BL|SH|TS|FZ)?((DZ|RA|SN|SG|IC|PL|GR|GS|UP|BR|FG|FU|VA|DU|SA|HZ|PY|PO|SQ|FC|SS|DS)+)?$/;

/** Present-weather groups in the main body, e.g. "FG", "BCFG BR", "-TSRA", "HZ". Null when none. */
export function wxFromRaw(raw: string): string | null {
  const out: string[] = [];
  const t = metarBody(raw);
  for (const tok of t.slice(afterTime(t))) {
    if (tok === "TS" || tok === "VCTS") {
      out.push(tok);
      continue;
    }
    const m = WX_GROUP.exec(tok);
    if (m && m[3]) out.push(tok);
  }
  return out.length ? out.join(" ") : null;
}

/** FAA categories from visibility (m) and ceiling (ft); null when neither is known. */
export function flightCategory(visM: number | null, ceilFt: number | null): FlightCategory | null {
  if (visM == null && ceilFt == null) return null;
  const sm = visM == null ? Infinity : visM >= VIS_10KM_PLUS ? Infinity : visM / METRES_PER_SM;
  const c = ceilFt ?? Infinity;
  if (c < 500 || sm < 1) return "LIFR";
  if (c < 1000 || sm < 3) return "IFR";
  if (c <= 3000 || sm <= 5) return "MVFR";
  return "VFR";
}

/**
 * Contract: fog = FG in the present weather (FG, BCFG, MIFG, FZFG, PRFG) or visibility below 1000 m.
 * VCFG is fog in the vicinity (8-16 km away), not at the aerodrome: it stays in wx but is not "fog" here,
 * otherwise a clear airport would get a critical "Fog at …" alert.
 */
export function isFog(wx: string | null, visM: number | null): boolean {
  if (wx && wx.split(/\s+/).some((g) => g.includes("FG") && !g.startsWith("VC"))) return true;
  return visM != null && visM < 1000;
}

function observedAt(m: MetarJson): string | null {
  if (typeof m.obsTime === "number" && Number.isFinite(m.obsTime)) return new Date(m.obsTime * 1000).toISOString();
  if (m.reportTime) {
    const s = m.reportTime.trim().replace(" ", "T");
    const t = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`);
    if (!Number.isNaN(t)) return new Date(t).toISOString();
  }
  return null;
}

/**
 * One METAR → one airport_weather row. The raw report wins over the JSON fields where both exist (the raw
 * text is what was observed; the JSON is a conversion of it), and the JSON fills gaps. Returns null for
 * an entry without a usable time or report.
 */
export function normaliseMetar(m: MetarJson, iata: string, fetchedAt: Date): AirportWeatherRow | null {
  const raw = (m.rawOb ?? "").trim();
  const obs = observedAt(m);
  if (!raw || !obs || !m.icaoId) return null;

  const visibility = visibilityFromRaw(raw) ?? visibilityFromJson(m.visib);
  const rawCeiling = ceilingFromRaw(raw);
  const ceiling = rawCeiling ?? (/\b(CAVOK|SKC|CLR|NSC|NCD)\b/.test(raw.toUpperCase()) ? null : ceilingFromJson(m.clouds));
  const rawWind = windFromRaw(raw);
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? Math.round(v) : null);
  const wind = rawWind?.wind ?? num(m.wspd);
  const gust = rawWind ? rawWind.gust : num(m.wgst);
  const wx = wxFromRaw(raw) ?? (m.wxString?.trim() ? m.wxString.trim().toUpperCase() : null);
  const fromJson = m.fltCat && CATEGORIES.includes(m.fltCat.toUpperCase()) ? m.fltCat.toUpperCase() as FlightCategory : null;

  return {
    iata,
    icao: m.icaoId.toUpperCase(),
    observed_at: obs,
    fetched_at: fetchedAt.toISOString(),
    raw_metar: raw.slice(0, 1000),
    visibility_m: visibility,
    ceiling_ft: ceiling,
    wind_kts: wind,
    gust_kts: gust,
    wx: wx ? wx.slice(0, 60) : null,
    flight_category: flightCategory(visibility, ceiling) ?? fromJson,
    fog: isFog(wx, visibility),
  };
}

// ---------- refresh step ----------

export interface WeatherTarget {
  iata: string;
  icao: string;
}

/** Storage used by the refresh step: SQL RPCs in production, a fake in tests. */
export interface WeatherStore {
  /** public.weather_refresh_targets(): metros + airports with a tracked flight in the next 24 h. */
  targets(): Promise<WeatherTarget[]>;
  /** public.upsert_airport_weather(): returns how many rows were written. */
  upsert(rows: AirportWeatherRow[]): Promise<number>;
}

export interface RefreshDeps {
  store: WeatherStore;
  fetch: typeof fetch;
  now: () => Date;
  log?: (msg: string, extra?: Record<string, unknown>) => void;
  timeoutMs?: number;
}

export interface RefreshStats {
  targets: number;
  batches: number;
  failedBatches: number;
  reports: number;
  upserted: number;
}

export function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

export function metarRequestUrl(icaos: string[]): string {
  return `${METAR_URL}?ids=${icaos.map(encodeURIComponent).join(",")}&format=json`;
}

/** Fetch one batch. 204 (no reports) → []. Anything else unexpected throws. */
export async function fetchMetars(deps: Pick<RefreshDeps, "fetch" | "timeoutMs">, icaos: string[]): Promise<MetarJson[]> {
  const res = await deps.fetch(metarRequestUrl(icaos), {
    headers: { "user-agent": USER_AGENT, accept: "application/json" },
    signal: AbortSignal.timeout(deps.timeoutMs ?? 10_000),
  });
  if (res.status === 204) return [];
  if (!res.ok) {
    await res.body?.cancel();
    throw new Error(`metar http ${res.status}`);
  }
  const text = await res.text();
  if (!text.trim()) return [];
  const body = JSON.parse(text);
  if (!Array.isArray(body)) throw new Error("metar: response is not an array");
  return body.filter((m): m is MetarJson => !!m && typeof m === "object" && typeof m.icaoId === "string");
}

/**
 * One refresh: targets → batches of 30 ICAO codes → normalise → one upsert. A failed batch is logged and
 * skipped (its airports keep their previous observation); the newest report per station wins.
 */
export async function refreshAirportWeather(deps: RefreshDeps): Promise<RefreshStats> {
  const targets = dedupeTargets(await deps.store.targets());
  const byIcao = new Map(targets.map((t) => [t.icao, t.iata]));
  const stats: RefreshStats = { targets: targets.length, batches: 0, failedBatches: 0, reports: 0, upserted: 0 };
  const latest = new Map<string, AirportWeatherRow>();
  const fetchedAt = deps.now();

  const batches = chunk([...byIcao.keys()], BATCH_SIZE);
  const results = await Promise.allSettled(batches.map((b) => fetchMetars(deps, b)));
  results.forEach((r, i) => {
    stats.batches++;
    if (r.status === "rejected") {
      stats.failedBatches++;
      deps.log?.("metar batch failed", { batch: i, size: batches[i].length, error: String(r.reason) });
      return;
    }
    for (const m of r.value) {
      const iata = byIcao.get(m.icaoId.toUpperCase());
      if (!iata) continue; // a station we did not ask for
      const row = normaliseMetar(m, iata, fetchedAt);
      if (!row) continue;
      stats.reports++;
      const prev = latest.get(iata);
      if (!prev || Date.parse(row.observed_at) > Date.parse(prev.observed_at)) latest.set(iata, row);
    }
  });
  if (latest.size) stats.upserted = await deps.store.upsert([...latest.values()]);
  return stats;
}

function dedupeTargets(ts: WeatherTarget[]): WeatherTarget[] {
  const seen = new Set<string>();
  const out: WeatherTarget[] = [];
  for (const t of ts) {
    const icao = (t.icao ?? "").trim().toUpperCase();
    const iata = (t.iata ?? "").trim().toUpperCase();
    if (!/^[A-Z0-9]{4}$/.test(icao) || !/^[A-Z0-9]{3}$/.test(iata) || seen.has(icao)) continue;
    seen.add(icao);
    out.push({ iata, icao });
  }
  return out;
}
