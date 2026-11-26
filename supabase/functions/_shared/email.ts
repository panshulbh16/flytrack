// Email forwarding import (docs/P2_WAVE2_CONTRACTS.md section 2). A Pro user forwards a booking
// confirmation to trips+{inbox_token}@{domain}; an email provider adapter POSTs it here in a
// provider-neutral shape, and every flight found is tracked with source 'email'.
//
// Data minimisation (DPDP): the body, the addresses and attachments are only held in memory while the
// request runs. email_imports keeps the sender's domain, a SHA-256 of the subject and the outcome.
// Logs never contain the body, the subject or an address.
import { CHANNEL_UPDATES, type FcmMessage } from "./notify.ts";
import { MAX_TEXT_CHARS, type ParsedFlight, type ParsedTicket, type ParseTicketError } from "./parse-ticket.ts";
import type { PushResult } from "./fcm.ts";
import { cors, json, safeEqual } from "./http.ts";

export const MAX_EMAIL_BYTES = 5 * 1024 * 1024;
/** Raw request bodies above this are not even parsed (base64 makes attachments 4/3 larger). */
export const MAX_REQUEST_BYTES = 8 * 1024 * 1024;
/** A confirmation rarely has more legs; this caps provider searches per email. */
export const MAX_LEGS = 8;
export const MAX_PDFS = 3;
/** Inbox tokens: 10 characters, lowercase, no look-alikes (migration 18). */
export const INBOX_TOKEN_RE = /^[a-hjkmnp-z2-9]{10}$/;

// ---- input ------------------------------------------------------------------------------------------

export interface InboundAttachment {
  filename: string;
  content_type: string;
  content_base64: string;
}

export interface InboundEmail {
  to: string;
  from: string;
  subject: string;
  text: string;
  html: string;
  attachments: InboundAttachment[];
}

const s = (v: unknown): string => (typeof v === "string" ? v : "");

/** The provider-neutral body, or null when it is not an object with a recipient. */
export function parseInboundEmail(raw: unknown): InboundEmail | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  // "to" may be a string or a list of strings (some providers send every recipient)
  const to = Array.isArray(o.to) ? o.to.filter((x) => typeof x === "string").join(", ") : s(o.to);
  if (!to) return null;
  const attachments: InboundAttachment[] = [];
  if (Array.isArray(o.attachments)) {
    for (const a of o.attachments) {
      if (!a || typeof a !== "object") continue;
      const r = a as Record<string, unknown>;
      attachments.push({
        filename: s(r.filename),
        content_type: s(r.content_type),
        content_base64: s(r.content_base64),
      });
    }
  }
  return { to, from: s(o.from), subject: s(o.subject), text: s(o.text), html: s(o.html), attachments };
}

/** The inbox token from any trips+{token}@ recipient, lowercased; null when there is none. */
export function extractInboxToken(to: string): string | null {
  for (const m of to.matchAll(/trips\+([A-Za-z0-9]{10})@/gi)) {
    const token = m[1].toLowerCase();
    if (INBOX_TOKEN_RE.test(token)) return token;
  }
  return null;
}

/** The sender's domain ("IndiGo <noreply@goindigo.in>" → "goindigo.in"), or null. */
export function fromDomain(from: string): string | null {
  const addr = /<([^>]+)>/.exec(from)?.[1] ?? from;
  const m = /@([A-Za-z0-9.-]+)\s*$/.exec(addr.trim());
  if (!m) return null;
  const domain = m[1].toLowerCase().replace(/\.$/, "");
  return /^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(domain) && domain.length <= 253 ? domain : null;
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Decoded size of the email: text + html + attachments (base64 decodes to 3/4 of its length). */
export function emailSizeBytes(e: InboundEmail): number {
  const enc = new TextEncoder();
  let n = enc.encode(e.text).length + enc.encode(e.html).length + enc.encode(e.subject).length;
  for (const a of e.attachments) n += Math.floor(a.content_base64.replace(/\s+/g, "").length * 3 / 4);
  return n;
}

// ---- HTML → text --------------------------------------------------------------------------------------

const NAMED_ENTITIES: Record<string, string> = {
  nbsp: " ",
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  rarr: "→",
  larr: "←",
  harr: "↔",
  ndash: "–",
  mdash: "—",
  middot: "·",
  bull: "•",
  hellip: "…",
  copy: "©",
  reg: "®",
  trade: "™",
  rsquo: "'",
  lsquo: "'",
  rdquo: '"',
  ldquo: '"',
  times: "×",
  ensp: " ",
  emsp: " ",
  thinsp: " ",
  zwnj: "",
  zwj: "",
  shy: "",
  deg: "°",
  raquo: "»",
  laquo: "«",
  rupee: "₹",
  inr: "₹",
};

export function decodeEntities(text: string): string {
  return text.replace(/&(#x[0-9a-f]{1,6}|#\d{1,7}|[a-z][a-z0-9]{1,31});/gi, (whole, ent: string) => {
    if (ent[0] === "#") {
      const code = ent[1] === "x" || ent[1] === "X" ? parseInt(ent.slice(2), 16) : parseInt(ent.slice(1), 10);
      if (!Number.isFinite(code) || code <= 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return "";
      return String.fromCodePoint(code);
    }
    const named = NAMED_ENTITIES[ent.toLowerCase()];
    return named ?? whole;
  });
}

/** Invisible characters marketing emails use as preheader padding. */
const INVISIBLE = /[­͏​-‏⁠﻿]/g;

/** Tidies plain text: no quote markers, no invisible padding, single spaces, at most one blank line. */
export function normaliseText(text: string): string {
  return text
    .replace(/\r\n?/g, "\n")
    .replace(INVISIBLE, "")
    .replace(/ /g, " ")
    .split("\n")
    .map((line) => line.replace(/^(?:\s*>)+\s?/, "").replace(/[ \t\f\v]+/g, " ").trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/**
 * Readable text from an HTML email: scripts, styles and comments go, block elements become line
 * breaks, table cells are separated by " | " so a row such as "6E 2341 | DEL | BOM" stays on one line.
 */
export function htmlToText(html: string): string {
  let t = html
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<(script|style|head|title|noscript|template|svg)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, " ")
    // source whitespace is not significant in HTML; only tags make line breaks
    .replace(/\s+/g, " ")
    // a <br> inside an innermost table cell stays on the row ("Delhi (DEL) · Terminal 1")
    .replace(
      /(<t[dh]\b[^>]*>)((?:(?!<\/?(?:t[dh]|table)\b)[\s\S])*?)(<\/t[dh]\s*>)/gi,
      (_m, open: string, inner: string, close: string) => open + inner.replace(/<br\s*\/?>/gi, " · ") + close,
    )
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(t[dh])\s*>/gi, "\t")
    .replace(/<li\b[^>]*>/gi, "\n- ")
    .replace(
      /<\/?(p|div|tr|table|tbody|thead|tfoot|h[1-6]|ul|ol|li|section|article|header|footer|blockquote|center|hr)\b[^>]*>/gi,
      "\n",
    )
    .replace(/<[^>]*>/g, "");
  t = decodeEntities(t).replace(INVISIBLE, "").replace(/ /g, " ");
  const lines = t.replace(/\r\n?/g, "\n").split("\n").map((line) =>
    line.split("\t").map((cell) => cell.replace(/[ \f\v]+/g, " ").trim()).filter((cell) => cell !== "").join(" | ")
  );
  return lines.join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

/** Flight-number-like tokens ("6E 2341", "AI-101", "QP1123"), used to choose and focus text. */
const FLIGHT_LIKE = /\b(?:[A-Z][A-Z0-9]|[0-9][A-Z])[ -]?\d{2,4}\b/g;

function flightLikeCount(text: string): number {
  return text.match(FLIGHT_LIKE)?.length ?? 0;
}

/** The better of the plain-text and HTML parts: more flight numbers wins, then the longer one. */
export function pickEmailText(text: string, html: string): string {
  const plain = normaliseText(text);
  const fromHtml = html ? htmlToText(html) : "";
  if (!fromHtml) return plain;
  if (!plain) return fromHtml;
  const a = flightLikeCount(plain), b = flightLikeCount(fromHtml);
  if (a !== b) return a > b ? plain : fromHtml;
  return plain.length >= fromHtml.length ? plain : fromHtml;
}

/**
 * Text no longer than [max]: when it is longer, the parts around flight numbers and PNRs are kept
 * (1200 characters either side), in order, joined by "…" lines.
 */
export function focusText(text: string, max: number): string {
  if (text.length <= max) return text;
  const pad = 1200;
  const spans: [number, number][] = [];
  const anchors = new RegExp(`${FLIGHT_LIKE.source}|\\b(?:PNR|[Bb]ooking [Rr]ef)`, "g");
  for (const m of text.matchAll(anchors)) {
    const start = Math.max(0, m.index! - pad), end = Math.min(text.length, m.index! + m[0].length + pad);
    const last = spans[spans.length - 1];
    if (last && start <= last[1]) last[1] = Math.max(last[1], end);
    else spans.push([start, end]);
  }
  if (!spans.length) return text.slice(0, max);
  let out = "";
  for (const [a, b] of spans) {
    const piece = (out ? "\n…\n" : "") + text.slice(a, b);
    if (out.length + piece.length > max) {
      out += piece.slice(0, max - out.length);
      break;
    }
    out += piece;
  }
  return out;
}

// ---- PDF text (best effort, no dependencies) ----------------------------------------------------------

function latin1(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return out;
}

async function inflate(data: Uint8Array, maxBytes: number): Promise<Uint8Array | null> {
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    const reader = new Blob([data as BlobPart]).stream().pipeThrough(new DecompressionStream("deflate")).getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > maxBytes) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } catch {
    // corrupt data, or junk after the end of the deflate stream: keep what was inflated, if anything
    if (!chunks.length) return null;
  }
  const out = new Uint8Array(total);
  let o = 0;
  for (const c of chunks) {
    out.set(c, o);
    o += c.length;
  }
  return out;
}

/** Reads a PDF literal string starting after "(" at [i]; returns the text and the index after ")". */
function readLiteral(src: string, i: number): [string, number] {
  let depth = 1, out = "";
  while (i < src.length) {
    const c = src[i++];
    if (c === "\\") {
      const n = src[i++];
      if (n === undefined) break;
      if (n === "n") out += "\n";
      else if (n === "r") out += "\r";
      else if (n === "t") out += "\t";
      else if (n === "b" || n === "f") out += "";
      else if (n === "\r" || n === "\n") {
        if (n === "\r" && src[i] === "\n") i++; // line continuation
      } else if (/[0-7]/.test(n)) {
        let oct = n;
        while (oct.length < 3 && /[0-7]/.test(src[i] ?? "")) oct += src[i++];
        out += String.fromCharCode(parseInt(oct, 8) & 0xff);
      } else out += n; // \( \) \\ and unknown escapes
    } else if (c === "(") {
      depth++;
      out += c;
    } else if (c === ")") {
      if (--depth === 0) break;
      out += c;
    } else out += c;
  }
  return [out, i];
}

function readHex(src: string, i: number): [string, number] {
  const end = src.indexOf(">", i);
  const hex = src.slice(i, end < 0 ? src.length : end).replace(/[^0-9a-fA-F]/g, "");
  let out = "";
  for (let k = 0; k + 1 < hex.length; k += 2) out += String.fromCharCode(parseInt(hex.slice(k, k + 2), 16));
  return [out, end < 0 ? src.length : end + 1];
}

/** Text drawn by one content stream: Tj, TJ, ' and " operators; line moves become newlines. */
export function contentStreamText(src: string): string {
  let out = "";
  let pending: string[] = []; // strings since the last operator
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === "(") {
      const [t, next] = readLiteral(src, i + 1);
      pending.push(t);
      i = next;
    } else if (c === "<" && src[i + 1] !== "<") {
      const [t, next] = readHex(src, i + 1);
      pending.push(t);
      i = next;
    } else if (c === "[") {
      pending = [];
      i++;
    } else if (c === "]") {
      i++;
    } else if (c === "%") {
      while (i < src.length && src[i] !== "\n" && src[i] !== "\r") i++;
    } else if (/[A-Za-z'"*]/.test(c)) {
      let op = "";
      while (i < src.length && /[A-Za-z'"*]/.test(src[i])) op += src[i++];
      if (op === "Tj" || op === "TJ") out += pending.join("");
      else if (op === "'" || op === '"') out += "\n" + pending.join("");
      else if (op === "Td" || op === "TD" || op === "T*" || op === "Tm" || op === "ET") out += "\n";
      pending = [];
    } else if ((c === "-" || c === "." || /\d/.test(c)) && pending.length) {
      // a TJ kerning number: a big negative gap is a word space
      const m = /^-?\d*\.?\d+/.exec(src.slice(i, i + 16));
      if (m && Number(m[0]) < -200) pending.push(" ");
      i += m ? m[0].length : 1;
    } else {
      i++;
    }
  }
  return out;
}

/** Plain-looking text: mostly letters, digits, spaces and punctuation, with some real words. */
export function looksLikeText(text: string): boolean {
  const compact = text.replace(/\s+/g, " ").trim();
  if (compact.length < 20) return false;
  const good = compact.match(/[A-Za-z0-9 .,:;\/()\-+'"@&#₹→|]/g)?.length ?? 0;
  return good / compact.length >= 0.85 && /[A-Za-z]{3,}/.test(compact);
}

/**
 * Best-effort text of a PDF: uncompressed and FlateDecode content streams, standard-encoded fonts only.
 * Returns null when no readable text comes out (scanned tickets, CID fonts, encrypted files); such
 * attachments are skipped rather than sent to the model as garbage.
 */
export async function pdfText(bytes: Uint8Array): Promise<string | null> {
  if (latin1(bytes.subarray(0, 1024)).indexOf("%PDF-") < 0) return null;
  const src = latin1(bytes);
  if (/\/Encrypt\b/.test(src)) return null;
  const parts: string[] = [];
  let total = 0;
  const re = /stream\r?\n/g;
  let m: RegExpExecArray | null;
  let streams = 0;
  while ((m = re.exec(src)) && streams < 300 && total < 60_000) {
    streams++;
    const start = m.index + m[0].length;
    const end = src.indexOf("endstream", start);
    if (end < 0) break;
    const dict = src.slice(Math.max(0, src.lastIndexOf("obj", m.index)), m.index);
    re.lastIndex = end + 9;
    if (/\/(Subtype\s*\/Image|Type\s*\/XRef|Type\s*\/ObjStm|Type\s*\/Metadata)/.test(dict)) continue;
    if (/\/Length1|\/Length2|\/Length3|\/Subtype\s*\/(Type1C|CIDFontType0C|OpenType)/.test(dict)) continue; // fonts
    // /Length when it is a direct number, else up to "endstream" minus the end-of-line before it
    const declared = /\/Length\s+(\d+)(?!\s+\d+\s+R)/.exec(dict);
    const len = declared ? Number(declared[1]) : -1;
    let stop = end;
    if (len >= 0 && start + len <= end) stop = start + len;
    else if (src[stop - 1] === "\n") stop -= src[stop - 2] === "\r" ? 2 : 1;
    else if (src[stop - 1] === "\r") stop -= 1;
    let data = bytes.subarray(start, stop);
    const filters = /\/Filter\s*(\[[^\]]*\]|\/\w+)/.exec(dict)?.[1] ?? "";
    if (filters) {
      if (filters.replace(/[\[\]\s]/g, "") !== "/FlateDecode") continue; // DCT, LZW, ASCII85 …: skip
      const inflated = await inflate(data, 2 * 1024 * 1024);
      if (!inflated) continue;
      data = inflated;
    }
    const content = latin1(data);
    if (!/\bT[jJ]\b|'/.test(content)) continue;
    const text = contentStreamText(content);
    if (text.trim()) {
      parts.push(text);
      total += text.length;
    }
  }
  const out = normaliseText(parts.join("\n"));
  return looksLikeText(out) ? out : null;
}

function base64Bytes(b64: string): Uint8Array | null {
  try {
    const bin = atob(b64.replace(/\s+/g, ""));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function isPdf(a: InboundAttachment): boolean {
  return /^application\/(pdf|x-pdf)\b/i.test(a.content_type) || /\.pdf$/i.test(a.filename);
}

/** The text the parser sees: subject, the better body part and readable PDF text, within the model's limit. */
export async function emailTicketText(e: InboundEmail): Promise<string> {
  const pieces: string[] = [];
  const subject = normaliseText(e.subject);
  if (subject) pieces.push(`Subject: ${subject}`);
  const body = pickEmailText(e.text, e.html);
  if (body) pieces.push(body);
  for (const a of e.attachments.filter(isPdf).slice(0, MAX_PDFS)) {
    const bytes = base64Bytes(a.content_base64);
    const text = bytes ? await pdfText(bytes) : null;
    if (text) pieces.push(`Attachment ${a.filename || "ticket.pdf"}:\n${text}`);
  }
  return focusText(pieces.join("\n\n"), MAX_TEXT_CHARS);
}

// ---- push ("Added 2 flights from your email") ----------------------------------------------------------

export function importPushText(count: number, lang: string | null | undefined): { title: string; body: string } {
  const one = count === 1;
  switch (lang) {
    case "hi":
      return {
        title: "ईमेल से फ़्लाइट जोड़ी गई",
        body: `आपके ईमेल से ${count} फ़्लाइट ${one ? "जोड़ी गई" : "जोड़ी गईं"}`,
      };
    case "hinglish":
      return {
        title: "Email se flight add hui",
        body: `Aapke email se ${count} ${one ? "flight" : "flights"} add ho gayi`,
      };
    default:
      return {
        title: "Flights added from email",
        body: `Added ${count} ${one ? "flight" : "flights"} from your email`,
      };
  }
}

/** kind="import": a visible, non-urgent notification; tapping opens the first added flight. */
export function buildImportMessage(
  token: string,
  count: number,
  firstFlightId: string | null,
  lang?: string | null,
): FcmMessage {
  const { title, body } = importPushText(count, lang);
  const data: Record<string, string> = { kind: "import", title, body, count: String(count) };
  if (firstFlightId) data.flight_id = firstFlightId;
  return {
    token,
    data,
    android: {
      priority: "NORMAL",
      ttl: `${24 * 3600}s`,
      notification: { title, body, channel_id: CHANNEL_UPDATES, tag: "import" },
    },
    apns: {
      headers: { "apns-push-type": "alert", "apns-priority": "5", "apns-collapse-id": "import" },
      payload: {
        aps: { alert: { title, body }, sound: "default", "thread-id": "import", "interruption-level": "active" },
        ...data,
      },
    },
  };
}

// ---- processing --------------------------------------------------------------------------------------

export type ImportStatus = "added" | "no_flights" | "not_pro" | "error";
export type TrackResult = "added" | "exists" | "limit";

export interface InboundUser {
  userId: string;
  isPro: boolean;
  alertLanguage: string | null;
}

/** The legs searchFlight returned (FlightRow subset). */
export interface FoundLeg {
  id: string;
  dep_iata: string;
  arr_iata: string;
  sched_dep?: string | null;
}

export type SearchOutcome =
  | { ok: true; legs: FoundLeg[] }
  | { ok: false; error: "bad_input" | "not_found" | "rate_limited" | "provider_error" };

export interface EmailImportRow {
  userId: string;
  fromDomain: string | null;
  subjectHash: string | null;
  status: ImportStatus;
  flightsAdded: number;
  error: string | null;
}

export interface InboundDeps {
  secret: string | undefined;
  findUser(token: string): Promise<InboundUser | null>;
  /** parseTicket() with the user's daily parse quota. */
  parse(
    userId: string,
    text: string,
  ): Promise<{ ok: true; ticket: ParsedTicket } | { ok: false; error: ParseTicketError }>;
  /** searchFlight() as the user (their search quota applies). */
  search(userId: string, flight: string, date: string): Promise<SearchOutcome>;
  /** import_track_flight(): tracks with source 'email'. */
  track(userId: string, flightId: string, pnr: string | null): Promise<TrackResult>;
  record(row: EmailImportRow): Promise<void>;
  /** Sends the import push to the user's devices; failures are logged, never thrown. */
  notify(user: InboundUser, count: number, firstFlightId: string | null): Promise<void>;
  log(msg: string, extra?: Record<string, unknown>): void;
}

export interface ImportOutcome {
  status: ImportStatus;
  flightsAdded: number;
  error: string | null;
  flightIds: string[];
}

/**
 * The legs of one parsed flight: the leg from "from" to "to" (following a through flight's legs when the
 * ticket names the final stop), else the leg from "from", else the leg to "to", else the only leg.
 * Several legs and no airport to choose by → none (we never guess).
 */
export function pickLegs(legs: FoundLeg[], from: string | null, to: string | null): FoundLeg[] {
  if (legs.length <= 1) return legs;
  const start = from ? legs.find((l) => l.dep_iata === from) : undefined;
  if (start) {
    if (!to || start.arr_iata === to) return [start];
    const chain = [start];
    let cur = start;
    for (let i = 0; i < 4; i++) {
      const next = legs.find((l) => l.dep_iata === cur.arr_iata && !chain.includes(l));
      if (!next) break;
      chain.push(next);
      cur = next;
      if (cur.arr_iata === to) return chain;
    }
    return [start];
  }
  const end = to ? legs.find((l) => l.arr_iata === to) : undefined;
  return end ? [end] : [];
}

function dedupeFlights(flights: ParsedFlight[]): ParsedFlight[] {
  const seen = new Set<string>();
  return flights.filter((f) => {
    const key = `${f.flight}|${f.date}|${f.from}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Steps 3–6 of the contract for a known user. Never throws for flight-level problems. */
/**
 * parse-ticket returns one PNR per ticket. A booking with a PNR per airline (an OTA round trip on two
 * carriers) would put the wrong one on a leg, so the PNR is only kept when the email names one PNR.
 */
export function singlePnr(text: string, pnr: string | null): string | null {
  if (!pnr) return null;
  const named = new Set<string>();
  for (const m of text.matchAll(/\bPNR\b[^\n]{0,40}?\b([A-Z0-9]{6})\b/g)) named.add(m[1]);
  return named.size > 1 ? null : pnr;
}

export async function importEmail(deps: InboundDeps, user: InboundUser, email: InboundEmail): Promise<ImportOutcome> {
  const done = (status: ImportStatus, error: string | null, ids: string[] = []): ImportOutcome => ({
    status,
    flightsAdded: ids.length,
    error,
    flightIds: ids,
  });
  const text = await emailTicketText(email);
  if (!text.replace(/^Subject:.*$/m, "").trim()) return done("no_flights", "empty");

  const parsed = await deps.parse(user.userId, text);
  if (!parsed.ok) return done("error", `parse_${parsed.error}`);
  const flights = dedupeFlights(parsed.ticket.flights).slice(0, MAX_LEGS);
  const pnr = singlePnr(text, parsed.ticket.pnr);
  if (!flights.length) return done("no_flights", null);

  const added: string[] = [];
  let exists = 0, limit = 0, notFound = 0, noDate = 0;
  let searchError: string | null = null;
  for (const f of flights) {
    if (!f.date) {
      noDate++;
      continue;
    }
    const found = await deps.search(user.userId, f.flight, f.date);
    if (!found.ok) {
      if (found.error === "rate_limited" || found.error === "provider_error") {
        searchError = `search_${found.error}`;
        if (found.error === "rate_limited") break; // no point trying the next legs
      } else notFound++; // not_found, or a date outside yesterday … +1 year
      continue;
    }
    for (const leg of pickLegs(found.legs, f.from, f.to)) {
      if (added.includes(leg.id)) continue;
      const r = await deps.track(user.userId, leg.id, pnr);
      if (r === "added") added.push(leg.id);
      else if (r === "exists") exists++;
      else limit++;
    }
  }

  if (added.length) return done("added", limit ? "tracking_limit" : null, added);
  if (exists) return done("added", "already_tracked");
  if (limit) return done("error", "tracking_limit");
  if (searchError) return done("error", searchError);
  return done("no_flights", notFound ? "not_found" : noDate ? "no_date" : null);
}

/** POST handler. 401 on a bad secret; otherwise 200 with what happened (providers must not retry). */
export async function handleInboundEmail(req: Request, deps: InboundDeps): Promise<Response> {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  if (!deps.secret) return json({ error: "not_configured" }, 503);
  if (!safeEqual(req.headers.get("x-inbound-secret") ?? "", deps.secret)) return json({ error: "unauthorized" }, 401);

  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > MAX_REQUEST_BYTES) return json({ ok: true, status: "ignored", reason: "too_large" });
  let raw: unknown;
  try {
    const buf = new Uint8Array(await req.arrayBuffer());
    if (buf.length > MAX_REQUEST_BYTES) return json({ ok: true, status: "ignored", reason: "too_large" });
    raw = JSON.parse(new TextDecoder().decode(buf));
  } catch {
    return json({ error: "bad_input", message: "JSON body required" }, 400);
  }
  const email = parseInboundEmail(raw);
  if (!email) return json({ error: "bad_input", message: "to required" }, 400);

  const token = extractInboxToken(email.to);
  const user = token ? await deps.findUser(token) : null;
  if (!user) return json({ ok: true, status: "ignored", reason: "unknown_recipient" });

  const base = {
    userId: user.userId,
    fromDomain: fromDomain(email.from),
    subjectHash: email.subject ? await sha256Hex(email.subject) : null,
  };
  let outcome: ImportOutcome;
  if (emailSizeBytes(email) > MAX_EMAIL_BYTES) {
    outcome = { status: "error", flightsAdded: 0, error: "too_large", flightIds: [] };
  } else if (!user.isPro) {
    outcome = { status: "not_pro", flightsAdded: 0, error: null, flightIds: [] };
  } else {
    try {
      outcome = await importEmail(deps, user, email);
    } catch (e) {
      deps.log("inbound-email failed", { userId: user.userId, error: e instanceof Error ? e.name : "error" });
      outcome = { status: "error", flightsAdded: 0, error: "internal", flightIds: [] };
    }
  }

  try {
    await deps.record({ ...base, status: outcome.status, flightsAdded: outcome.flightsAdded, error: outcome.error });
  } catch (e) {
    deps.log("inbound-email record failed", { userId: user.userId, error: e instanceof Error ? e.message : "error" });
  }
  if (outcome.flightsAdded > 0) await deps.notify(user, outcome.flightsAdded, outcome.flightIds[0] ?? null);
  deps.log("inbound-email", {
    userId: user.userId,
    status: outcome.status,
    added: outcome.flightsAdded,
    error: outcome.error,
    from: base.fromDomain,
  });
  return json({ ok: true, status: outcome.status, flights_added: outcome.flightsAdded });
}

/** Pushes the import notice to every device of the user with notifications on. */
export async function sendImportPush(
  devices: { id: string; fcm_token: string }[],
  user: InboundUser,
  count: number,
  firstFlightId: string | null,
  send: (m: FcmMessage) => Promise<PushResult>,
): Promise<{ deviceId: string; token: string; result: PushResult }[]> {
  const out: { deviceId: string; token: string; result: PushResult }[] = [];
  for (const d of devices) {
    let result: PushResult;
    try {
      result = await send(buildImportMessage(d.fcm_token, count, firstFlightId, user.alertLanguage));
    } catch (e) {
      result = { ok: false, error: e instanceof Error ? e.message : "error" };
    }
    out.push({ deviceId: d.id, token: d.fcm_token, result });
  }
  return out;
}
