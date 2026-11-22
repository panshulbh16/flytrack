import { assert, assertEquals } from "@std/assert";
import { FcmPusher } from "../_shared/fcm.ts";
import { buildAlertMessage, buildSyncMessage, renderEvent } from "../_shared/notify.ts";
import type { FlightEvent, FlightRow } from "../_shared/types.ts";

async function testServiceAccount() {
  const kp = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"],
  );
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...der))}\n-----END PRIVATE KEY-----\n`;
  return { sa: JSON.stringify({ project_id: "demo", client_email: "x@demo.iam.gserviceaccount.com", private_key: pem }), kp };
}

Deno.test("FCM v1: signs a valid RS256 JWT, caches the token, maps 404 to invalidToken", async () => {
  const { sa, kp } = await testServiceAccount();
  const calls: { url: string; body: string }[] = [];
  let sendStatus = 200;
  const fetchImpl = (async (url: string | URL | Request, init?: RequestInit) => {
    const body = init?.body instanceof URLSearchParams ? init.body.toString() : String(init?.body ?? "");
    calls.push({ url: String(url), body });
    if (String(url).includes("oauth2")) {
      const jwt = new URLSearchParams(body).get("assertion")!;
      const [h, c, s] = jwt.split(".");
      const sig = Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (ch) => ch.charCodeAt(0));
      const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", kp.publicKey, sig, new TextEncoder().encode(`${h}.${c}`));
      if (!ok) return new Response("bad sig", { status: 400 });
      return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }));
    }
    return sendStatus === 200
      ? new Response(JSON.stringify({ name: "projects/demo/messages/1" }))
      : new Response(JSON.stringify({ error: { status: "NOT_FOUND", message: "Requested entity was not found.", details: [{ errorCode: "UNREGISTERED" }] } }), { status: 404 });
  }) as typeof fetch;

  const p = new FcmPusher(sa, fetchImpl);
  const r1 = await p.send({ token: "t", data: { a: "b" } });
  assertEquals(r1, { ok: true, messageId: "projects/demo/messages/1" });
  await p.send({ token: "t" });
  assertEquals(calls.filter((c) => c.url.includes("oauth2")).length, 1, "access token cached");
  sendStatus = 404;
  const r3 = await p.send({ token: "t" });
  assertEquals(r3.invalidToken, true);
  assert(calls.at(-1)!.url.endsWith("/v1/projects/demo/messages:send"));
});

const ev = (type: FlightEvent["type"], over: Partial<FlightEvent> = {}): FlightEvent => ({
  type, severity: "critical", proOnly: false, oldValue: "12", newValue: "29", dedupeKey: "k",
  payload: { flight: "6E 2341", dep: "DEL", arr: "BOM", estDep: "2026-11-25T13:20:00Z" }, ...over,
});

Deno.test("alert text uses airport-local time (IST) and is human", () => {
  const { title, body } = renderEvent(ev("GATE_CHANGE"), { dep: "Asia/Kolkata", arr: "Asia/Kolkata" });
  assertEquals(title, "Gate change: 6E 2341 → Gate 29");
  assertEquals(body, "Was gate 12. DEL→BOM, departs 18:50.");
});

Deno.test("alert message: notification payload on critical channel with per-type tag + TTL", () => {
  const m = buildAlertMessage("tok", "f1", "e1", ev("GATE_CHANGE"), { dep: null, arr: null });
  const n = m.android!.notification as Record<string, string>;
  assertEquals(n.channel_id, "flight_critical");
  assertEquals(n.tag, "f1:gate");
  assertEquals(m.android!.ttl, "7200s");
  assertEquals(m.data!.event_type, "GATE_CHANGE");
  const dep = buildAlertMessage("tok", "f1", "e2", ev("DEPARTED", { payload: { flight: "x", actDep: "2026-11-25T13:30:00Z" } }), { dep: null, arr: null });
  assertEquals((dep.android!.notification as Record<string, string>).channel_id, "flight_updates");
});

Deno.test("sync message is data-only, collapsible, under FCM's 4KB limit", () => {
  const row = { id: "f1", carrier_iata: "6E", flight_number: "2341", dep_iata: "DEL", arr_iata: "BOM" } as unknown as FlightRow;
  const m = buildSyncMessage("tok", row, true);
  assertEquals(m.android!.notification, undefined);
  assertEquals(m.android!.collapse_key, "sync:f1");
  assert(JSON.stringify(m.data).length < 4096);
});

Deno.test("review fix: concurrent sends share one OAuth exchange; 5xx retried; unconfigured push fails loudly", async () => {
  const { sa } = await testServiceAccount();
  let oauth = 0, sends = 0;
  const fetchImpl = (async (url: string | URL | Request) => {
    if (String(url).includes("oauth2")) {
      oauth++;
      await new Promise((r) => setTimeout(r, 20));
      return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }));
    }
    sends++;
    return sends === 1 ? new Response("unavailable", { status: 503 }) : new Response(JSON.stringify({ name: "m" }));
  }) as typeof fetch;
  const p = new FcmPusher(sa, fetchImpl);
  const res = await Promise.all([p.send({ token: "a" }), p.send({ token: "b" }), p.send({ token: "c" })]);
  assertEquals(oauth, 1);
  assert(res.every((r) => r.ok), JSON.stringify(res));
  const { UnconfiguredPusher } = await import("../_shared/fcm.ts");
  assertEquals((await new UnconfiguredPusher().send()).ok, false);
});

Deno.test("review fix: terminal change has its own notification tag; gate change shows terminal", () => {
  const t = buildAlertMessage("tok", "f1", "e", ev("TERMINAL_CHANGE", { oldValue: "1", newValue: "3", payload: { flight: "x", airport: "DEL" } }), { dep: null, arr: null });
  assertEquals((t.android!.notification as Record<string, string>).tag, "f1:terminal");
  const g = renderEvent(ev("GATE_CHANGE", { payload: { flight: "6E 1", dep: "DEL", arr: "BOM", terminal: "3", estDep: "2026-11-25T13:20:00Z" } }), { dep: null, arr: null });
  assertEquals(g.title, "Gate change: 6E 1 → Gate 29 (T3)");
  const d = renderEvent(ev("DEPARTED", { payload: { flight: "6E 1", arr: "BOM" } }), { dep: null, arr: null });
  assertEquals(d.body.includes("--:--"), false);
});
