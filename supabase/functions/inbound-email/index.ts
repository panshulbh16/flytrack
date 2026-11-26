// Email forwarding import (docs/P2_WAVE2_CONTRACTS.md section 2). verify_jwt = false: called by the
// inbound-mail provider's adapter, authenticated with the x-inbound-secret header (secret
// INBOUND_EMAIL_SECRET, compared in constant time).
// POST { to, from, subject, text, html, attachments: [{ filename, content_type, content_base64 }] }
// Always 200 for a well-formed request (unknown recipient, not Pro and too large are "ignored" / recorded),
// so the provider never retries an email we have already seen. The email itself is never stored or logged.
import { buildDeps } from "../_shared/deps.ts";
import {
  type EmailImportRow,
  handleInboundEmail,
  type InboundUser,
  sendImportPush,
  type TrackResult,
} from "../_shared/email.ts";
import { SearchError, searchFlight } from "../_shared/engine.ts";
import { pusherFromEnv } from "../_shared/fcm.ts";
import { log } from "../_shared/http.ts";
import { DAILY_LIMIT, parseTicket } from "../_shared/parse-ticket.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";
import { withSearchQuota } from "../search-flight/view.ts";

/** Searches an import may start per user and day (shared with search-flight's quota). */
const SEARCH_LIMIT = 30;

function db() {
  return serviceClient();
}

async function usedToday(userId: string): Promise<number> {
  const day = new Date().toISOString().slice(0, 10);
  const res = await db().from("ticket_parse_usage").select("uses").eq("user_id", userId).eq("day", day).maybeSingle();
  if (res.error) throw new Error(res.error.message);
  return (res.data as { uses?: number } | null)?.uses ?? 0;
}

async function reserve(userId: string): Promise<boolean> {
  const { data, error } = await db().rpc("reserve_ticket_parse", { p_user: userId, p_limit: DAILY_LIMIT });
  if (error) throw new Error(error.message);
  return data === true;
}

Deno.serve((req) =>
  handleInboundEmail(req, {
    secret: Deno.env.get("INBOUND_EMAIL_SECRET") || undefined,

    async findUser(token) {
      const { data, error } = await db().rpc("inbound_email_user", { p_token: token });
      if (error) throw new Error(`inbound_email_user: ${error.message}`);
      const row = (data as { user_id: string; is_pro: boolean; alert_language: string | null }[] | null)?.[0];
      return row ? { userId: row.user_id, isPro: row.is_pro === true, alertLanguage: row.alert_language } : null;
    },

    parse: (userId, text) =>
      parseTicket(
        { fetch, apiKey: Deno.env.get("ANTHROPIC_API_KEY") || undefined, usedToday, reserve, log },
        userId,
        text,
      ),

    async search(userId, flight, date) {
      const deps = buildDeps({ userId });
      deps.repo = withSearchQuota(deps.repo, async () => {
        const { data, error } = await db().rpc("reserve_search_quota", { p_user: userId, p_limit: SEARCH_LIMIT });
        if (error) throw new Error(`reserve_search_quota: ${error.message}`);
        return data === true;
      }, SEARCH_LIMIT);
      try {
        const rows = await searchFlight(deps, userId, { flight, date }, {
          perUserPerDay: SEARCH_LIMIT,
          freshForMinutes: 10,
        });
        return {
          ok: true,
          legs: rows.map((r) => ({ id: r.id, dep_iata: r.dep_iata, arr_iata: r.arr_iata, sched_dep: r.sched_dep })),
        };
      } catch (e) {
        if (e instanceof SearchError) return { ok: false, error: e.code };
        throw e;
      }
    },

    async track(userId, flightId, pnr) {
      const { data, error } = await db().rpc("import_track_flight", { p_user: userId, p_flight: flightId, p_pnr: pnr });
      if (error) throw new Error(`import_track_flight: ${error.message}`);
      return data as TrackResult;
    },

    async record(row: EmailImportRow) {
      const { error } = await db().from("email_imports").insert({
        user_id: row.userId,
        from_domain: row.fromDomain,
        subject_hash: row.subjectHash,
        status: row.status,
        flights_added: row.flightsAdded,
        error: row.error,
      });
      if (error) throw new Error(error.message);
    },

    async notify(user: InboundUser, count, firstFlightId) {
      try {
        const client = db();
        const res = await client.from("devices").select("id, fcm_token").eq("user_id", user.userId).eq(
          "notifications_enabled",
          true,
        );
        if (res.error) throw new Error(res.error.message);
        const pusher = pusherFromEnv();
        const sent = await sendImportPush(res.data ?? [], user, count, firstFlightId, (m) => pusher.send(m));
        if (!sent.length) return;
        await client.from("push_log").insert(sent.map((s) => ({
          event_id: null,
          device_id: s.deviceId,
          user_id: user.userId,
          kind: "import",
          status: s.result.dryRun
            ? "dry_run"
            : s.result.ok
            ? "sent"
            : s.result.invalidToken
            ? "invalid_token"
            : "failed",
          fcm_message_id: s.result.messageId ?? null,
          error: s.result.error ?? null,
        })));
        const stale = sent.filter((s) => s.result.invalidToken).map((s) => s.token);
        if (stale.length) await client.from("devices").delete().in("fcm_token", stale);
      } catch (e) {
        log("inbound-email push failed", { userId: user.userId, error: e instanceof Error ? e.message : "error" });
      }
    },

    log,
  })
);
