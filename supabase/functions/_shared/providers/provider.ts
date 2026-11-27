import type { FlightKey, FlightSnapshot } from "../types.ts";

/** Thrown by adapters so the poller can back off correctly. */
export class ProviderError extends Error {
  constructor(
    message: string,
    public readonly status: number | null = null,
    public readonly retryable = true,
  ) {
    super(message);
    this.name = "ProviderError";
  }
}

export interface ProviderCallInfo {
  endpoint: string;
  status: number | null;
  units: number; // billing units consumed (provider-specific), for cost tracking
  durationMs: number;
}

/** One aircraft (table public.aircraft, docs/P3_CONTRACTS.md A5). Dates are "YYYY-MM-DD". */
export interface AircraftInfo {
  reg: string;
  model: string | null;
  icaoType: string | null;
  firstFlightDate: string | null;
  deliveryDate: string | null;
  ageYears: number | null; // one decimal
  airlineIata: string | null;
  seatConfig: string | null;
}

export interface WebhookSubscription {
  id: string;
  expiresAt: string | null;
}

/**
 * Provider-agnostic flight data source. The app never sees this — only Edge Functions do.
 * Swap AeroDataBox -> FlightAware by writing another adapter; nothing else changes.
 */
export interface FlightDataProvider {
  readonly name: string;

  /** All legs for carrier+number on a local departure date (a flight number can have >1 leg). */
  getFlight(key: FlightKey): Promise<FlightSnapshot[]>;

  /** All legs flown by an aircraft (tail) on a local date — used for "Where's my plane". */
  getFlightsByRegistration(reg: string, date: string): Promise<FlightSnapshot[]>;

  /**
   * Departures from an airport between two airport-LOCAL times "YYYY-MM-DDTHH:mm" (search by route, B5).
   * Adapters split long ranges into windows the provider accepts; codeshare duplicates are removed.
   */
  getAirportDepartures(iata: string, fromLocal: string, toLocal: string): Promise<FlightSnapshot[]>;

  /** Aircraft details by registration (age, type). Null when the provider doesn't know the tail. */
  getAircraft?(reg: string): Promise<AircraftInfo | null>;

  /** Optional push support. If present, the poller subscribes and polls less often. */
  readonly supportsWebhooks: boolean;
  subscribe?(key: FlightKey, callbackUrl: string): Promise<WebhookSubscription>;
  unsubscribe?(subscriptionId: string): Promise<void>;
  /** Parse a webhook body into snapshots. */
  parseWebhook?(body: unknown): FlightSnapshot[];

  /** Called after every network call (for the provider_calls cost log). */
  onCall?: (info: ProviderCallInfo) => void;
}
