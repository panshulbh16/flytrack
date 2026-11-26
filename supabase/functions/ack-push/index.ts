// POST { "event_id": 123, "received_at": "<iso>" }  (Authorization: Bearer <user JWT>)
// The apps call it when an alert arrives or is opened, whichever is first; they ignore failures.
// Sets push_log.delivered_at on the CALLER's alert rows for that event (ack_push RPC, service role only).
import { createClient } from "npm:@supabase/supabase-js@2";
import { log } from "../_shared/http.ts";
import { publishableKey } from "../_shared/supabase-keys.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import { handleAckPush } from "./handler.ts";

Deno.serve((req) =>
  handleAckPush(req, {
    async userId(auth) {
      const url = Deno.env.get("SUPABASE_URL");
      const key = publishableKey();
      const jwt = auth.replace(/^Bearer\s+/i, "");
      if (!url || !key || !jwt) return null;
      const userClient = createClient(url, key, {
        global: { headers: { Authorization: auth } },
        auth: { persistSession: false },
      });
      const { data: { user }, error } = await userClient.auth.getUser(jwt);
      return error || !user ? null : user.id;
    },
    async ack(userId, eventId, receivedAt) {
      const { data, error } = await serviceClient().rpc("ack_push", {
        p_user: userId,
        p_event_id: eventId,
        p_received_at: receivedAt.toISOString(),
      });
      if (error) throw new Error(error.message);
      return Number(data ?? 0);
    },
    now: () => new Date(),
    log,
  })
);
