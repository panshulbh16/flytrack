import type { FlightKey, FlightSnapshot, FlightStatus, LivePosition } from "../types.ts";
import { type AircraftInfo, type FlightDataProvider, ProviderError, type ProviderCallInfo } from "./provider.ts";

/**
 * Deterministic, clock-driven mock provider. The same flight evolves realistically as `now()` moves.
 *
 * Flight numbers 1–3999 = "your" flights; scenario picked by the LAST DIGIT:
 *   0,1,2 on time · 3 delayed 45 → 70 min · 4 delayed 60 → reduced to 25 · 5 gate change
 *   6 cancelled at T-150m · 7 inbound aircraft late (Pro alert) · 8 belt change · 9 diverted
 * Numbers 5000–8999 = the inbound leg flown by the same aircraft before flight (n-5000).
 * Numbers >= 9000 or 4000–4999 = "not found".
 * Tail number of flight n (and its inbound leg) = "VT-M" + n padded to 4 digits.
 * Block times: pushback 2 min after the departure estimate, takeoff MOCK_TAXI_OUT(n) min later, touchdown at
 * the arrival estimate, on-block MOCK_TAXI_IN min after touchdown.
 * Airport departures: own flights 1..MOCK_DEPARTURE_RANGE whose route leaves that airport, carrier by n.
 * Every registration resolves to MOCK_AIRCRAFT.
 */

/** Taxi-out minutes of mock flight n: 8, 11 or 14. */
export const MOCK_TAXI_OUT = (n: number) => 8 + (n % 3) * 3;
export const MOCK_TAXI_IN = 8;
export const MOCK_DEPARTURE_RANGE = 240;
export const MOCK_CARRIERS = ["6E", "AI", "QP", "SG", "IX"] as const;
/** The fixed aircraft the mock returns for any tail (age is computed against the mock clock). */
export const MOCK_AIRCRAFT: Omit<AircraftInfo, "reg" | "ageYears"> = {
  model: "Airbus A320neo",
  icaoType: "A20N",
  firstFlightDate: "2018-03-14",
  deliveryDate: "2018-04-20",
  airlineIata: "6E",
  seatConfig: "186 seats",
};

interface Route {
  dep: string;
  arr: string;
  durMin: number;
  depTerm: string;
  arrTerm: string;
  alt: string; // diversion airport
  depLL: [number, number];
  arrLL: [number, number];
  altLL: [number, number];
}

const LL: Record<string, [number, number]> = {
  DEL: [28.5562, 77.1], BOM: [19.0896, 72.8656], BLR: [13.1986, 77.7066],
  HYD: [17.2403, 78.4294], MAA: [12.9941, 80.1709], CCU: [22.6547, 88.4467],
  GOI: [15.3808, 73.8314], GOX: [15.7300, 73.8600], AMD: [23.0772, 72.6347],
  DXB: [25.2532, 55.3657], PNQ: [18.5821, 73.9197], JAI: [26.8242, 75.8122],
};

const ROUTES: Array<Omit<Route, "depLL" | "arrLL" | "altLL">> = [
  { dep: "DEL", arr: "BOM", durMin: 130, depTerm: "1", arrTerm: "2", alt: "AMD" },
  { dep: "BLR", arr: "DEL", durMin: 165, depTerm: "1", arrTerm: "1", alt: "JAI" },
  { dep: "BOM", arr: "GOX", durMin: 75, depTerm: "1", arrTerm: "1", alt: "PNQ" },
  { dep: "HYD", arr: "MAA", durMin: 70, depTerm: "1", arrTerm: "1", alt: "BLR" },
  { dep: "DEL", arr: "DXB", durMin: 225, depTerm: "3", arrTerm: "1", alt: "AMD" },
  { dep: "CCU", arr: "BLR", durMin: 150, depTerm: "1", arrTerm: "1", alt: "HYD" },
];

const IST_OFFSET_MIN = 330;
const MIN = 60_000;

function route(n: number): Route {
  const r = ROUTES[n % ROUTES.length];
  return { ...r, depLL: LL[r.dep], arrLL: LL[r.arr], altLL: LL[r.alt] };
}

function iso(ms: number | null): string | null {
  return ms == null ? null : new Date(Math.round(ms / 1000) * 1000).toISOString().replace(".000Z", "Z");
}

/** Scheduled departure (UTC ms) for own flight n on local date. */
function schedDepMs(n: number, date: string): number {
  const [y, m, d] = date.split("-").map(Number);
  const hour = 6 + ((n * 7) % 15); // 06..20 local
  const minute = ((n * 13) % 4) * 15;
  return Date.UTC(y, m - 1, d, hour, minute) - IST_OFFSET_MIN * MIN;
}

function localDate(ms: number): string {
  return new Date(ms + IST_OFFSET_MIN * MIN).toISOString().slice(0, 10);
}

interface LegPlan {
  carrier: string;
  number: string;
  date: string;
  r: Route;
  reversed: boolean; // inbound leg flies arr -> dep of the own route
  S: number; // scheduled departure UTC ms
  reg: string;
  delayAt: (now: number) => number; // departure delay (min) as known at `now`
  cancelAt?: number;
  divertFraction?: number;
  gateAt: (now: number) => string | null;
  beltAt: (now: number, actArr: number) => string | null;
}

function ownPlan(carrier: string, n: number, date: string): LegPlan {
  const r = route(n);
  const S = schedDepMs(n, date);
  const sc = n % 10;
  const gateNum = 10 + (n % 40);
  const plan: LegPlan = {
    carrier, number: String(n), date, r, reversed: false, S,
    reg: `VT-M${String(n).padStart(4, "0")}`,
    delayAt: () => 0,
    gateAt: (now) => (now >= S - 120 * MIN ? `${gateNum}` : null),
    beltAt: (now, actArr) => (now >= actArr + 10 * MIN ? `${(n % 9) + 1}` : null),
  };
  switch (sc) {
    case 3:
      plan.delayAt = (now) => (now >= S - 60 * MIN ? 70 : now >= S - 180 * MIN ? 45 : 0);
      break;
    case 4:
      plan.delayAt = (now) => (now >= S - 90 * MIN ? 25 : now >= S - 240 * MIN ? 60 : 0);
      break;
    case 5:
      plan.gateAt = (now) => (now >= S - 50 * MIN ? `${gateNum + 17}` : now >= S - 120 * MIN ? `${gateNum}` : null);
      break;
    case 6:
      plan.cancelAt = S - 150 * MIN;
      break;
    case 7:
      plan.delayAt = (now) => (now >= S - 60 * MIN ? 35 : 0);
      break;
    case 8:
      plan.beltAt = (now, actArr) =>
        now >= actArr + 22 * MIN ? `${(n % 9) + 2}` : now >= actArr + 10 * MIN ? `${(n % 9) + 1}` : null;
      break;
    case 9:
      plan.divertFraction = 0.6;
      break;
  }
  return plan;
}

function inboundPlan(carrier: string, ownN: number, ownDate: string): LegPlan {
  const own = ownPlan(carrier, ownN, ownDate);
  const r = own.r;
  const schedArr = own.S - 55 * MIN; // lands 55 min before our scheduled departure
  const S = schedArr - r.durMin * MIN;
  const late = ownN % 10 === 7;
  return {
    carrier, number: String(ownN + 5000), date: localDate(S), r, reversed: true, S, reg: own.reg,
    delayAt: (now) => (late && now >= own.S - 200 * MIN ? 40 : 0),
    gateAt: (now) => (now >= S - 120 * MIN ? `${20 + (ownN % 30)}` : null),
    beltAt: (now, actArr) => (now >= actArr + 10 * MIN ? `${(ownN % 5) + 1}` : null),
  };
}

function lerp(a: [number, number], b: [number, number], f: number): [number, number] {
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
}

function simulate(p: LegPlan, now: number): FlightSnapshot {
  const r = p.r;
  const depIata = p.reversed ? r.arr : r.dep;
  let arrIata = p.reversed ? r.dep : r.arr;
  const depLL = p.reversed ? r.arrLL : r.depLL;
  let arrLL = p.reversed ? r.depLL : r.arrLL;
  const S = p.S;
  const A = S + r.durMin * MIN;
  const known = now >= S - 30 * 60 * MIN; // tail/aircraft assignment visible ~30h before
  const delay = p.delayAt(now);
  const D = S + delay * MIN; // current departure estimate
  const offBlock = D + 2 * MIN;
  const takeoff = offBlock + MOCK_TAXI_OUT(Number.parseInt(p.number, 10) || 0) * MIN;
  let estArr = A + delay * MIN;

  const snap: FlightSnapshot = {
    carrier: p.carrier, number: p.number, date: p.date, depIata, arrIata,
    carrierIcao: null, airlineName: `Mock Air ${p.carrier}`,
    status: "scheduled",
    schedDep: iso(S), estDep: delay > 0 ? iso(D) : null, actDep: null,
    depTerminal: p.reversed ? r.arrTerm : r.depTerm, depGate: null,
    depCheckin: now >= S - 180 * MIN ? "Row C" : null,
    schedArr: iso(A), estArr: delay > 0 ? iso(estArr) : null, actArr: null,
    arrTerminal: p.reversed ? r.depTerm : r.arrTerm, arrGate: null, baggageBelt: null,
    aircraftModel: known ? "Airbus A320neo" : null, aircraftReg: known ? p.reg : null,
    depOffblockAt: null, depRunwayAt: null, arrRunwayAt: null, arrOnblockAt: null,
    delayReason: null, live: null,
    providerRef: `mock:${p.carrier}${p.number}:${p.date}`,
    providerUpdatedAt: iso(now),
  };

  if (p.cancelAt != null && now >= p.cancelAt) {
    return { ...snap, status: "cancelled", estDep: null, estArr: null };
  }

  snap.depGate = p.gateAt(now);
  let status: FlightStatus = "scheduled";
  if (now >= S - 180 * MIN) status = "check_in";
  if (now >= D - 40 * MIN) status = "boarding";
  if (now >= D - 15 * MIN) status = "gate_closed";
  if (now >= offBlock) {
    status = "departed";
    snap.actDep = iso(offBlock);
    snap.depOffblockAt = iso(offBlock);
  }
  if (now >= takeoff) snap.depRunwayAt = iso(takeoff);
  if (now >= offBlock + 15 * MIN) status = "en_route";

  // Diversion: decided mid-flight, new destination + new ETA
  if (p.divertFraction != null && now >= offBlock + r.durMin * MIN * p.divertFraction) {
    arrIata = r.alt;
    arrLL = r.altLL;
    snap.arrIata = arrIata;
    snap.arrTerminal = "1";
    estArr = offBlock + r.durMin * MIN * p.divertFraction + 40 * MIN;
    snap.estArr = iso(estArr);
    status = "diverted";
  }

  if (status !== "diverted" && now >= estArr - 25 * MIN && now >= offBlock) status = "approaching";
  if (now >= estArr && now >= offBlock) {
    snap.actArr = iso(estArr);
    snap.arrRunwayAt = iso(estArr);
    if (now >= estArr + MOCK_TAXI_IN * MIN) snap.arrOnblockAt = iso(estArr + MOCK_TAXI_IN * MIN);
    status = status === "diverted" ? "diverted" : "landed";
  }
  if (status === "landed" && now >= estArr + MOCK_TAXI_IN * MIN) status = "arrived";
  if (snap.actArr) {
    snap.arrGate = p.reversed ? null : "A4";
    snap.baggageBelt = p.beltAt(now, estArr);
  }

  if (snap.actDep && !snap.actArr) {
    const f = Math.min(1, Math.max(0, (now - offBlock) / (estArr - offBlock)));
    const [lat, lon] = lerp(depLL, arrLL, f);
    const altFt = f < 0.15 ? Math.round(f / 0.15 * 35000) : f > 0.85 ? Math.round((1 - f) / 0.15 * 35000) : 35000;
    snap.live = { lat, lon, altFt, headingDeg: null, speedKts: 450, at: iso(now)! } satisfies LivePosition;
  }

  snap.status = status;
  return snap;
}

export class MockProvider implements FlightDataProvider {
  readonly name = "mock";
  readonly supportsWebhooks = false;
  onCall?: (info: ProviderCallInfo) => void;
  calls = 0;

  constructor(private readonly now: () => Date = () => new Date()) {}

  private record(endpoint: string) {
    this.calls++;
    this.onCall?.({ endpoint, status: 200, units: 1, durationMs: 0 });
  }

  getFlight(key: FlightKey): Promise<FlightSnapshot[]> {
    this.record("flight_by_number");
    const n = Number.parseInt(key.number, 10);
    const now = this.now().getTime();
    let snaps: FlightSnapshot[] = [];
    if (n >= 1 && n < 4000) {
      snaps = [simulate(ownPlan(key.carrier, n, key.date), now)];
    } else if (n >= 5000 && n < 9000) {
      // inbound leg: may belong to the own flight of the same date or the next day
      for (const d of [key.date, shiftDate(key.date, 1)]) {
        const p = inboundPlan(key.carrier, n - 5000, d);
        if (p.date === key.date) snaps = [simulate(p, now)];
      }
    }
    if (key.depIata) snaps = snaps.filter((s) => s.depIata === key.depIata);
    return Promise.resolve(snaps);
  }

  getFlightsByRegistration(reg: string, date: string): Promise<FlightSnapshot[]> {
    this.record("flight_by_registration");
    const m = /^VT-M(\d{4})$/.exec(reg);
    if (!m) return Promise.resolve([]);
    const n = Number(m[1]);
    const now = this.now().getTime();
    // carrier is not encoded in the tail; mock uses "6E" for registration lookups
    const legs: FlightSnapshot[] = [];
    for (const d of [shiftDate(date, -1), date, shiftDate(date, 1)]) {
      for (const p of [inboundPlan("6E", n, d), ownPlan("6E", n, d)]) {
        if (p.date === date) legs.push(simulate(p, now));
      }
    }
    legs.sort((a, b) => Date.parse(a.schedDep!) - Date.parse(b.schedDep!));
    return Promise.resolve(legs);
  }

  getAirportDepartures(iata: string, fromLocal: string, toLocal: string): Promise<FlightSnapshot[]> {
    this.record("airport_departures");
    const from = parseLocalIst(fromLocal), to = parseLocalIst(toLocal);
    if (from == null || to == null || to < from) {
      return Promise.reject(new ProviderError("bad local time range", null, false));
    }
    const code = iata.trim().toUpperCase();
    const now = this.now().getTime();
    const out: FlightSnapshot[] = [];
    for (const date of new Set([localDate(from), localDate(to)])) {
      for (let n = 1; n <= MOCK_DEPARTURE_RANGE; n++) {
        if (route(n).dep !== code) continue;
        const S = schedDepMs(n, date);
        if (S < from || S > to) continue;
        out.push(simulate(ownPlan(MOCK_CARRIERS[n % MOCK_CARRIERS.length], n, date), now));
      }
    }
    out.sort((a, b) => Date.parse(a.schedDep!) - Date.parse(b.schedDep!));
    return Promise.resolve(out);
  }

  getAircraft(reg: string): Promise<AircraftInfo | null> {
    this.record("aircraft_by_registration");
    const r = reg.trim().toUpperCase();
    if (!/^[A-Z0-9-]{2,10}$/.test(r)) return Promise.resolve(null);
    const first = Date.parse(`${MOCK_AIRCRAFT.firstFlightDate}T00:00:00Z`);
    const age = Math.round(((this.now().getTime() - first) / (365.25 * 86400_000)) * 10) / 10;
    return Promise.resolve({ reg: r, ...MOCK_AIRCRAFT, ageYears: age });
  }
}

/** "YYYY-MM-DDTHH:mm" in IST (the mock's only zone) → UTC ms, or null. */
function parseLocalIst(v: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(v);
  if (!m) return null;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) - IST_OFFSET_MIN * MIN;
}

export function shiftDate(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
