import type { FcmMessage } from "./notify.ts";

export interface PushResult {
  ok: boolean;
  messageId?: string;
  invalidToken?: boolean; // device uninstalled / token rotated → delete it
  error?: string;
  dryRun?: boolean;
  retryable?: boolean;
}

export interface Pusher {
  send(message: FcmMessage): Promise<PushResult>;
}

/** Logs instead of sending. Used locally, in tests and in the simulator. */
export class LogPusher implements Pusher {
  sent: FcmMessage[] = [];
  constructor(private readonly verbose = false) {}
  send(message: FcmMessage): Promise<PushResult> {
    this.sent.push(message);
    if (this.verbose) {
      const n = message.android?.notification as Record<string, string> | undefined;
      console.log(`[push:dry-run] ${message.data?.kind} → …${message.token.slice(-6)} ${n ? `"${n.title}" — ${n.body}` : ""}`);
    }
    return Promise.resolve({ ok: true, dryRun: true, messageId: `dry-${this.sent.length}` });
  }
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

function b64url(data: Uint8Array | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): Uint8Array<ArrayBuffer> {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
}

/**
 * Firebase Cloud Messaging HTTP v1 sender.
 * Secret: FCM_SERVICE_ACCOUNT_JSON (the Firebase service-account JSON, stored via `supabase secrets set`).
 */
export class FcmPusher implements Pusher {
  private sa: ServiceAccount;
  private token: { value: string; exp: number } | null = null;
  private inflightToken: Promise<string> | null = null; // concurrent sends share ONE OAuth exchange

  constructor(serviceAccountJson: string, private readonly fetchImpl: typeof fetch = fetch) {
    this.sa = JSON.parse(serviceAccountJson);
    if (!this.sa.project_id || !this.sa.client_email || !this.sa.private_key) {
      throw new Error("FCM_SERVICE_ACCOUNT_JSON is missing project_id/client_email/private_key");
    }
  }

  private accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.token && this.token.exp - 60 > now) return Promise.resolve(this.token.value);
    return this.inflightToken ??= this.mintAccessToken().finally(() => {
      this.inflightToken = null;
    });
  }

  private async mintAccessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = b64url(JSON.stringify({
      iss: this.sa.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    }));
    const key = await crypto.subtle.importKey(
      "pkcs8", pemToDer(this.sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
    );
    const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`)));
    const jwt = `${header}.${claims}.${b64url(sig)}`;
    const res = await this.fetchImpl("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
    });
    if (!res.ok) throw new Error(`FCM auth failed: ${res.status} ${await res.text()}`);
    const j = await res.json();
    this.token = { value: j.access_token, exp: now + (j.expires_in ?? 3600) };
    return this.token.value;
  }

  /** Up to 3 attempts for transient errors (network, 429, 5xx); 401 → refresh token once. */
  async send(message: FcmMessage): Promise<PushResult> {
    let last: PushResult = { ok: false, error: "not sent" };
    for (let attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0) await new Promise((r) => setTimeout(r, 400 * 2 ** (attempt - 1)));
      last = await this.sendOnce(message);
      if (last.ok || !last.retryable) return last;
    }
    return last;
  }

  private async sendOnce(message: FcmMessage): Promise<PushResult> {
    try {
      const res = await this.fetchImpl(`https://fcm.googleapis.com/v1/projects/${this.sa.project_id}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${await this.accessToken()}`, "content-type": "application/json" },
        body: JSON.stringify({ message }),
        signal: AbortSignal.timeout(8_000),
      });
      if (res.ok) return { ok: true, messageId: (await res.json()).name };
      const text = await res.text();
      if (res.status === 401) this.token = null; // expired/revoked access token → re-mint on retry
      // 404 UNREGISTERED = app uninstalled / token expired; 400 INVALID_ARGUMENT mentioning the token = bad token
      const invalid = res.status === 404 || /UNREGISTERED/.test(text) ||
        (res.status === 400 && /INVALID_ARGUMENT/.test(text) && /registration token/i.test(text));
      const retryable = !invalid && (res.status === 401 || res.status === 429 || res.status >= 500);
      return { ok: false, invalidToken: invalid, retryable, error: `${res.status} ${text.slice(0, 300)}` };
    } catch (e) {
      return { ok: false, retryable: true, error: String(e) };
    }
  }
}

/** Push not configured: every send FAILS loudly (logged in push_log) instead of pretending to succeed. */
export class UnconfiguredPusher implements Pusher {
  send(): Promise<PushResult> {
    console.error("[push] FCM_SERVICE_ACCOUNT_JSON is not set — alert NOT delivered");
    return Promise.resolve({ ok: false, error: "push_not_configured" });
  }
}

let cached: Pusher | null = null;
/** One pusher per isolate → the OAuth token is reused across invocations (warm starts). */
export function pusherFromEnv(): Pusher {
  if (cached) return cached;
  if (Deno.env.get("PUSH_DRY_RUN") === "true") {
    console.warn("[push] PUSH_DRY_RUN=true — pushes are only logged, NOT sent");
    cached = new LogPusher(true);
  } else {
    const sa = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
    cached = sa ? new FcmPusher(sa) : new UnconfiguredPusher();
  }
  return cached;
}
