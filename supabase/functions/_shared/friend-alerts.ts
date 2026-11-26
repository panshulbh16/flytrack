// Friend alerts ("Mom's flight has landed"), docs/P2_WAVE2_CONTRACTS.md section 1.
//
// A friend gets an alert for a flight a friend of theirs tracks (and shares) when:
//  * the event is DEPARTED, LANDED, CANCELLED, REINSTATED (a friend told "cancelled" must hear it's back on), DIVERTED, DIVERTED_DEST, or a DELAY of 30 min or more, and
//  * the event is free (Pro-only events such as INBOUND_LATE never go to friends).
// get_flight_recipients() already leaves out friends who muted that friend, and friends who track the flight
// themselves (they get the normal tracker alert instead).
//
// Texts name the friend: "Asha's flight 6E 2341 has landed in Mumbai". Same rules as alert-text.ts:
// flight numbers, codes and times stay in Latin letters and digits; unknown values are left out.
import type { AlertLanguage, EventType, FlightEvent } from "./types.ts";
import { alertLanguage, city, localTime, type Rendered, type Tz } from "./alert-text.ts";

/** The event types friends may get (DELAY only from FRIEND_DELAY_MIN). */
export const FRIEND_EVENT_TYPES = ["DEPARTED", "LANDED", "CANCELLED", "REINSTATED", "DIVERTED", "DIVERTED_DEST", "DELAY"] as const;
export type FriendEventType = typeof FRIEND_EVENT_TYPES[number];

/** A friend hears about a delay only once it is worth changing plans for (picking someone up). */
export const FRIEND_DELAY_MIN = 30;

/**
 * Friend alerts that break through like a tracker's critical alert (and stay loud in quiet hours).
 * Everything else goes out as an info alert, so a 40-minute delay of a friend's flight doesn't buzz at 3 am.
 */
const FRIEND_CRITICAL: readonly EventType[] = ["CANCELLED", "REINSTATED", "DIVERTED", "DIVERTED_DEST"];

/** Max length of a friend's name inside an alert; display names may be up to 80 characters. */
const NAME_MAX = 40;

/** What the apps and the FCM friend_name show when the friend has no display name. */
export const FRIEND_NAME_FALLBACK: Record<AlertLanguage, string> = {
  en: "Your friend",
  hi: "आपका दोस्त",
  hinglish: "Aapka dost",
};

function delayMinutes(e: FlightEvent): number | null {
  const p = (e.payload ?? {}) as Record<string, unknown>;
  const v = typeof p.delayMin === "number" ? p.delayMin : Number(p.delayMin ?? e.newValue ?? NaN);
  return Number.isFinite(v) ? v : null;
}

/** May this event go to friends of the flight's trackers? */
export function isFriendEvent(e: FlightEvent): boolean {
  if (e.proOnly) return false;
  if (!(FRIEND_EVENT_TYPES as readonly string[]).includes(e.type)) return false;
  if (e.type === "DELAY") return (delayMinutes(e) ?? 0) >= FRIEND_DELAY_MIN;
  return true;
}

/** Is this friend alert loud (critical channel, time-sensitive on iOS)? */
export function isFriendCritical(t: EventType): boolean {
  return FRIEND_CRITICAL.includes(t);
}

/**
 * A display name made safe for one line of a notification: no control characters, line breaks or bidi
 * override / isolate controls, spaces collapsed, at most 40 characters. null when nothing is left (the templates then say "Your friend").
 */
export function cleanFriendName(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const s = v
    .replace(/[\u202a-\u202e\u2066-\u2069]/g, "") // bidi overrides / isolates: a name can't reorder the alert text
    // deno-lint-ignore no-control-regex
    .replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!s) return null;
  const chars = Array.from(s); // code points, so an emoji or a Devanagari letter is never cut in half
  return chars.length <= NAME_MAX ? s : `${chars.slice(0, NAME_MAX - 1).join("").trimEnd()}…`;
}

/** friend_name for the FCM data: the cleaned name, or "Your friend" in the recipient's language. */
export function friendNameForData(name: unknown, lang: AlertLanguage | string | null): string {
  return cleanFriendName(name) ?? FRIEND_NAME_FALLBACK[alertLanguage(lang)];
}

interface FriendCtx {
  name: string | null;
  f: string; // "6E 2341"
  route: string; // "DEL→BOM"
  depCity: string;
  arrCity: string;
  p: Record<string, unknown>;
  newValue: string | null;
  depTime: (key: string) => string | null;
  arrTime: (key: string) => string | null;
  str: (key: string) => string | null;
  cityOf: (iata: string | null) => string;
}

function text(v: unknown): string | null {
  if (typeof v === "number" && Number.isFinite(v)) return String(v);
  if (typeof v === "string" && v.trim() && v !== "undefined" && v !== "null") return v.trim();
  return null;
}

const join = (...parts: (string | null | false | undefined)[]) => parts.filter(Boolean).join(" ");

function context(e: FlightEvent, tz: Tz, lang: AlertLanguage, name: string | null): FriendCtx {
  const p = (e.payload ?? {}) as Record<string, unknown>;
  const dep = text(p.dep), arr = text(p.arr);
  const cityOf = (iata: string | null) => city(iata, lang, null);
  return {
    name,
    f: text(p.flight) ?? "",
    route: dep && arr ? `${dep}→${arr}` : dep ?? arr ?? "",
    depCity: cityOf(dep),
    arrCity: cityOf(arr),
    p,
    newValue: text(e.newValue),
    depTime: (k) => localTime(p[k], tz.dep),
    arrTime: (k) => localTime(p[k], tz.arr),
    str: (k) => text(p[k]),
    cityOf,
  };
}

type FriendTemplates = Record<FriendEventType, (c: FriendCtx, delayMin: number | null) => Rendered>;

// ---------------------------------------------------------------- English
const EN: FriendTemplates = {
  DEPARTED: (c) => {
    const off = c.depTime("actDep"), eta = c.arrTime("estArr");
    return {
      title: `${whose("en", c.name)} flight ${c.f} has departed${c.depCity ? ` from ${c.depCity}` : ""}`,
      body: join(
        off && `Took off ${off}.`,
        eta ? `Expected in ${c.arrCity} ${eta}.` : c.arrCity && `Heading to ${c.arrCity}.`,
      ) ||
        `${c.route}.`,
    };
  },
  LANDED: (c) => {
    const on = c.arrTime("actArr"), belt = c.str("belt");
    return {
      title: `${whose("en", c.name)} flight ${c.f} has landed${c.arrCity ? ` in ${c.arrCity}` : ""}`,
      body: join(on && `Landed ${on}.`, belt && `Baggage belt ${belt}.`) || `${c.route}.`,
    };
  },
  CANCELLED: (c) => ({
    title: `${whose("en", c.name)} flight ${c.f} has been cancelled`,
    body: join(c.route && `${c.route}.`, `Check with ${c.name ?? "your friend"} about new plans.`),
  }),
  REINSTATED: (c) => ({
    title: `${whose("en", c.name)} flight ${c.f} is back on`,
    body: join(c.route && `${c.route}.`, "The cancellation was withdrawn."),
  }),
  DIVERTED: (c) => {
    const orig = c.cityOf(c.str("origArr") ?? text(c.p.arr)), nw = c.str("newArr");
    return {
      title: `${whose("en", c.name)} flight ${c.f} has been diverted`,
      body: nw
        ? `Now heading to ${c.cityOf(nw)}${orig ? ` instead of ${orig}` : ""}.`
        : `Not landing at ${orig || "the planned airport"}. New destination not announced yet.`,
    };
  },
  DIVERTED_DEST: (c) => {
    const orig = c.str("origArr"), nw = c.cityOf(c.str("newArr") ?? text(c.p.arr)), eta = c.arrTime("estArr");
    return {
      title: `${whose("en", c.name)} flight ${c.f} is diverting to ${nw}`,
      body: join(orig && `Was going to ${c.cityOf(orig)}.`, eta && `ETA ${eta} local.`) || `${c.route}.`,
    };
  },
  DELAY: (c, m) => {
    const est = c.depTime("estDep");
    return {
      title: `${whose("en", c.name)} flight ${c.f} is delayed${m != null ? ` ${m} min` : ""}`,
      body: est ? `${c.route} now departs ${est}.` : `${c.route}.`,
    };
  },
};

// ---------------------------------------------------------------- Hindi (Devanagari)
const HI: FriendTemplates = {
  DEPARTED: (c) => {
    const off = c.depTime("actDep"), eta = c.arrTime("estArr");
    return {
      title: `${whose("hi", c.name)} फ़्लाइट ${c.f}${c.depCity ? ` ${c.depCity} से` : ""} रवाना हो गई`,
      body: join(
        off && `${off} बजे उड़ान भरी।`,
        eta ? `${c.arrCity} ${eta} बजे पहुँचने की उम्मीद।` : c.arrCity && `${c.arrCity} की ओर जा रही है।`,
      ) || `${c.route}।`,
    };
  },
  LANDED: (c) => {
    const on = c.arrTime("actArr"), belt = c.str("belt");
    return {
      title: `${whose("hi", c.name)} फ़्लाइट ${c.f}${c.arrCity ? ` ${c.arrCity}` : ""} पहुँच गई`,
      body: join(on && `${on} बजे लैंड हुई।`, belt && `बैगेज बेल्ट ${belt}।`) || `${c.route}।`,
    };
  },
  CANCELLED: (c) => ({
    title: `${whose("hi", c.name)} फ़्लाइट ${c.f} रद्द हो गई`,
    body: join(c.route && `${c.route}।`, `आगे की योजना के लिए ${c.name ?? "अपने दोस्त"} से बात करें।`),
  }),
  REINSTATED: (c) => ({
    title: `${whose("hi", c.name)} फ़्लाइट ${c.f} फिर से चलेगी`,
    body: join(c.route && `${c.route}।`, "रद्द करने का फ़ैसला वापस ले लिया गया।"),
  }),
  DIVERTED: (c) => {
    const orig = c.cityOf(c.str("origArr") ?? text(c.p.arr)), nw = c.str("newArr");
    return {
      title: `${whose("hi", c.name)} फ़्लाइट ${c.f} डायवर्ट हो गई`,
      body: nw
        ? `${orig ? `${orig} के बजाय ` : ""}अब ${c.cityOf(nw)} जा रही है।`
        : `${orig ? `तय योजना के अनुसार ${orig} पर` : "तय हवाई अड्डे पर"} नहीं उतरेगी। नई मंज़िल अभी घोषित नहीं हुई है।`,
    };
  },
  DIVERTED_DEST: (c) => {
    const orig = c.str("origArr"), nw = c.cityOf(c.str("newArr") ?? text(c.p.arr)), eta = c.arrTime("estArr");
    return {
      title: `${whose("hi", c.name)} फ़्लाइट ${c.f} अब ${nw} जा रही है`,
      body: join(orig && `पहले ${c.cityOf(orig)} जाना था।`, eta && `स्थानीय समय ${eta} बजे पहुँचेगी।`) || `${c.route}।`,
    };
  },
  DELAY: (c, m) => {
    const est = c.depTime("estDep");
    return {
      title: `${whose("hi", c.name)} फ़्लाइट ${c.f}${m != null ? ` ${m} मिनट` : ""} लेट है`,
      body: est ? `${c.route} अब ${est} बजे रवाना होगी।` : `${c.route}।`,
    };
  },
};

// ---------------------------------------------------------------- Hinglish (Roman Hindi)
const HINGLISH: FriendTemplates = {
  DEPARTED: (c) => {
    const off = c.depTime("actDep"), eta = c.arrTime("estArr");
    return {
      title: `${whose("hinglish", c.name)} flight ${c.f}${c.depCity ? ` ${c.depCity} se` : ""} ud gayi`,
      body: join(
        off && `${off} baje take-off hua.`,
        eta ? `${c.arrCity} ${eta} baje pahunchne ki ummeed.` : c.arrCity && `${c.arrCity} ki taraf ja rahi hai.`,
      ) || `${c.route}.`,
    };
  },
  LANDED: (c) => {
    const on = c.arrTime("actArr"), belt = c.str("belt");
    return {
      title: `${whose("hinglish", c.name)} flight ${c.f}${c.arrCity ? ` ${c.arrCity}` : ""} land ho gayi`,
      body: join(on && `${on} baje land hui.`, belt && `Baggage belt ${belt}.`) || `${c.route}.`,
    };
  },
  CANCELLED: (c) => ({
    title: `${whose("hinglish", c.name)} flight ${c.f} cancel ho gayi`,
    body: join(c.route && `${c.route}.`, `Aage ke plan ke liye ${c.name ?? "apne dost"} se baat karein.`),
  }),
  REINSTATED: (c) => ({
    title: `${whose("hinglish", c.name)} flight ${c.f} wapas schedule par hai`,
    body: join(c.route && `${c.route}.`, "Cancellation wapas le li gayi hai."),
  }),
  DIVERTED: (c) => {
    const orig = c.cityOf(c.str("origArr") ?? text(c.p.arr)), nw = c.str("newArr");
    return {
      title: `${whose("hinglish", c.name)} flight ${c.f} divert ho gayi`,
      body: nw
        ? `${orig ? `${orig} ki jagah ` : ""}ab ${c.cityOf(nw)} ja rahi hai.`
        : `${
          orig ? `Plan ke hisaab se ${orig} par` : "Tay airport par"
        } land nahi karegi. Nayi destination abhi announce nahi hui.`,
    };
  },
  DIVERTED_DEST: (c) => {
    const orig = c.str("origArr"), nw = c.cityOf(c.str("newArr") ?? text(c.p.arr)), eta = c.arrTime("estArr");
    return {
      title: `${whose("hinglish", c.name)} flight ${c.f} ab ${nw} ja rahi hai`,
      body: join(orig && `Pehle ${c.cityOf(orig)} jaana tha.`, eta && `ETA ${eta} local.`) || `${c.route}.`,
    };
  },
  DELAY: (c, m) => {
    const est = c.depTime("estDep");
    return {
      title: `${whose("hinglish", c.name)} flight ${c.f}${m != null ? ` ${m} min` : ""} late hai`,
      body: est ? `${c.route} ab ${est} baje udegi.` : `${c.route}.`,
    };
  },
};

/** "Asha's" / "Asha की" / "Asha ki", or "Your friend's" / "आपके दोस्त की" / "Aapke dost ki". */
function whose(lang: AlertLanguage, name: string | null): string {
  switch (lang) {
    case "hi":
      return name ? `${name} की` : "आपके दोस्त की";
    case "hinglish":
      return name ? `${name} ki` : "Aapke dost ki";
    default:
      return name ? `${name}'s` : "Your friend's";
  }
}

export const FRIEND_TEMPLATES: Record<AlertLanguage, FriendTemplates> = { en: EN, hi: HI, hinglish: HINGLISH };

/**
 * Title and body of a friend alert in the recipient's language. Returns null for an event friends never get
 * (callers check isFriendEvent first; this is the second guard).
 */
export function renderFriendAlert(
  e: FlightEvent,
  tz: Tz,
  lang: AlertLanguage | string | null,
  friendName: unknown,
): Rendered | null {
  if (!isFriendEvent(e)) return null;
  const l = alertLanguage(lang);
  const tpl = FRIEND_TEMPLATES[l][e.type as FriendEventType];
  return tpl(context(e, tz, l, cleanFriendName(friendName)), e.type === "DELAY" ? delayMinutes(e) : null);
}
