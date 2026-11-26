// Public airport page data (docs/P3_CONTRACTS.md B8). verify_jwt = false, CORS *.
// GET ?iata=DEL → { airport: { iata, name, city, tz, level, headline, updated_at, weather, today } }.
// The public part of Airport Health plus today's departure counts; never any user data. Cached 5 min.
import {
  AIRPORT_STATUS_TTL_S,
  handleAirportStatus,
  type PublicAirportStatus,
  TtlCache,
} from "../_shared/public-stats.ts";
import { log } from "../_shared/http.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";

const cache = new TtlCache<PublicAirportStatus | null>(AIRPORT_STATUS_TTL_S * 1000);

Deno.serve((req) =>
  handleAirportStatus(req, {
    async lookup(iata) {
      const { data, error } = await serviceClient().rpc("airport_status_public", { p_iata: iata });
      if (error) throw new Error(error.message);
      return (data as Record<string, unknown> | null) ?? null;
    },
    now: () => new Date(),
    cache,
    log,
  })
);
