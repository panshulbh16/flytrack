// OAuth 1.0 request signing (RFC 5849), HMAC-SHA1 only — what TripIt API v1 uses.
// Pure functions + WebCrypto; no network. The nonce and timestamp are injectable so the RFC / TripIt
// example vectors can be reproduced exactly in tests.

export interface OAuth1Credentials {
  consumerKey: string;
  consumerSecret: string;
  /** Temporary (request) or token (access) credentials; absent for the request-token call. */
  token?: string | null;
  tokenSecret?: string | null;
}

export interface OAuth1Request {
  method: string;
  /** Full URL, query included. */
  url: string;
  /**
   * Form parameters of an `application/x-www-form-urlencoded` body (RFC 5849 3.4.1.3.1: they are
   * signed). Other bodies are not signed.
   */
  form?: Record<string, string> | [string, string][];
  /** Extra protocol parameters, e.g. oauth_callback or oauth_verifier. */
  extraOAuth?: Record<string, string>;
  realm?: string;
}

export interface OAuth1Options {
  nonce?: string;
  /** Seconds since the epoch. */
  timestamp?: number;
  /** Default "1.0". `null` leaves oauth_version out (it is optional). */
  version?: string | null;
}

/** RFC 5849 3.6: UTF-8, then every byte except ALPHA / DIGIT / "-" / "." / "_" / "~" as %XX (upper case). */
export function percentEncode(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());
}

/** RFC 5849 3.4.1.2: scheme and host lower case, default port dropped, no query or fragment. */
export function baseStringUri(url: string): string {
  const u = new URL(url);
  const scheme = u.protocol.replace(/:$/, "").toLowerCase();
  const host = u.hostname.toLowerCase();
  const defaultPort = (scheme === "http" && u.port === "80") || (scheme === "https" && u.port === "443");
  const port = u.port && !defaultPort ? `:${u.port}` : "";
  return `${scheme}://${host}${port}${u.pathname || "/"}`;
}

/** RFC 5849 3.4.1.3.2: encode names and values, sort by name then value, join with "=" and "&". */
export function normalizeParameters(params: [string, string][]): string {
  return params
    .map(([k, v]) => [percentEncode(k), percentEncode(v)] as [string, string])
    .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0))
    .map(([k, v]) => `${k}=${v}`)
    .join("&");
}

/** RFC 5849 3.4.1: METHOD & encoded base URI & encoded normalized parameters. */
export function signatureBaseString(method: string, url: string, params: [string, string][]): string {
  return [method.toUpperCase(), percentEncode(baseStringUri(url)), percentEncode(normalizeParameters(params))].join(
    "&",
  );
}

function formEntries(form: OAuth1Request["form"]): [string, string][] {
  if (!form) return [];
  return Array.isArray(form) ? form.map(([k, v]) => [k, v]) : Object.entries(form);
}

/** Query parameters as RFC 5849 3.4.1.3.1 reads them (form-urlencoded: "+" is a space). */
function queryEntries(url: string): [string, string][] {
  return [...new URL(url).searchParams.entries()];
}

export async function hmacSha1Base64(key: string, text: string): Promise<string> {
  const enc = new TextEncoder();
  const k = await crypto.subtle.importKey("raw", enc.encode(key), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", k, enc.encode(text)));
  let bin = "";
  for (const b of sig) bin += String.fromCharCode(b);
  return btoa(bin);
}

/** 32 hex characters from the platform CSPRNG. */
export function makeNonce(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export interface SignedRequest {
  /** The protocol parameters including oauth_signature. */
  oauth: Record<string, string>;
  signature: string;
  baseString: string;
  /** Value for the `Authorization` header (RFC 5849 3.5.1). */
  authorization: string;
}

/** Signs a request with HMAC-SHA1 and returns the Authorization header. */
export async function signRequest(
  creds: OAuth1Credentials,
  req: OAuth1Request,
  opts: OAuth1Options = {},
): Promise<SignedRequest> {
  const oauth: Record<string, string> = {
    oauth_consumer_key: creds.consumerKey,
    oauth_nonce: opts.nonce ?? makeNonce(),
    oauth_signature_method: "HMAC-SHA1",
    oauth_timestamp: String(opts.timestamp ?? Math.floor(Date.now() / 1000)),
    ...(creds.token ? { oauth_token: creds.token } : {}),
    ...(opts.version === null ? {} : { oauth_version: opts.version ?? "1.0" }),
    ...(req.extraOAuth ?? {}),
  };
  const params: [string, string][] = [...queryEntries(req.url), ...formEntries(req.form), ...Object.entries(oauth)];
  const baseString = signatureBaseString(req.method, req.url, params);
  const key = `${percentEncode(creds.consumerSecret)}&${percentEncode(creds.tokenSecret ?? "")}`;
  const signature = await hmacSha1Base64(key, baseString);
  const all = { ...oauth, oauth_signature: signature };
  const parts = Object.entries(all).map(([k, v]) => `${percentEncode(k)}="${percentEncode(v)}"`);
  if (req.realm !== undefined) parts.unshift(`realm="${req.realm.replace(/["\\]/g, "")}"`);
  return { oauth: all, signature, baseString, authorization: `OAuth ${parts.join(", ")}` };
}

/** Parses a token endpoint's form-encoded response (oauth_token=..&oauth_token_secret=..). */
export function parseFormEncoded(body: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of new URLSearchParams(body.trim())) out[k] = v;
  return out;
}
