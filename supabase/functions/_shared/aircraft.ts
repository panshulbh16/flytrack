// Aircraft lookup cache (docs/P3_CONTRACTS.md A5): table public.aircraft, one row per registration.
// Fetched lazily by the poller when a Pro-tracked flight has a tail, refreshed after 30 days.
import type { AircraftInfo } from "./providers/provider.ts";

/** How long an aircraft row is trusted before the provider is asked again. */
export const AIRCRAFT_TTL_DAYS = 30;

/** One row of public.aircraft (snake_case, as stored). */
export interface AircraftRow {
  reg: string;
  model: string | null;
  icao_type: string | null;
  first_flight_date: string | null;
  delivery_date: string | null;
  age_years: number | null;
  airline_iata: string | null;
  seat_config: string | null;
  fetched_at: string;
}

/** "vt-isr " → "VT-ISR"; null when it can't be a registration (the table's check). */
export function normalizeReg(reg: string | null | undefined): string | null {
  const r = (reg ?? "").trim().toUpperCase();
  return /^[A-Z0-9-]{2,10}$/.test(r) ? r : null;
}

const day = (v: string | null) => (v && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) ? v : null);
const short = (v: string | null, max: number) => (v && v.trim() ? v.trim().slice(0, max) : null);

/**
 * The row to store for a lookup result. Values that would break the table's checks are dropped instead of
 * failing the write; an unknown tail (info null) is stored with nulls so it is not looked up again for 30 days.
 */
export function aircraftRow(reg: string, info: AircraftInfo | null, now: Date): AircraftRow {
  const icao = info?.icaoType?.trim().toUpperCase() ?? null;
  const airline = info?.airlineIata?.trim().toUpperCase() ?? null;
  const age = info?.ageYears;
  return {
    reg,
    model: short(info?.model ?? null, 100),
    icao_type: icao && /^[A-Z0-9]{2,4}$/.test(icao) ? icao : null,
    first_flight_date: day(info?.firstFlightDate ?? null),
    delivery_date: day(info?.deliveryDate ?? null),
    age_years: typeof age === "number" && Number.isFinite(age) && age >= 0 && age <= 99 ? Math.round(age * 10) / 10 : null,
    airline_iata: airline && /^[A-Z0-9]{2}$/.test(airline) ? airline : null,
    seat_config: short(info?.seatConfig ?? null, 100),
    fetched_at: now.toISOString(),
  };
}

/** True when the stored row (fetched_at, or null for no row) is missing or older than the TTL. */
export function aircraftStale(fetchedAt: string | null, now: Date): boolean {
  const t = fetchedAt ? Date.parse(fetchedAt) : NaN;
  return Number.isNaN(t) || now.getTime() - t >= AIRCRAFT_TTL_DAYS * 86400_000;
}
