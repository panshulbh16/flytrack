// Live aircraft positions for radar (docs/P4_CONTRACTS.md section 1).
// One interface, three adapters chosen by the secret RADAR_FEED: none (default), opensky, adsbx.
// Any real feed also needs RADAR_FEED_LICENSED=true, so a free non-commercial feed is never used in the
// paid app by accident. All network access goes through an injected fetch (tests use recorded fixtures).

export interface AircraftPosition {
  /** 24-bit ICAO transponder address, lower-case hex. */
  icao24: string;
  /** Trimmed ATC callsign ("IGO2341"), or "" when the aircraft sends none. */
  callsign: string;
  reg?: string;
  lat: number;
  lon: number;
  /** Barometric altitude in feet; 0 on the ground. */
  alt_ft: number;
  gs_kts: number;
  track_deg: number;
  on_ground: boolean;
  vrate_fpm?: number;
  /** ISO-8601 UTC of the last position. */
  seen_at: string;
}

export interface PositionFeed {
  readonly name: string;
  nearby(lat: number, lon: number, radiusKm: number): Promise<AircraftPosition[]>;
}

export type FeedErrorCode = "feed_unavailable" | "feed_unlicensed" | "feed_error";

export class FeedError extends Error {
  constructor(public readonly code: FeedErrorCode, msg: string, public readonly status?: number) {
    super(msg);
  }
}

export type FetchFn = typeof fetch;
export type EnvGet = (name: string) => string | undefined;

const M_TO_FT = 3.28084;
const MS_TO_KTS = 1.943844;
const MS_TO_FPM = 196.850394;
const KM_PER_NM = 1.852;
const EARTH_KM = 6371.0088;
const TIMEOUT_MS = 8_000;

// ---------- geometry -----------------------------------------------------------------------------

export function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = Math.PI / 180;
  const dLat = (lat2 - lat1) * r, dLon = (lon2 - lon1) * r;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(a)));
}

export interface BBox {
  lamin: number;
  lomin: number;
  lamax: number;
  lomax: number;
}

/** A box that contains the circle (lat, lon, radiusKm). Clamped to valid latitudes. */
export function boundingBox(lat: number, lon: number, radiusKm: number): BBox {
  // 110 km per degree is a little under the spherical 111.2, so the box always covers the circle.
  const dLat = radiusKm / 110;
  const cos = Math.max(0.01, Math.cos(Math.min(89.9, Math.abs(lat) + dLat) * Math.PI / 180));
  const dLon = Math.min(180, radiusKm / (110 * cos));
  const round = (v: number) => Math.round(v * 10_000) / 10_000;
  return {
    lamin: round(Math.max(-90, lat - dLat)),
    lamax: round(Math.min(90, lat + dLat)),
    lomin: round(Math.max(-180, lon - dLon)),
    lomax: round(Math.min(180, lon + dLon)),
  };
}

/** Rounds to the nearest 0.05° (contract: privacy + cache key). Returns a clean decimal. */
export function roundToCell(v: number, step = 0.05): number {
  return Math.round(Math.round(v / step) * step * 100) / 100;
}

function finite(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

function withinRadius(list: AircraftPosition[], lat: number, lon: number, radiusKm: number): AircraftPosition[] {
  return list.filter((a) => haversineKm(lat, lon, a.lat, a.lon) <= radiusKm);
}

async function readJson(res: Response, feed: string): Promise<unknown> {
  if (!res.ok) {
    await res.body?.cancel();
    throw new FeedError("feed_error", `${feed} HTTP ${res.status}`, res.status);
  }
  try {
    return await res.json();
  } catch {
    throw new FeedError("feed_error", `${feed}: bad JSON`);
  }
}

// ---------- none ---------------------------------------------------------------------------------

export const noneFeed: PositionFeed = {
  name: "none",
  nearby() {
    return Promise.reject(new FeedError("feed_unavailable", "no radar feed configured"));
  },
};

// ---------- OpenSky ------------------------------------------------------------------------------
// GET /api/states/all?lamin=..&lomin=..&lamax=..&lomax=.. with a Bearer token from the OAuth2
// client-credentials flow. `states` rows are positional arrays:
// 0 icao24, 1 callsign, 2 origin_country, 3 time_position, 4 last_contact, 5 longitude, 6 latitude,
// 7 baro_altitude (m), 8 on_ground, 9 velocity (m/s), 10 true_track, 11 vertical_rate (m/s), 12 sensors,
// 13 geo_altitude (m), 14 squawk, 15 spi, 16 position_source, 17 category.

export const OPENSKY_API_BASE = "https://opensky-network.org/api";
export const OPENSKY_TOKEN_URL =
  "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";

export interface OpenSkyOptions {
  clientId: string;
  clientSecret: string;
  fetch?: FetchFn;
  now?: () => number;
  baseUrl?: string;
  tokenUrl?: string;
}

export function parseOpenSkyStates(body: unknown): AircraftPosition[] {
  const b = body as { time?: number; states?: unknown[] | null } | null;
  const out: AircraftPosition[] = [];
  for (const row of b?.states ?? []) {
    if (!Array.isArray(row)) continue;
    const lon = row[5], lat = row[6];
    if (!finite(lat) || !finite(lon) || typeof row[0] !== "string") continue;
    const onGround = row[8] === true;
    const seen = finite(row[3]) ? row[3] : finite(row[4]) ? row[4] : finite(b?.time) ? b!.time! : null;
    out.push({
      icao24: row[0].trim().toLowerCase(),
      callsign: typeof row[1] === "string" ? row[1].trim().toUpperCase() : "",
      lat,
      lon,
      alt_ft: onGround ? 0 : Math.round((finite(row[7]) ? row[7] : finite(row[13]) ? row[13] : 0) * M_TO_FT),
      gs_kts: finite(row[9]) ? Math.round(row[9] * MS_TO_KTS) : 0,
      track_deg: finite(row[10]) ? Math.round(row[10]) % 360 : 0,
      on_ground: onGround,
      ...(finite(row[11]) ? { vrate_fpm: Math.round(row[11] * MS_TO_FPM) } : {}),
      seen_at: seen === null ? new Date(0).toISOString() : new Date(seen * 1000).toISOString(),
    });
  }
  return out;
}

export function openSkyFeed(opts: OpenSkyOptions): PositionFeed {
  const f = opts.fetch ?? fetch;
  const now = opts.now ?? Date.now;
  const base = opts.baseUrl ?? OPENSKY_API_BASE;
  let token: { value: string; until: number } | null = null;

  async function accessToken(): Promise<string> {
    if (token && token.until > now()) return token.value;
    const res = await f(opts.tokenUrl ?? OPENSKY_TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: opts.clientId,
        client_secret: opts.clientSecret,
      }).toString(),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const body = await readJson(res, "opensky token") as { access_token?: string; expires_in?: number };
    if (!body?.access_token) throw new FeedError("feed_error", "opensky token: no access_token");
    // refresh a minute early
    const ttl = Math.max(60, finite(body.expires_in) ? body.expires_in : 1800) - 60;
    token = { value: body.access_token, until: now() + ttl * 1000 };
    return token.value;
  }

  return {
    name: "opensky",
    async nearby(lat, lon, radiusKm) {
      const bb = boundingBox(lat, lon, radiusKm);
      const qs = new URLSearchParams({
        lamin: String(bb.lamin),
        lomin: String(bb.lomin),
        lamax: String(bb.lamax),
        lomax: String(bb.lomax),
      });
      const call = async () =>
        await f(`${base}/states/all?${qs}`, {
          headers: { authorization: `Bearer ${await accessToken()}`, accept: "application/json" },
          signal: AbortSignal.timeout(TIMEOUT_MS),
        });
      let res = await call();
      if (res.status === 401) { // token revoked early: one retry with a fresh one
        await res.body?.cancel();
        token = null;
        res = await call();
      }
      return withinRadius(parseOpenSkyStates(await readJson(res, "opensky")), lat, lon, radiusKm);
    },
  };
}

// ---------- ADS-B Exchange -----------------------------------------------------------------------
// GET {base}/lat/{lat}/lon/{lon}/dist/{nm}/ with header api-auth. Response { ac: [...], now (ms) }.
// alt_baro is a number in feet or the string "ground"; gs knots; baro_rate ft/min; seen_pos seconds ago.

export const ADSBX_API_BASE = "https://adsbexchange.com/api/aircraft/v2";
/** The v2 point query's maximum radius. */
export const ADSBX_MAX_NM = 250;

export interface AdsbxOptions {
  apiKey: string;
  fetch?: FetchFn;
  baseUrl?: string;
  now?: () => number;
}

export function parseAdsbx(body: unknown, nowMs: number): AircraftPosition[] {
  const b = body as { ac?: unknown[] | null; now?: number } | null;
  const ref = finite(b?.now) ? b!.now! : nowMs;
  const out: AircraftPosition[] = [];
  for (const raw of b?.ac ?? []) {
    const a = raw as Record<string, unknown>;
    if (!a || typeof a.hex !== "string" || !finite(a.lat) || !finite(a.lon)) continue;
    const onGround = a.alt_baro === "ground";
    const alt = onGround ? 0 : finite(a.alt_baro) ? a.alt_baro : finite(a.alt_geom) ? a.alt_geom : 0;
    const ago = finite(a.seen_pos) ? a.seen_pos : finite(a.seen) ? a.seen : 0;
    const vrate = finite(a.baro_rate) ? a.baro_rate : finite(a.geom_rate) ? a.geom_rate : undefined;
    out.push({
      icao24: a.hex.replace(/^~/, "").trim().toLowerCase(),
      callsign: typeof a.flight === "string" ? a.flight.trim().toUpperCase() : "",
      ...(typeof a.r === "string" && a.r.trim() ? { reg: a.r.trim().toUpperCase() } : {}),
      lat: a.lat,
      lon: a.lon,
      alt_ft: Math.round(alt),
      gs_kts: finite(a.gs) ? Math.round(a.gs) : 0,
      track_deg: finite(a.track)
        ? Math.round(a.track) % 360
        : finite(a.true_heading)
        ? Math.round(a.true_heading) % 360
        : 0,
      on_ground: onGround,
      ...(vrate !== undefined ? { vrate_fpm: Math.round(vrate) } : {}),
      seen_at: new Date(ref - ago * 1000).toISOString(),
    });
  }
  return out;
}

export function adsbxFeed(opts: AdsbxOptions): PositionFeed {
  const f = opts.fetch ?? fetch;
  const base = (opts.baseUrl ?? ADSBX_API_BASE).replace(/\/+$/, "");
  const now = opts.now ?? Date.now;
  return {
    name: "adsbx",
    async nearby(lat, lon, radiusKm) {
      const nm = Math.min(ADSBX_MAX_NM, Math.max(1, Math.ceil(radiusKm / KM_PER_NM)));
      const res = await f(`${base}/lat/${lat.toFixed(4)}/lon/${lon.toFixed(4)}/dist/${nm}/`, {
        headers: { "api-auth": opts.apiKey, accept: "application/json" },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      return withinRadius(parseAdsbx(await readJson(res, "adsbx"), now()), lat, lon, radiusKm);
    },
  };
}

// ---------- selection ----------------------------------------------------------------------------

export type FeedChoice = { ok: true; feed: PositionFeed } | {
  ok: false;
  error: "feed_unavailable" | "feed_unlicensed";
};

/**
 * RADAR_FEED: none (default) | opensky | adsbx. Unknown values and missing credentials are
 * `feed_unavailable`; a real feed without RADAR_FEED_LICENSED=true is `feed_unlicensed`.
 */
export function feedFromEnv(env: EnvGet, fetchFn: FetchFn = fetch): FeedChoice {
  const kind = (env("RADAR_FEED") ?? "none").trim().toLowerCase() || "none";
  if (kind === "none") return { ok: false, error: "feed_unavailable" };
  if (kind !== "opensky" && kind !== "adsbx") return { ok: false, error: "feed_unavailable" };
  if ((env("RADAR_FEED_LICENSED") ?? "").trim().toLowerCase() !== "true") {
    return { ok: false, error: "feed_unlicensed" };
  }
  if (kind === "opensky") {
    const clientId = env("OPENSKY_CLIENT_ID")?.trim(), clientSecret = env("OPENSKY_CLIENT_SECRET")?.trim();
    if (!clientId || !clientSecret) return { ok: false, error: "feed_unavailable" };
    return { ok: true, feed: openSkyFeed({ clientId, clientSecret, fetch: fetchFn }) };
  }
  const apiKey = env("ADSBX_API_KEY")?.trim();
  if (!apiKey) return { ok: false, error: "feed_unavailable" };
  return {
    ok: true,
    feed: adsbxFeed({ apiKey, fetch: fetchFn, baseUrl: env("ADSBX_BASE_URL")?.trim() || undefined }),
  };
}
