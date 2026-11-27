// Billing (docs/P1_CONTRACTS.md section 1): RevenueCat → profiles.plan / profiles.pro_until.
// Pure decision logic + a small storage interface, so the webhook state machine is unit-tested
// without Supabase or RevenueCat. Prices live in the store consoles, never here.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { safeEqual } from "./http.ts";

export const ENTITLEMENT_ID = "pro";
export const PRO_PRODUCTS: ReadonlySet<string> = new Set(["heygate_pro_yearly", "heygate_pro_monthly"]);
export const TRIP_PASS_PRODUCT = "heygate_trip_pass";
export const TRIP_PASS_DAYS = 7;
/**
 * Gift Pro (docs/P4_CONTRACTS.md section 3): consumables that create a code for someone else. They are not
 * attached to the `pro` entitlement and never give the purchaser Pro.
 */
export const GIFT_PRODUCTS: Readonly<Record<string, number>> = { heygate_gift_year: 365, heygate_gift_trip: 7 };
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
  /** Store transaction of a purchase (and of the purchase a CANCELLATION / refund is about). */
  transaction_id?: string | null;
  original_transaction_id?: string | null;
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

/** Days a gift product gives, or null when the product is not a gift. */
export function giftDays(productId: string | null | undefined): number | null {
  return GIFT_PRODUCTS[baseProductId(productId)] ?? null;
}

/** The store transaction a gift event is about; the RevenueCat event id when the store sent none. */
export function giftTransactionId(ev: RcEvent): string {
  const t = [ev.transaction_id, ev.original_transaction_id].find((v) => typeof v === "string" && v.trim());
  return (t ?? `rc_event:${ev.id}`).trim().slice(0, 200);
}

/** One redeemed gift code (public.redeemed_gifts). */
export interface RedeemedGift {
  redeemedAt: number;
  /** Start of the gift's window at redemption: the later of then and the Pro end it stacked on. */
  startsAt: number;
  days: number;
}

/**
 * End of the Pro time redeemed gifts give (ms), or null when there are none. `baseEnd` is when the
 * user's store Pro (subscription / trip pass) really ends as now known: a natural expiry or renewal, or the
 * refund time. Each gift starts at the latest of its redemption, the previous gift's end, and its recorded
 * start pulled back to `baseEnd` when the store Pro it stacked on ended early. So a gift stacked on a
 * subscription that is later refunded gives its own days from the refund, never the refunded time, and the
 * result never exceeds what the gift promised at redemption. Stable: depends on recorded times only.
 */
export function giftEnd(gifts: readonly RedeemedGift[], baseEnd: number | null): number | null {
  let t: number | null = null;
  for (const g of [...gifts].sort((a, b) => a.redeemedAt - b.redeemedAt)) {
    const stackedOn = baseEnd === null ? g.redeemedAt : Math.min(baseEnd, g.startsAt);
    const start = Math.max(g.redeemedAt, t ?? g.redeemedAt, stackedOn);
    t = start + g.days * DAY_MS;
  }
  return t;
}

function numericGrant(...grants: (number | null | undefined)[]): number | null {
  const ns = grants.filter((g): g is number => typeof g === "number");
  return ns.length ? Math.max(...ns) : null;
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
  /** Gift codes the user redeemed. Not RevenueCat's to take away (see giftEnd). */
  gifts?: readonly RedeemedGift[];
}

/** What an event means for one user's profile when we cannot ask RevenueCat. */
export function decideFromEvent(ev: RcEvent, current: Entitlement, history: EventHistory, now: Date): Decision {
  const nowMs = now.getTime();
  const tripOnly: Grant = history.tripEnd !== null && history.tripEnd > nowMs ? history.tripEnd : null;
  const gifts = history.gifts ?? [];
  /** Grants that outlive a subscription change, given when store Pro really ends: trip pass + gift time. */
  const keep = (storeEnd: number | null): Grant => {
    const g = gifts.length ? giftEnd(gifts, numericGrant(storeEnd, history.tripEnd)) : null;
    return laterGrant(tripOnly, g !== null && g > nowMs ? g : null);
  };

  if (SETS_EXPIRY.has(ev.type)) {
    if (!isProEvent(ev)) return { kind: "none", reason: "not_pro_product" };
    if (typeof ev.expiration_at_ms !== "number") return { kind: "none", reason: "no_expiration" };
    return { kind: "set", grant: laterGrant(ev.expiration_at_ms, keep(ev.expiration_at_ms)), reason: "pro_expiration" };
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
      const refundedAt = typeof ev.event_timestamp_ms === "number" ? Math.min(ev.event_timestamp_ms, nowMs) : nowMs;
      return { kind: "set", grant: keep(refundedAt), reason: "refund" };
    }
    case "EXPIRATION": {
      if (!isProEvent(ev)) return { kind: "none", reason: "not_pro_product" };
      // An older expiration delivered after a renewal must not cut the renewed period short.
      const endedAt = typeof ev.expiration_at_ms === "number" ? ev.expiration_at_ms : nowMs;
      if (history.subExpiry !== null && history.subExpiry > endedAt + STALE_SLACK_MS) {
        return { kind: "none", reason: "newer_grant" };
      }
      // "unless another active grant exists": a trip pass keeps Pro until it ends.
      return { kind: "set", grant: keep(endedAt), reason: "expired" };
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
  /** Gift Pro: the code for a store transaction, created once (null = no profile for the purchaser). */
  createGiftCode?(purchaserId: string, productId: string, days: number, txn: string): Promise<GiftCodeResult | null>;
  /** Gift Pro refund: voids the transaction's code unless it was redeemed. */
  voidGiftCode?(txn: string): Promise<VoidGiftResult>;
  /** Gift codes the user redeemed (public.redeemed_gifts). */
  redeemedGifts?(userId: string): Promise<RedeemedGift[]>;
}

export interface GiftCodeResult {
  code: string;
  created: boolean;
}

export type VoidGiftResult = "voided" | "already_voided" | "redeemed" | "not_found";

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
  const now = deps.now();
  const store = grantFromSubscriber(sub);
  // RevenueCat keeps an ended (or refunded) entitlement with its real end date, so this is stable
  const storeEnd = store === "lifetime" ? null : store;
  const grant = laterGrant(store, activeGiftGrant(await redeemedGifts(deps, userId), storeEnd, now));
  return await write(deps, userId, profile, entitlementFromGrant(grant, now));
}

async function redeemedGifts(deps: BillingDeps, userId: string): Promise<RedeemedGift[]> {
  return deps.store.redeemedGifts ? await deps.store.redeemedGifts(userId) : [];
}

/** Pro time from redeemed gifts, when still running. RevenueCat does not know about it. */
function activeGiftGrant(gifts: readonly RedeemedGift[], storeEnd: number | null, now: Date): Grant {
  const end = gifts.length ? giftEnd(gifts, storeEnd) : null;
  return end !== null && end > now.getTime() ? end : null;
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
  if (giftDays(ev.product_id) !== null) return await applyGiftEvent(deps, ev);
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
  const [lastTrip, subExpiry, gifts] = await Promise.all([
    deps.store.latestTripPassPurchase(userId),
    deps.store.latestSubscriptionExpiry(userId),
    redeemedGifts(deps, userId),
  ]);
  const d = decideFromEvent(
    ev,
    profile,
    { tripEnd: lastTrip === null ? null : tripPassEnd(lastTrip), subExpiry, gifts },
    now,
  );
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
    // Redeemed gift time belongs to the account, not to the store purchases that move: the sender keeps
    // the gifts (laid out from their redemption, as the store Pro is gone), and only the store part moves.
    const gifts = await redeemedGifts(deps, id);
    let store: Grant = g;
    if (gifts.length) {
      const [lastTrip, subExpiry] = await Promise.all([
        deps.store.latestTripPassPurchase(id),
        deps.store.latestSubscriptionExpiry(id),
      ]);
      const recorded = numericGrant(subExpiry, lastTrip === null ? null : tripPassEnd(lastTrip));
      if (recorded !== null) store = recorded > now.getTime() ? recorded : null;
      // no store events recorded (bought before the webhook existed): the profile, as before gifts
      else if (typeof g !== "number" || g <= (giftEnd(gifts, null) ?? 0)) store = null;
    }
    moved = laterGrant(moved, store);
    outcomes.push(await write(deps, id, p, entitlementFromGrant(activeGiftGrant(gifts, null, now), now)));
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

// ---------- Gift Pro -------------------------------------------------------------------------------

/**
 * A gift product's event. NON_RENEWING_PURCHASE creates the transaction's single code (idempotent on
 * store_transaction_id) and does not touch the purchaser's plan. CANCELLATION is a refund: the code is
 * voided unless someone already redeemed it (then nothing changes and it is logged). The code itself is
 * never logged: it is a bearer secret until redeemed.
 */
export async function applyGiftEvent(deps: BillingDeps, ev: RcEvent): Promise<string> {
  const days = giftDays(ev.product_id);
  if (days === null) return "not_gift";
  const txn = giftTransactionId(ev);
  switch (ev.type) {
    case "NON_RENEWING_PURCHASE": {
      const buyer = ev.app_user_id ?? "";
      if (!isSupabaseUserId(buyer)) {
        deps.log("billing: gift purchase without a Supabase app_user_id", { eventId: ev.id });
        return "unknown_user";
      }
      if (!deps.store.createGiftCode) throw new Error("billing store cannot create gift codes");
      const r = await deps.store.createGiftCode(buyer, baseProductId(ev.product_id), days, txn);
      if (!r) return "unknown_user";
      return r.created ? "gift_created" : "gift_exists";
    }
    case "CANCELLATION": {
      if (!deps.store.voidGiftCode) throw new Error("billing store cannot void gift codes");
      const r = await deps.store.voidGiftCode(txn);
      if (r === "redeemed") deps.log("billing: refunded gift was already redeemed; kept", { eventId: ev.id });
      if (r === "not_found") deps.log("billing: refund for an unknown gift transaction", { eventId: ev.id });
      return `gift_${r}`;
    }
    default:
      return "gift_ignored";
  }
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

  async createGiftCode(purchaserId: string, productId: string, days: number, txn: string) {
    const { data, error } = await this.db.rpc("create_gift_code", {
      p_purchaser: purchaserId,
      p_product: productId,
      p_days: days,
      p_txn: txn,
    });
    if (error) throw new Error(`create_gift_code: ${error.message}`);
    return (data as GiftCodeResult | null) ?? null;
  }

  async voidGiftCode(txn: string): Promise<VoidGiftResult> {
    const { data, error } = await this.db.rpc("void_gift_code", { p_txn: txn });
    if (error) throw new Error(`void_gift_code: ${error.message}`);
    return data as VoidGiftResult;
  }

  async redeemedGifts(userId: string): Promise<RedeemedGift[]> {
    const { data, error } = await this.db.rpc("redeemed_gifts", { p_user: userId });
    if (error) throw new Error(`redeemed_gifts: ${error.message}`);
    const out: RedeemedGift[] = [];
    for (const r of (data ?? []) as { redeemed_at?: string; starts_at?: string; days?: number }[]) {
      const redeemedAt = parseMs(r.redeemed_at), startsAt = parseMs(r.starts_at);
      if (redeemedAt === null || typeof r.days !== "number") continue;
      out.push({ redeemedAt, startsAt: startsAt ?? redeemedAt, days: r.days });
    }
    return out;
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
