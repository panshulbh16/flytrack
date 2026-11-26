// Public "track my flight" link (docs/P1_CONTRACTS.md section 2).
// GET /functions/v1/shared-flight?t=<token> → public fields only. No user, PNR, seat, device or
// internal scheduler data ever leaves here: the response is rebuilt field by field from a whitelist.

/** Tokens are 43-char base64url today; accept 22..128 so old/new formats keep working. */
export const TOKEN_RE = /^[A-Za-z0-9_-]{22,128}$/;
/** A live position older than this is not drawn as "the plane is here". */
export const LIVE_MAX_AGE_MIN = 30;

export interface PublicAirport {
  iata: string;
  name: string | null;
  city: string | null;
  lat: number | null;
  lon: number | null;
  tz: string | null;
  terminal: string | null;
  gate: string | null;
}

export interface PublicFlight {
  carrier: string;
  number: string;
  date: string;
  airline: string | null;
  status: string;
  sched_dep: string | null;
  est_dep: string | null;
  act_dep: string | null;
  sched_arr: string | null;
  est_arr: string | null;
  act_arr: string | null;
  dep: PublicAirport;
  arr: PublicAirport & { belt: string | null };
  aircraft: { model: string | null };
  live: { lat: number; lon: number; alt_ft: number | null; speed_kts: number | null; heading: number | null; at: string } | null;
  updated_at: string | null;
}

const str = (v: unknown): string | null => (typeof v === "string" && v !== "" ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" ? v as Record<string, unknown> : {});

function airport(v: unknown): PublicAirport {
  const a = obj(v);
  return {
    iata: str(a.iata) ?? "", name: str(a.name), city: str(a.city), lat: num(a.lat), lon: num(a.lon), tz: str(a.tz),
    terminal: str(a.terminal), gate: str(a.gate),
  };
}

const ON_GROUND = new Set(["landed", "arrived", "cancelled"]);

/** Rebuilds get_shared_flight()'s JSON from a whitelist (anything extra in the record is dropped). */
export function toPublicFlight(raw: Record<string, unknown>, now: Date): PublicFlight {
  const live = obj(raw.live);
  const liveAt = str(live.at);
  const lat = num(live.lat), lon = num(live.lon);
  const fresh = liveAt != null && now.getTime() - Date.parse(liveAt) <= LIVE_MAX_AGE_MIN * 60_000;
  const status = str(raw.status) ?? "unknown";
  return {
    carrier: str(raw.carrier) ?? "",
    number: str(raw.number) ?? "",
    date: str(raw.date) ?? "",
    airline: str(raw.airline),
    status,
    sched_dep: str(raw.sched_dep), est_dep: str(raw.est_dep), act_dep: str(raw.act_dep),
    sched_arr: str(raw.sched_arr), est_arr: str(raw.est_arr), act_arr: str(raw.act_arr),
    dep: airport(raw.dep),
    arr: { ...airport(raw.arr), belt: str(obj(raw.arr).belt) },
    aircraft: { model: str(obj(raw.aircraft).model) },
    live: lat != null && lon != null && fresh && !ON_GROUND.has(status)
      ? { lat, lon, alt_ft: num(live.alt_ft), speed_kts: num(live.speed_kts), heading: num(live.heading), at: liveAt! }
      : null,
    updated_at: str(raw.updated_at),
  };
}

const HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "GET, OPTIONS",
  "cache-control": "no-store", // a revoked link must stop working at once
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

function reply(body: unknown, status: number, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...HEADERS, "content-type": "application/json", ...extra } });
}

export interface ShareDeps {
  /** get_shared_flight(token): the public record, or null for unknown / revoked / expired. */
  lookup(token: string): Promise<Record<string, unknown> | null>;
  now: () => Date;
  log?: (msg: string, extra?: Record<string, unknown>) => void;
}

export async function handleSharedFlight(req: Request, deps: ShareDeps): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: HEADERS });
  if (req.method !== "GET") return reply({ error: "method_not_allowed" }, 405, { allow: "GET, OPTIONS" });
  const token = new URL(req.url).searchParams.get("t") ?? "";
  // Same answer for malformed, unknown, revoked and expired: nothing to learn by probing.
  if (!TOKEN_RE.test(token)) return reply({ error: "not_found" }, 404);
  let raw: Record<string, unknown> | null;
  try {
    raw = await deps.lookup(token);
  } catch (e) {
    deps.log?.("shared-flight lookup failed", { error: String(e) }); // never log the token
    return reply({ error: "unavailable" }, 503, { "retry-after": "30" });
  }
  if (!raw) return reply({ error: "not_found" }, 404);
  return reply({ flight: toPublicFlight(raw, deps.now()) }, 200);
}
