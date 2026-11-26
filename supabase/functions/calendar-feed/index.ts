// Calendar subscription feed (docs/P2_WAVE2_CONTRACTS.md section 3). verify_jwt = false: calendar apps
// cannot send a JWT, so the unguessable ?t= token (profiles.calendar_token) is the only key.
// GET ?t=<token> → text/calendar; 404 for an unknown token. Rotating the token kills the old URL.
import { handleCalendarFeed } from "../_shared/ics.ts";
import { log } from "../_shared/http.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";

Deno.serve((req) =>
  handleCalendarFeed(req, {
    async lookup(token) {
      const { data, error } = await serviceClient().rpc("calendar_feed", { p_token: token });
      if (error) throw new Error(error.message);
      return data ?? null;
    },
    now: () => new Date(),
    log,
  })
);
