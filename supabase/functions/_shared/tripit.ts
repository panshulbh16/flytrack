// TripIt sync (docs/P4_CONTRACTS.md section 2). TripIt API v1 with OAuth 1.0 (HMAC-SHA1, ./oauth1.ts).
// Pure logic behind small ports (store, TripIt API, flight search, tracking) so the whole flow — connect,
// callback, sync, cron, disconnect — is unit-tested with recorded TripIt JSON and no network.
//
// Tokens: the request secret, access token and access secret are encrypted with AES-256-GCM using the
// function secret TRIPIT_TOKEN_KEY (32 bytes, hex or base64) and stored as "v1:<base64(iv|ciphertext)>".
// Without the key they are stored as given, in the service-role-only table (contract fallback).
import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { parseFormEncoded, signRequest } from "./oauth1.ts";

export const TRIPIT_API_BASE = "https://api.tripit.com";
export const TRIPIT_AUTHORIZE_URL = "https://www.tripit.com/oauth/authorize";
export const TRIPIT_LIST_PATH = "/v1/list/trip/traveler/true/past/false/include_objects/true/format/json";
export const APP_CONNECTED_URL = "heygate://tripit/connected";
export const APP_FAILED_URL = "heygate://tripit/failed";
/** tripit-callback hands the one-time code to the app here; the app finishes with tripit-complete. */
export const APP_CALLBACK_URL = "heygate://tripit/callback";

/** A request token older than this can no longer be exchanged. */
export const REQUEST_TOKEN_TTL_MS = 30 * 60_000;
/** How long the one-time code from tripit-callback can be redeemed with tripit-complete. */
export const CALLBACK_CODE_TTL_MS = 10 * 60_000;
/** "Sync now": at most one manual sync per user in this window (the hourly cron is not limited). */
export const MANUAL_SYNC_COOLDOWN_MS = 60_000;
/** Segments handled per sync (each new one can cost a flight search). */
export const MAX_SEGMENTS_PER_SYNC = 25;
/** Searches a sync may start per user and day (shared with search-flight's quota). */
export const SEARCH_LIMIT = 30;
const NOT_FOUND_RETRY_MS = 24 * 3600_000;
const ERROR_RETRY_MS = 3600_000;
const DAY_MS = 86_400_000;
const TIMEOUT_MS = 15_000;

export type FetchFn = typeof fetch;

// ---------- errors / results ---------------------------------------------------------------------

export type TripItErrorCode =
  | "unauthorized"
  | "pro_required"
  | "not_configured"
  | "not_connected"
  | "tripit_revoked"
  | "tripit_error"
  | "bad_request"
  | "expired"
  | "not_your_link"
  | "denied"
  | "cooldown";

export const TRIPIT_STATUS: Record<TripItErrorCode, number> = {
  unauthorized: 401,
  pro_required: 402,
  not_configured: 503,
  not_connected: 409,
  tripit_revoked: 409,
  tripit_error: 502,
  bad_request: 400,
  expired: 410,
  not_your_link: 403,
  denied: 409,
  cooldown: 429,
};

export class TripItApiError extends Error {
  constructor(public readonly status: number, msg: string) {
    super(msg);
  }
}

export interface TripItErrorBody {
  error: TripItErrorCode;
  /** cooldown only: seconds until "Sync now" works again (also in `message` as "retry_after_s=<n>"). */
  retry_after_s?: number;
  message?: string;
}

export interface HttpResult<T = Record<string, unknown>> {
  status: number;
  body: T | TripItErrorBody;
}

function fail(code: TripItErrorCode): HttpResult<never> {
  return { status: TRIPIT_STATUS[code], body: { error: code } };
}

// ---------- token encryption ---------------------------------------------------------------------

export interface TokenCipher {
  readonly encrypted: boolean;
  seal(plain: string): Promise<string>;
  open(stored: string): Promise<string>;
}

function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function unb64(s: string): Uint8Array<ArrayBuffer> {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** 32-byte key from 64 hex characters or base64; null when absent or malformed. */
export function parseTokenKey(raw: string | undefined): Uint8Array<ArrayBuffer> | null {
  const v = raw?.trim();
  if (!v) return null;
  if (/^[0-9a-f]{64}$/i.test(v)) return new Uint8Array(v.match(/../g)!.map((h) => parseInt(h, 16)));
  try {
    const k = unb64(v);
    return k.length === 32 ? k : null;
  } catch {
    return null;
  }
}

export async function tokenCipher(rawKey: Uint8Array<ArrayBuffer> | null): Promise<TokenCipher> {
  if (!rawKey) {
    return {
      encrypted: false,
      seal: (p) => Promise.resolve(p),
      open: (s) => {
        if (s.startsWith("v1:")) return Promise.reject(new Error("TRIPIT_TOKEN_KEY is needed to read stored tokens"));
        return Promise.resolve(s);
      },
    };
  }
  const key = await crypto.subtle.importKey("raw", rawKey, "AES-GCM", false, ["encrypt", "decrypt"]);
  return {
    encrypted: true,
    async seal(plain) {
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const ct = new Uint8Array(
        await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(plain)),
      );
      const all = new Uint8Array(iv.length + ct.length);
      all.set(iv);
      all.set(ct, iv.length);
      return `v1:${b64(all)}`;
    },
    async open(stored) {
      if (!stored.startsWith("v1:")) return stored; // written before the key was set
      const all = unb64(stored.slice(3));
      const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: all.slice(0, 12) }, key, all.slice(12));
      return new TextDecoder().decode(pt);
    },
  };
}

// ---------- TripIt API ---------------------------------------------------------------------------

export interface OAuthToken {
  token: string;
  secret: string;
}

export interface TripItApi {
  requestToken(): Promise<OAuthToken>;
  accessToken(request: OAuthToken, verifier?: string | null): Promise<OAuthToken>;
  /** GET /v1/list/trip/traveler/true/past/false/include_objects/true/format/json */
  listUpcoming(access: OAuthToken): Promise<unknown>;
}

export interface TripItApiOptions {
  consumerKey: string;
  consumerSecret: string;
  fetch?: FetchFn;
  baseUrl?: string;
  /** Seconds; injectable for tests. */
  timestamp?: () => number;
  nonce?: () => string;
}

export function tripItApi(opts: TripItApiOptions): TripItApi {
  const f = opts.fetch ?? fetch;
  const base = (opts.baseUrl ?? TRIPIT_API_BASE).replace(/\/+$/, "");
  const sigOpts = () => ({ timestamp: opts.timestamp?.(), nonce: opts.nonce?.() });

  async function tokenCall(path: string, token?: OAuthToken, verifier?: string | null): Promise<OAuthToken> {
    const url = `${base}${path}`;
    const signed = await signRequest(
      {
        consumerKey: opts.consumerKey,
        consumerSecret: opts.consumerSecret,
        token: token?.token,
        tokenSecret: token?.secret,
      },
      { method: "POST", url, extraOAuth: verifier ? { oauth_verifier: verifier } : undefined },
      sigOpts(),
    );
    const res = await f(url, {
      method: "POST",
      headers: { authorization: signed.authorization, "content-type": "application/x-www-form-urlencoded" },
      body: "",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const text = await res.text();
    if (!res.ok) throw new TripItApiError(res.status, `TripIt ${path} HTTP ${res.status}`);
    const form = parseFormEncoded(text);
    if (!form.oauth_token || !form.oauth_token_secret) throw new TripItApiError(502, `TripIt ${path}: no token`);
    return { token: form.oauth_token, secret: form.oauth_token_secret };
  }

  return {
    requestToken: () => tokenCall("/oauth/request_token"),
    accessToken: (request, verifier) => tokenCall("/oauth/access_token", request, verifier),
    async listUpcoming(access) {
      const url = `${base}${TRIPIT_LIST_PATH}`;
      const signed = await signRequest(
        {
          consumerKey: opts.consumerKey,
          consumerSecret: opts.consumerSecret,
          token: access.token,
          tokenSecret: access.secret,
        },
        { method: "GET", url },
        sigOpts(),
      );
      const res = await f(url, {
        headers: { authorization: signed.authorization, accept: "application/json" },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!res.ok) {
        await res.body?.cancel();
        throw new TripItApiError(res.status, `TripIt list trips HTTP ${res.status}`);
      }
      try {
        return await res.json();
      } catch {
        throw new TripItApiError(502, "TripIt list trips: bad JSON");
      }
    },
  };
}

export function authorizeUrl(requestToken: string, callbackUrl: string): string {
  return `${TRIPIT_AUTHORIZE_URL}?oauth_token=${encodeURIComponent(requestToken)}&oauth_callback=${
    encodeURIComponent(callbackUrl)
  }`;
}

// ---------- segments -----------------------------------------------------------------------------

export interface TripItSegment {
  carrier: string;
  number: string;
  /** Local departure date, YYYY-MM-DD. */
  date: string;
  dep: string;
  arr: string | null;
  /** tripit_segments.segment_key: "6E2341:2026-12-01:DEL". */
  key: string;
}

/** TripIt sends a single object where a list has one element. */
function asList<T>(v: T | T[] | null | undefined): T[] {
  if (v == null) return [];
  return Array.isArray(v) ? v : [v];
}

function flightCode(carrier: unknown, number: unknown): { carrier: string; number: string } | null {
  if (typeof carrier !== "string" || (typeof number !== "string" && typeof number !== "number")) return null;
  const c = carrier.trim().toUpperCase();
  const m = /^0*(\d{1,4})([A-Z]?)$/.exec(String(number).trim().toUpperCase());
  if (!/^[A-Z0-9]{2}$/.test(c) || /^\d\d$/.test(c) || !m || Number(m[1]) === 0) return null;
  return { carrier: c, number: `${Number(m[1])}${m[2]}` };
}

/**
 * Flight segments of a list/trip response. The operating flight is preferred (it is what flies and what
 * the flight provider knows); the marketing number is the fallback. Segments without an airline code,
 * flight number, date or IATA departure airport are skipped. De-duplicated by key, in date order.
 */
export function parseSegments(body: unknown): TripItSegment[] {
  const root = body as { AirObject?: unknown } | null;
  const out = new Map<string, TripItSegment>();
  for (const air of asList(root?.AirObject as Record<string, unknown> | Record<string, unknown>[] | undefined)) {
    for (const seg of asList(air?.Segment as Record<string, unknown> | Record<string, unknown>[] | undefined)) {
      if (!seg || typeof seg !== "object") continue;
      const code = flightCode(seg.operating_airline_code, seg.operating_flight_number) ??
        flightCode(seg.marketing_airline_code, seg.marketing_flight_number);
      const start = seg.StartDateTime as { date?: unknown } | undefined;
      const date = typeof start?.date === "string" ? start.date.trim() : "";
      const dep = typeof seg.start_airport_code === "string" ? seg.start_airport_code.trim().toUpperCase() : "";
      const arrRaw = typeof seg.end_airport_code === "string" ? seg.end_airport_code.trim().toUpperCase() : "";
      if (!code || !/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^[A-Z]{3}$/.test(dep)) continue;
      const key = `${code.carrier}${code.number}:${date}:${dep}`;
      out.set(key, { ...code, date, dep, arr: /^[A-Z]{3}$/.test(arrRaw) ? arrRaw : null, key });
    }
  }
  return [...out.values()].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.key < b.key ? -1 : 1));
}

// ---------- storage port -------------------------------------------------------------------------

export interface TripItConnection {
  userId: string;
  requestToken: string | null;
  requestSecret: string | null;
  requestedAt: string | null;
  accessToken: string | null;
  accessSecret: string | null;
  lastSyncAt: string | null;
  /** sha256 hex of the one-time code tripit-callback issued for the pending request token, if any. */
  callbackCodeHash: string | null;
}

/** The pending request a callback code stood for (tripit_claim_callback). */
export interface ClaimedRequest {
  userId: string;
  requestToken: string;
  requestSecret: string;
  requestedAt: string | null;
}

export type SegmentOutcome = "added" | "exists" | "not_found" | "limit" | "error";

export interface SeenSegment {
  outcome: SegmentOutcome;
  attemptedAt: number;
}

export interface TripItStore {
  get(userId: string): Promise<TripItConnection | null>;
  findByRequestToken(token: string): Promise<TripItConnection | null>;
  /** Stores a new request token; an existing access token stays until the exchange succeeds. */
  savePending(userId: string, requestToken: string, sealedSecret: string, at: Date): Promise<void>;
  /**
   * Stores sha256(code) + expiry on the row whose pending request token is [requestToken], only while that row
   * has no code yet. False when no row was changed (unknown token, or a code was already issued).
   */
  issueCallbackCode(requestToken: string, codeHash: string, expiresAt: Date): Promise<boolean>;
  /**
   * Atomically clears the pending request token and the code of the row holding [codeHash] (single use), and
   * returns the pending request when the code had not expired at [now]; null otherwise.
   */
  claimCallbackCode(codeHash: string, now: Date): Promise<ClaimedRequest | null>;
  /** Forgets the pending request token [requestToken] of [userId] (and its code); the access token stays. */
  clearPending(userId: string, requestToken: string): Promise<void>;
  saveConnected(userId: string, sealedToken: string, sealedSecret: string, at: Date): Promise<void>;
  /** "Sync now" cooldown: 0 = go (and the window starts now), else the seconds left. */
  claimManualSync(userId: string, now: Date, cooldownMs: number): Promise<number>;
  recordSync(userId: string, at: Date, error: string | null): Promise<void>;
  seenSegments(userId: string): Promise<Map<string, SeenSegment>>;
  recordSegment(userId: string, key: string, flightId: string | null, outcome: SegmentOutcome, at: Date): Promise<void>;
  delete(userId: string): Promise<void>;
  syncTargets(limit: number): Promise<string[]>;
}

export type SearchResult =
  | { ok: true; legs: { id: string; dep_iata: string; arr_iata: string | null }[] }
  | { ok: false; error: "bad_input" | "rate_limited" | "not_found" | "provider_error" };

export interface TripItDeps {
  store: TripItStore;
  /** null when TRIPIT_CONSUMER_KEY / TRIPIT_CONSUMER_SECRET are not set. */
  api: TripItApi | null;
  cipher: TokenCipher;
  isPro(userId: string): Promise<boolean>;
  search(userId: string, flight: string, date: string): Promise<SearchResult>;
  track(userId: string, flightId: string): Promise<"added" | "exists" | "limit">;
  now: () => Date;
  log: (msg: string, extra?: Record<string, unknown>) => void;
}

// ---------- connect / callback / disconnect ------------------------------------------------------

export async function connectTripIt(
  deps: TripItDeps,
  userId: string,
  callbackUrl: string,
): Promise<HttpResult<{ authorize_url: string }>> {
  if (!deps.api) return fail("not_configured");
  if (!await deps.isPro(userId)) return fail("pro_required");
  let req: OAuthToken;
  try {
    req = await deps.api.requestToken();
  } catch (e) {
    deps.log("tripit: request token failed", { error: errText(e) });
    return fail("tripit_error");
  }
  await deps.store.savePending(userId, req.token, await deps.cipher.seal(req.secret), deps.now());
  return { status: 200, body: { authorize_url: authorizeUrl(req.token, callbackUrl) } };
}

// One-time code: 32 random bytes, base64url without padding (43 characters).
const CODE_RE = /^[A-Za-z0-9_-]{43}$/;

export function newCallbackCode(): string {
  return b64(crypto.getRandomValues(new Uint8Array(32))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function hashCallbackCode(code: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(code)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * tripit-callback (public GET from the browser TripIt redirected). It NEVER exchanges the request token: TripIt
 * sends no oauth_verifier, so this request proves nothing about who approved. It issues a one-time code for the
 * pending row and sends the browser to the app (heygate://tripit/callback?code=...), where the signed-in user
 * finishes with tripit-complete. A second callback for the same request token destroys the pending link (the
 * token is in the authorize URL, so its creator could otherwise fetch a code after someone else approved).
 */
export async function callbackTripIt(
  deps: TripItDeps,
  params: URLSearchParams,
): Promise<{ redirect: string; code?: string }> {
  const failed = (reason: string) => ({ redirect: `${APP_FAILED_URL}?error=${encodeURIComponent(reason)}` });
  const token = params.get("oauth_token")?.trim();
  if (!deps.api) return failed("not_configured");
  if (!token || token.length > 200) return failed("denied");
  const row = await deps.store.findByRequestToken(token);
  if (!row || !row.requestSecret) return failed("expired");
  const at = row.requestedAt ? Date.parse(row.requestedAt) : NaN;
  if (Number.isNaN(at) || deps.now().getTime() - at > REQUEST_TOKEN_TTL_MS) {
    await deps.store.clearPending(row.userId, token);
    return failed("expired");
  }
  if (row.callbackCodeHash) {
    deps.log("tripit: second callback for a request token; pending link destroyed", { userId: row.userId });
    await deps.store.clearPending(row.userId, token);
    return failed("expired");
  }
  const code = newCallbackCode();
  const issued = await deps.store.issueCallbackCode(
    token,
    await hashCallbackCode(code),
    new Date(deps.now().getTime() + CALLBACK_CODE_TTL_MS),
  );
  if (!issued) {
    // a parallel callback won the race: same rule as a second callback
    await deps.store.clearPending(row.userId, token);
    return failed("expired");
  }
  return { redirect: `${APP_CALLBACK_URL}?code=${code}`, code };
}

/**
 * tripit-complete (POST {code}, user JWT): redeems the one-time code from tripit-callback. The code is claimed
 * (and the pending request token destroyed) before anything else, so it works once whoever sends it. Only the
 * user who started the connect gets the exchange; anyone else gets not_your_link and the link is gone.
 */
export async function completeTripIt(
  deps: TripItDeps,
  userId: string,
  code: unknown,
): Promise<HttpResult<{ connected: true }>> {
  if (!deps.api) return fail("not_configured");
  if (typeof code !== "string" || !CODE_RE.test(code)) return fail("bad_request");
  const claimed = await deps.store.claimCallbackCode(await hashCallbackCode(code), deps.now());
  if (!claimed) return fail("expired");
  if (claimed.userId !== userId) {
    deps.log("tripit: callback code redeemed by another account; pending link destroyed", {
      owner: claimed.userId,
      caller: userId,
    });
    return fail("not_your_link");
  }
  let access: OAuthToken;
  try {
    const secret = await deps.cipher.open(claimed.requestSecret);
    access = await deps.api.accessToken({ token: claimed.requestToken, secret }, null);
  } catch (e) {
    deps.log("tripit: access token failed", { error: errText(e) });
    return fail(e instanceof TripItApiError && e.status === 401 ? "denied" : "tripit_error");
  }
  await deps.store.saveConnected(
    userId,
    await deps.cipher.seal(access.token),
    await deps.cipher.seal(access.secret),
    deps.now(),
  );
  return { status: 200, body: { connected: true } };
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

/** The body of tripit-callback's 302: the same deep link as a button. */
export function callbackPage(to: string): string {
  const ok = to.startsWith("heygate://tripit/callback");
  const href = escapeHtml(to);
  const text = ok
    ? "Return to the HeyGate app to finish connecting TripIt."
    : "TripIt could not be connected. Return to the HeyGate app and try again.";
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>HeyGate</title></head><body style="font-family:system-ui,sans-serif;text-align:center;padding:48px 16px">` +
    `<p>${text}</p><p><a href="${href}" style="display:inline-block;padding:12px 20px;border-radius:8px;` +
    `background:#1a56db;color:#fff;text-decoration:none">Open HeyGate</a></p></body></html>`;
}

export async function disconnectTripIt(deps: TripItDeps, userId: string): Promise<HttpResult<{ ok: true }>> {
  await deps.store.delete(userId);
  return { status: 200, body: { ok: true } };
}

// ---------- sync ---------------------------------------------------------------------------------

export interface SyncStats {
  segments: number;
  added: number;
  existing: number;
  not_found: number;
  skipped: number;
  errors: number;
}

function isoDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

function shouldSkip(seen: SeenSegment | undefined, nowMs: number): boolean {
  if (!seen) return false;
  if (seen.outcome === "added" || seen.outcome === "exists") return true;
  if (seen.outcome === "not_found") return nowMs - seen.attemptedAt < NOT_FOUND_RETRY_MS;
  if (seen.outcome === "error") return nowMs - seen.attemptedAt < ERROR_RETRY_MS;
  return false; // limit: try again next time
}

/** Picks the searched leg that departs from the segment's airport (multi-leg flight numbers). */
export function pickLeg(legs: { id: string; dep_iata: string }[], dep: string): string | null {
  const hit = legs.find((l) => l.dep_iata?.toUpperCase() === dep);
  if (hit) return hit.id;
  return null;
}

/**
 * One user's sync. [opts.manual] ("Sync now" from the app) is limited to one per MANUAL_SYNC_COOLDOWN_MS:
 * 429 cooldown with retry_after_s. The cron path and the first sync after connecting are not limited.
 */
export async function syncTripIt(
  deps: TripItDeps,
  userId: string,
  opts: { manual?: boolean } = {},
): Promise<HttpResult<SyncStats>> {
  if (!deps.api) return fail("not_configured");
  if (!await deps.isPro(userId)) return fail("pro_required");
  const row = await deps.store.get(userId);
  if (!row?.accessToken || !row.accessSecret) return fail("not_connected");
  const now = deps.now();
  if (opts.manual) {
    const wait = await deps.store.claimManualSync(userId, now, MANUAL_SYNC_COOLDOWN_MS);
    if (wait > 0) {
      return {
        status: TRIPIT_STATUS.cooldown,
        body: { error: "cooldown", retry_after_s: wait, message: `retry_after_s=${wait}` },
      };
    }
  }

  let body: unknown;
  try {
    const access = { token: await deps.cipher.open(row.accessToken), secret: await deps.cipher.open(row.accessSecret) };
    body = await deps.api.listUpcoming(access);
  } catch (e) {
    const revoked = e instanceof TripItApiError && (e.status === 401 || e.status === 403);
    deps.log("tripit: list trips failed", { userId, error: errText(e) });
    await deps.store.recordSync(userId, now, revoked ? "tripit_revoked" : "tripit_error");
    return fail(revoked ? "tripit_revoked" : "tripit_error");
  }

  const from = isoDay(now.getTime() - DAY_MS), to = isoDay(now.getTime() + 330 * DAY_MS);
  const all = parseSegments(body).filter((s) => s.date >= from && s.date <= to);
  const seen = await deps.store.seenSegments(userId);
  const stats: SyncStats = { segments: all.length, added: 0, existing: 0, not_found: 0, skipped: 0, errors: 0 };
  let handled = 0;
  let rateLimited = false;

  for (const seg of all) {
    if (shouldSkip(seen.get(seg.key), now.getTime())) {
      stats.skipped++;
      continue;
    }
    if (handled >= MAX_SEGMENTS_PER_SYNC || rateLimited) {
      stats.skipped++; // next run
      continue;
    }
    handled++;
    let outcome: SegmentOutcome;
    let flightId: string | null = null;
    try {
      const found = await deps.search(userId, `${seg.carrier} ${seg.number}`, seg.date);
      if (!found.ok) {
        if (found.error === "rate_limited") {
          rateLimited = true;
          stats.skipped++;
          continue; // not recorded: retried on the next run
        }
        outcome = found.error === "provider_error" ? "error" : "not_found";
      } else {
        flightId = pickLeg(found.legs, seg.dep);
        outcome = flightId ? await deps.track(userId, flightId) : "not_found";
      }
    } catch (e) {
      deps.log("tripit: segment failed", { userId, segment: seg.key, error: errText(e) });
      outcome = "error";
    }
    if (outcome === "added") stats.added++;
    else if (outcome === "exists") stats.existing++;
    else if (outcome === "not_found") stats.not_found++;
    else stats.errors++;
    await deps.store.recordSegment(userId, seg.key, flightId, outcome, now);
  }

  await deps.store.recordSync(userId, now, null);
  return { status: 200, body: stats };
}

/** The hourly cron run: due users one by one until the time budget is used up. */
export async function cronSyncTripIt(
  deps: TripItDeps,
  budgetMs = 50_000,
  clock: () => number = Date.now,
): Promise<{ users: number; synced: number; failed: number; added: number }> {
  const started = clock();
  const out = { users: 0, synced: 0, failed: 0, added: 0 };
  if (!deps.api) return out;
  for (const userId of await deps.store.syncTargets(50)) {
    if (clock() - started > budgetMs) break;
    out.users++;
    try {
      const r = await syncTripIt(deps, userId);
      if (r.status === 200) {
        out.synced++;
        out.added += (r.body as SyncStats).added;
      } else out.failed++;
    } catch (e) {
      out.failed++;
      deps.log("tripit: cron sync failed", { userId, error: errText(e) });
    }
  }
  return out;
}

function errText(e: unknown): string {
  return (e instanceof Error ? e.message : String(e)).slice(0, 200);
}

// ---------- Supabase storage ---------------------------------------------------------------------

interface ConnectionRow {
  user_id: string;
  request_token: string | null;
  request_secret: string | null;
  requested_at: string | null;
  access_token: string | null;
  access_secret: string | null;
  last_sync_at: string | null;
  callback_code_hash: string | null;
}

const COLUMNS =
  "user_id, request_token, request_secret, requested_at, access_token, access_secret, last_sync_at, callback_code_hash";

function toConnection(r: ConnectionRow | null): TripItConnection | null {
  return r
    ? {
      userId: r.user_id,
      requestToken: r.request_token,
      requestSecret: r.request_secret,
      requestedAt: r.requested_at,
      accessToken: r.access_token,
      accessSecret: r.access_secret,
      lastSyncAt: r.last_sync_at,
      callbackCodeHash: r.callback_code_hash ?? null,
    }
    : null;
}

export class SupabaseTripItStore implements TripItStore {
  constructor(private readonly db: SupabaseClient) {}

  async get(userId: string) {
    const { data, error } = await this.db.from("tripit_connections").select(COLUMNS).eq("user_id", userId)
      .maybeSingle();
    if (error) throw new Error(`tripit_connections read: ${error.message}`);
    return toConnection(data as ConnectionRow | null);
  }

  async findByRequestToken(token: string) {
    const { data, error } = await this.db.from("tripit_connections").select(COLUMNS).eq("request_token", token)
      .maybeSingle();
    if (error) throw new Error(`tripit_connections read: ${error.message}`);
    return toConnection(data as ConnectionRow | null);
  }

  async savePending(userId: string, requestToken: string, sealedSecret: string, at: Date) {
    const { error } = await this.db.from("tripit_connections").upsert({
      user_id: userId,
      request_token: requestToken,
      request_secret: sealedSecret,
      requested_at: at.toISOString(),
      callback_code_hash: null,
      callback_code_expires_at: null,
    }, { onConflict: "user_id" });
    if (error) throw new Error(`tripit_connections pending: ${error.message}`);
  }

  async issueCallbackCode(requestToken: string, codeHash: string, expiresAt: Date) {
    // One UPDATE: the "no code yet" condition and the write are atomic (a parallel callback changes 0 rows).
    const { data, error } = await this.db.from("tripit_connections")
      .update({ callback_code_hash: codeHash, callback_code_expires_at: expiresAt.toISOString() })
      .eq("request_token", requestToken).is("callback_code_hash", null).select("user_id");
    if (error) throw new Error(`tripit_connections code: ${error.message}`);
    return (data ?? []).length > 0;
  }

  async claimCallbackCode(codeHash: string, _now: Date) {
    // The database clock decides expiry (migration 35).
    const { data, error } = await this.db.rpc("tripit_claim_callback", { p_code_hash: codeHash });
    if (error) throw new Error(`tripit_claim_callback: ${error.message}`);
    const r = ((data ?? []) as {
      user_id: string;
      request_token: string;
      request_secret: string;
      requested_at: string | null;
    }[])[0];
    return r
      ? {
        userId: r.user_id,
        requestToken: r.request_token,
        requestSecret: r.request_secret,
        requestedAt: r.requested_at,
      }
      : null;
  }

  async clearPending(userId: string, requestToken: string) {
    const { error } = await this.db.from("tripit_connections").update({
      request_token: null,
      request_secret: null,
      requested_at: null,
      callback_code_hash: null,
      callback_code_expires_at: null,
    }).eq("user_id", userId).eq("request_token", requestToken);
    if (error) throw new Error(`tripit_connections clear pending: ${error.message}`);
  }

  async claimManualSync(userId: string, _now: Date, cooldownMs: number) {
    const { data, error } = await this.db.rpc("tripit_claim_manual_sync", {
      p_user: userId,
      p_cooldown_s: Math.max(1, Math.round(cooldownMs / 1000)),
    });
    if (error) throw new Error(`tripit_claim_manual_sync: ${error.message}`);
    return typeof data === "number" ? data : Number(data) || 0;
  }

  async saveConnected(userId: string, sealedToken: string, sealedSecret: string, at: Date) {
    const { error } = await this.db.from("tripit_connections").update({
      request_token: null,
      request_secret: null,
      requested_at: null,
      callback_code_hash: null,
      callback_code_expires_at: null,
      access_token: sealedToken,
      access_secret: sealedSecret,
      connected_at: at.toISOString(),
      last_error: null,
    }).eq("user_id", userId);
    if (error) throw new Error(`tripit_connections connect: ${error.message}`);
  }

  async recordSync(userId: string, at: Date, err: string | null) {
    const { error } = await this.db.from("tripit_connections")
      .update({ last_sync_at: at.toISOString(), last_error: err }).eq("user_id", userId);
    if (error) throw new Error(`tripit_connections sync: ${error.message}`);
  }

  async seenSegments(userId: string) {
    const { data, error } = await this.db.from("tripit_segments").select("segment_key, outcome, attempted_at")
      .eq("user_id", userId).limit(2000);
    if (error) throw new Error(`tripit_segments read: ${error.message}`);
    const out = new Map<string, SeenSegment>();
    for (const r of (data ?? []) as { segment_key: string; outcome: SegmentOutcome; attempted_at: string }[]) {
      out.set(r.segment_key, { outcome: r.outcome, attemptedAt: Date.parse(r.attempted_at) });
    }
    return out;
  }

  async recordSegment(userId: string, key: string, flightId: string | null, outcome: SegmentOutcome, at: Date) {
    const { error } = await this.db.from("tripit_segments").upsert({
      user_id: userId,
      segment_key: key,
      flight_id: flightId,
      outcome,
      attempted_at: at.toISOString(),
    }, { onConflict: "user_id,segment_key" });
    if (error) throw new Error(`tripit_segments write: ${error.message}`);
  }

  async delete(userId: string) {
    const { error } = await this.db.from("tripit_connections").delete().eq("user_id", userId);
    if (error) throw new Error(`tripit_connections delete: ${error.message}`);
  }

  async syncTargets(limit: number) {
    const { data, error } = await this.db.rpc("tripit_sync_targets", { p_limit: limit });
    if (error) throw new Error(`tripit_sync_targets: ${error.message}`);
    return ((data ?? []) as (string | { tripit_sync_targets: string })[]).map((r) =>
      typeof r === "string" ? r : r.tripit_sync_targets
    );
  }
}
