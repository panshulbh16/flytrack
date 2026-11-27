// Public yearly report (docs/P3_CONTRACTS.md B11). verify_jwt = false, CORS *.
// GET ?year=2026 (default: the current year in India) → { report: { year, totals, airlines, airports,
// busiest_routes, fog } }. Anonymous aggregates only (global_report applies k-anonymity; re-checked in
// _shared/public-stats.ts). Cached 1 h.
import { GLOBAL_REPORT_TTL_S, handleGlobalReport, type PublicGlobalReport, TtlCache } from "../_shared/public-stats.ts";
import { log } from "../_shared/http.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";

const cache = new TtlCache<PublicGlobalReport | null>(GLOBAL_REPORT_TTL_S * 1000, 20);

Deno.serve((req) =>
  handleGlobalReport(req, {
    async lookup(year) {
      const { data, error } = await serviceClient().rpc("global_report", { p_year: Number(year) });
      if (error) throw new Error(error.message);
      return (data as Record<string, unknown> | null) ?? null;
    },
    now: () => new Date(),
    cache,
    log,
  })
);
