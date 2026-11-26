// Push alert texts in English, Hindi (Devanagari) and Hinglish (Roman Hindi). docs/P2_CONTRACTS.md section 4.
//
// Rules for every template:
//  * Flight numbers, IATA codes, gates, belts, minutes and times stay in Latin letters and digits, in every language.
//  * Times are airport-local ("18:50"). An unknown time is LEFT OUT of the sentence: an alert never shows a
//    placeholder such as "--:--", and never "undefined" or "null".
//  * Each language is a Record over EventType, so the compiler rejects a language that misses an event type.
import type { AlertLanguage, EventType, FlightEvent } from "./types.ts";
import { ALERT_LANGUAGES } from "./types.ts";

export interface Rendered {
  title: string;
  body: string;
}

/** Time zones used to print times: the departure airport's and the (current) arrival airport's. */
export interface Tz {
  dep: string | null;
  arr: string | null;
}

/** Unknown or missing language → English (the default of profiles.alert_language). */
export function alertLanguage(v: unknown): AlertLanguage {
  return (ALERT_LANGUAGES as readonly string[]).includes(String(v)) ? v as AlertLanguage : "en";
}

/** "18:50" in the given zone (Latin digits), or null when the time is unknown or not a date. */
export function localTime(iso: unknown, tz: string | null): string | null {
  if (typeof iso !== "string" || !iso || Number.isNaN(Date.parse(iso))) return null;
  try {
    return new Intl.DateTimeFormat("en-IN", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
      timeZone: tz ?? "Asia/Kolkata",
    }).format(new Date(iso));
  } catch {
    // a bad zone name must not lose the alert
    return new Intl.DateTimeFormat("en-IN", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
      timeZone: "Asia/Kolkata",
    }).format(new Date(iso));
  }
}

/** City names for the airports the fog alerts cover (the metros of section 1). Others print the IATA code. */
const CITY: Record<string, { en: string; hi: string }> = {
  DEL: { en: "Delhi", hi: "दिल्ली" },
  BOM: { en: "Mumbai", hi: "मुंबई" },
  BLR: { en: "Bengaluru", hi: "बेंगलुरु" },
  HYD: { en: "Hyderabad", hi: "हैदराबाद" },
  MAA: { en: "Chennai", hi: "चेन्नई" },
  CCU: { en: "Kolkata", hi: "कोलकाता" },
  COK: { en: "Kochi", hi: "कोच्चि" },
  AMD: { en: "Ahmedabad", hi: "अहमदाबाद" },
  PNQ: { en: "Pune", hi: "पुणे" },
  GOI: { en: "Goa", hi: "गोवा" },
  GOX: { en: "Goa", hi: "गोवा" },
  LKO: { en: "Lucknow", hi: "लखनऊ" },
  ATQ: { en: "Amritsar", hi: "अमृतसर" },
  JAI: { en: "Jaipur", hi: "जयपुर" },
  VNS: { en: "Varanasi", hi: "वाराणसी" },
  PAT: { en: "Patna", hi: "पटना" },
  IXC: { en: "Chandigarh", hi: "चंडीगढ़" },
  SXR: { en: "Srinagar", hi: "श्रीनगर" },
  GAU: { en: "Guwahati", hi: "गुवाहाटी" },
  DXB: { en: "Dubai", hi: "दुबई" },
};

/** Everything a template needs, already made safe: missing values are null, never "undefined". */
interface Ctx {
  f: string; // "6E 2341"
  route: string; // "DEL→BOM" (or just the flight's dep or arr when one is missing)
  dep: string | null;
  arr: string | null;
  oldV: string | null;
  newV: string | null;
  p: Record<string, unknown>;
  depTime: (key: string) => string | null; // a payload time at the departure airport
  arrTime: (key: string) => string | null; // a payload time at the arrival airport
  str: (key: string) => string | null; // a non-empty payload string or number, as text
  num: (key: string) => number | null;
}

function text(v: unknown): string | null {
  if (typeof v === "number" && Number.isFinite(v)) return String(v);
  if (typeof v === "string" && v.trim() && v !== "undefined" && v !== "null") return v.trim();
  return null;
}

function context(e: FlightEvent, tz: Tz): Ctx {
  const p = (e.payload ?? {}) as Record<string, unknown>;
  const dep = text(p.dep), arr = text(p.arr);
  return {
    f: text(p.flight) ?? "",
    route: dep && arr ? `${dep}→${arr}` : dep ?? arr ?? "",
    dep,
    arr,
    oldV: text(e.oldValue),
    newV: text(e.newValue),
    p,
    depTime: (k) => localTime(p[k], tz.dep),
    arrTime: (k) => localTime(p[k], tz.arr),
    str: (k) => text(p[k]),
    num: (k) => {
      const n = typeof p[k] === "number"
        ? p[k] as number
        : typeof p[k] === "string" && p[k] !== ""
        ? Number(p[k])
        : NaN;
      return Number.isFinite(n) ? n : null;
    },
  };
}

/** Joins sentence parts, dropping the empty ones. */
const join = (...parts: (string | null | false | undefined)[]) => parts.filter(Boolean).join(" ");

/** 300 → "300 m", 1200 → "1.2 km". */
function visibility(m: number | null): string | null {
  if (m == null || m < 0) return null;
  if (m >= 9999) return "10 km+";
  return m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1).replace(/\.0$/, "")} km`;
}

export function city(iata: string | null, lang: AlertLanguage, fallback: string | null): string {
  const c = iata ? CITY[iata.toUpperCase()] : undefined;
  if (c) return lang === "hi" ? c.hi : c.en;
  return (lang === "hi" ? null : fallback) ?? iata ?? "";
}

function inbound(c: Ctx): { flight: string | null; from: string | null } {
  const ib = (c.p.inbound ?? {}) as Record<string, unknown>;
  return { flight: text(ib.flight), from: text(ib.from) };
}

/** "Airbus A320neo VT-ISR", "VT-ISR" or null (AIRCRAFT_SWAP). */
function plane(c: Ctx, side: "old" | "new"): string | null {
  const reg = c.str(`${side}_reg`) ?? (side === "old" ? c.oldV : c.newV), model = c.str(`${side}_model`);
  return join(model, reg) || null;
}

/** Minutes the connection is short of the minimum connection time (positive), or null. */
function shortBy(c: Ctx): number | null {
  const b = c.num("buffer_min");
  return b != null && b < 0 ? Math.abs(Math.round(b)) : null;
}

type Templates = Record<EventType, (c: Ctx) => Rendered>;

// ---------------------------------------------------------------- English
const EN: Templates = {
  GATE_CHANGE: (c) => {
    const t = c.str("terminal"), d = c.depTime("estDep");
    return {
      title: `Gate change: ${c.f} → Gate ${c.newV ?? ""}${t ? ` (T${t})` : ""}`,
      body: join(c.oldV && `Was gate ${c.oldV}.`, `${c.route}${d ? `, departs ${d}` : ""}.`),
    };
  },
  GATE_ASSIGNED: (c) => {
    const t = c.str("terminal"), d = c.depTime("estDep");
    return {
      title: `${c.f}: Gate ${c.newV ?? ""}`,
      body: t && d ? `Terminal ${t}, departs ${d}.` : t ? `Terminal ${t}.` : d ? `Departs ${d}.` : `${c.route}.`,
    };
  },
  TERMINAL_CHANGE: (c) => ({
    title: `Terminal change: ${c.f} now T${c.newV ?? ""}`,
    body: join(c.oldV && `Was T${c.oldV}${c.str("airport") ? ` at ${c.str("airport")}` : ""}.`, "Leave extra time."),
  }),
  DELAY: (c) => {
    const est = c.depTime("estDep"), was = c.depTime("schedDep"), r = c.str("reason");
    return {
      title: `Delayed ${c.num("delayMin") ?? c.newV ?? ""} min: ${c.f}`,
      body: join(
        est ? `${c.route} now departs ${est}${was ? ` (was ${was})` : ""}.` : `${c.route} is delayed.`,
        r && `Reason: ${r}`,
      ),
    };
  },
  SCHEDULE_CHANGE: (c) => {
    const nw = c.depTime("newDep"), old = c.depTime("oldDep"), shift = Math.abs(c.num("shiftMin") ?? 0);
    const dir = c.p.direction === "earlier" ? "earlier" : "later";
    return {
      title: nw ? `Rescheduled: ${c.f} now ${nw}` : `Rescheduled: ${c.f}`,
      body: `The airline moved ${c.route} ${shift} min ${dir}${old ? ` (was ${old})` : ""}.`,
    };
  },
  DELAY_REDUCED: (c) => {
    const m = c.num("delayMin") ?? 0, d = c.depTime("estDep");
    return {
      title: m < 5 ? `${c.f} back on time` : `${c.f}: delay reduced to ${m} min`,
      body: d ? `${c.route} now departs ${d}.` : `${c.route}.`,
    };
  },
  BOARDING: (c) => {
    const g = c.str("gate"), t = c.str("terminal");
    return { title: `Boarding now: ${c.f}`, body: g ? `Gate ${g}${t ? `, T${t}` : ""}.` : c.route };
  },
  DEPARTED: (c) => {
    const off = c.depTime("actDep"), eta = c.arrTime("estArr");
    return {
      title: `${c.f} has departed`,
      body: join(off && `Took off ${off}.`, eta ? `Expected at ${c.arr ?? ""} ${eta}.` : `Heading to ${c.arr ?? ""}.`),
    };
  },
  LANDED: (c) => {
    const on = c.arrTime("actArr"), belt = c.str("belt");
    return {
      title: `${c.f} landed at ${c.arr ?? ""}`,
      body: join(on && `Landed ${on}.`, belt && `Baggage belt ${belt}.`) || `Welcome to ${c.arr ?? ""}.`,
    };
  },
  BELT_ASSIGNED: (c) => {
    const t = c.str("arrTerminal");
    return { title: `Baggage belt ${c.newV ?? ""}: ${c.f}`, body: `${t ? `Terminal ${t}, ` : ""}${c.arr ?? ""}.` };
  },
  BELT_CHANGE: (c) => ({
    title: `Belt change: ${c.f} → Belt ${c.newV ?? ""}`,
    body: c.oldV ? `Was belt ${c.oldV}.` : "Check the screens at baggage claim.",
  }),
  CANCELLED: (c) => ({
    title: `Cancelled: ${c.f}`,
    body: `${c.route} has been cancelled. Check with the airline for rebooking/refund.`,
  }),
  REINSTATED: (c) => {
    const d = c.depTime("estDep");
    return { title: `${c.f} is back on`, body: join("The cancellation was withdrawn.", d && `Departs ${d}.`) };
  },
  DIVERTED: (c) => {
    // tz.arr is the row's CURRENT arrival airport (= diversion airport once known)
    const orig = c.str("origArr") ?? c.arr ?? "", nw = c.str("newArr");
    const eta = nw ? c.arrTime("estArr") : null;
    return {
      title: `Diverted: ${c.f}`,
      body: nw
        ? `Not landing at ${orig}. Now heading to ${nw}${eta ? `, ETA ${eta} local` : ""}.`
        : `Not landing at ${orig} as planned. New destination not announced yet.`,
    };
  },
  DIVERTED_DEST: (c) => {
    const orig = c.str("origArr"), nw = c.str("newArr") ?? c.arr ?? "", eta = c.arrTime("estArr");
    return {
      title: `${c.f} diverted to ${nw}`,
      body: join(orig && `Was going to ${orig}.`, `Now landing at ${nw}${eta ? `, ETA ${eta} local` : ""}.`),
    };
  },
  CHECKIN_OPEN: (c) => {
    const closes = c.depTime("closes_at"), d = c.depTime("estDep");
    return {
      title: `Web check-in is open: ${c.f}`,
      body: join(`${c.route}${d ? ` departs ${d}` : ""}.`, closes && `Check in online before ${closes}.`),
    };
  },
  CHECKIN_CLOSING: (c) => {
    const closes = c.depTime("closes_at");
    return {
      title: `Check-in closes soon: ${c.f}`,
      body: `Web check-in for ${c.route} closes${closes ? ` at ${closes}` : " soon"}. Check in now.`,
    };
  },
  INBOUND_LATE: (c) => {
    const ib = inbound(c), pred = c.depTime("predictedDep");
    const plane = ib.flight ? `Your plane (${ib.flight}${ib.from ? ` from ${ib.from}` : ""})` : "Your plane";
    return {
      title: `${c.f} likely ~${c.num("lateMin") ?? ""} min late`,
      body: join(`${plane} is running late.`, pred && `Expected departure ~${pred}.`, "Airline hasn't announced yet."),
    };
  },
  FOG_ALERT: (c) => {
    const ap = c.str("airport"), vis = visibility(c.num("visibility_m"));
    const where = city(ap, "en", c.str("city"));
    return {
      title: `Fog at ${where}${vis ? `: visibility ${vis}` : ""}`,
      body: c.p.side === "arr"
        ? (c.f ? `Arrival delays or holding likely for ${c.f}.` : "Arrival delays or holding likely.")
        : (c.f ? `Delays likely for ${c.f}.` : "Delays likely."),
    };
  },
  GATE_CLOSED: (c) => {
    const g = c.str("gate"), t = c.str("terminal");
    return {
      title: `Gate closed: ${c.f}`,
      body: g ? `Boarding at gate ${g}${t ? ` (T${t})` : ""} has closed.` : `Boarding for ${c.route} has closed.`,
    };
  },
  TAKEOFF: (c) => {
    const up = c.depTime("runwayAt"), eta = c.arrTime("estArr");
    return {
      title: `${c.f} took off`,
      body: join(up && `Wheels up ${up}.`, eta ? `Expected at ${c.arr ?? ""} ${eta}.` : `Heading to ${c.arr ?? ""}.`),
    };
  },
  AT_GATE: (c) => {
    const on = c.arrTime("onblockAt"), g = c.str("arrGate"), belt = c.str("belt");
    return {
      title: `${c.f} is at the gate${c.arr ? ` in ${c.arr}` : ""}`,
      body: join(g ? `Gate ${g}${on ? `, ${on}` : ""}.` : on && `Parked ${on}.`, belt && `Baggage belt ${belt}.`) ||
        "You can get off soon.",
    };
  },
  AIRCRAFT_SWAP: (c) => {
    const nw = plane(c, "new"), old = plane(c, "old");
    return {
      title: `Aircraft change: ${c.f}`,
      body: join(
        nw ? `${c.route} will now fly on ${nw}${old ? ` (was ${old})` : ""}.` : `${c.route} has a different aircraft.`,
        "Check your seat in the airline app.",
      ),
    };
  },
  CONNECTION_AT_RISK: (c) => {
    const out = c.str("out_flight"), ap = c.str("airport") ?? c.arr, d = c.arrTime("out_dep"), short = shortBy(c);
    return {
      title: out ? `Connection at risk: ${c.f} → ${out}` : `Connection at risk after ${c.f}`,
      body: join(
        `${c.f} is running late${ap ? ` into ${ap}` : ""}.`,
        out && `${out}${d ? ` departs ${d}` : " departs soon"}${short ? `, ${short} min short of the minimum connection time` : ""}.`,
        "Talk to the airline about your options.",
      ),
    };
  },
};

// ---------------------------------------------------------------- Hindi (Devanagari)
const HI: Templates = {
  GATE_CHANGE: (c) => {
    const t = c.str("terminal"), d = c.depTime("estDep");
    return {
      title: `गेट बदला: ${c.f} अब गेट ${c.newV ?? ""} से${t ? ` (T${t})` : ""}`,
      body: join(c.oldV && `पहले गेट ${c.oldV} था।`, `${c.route}${d ? `, ${d} बजे रवाना होगी` : ""}।`),
    };
  },
  GATE_ASSIGNED: (c) => {
    const t = c.str("terminal"), d = c.depTime("estDep");
    return {
      title: `${c.f}: गेट ${c.newV ?? ""}`,
      body: t && d
        ? `टर्मिनल ${t}, ${d} बजे रवाना होगी।`
        : t
        ? `टर्मिनल ${t}।`
        : d
        ? `${d} बजे रवाना होगी।`
        : `${c.route}।`,
    };
  },
  TERMINAL_CHANGE: (c) => {
    const ap = c.str("airport");
    return {
      title: `टर्मिनल बदला: ${c.f} अब T${c.newV ?? ""} से`,
      body: join(c.oldV && `${ap ? `${ap} पर ` : ""}पहले T${c.oldV} था।`, "थोड़ा जल्दी निकलें।"),
    };
  },
  DELAY: (c) => {
    const est = c.depTime("estDep"), was = c.depTime("schedDep"), r = c.str("reason");
    return {
      title: `${c.f}: ${c.num("delayMin") ?? c.newV ?? ""} मिनट की देरी`, // ":" keeps the two numbers apart
      body: join(
        est ? `${c.route} अब ${est} बजे रवाना होगी${was ? ` (पहले ${was})` : ""}।` : `${c.route} में देरी है।`,
        r && `कारण: ${r}`,
      ),
    };
  },
  SCHEDULE_CHANGE: (c) => {
    const nw = c.depTime("newDep"), old = c.depTime("oldDep"), shift = Math.abs(c.num("shiftMin") ?? 0);
    const dir = c.p.direction === "earlier" ? "पहले" : "बाद";
    return {
      title: nw ? `समय बदला: ${c.f} अब ${nw} बजे` : `समय बदला: ${c.f}`,
      body: `एयरलाइन ने ${c.route} की उड़ान ${shift} मिनट ${dir} कर दी है${old ? ` (पहले ${old})` : ""}।`,
    };
  },
  DELAY_REDUCED: (c) => {
    const m = c.num("delayMin") ?? 0, d = c.depTime("estDep");
    return {
      title: m < 5 ? `${c.f} फिर से समय पर` : `${c.f}: देरी घटकर ${m} मिनट`,
      body: d ? `${c.route} अब ${d} बजे रवाना होगी।` : `${c.route}।`,
    };
  },
  BOARDING: (c) => {
    const g = c.str("gate"), t = c.str("terminal");
    return { title: `बोर्डिंग शुरू: ${c.f}`, body: g ? `गेट ${g}${t ? `, T${t}` : ""}।` : c.route };
  },
  DEPARTED: (c) => {
    const off = c.depTime("actDep"), eta = c.arrTime("estArr");
    return {
      title: `${c.f} रवाना हो गई`,
      body: join(
        off && `${off} बजे उड़ान भरी।`,
        eta ? `${c.arr ?? ""} ${eta} बजे पहुँचने की उम्मीद।` : `${c.arr ?? ""} की ओर जा रही है।`,
      ),
    };
  },
  LANDED: (c) => {
    const on = c.arrTime("actArr"), belt = c.str("belt");
    return {
      title: `${c.f} ${c.arr ?? ""} पहुँच गई`,
      body: join(on && `${on} बजे लैंड हुई।`, belt && `बैगेज बेल्ट ${belt}।`) || `${c.arr ?? ""} में आपका स्वागत है।`,
    };
  },
  BELT_ASSIGNED: (c) => {
    const t = c.str("arrTerminal");
    return { title: `बैगेज बेल्ट ${c.newV ?? ""}: ${c.f}`, body: `${t ? `टर्मिनल ${t}, ` : ""}${c.arr ?? ""}।` };
  },
  BELT_CHANGE: (c) => ({
    title: `बेल्ट बदली: ${c.f} अब बेल्ट ${c.newV ?? ""}`,
    body: c.oldV ? `पहले बेल्ट ${c.oldV} थी।` : "बैगेज क्लेम की स्क्रीन देखें।",
  }),
  CANCELLED: (c) => ({
    title: `रद्द: ${c.f}`,
    body: `${c.route} की उड़ान रद्द हो गई है। रीबुकिंग या रिफ़ंड के लिए एयरलाइन से संपर्क करें।`,
  }),
  REINSTATED: (c) => {
    const d = c.depTime("estDep");
    return {
      title: `${c.f} फिर से चालू`,
      body: join("उड़ान रद्द करने का फ़ैसला वापस ले लिया गया है।", d && `${d} बजे रवाना होगी।`),
    };
  },
  DIVERTED: (c) => {
    const orig = c.str("origArr") ?? c.arr ?? "", nw = c.str("newArr");
    const eta = nw ? c.arrTime("estArr") : null;
    return {
      title: `डायवर्ट: ${c.f}`,
      body: nw
        ? `${orig} पर नहीं उतरेगी। अब ${nw} जा रही है${eta ? `, स्थानीय समय ${eta} बजे पहुँचेगी` : ""}।`
        : `तय योजना के अनुसार ${orig} पर नहीं उतरेगी। नई मंज़िल अभी घोषित नहीं हुई है।`,
    };
  },
  DIVERTED_DEST: (c) => {
    const orig = c.str("origArr"), nw = c.str("newArr") ?? c.arr ?? "", eta = c.arrTime("estArr");
    return {
      title: `${c.f} अब ${nw} जा रही है`,
      body: join(orig && `पहले ${orig} जाना था।`, `अब ${nw} में उतरेगी${eta ? `, स्थानीय समय ${eta} बजे` : ""}।`),
    };
  },
  CHECKIN_OPEN: (c) => {
    const closes = c.depTime("closes_at"), d = c.depTime("estDep");
    return {
      title: `वेब चेक-इन खुल गया: ${c.f}`,
      body: join(`${c.route}${d ? `, ${d} बजे रवाना होगी` : ""}।`, closes && `${closes} बजे से पहले ऑनलाइन चेक-इन करें।`),
    };
  },
  CHECKIN_CLOSING: (c) => {
    const closes = c.depTime("closes_at");
    return {
      title: `चेक-इन जल्द बंद होगा: ${c.f}`,
      body: `${c.route} का वेब चेक-इन ${closes ? `${closes} बजे` : "जल्द ही"} बंद होगा। अभी चेक-इन करें।`,
    };
  },
  INBOUND_LATE: (c) => {
    const ib = inbound(c), pred = c.depTime("predictedDep");
    const plane = ib.flight ? `आपका विमान (${ib.flight}${ib.from ? `, ${ib.from} से` : ""})` : "आपका विमान";
    return {
      title: `${c.f} करीब ${c.num("lateMin") ?? ""} मिनट लेट हो सकती है`,
      body: join(`${plane} देर से चल रहा है।`, pred && `अनुमानित रवानगी ~${pred} बजे।`, "एयरलाइन ने अभी घोषणा नहीं की है।"),
    };
  },
  FOG_ALERT: (c) => {
    const ap = c.str("airport"), vis = visibility(c.num("visibility_m"));
    const where = city(ap, "hi", c.str("city"));
    return {
      title: `${where} में कोहरा${vis ? `: विज़िबिलिटी ${vis}` : ""}`,
      body: c.p.side === "arr"
        ? (c.f ? `${c.f} की लैंडिंग में देरी हो सकती है।` : "लैंडिंग में देरी हो सकती है।")
        : (c.f ? `${c.f} में देरी हो सकती है।` : "उड़ानों में देरी हो सकती है।"),
    };
  },
  GATE_CLOSED: (c) => {
    const g = c.str("gate"), t = c.str("terminal");
    return {
      title: `गेट बंद: ${c.f}`,
      body: g ? `गेट ${g}${t ? ` (T${t})` : ""} पर बोर्डिंग बंद हो गई है।` : `${c.route} की बोर्डिंग बंद हो गई है।`,
    };
  },
  TAKEOFF: (c) => {
    const up = c.depTime("runwayAt"), eta = c.arrTime("estArr");
    return {
      title: `${c.f} ने उड़ान भरी`,
      body: join(
        up && `${up} बजे टेक-ऑफ़ हुआ।`,
        eta ? `${c.arr ?? ""} ${eta} बजे पहुँचने की उम्मीद।` : `${c.arr ?? ""} की ओर जा रही है।`,
      ),
    };
  },
  AT_GATE: (c) => {
    const on = c.arrTime("onblockAt"), g = c.str("arrGate"), belt = c.str("belt");
    return {
      title: `${c.f} गेट पर पहुँच गई`,
      body: join(g ? `गेट ${g}${on ? `, ${on} बजे` : ""}।` : on && `${on} बजे गेट पर पहुँची।`, belt && `बैगेज बेल्ट ${belt}।`) ||
        "जल्द ही विमान से उतर सकेंगे।",
    };
  },
  AIRCRAFT_SWAP: (c) => {
    const nw = plane(c, "new"), old = plane(c, "old");
    return {
      title: `विमान बदला: ${c.f}`,
      body: join(
        nw ? `${c.route} अब ${nw} से जाएगी${old ? ` (पहले ${old})` : ""}।` : `${c.route} का विमान बदल गया है।`,
        "एयरलाइन ऐप में अपनी सीट जाँच लें।",
      ),
    };
  },
  CONNECTION_AT_RISK: (c) => {
    const out = c.str("out_flight"), ap = c.str("airport") ?? c.arr, d = c.arrTime("out_dep"), short = shortBy(c);
    return {
      title: out ? `कनेक्शन खतरे में: ${c.f} → ${out}` : `${c.f} के बाद का कनेक्शन खतरे में`,
      body: join(
        `${c.f}${ap ? ` ${ap}` : ""} देर से पहुँच रही है।`,
        out && `${out}${d ? ` ${d} बजे` : " जल्द"} रवाना होगी${short ? `, न्यूनतम कनेक्शन समय से ${short} मिनट कम` : ""}।`,
        "विकल्पों के लिए एयरलाइन से बात करें।",
      ),
    };
  },
};

// ---------------------------------------------------------------- Hinglish (Roman Hindi)
const HINGLISH: Templates = {
  GATE_CHANGE: (c) => {
    const t = c.str("terminal"), d = c.depTime("estDep");
    return {
      title: `Gate change: ${c.f} ab Gate ${c.newV ?? ""} se${t ? ` (T${t})` : ""}`,
      body: join(c.oldV && `Pehle gate ${c.oldV} tha.`, `${c.route}${d ? `, ${d} baje departure` : ""}.`),
    };
  },
  GATE_ASSIGNED: (c) => {
    const t = c.str("terminal"), d = c.depTime("estDep");
    return {
      title: `${c.f}: Gate ${c.newV ?? ""}`,
      body: t && d
        ? `Terminal ${t}, ${d} baje departure.`
        : t
        ? `Terminal ${t}.`
        : d
        ? `${d} baje departure.`
        : `${c.route}.`,
    };
  },
  TERMINAL_CHANGE: (c) => {
    const ap = c.str("airport");
    return {
      title: `Terminal change: ${c.f} ab T${c.newV ?? ""} se`,
      body: join(c.oldV && `${ap ? `${ap} par ` : ""}pehle T${c.oldV} tha.`, "Thoda jaldi niklein."),
    };
  },
  DELAY: (c) => {
    const est = c.depTime("estDep"), was = c.depTime("schedDep"), r = c.str("reason");
    return {
      title: `${c.f}: ${c.num("delayMin") ?? c.newV ?? ""} min late hai`, // ":" keeps the two numbers apart
      body: join(
        est ? `${c.route} ab ${est} baje udegi${was ? ` (pehle ${was} thi)` : ""}.` : `${c.route} late hai.`,
        r && `Reason: ${r}`,
      ),
    };
  },
  SCHEDULE_CHANGE: (c) => {
    const nw = c.depTime("newDep"), old = c.depTime("oldDep"), shift = Math.abs(c.num("shiftMin") ?? 0);
    const dir = c.p.direction === "earlier" ? "pehle" : "baad mein";
    return {
      title: nw ? `Time change: ${c.f} ab ${nw} baje` : `Time change: ${c.f}`,
      body: `Airline ne ${c.route} flight ${shift} min ${dir} kar di hai${old ? ` (pehle ${old} thi)` : ""}.`,
    };
  },
  DELAY_REDUCED: (c) => {
    const m = c.num("delayMin") ?? 0, d = c.depTime("estDep");
    return {
      title: m < 5 ? `${c.f} wapas on time` : `${c.f}: delay ab sirf ${m} min`,
      body: d ? `${c.route} ab ${d} baje udegi.` : `${c.route}.`,
    };
  },
  BOARDING: (c) => {
    const g = c.str("gate"), t = c.str("terminal");
    return { title: `Boarding shuru: ${c.f}`, body: g ? `Gate ${g}${t ? `, T${t}` : ""}.` : c.route };
  },
  DEPARTED: (c) => {
    const off = c.depTime("actDep"), eta = c.arrTime("estArr");
    return {
      title: `${c.f} ud gayi`,
      body: join(
        off && `${off} baje take-off hua.`,
        eta ? `${c.arr ?? ""} ${eta} baje pahunchne ki ummeed.` : `${c.arr ?? ""} ki taraf ja rahi hai.`,
      ),
    };
  },
  LANDED: (c) => {
    const on = c.arrTime("actArr"), belt = c.str("belt");
    return {
      title: `${c.f} ${c.arr ?? ""} pahunch gayi`,
      body: join(on && `${on} baje land hui.`, belt && `Baggage belt ${belt}.`) ||
        `${c.arr ?? ""} mein aapka swagat hai.`,
    };
  },
  BELT_ASSIGNED: (c) => {
    const t = c.str("arrTerminal");
    return { title: `Baggage belt ${c.newV ?? ""}: ${c.f}`, body: `${t ? `Terminal ${t}, ` : ""}${c.arr ?? ""}.` };
  },
  BELT_CHANGE: (c) => ({
    title: `Belt change: ${c.f} ab Belt ${c.newV ?? ""}`,
    body: c.oldV ? `Pehle belt ${c.oldV} thi.` : "Baggage claim ki screen check karein.",
  }),
  CANCELLED: (c) => ({
    title: `Cancel: ${c.f}`,
    body: `${c.route} flight cancel ho gayi hai. Rebooking/refund ke liye airline se baat karein.`,
  }),
  REINSTATED: (c) => {
    const d = c.depTime("estDep");
    return {
      title: `${c.f} phir se on hai`,
      body: join("Cancellation wapas le li gayi hai.", d && `${d} baje udegi.`),
    };
  },
  DIVERTED: (c) => {
    const orig = c.str("origArr") ?? c.arr ?? "", nw = c.str("newArr");
    const eta = nw ? c.arrTime("estArr") : null;
    return {
      title: `Divert: ${c.f}`,
      body: nw
        ? `${orig} par land nahi karegi. Ab ${nw} ja rahi hai${eta ? `, ETA ${eta} local` : ""}.`
        : `Plan ke hisaab se ${orig} par land nahi karegi. Nayi destination abhi announce nahi hui.`,
    };
  },
  DIVERTED_DEST: (c) => {
    const orig = c.str("origArr"), nw = c.str("newArr") ?? c.arr ?? "", eta = c.arrTime("estArr");
    return {
      title: `${c.f} ab ${nw} ja rahi hai`,
      body: join(orig && `Pehle ${orig} jaana tha.`, `Ab ${nw} mein land karegi${eta ? `, ETA ${eta} local` : ""}.`),
    };
  },
  CHECKIN_OPEN: (c) => {
    const closes = c.depTime("closes_at"), d = c.depTime("estDep");
    return {
      title: `Web check-in khul gaya: ${c.f}`,
      body: join(
        `${c.route}${d ? `, ${d} baje departure` : ""}.`,
        closes && `${closes} se pehle online check-in kar lein.`,
      ),
    };
  },
  CHECKIN_CLOSING: (c) => {
    const closes = c.depTime("closes_at");
    return {
      title: `Check-in jaldi band hoga: ${c.f}`,
      body: `${c.route} ka web check-in ${closes ? `${closes} baje` : "jaldi"} band hoga. Abhi check-in kar lein.`,
    };
  },
  INBOUND_LATE: (c) => {
    const ib = inbound(c), pred = c.depTime("predictedDep");
    const plane = ib.flight ? `Aapka plane (${ib.flight}${ib.from ? `, ${ib.from} se` : ""})` : "Aapka plane";
    return {
      title: `${c.f} ~${c.num("lateMin") ?? ""} min late ho sakti hai`,
      body: join(
        `${plane} late chal raha hai.`,
        pred && `Expected departure ~${pred}.`,
        "Airline ne abhi announce nahi kiya.",
      ),
    };
  },
  FOG_ALERT: (c) => {
    const ap = c.str("airport"), vis = visibility(c.num("visibility_m"));
    const where = city(ap, "hinglish", c.str("city"));
    return {
      title: `${where} mein fog${vis ? `: visibility ${vis}` : ""}`,
      body: c.p.side === "arr"
        ? (c.f ? `${c.f} ki landing mein der ho sakti hai.` : "Landing mein der ho sakti hai.")
        : (c.f ? `${c.f} late ho sakti hai.` : "Flights late ho sakti hain."),
    };
  },
  GATE_CLOSED: (c) => {
    const g = c.str("gate"), t = c.str("terminal");
    return {
      title: `Gate band: ${c.f}`,
      body: g ? `Gate ${g}${t ? ` (T${t})` : ""} par boarding band ho gayi hai.` : `${c.route} ki boarding band ho gayi hai.`,
    };
  },
  TAKEOFF: (c) => {
    const up = c.depTime("runwayAt"), eta = c.arrTime("estArr");
    return {
      title: `${c.f} ka take-off ho gaya`,
      body: join(
        up && `${up} baje wheels up.`,
        eta ? `${c.arr ?? ""} ${eta} baje pahunchne ki ummeed.` : `${c.arr ?? ""} ki taraf ja rahi hai.`,
      ),
    };
  },
  AT_GATE: (c) => {
    const on = c.arrTime("onblockAt"), g = c.str("arrGate"), belt = c.str("belt");
    return {
      title: `${c.f} gate par pahunch gayi`,
      body: join(g ? `Gate ${g}${on ? `, ${on} baje` : ""}.` : on && `${on} baje gate par pahunchi.`, belt && `Baggage belt ${belt}.`) ||
        "Jaldi hi utar payenge.",
    };
  },
  AIRCRAFT_SWAP: (c) => {
    const nw = plane(c, "new"), old = plane(c, "old");
    return {
      title: `Aircraft change: ${c.f}`,
      body: join(
        nw ? `${c.route} ab ${nw} se jayegi${old ? ` (pehle ${old} tha)` : ""}.` : `${c.route} ka aircraft badal gaya hai.`,
        "Airline app mein apni seat check kar lein.",
      ),
    };
  },
  CONNECTION_AT_RISK: (c) => {
    const out = c.str("out_flight"), ap = c.str("airport") ?? c.arr, d = c.arrTime("out_dep"), short = shortBy(c);
    return {
      title: out ? `Connection khatre mein: ${c.f} → ${out}` : `${c.f} ke baad ka connection khatre mein`,
      body: join(
        `${c.f}${ap ? ` ${ap}` : ""} late pahunch rahi hai.`,
        out && `${out}${d ? ` ${d} baje` : " jaldi"} udegi${short ? `, minimum connection time se ${short} min kam` : ""}.`,
        "Options ke liye airline se baat karein.",
      ),
    };
  },
};

export const TEMPLATES: Record<AlertLanguage, Templates> = { en: EN, hi: HI, hinglish: HINGLISH };

/** Title and body of one alert, in the recipient's language. */
export function renderAlert(e: FlightEvent, tz: Tz, lang: AlertLanguage | string | null = "en"): Rendered {
  const tpl = TEMPLATES[alertLanguage(lang)][e.type] ?? EN[e.type];
  if (!tpl) return { title: text((e.payload as Record<string, unknown>)?.flight) ?? "Flight update", body: "" };
  return tpl(context(e, tz));
}
