// Billing (docs/P1_CONTRACTS.md section 1): RevenueCat → profiles.plan / profiles.pro_until.
// Pure decision logic + a small storage interface, so the webhook state machine is unit-tested
// without Supabase or RevenueCat. Prices live in the store consoles, never here.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { safeEqual } from "./http.ts";

export const ENTITLEMENT_ID = "pro";
export const PRO_PRODUCTS: ReadonlySet<string> = new Set(["heygate_pro_yearly", "heygate_pro_monthly"]);
export const TRIP_PASS_PRODUCT = "heygate_trip_pass";
export const TRIP_PASS_DAYS = 7;
export const REVENUECAT_API_BASE = "https://api.revenuecat.com/v1";

const DAY_MS = 86_400_000;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// ---------- wire types (only the fields we read) -------------------------------------------------

/** RevenueCat webhook event (`body.event`). */
export interface RcEvent {
  id: string;
  type: string;
  app_user_id?: string | null;
  original_app_user_id?: string | null;
  product_id?: string | null;
  entitlement_ids?: string[] | null;
  entitlement_id?: string | null;
  purchased_at_ms?: number | null;
  expiration_at_ms?: number | null;
  event_timestamp_ms?: number | null;
  environment?: string | null;
  cancel_reason?: string | null;
  transferred_from?: string[] | null;
  transferred_to?: string[] | null;
  [key: string]: unknown;
}

/** `GET /v1/subscribers/{id}` → `subscriber`. */
export interface RcSubscriber {
  entitlements?: Record<string, {
    expires_date?: string | null;
    grace_period_expires_date?: string | null;
    purchase_date?: string | null;
    product_identifier?: string | null;
  }>;
  non_subscriptions?: Record<string, { id?: string; purchase_date?: string | null }[]>;
}

// ---------- state ---------------------------------------------------------------------------------

/** What a user is entitled to: none, Pro until a time (ms), or Pro without end. */
export type Grant = number | "lifetime" | null;

export interface Entitlement {
  plan: "free" | "pro";
  /** null with plan "pro" = no end (lifetime / manual grant), as in profiles.pro_until. */
  proUntil: Date | null;
}

export interface ProfileBilling extends Entitlement {
  trialFlightId: string | null;
}

export function laterGrant(a: Grant, b: Grant): Grant {
  if (a === "lifetime" || b === "lifetime") return "lifetime";
  if (a === null) return b;
  if (b === null) return a;
  return Math.max(a, b);
}

export function entitlementFromGrant(grant: Grant, now: Date): Entitlement {
  if (grant === "lifetime") return { plan: "pro", proUntil: null };
  if (grant !== null && grant > now.getTime()) return { plan: "pro", proUntil: new Date(grant) };
  return { plan: "free", proUntil: null };
}

/** The grant a stored profile represents right now (expired = none). */
export function grantFromProfile(p: Entitlement, now: Date): Grant {
  if (p.plan !== "pro") return null;
  if (p.proUntil === null) return "lifetime";
  return p.proUntil.getTime() > now.getTime() ? p.proUntil.getTime() : null;
}

export function isActive(p: Entitlement, now: Date): boolean {
  return grantFromProfile(p, now) !== null;
}

/** Play sends "productId:basePlanId"; App Store sends the plain product id. */
export function baseProductId(productId: string | null | undefined): string {
  return (productId ?? "").split(":")[0];
}

export function tripPassEnd(purchasedAtMs: number): number {
  return purchasedAtMs + TRIP_PASS_DAYS * DAY_MS;
}

function parseMs(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

/** Current `pro` entitlement (null expiry = lifetime, grace period counts) plus the latest trip pass + 7 days. */
export function grantFromSubscriber(sub: RcSubscriber): Grant {
  let pro: Grant = null;
  const ent = sub.entitlements?.[ENTITLEMENT_ID];
  if (ent) {
    if (ent.expires_date == null) pro = "lifetime";
    else pro = laterGrant(parseMs(ent.expires_date), parseMs(ent.grace_period_expires_date));
  }
  let lastTrip: number | null = null;
  for (const p of sub.non_subscriptions?.[TRIP_PASS_PRODUCT] ?? []) {
    const t = parseMs(p.purchase_date);
    if (t !== null && (lastTrip === null || t > lastTrip)) lastTrip = t;
  }
  return laterGrant(pro, lastTrip === null ? null : tripPassEnd(lastTrip));
}

/**
 * The profile write for a new entitlement, or null when nothing changes.
 * A manual / lifetime grant (plan pro, pro_until null) is never taken away automatically:
 * RevenueCat does not know about it.
 */
export function resolveWrite(current: Entitlement, next: Entitlement): Entitlement | null {
  const currentLifetime = current.plan === "pro" && current.proUntil === null;
  const nextLifetime = next.plan === "pro" && next.proUntil === null;
  if (currentLifetime && !nextLifetime) return null;
  const same = current.plan === next.plan &&
    (current.proUntil?.getTime() ?? null) === (next.proUntil?.getTime() ?? null);
  return same ? null : next;
}

// ---------- event-derived state machine (no REVENUECAT_SECRET_API_KEY) ---------------------------

export type Decision =
  | { kind: "set"; grant: Grant; reason: string }
  | { kind: "none"; reason: string };

export const SETS_EXPIRY: ReadonlySet<string> = new Set([
  "INITIAL_PURCHASE",
  "RENEWAL",
  "UNCANCELLATION",
  "PRODUCT_CHANGE",
  "SUBSCRIPTION_EXTENDED",
]);

function isProEvent(ev: RcEvent): boolean {
  return (ev.entitlement_ids ?? []).includes(ENTITLEMENT_ID) || ev.entitlement_id === ENTITLEMENT_ID ||
    PRO_PRODUCTS.has(baseProductId(ev.product_id));
}

function isTripPassEvent(ev: RcEvent): boolean {
  return baseProductId(ev.product_id) === TRIP_PASS_PRODUCT;
}

/** Slack for clock skew between RevenueCat's expiration time and ours. */
const STALE_SLACK_MS = 60_000;

/** What we already recorded for the user (applied billing_events). */
export interface EventHistory {
  /** End of the latest trip pass (purchase + 7 days), or null. */
  tripEnd: number | null;
  /** Latest expiration a Pro subscription event set, or null. */
  subExpiry: number | null;
}

/** What an event means for one user's profile when we cannot ask RevenueCat. */
export function decideFromEvent(ev: RcEvent, current: Entitlement, history: EventHistory, now: Date): Decision {
  const nowMs = now.getTime();
  const activeTrip: Grant = history.tripEnd !== null && history.tripEnd > nowMs ? history.tripEnd : null;

  if (SETS_EXPIRY.has(ev.type)) {
    if (!isProEvent(ev)) return { kind: "none", reason: "not_pro_product" };
    if (typeof ev.expiration_at_ms !== "number") return { kind: "none", reason: "no_expiration" };
    return { kind: "set", grant: laterGrant(ev.expiration_at_ms, activeTrip), reason: "pro_expiration" };
  }

  switch (ev.type) {
    case "NON_RENEWING_PURCHASE": {
      if (!isTripPassEvent(ev)) return { kind: "none", reason: "not_trip_pass" };
      const bought = typeof ev.purchased_at_ms === "number" ? ev.purchased_at_ms : nowMs;
      return {
        kind: "set",
        grant: laterGrant(grantFromProfile(current, now), tripPassEnd(bought)),
        reason: "trip_pass",
      };
    }
    case "CANCELLATION": {
      // Auto-renew turned off: still Pro until expiry. A refund (CUSTOMER_SUPPORT) ends Pro now.
      if (ev.cancel_reason !== "CUSTOMER_SUPPORT" || !isProEvent(ev)) return { kind: "none", reason: "cancellation" };
      return { kind: "set", grant: activeTrip, reason: "refund" };
    }
    case "EXPIRATION": {
      if (!isProEvent(ev)) return { kind: "none", reason: "not_pro_product" };
      // An older expiration delivered after a renewal must not cut the renewed period short.
      const endedAt = typeof ev.expiration_at_ms === "number" ? ev.expiration_at_ms : nowMs;
      if (history.subExpiry !== null && history.subExpiry > endedAt + STALE_SLACK_MS) {
        return { kind: "none", reason: "newer_grant" };
      }
      // "unless another active grant exists": a trip pass keeps Pro until it ends.
      return { kind: "set", grant: activeTrip, reason: "expired" };
    }
    case "BILLING_ISSUE":
      return { kind: "none", reason: "grace_period" };
    case "TEST":
      return { kind: "none", reason: "test" };
    default:
      return { kind: "none", reason: "ignored_type" };
  }
}

// ---------- ports -----------------------------------------------------------------------------------

export interface BillingEventRow {
  id: string;
  type: string;
  app_user_id: string | null;
  product_id: string | null;
  purchased_at: string | null;
  expires_at: string | null;
  environment: string | null;
  payload: Record<string, unknown>;
}

export interface BillingStore {
  /** Records the event. "duplicate" = it was already applied. */
  beginEvent(row: BillingEventRow): Promise<"new" | "duplicate">;
  finishEvent(id: string, outcome: string): Promise<void>;
  getProfile(userId: string): Promise<ProfileBilling | null>;
  writeEntitlement(userId: string, e: Entitlement): Promise<void>;
  /** purchased_at (ms) of the user's latest applied trip-pass event, or null. */
  latestTripPassPurchase(userId: string): Promise<number | null>;
  /** Latest expires_at (ms) of the user's applied Pro subscription events (SETS_EXPIRY types), or null. */
  latestSubscriptionExpiry(userId: string): Promise<number | null>;
}

export interface RevenueCatApi {
  getSubscriber(appUserId: string): Promise<RcSubscriber>;
}

export class RevenueCatError extends Error {
  constructor(public readonly status: number, msg: string) {
    super(msg);
  }
}

/** REST client for `GET /v1/subscribers/{id}`. `fetchFn` is injected in tests. */
export function revenueCatApi(
  secretApiKey: string,
  fetchFn: typeof fetch = fetch,
  base = REVENUECAT_API_BASE,
): RevenueCatApi {
  return {
    async getSubscriber(appUserId) {
      const res = await fetchFn(`${base}/subscribers/${encodeURIComponent(appUserId)}`, {
        headers: { authorization: `Bearer ${secretApiKey}`, accept: "application/json" },
        signal: AbortSignal.timeout(10_000),
      });
      if (!res.ok) {
        await res.body?.cancel();
        throw new RevenueCatError(res.status, `RevenueCat subscribers ${res.status}`);
      }
      const body = await res.json() as { subscriber?: RcSubscriber };
      if (!body?.subscriber) throw new RevenueCatError(502, "RevenueCat: no subscriber in response");
      return body.subscriber;
    },
  };
}

export interface BillingDeps {
  store: BillingStore;
  /** null when REVENUECAT_SECRET_API_KEY is not set → state comes from the event itself. */
  rc: RevenueCatApi | null;
  now: () => Date;
  log: (msg: string, extra?: Record<string, unknown>) => void;
}

export function isSupabaseUserId(id: string | null | undefined): id is string {
  return typeof id === "string" && UUID_RE.test(id);
}

// ---------- applying ------------------------------------------------------------------------------

export type ApplyOutcome = "applied" | "unchanged" | "unknown_user";

/** Re-fetches RevenueCat for one user and writes the current state. */
export async function refreshFromRevenueCat(deps: BillingDeps, userId: string): Promise<ApplyOutcome> {
  if (!deps.rc) throw new Error("RevenueCat API key not configured");
  const profile = await deps.store.getProfile(userId);
  if (!profile) return "unknown_user";
  const sub = await deps.rc.getSubscriber(userId);
  return await write(deps, userId, profile, entitlementFromGrant(grantFromSubscriber(sub), deps.now()));
}

async function write(
  deps: BillingDeps,
  userId: string,
  current: Entitlement,
  next: Entitlement,
): Promise<ApplyOutcome> {
  const w = resolveWrite(current, next);
  if (!w) {
    if (current.plan === "pro" && current.proUntil === null && next.proUntil !== null) {
      deps.log("billing: kept lifetime/manual grant", { userId });
    }
    return "unchanged";
  }
  await deps.store.writeEntitlement(userId, w);
  return "applied";
}

function eventUsers(ev: RcEvent): string[] {
  const ids = ev.type === "TRANSFER"
    ? [...(ev.transferred_from ?? []), ...(ev.transferred_to ?? [])]
    : [ev.app_user_id ?? ""];
  return [...new Set(ids.filter((id) => id))];
}

/** Applies one webhook event. Returns a short outcome string for billing_events.outcome. */
export async function applyEvent(deps: BillingDeps, ev: RcEvent): Promise<string> {
  if (ev.type === "TEST") return "test";
  const ids = eventUsers(ev);
  const known = ids.filter(isSupabaseUserId);
  const skipped = ids.filter((id) => !isSupabaseUserId(id));
  if (skipped.length) deps.log("billing: ignoring non-Supabase app_user_id", { eventId: ev.id, count: skipped.length });
  if (!known.length) return "unknown_user";

  const outcomes: ApplyOutcome[] = [];
  if (deps.rc) {
    for (const id of known) outcomes.push(await refreshFromRevenueCat(deps, id));
  } else if (ev.type === "TRANSFER") {
    outcomes.push(...await transferFromEvent(deps, ev));
  } else {
    outcomes.push(await applyDerived(deps, known[0], ev));
  }
  if (outcomes.every((o) => o === "unknown_user")) {
    deps.log("billing: no profile for app_user_id", { eventId: ev.id });
    return "unknown_user";
  }
  return outcomes.includes("applied") ? "applied" : "unchanged";
}

async function applyDerived(deps: BillingDeps, userId: string, ev: RcEvent): Promise<ApplyOutcome> {
  const profile = await deps.store.getProfile(userId);
  if (!profile) return "unknown_user";
  const now = deps.now();
  const [lastTrip, subExpiry] = await Promise.all([
    deps.store.latestTripPassPurchase(userId),
    deps.store.latestSubscriptionExpiry(userId),
  ]);
  const d = decideFromEvent(ev, profile, { tripEnd: lastTrip === null ? null : tripPassEnd(lastTrip), subExpiry }, now);
  if (d.kind === "none") return "unchanged";
  return await write(deps, userId, profile, entitlementFromGrant(d.grant, now));
}

/**
 * TRANSFER without the REST API: the purchases moved from `transferred_from` to `transferred_to`,
 * so the receivers get the senders' (finite) Pro time and the senders lose it.
 * Lifetime/manual grants are not RevenueCat's to move and stay where they are.
 */
async function transferFromEvent(deps: BillingDeps, ev: RcEvent): Promise<ApplyOutcome[]> {
  const now = deps.now();
  const from = (ev.transferred_from ?? []).filter(isSupabaseUserId);
  const to = (ev.transferred_to ?? []).filter(isSupabaseUserId);
  const outcomes: ApplyOutcome[] = [];
  let moved: Grant = null;
  for (const id of from) {
    const p = await deps.store.getProfile(id);
    if (!p) {
      outcomes.push("unknown_user");
      continue;
    }
    const g = grantFromProfile(p, now);
    if (g === "lifetime") {
      outcomes.push("unchanged");
      continue;
    }
    moved = laterGrant(moved, g);
    outcomes.push(await write(deps, id, p, { plan: "free", proUntil: null }));
  }
  for (const id of to) {
    const p = await deps.store.getProfile(id);
    if (!p) {
      outcomes.push("unknown_user");
      continue;
    }
    outcomes.push(await write(deps, id, p, entitlementFromGrant(laterGrant(grantFromProfile(p, now), moved), now)));
  }
  return outcomes;
}

// ---------- webhook --------------------------------------------------------------------------------

export interface HttpResult {
  status: number;
  body: Record<string, unknown>;
}

/** Event as stored: without subscriber_attributes (they can hold e-mail / phone numbers). */
export function eventRow(ev: RcEvent): BillingEventRow {
  const { subscriber_attributes: _attrs, ...payload } = ev;
  const iso = (ms: unknown) => typeof ms === "number" && Number.isFinite(ms) ? new Date(ms).toISOString() : null;
  return {
    id: ev.id,
    type: ev.type,
    app_user_id: ev.app_user_id ?? null,
    product_id: ev.product_id ? baseProductId(ev.product_id) : null,
    purchased_at: iso(ev.purchased_at_ms),
    expires_at: iso(ev.expiration_at_ms),
    environment: ev.environment ?? null,
    payload,
  };
}

/**
 * POST /revenuecat-webhook. `authorization` must equal REVENUECAT_WEBHOOK_AUTH (constant-time).
 * 200 for everything RevenueCat should not retry (duplicates, unknown users, ignored types);
 * 5xx only when applying failed, so the retry applies it again.
 */
export async function handleRevenueCatWebhook(
  deps: BillingDeps,
  webhookAuth: string | null | undefined,
  authorization: string | null,
  rawBody: string,
): Promise<HttpResult> {
  if (!webhookAuth) {
    deps.log("billing: REVENUECAT_WEBHOOK_AUTH not set");
    return { status: 503, body: { error: "not_configured" } };
  }
  if (!safeEqual(authorization ?? "", webhookAuth)) return { status: 401, body: { error: "unauthorized" } };

  let ev: RcEvent | undefined;
  try {
    ev = (JSON.parse(rawBody) as { event?: RcEvent })?.event;
  } catch {
    return { status: 400, body: { error: "bad_json" } };
  }
  if (!ev || typeof ev.id !== "string" || !ev.id || ev.id.length > 200 || typeof ev.type !== "string") {
    return { status: 400, body: { error: "bad_event" } };
  }
  if (ev.type === "TEST") return { status: 200, body: { ok: true, outcome: "test" } };

  if (await deps.store.beginEvent(eventRow(ev)) === "duplicate") {
    return { status: 200, body: { ok: true, outcome: "duplicate" } };
  }
  try {
    const outcome = await applyEvent(deps, ev);
    await deps.store.finishEvent(ev.id, outcome);
    deps.log("billing: event", { eventId: ev.id, type: ev.type, outcome });
    return { status: 200, body: { ok: true, outcome } };
  } catch (e) {
    deps.log("billing: event failed", { eventId: ev.id, type: ev.type, error: String(e) });
    return { status: 500, body: { error: "apply_failed" } };
  }
}

// ---------- sync-entitlement ----------------------------------------------------------------------

export interface EntitlementResponse {
  plan: "pro" | "free";
  pro_until: string | null;
  trial_flight_id: string | null;
  /**
   * P3 B13: set when the user is Pro only through a family (public.family_pro_source); the apps show
   * "Pro via {owner_name}'s family". Absent / null for own Pro and for free users.
   */
  via_family?: { owner_name: string | null } | null;
}

/** public.family_pro_source(user): the family owner whose Pro the user shares, or null. */
export interface FamilyProSource {
  owner_id: string;
  owner_name: string | null;
  pro_until: string | null;
}

/**
 * Own Pro wins; otherwise a family's Pro makes the response Pro until the owner's end (null = no end).
 * A member's Pro is never passed on further: family_pro_source only ever names an owner with own Pro.
 */
export function applyFamilyPro(
  resp: EntitlementResponse,
  family: FamilyProSource | null,
  now: Date,
): EntitlementResponse {
  if (resp.plan === "pro" || !family) return { ...resp, via_family: null };
  const until = family.pro_until ? Date.parse(family.pro_until) : null;
  if (until !== null && (Number.isNaN(until) || until <= now.getTime())) return { ...resp, via_family: null };
  return {
    ...resp,
    plan: "pro",
    pro_until: until === null ? null : new Date(until).toISOString(),
    via_family: { owner_name: family.owner_name ?? null },
  };
}

export function entitlementResponse(p: ProfileBilling, now: Date): EntitlementResponse {
  const active = isActive(p, now);
  return {
    plan: active ? "pro" : "free",
    pro_until: active && p.proUntil ? p.proUntil.toISOString() : null,
    trial_flight_id: p.trialFlightId,
  };
}

/** Re-fetches RevenueCat for the caller (when configured) and returns their profile state. */
export async function syncEntitlement(deps: BillingDeps, userId: string): Promise<EntitlementResponse | null> {
  if (deps.rc) {
    if (await refreshFromRevenueCat(deps, userId) === "unknown_user") return null;
  } else {
    deps.log("billing: sync without REVENUECAT_SECRET_API_KEY returns the stored profile");
  }
  const p = await deps.store.getProfile(userId);
  return p ? entitlementResponse(p, deps.now()) : null;
}

// ---------- Supabase storage ----------------------------------------------------------------------

export class SupabaseBillingStore implements BillingStore {
  constructor(private readonly db: SupabaseClient) {}

  async beginEvent(row: BillingEventRow): Promise<"new" | "duplicate"> {
    const ins = await this.db.from("billing_events").upsert(row, { onConflict: "id", ignoreDuplicates: true }).select(
      "id",
    );
    if (ins.error) throw new Error(`billing_events insert: ${ins.error.message}`);
    if (ins.data?.length) return "new";
    const prev = await this.db.from("billing_events").select("processed_at").eq("id", row.id).maybeSingle();
    if (prev.error) throw new Error(`billing_events read: ${prev.error.message}`);
    // Recorded but never applied (an earlier attempt failed): apply it now.
    return prev.data?.processed_at ? "duplicate" : "new";
  }

  async finishEvent(id: string, outcome: string) {
    const { error } = await this.db.from("billing_events")
      .update({ processed_at: new Date().toISOString(), outcome }).eq("id", id);
    if (error) throw new Error(`billing_events finish: ${error.message}`);
  }

  async getProfile(userId: string): Promise<ProfileBilling | null> {
    const { data, error } = await this.db.from("profiles").select("plan, pro_until, trial_flight_id")
      .eq("id", userId).maybeSingle();
    if (error) throw new Error(`profiles read: ${error.message}`);
    if (!data) return null;
    const r = data as { plan: string; pro_until: string | null; trial_flight_id: string | null };
    return {
      plan: r.plan === "pro" ? "pro" : "free",
      proUntil: r.pro_until ? new Date(r.pro_until) : null,
      trialFlightId: r.trial_flight_id,
    };
  }

  async writeEntitlement(userId: string, e: Entitlement) {
    const { error } = await this.db.from("profiles")
      .update({ plan: e.plan, pro_until: e.proUntil?.toISOString() ?? null }).eq("id", userId);
    if (error) throw new Error(`profiles write: ${error.message}`);
  }

  async latestTripPassPurchase(userId: string): Promise<number | null> {
    const { data, error } = await this.db.from("billing_events").select("purchased_at")
      .eq("type", "NON_RENEWING_PURCHASE").eq("product_id", TRIP_PASS_PRODUCT).eq("app_user_id", userId)
      .not("processed_at", "is", null).not("purchased_at", "is", null)
      .order("purchased_at", { ascending: false }).limit(1);
    if (error) throw new Error(`billing_events trip: ${error.message}`);
    const at = (data as { purchased_at: string }[] | null)?.[0]?.purchased_at;
    return at ? Date.parse(at) : null;
  }

  async latestSubscriptionExpiry(userId: string): Promise<number | null> {
    const { data, error } = await this.db.from("billing_events").select("expires_at")
      .in("type", [...SETS_EXPIRY]).in("product_id", [...PRO_PRODUCTS]).eq("app_user_id", userId)
      .not("processed_at", "is", null).not("expires_at", "is", null)
      .order("expires_at", { ascending: false }).limit(1);
    if (error) throw new Error(`billing_events sub: ${error.message}`);
    const at = (data as { expires_at: string }[] | null)?.[0]?.expires_at;
    return at ? Date.parse(at) : null;
  }
}
