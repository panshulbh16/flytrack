// Delivery receipts (docs/P2_CONTRACTS.md section 3). Pure request handling, so it is testable without Supabase.
import { cors, json } from "../_shared/http.ts";

export interface AckDeps {
  /** The caller's user id from their JWT, or null when the token is missing or invalid. */
  userId(authorization: string): Promise<string | null>;
  /** Sets delivered_at on the caller's alert rows for the event; returns how many rows changed. */
  ack(userId: string, eventId: number, receivedAt: Date): Promise<number>;
  now(): Date;
  log(msg: string, extra?: Record<string, unknown>): void;
}

/** flight_events.id is a bigint identity: a positive safe integer, as a JSON number or a digit string. */
export function parseEventId(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" && /^\d{1,15}$/.test(v) ? Number(v) : NaN;
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

/** received_at from the phone; missing → now. A phone clock ahead of ours never moves it past now(). */
export function parseReceivedAt(v: unknown, now: Date): Date | null {
  if (v == null || v === "") return now;
  if (typeof v !== "string" || v.length > 64) return null;
  const t = Date.parse(v);
  if (Number.isNaN(t)) return null;
  return new Date(Math.min(t, now.getTime()));
}

export async function handleAckPush(req: Request, deps: AckDeps): Promise<Response> {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);

  const userId = await deps.userId(req.headers.get("authorization") ?? "");
  if (!userId) return json({ error: "unauthorized" }, 401);

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }
  const eventId = parseEventId(body.event_id);
  if (eventId == null) return json({ error: "bad_input", message: "event_id" }, 400);
  const receivedAt = parseReceivedAt(body.received_at, deps.now());
  if (!receivedAt) return json({ error: "bad_input", message: "received_at" }, 400);

  try {
    const updated = await deps.ack(userId, eventId, receivedAt);
    return json({ ok: true, updated });
  } catch (e) {
    deps.log("ack-push failed", { error: String(e) });
    return json({ error: "internal" }, 500);
  }
}
