import { assert, assertEquals } from "@std/assert";
import {
  applyGiftEvent,
  type BillingDeps,
  type BillingEventRow,
  type BillingStore,
  decideFromEvent,
  type Entitlement,
  type GiftCodeResult,
  giftDays,
  giftEnd,
  giftTransactionId,
  handleRevenueCatWebhook,
  type ProfileBilling,
  type RcEvent,
  type RcSubscriber,
  type RedeemedGift,
  type VoidGiftResult,
} from "../_shared/billing.ts";

const NOW = Date.parse("2026-11-27T10:00:00Z");
const DAY = 86_400_000;
const AUTH = "whsec-gift-0123456789";
const BUYER = "11111111-2222-4333-8444-555555555555";
const FRIEND = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

interface Gift {
  code: string;
  purchaser: string;
  product: string;
  days: number;
  txn: string;
  redeemedBy?: string;
  redeemedAt?: number;
  startsAt?: number;
  grantsUntil?: number;
  voided?: boolean;
}

class GiftStore implements BillingStore {
  events = new Map<string, BillingEventRow & { processed: boolean; outcome?: string }>();
  profiles = new Map<string, ProfileBilling>();
  gifts: Gift[] = [];
  writes: { userId: string; e: Entitlement }[] = [];
  private n = 0;

  beginEvent(row: BillingEventRow) {
    const prev = this.events.get(row.id);
    if (prev?.processed) return Promise.resolve("duplicate" as const);
    if (!prev) this.events.set(row.id, { ...row, processed: false });
    return Promise.resolve("new" as const);
  }
  finishEvent(id: string, outcome: string) {
    const e = this.events.get(id)!;
    e.processed = true;
    e.outcome = outcome;
    return Promise.resolve();
  }
  getProfile(userId: string) {
    const p = this.profiles.get(userId);
    return Promise.resolve(p ? { ...p } : null);
  }
  writeEntitlement(userId: string, e: Entitlement) {
    this.profiles.set(userId, { ...this.profiles.get(userId)!, ...e });
    this.writes.push({ userId, e });
    return Promise.resolve();
  }
  latestTripPassPurchase() {
    return Promise.resolve(null);
  }
  subExpiry: number | null = null;
  latestSubscriptionExpiry() {
    return Promise.resolve(this.subExpiry);
  }
  createGiftCode(purchaserId: string, productId: string, days: number, txn: string) {
    const prev = this.gifts.find((g) => g.txn === txn);
    if (prev) return Promise.resolve<GiftCodeResult>({ code: prev.code, created: false });
    if (!this.profiles.has(purchaserId)) return Promise.resolve(null);
    const code = `ABCD-EFGH-${String(++this.n).padStart(4, "2")}`;
    this.gifts.push({ code, purchaser: purchaserId, product: productId, days, txn });
    return Promise.resolve<GiftCodeResult>({ code, created: true });
  }
  voidGiftCode(txn: string): Promise<VoidGiftResult> {
    const g = this.gifts.find((x) => x.txn === txn);
    if (!g) return Promise.resolve("not_found");
    if (g.redeemedBy) return Promise.resolve("redeemed");
    if (g.voided) return Promise.resolve("already_voided");
    g.voided = true;
    return Promise.resolve("voided");
  }
  redeemedGifts(userId: string): Promise<RedeemedGift[]> {
    return Promise.resolve(
      this.gifts.filter((g) => g.redeemedBy === userId).map((g) => ({
        redeemedAt: g.redeemedAt!,
        startsAt: g.startsAt!,
        days: g.days,
      })),
    );
  }
  /** What redeem_gift does in SQL. */
  redeem(code: string, userId: string, at = NOW) {
    const g = this.gifts.find((x) => x.code === code)!;
    const p = this.profiles.get(userId)!;
    const base = p.plan === "pro" && p.proUntil && p.proUntil.getTime() > at ? p.proUntil.getTime() : at;
    g.redeemedBy = userId;
    g.redeemedAt = at;
    g.startsAt = base;
    g.grantsUntil = base + g.days * DAY;
    this.profiles.set(userId, { ...p, plan: "pro", proUntil: new Date(g.grantsUntil) });
  }
}

function setup(rc: RcSubscriber | null = null) {
  const store = new GiftStore();
  store.profiles.set(BUYER, { plan: "free", proUntil: null, trialFlightId: null });
  store.profiles.set(FRIEND, { plan: "free", proUntil: null, trialFlightId: null });
  const logs: { msg: string; extra?: Record<string, unknown> }[] = [];
  const rcCalls: string[] = [];
  const deps: BillingDeps = {
    store,
    rc: rc
      ? {
        getSubscriber: (id) => {
          rcCalls.push(id);
          return Promise.resolve(rc);
        },
      }
      : null,
    now: () => new Date(NOW),
    log: (msg, extra) => logs.push({ msg, extra }),
  };
  const send = (ev: Partial<RcEvent>) =>
    handleRevenueCatWebhook(deps, AUTH, AUTH, JSON.stringify({ event: { id: crypto.randomUUID(), ...ev } }));
  return { store, deps, logs, rcCalls, send };
}

const giftPurchase = (over: Partial<RcEvent> = {}): Partial<RcEvent> => ({
  type: "NON_RENEWING_PURCHASE",
  app_user_id: BUYER,
  product_id: "heygate_gift_year",
  transaction_id: "GPA.3300-1111-2222-33333",
  purchased_at_ms: NOW,
  entitlement_ids: null,
  ...over,
});

Deno.test("gifts: product table and transaction ids", () => {
  assertEquals(giftDays("heygate_gift_year"), 365);
  assertEquals(giftDays("heygate_gift_trip"), 7);
  assertEquals(giftDays("heygate_gift_trip:base"), 7);
  assertEquals(giftDays("heygate_trip_pass"), null);
  assertEquals(giftDays(null), null);
  assertEquals(giftTransactionId({ id: "e1", type: "X", transaction_id: " T1 " }), "T1");
  assertEquals(giftTransactionId({ id: "e1", type: "X", transaction_id: "", original_transaction_id: "O1" }), "O1");
  assertEquals(giftTransactionId({ id: "e1", type: "X" }), "rc_event:e1");
});

Deno.test("gifts: a gift purchase creates exactly one code and does not give the purchaser Pro", async () => {
  const { store, send, logs } = setup();
  const r = await send(giftPurchase());
  assertEquals(r, { status: 200, body: { ok: true, outcome: "gift_created" } });
  assertEquals(store.gifts.length, 1);
  assertEquals(store.gifts[0].purchaser, BUYER);
  assertEquals(store.gifts[0].days, 365);
  assertEquals(store.gifts[0].txn, "GPA.3300-1111-2222-33333");
  assertEquals(store.writes, [], "no profile write");
  assertEquals(store.profiles.get(BUYER)!.plan, "free");
  // RevenueCat re-delivers under a new event id: same transaction → same code
  const again = await send(giftPurchase());
  assertEquals(again.body.outcome, "gift_exists");
  assertEquals(store.gifts.length, 1);
  // a second purchase is a second code
  await send(giftPurchase({ transaction_id: "GPA.second", product_id: "heygate_gift_trip" }));
  assertEquals(store.gifts.map((g) => g.days), [365, 7]);
  assert(!JSON.stringify(logs).includes("ABCD-"), "codes are never logged");
});

Deno.test("gifts: with the RevenueCat API configured gifts still skip the subscriber refresh", async () => {
  const { store, send, rcCalls } = setup({ entitlements: {} });
  const r = await send(giftPurchase());
  assertEquals(r.body.outcome, "gift_created");
  assertEquals(rcCalls, []);
  assertEquals(store.writes, []);
});

Deno.test("gifts: unknown purchaser ids are recorded, not retried", async () => {
  const { send, store } = setup();
  assertEquals((await send(giftPurchase({ app_user_id: "$RCAnonymousID:abc" }))).body.outcome, "unknown_user");
  assertEquals(
    (await send(giftPurchase({ app_user_id: "99999999-2222-4333-8444-555555555555" }))).body.outcome,
    "unknown_user",
  );
  assertEquals(store.gifts.length, 0);
});

Deno.test("gifts: a refund voids an unused code; a redeemed one is kept and logged", async () => {
  const { store, send, logs } = setup();
  await send(giftPurchase({ transaction_id: "T-unused" }));
  await send(giftPurchase({ transaction_id: "T-used" }));
  store.redeem(store.gifts[1].code, FRIEND);

  const refund = (txn: string) =>
    send({
      type: "CANCELLATION",
      app_user_id: BUYER,
      product_id: "heygate_gift_year",
      transaction_id: txn,
      cancel_reason: "CUSTOMER_SUPPORT",
    });
  assertEquals((await refund("T-unused")).body.outcome, "gift_voided");
  assertEquals(store.gifts[0].voided, true);
  assertEquals((await refund("T-unused")).body.outcome, "gift_already_voided");
  assertEquals((await refund("T-used")).body.outcome, "gift_redeemed");
  assertEquals(store.gifts[1].voided, undefined);
  assertEquals(store.profiles.get(FRIEND)!.plan, "pro", "the recipient keeps Pro");
  assert(logs.some((l) => l.msg === "billing: refunded gift was already redeemed; kept"));
  assertEquals((await refund("T-nope")).body.outcome, "gift_not_found");
  // the purchaser's profile was never touched
  assertEquals(store.writes.filter((w) => w.userId === BUYER), []);
});

Deno.test("gifts: other event types for gift products are ignored", async () => {
  const { deps } = setup();
  assertEquals(
    await applyGiftEvent(deps, { id: "x", type: "EXPIRATION", product_id: "heygate_gift_trip" }),
    "gift_ignored",
  );
  assertEquals(
    await applyGiftEvent(deps, { id: "x", type: "NON_RENEWING_PURCHASE", product_id: "heygate_pro_yearly" }),
    "not_gift",
  );
});

Deno.test("gifts: redeemed gift time survives the recipient's subscription expiring (event path)", async () => {
  const { store, send } = setup();
  await send(giftPurchase({ transaction_id: "T1" }));
  // FRIEND had a monthly subscription until NOW + 5 days, then redeems a year gift on top
  store.profiles.set(FRIEND, { plan: "pro", proUntil: new Date(NOW + 5 * DAY), trialFlightId: null });
  store.redeem(store.gifts[0].code, FRIEND);
  const giftEnd = NOW + 370 * DAY;
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), giftEnd);
  // the subscription expires: gift time stays
  const r = await send({
    type: "EXPIRATION",
    app_user_id: FRIEND,
    product_id: "heygate_pro_monthly",
    entitlement_ids: ["pro"],
    expiration_at_ms: NOW + 5 * DAY,
  });
  assertEquals(r.body.outcome, "unchanged");
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), giftEnd);
  // a refund of the subscription likewise falls back to the gift, not to free; the gift's year now runs
  // from the refund (the refunded 5 days are not kept)
  await send({
    type: "CANCELLATION",
    app_user_id: FRIEND,
    product_id: "heygate_pro_monthly",
    entitlement_ids: ["pro"],
    cancel_reason: "CUSTOMER_SUPPORT",
  });
  assertEquals(store.profiles.get(FRIEND)!.plan, "pro");
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 365 * DAY);
  // a renewal that ends later than the gift wins
  await send({
    type: "RENEWAL",
    app_user_id: FRIEND,
    product_id: "heygate_pro_yearly",
    entitlement_ids: ["pro"],
    expiration_at_ms: NOW + 400 * DAY,
  });
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 400 * DAY);
});

Deno.test("gifts: redeemed gift time survives a RevenueCat refresh that has no entitlement", async () => {
  const { store, send } = setup({ entitlements: {} });
  await send(giftPurchase({ transaction_id: "T1" }));
  store.redeem(store.gifts[0].code, FRIEND);
  const r = await send({ type: "EXPIRATION", app_user_id: FRIEND, product_id: "heygate_pro_monthly" });
  assertEquals(r.body.outcome, "unchanged");
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 365 * DAY);
});

Deno.test("gifts: decideFromEvent folds giftEnd in like a trip pass", () => {
  const cur: Entitlement = { plan: "pro", proUntil: new Date(NOW + 30 * DAY) };
  const d = decideFromEvent(
    { id: "e", type: "EXPIRATION", product_id: "heygate_pro_monthly", entitlement_ids: ["pro"], expiration_at_ms: NOW },
    cur,
    { tripEnd: NOW + 2 * DAY, subExpiry: null, gifts: [{ redeemedAt: NOW - DAY, startsAt: NOW - DAY, days: 31 }] },
    new Date(NOW),
  );
  assertEquals(d, { kind: "set", grant: NOW + 30 * DAY, reason: "expired" });
  const past = decideFromEvent(
    { id: "e", type: "EXPIRATION", product_id: "heygate_pro_monthly", entitlement_ids: ["pro"], expiration_at_ms: NOW },
    cur,
    { tripEnd: null, subExpiry: null, gifts: [{ redeemedAt: NOW - 8 * DAY, startsAt: NOW - 8 * DAY, days: 7 }] },
    new Date(NOW),
  );
  assertEquals(past, { kind: "set", grant: null, reason: "expired" });
});

Deno.test("gifts: TRANSFER moves store Pro but leaves redeemed gift time with the account", async () => {
  const { store, send } = setup();
  await send(giftPurchase({ transaction_id: "T1", product_id: "heygate_gift_trip" }));
  store.profiles.set(FRIEND, { plan: "pro", proUntil: new Date(NOW + 100 * DAY), trialFlightId: null });
  store.redeem(store.gifts[0].code, FRIEND); // gift until NOW + 107 days
  await send({ type: "TRANSFER", transferred_from: [FRIEND], transferred_to: [BUYER] });
  assertEquals(store.profiles.get(BUYER)!.proUntil!.getTime(), NOW + 107 * DAY);
  // no store events recorded: the profile's time moves; FRIEND keeps only the gift's own 7 days
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 7 * DAY, "gift time stays with FRIEND");
});

Deno.test("gifts: giftEnd lays gifts after the store Pro that really ran", () => {
  const sub = NOW + 300 * DAY;
  const g1: RedeemedGift = { redeemedAt: NOW, startsAt: sub, days: 7 };
  const g2: RedeemedGift = { redeemedAt: NOW + DAY, startsAt: sub + 7 * DAY, days: 7 };
  assertEquals(giftEnd([], sub), null);
  // natural expiry at the recorded end: the stacking promised at redemption
  assertEquals(giftEnd([g2, g1], sub), sub + 14 * DAY);
  // a renewal past it never pushes the gift further than promised
  assertEquals(giftEnd([g1, g2], sub + 365 * DAY), sub + 14 * DAY);
  // refunded on day 2: both gifts run from the refund
  assertEquals(giftEnd([g1, g2], NOW + 2 * DAY), NOW + 16 * DAY);
  // no store Pro known: from redemption, chained
  assertEquals(giftEnd([g1, g2], null), NOW + 14 * DAY);
});

Deno.test("gifts: a refunded subscription does not survive behind a cheap gift (refund abuse)", async () => {
  const { store, send } = setup();
  await send(giftPurchase({ transaction_id: "T1", product_id: "heygate_gift_trip" }));
  // FRIEND buys a year, redeems a 7-day gift on top (until year + 7 days), then gets the year refunded
  store.profiles.set(FRIEND, { plan: "pro", proUntil: new Date(NOW + 365 * DAY), trialFlightId: null });
  store.redeem(store.gifts[0].code, FRIEND);
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 372 * DAY);
  await send({
    type: "CANCELLATION",
    app_user_id: FRIEND,
    product_id: "heygate_pro_yearly",
    entitlement_ids: ["pro"],
    cancel_reason: "CUSTOMER_SUPPORT",
    event_timestamp_ms: NOW,
  });
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 7 * DAY, "only the gift's 7 days");
  // a later EXPIRATION for the refunded year (same end) does not bring the year back either
  await send({
    type: "EXPIRATION",
    app_user_id: FRIEND,
    product_id: "heygate_pro_yearly",
    entitlement_ids: ["pro"],
    expiration_at_ms: NOW,
  });
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 7 * DAY);
});

Deno.test("gifts: RevenueCat refresh after a refund keeps only the gift's own days", async () => {
  const refundedAt = new Date(NOW).toISOString();
  const { store, send } = setup({ entitlements: { pro: { expires_date: refundedAt } } });
  await send(giftPurchase({ transaction_id: "T1", product_id: "heygate_gift_trip" }));
  store.profiles.set(FRIEND, { plan: "pro", proUntil: new Date(NOW + 365 * DAY), trialFlightId: null });
  store.redeem(store.gifts[0].code, FRIEND, NOW - DAY);
  await send({ type: "CANCELLATION", app_user_id: FRIEND, product_id: "heygate_pro_yearly", entitlement_ids: ["pro"] });
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 7 * DAY);
});

Deno.test("gifts: TRANSFER with recorded store events moves only the subscription, not the gift", async () => {
  const { store, send } = setup();
  await send(giftPurchase({ transaction_id: "T1", product_id: "heygate_gift_trip" }));
  store.profiles.set(FRIEND, { plan: "pro", proUntil: new Date(NOW + 100 * DAY), trialFlightId: null });
  store.subExpiry = NOW + 100 * DAY;
  store.redeem(store.gifts[0].code, FRIEND); // stacked: until NOW + 107 days
  await send({ type: "TRANSFER", transferred_from: [FRIEND], transferred_to: [BUYER] });
  assertEquals(store.profiles.get(BUYER)!.proUntil!.getTime(), NOW + 100 * DAY, "the subscription moves");
  assertEquals(store.profiles.get(FRIEND)!.proUntil!.getTime(), NOW + 7 * DAY, "the gift's own days stay");
});
