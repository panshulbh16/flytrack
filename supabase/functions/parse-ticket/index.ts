// POST { "text": "<extracted ticket text>" }  (Authorization: Bearer <user JWT>)
// Returns { pnr, flights }. The file is never uploaded; only text the phone already extracted.
import { createClient } from "npm:@supabase/supabase-js@2";
import { cors, json, log } from "../_shared/http.ts";
import { DAILY_LIMIT, parseTicket, type ParseTicketDeps } from "../_shared/parse-ticket.ts";
import { serviceClient } from "../_shared/supabase-repo.ts";

const STATUS: Record<string, number> = { bad_input: 400, rate_limited: 429, not_configured: 503, provider_error: 502 };

async function usedToday(userId: string): Promise<number> {
  const since = new Date();
  since.setUTCHours(0, 0, 0, 0);
  const res = await serviceClient().from("ticket_parse_usage").select("uses").eq("user_id", userId)
    .eq("day", since.toISOString().slice(0, 10)).maybeSingle();
  if (res.error) throw new Error(res.error.message);
  return (res.data as { uses?: number } | null)?.uses ?? 0;
}

async function reserve(userId: string): Promise<boolean> {
  const { data, error } = await serviceClient().rpc("reserve_ticket_parse", { p_user: userId, p_limit: DAILY_LIMIT });
  if (error) throw new Error(error.message);
  return data === true;
}

export function depsFromEnv(): ParseTicketDeps {
  return { fetch, apiKey: Deno.env.get("ANTHROPIC_API_KEY") || undefined, usedToday, reserve, log };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);

  const auth = req.headers.get("authorization") ?? "";
  const userClient = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
  const { data: { user }, error } = await userClient.auth.getUser(auth.replace(/^Bearer\s+/i, ""));
  if (error || !user) return json({ error: "unauthorized" }, 401);

  let body: { text?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }
  if (typeof body.text !== "string") return json({ error: "bad_input", message: "text required" }, 400);

  try {
    const result = await parseTicket(depsFromEnv(), user.id, body.text);
    if (!result.ok) return json({ error: result.error }, STATUS[result.error]);
    return json(result.ticket);
  } catch (e) {
    log("parse-ticket failed", { error: e instanceof Error ? e.name : "error" });
    return json({ error: "provider_error" }, 502);
  }
});
