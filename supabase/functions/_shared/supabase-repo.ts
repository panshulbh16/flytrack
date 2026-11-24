import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import type { FlightEvent, FlightRow, Recipient, TrackedField } from "./types.ts";
import type { LiveActivityTarget, ProviderCallLog, PushLogRow, Repo } from "./repo.ts";

/** Service-role client (bypasses RLS). Only ever used inside Edge Functions. */
export function serviceClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY not set");
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

function must<T>(res: { data: T | null; error: { message: string } | null }, what: string): T {
  if (res.error) throw new Error(`${what}: ${res.error.message}`);
  return res.data as T;
}

export class SupabaseRepo implements Repo {
  private tzCache = new Map<string, string | null>();
  constructor(private readonly db: SupabaseClient) {}

  async claimDueFlights(limit: number, leaseSeconds: number): Promise<FlightRow[]> {
    return must(await this.db.rpc("claim_due_flights", { p_limit: limit, p_lease_seconds: leaseSeconds }), "claim") ?? [];
  }

  async getFlight(id: string) {
    return must(await this.db.from("flights").select("*").eq("id", id).maybeSingle(), "getFlight") as FlightRow | null;
  }

  async getFlightByKey(c: string, n: string, d: string, dep: string) {
    return must(
      await this.db.from("flights").select("*").eq("carrier_iata", c).eq("flight_number", n).eq("flight_date", d)
        .eq("dep_iata", dep).maybeSingle(),
      "getFlightByKey",
    ) as FlightRow | null;
  }

  async getFlightsByNumberDate(c: string, n: string, d: string) {
    return must(
      await this.db.from("flights").select("*").eq("carrier_iata", c).eq("flight_number", n).eq("flight_date", d),
      "getFlightsByNumberDate",
    ) as FlightRow[];
  }

  async insertFlight(row: Partial<FlightRow>): Promise<FlightRow> {
    // ON CONFLICT DO NOTHING, then read back — two concurrent searches can't create duplicates.
    const ins = await this.db.from("flights").upsert(row, {
      onConflict: "carrier_iata,flight_number,flight_date,dep_iata",
      ignoreDuplicates: true,
    }).select("*");
    if (ins.error) throw new Error(`insertFlight: ${ins.error.message}`);
    if (ins.data?.length) return ins.data[0] as FlightRow;
    const existing = await this.getFlightByKey(row.carrier_iata!, row.flight_number!, row.flight_date!, row.dep_iata!);
    if (!existing) throw new Error("insertFlight: conflict but row not found");
    return existing;
  }

  async updateFlight(id: string, patch: Partial<FlightRow>): Promise<FlightRow> {
    return must(await this.db.from("flights").update(patch).eq("id", id).select("*").single(), "updateFlight") as FlightRow;
  }

  async insertHistory(flightId: string, source: string, changed: TrackedField[], snapshot: unknown) {
    const { raw: _raw, ...snap } = (snapshot ?? {}) as Record<string, unknown>; // don't store provider raw twice
    must(
      await this.db.from("flight_status_history").insert({ flight_id: flightId, source, changed_fields: changed, snapshot: snap }),
      "insertHistory",
    );
  }

  async insertEventIfNew(flightId: string, e: FlightEvent): Promise<string | null> {
    const res = await this.db.from("flight_events").upsert({
      flight_id: flightId, type: e.type, severity: e.severity, pro_only: e.proOnly,
      old_value: e.oldValue, new_value: e.newValue, payload: e.payload, dedupe_key: e.dedupeKey,
    }, { onConflict: "dedupe_key", ignoreDuplicates: true }).select("id");
    if (res.error) throw new Error(`insertEvent: ${res.error.message}`);
    return res.data?.[0]?.id != null ? String(res.data[0].id) : null;
  }

  async markEventsPushed(ids: string[]) {
    if (!ids.length) return;
    must(await this.db.from("flight_events").update({ pushed_at: new Date().toISOString() }).in("id", ids), "markPushed");
  }

  async getUnpushedEvents(minAgeMin: number, maxAgeMin: number, limit: number) {
    const now = Date.now();
    const rows = must(
      await this.db.from("flight_events").select("*").is("pushed_at", null)
        .lte("created_at", new Date(now - minAgeMin * 60_000).toISOString())
        .gte("created_at", new Date(now - maxAgeMin * 60_000).toISOString())
        .order("created_at").limit(limit),
      "unpushed",
    ) as Record<string, unknown>[];
    return rows.map((r) => ({
      id: String(r.id), flightId: String(r.flight_id), type: r.type, severity: r.severity, proOnly: r.pro_only,
      oldValue: r.old_value, newValue: r.new_value, payload: r.payload, dedupeKey: r.dedupe_key,
    })) as (FlightEvent & { id: string; flightId: string })[];
  }

  async getRecipients(flightId: string): Promise<Recipient[]> {
    return must(await this.db.rpc("get_flight_recipients", { p_flight_id: flightId }), "recipients") ?? [];
  }

  async getDependentsOfInbound(inboundId: string) {
    return must(await this.db.from("flights").select("*").eq("inbound_flight_id", inboundId), "dependents") as FlightRow[];
  }

  async logPushes(rows: PushLogRow[]) {
    if (!rows.length) return;
    must(
      await this.db.from("push_log").insert(rows.map((r) => ({
        event_id: r.eventId, device_id: r.deviceId, user_id: r.userId, kind: r.kind,
        status: r.result.dryRun ? "dry_run" : r.result.ok ? "sent" : r.result.invalidToken ? "invalid_token" : "failed",
        fcm_message_id: r.result.messageId ?? null, error: r.result.error ?? null,
      }))),
      "logPushes",
    );
  }

  async deleteDeviceByToken(token: string) {
    must(await this.db.from("devices").delete().eq("fcm_token", token), "deleteDevice");
  }

  async getLiveActivities(flightId: string): Promise<LiveActivityTarget[]> {
    const rows = must(
      await this.db.from("live_activities").select("id, user_id, device_id, activity_token, devices(fcm_token)")
        .eq("flight_id", flightId),
      "liveActivities",
    ) as unknown as { id: string; user_id: string; device_id: string; activity_token: string; devices: { fcm_token: string } }[];
    // devices is many-to-one, so PostgREST embeds one object (the untyped client guesses an array).
    return rows.map(({ devices, ...r }) => ({ ...r, fcm_token: devices.fcm_token }));
  }

  async deleteLiveActivities(ids: string[]) {
    if (!ids.length) return;
    must(await this.db.from("live_activities").delete().in("id", ids), "deleteLiveActivities");
  }

  async airportTz(iata: string) {
    if (this.tzCache.has(iata)) return this.tzCache.get(iata)!;
    const r = must(await this.db.from("airports").select("tz").eq("iata", iata).maybeSingle(), "tz") as { tz: string } | null;
    this.tzCache.set(iata, r?.tz ?? null);
    return r?.tz ?? null;
  }

  async logProviderCall(c: ProviderCallLog) {
    const { error } = await this.db.from("provider_calls").insert({
      provider: c.provider, endpoint: c.endpoint, status_code: c.status, units: c.units,
      duration_ms: c.durationMs, flight_id: c.flightId ?? null, user_id: c.userId ?? null,
    });
    if (error) console.error("logProviderCall", error.message); // never fail a poll because of metering
  }

  async countUserProviderCalls(userId: string, sinceIso: string) {
    const res = await this.db.from("provider_calls").select("id", { count: "exact", head: true })
      .eq("user_id", userId).gte("created_at", sinceIso);
    if (res.error) throw new Error(`countCalls: ${res.error.message}`);
    return res.count ?? 0;
  }
}
