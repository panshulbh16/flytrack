import type { FlightKey, FlightSnapshot, FlightStatus } from "../types.ts";
import { type FlightDataProvider, ProviderError, type ProviderCallInfo, type WebhookSubscription } from "./provider.ts";

/**
 * AeroDataBox adapter (RapidAPI or API.Market).
 *
 * Env / secrets (never hardcode):
 *   ADB_API_KEY        required
 *   ADB_BASE_URL       default https://aerodatabox.p.rapidapi.com  (API.Market: its gateway URL)
 *   ADB_KEY_HEADER     default X-RapidAPI-Key                       (API.Market: check their dashboard)
 *   ADB_HOST_HEADER    default aerodatabox.p.rapidapi.com (sent as X-RapidAPI-Host; set "" to omit)
 *   ADB_WEBHOOKS       "true" to enable Flight Alert webhooks (credit-billed, best-effort delivery)
 *
 * Verified from the OpenAPI spec mirror (Nov 2026): GET /flights/{searchBy}/{param}/{dateLocal}
 * with searchBy = number | reg | callsign | icao24 | id; movement fields scheduledTime / revisedTime /
 * predictedTime / runwayTime ({utc, local}); there is NO actualTime; status "Canceled" (one L);
 * webhook body = { flights[], subscription, balance }.
 * UNVERIFIED: exact webhook subject enum ("FlightByNumber"), units per call — measure on the free tier.
 */

type AdbTime = { utc?: string; local?: string } | undefined;
interface AdbMovement {
  airport?: { iata?: string; icao?: string; name?: string; timeZone?: string };
  scheduledTime?: AdbTime;
  revisedTime?: AdbTime;
  predictedTime?: AdbTime;
  runwayTime?: AdbTime;
  terminal?: string;
  gate?: string;
  checkInDesk?: string;
  baggageBelt?: string;
  quality?: string[];
}
export interface AdbFlight {
  number?: string;
  callSign?: string;
  status?: string;
  codeshareStatus?: string;
  isCargo?: boolean;
  lastUpdatedUtc?: string;
  departure?: AdbMovement;
  arrival?: AdbMovement;
  aircraft?: { reg?: string; modeS?: string; model?: string };
  airline?: { name?: string; iata?: string; icao?: string };
  location?: {
    lat?: number; lon?: number; reportedAtUtc?: string;
    altitude?: { feet?: number }; pressureAltitude?: { feet?: number };
    groundSpeed?: { kt?: number }; trueTrack?: { deg?: number };
  };
}

/** "2026-11-25 13:20Z" / "2026-11-25T13:20:00Z" → "2026-11-25T13:20:00Z" */
export function adbUtc(t: AdbTime): string | null {
  const v = t?.utc;
  if (!v) return null;
  const ms = Date.parse(v.trim().replace(" ", "T"));
  return Number.isNaN(ms) ? null : new Date(ms).toISOString().replace(".000Z", "Z");
}

const STATUS: Record<string, FlightStatus> = {
  Unknown: "unknown", Expected: "scheduled", Delayed: "scheduled", CheckIn: "check_in",
  Boarding: "boarding", GateClosed: "gate_closed", Departed: "departed", EnRoute: "en_route",
  Approaching: "approaching", Arrived: "arrived", Canceled: "cancelled", Diverted: "diverted",
  // "CanceledUncertain": we do NOT push "cancelled" on a guess — correctness over speed.
  CanceledUncertain: "unknown",
};

const DEPARTED_STATES = new Set(["Departed", "EnRoute", "Approaching", "Arrived", "Diverted"]);
const blank = (s?: string) => (s && s.trim() ? s.trim() : null);

export function mapAdbFlight(f: AdbFlight): FlightSnapshot | null {
  const dep = f.departure ?? {};
  const arr = f.arrival ?? {};
  const depIata = dep.airport?.iata;
  const arrIata = arr.airport?.iata;
  const schedDep = adbUtc(dep.scheduledTime);
  const localDate = dep.scheduledTime?.local?.slice(0, 10);
  if (!depIata || !arrIata || !localDate) return null;

  const rawNo = (f.number ?? "").replace(/\s+/g, "");
  const carrier = (f.airline?.iata ?? rawNo.slice(0, 2)).toUpperCase();
  const numberPart = rawNo.toUpperCase().startsWith(carrier) ? rawNo.slice(carrier.length) : rawNo.slice(2);
  const m = /^0*(\d{1,4})([A-Z]?)$/.exec(numberPart.toUpperCase());
  if (!m) return null;

  const status: FlightStatus = STATUS[f.status ?? "Unknown"] ?? "unknown";
  const departed = DEPARTED_STATES.has(f.status ?? "");
  const depRevised = adbUtc(dep.revisedTime);
  const depRunway = adbUtc(dep.runwayTime);
  const arrRevised = adbUtc(arr.revisedTime);
  const arrRunway = adbUtc(arr.runwayTime);
  const arrived = f.status === "Arrived";

  // Actual departure: runway (takeoff) if present, else revised time once status says departed.
  const actDep = depRunway ?? (departed ? depRevised : null);
  const actArr = arrRunway ?? (arrived ? arrRevised : null);

  let finalStatus = status;
  if (arrRunway && status !== "arrived" && status !== "diverted") finalStatus = "landed";

  const loc = f.location;
  return {
    carrier, number: `${Number(m[1])}${m[2]}`, date: localDate, depIata, arrIata,
    carrierIcao: f.airline?.icao ?? null, airlineName: f.airline?.name ?? null,
    status: finalStatus,
    schedDep,
    estDep: departed ? null : depRevised, // airline-revised only; predictedTime is ADB's ML guess
    actDep,
    depTerminal: blank(dep.terminal), depGate: blank(dep.gate), depCheckin: blank(dep.checkInDesk),
    schedArr: adbUtc(arr.scheduledTime),
    estArr: actArr ? null : (arrRevised ?? adbUtc(arr.predictedTime)),
    actArr,
    arrTerminal: blank(arr.terminal), arrGate: blank(arr.gate), baggageBelt: blank(arr.baggageBelt),
    aircraftModel: blank(f.aircraft?.model), aircraftReg: blank(f.aircraft?.reg),
    delayReason: null, // AeroDataBox doesn't provide reasons; we derive (inbound late) ourselves
    live: loc?.lat != null && loc?.lon != null
      ? {
        lat: loc.lat, lon: loc.lon,
        altFt: loc.altitude?.feet ?? loc.pressureAltitude?.feet ?? null,
        headingDeg: loc.trueTrack?.deg ?? null,
        speedKts: loc.groundSpeed?.kt ?? null,
        at: adbUtc({ utc: loc.reportedAtUtc }) ?? new Date().toISOString(),
      }
      : null,
    providerRef: f.callSign ?? null,
    providerUpdatedAt: adbUtc({ utc: f.lastUpdatedUtc }),
    raw: f,
  };
}

/** Prefer the operating carrier's record; drop codeshare duplicates of the same leg. */
export function dedupeCodeshares(flights: AdbFlight[]): AdbFlight[] {
  const operators = flights.filter((f) => f.codeshareStatus !== "IsCodeshared");
  return operators.length ? operators : flights;
}

export class AeroDataBoxProvider implements FlightDataProvider {
  readonly name = "aerodatabox";
  readonly supportsWebhooks: boolean;
  onCall?: (info: ProviderCallInfo) => void;

  constructor(
    private readonly cfg: { apiKey: string; baseUrl: string; keyHeader: string; hostHeader: string | null; webhooks: boolean },
    private readonly fetchImpl: typeof fetch = fetch,
  ) {
    this.supportsWebhooks = cfg.webhooks;
  }

  static fromEnv(): AeroDataBoxProvider {
    const apiKey = Deno.env.get("ADB_API_KEY");
    if (!apiKey) throw new Error("ADB_API_KEY secret is not set");
    const host = Deno.env.get("ADB_HOST_HEADER");
    return new AeroDataBoxProvider({
      apiKey,
      baseUrl: (Deno.env.get("ADB_BASE_URL") ?? "https://aerodatabox.p.rapidapi.com").replace(/\/$/, ""),
      keyHeader: Deno.env.get("ADB_KEY_HEADER") ?? "X-RapidAPI-Key",
      hostHeader: host === "" ? null : host ?? "aerodatabox.p.rapidapi.com",
      webhooks: Deno.env.get("ADB_WEBHOOKS") === "true",
    });
  }

  private async request(endpoint: string, path: string, init: RequestInit = {}): Promise<unknown | null> {
    const headers: Record<string, string> = { [this.cfg.keyHeader]: this.cfg.apiKey, accept: "application/json" };
    if (this.cfg.hostHeader) headers["X-RapidAPI-Host"] = this.cfg.hostHeader;
    if (init.body) headers["content-type"] = "application/json";
    const t0 = Date.now();
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.cfg.baseUrl}${path}`, { ...init, headers, signal: AbortSignal.timeout(12_000) });
    } catch (e) {
      this.onCall?.({ endpoint, status: null, units: 0, durationMs: Date.now() - t0 });
      throw new ProviderError(`network: ${String(e)}`, null, true);
    }
    this.onCall?.({ endpoint, status: res.status, units: res.ok ? 1 : 0, durationMs: Date.now() - t0 });
    if (res.status === 204 || res.status === 404) return null; // no such flight
    if (res.status === 429) throw new ProviderError("rate limited / quota", 429, true);
    if (res.status === 401 || res.status === 403) throw new ProviderError("auth failed — check ADB_API_KEY", res.status, false);
    if (!res.ok) throw new ProviderError(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`, res.status, res.status >= 500);
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }

  private toSnapshots(body: unknown): FlightSnapshot[] {
    const arr = Array.isArray(body) ? body as AdbFlight[] : [];
    return dedupeCodeshares(arr).filter((f) => !f.isCargo).map(mapAdbFlight).filter((s): s is FlightSnapshot => !!s);
  }

  async getFlight(key: FlightKey): Promise<FlightSnapshot[]> {
    const q = "withAircraftImage=false&withLocation=true&dateLocalRole=Departure";
    const body = await this.request(
      "flight_by_number",
      `/flights/number/${encodeURIComponent(key.carrier + key.number)}/${key.date}?${q}`,
    );
    const snaps = this.toSnapshots(body);
    return key.depIata ? snaps.filter((s) => s.depIata === key.depIata) : snaps;
  }

  async getFlightsByRegistration(reg: string, date: string): Promise<FlightSnapshot[]> {
    const body = await this.request(
      "flight_by_registration",
      `/flights/reg/${encodeURIComponent(reg)}/${date}?withAircraftImage=false&withLocation=true&dateLocalRole=Both`,
    );
    return this.toSnapshots(body);
  }

  async subscribe(key: FlightKey, callbackUrl: string): Promise<WebhookSubscription> {
    // UNVERIFIED subject type name; confirm in AeroDataBox "Flight Alert API" docs before enabling.
    const body = await this.request(
      "webhook_subscribe",
      `/subscriptions/webhook/FlightByNumber/${encodeURIComponent(key.carrier + key.number)}`,
      { method: "POST", body: JSON.stringify({ url: callbackUrl }) },
    ) as { id?: string; expiresOnUtc?: string } | null;
    if (!body?.id) throw new ProviderError("subscribe returned no id", null, false);
    return { id: body.id, expiresAt: body.expiresOnUtc ? adbUtc({ utc: body.expiresOnUtc }) : null };
  }

  async unsubscribe(id: string): Promise<void> {
    await this.request("webhook_unsubscribe", `/subscriptions/webhook/${encodeURIComponent(id)}`, { method: "DELETE" });
  }

  parseWebhook(body: unknown): FlightSnapshot[] {
    const flights = (body as { flights?: AdbFlight[] })?.flights;
    return this.toSnapshots(flights ?? []);
  }
}
