import { assert, assertEquals, assertMatch, assertNotEquals, assertRejects } from "@std/assert";
import listTrips from "./fixtures/tripit/list-trip-upcoming.json" with { type: "json" };
import { hmacSha1Base64 } from "../_shared/oauth1.ts";
import {
  authorizeUrl,
  CALLBACK_CODE_TTL_MS,
  callbackPage,
  callbackTripIt,
  type ClaimedRequest,
  completeTripIt,
  connectTripIt,
  cronSyncTripIt,
  disconnectTripIt,
  hashCallbackCode,
  MANUAL_SYNC_COOLDOWN_MS,
  MAX_SEGMENTS_PER_SYNC,
  type OAuthToken,
  parseSegments,
  parseTokenKey,
  type SearchResult,
  type SeenSegment,
  type SegmentOutcome,
  type SyncStats,
  syncTripIt,
  tokenCipher,
  type TripItApi,
  tripItApi,
  TripItApiError,
  type TripItConnection,
  type TripItDeps,
  type TripItStore,
} from "../_shared/tripit.ts";

const NOW = Date.parse("2026-11-27T10:00:00Z");
const U = "11111111-2222-4333-8444-555555555555";
const KEY_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
const CALLBACK = "https://proj.supabase.co/functions/v1/tripit-callback";

type MemoryRow = TripItConnection & {
  lastError?: string | null;
  callbackCodeExpiresAt?: number | null;
  lastManualSyncAt?: number | null;
};

class MemoryTripItStore implements TripItStore {
  rows = new Map<string, MemoryRow>();
  segments = new Map<string, Map<string, SeenSegment & { flightId: string | null }>>();

  get(userId: string) {
    const r = this.rows.get(userId);
    return Promise.resolve(r ? { ...r } : null);
  }
  findByRequestToken(token: string) {
    const r = [...this.rows.values()].find((x) => x.requestToken === token);
    return Promise.resolve(r ? { ...r } : null);
  }
  savePending(userId: string, requestToken: string, sealedSecret: string, at: Date) {
    const prev = this.rows.get(userId);
    this.rows.set(userId, {
      userId,
      accessToken: prev?.accessToken ?? null,
      accessSecret: prev?.accessSecret ?? null,
      lastSyncAt: prev?.lastSyncAt ?? null,
      requestToken,
      requestSecret: sealedSecret,
      requestedAt: at.toISOString(),
      callbackCodeHash: null,
      callbackCodeExpiresAt: null,
    });
    return Promise.resolve();
  }
  issueCallbackCode(requestToken: string, codeHash: string, expiresAt: Date) {
    const r = [...this.rows.values()].find((x) => x.requestToken === requestToken && !x.callbackCodeHash);
    if (!r) return Promise.resolve(false);
    r.callbackCodeHash = codeHash;
    r.callbackCodeExpiresAt = expiresAt.getTime();
    return Promise.resolve(true);
  }
  claimCallbackCode(codeHash: string, now: Date): Promise<ClaimedRequest | null> {
    const r = [...this.rows.values()].find((x) => x.callbackCodeHash === codeHash);
    if (!r) return Promise.resolve(null);
    const fresh = (r.callbackCodeExpiresAt ?? 0) > now.getTime() && r.requestToken && r.requestSecret;
    const out = fresh
      ? { userId: r.userId, requestToken: r.requestToken!, requestSecret: r.requestSecret!, requestedAt: r.requestedAt }
      : null;
    Object.assign(r, {
      requestToken: null,
      requestSecret: null,
      requestedAt: null,
      callbackCodeHash: null,
      callbackCodeExpiresAt: null,
    });
    return Promise.resolve(out);
  }
  clearPending(userId: string, requestToken: string) {
    const r = this.rows.get(userId);
    if (r && r.requestToken === requestToken) {
      Object.assign(r, {
        requestToken: null,
        requestSecret: null,
        requestedAt: null,
        callbackCodeHash: null,
        callbackCodeExpiresAt: null,
      });
    }
    return Promise.resolve();
  }
  claimManualSync(userId: string, now: Date, cooldownMs: number) {
    const r = this.rows.get(userId);
    if (!r?.accessToken) return Promise.resolve(0);
    const last = r.lastManualSyncAt;
    if (last == null || now.getTime() - last >= cooldownMs) {
      r.lastManualSyncAt = now.getTime();
      return Promise.resolve(0);
    }
    return Promise.resolve(Math.max(1, Math.ceil((last + cooldownMs - now.getTime()) / 1000)));
  }
  saveConnected(userId: string, sealedToken: string, sealedSecret: string) {
    const r = this.rows.get(userId)!;
    this.rows.set(userId, {
      ...r,
      requestToken: null,
      requestSecret: null,
      requestedAt: null,
      callbackCodeHash: null,
      callbackCodeExpiresAt: null,
      accessToken: sealedToken,
      accessSecret: sealedSecret,
      lastError: null,
    });
    return Promise.resolve();
  }
  recordSync(userId: string, at: Date, error: string | null) {
    const r = this.rows.get(userId)!;
    this.rows.set(userId, { ...r, lastSyncAt: at.toISOString(), lastError: error });
    return Promise.resolve();
  }
  seenSegments(userId: string) {
    const m = new Map<string, SeenSegment>();
    for (const [k, v] of this.segments.get(userId) ?? []) m.set(k, { outcome: v.outcome, attemptedAt: v.attemptedAt });
    return Promise.resolve(m);
  }
  recordSegment(userId: string, key: string, flightId: string | null, outcome: SegmentOutcome, at: Date) {
    if (!this.segments.has(userId)) this.segments.set(userId, new Map());
    this.segments.get(userId)!.set(key, { flightId, outcome, attemptedAt: at.getTime() });
    return Promise.resolve();
  }
  delete(userId: string) {
    this.rows.delete(userId);
    this.segments.delete(userId);
    return Promise.resolve();
  }
  syncTargets() {
    return Promise.resolve([...this.rows.values()].filter((r) => r.accessToken).map((r) => r.userId));
  }
}

class FakeApi implements TripItApi {
  calls: string[] = [];
  list: unknown = listTrips;
  listError: TripItApiError | null = null;
  accessError: TripItApiError | null = null;
  lastAccess: OAuthToken | null = null;
  requestToken() {
    this.calls.push("request");
    return Promise.resolve({ token: "req-tok", secret: "req-secret" });
  }
  accessToken(request: OAuthToken, verifier?: string | null) {
    this.calls.push(`access:${request.token}:${request.secret}:${verifier ?? ""}`);
    if (this.accessError) return Promise.reject(this.accessError);
    return Promise.resolve({ token: "acc-tok", secret: "acc-secret" });
  }
  listUpcoming(access: OAuthToken) {
    this.calls.push("list");
    this.lastAccess = access;
    if (this.listError) return Promise.reject(this.listError);
    return Promise.resolve(structuredClone(this.list));
  }
}

async function harness(opts: { pro?: boolean; key?: boolean } = {}) {
  const store = new MemoryTripItStore();
  const api = new FakeApi();
  const searches: string[] = [];
  const tracked: string[] = [];
  const logs: string[] = [];
  let t = NOW;
  const results = new Map<string, SearchResult>();
  const deps: TripItDeps = {
    store,
    api,
    cipher: await tokenCipher(opts.key === false ? null : parseTokenKey(KEY_HEX)),
    isPro: () => Promise.resolve(opts.pro ?? true),
    search: (_u, flight, date) => {
      searches.push(`${flight}@${date}`);
      const r = results.get(`${flight}@${date}`);
      if (r) return Promise.resolve(r);
      const [carrier, number] = flight.split(" ");
      const dep = { "6E 2341": "DEL", "AI 864": "BOM", "QP 1101": "BOM", "QP 1102": "GOX" }[flight] ?? "DEL";
      return Promise.resolve({
        ok: true,
        legs: [{ id: `f-${carrier}${number}-${date}`, dep_iata: dep, arr_iata: null }],
      });
    },
    track: (_u, flightId) => {
      const first = !tracked.includes(flightId);
      tracked.push(flightId);
      return Promise.resolve(first ? "added" : "exists");
    },
    now: () => new Date(t),
    log: (m) => logs.push(m),
  };
  return { store, api, deps, searches, tracked, logs, results, advance: (ms: number) => (t += ms) };
}

/** tripit-callback for the pending request token: the one-time code from the app deep link. */
async function callbackCode(h: Awaited<ReturnType<typeof harness>>, token = "req-tok"): Promise<string> {
  const r = await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: token }));
  const m = /^heygate:\/\/tripit\/callback\?code=([A-Za-z0-9_-]{43})$/.exec(r.redirect);
  assert(m, r.redirect);
  return m[1];
}

async function connected(h: Awaited<ReturnType<typeof harness>>) {
  await connectTripIt(h.deps, U, CALLBACK);
  const r = await completeTripIt(h.deps, U, await callbackCode(h));
  assertEquals([r.status, r.body], [200, { connected: true }]);
}

// ---------- parsing ----------

Deno.test("tripit: segments from list/trip JSON (single objects, codeshares, duplicates, gaps)", () => {
  const segs = parseSegments(listTrips);
  assertEquals(segs.map((s) => s.key), [
    "6E2341:2026-12-01:DEL",
    "AI864:2026-12-05:BOM", // operating flight preferred over the UA codeshare; leading zero dropped
    "QP1101:2026-12-20:BOM", // AirObject.Segment as a single object
    "QP1102:2026-12-22:GOX",
  ]);
  assertEquals(segs[0], {
    carrier: "6E",
    number: "2341",
    date: "2026-12-01",
    dep: "DEL",
    arr: "BOM",
    key: "6E2341:2026-12-01:DEL",
  });
  assertEquals(parseSegments({ AirObject: { Segment: { start_airport_code: "DEL" } } }), []);
  assertEquals(parseSegments({}), []);
  assertEquals(parseSegments(null), []);
  // a single AirObject with a numeric flight number
  assertEquals(
    parseSegments({
      AirObject: {
        Segment: {
          StartDateTime: { date: "2026-12-02" },
          start_airport_code: "blr",
          marketing_airline_code: "ix",
          marketing_flight_number: 1234,
        },
      },
    }).map((s) => s.key),
    ["IX1234:2026-12-02:BLR"],
  );
});

Deno.test("tripit: authorize URL carries the request token and the callback", () => {
  assertEquals(
    authorizeUrl("a b", CALLBACK),
    "https://www.tripit.com/oauth/authorize?oauth_token=a%20b&oauth_callback=https%3A%2F%2Fproj.supabase.co%2Ffunctions%2Fv1%2Ftripit-callback",
  );
});

// ---------- token crypto ----------

Deno.test("tripit: tokens are sealed with AES-GCM and opened again; plain fallback without a key", async () => {
  assertEquals(parseTokenKey(undefined), null);
  assertEquals(parseTokenKey("short"), null);
  assertEquals(parseTokenKey(KEY_HEX)?.length, 32);
  assertEquals(parseTokenKey(btoa("x".repeat(32)))?.length, 32);
  const c = await tokenCipher(parseTokenKey(KEY_HEX));
  const a = await c.seal("secret-token"), b = await c.seal("secret-token");
  assertMatch(a, /^v1:/);
  assertNotEquals(a, b, "random IV");
  assert(!a.includes("secret"));
  assertEquals(await c.open(a), "secret-token");
  assertEquals(await c.open("legacy-plain"), "legacy-plain");
  const other = await tokenCipher(parseTokenKey("ff".repeat(32)));
  await assertRejects(() => other.open(a));
  const plain = await tokenCipher(null);
  assertEquals(plain.encrypted, false);
  assertEquals(await plain.seal("t"), "t");
  await assertRejects(() => plain.open(a), Error, "TRIPIT_TOKEN_KEY");
});

// ---------- TripIt REST client ----------

Deno.test("tripit: REST client signs token calls and the list call with OAuth 1.0 HMAC-SHA1", async () => {
  const calls: { url: string; init?: RequestInit }[] = [];
  const f = ((url: string, init?: RequestInit) => {
    calls.push({ url, init });
    if (url.endsWith("/oauth/request_token")) {
      return Promise.resolve(new Response("oauth_token=rt&oauth_token_secret=rs&oauth_callback_confirmed=true"));
    }
    if (url.endsWith("/oauth/access_token")) {
      return Promise.resolve(new Response("oauth_token=at&oauth_token_secret=as"));
    }
    return Promise.resolve(Response.json(listTrips));
  }) as typeof fetch;
  const api = tripItApi({
    consumerKey: "ck",
    consumerSecret: "cs",
    fetch: f,
    baseUrl: "https://api.tripit.test",
    timestamp: () => 1795770000,
    nonce: () => "fixednonce",
  });
  assertEquals(await api.requestToken(), { token: "rt", secret: "rs" });
  assertEquals(await api.accessToken({ token: "rt", secret: "rs" }), { token: "at", secret: "as" });
  const body = await api.listUpcoming({ token: "at", secret: "as" });
  assertEquals(parseSegments(body).length, 4);

  const auth = (i: number) => (calls[i].init?.headers as Record<string, string>).authorization;
  assertEquals(calls[0].init?.method, "POST");
  assertMatch(auth(0), /^OAuth oauth_consumer_key="ck", oauth_nonce="fixednonce", oauth_signature_method="HMAC-SHA1"/);
  assert(!auth(0).includes("oauth_token="), "request-token call has no token");
  assertMatch(auth(1), /oauth_token="rt"/);
  assertEquals(
    calls[2].url,
    "https://api.tripit.test/v1/list/trip/traveler/true/past/false/include_objects/true/format/json",
  );
  // reproduce the list call's signature independently (key "cs&as")
  const base =
    "GET&https%3A%2F%2Fapi.tripit.test%2Fv1%2Flist%2Ftrip%2Ftraveler%2Ftrue%2Fpast%2Ffalse%2Finclude_objects" +
    "%2Ftrue%2Fformat%2Fjson&oauth_consumer_key%3Dck%26oauth_nonce%3Dfixednonce%26oauth_signature_method%3DHMAC-SHA1" +
    "%26oauth_timestamp%3D1795770000%26oauth_token%3Dat%26oauth_version%3D1.0";
  const sig = encodeURIComponent(await hmacSha1Base64("cs&as", base));
  assert(auth(2).includes(`oauth_signature="${sig}"`), auth(2));
});

Deno.test("tripit: REST client errors carry the HTTP status", async () => {
  const api = tripItApi({
    consumerKey: "k",
    consumerSecret: "s",
    fetch: (() => Promise.resolve(new Response("nope", { status: 401 }))) as unknown as typeof fetch,
  });
  const e = await assertRejects(() => api.listUpcoming({ token: "a", secret: "b" }), TripItApiError);
  assertEquals(e.status, 401);
  const e2 = await assertRejects(() => api.requestToken(), TripItApiError);
  assertEquals(e2.status, 401);
  const noToken = tripItApi({
    consumerKey: "k",
    consumerSecret: "s",
    fetch: (() => Promise.resolve(new Response("oauth_problem=x"))) as unknown as typeof fetch,
  });
  assertEquals((await assertRejects(() => noToken.requestToken(), TripItApiError)).status, 502);
});

// ---------- connect / callback ----------

Deno.test("tripit: connect needs Pro and configuration; stores the sealed request secret", async () => {
  assertEquals((await connectTripIt((await harness({ pro: false })).deps, U, CALLBACK)).body, {
    error: "pro_required",
  });
  const unconf = await harness();
  unconf.deps.api = null;
  assertEquals((await connectTripIt(unconf.deps, U, CALLBACK)).status, 503);

  const h = await harness();
  const r = await connectTripIt(h.deps, U, CALLBACK);
  assertEquals(r.status, 200);
  assertEquals((r.body as { authorize_url: string }).authorize_url, authorizeUrl("req-tok", CALLBACK));
  const row = h.store.rows.get(U)!;
  assertEquals(row.requestToken, "req-tok");
  assertMatch(row.requestSecret!, /^v1:/);
  assertEquals(row.accessToken, null);
});

const V = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"; // a second account (the victim in the CSRF tests)

function accessCalls(h: Awaited<ReturnType<typeof harness>>): string[] {
  return h.api.calls.filter((c) => c.startsWith("access:"));
}

Deno.test("tripit: callback never exchanges; it hands the app a one-time code and stores only its hash", async () => {
  const h = await harness();
  await connectTripIt(h.deps, U, CALLBACK);
  const r = await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: "req-tok", oauth_verifier: "v1" }));
  const code = new URL(r.redirect.replace("heygate://", "https://")).searchParams.get("code")!;
  assertMatch(r.redirect, /^heygate:\/\/tripit\/callback\?code=[A-Za-z0-9_-]{43}$/);
  assertEquals(accessCalls(h), [], "no token exchange in the callback");
  const row = h.store.rows.get(U)!;
  assertEquals(row.callbackCodeHash, await hashCallbackCode(code));
  assert(!JSON.stringify(row).includes(code), "the plain code is not stored");
  assertEquals(row.callbackCodeExpiresAt, NOW + CALLBACK_CODE_TTL_MS);
  assertEquals([row.requestToken, row.accessToken], ["req-tok", null], "still pending, not connected");
  // codes are random
  const h2 = await harness();
  await connectTripIt(h2.deps, U, CALLBACK);
  assertNotEquals(await callbackCode(h2), code);
});

Deno.test("tripit: complete by the right user exchanges once and connects", async () => {
  const h = await harness();
  await connectTripIt(h.deps, U, CALLBACK);
  const code = await callbackCode(h);
  const r = await completeTripIt(h.deps, U, code);
  assertEquals([r.status, r.body], [200, { connected: true }]);
  assertEquals(accessCalls(h), ["access:req-tok:req-secret:"]);
  const row = h.store.rows.get(U)!;
  assertEquals([row.requestToken, row.requestSecret, row.callbackCodeHash], [null, null, null]);
  assertMatch(row.accessToken!, /^v1:/);
  assert(!row.accessToken!.includes("acc-tok") && !row.accessSecret!.includes("acc-secret"));
  // single use: the same code again, by the same user
  const again = await completeTripIt(h.deps, U, code);
  assertEquals([again.status, again.body], [410, { error: "expired" }]);
  assertEquals(accessCalls(h).length, 1, "exchanged once");
  // and the request token cannot produce a new code
  assertEquals(
    (await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: "req-tok" }))).redirect,
    "heygate://tripit/failed?error=expired",
  );
});

Deno.test("tripit: login CSRF — complete by another account is 403 and destroys the pending link", async () => {
  // The attacker (U) starts a connect and gets a victim (V) to approve; TripIt sends the VICTIM's browser to the
  // callback, so the code lands in the victim's app, signed in as V.
  const h = await harness();
  await connectTripIt(h.deps, U, CALLBACK);
  const code = await callbackCode(h);
  const r = await completeTripIt(h.deps, V, code);
  assertEquals([r.status, r.body], [403, { error: "not_your_link" }]);
  assertEquals(accessCalls(h), [], "nothing exchanged");
  const row = h.store.rows.get(U)!;
  assertEquals([row.requestToken, row.requestSecret, row.callbackCodeHash, row.accessToken], [null, null, null, null]);
  // the attacker cannot finish with the same code, nor get a new one from the callback
  assertEquals((await completeTripIt(h.deps, U, code)).body, { error: "expired" });
  assertEquals(
    (await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: "req-tok" }))).redirect,
    "heygate://tripit/failed?error=expired",
  );
  assertEquals(accessCalls(h), []);
  assertEquals((await syncTripIt(h.deps, U)).body, { error: "not_connected" });
});

Deno.test("tripit: login CSRF — the link's creator replaying the callback destroys the pending link", async () => {
  // The request token is in the authorize URL, so its creator can call the callback himself. Only the first
  // callback gets a code; any second one kills the link, whichever comes first.
  const h = await harness();
  await connectTripIt(h.deps, U, CALLBACK);
  const victimsCode = await callbackCode(h); // the victim approved; their browser got the code
  assertEquals(
    (await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: "req-tok" }))).redirect,
    "heygate://tripit/failed?error=expired",
  );
  assertEquals(h.store.rows.get(U)!.requestToken, null);
  assertEquals((await completeTripIt(h.deps, U, victimsCode)).body, { error: "expired" });
  assertEquals(accessCalls(h), []);
});

Deno.test("tripit: an expired code is rejected and kills the pending link; bad codes are refused", async () => {
  const h = await harness();
  await connectTripIt(h.deps, U, CALLBACK);
  const code = await callbackCode(h);
  h.advance(CALLBACK_CODE_TTL_MS + 1000);
  assertEquals((await completeTripIt(h.deps, U, code)).body, { error: "expired" });
  assertEquals(h.store.rows.get(U)!.requestToken, null);
  assertEquals(accessCalls(h), []);
  for (const bad of [undefined, null, 42, "", "short", "x".repeat(43) + "!", "a".repeat(44)]) {
    assertEquals((await completeTripIt(h.deps, U, bad)).status, 400, String(bad));
  }
  assertEquals((await completeTripIt(h.deps, U, "A".repeat(43))).body, { error: "expired" }, "unknown code");
  const unconf = await harness();
  unconf.deps.api = null;
  assertEquals((await completeTripIt(unconf.deps, U, code)).status, 503);
});

Deno.test("tripit: callback failures redirect with a reason", async () => {
  const h = await harness();
  assertEquals((await callbackTripIt(h.deps, new URLSearchParams())).redirect, "heygate://tripit/failed?error=denied");
  assertEquals(
    (await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: "unknown" }))).redirect,
    "heygate://tripit/failed?error=expired",
  );
  await connectTripIt(h.deps, U, CALLBACK);
  h.advance(31 * 60_000);
  assertEquals(
    (await callbackTripIt(h.deps, new URLSearchParams({ oauth_token: "req-tok" }))).redirect,
    "heygate://tripit/failed?error=expired",
  );
  assertEquals(h.store.rows.get(U)!.requestToken, null, "an old request token is forgotten");
  const unconf = await harness();
  unconf.deps.api = null;
  assertEquals(
    (await callbackTripIt(unconf.deps, new URLSearchParams({ oauth_token: "x" }))).redirect,
    "heygate://tripit/failed?error=not_configured",
  );
  assertEquals(accessCalls(h), []);
});

Deno.test("tripit: exchange failures in complete — denied and TripIt errors; the code is spent either way", async () => {
  const h = await harness();
  await connectTripIt(h.deps, U, CALLBACK);
  h.api.accessError = new TripItApiError(401, "denied");
  const code = await callbackCode(h);
  assertEquals((await completeTripIt(h.deps, U, code)).body, { error: "denied" });
  assertEquals((await completeTripIt(h.deps, U, code)).body, { error: "expired" });

  await connectTripIt(h.deps, U, CALLBACK);
  h.api.accessError = new TripItApiError(500, "boom");
  const r = await completeTripIt(h.deps, U, await callbackCode(h));
  assertEquals([r.status, r.body], [502, { error: "tripit_error" }]);
  assertEquals(h.store.rows.get(U)!.accessToken, null);
});

Deno.test("tripit: a new connect replaces an unredeemed code; a connected user keeps the access token", async () => {
  const h = await harness();
  await connected(h);
  await connectTripIt(h.deps, U, CALLBACK);
  const stale = await callbackCode(h);
  await connectTripIt(h.deps, U, CALLBACK); // started again before finishing
  assertEquals(h.store.rows.get(U)!.callbackCodeHash, null);
  assertEquals((await completeTripIt(h.deps, U, stale)).body, { error: "expired" });
  // someone else's code attempt on a reconnect never touches the existing access token
  const code = await callbackCode(h);
  assertEquals((await completeTripIt(h.deps, V, code)).body, { error: "not_your_link" });
  assertMatch(h.store.rows.get(U)!.accessToken!, /^v1:/);
});

Deno.test("tripit: the callback page carries the deep link as a button, escaped", () => {
  const ok = callbackPage("heygate://tripit/callback?code=abc_DEF-123");
  assert(ok.includes('href="heygate://tripit/callback?code=abc_DEF-123"'));
  assert(ok.includes("Return to the HeyGate app"));
  const failed = callbackPage('heygate://tripit/failed?error=x"><script>');
  assert(!failed.includes("<script>") && failed.includes("&quot;&gt;&lt;script&gt;"));
  assert(failed.includes("could not be connected"));
});

// ---------- sync ----------

Deno.test("tripit: sync tracks each upcoming segment once with the right leg; re-sync is idempotent", async () => {
  const h = await harness();
  await connected(h);
  const r = await syncTripIt(h.deps, U);
  assertEquals(r.status, 200);
  assertEquals(r.body, { segments: 4, added: 4, existing: 0, not_found: 0, skipped: 0, errors: 0 } satisfies SyncStats);
  assertEquals(h.searches, ["6E 2341@2026-12-01", "AI 864@2026-12-05", "QP 1101@2026-12-20", "QP 1102@2026-12-22"]);
  assertEquals(h.api.lastAccess, { token: "acc-tok", secret: "acc-secret" }, "tokens decrypted for the call");
  assertEquals(h.store.rows.get(U)!.lastSyncAt, new Date(NOW).toISOString());

  h.advance(3600_000);
  const again = await syncTripIt(h.deps, U);
  assertEquals(again.body, { segments: 4, added: 0, existing: 0, not_found: 0, skipped: 4, errors: 0 });
  assertEquals(h.searches.length, 4, "no new searches");
});

Deno.test("tripit: already tracked, not found, wrong airport, provider errors and the search quota", async () => {
  const h = await harness();
  await connected(h);
  h.tracked.push("f-6E2341-2026-12-01"); // user added it by hand
  h.results.set("AI 864@2026-12-05", { ok: false, error: "not_found" });
  h.results.set("QP 1101@2026-12-20", { ok: true, legs: [{ id: "x", dep_iata: "DEL", arr_iata: "BOM" }] });
  h.results.set("QP 1102@2026-12-22", { ok: false, error: "provider_error" });
  const r = await syncTripIt(h.deps, U);
  assertEquals(r.body, { segments: 4, added: 0, existing: 1, not_found: 2, skipped: 0, errors: 1 });

  // not_found is retried after a day, errors after an hour, "exists" never
  h.results.clear();
  h.advance(2 * 3600_000);
  const r2 = await syncTripIt(h.deps, U);
  assertEquals(r2.body, { segments: 4, added: 1, existing: 0, not_found: 0, skipped: 3, errors: 0 });
  h.advance(23 * 3600_000);
  const r3 = await syncTripIt(h.deps, U);
  assertEquals(r3.body, { segments: 4, added: 2, existing: 0, not_found: 0, skipped: 2, errors: 0 });

  // rate limited: stop searching; nothing recorded so the next run retries
  const q = await harness();
  await connected(q);
  q.results.set("6E 2341@2026-12-01", { ok: false, error: "rate_limited" });
  const rq = await syncTripIt(q.deps, U);
  assertEquals(rq.body, { segments: 4, added: 0, existing: 0, not_found: 0, skipped: 4, errors: 0 });
  assertEquals(q.searches.length, 1);
  assertEquals(q.store.segments.get(U), undefined);
});

Deno.test("tripit: past and far-future segments are ignored; at most 25 new segments per run", async () => {
  const h = await harness();
  await connected(h);
  const seg = (date: string, n: number) => ({
    StartDateTime: { date },
    start_airport_code: "DEL",
    marketing_airline_code: "6E",
    marketing_flight_number: String(n),
  });
  h.api.list = {
    AirObject: {
      Segment: [
        seg("2026-11-20", 1),
        seg("2027-12-01", 2),
        ...Array.from({ length: 30 }, (_, i) => seg("2026-12-10", 100 + i)),
      ],
    },
  };
  const r = await syncTripIt(h.deps, U);
  assertEquals((r.body as SyncStats).segments, 30);
  assertEquals((r.body as SyncStats).added, MAX_SEGMENTS_PER_SYNC);
  assertEquals((r.body as SyncStats).skipped, 5);
  h.advance(3600_000);
  assertEquals(((await syncTripIt(h.deps, U)).body as SyncStats).added, 5);
});

Deno.test("tripit: sync errors — not Pro, not connected, revoked token, TripIt down", async () => {
  assertEquals((await syncTripIt((await harness({ pro: false })).deps, U)).body, { error: "pro_required" });
  const h = await harness();
  assertEquals((await syncTripIt(h.deps, U)).body, { error: "not_connected" });
  await connectTripIt(h.deps, U, CALLBACK); // pending only
  assertEquals((await syncTripIt(h.deps, U)).status, 409);
  await completeTripIt(h.deps, U, await callbackCode(h));
  h.api.listError = new TripItApiError(401, "revoked");
  const r = await syncTripIt(h.deps, U);
  assertEquals([r.status, r.body], [409, { error: "tripit_revoked" }]);
  assertEquals(h.store.rows.get(U)!.lastError, "tripit_revoked");
  h.api.listError = new TripItApiError(503, "down");
  assertEquals((await syncTripIt(h.deps, U)).body, { error: "tripit_error" });
  h.api.listError = null;
  await syncTripIt(h.deps, U);
  assertEquals(h.store.rows.get(U)!.lastError, null, "a good sync clears the error");
});

Deno.test("tripit: works with plain-text token storage when TRIPIT_TOKEN_KEY is missing", async () => {
  const h = await harness({ key: false });
  await connected(h);
  assertEquals(h.store.rows.get(U)!.accessToken, "acc-tok");
  assertEquals((await syncTripIt(h.deps, U)).status, 200);
});

Deno.test("tripit: cron syncs every due user; disconnect forgets the connection and segments", async () => {
  const h = await harness();
  await connected(h);
  await h.store.savePending(V, "other", "x", new Date(NOW)); // not connected: not a target
  const out = await cronSyncTripIt(h.deps, 50_000);
  assertEquals(out, { users: 1, synced: 1, failed: 0, added: 4 });

  const budget = await cronSyncTripIt(h.deps, -1);
  assertEquals(budget.users, 0, "time budget respected");

  assertEquals((await disconnectTripIt(h.deps, U)).body, { ok: true });
  assertEquals(h.store.rows.has(U), false);
  assertEquals(h.store.segments.has(U), false);
  const none = await harness();
  none.deps.api = null;
  assertEquals(await cronSyncTripIt(none.deps), { users: 0, synced: 0, failed: 0, added: 0 });
});

// ---------- manual sync cooldown ----------

Deno.test("tripit: Sync now has a 60 s per-user cooldown; cron and the first sync are not limited", async () => {
  const h = await harness();
  await connected(h);
  assertEquals((await syncTripIt(h.deps, U, { manual: true })).status, 200);
  h.advance(20_000);
  const r = await syncTripIt(h.deps, U, { manual: true });
  assertEquals([r.status, r.body], [429, { error: "cooldown", retry_after_s: 40, message: "retry_after_s=40" }]);
  assertEquals(h.api.calls.filter((c) => c === "list").length, 1, "no TripIt call while cooling down");
  // the cron path and the background first sync ignore the cooldown
  assertEquals((await syncTripIt(h.deps, U)).status, 200);
  assertEquals((await cronSyncTripIt(h.deps)).synced, 1);
  h.advance(39_500);
  assertEquals(((await syncTripIt(h.deps, U, { manual: true })).body as { retry_after_s: number }).retry_after_s, 1);
  h.advance(500);
  assertEquals((await syncTripIt(h.deps, U, { manual: true })).status, 200, "60 s later");
  assertEquals(MANUAL_SYNC_COOLDOWN_MS, 60_000);
  // per user: another connected user is not affected
  const other = await harness();
  await connected(other);
  assertEquals((await syncTripIt(other.deps, U, { manual: true })).status, 200);
  // not connected / not Pro answer as before (no cooldown spent)
  const none = await harness();
  assertEquals((await syncTripIt(none.deps, U, { manual: true })).body, { error: "not_connected" });
  assertEquals((await syncTripIt((await harness({ pro: false })).deps, U, { manual: true })).body, {
    error: "pro_required",
  });
});
