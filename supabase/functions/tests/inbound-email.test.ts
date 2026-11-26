import { assert, assertEquals, assertFalse, assertStringIncludes } from "@std/assert";
import {
  buildImportMessage,
  contentStreamText,
  decodeEntities,
  type EmailImportRow,
  emailSizeBytes,
  emailTicketText,
  extractInboxToken,
  focusText,
  fromDomain,
  handleInboundEmail,
  htmlToText,
  importPushText,
  type InboundDeps,
  type InboundEmail,
  type InboundUser,
  MAX_EMAIL_BYTES,
  normaliseText,
  parseInboundEmail,
  pdfText,
  pickEmailText,
  pickLegs,
  sendImportPush,
  sha256Hex,
  singlePnr,
  type TrackResult,
} from "../_shared/email.ts";
import { SearchError, searchFlight } from "../_shared/engine.ts";
import { MAX_TEXT_CHARS, parseTicket, type ParseTicketDeps } from "../_shared/parse-ticket.ts";
import { makeDeps } from "./helpers.ts";
import AI_TEXT from "./fixtures/email/airindia-forwarded.ts";
import INDIGO_HTML from "./fixtures/email/indigo-confirmation.ts";
import MMT_HTML from "./fixtures/email/makemytrip-roundtrip.ts";

const SECRET = "s3cret-inbound-0123456789";
const TOKEN = "k7m2pq9xzt";
const PRO: InboundUser = { userId: "user-pro", isPro: true, alertLanguage: "en" };
const FREE: InboundUser = { userId: "user-free", isPro: false, alertLanguage: "hi" };

// ---- HTML → text on real-world-shaped confirmations ---------------------------------------------------

Deno.test("email: IndiGo HTML confirmation becomes readable text", () => {
  const t = htmlToText(INDIGO_HTML);
  assertStringIncludes(t, "PNR / Booking Reference | Q7XK2M");
  assertStringIncludes(t, "6E 2341 | Thu, 03 Dec 2026 | Delhi (DEL)");
  assertStringIncludes(t, "Mumbai (BOM)");
  assertStringIncludes(t, "Completed ₹ 6,842", "numeric entity and nbsp decoded");
  assertStringIncludes(t, "6E Eats & Fast Forward");
  assertStringIncludes(t, "departure → goindigo.in");
  assertFalse(t.includes("6E 9999"), "script content is dropped");
  assertFalse(/font-family|@media|<|&nbsp;|&zwnj;|‌/.test(t), "no CSS, tags, raw entities or padding");
  assertFalse(t.includes("campaign id"), "comments are dropped");
  assertFalse(/\n{3,}/.test(t));
});

Deno.test("email: MakeMyTrip round trip keeps both legs and their PNRs", () => {
  const t = htmlToText(MMT_HTML);
  assertStringIncludes(t, "DEPARTURE · Bengaluru → Goa · Sat, 12 Dec 2026");
  assertStringIncludes(t, "QP 1342");
  assertStringIncludes(t, "RETURN · Goa → Bengaluru · Wed, 16 Dec 2026");
  assertStringIncludes(t, "6E 6814");
  assertStringIncludes(t, "Airline PNR | K4TZ8P");
  assertStringIncludes(t, "Airline PNR | H2MW9C");
  assertStringIncludes(t, "From: MakeMyTrip <noreply@makemytrip.com>");
  assertStringIncludes(t, "Total amount paid: ₹ 9,214");
});

Deno.test("email: forwarded plain text loses its quote markers", () => {
  const t = normaliseText(AI_TEXT);
  assertStringIncludes(t, "Booking Reference (PNR): 8HJQ4L");
  assertStringIncludes(t, "AI 2805 20DEC2026 Mumbai (BOM) T2 Delhi (DEL) T3 07:00 09:10");
  assertStringIncludes(t, "AI 101 20DEC2026 Delhi (DEL) T3 New York (JFK) T4 14:15 19:25");
  assertFalse(/^>/m.test(t));
  assertFalse(/\n{3,}/.test(t));
});

Deno.test("email: entities", () => {
  assertEquals(
    decodeEntities("A&amp;B &lt;x&gt; &#8377;5 &#x20b9;6 &rarr; &unknown; &#0; &#xD800;"),
    "A&B <x> ₹5 ₹6 → &unknown;  ",
  );
});

Deno.test("email: the part with more flight numbers wins", () => {
  // a text part that only says "view in browser" loses to the HTML
  assertStringIncludes(pickEmailText("View this email in your browser.", INDIGO_HTML), "6E 2341");
  // a good text part wins over a logo-only HTML part
  assertStringIncludes(pickEmailText(AI_TEXT, "<img alt='Air India'>"), "AI 2805");
  assertEquals(pickEmailText("", ""), "");
  assertStringIncludes(pickEmailText("", MMT_HTML), "QP 1342");
});

Deno.test("email: long text is focused on flights and PNRs within the limit", () => {
  const filler = "Terms and conditions apply. ".repeat(2000);
  const text = `${filler}\nPNR: Q7XK2M\n${filler}\nFlight 6E 2341 DEL to BOM on 03 Dec 2026\n${filler}`;
  const out = focusText(text, MAX_TEXT_CHARS);
  assert(out.length <= MAX_TEXT_CHARS);
  assertStringIncludes(out, "PNR: Q7XK2M");
  assertStringIncludes(out, "6E 2341 DEL to BOM");
  assertEquals(focusText("short", 100), "short");
  assertEquals(focusText("x".repeat(50), 10), "x".repeat(10), "no anchors → the start");
});

Deno.test("email: recipients, sender domain, hash, size", async () => {
  assertEquals(extractInboxToken(`trips+${TOKEN}@in.heygate.app`), TOKEN);
  assertEquals(extractInboxToken(`"HeyGate" <trips+${TOKEN.toUpperCase()}@in.heygate.app>, me@example.com`), TOKEN);
  assertEquals(extractInboxToken(`other@x.com, trips+${TOKEN}@in.heygate.app`), TOKEN);
  assertEquals(extractInboxToken(`TRIPS+${TOKEN.toUpperCase()}@IN.HEYGATE.APP`), TOKEN, "upper-cased by a mail system");
  assertEquals(extractInboxToken("trips+abcdefghil@in.heygate.app"), null, "i and l are not in the alphabet");
  assertEquals(extractInboxToken("trips@in.heygate.app"), null);
  assertEquals(extractInboxToken(`trips+${TOKEN}x@in.heygate.app`), null, "11 characters");

  assertEquals(fromDomain("IndiGo <NoReply@GoIndigo.in>"), "goindigo.in");
  assertEquals(fromDomain("eticket@airindia.com"), "airindia.com");
  assertEquals(fromDomain("no address"), null);
  assertEquals(fromDomain("x@localhost"), null);

  assertEquals(await sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

  const e = parseInboundEmail({
    to: "a",
    text: "abc",
    html: "é",
    attachments: [{ filename: "a.pdf", content_type: "application/pdf", content_base64: "AAAA" }],
  })!;
  assertEquals(emailSizeBytes(e), 3 + 2 + 3);
});

Deno.test("email: body shapes", () => {
  assertEquals(parseInboundEmail(null), null);
  assertEquals(parseInboundEmail([]), null);
  assertEquals(parseInboundEmail({ subject: "no recipient" }), null);
  const e = parseInboundEmail({ to: ["a@x", 5, "b@y"], subject: 7, attachments: [null, { filename: "t.pdf" }] })!;
  assertEquals(e.to, "a@x, b@y");
  assertEquals(e.subject, "");
  assertEquals(e.attachments, [{ filename: "t.pdf", content_type: "", content_base64: "" }]);
});

// ---- PDF --------------------------------------------------------------------------------------------

/** A minimal one-page PDF whose content stream draws [lines]. */
async function makePdf(lines: string[], compress: boolean): Promise<Uint8Array> {
  const esc = (s: string) => s.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
  const content = `BT /F1 11 Tf 50 780 Td 14 TL\n${
    lines.map((l, i) => (i ? `T* (${esc(l)}) Tj` : `(${esc(l)}) Tj`)).join("\n")
  }\nET`;
  let data = new TextEncoder().encode(content);
  if (compress) {
    const stream = new Blob([data]).stream().pipeThrough(new CompressionStream("deflate"));
    data = new Uint8Array(await new Response(stream).arrayBuffer());
  }
  const head = new TextEncoder().encode(
    "%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n" +
      "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n" +
      "3 0 obj << /Type /Page /Parent 2 0 R /Contents 4 0 R >> endobj\n" +
      `4 0 obj << /Length ${data.length}${compress ? " /Filter /FlateDecode" : ""} >>\nstream\n`,
  );
  const tail = new TextEncoder().encode("\nendstream\nendobj\ntrailer << /Root 1 0 R >>\n%%EOF\n");
  const out = new Uint8Array(head.length + data.length + tail.length);
  out.set(head);
  out.set(data, head.length);
  out.set(tail, head.length + data.length);
  return out;
}

const TICKET_LINES = [
  "IndiGo e-Ticket",
  "PNR: Q7XK2M",
  "Flight 6E 2341 (Thu, 03 Dec 2026)",
  "Delhi (DEL) T1 05:45 -> Mumbai (BOM) T2 08:00",
];

Deno.test("email: PDF text from plain and Flate-compressed content streams", async () => {
  for (const compress of [false, true]) {
    const text = await pdfText(await makePdf(TICKET_LINES, compress));
    assert(text, `compress=${compress}`);
    for (const l of TICKET_LINES) assertStringIncludes(text, l);
  }
});

Deno.test("email: unreadable PDFs are skipped", async () => {
  assertEquals(await pdfText(new TextEncoder().encode("not a pdf at all")), null);
  // CID-style glyph ids: hex strings that decode to control characters
  const cid = new TextEncoder().encode(
    "%PDF-1.7\n4 0 obj << /Length 60 >>\nstream\nBT /F1 11 Tf <0012003500450046001A> Tj <00330044> Tj ET\nendstream\nendobj\n%%EOF",
  );
  assertEquals(await pdfText(cid), null);
  // a broken Flate stream
  const broken = new TextEncoder().encode(
    "%PDF-1.4\n4 0 obj << /Length 5 /Filter /FlateDecode >>\nstream\nxxxxx\nendstream\nendobj\n",
  );
  assertEquals(await pdfText(broken), null);
  // encrypted
  assertEquals(await pdfText(new TextEncoder().encode("%PDF-1.4\ntrailer << /Encrypt 5 0 R >>")), null);
});

Deno.test("email: content stream operators", () => {
  assertEquals(contentStreamText("BT [(6E)-300(2341)] TJ ET"), "6E 2341\n");
  assertEquals(contentStreamText("BT [(De)20(lhi)] TJ ET"), "Delhi\n", "small kerning is not a space");
  assertEquals(contentStreamText("BT (a\\(b\\)c \\101\\\\) Tj ET"), "a(b)c A\\\n");
  assertEquals(contentStreamText("BT (line1) Tj (line2) ' ET"), "line1\nline2\n");
});

Deno.test("email: the parser text has the subject, the body and PDF text, within the limit", async () => {
  const pdf = await makePdf(TICKET_LINES, true);
  let b64 = "";
  for (const b of pdf) b64 += String.fromCharCode(b);
  const email = parseInboundEmail({
    to: `trips+${TOKEN}@in.heygate.app`,
    subject: "Fwd: Your IndiGo booking Q7XK2M",
    text: "See attached.",
    attachments: [
      { filename: "ticket.pdf", content_type: "application/pdf", content_base64: btoa(b64) },
      { filename: "logo.png", content_type: "image/png", content_base64: "iVBORw0KGgo=" },
      { filename: "bad.pdf", content_type: "application/pdf", content_base64: "!!!not base64" },
    ],
  })!;
  const text = await emailTicketText(email);
  assert(text.startsWith("Subject: Fwd: Your IndiGo booking Q7XK2M\n\nSee attached."));
  assertStringIncludes(text, "Attachment ticket.pdf:\nIndiGo e-Ticket\nPNR: Q7XK2M");
  assertFalse(text.includes("logo.png"));
  assert(text.length <= MAX_TEXT_CHARS);
});

// ---- push ---------------------------------------------------------------------------------------------

Deno.test("email: import push text in three languages", () => {
  assertEquals(importPushText(2, "en").body, "Added 2 flights from your email");
  assertEquals(importPushText(1, "en").body, "Added 1 flight from your email");
  assertEquals(importPushText(2, "hi").body, "आपके ईमेल से 2 फ़्लाइट जोड़ी गईं");
  assertEquals(importPushText(1, "hi").body, "आपके ईमेल से 1 फ़्लाइट जोड़ी गई");
  assertEquals(importPushText(3, "hinglish").body, "Aapke email se 3 flights add ho gayi");
  assertEquals(importPushText(2, null).body, "Added 2 flights from your email");
  assertEquals(importPushText(2, "fr").title, "Flights added from email");
});

Deno.test("email: import push message is kind=import with string data", () => {
  const m = buildImportMessage("tok-1", 2, "flight-1", "en");
  assertEquals(m.token, "tok-1");
  assertEquals(m.data, {
    kind: "import",
    title: "Flights added from email",
    body: "Added 2 flights from your email",
    count: "2",
    flight_id: "flight-1",
  });
  assert(Object.values(m.data!).every((v) => typeof v === "string"));
  const n = (m.android as { notification: Record<string, string> }).notification;
  assertEquals(n.channel_id, "flight_updates");
  const aps = (m.apns as { payload: { aps: { alert: { body: string } } } }).payload.aps;
  assertEquals(aps.alert.body, "Added 2 flights from your email");
  assertEquals(buildImportMessage("t", 1, null).data!.flight_id, undefined);
});

Deno.test("email: sendImportPush reports each device, even when a send throws", async () => {
  const sent = await sendImportPush(
    [{ id: "d1", fcm_token: "a" }, { id: "d2", fcm_token: "b" }],
    PRO,
    1,
    "f1",
    (m) => m.token === "a" ? Promise.resolve({ ok: true, messageId: "m1" }) : Promise.reject(new Error("boom")),
  );
  assertEquals(sent.map((s) => [s.deviceId, s.result.ok]), [["d1", true], ["d2", false]]);
});

// ---- leg choice ---------------------------------------------------------------------------------------

Deno.test("email: picking legs of a flight number", () => {
  const a = { id: "a", dep_iata: "DEL", arr_iata: "BOM" };
  const b = { id: "b", dep_iata: "BOM", arr_iata: "GOI" };
  const c = { id: "c", dep_iata: "GOI", arr_iata: "COK" };
  assertEquals(pickLegs([a], "BLR", "XXX"), [a], "a single leg is taken as is");
  assertEquals(pickLegs([], "DEL", "BOM"), []);
  assertEquals(pickLegs([a, b], "DEL", "BOM"), [a]);
  assertEquals(pickLegs([a, b], "BOM", null), [b]);
  assertEquals(pickLegs([a, b, c], "DEL", "COK"), [a, b, c], "a through flight to the final stop");
  assertEquals(pickLegs([a, b], "DEL", "CCU"), [a], "unknown end → the first leg only");
  assertEquals(pickLegs([a, b], null, "GOI"), [b]);
  assertEquals(pickLegs([a, b], null, null), [], "ambiguous → none");
});

// ---- handler ----------------------------------------------------------------------------------------

interface Harness {
  deps: InboundDeps;
  records: EmailImportRow[];
  tracked: { userId: string; flightId: string; pnr: string | null }[];
  parsed: string[];
  searches: { flight: string; date: string }[];
  pushes: { user: InboundUser; count: number; first: string | null }[];
  logs: string[];
}

function harness(over: Partial<InboundDeps> = {}, opts: {
  ticket?: {
    pnr: string | null;
    flights: { flight: string; date: string | null; from: string | null; to: string | null }[];
  };
  existing?: Set<string>;
  limitAfter?: number;
} = {}): Harness {
  const h: Harness = {
    records: [],
    tracked: [],
    parsed: [],
    searches: [],
    pushes: [],
    logs: [],
    deps: null as unknown as InboundDeps,
  };
  h.deps = {
    secret: SECRET,
    findUser: (t) => Promise.resolve(t === TOKEN ? PRO : t === "freeuser22" ? FREE : null),
    parse: (_u, text) => {
      h.parsed.push(text);
      return Promise.resolve({ ok: true, ticket: opts.ticket ?? { pnr: null, flights: [] } });
    },
    search: (_u, flight, date) => {
      h.searches.push({ flight, date });
      const id = `${flight.replace(" ", "")}-${date}`;
      return Promise.resolve({ ok: true, legs: [{ id, dep_iata: "DEL", arr_iata: "BOM" }] });
    },
    track: (userId, flightId, pnr) => {
      if (opts.existing?.has(flightId)) return Promise.resolve("exists" as TrackResult);
      if (opts.limitAfter != null && h.tracked.length >= opts.limitAfter) {
        return Promise.resolve("limit" as TrackResult);
      }
      h.tracked.push({ userId, flightId, pnr });
      return Promise.resolve("added" as TrackResult);
    },
    record: (row) => {
      h.records.push(row);
      return Promise.resolve();
    },
    notify: (user, count, first) => {
      h.pushes.push({ user, count, first });
      return Promise.resolve();
    },
    log: (msg, extra) => h.logs.push(JSON.stringify({ msg, ...extra })),
    ...over,
  };
  return h;
}

function post(body: unknown, secret: string | null = SECRET, headers: Record<string, string> = {}): Request {
  return new Request("https://x/functions/v1/inbound-email", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(secret != null ? { "x-inbound-secret": secret } : {}),
      ...headers,
    },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

const EMAIL = {
  to: `trips+${TOKEN}@in.heygate.app`,
  from: "IndiGo <noreply@goindigo.in>",
  subject: "Your IndiGo Itinerary - Q7XK2M",
  text: "",
  html: INDIGO_HTML,
};

Deno.test("inbound-email: the secret header is required", async () => {
  const h = harness();
  assertEquals((await handleInboundEmail(post(EMAIL, null), h.deps)).status, 401);
  assertEquals((await handleInboundEmail(post(EMAIL, "wrong"), h.deps)).status, 401);
  assertEquals((await handleInboundEmail(post(EMAIL, SECRET.slice(0, -1)), h.deps)).status, 401);
  const unconfigured = harness({ secret: undefined });
  assertEquals((await handleInboundEmail(post(EMAIL, ""), unconfigured.deps)).status, 503);
  assertEquals((await handleInboundEmail(new Request("https://x", { method: "GET" }), h.deps)).status, 405);
  assertEquals(h.records.length, 0);
  assertEquals(h.parsed.length, 0);
});

Deno.test("inbound-email: malformed bodies are 400", async () => {
  const h = harness();
  assertEquals((await handleInboundEmail(post("{not json"), h.deps)).status, 400);
  assertEquals((await handleInboundEmail(post({ subject: "x" }), h.deps)).status, 400);
});

Deno.test("inbound-email: an unknown recipient is ignored without a record", async () => {
  const h = harness();
  const r = await handleInboundEmail(post({ ...EMAIL, to: "trips+zzzzzzzzzz@in.heygate.app" }), h.deps);
  assertEquals(r.status, 200);
  assertEquals((await r.json()).status, "ignored");
  const r2 = await handleInboundEmail(post({ ...EMAIL, to: "hello@in.heygate.app" }), h.deps);
  assertEquals((await r2.json()).status, "ignored");
  assertEquals(h.records.length, 0);
  assertEquals(h.parsed.length, 0);
});

Deno.test("inbound-email: free users are recorded as not_pro and never parsed", async () => {
  const h = harness();
  const r = await handleInboundEmail(post({ ...EMAIL, to: "trips+freeuser22@in.heygate.app" }), h.deps);
  assertEquals(r.status, 200);
  assertEquals((await r.json()).status, "not_pro");
  assertEquals(h.parsed.length, 0);
  assertEquals(h.records.length, 1);
  assertEquals(h.records[0].status, "not_pro");
  assertEquals(h.records[0].userId, "user-free");
  assertEquals(h.pushes.length, 0);
});

Deno.test("inbound-email: emails over 5 MB are recorded as too_large and not parsed", async () => {
  const h = harness();
  const big = "A".repeat(Math.ceil((MAX_EMAIL_BYTES + 10) * 4 / 3));
  const r = await handleInboundEmail(
    post({ ...EMAIL, attachments: [{ filename: "x.pdf", content_type: "application/pdf", content_base64: big }] }),
    h.deps,
  );
  assertEquals(r.status, 200);
  assertEquals(h.records[0].status, "error");
  assertEquals(h.records[0].error, "too_large");
  assertEquals(h.parsed.length, 0);
  // a body far over the request cap is not even parsed
  const r2 = await handleInboundEmail(
    post({ ...EMAIL }, SECRET, { "content-length": String(64 * 1024 * 1024) }),
    h.deps,
  );
  assertEquals((await r2.json()).reason, "too_large");
});

Deno.test("inbound-email: flights are searched, tracked with the PNR, recorded and pushed once", async () => {
  const h = harness({}, {
    ticket: {
      pnr: "K4TZ8P",
      flights: [
        { flight: "QP 1342", date: "2026-12-12", from: "BLR", to: "GOX" },
        { flight: "6E 6814", date: "2026-12-16", from: "GOI", to: "BLR" },
        { flight: "6E 6814", date: "2026-12-16", from: "GOI", to: "BLR" }, // duplicate
        { flight: "AI 101", date: null, from: "DEL", to: "JFK" }, // no date: skipped
      ],
    },
  });
  const r = await handleInboundEmail(
    post({ ...EMAIL, html: MMT_HTML, from: "MakeMyTrip <noreply@makemytrip.com>", subject: "Booking Confirmed" }),
    h.deps,
  );
  assertEquals(await r.json(), { ok: true, status: "added", flights_added: 2 });
  assertEquals(h.searches, [{ flight: "QP 1342", date: "2026-12-12" }, { flight: "6E 6814", date: "2026-12-16" }]);
  // two airline PNRs in the email → none is attached rather than a wrong one
  assertEquals(h.tracked.map((t) => [t.flightId, t.pnr]), [["QP1342-2026-12-12", null], ["6E6814-2026-12-16", null]]);
  assertEquals(h.pushes, [{ user: PRO, count: 2, first: "QP1342-2026-12-12" }]);
  assertEquals(h.records.length, 1);
  const rec = h.records[0];
  assertEquals(rec.status, "added");
  assertEquals(rec.flightsAdded, 2);
  assertEquals(rec.fromDomain, "makemytrip.com");
  assertEquals(rec.subjectHash, await sha256Hex("Booking Confirmed"));
  // the parser saw readable text; nothing of the email is in the record or the logs
  assertStringIncludes(h.parsed[0], "QP 1342");
  assertStringIncludes(h.parsed[0], "Subject: Booking Confirmed");
  const stored = JSON.stringify(h.records) + h.logs.join("\n");
  for (const secretBit of ["Booking Confirmed", "K4TZ8P", "Rahul", "rahul.v@example.com", TOKEN]) {
    assertFalse(stored.includes(secretBit), `${secretBit} leaked`);
  }
});

Deno.test("inbound-email: outcomes without new flights", async () => {
  const flights = [{ flight: "6E 2341", date: "2026-12-03", from: "DEL", to: "BOM" }];
  // nothing found by the parser
  let h = harness();
  await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals([h.records[0].status, h.records[0].error], ["no_flights", null]);
  assertEquals(h.pushes.length, 0);
  // already tracked
  h = harness({}, { ticket: { pnr: null, flights }, existing: new Set(["6E2341-2026-12-03"]) });
  await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals([h.records[0].status, h.records[0].flightsAdded, h.records[0].error], ["added", 0, "already_tracked"]);
  assertEquals(h.pushes.length, 0);
  // tracking limit
  h = harness({}, { ticket: { pnr: null, flights }, limitAfter: 0 });
  await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals([h.records[0].status, h.records[0].error], ["error", "tracking_limit"]);
  // provider has no such flight
  h = harness({ search: () => Promise.resolve({ ok: false, error: "not_found" }) }, { ticket: { pnr: null, flights } });
  await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals([h.records[0].status, h.records[0].error], ["no_flights", "not_found"]);
  // search quota used up: stop searching
  let calls = 0;
  h = harness({ search: () => (calls++, Promise.resolve({ ok: false, error: "rate_limited" })) }, {
    ticket: { pnr: null, flights: [...flights, { flight: "6E 2342", date: "2026-12-04", from: null, to: null }] },
  });
  await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals([h.records[0].status, h.records[0].error, calls], ["error", "search_rate_limited", 1]);
  // the parser fails (quota, no key, model down)
  h = harness({ parse: () => Promise.resolve({ ok: false, error: "rate_limited" }) });
  await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals([h.records[0].status, h.records[0].error], ["error", "parse_rate_limited"]);
  // an empty email never reaches the parser
  h = harness();
  await handleInboundEmail(post({ to: EMAIL.to, subject: "Fwd:" }), h.deps);
  assertEquals([h.records[0].status, h.records[0].error, h.parsed.length], ["no_flights", "empty", 0]);
  // an unexpected failure is recorded as error and still answers 200
  h = harness({ track: () => Promise.reject(new Error("db down")) }, { ticket: { pnr: null, flights } });
  const r = await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals(r.status, 200);
  assertEquals([h.records[0].status, h.records[0].error], ["error", "internal"]);
});

Deno.test("inbound-email: a failing record does not lose the push", async () => {
  const h = harness({ record: () => Promise.reject(new Error("insert failed")) }, {
    ticket: { pnr: null, flights: [{ flight: "6E 2341", date: "2026-12-03", from: "DEL", to: "BOM" }] },
  });
  const r = await handleInboundEmail(post(EMAIL), h.deps);
  assertEquals(r.status, 200);
  assertEquals(h.pushes.length, 1);
});

// ---- end to end with the real parser (mocked model) and the real searchFlight (mock provider) ------------

Deno.test("inbound-email: IndiGo, MakeMyTrip and Air India emails end to end", async () => {
  const cases: {
    name: string;
    email: Partial<InboundEmail>;
    model: string;
    expect: string[];
    added: number;
    pnr: string | null;
  }[] = [
    {
      name: "IndiGo",
      email: {
        html: INDIGO_HTML,
        subject: "IndiGo Booking Confirmation - PNR Q7XK2M",
        from: "IndiGo <reservations@customer.goindigo.in>",
      },
      model: '{"pnr":"Q7XK2M","flights":[{"flight":"6E 2341","date":"2026-12-03","from":"DEL","to":"BOM"}]}',
      expect: ["6E 2341 | Thu, 03 Dec 2026 | Delhi (DEL)", "Q7XK2M"],
      added: 1,
      pnr: "Q7XK2M",
    },
    {
      name: "MakeMyTrip",
      email: {
        html: MMT_HTML,
        text: "Your booking is confirmed. View in browser.",
        subject: "Fwd: Booking Confirmed - Bengaluru to Goa",
      },
      model: '```json\n{"pnr":"K4TZ8P","flights":[{"flight":"QP 1342","date":"2026-12-12","from":"BLR","to":"GOX"},' +
        '{"flight":"6E 6814","date":"2026-12-16","from":"GOI","to":"BLR"}]}\n```',
      expect: ["QP 1342", "6E 6814", "Sat, 12 Dec 2026"],
      added: 2,
      pnr: null,
    },
    {
      name: "Air India",
      email: {
        text: AI_TEXT,
        subject: "Fwd: Air India e-Ticket Itinerary / Receipt - PNR 8HJQ4L",
        from: "me@example.com",
      },
      model: '{"pnr":"8HJQ4L","flights":[{"flight":"AI 2805","date":"2026-12-20","from":"BOM","to":"DEL"},' +
        '{"flight":"AI 101","date":"2026-12-20","from":"DEL","to":"JFK"},{"flight":"XX","date":"2026-12-20"}]}',
      expect: ["AI 2805 20DEC2026 Mumbai (BOM)", "AI 101 20DEC2026 Delhi (DEL)"],
      added: 2,
      pnr: "8HJQ4L",
    },
  ];
  for (const c of cases) {
    const env = makeDeps("2026-11-26T06:00:00Z");
    const modelBodies: string[] = [];
    const parseDeps: ParseTicketDeps = {
      fetch: ((_u: string | URL | Request, init?: RequestInit) => {
        modelBodies.push(String(init?.body ?? ""));
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: "text", text: c.model }] })));
      }) as typeof fetch,
      apiKey: "test-key",
      usedToday: () => Promise.resolve(0),
      reserve: () => Promise.resolve(true),
    };
    const tracked = new Map<string, string | null>();
    const h = harness({
      parse: (userId, text) => parseTicket(parseDeps, userId, text),
      async search(userId, flight, date) {
        try {
          const rows = await searchFlight(env.deps, userId, { flight, date });
          return { ok: true, legs: rows.map((r) => ({ id: r.id, dep_iata: r.dep_iata, arr_iata: r.arr_iata })) };
        } catch (e) {
          if (e instanceof SearchError) return { ok: false, error: e.code };
          throw e;
        }
      },
      track: (_u, flightId, pnr) => {
        if (tracked.has(flightId)) return Promise.resolve("exists");
        tracked.set(flightId, pnr);
        return Promise.resolve("added");
      },
    });
    const r = await handleInboundEmail(
      post({ to: `trips+${TOKEN}@in.heygate.app`, text: "", html: "", ...c.email }),
      h.deps,
    );
    const out = await r.json();
    assertEquals(out.status, "added", c.name);
    assertEquals(out.flights_added, c.added, c.name);
    const prompt = JSON.parse(modelBodies[0]).messages[0].content as string;
    for (const bit of c.expect) assertStringIncludes(prompt, bit, `${c.name}: ${bit}`);
    assertFalse(prompt.includes("<td"), `${c.name}: no HTML reaches the model`);
    assertEquals(h.pushes.length, 1, c.name);
    assertEquals(h.pushes[0].count, c.added, c.name);
    assertEquals(env.repo.flights.size, c.added, `${c.name}: legs cached by searchFlight`);
    assertEquals([...new Set(tracked.values())], [c.pnr], `${c.name}: PNR`);

    // the same email again: nothing new, no second push
    const again = await handleInboundEmail(
      post({ to: `trips+${TOKEN}@in.heygate.app`, text: "", html: "", ...c.email }),
      h.deps,
    );
    assertEquals((await again.json()).flights_added, 0, c.name);
    assertEquals(h.records[1].error, "already_tracked", c.name);
    assertEquals(h.pushes.length, 1, c.name);
  }
});

Deno.test("email: a PNR is kept only when the email names one", () => {
  assertEquals(singlePnr("PNR: Q7XK2M\nSubject: PNR Q7XK2M", "Q7XK2M"), "Q7XK2M");
  assertEquals(singlePnr("Airline PNR | K4TZ8P\nAirline PNR | H2MW9C", "K4TZ8P"), null);
  assertEquals(singlePnr("Booking ref ABC123", "ABC123"), "ABC123", "no PNR label: trust the parser");
  assertEquals(singlePnr("PNR Q7XK2M", null), null);
});
