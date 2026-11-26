// Public "track my flight" link (docs/P1_CONTRACTS.md section 2). verify_jwt = false: anyone holding the
// token may read the flight's PUBLIC fields. GET ?t=<token>; 404 for unknown, revoked or expired tokens.
import { handleSharedFlight } from "../_shared/share.ts";
import { log } from "../_shared/http.ts";
import { serviceClient, SupabaseRepo } from "../_shared/supabase-repo.ts";

let repo: SupabaseRepo | null = null;

Deno.serve((req) =>
  handleSharedFlight(req, {
    lookup: (token) => (repo ??= new SupabaseRepo(serviceClient())).getSharedFlight(token),
    now: () => new Date(),
    log,
  })
);
