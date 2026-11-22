import { AeroDataBoxProvider } from "./aerodatabox.ts";
import { MockProvider } from "./mock.ts";
import type { FlightDataProvider } from "./provider.ts";

/** FLIGHT_PROVIDER = "mock" (default, free) | "aerodatabox". Add "flightaware" here later. */
export function providerFromEnv(): FlightDataProvider {
  const name = Deno.env.get("FLIGHT_PROVIDER") ?? "mock";
  switch (name) {
    case "mock":
      return new MockProvider();
    case "aerodatabox":
      return AeroDataBoxProvider.fromEnv();
    default:
      throw new Error(`Unknown FLIGHT_PROVIDER "${name}"`);
  }
}
