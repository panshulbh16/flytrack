import { assert, assertEquals, assertRejects } from "@std/assert";
import {
  type BillingDeps,
  type BillingEventRow,
  type BillingStore,
  type Entitlement,
  grantFromSubscriber,
  handleRevenueCatWebhook,
  PRO_PRODUCTS,
  type ProfileBilling,
  type RcEvent,
  type RcSubscriber,
  revenueCatApi,
  SETS_EXPIRY,
  syncEntitlement,
  TRIP_PASS_PRODUCT,
} from "../_shared/billing.ts";
import { safeEqual } from "../_shared/http.ts";
import { keyFromJson, publishableKey, secretKey } from "../_shared/supabase-keys.ts";
import { SearchError, searchFlight } from "../_shared/engine.ts";
import type { FlightRow } from "../_shared/types.ts";
import { type AirportInfo, toSearchFlight, withSearchQuota } from "../search-flight/view.ts";
import { makeDeps } from "./helpers.ts";

const NOW = Date.parse("2026-11-26T10:00:00Z");
const DAY = 86_400_000;
const SECRET = "whsec-0123456789abcdef";
const U1 = "11111111-2222-4333-8444-555555555555";
const U2 = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

class MemoryBillingStore implements BillingStore {
  events = new Map<string, BillingEventRow & { processed: boolean; outcome?: string }>();
  profiles = new Map<string, ProfileBilling>();
  writes: { userId: string; e: Entitlement }[] = [];
  failWrites = 0;

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
    if (this.failWrites > 0) {
      this.failWrites--;
      return Promise.reject(new Error("db down"));
    }
    const p = this.profiles.get(userId)!;
    this.profiles.set(userId, { ...p, ...e });
    this.writes.push({ userId, e });
    return Promise.resolve();
  }
  latestTripPassPurchase(userId: string) {
    let best: number | null = null;
    for (const e of this.events.values()) {
      if (
        e.processed && e.type === "NON_RENEWING_PURCHASE" && e.product_id === TRIP_PASS_PRODUCT &&
        e.app_user_id === userId && e.purchased_at
      ) {
        const t = Date.parse(e.purchased_at);
        if (best === null || t > best) best = t;
      }
    }
    return Promise.resolve(best);
  }
  latestSubscriptionExpiry(userId: string) {
    let best: number | null = null;
    for (const e of this.events.values()) {
      if (
        e.processed && SETS_EXPIRY.has(e.type) && PRO_PRODUCTS.has(e.product_id ?? "") && e.app_user_id === userId &&
        e.expires_at
      ) {
        const t = Date.parse(e.expires_at);
        if (best === null || t > best) best = t;
      }
    }
    return Promise.resolve(best);
  }
  profile(id: string) {
    return this.profiles.get(id)!;
  }
}

function setup(opts: { rc?: (id: string) => RcSubscriber | number } = {}) {
  const store = new MemoryBillingStore();
  store.profiles.set(U1, { plan: "free", proUntil: null, trialFlightId: null });
  store.profiles.set(U2, { plan: "free", proUntil: null, trialFlightId: null });
  const logs: string[] = [];
  const fetched: { url: string; auth: string | null }[] = [];
  const fetchFn: typeof fetch = (input, init) => {
    const url = String(input);
    fetched.push({ url, auth: new Headers(init?.headers).get("authorization") });
    const id = decodeURIComponent(url.split("/subscribers/")[1]);
    const r = opts.rc!(id);
    if (typeof r === "number") return Promise.resolve(new Response("{}", { status: r }));
    return Promise.resolve(new Response(JSON.stringify({ subscriber: r }), { status: 200 }));
  };
  const deps: BillingDeps = {
    store,
    rc: opts.rc ? revenueCatApi("sk_test_rc", fetchFn) : null,
    now: () => new Date(NOW),
    log: (m) => logs.push(m),
  };
  const send = (event: Partial<RcEvent>, auth: string | null = SECRET) =>
    handleRevenueCatWebhook(deps, SECRET, auth, JSON.stringify({ api_version: "1.0", event }));
  return { store, deps, send, logs, fetched };
}

let seq = 0;
function ev(type: string, over: Partial<RcEvent> = {}): Partial<RcEvent> {
  return {
    id: `evt-${++seq}`,
    type,
    app_user_id: U1,
    product_id: "heygate_pro_monthly",
    entitlement_ids: ["pro"],
    purchased_at_ms: NOW - 1000,
    expiration_at_ms: NOW + 30 * DAY,
    environment: "PRODUCTION",
    subscriber_attributes: { $email: { value: "someone@example.com" } },
    ...over,
  };
}

// ---------- auth ----------

Deno.test("webhook: fails closed without a secret, rejects a wrong Authorization", async () => {
  const { deps, send } = setup();
  assertEquals((await handleRevenueCatWebhook(deps, null, SECRET, "{}")).status, 503);
  assertEquals((await send(ev("INITIAL_PURCHASE"), null)).status, 401);
  assertEquals((await send(ev("INITIAL_PURCHASE"), "Bearer " + SECRET)).status, 401);
  assertEquals((await send(ev("INITIAL_PURCHASE"), SECRET + "x")).status, 401);
  assertEquals((await handleRevenueCatWebhook(deps, SECRET, SECRET, "not json")).status, 400);
  assertEquals(
    (await handleRevenueCatWebhook(deps, SECRET, SECRET, JSON.stringify({ event: { type: "RENEWAL" } }))).status,
    400,
  );
  assert(safeEqual(SECRET, SECRET) && !safeEqual(SECRET, SECRET.slice(0, -1)) && !safeEqual("", SECRET));
});

Deno.test("webhook: TEST is a 200 no-op", async () => {
  const { send, store } = setup();
  const r = await send(ev("TEST"));
  assertEquals(r.status, 200);
  assertEquals(store.events.size, 0);
  assertEquals(store.writes.length, 0);
});

// ---------- event-derived state machine ----------

Deno.test("webhook: INITIAL_PURCHASE → Pro until expiration; duplicate delivery is a no-op", async () => {
  const { send, store } = setup();
  const e = ev("INITIAL_PURCHASE", { product_id: "heygate_pro_yearly:yearly", expiration_at_ms: NOW + 365 * DAY });
  const r = await send(e);
  assertEquals(r.body.outcome, "applied");
  assertEquals(store.profile(U1).plan, "pro");
  assertEquals(store.profile(U1).proUntil?.getTime(), NOW + 365 * DAY);
  const row = store.events.get(e.id!)!;
  assertEquals(row.product_id, "heygate_pro_yearly", "base product id stored");
  assert(!("subscriber_attributes" in row.payload), "subscriber attributes (PII) not stored");

  store.profiles.set(U1, { plan: "free", proUntil: null, trialFlightId: null }); // changed meanwhile
  const again = await send(e);
  assertEquals(again.body.outcome, "duplicate");
  assertEquals(store.profile(U1).plan, "free", "duplicate did not re-apply");
});

Deno.test("webhook: a failed apply returns 500 and the retry applies it", async () => {
  const { send, store } = setup();
  store.failWrites = 1;
  const e = ev("RENEWAL");
  assertEquals((await send(e)).status, 500);
  assertEquals(store.profile(U1).plan, "free");
  const retry = await send(e);
  assertEquals(retry.status, 200);
  assertEquals(retry.body.outcome, "applied");
  assertEquals(store.profile(U1).plan, "pro");
});

Deno.test("webhook: CANCELLATION and BILLING_ISSUE keep Pro; EXPIRATION ends it", async () => {
  const { send, store } = setup();
  await send(ev("INITIAL_PURCHASE", { expiration_at_ms: NOW + 10 * DAY }));
  assertEquals((await send(ev("CANCELLATION", { cancel_reason: "UNSUBSCRIBE" }))).body.outcome, "unchanged");
  assertEquals((await send(ev("BILLING_ISSUE"))).body.outcome, "unchanged");
  assertEquals(store.profile(U1).proUntil?.getTime(), NOW + 10 * DAY);
  await send(ev("EXPIRATION", { expiration_at_ms: NOW + 10 * DAY }));
  assertEquals(store.profile(U1).plan, "free");
  assertEquals(store.events.size, 4, "every event recorded");
  assertEquals(store.profile(U1).proUntil, null);
});

Deno.test("webhook: stale EXPIRATION after a renewal does not cut Pro short", async () => {
  const { send, store } = setup();
  await send(ev("RENEWAL", { expiration_at_ms: NOW + 30 * DAY }));
  const r = await send(ev("EXPIRATION", { expiration_at_ms: NOW - DAY }));
  assertEquals(r.body.outcome, "unchanged");
  assertEquals(store.profile(U1).plan, "pro");
});

Deno.test("webhook: refund (CANCELLATION by CUSTOMER_SUPPORT) ends Pro now", async () => {
  const { send, store } = setup();
  await send(ev("INITIAL_PURCHASE", { expiration_at_ms: NOW + 30 * DAY }));
  await send(ev("CANCELLATION", { cancel_reason: "CUSTOMER_SUPPORT", expiration_at_ms: NOW }));
  assertEquals(store.profile(U1).plan, "free");
});

Deno.test("webhook: trip pass grants 7 days, stacks with the later grant, survives sub expiry", async () => {
  const { send, store } = setup();
  const bought = NOW - DAY;
  await send(ev("NON_RENEWING_PURCHASE", {
    product_id: TRIP_PASS_PRODUCT,
    entitlement_ids: null,
    purchased_at_ms: bought,
    expiration_at_ms: null,
  }));
  assertEquals(store.profile(U1).plan, "pro");
  assertEquals(store.profile(U1).proUntil?.getTime(), bought + 7 * DAY);

  // a monthly sub bought later: its expiry is later, so it wins
  await send(ev("INITIAL_PURCHASE", { expiration_at_ms: NOW + 30 * DAY }));
  assertEquals(store.profile(U1).proUntil?.getTime(), NOW + 30 * DAY);
  // a second trip pass never shortens a longer grant
  await send(
    ev("NON_RENEWING_PURCHASE", { product_id: TRIP_PASS_PRODUCT, purchased_at_ms: NOW, expiration_at_ms: null }),
  );
  assertEquals(store.profile(U1).proUntil?.getTime(), NOW + 30 * DAY);
  // the sub is refunded at once: the latest trip pass (bought NOW) still covers 7 days
  await send(ev("CANCELLATION", { cancel_reason: "CUSTOMER_SUPPORT", expiration_at_ms: NOW }));
  assertEquals(store.profile(U1).plan, "pro");
  assertEquals(store.profile(U1).proUntil?.getTime(), NOW + 7 * DAY);
});

Deno.test("webhook: an old trip pass delivered late grants nothing", async () => {
  const { send, store } = setup();
  await send(ev("NON_RENEWING_PURCHASE", { product_id: TRIP_PASS_PRODUCT, purchased_at_ms: NOW - 8 * DAY }));
  assertEquals(store.profile(U1).plan, "free");
});

Deno.test("webhook: unrelated products and unknown users get 200 without changes", async () => {
  const { send, store, logs } = setup();
  assertEquals(
    (await send(ev("INITIAL_PURCHASE", { product_id: "other_sub", entitlement_ids: [] }))).body.outcome,
    "unchanged",
  );
  const anon = await send(ev("INITIAL_PURCHASE", { app_user_id: "$RCAnonymousID:abc" }));
  assertEquals([anon.status, anon.body.outcome], [200, "unknown_user"]);
  const missing = await send(ev("INITIAL_PURCHASE", { app_user_id: "99999999-9999-4999-8999-999999999999" }));
  assertEquals([missing.status, missing.body.outcome], [200, "unknown_user"]);
  assertEquals(store.writes.length, 0);
  assert(logs.some((l) => l.includes("no profile")));
});

Deno.test("webhook: manual lifetime grant is never downgraded", async () => {
  const { send, store } = setup();
  store.profiles.set(U1, { plan: "pro", proUntil: null, trialFlightId: null });
  await send(ev("EXPIRATION", { expiration_at_ms: NOW }));
  await send(ev("INITIAL_PURCHASE", { expiration_at_ms: NOW + DAY }));
  assertEquals(store.profile(U1), { plan: "pro", proUntil: null, trialFlightId: null });
});

Deno.test("webhook: TRANSFER without the API moves finite Pro time to the new user", async () => {
  const { send, store } = setup();
  store.profiles.set(U1, { plan: "pro", proUntil: new Date(NOW + 20 * DAY), trialFlightId: null });
  const r = await send(ev("TRANSFER", {
    app_user_id: undefined,
    transferred_from: [U1, "$RCAnonymousID:x"],
    transferred_to: [U2],
  }));
  assertEquals(r.body.outcome, "applied");
  assertEquals(store.profile(U1).plan, "free");
  assertEquals(store.profile(U2).plan, "pro");
  assertEquals(store.profile(U2).proUntil?.getTime(), NOW + 20 * DAY);
});

// ---------- RevenueCat REST refetch ----------

Deno.test("refetch: current entitlement + latest trip pass, the later wins", async () => {
  const { send, store, fetched } = setup({
    rc: () => ({
      entitlements: { pro: { expires_date: new Date(NOW + 2 * DAY).toISOString() } },
      non_subscriptions: {
        [TRIP_PASS_PRODUCT]: [
          { purchase_date: new Date(NOW - 20 * DAY).toISOString() },
          { purchase_date: new Date(NOW - DAY).toISOString() },
        ],
      },
    }),
  });
  // event content is ignored in refetch mode: even an EXPIRATION applies the current state
  await send(ev("EXPIRATION", { expiration_at_ms: NOW - DAY }));
  assertEquals(store.profile(U1).plan, "pro");
  assertEquals(store.profile(U1).proUntil?.getTime(), NOW + 6 * DAY);
  assertEquals(fetched[0].url, `https://api.revenuecat.com/v1/subscribers/${U1}`);
  assertEquals(fetched[0].auth, "Bearer sk_test_rc");
});

Deno.test("refetch: no entitlement → free; null expires_date → lifetime; grace period counts", async () => {
  let sub: RcSubscriber = { entitlements: {} };
  const { send, store } = setup({ rc: () => sub });
  store.profiles.set(U1, { plan: "pro", proUntil: new Date(NOW + DAY), trialFlightId: null });
  await send(ev("EXPIRATION"));
  assertEquals(store.profile(U1).plan, "free");

  sub = { entitlements: { pro: { expires_date: null } } };
  await send(ev("INITIAL_PURCHASE"));
  assertEquals(store.profile(U1), { plan: "pro", proUntil: null, trialFlightId: null });

  assertEquals(
    grantFromSubscriber({
      entitlements: {
        pro: {
          expires_date: new Date(NOW - DAY).toISOString(),
          grace_period_expires_date: new Date(NOW + DAY).toISOString(),
        },
      },
    }),
    NOW + DAY,
  );
});

Deno.test("refetch: TRANSFER re-fetches both sides; RevenueCat errors make RevenueCat retry", async () => {
  const subs: Record<string, RcSubscriber | number> = {
    [U1]: { entitlements: {} },
    [U2]: { entitlements: { pro: { expires_date: new Date(NOW + 30 * DAY).toISOString() } } },
  };
  const { send, store, fetched } = setup({ rc: (id) => subs[id] });
  store.profiles.set(U1, { plan: "pro", proUntil: new Date(NOW + 30 * DAY), trialFlightId: null });
  await send(ev("TRANSFER", { app_user_id: undefined, transferred_from: [U1], transferred_to: [U2] }));
  assertEquals(fetched.map((f) => f.url.split("/").pop()), [U1, U2]);
  assertEquals(store.profile(U1).plan, "free");
  assertEquals(store.profile(U2).plan, "pro");

  subs[U1] = 500;
  const e = ev("RENEWAL");
  assertEquals((await send(e)).status, 500);
  assert(!store.events.get(e.id!)!.processed, "left unprocessed for the retry");
});

// ---------- sync-entitlement ----------

Deno.test("sync-entitlement: refetches and returns the contract shape", async () => {
  const { deps, store } = setup({
    rc: () => ({ entitlements: { pro: { expires_date: new Date(NOW + 30 * DAY).toISOString() } } }),
  });
  store.profiles.set(U1, { plan: "free", proUntil: null, trialFlightId: "f-trial" });
  assertEquals(await syncEntitlement(deps, U1), {
    plan: "pro",
    pro_until: new Date(NOW + 30 * DAY).toISOString(),
    trial_flight_id: "f-trial",
  });
  assertEquals(await syncEntitlement(deps, "99999999-9999-4999-8999-999999999999"), null);
});

Deno.test("sync-entitlement: without the API key reports the stored (effective) state", async () => {
  const { deps, store } = setup();
  store.profiles.set(U1, { plan: "pro", proUntil: new Date(NOW - 1), trialFlightId: null });
  assertEquals(await syncEntitlement(deps, U1), { plan: "free", pro_until: null, trial_flight_id: null });
});

// ---------- supabase-keys ----------

Deno.test("supabase-keys: JSON keys, then FLYTRACK_*, then legacy", () => {
  const env = (vars: Record<string, string>) => (n: string) => vars[n];
  assertEquals(
    secretKey(env({
      SUPABASE_SECRET_KEYS: '{"other":"sb_secret_o","default":"sb_secret_d"}',
      FLYTRACK_SECRET_KEY: "manual",
      SUPABASE_SERVICE_ROLE_KEY: "legacy",
    })),
    "sb_secret_d",
  );
  assertEquals(
    secretKey(
      env({ SUPABASE_SECRET_KEYS: "not json", FLYTRACK_SECRET_KEY: " manual ", SUPABASE_SERVICE_ROLE_KEY: "legacy" }),
    ),
    "manual",
  );
  assertEquals(secretKey(env({ FLYTRACK_SECRET_KEY: "", SUPABASE_SERVICE_ROLE_KEY: "legacy" })), "legacy");
  assertEquals(secretKey(env({})), null);
  assertEquals(
    publishableKey(env({ SUPABASE_PUBLISHABLE_KEYS: '{"web":"sb_publishable_w"}', SUPABASE_ANON_KEY: "anon" })),
    "sb_publishable_w",
  );
  assertEquals(publishableKey(env({ FLYTRACK_PUBLISHABLE_KEY: "pk", SUPABASE_ANON_KEY: "anon" })), "pk");
  assertEquals(publishableKey(env({ SUPABASE_ANON_KEY: "anon" })), "anon");
  assertEquals(keyFromJson('["", "k2"]'), "k2");
  assertEquals(keyFromJson('{"default": ""}'), null);
});

// ---------- search-flight: whitelisted response + quota ----------

const AIRPORTS = new Map<string, AirportInfo>([
  ["DEL", { iata: "DEL", name: "Indira Gandhi Intl", city: "Delhi", lat: 28.56, lon: 77.1, tz: "Asia/Kolkata" }],
]);

function flightRow(over: Partial<FlightRow> = {}): FlightRow {
  return {
    id: "f1",
    carrier_iata: "6E",
    flight_number: "2341",
    flight_date: "2026-11-25",
    dep_iata: "DEL",
    arr_iata: "BOM",
    carrier_icao: "IGO",
    airline_name: "IndiGo",
    status: "scheduled",
    sched_dep: "2026-11-25T13:20:00Z",
    est_dep: null,
    act_dep: null,
    dep_terminal: "1",
    dep_gate: null,
    dep_checkin: null,
    sched_arr: null,
    est_arr: null,
    act_arr: null,
    arr_terminal: null,
    arr_gate: null,
    baggage_belt: null,
    aircraft_model: "A320neo",
    aircraft_reg: "VT-ISR",
    delay_reason: null,
    live_lat: null,
    live_lon: null,
    live_alt_ft: null,
    live_heading: null,
    live_speed_kts: null,
    live_updated_at: null,
    inbound_flight_id: "f0",
    inbound_checked_at: null,
    inbound_watch_until: null,
    prediction: { lateMin: 25, alertedBucket: 30, inboundReg: "VT-X" },
    provider: "mock",
    provider_ref: "secret-ref",
    provider_updated_at: null,
    alert_state: {} as FlightRow["alert_state"],
    tracker_count: 3,
    pro_tracker_count: 1,
    last_polled_at: null,
    next_poll_at: null,
    lease_until: null,
    poll_error_count: 0,
    last_error: "boom",
    webhook_subscription_id: "sub",
    webhook_expires_at: null,
    updated_at: "2026-11-25T00:00:00Z",
    ...over,
  };
}

Deno.test("search view: flight_view columns only, Pro fields masked unless Pro for that flight", () => {
  const free = toSearchFlight(flightRow(), AIRPORTS, () => false);
  for (
    const k of [
      "alert_state",
      "tracker_count",
      "lease_until",
      "provider_ref",
      "last_error",
      "webhook_subscription_id",
      "carrier_icao",
    ]
  ) {
    assert(!(k in free), `${k} must not be exposed`);
  }
  assertEquals(free.dep_tz, "Asia/Kolkata");
  assertEquals(free.arr_tz, null, "unknown airport → nulls");
  assertEquals([free.inbound_flight_id, free.prediction, free.has_pro_insight], [null, null, true]);

  const pro = toSearchFlight(flightRow(), AIRPORTS, (id) => id === "f1");
  assertEquals(pro.inbound_flight_id, "f0");
  assertEquals(pro.prediction, { lateMin: 25 });
  assertEquals(toSearchFlight(flightRow({ prediction: null }), AIRPORTS, () => true).has_pro_insight, false);
  assertEquals(toSearchFlight(flightRow({ prediction: { reason: "x" } }), AIRPORTS, () => true).has_pro_insight, null);
});

Deno.test("search quota: the reservation gates the provider call, cache hits cost nothing", async () => {
  const h = makeDeps("2026-11-21T00:00:00Z");
  let slots = 1;
  let reservations = 0;
  h.deps.repo = withSearchQuota(h.repo, () => {
    reservations++;
    return Promise.resolve(slots-- > 0);
  }, 30);
  await searchFlight(h.deps, "u", { flight: "6E 2341", date: "2026-11-25" });
  await searchFlight(h.deps, "u", { flight: "6E 2341", date: "2026-11-25" }); // cache
  assertEquals(reservations, 1, "cache hit does not reserve");
  await assertRejects(
    () => searchFlight(h.deps, "u", { flight: "QP 1372", date: "2026-11-25" }),
    SearchError,
    "Too many",
  );
  assertEquals(reservations, 2);
  // the wrapped repo still behaves like the original for everything else
  assertEquals((await h.deps.repo.getFlightsByNumberDate("6E", "2341", "2026-11-25")).length > 0, true);
});
