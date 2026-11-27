#!/usr/bin/env node
// Build-time generator for the bundled terminal maps (P4 contract section 4).
//
// For each airport it asks Overpass for the OpenStreetMap features inside the
// airport's `aeroway=aerodrome` area, converts them to GeoJSON, simplifies
// (Douglas-Peucker), quantizes coordinates to 6 decimals, keeps only the
// properties {kind, ref, name, level?} and caps the file size (~400 KB).
//
// Usage:
//   node scripts/build-terminal-maps.mjs                 # all airports
//   node scripts/build-terminal-maps.mjs DEL BOM         # a subset
//   node scripts/build-terminal-maps.mjs --from-dir DIR  # convert saved Overpass JSON (DIR/{IATA}.json), no network
//   node scripts/build-terminal-maps.mjs --save-raw DIR  # also keep the raw Overpass responses
//   node scripts/build-terminal-maps.mjs --no-copy       # do not copy into the app asset folders
// Env: OVERPASS_URL (default https://overpass-api.de/api/interpreter).
// Behind an HTTPS proxy on Node 22 set NODE_USE_ENV_PROXY=1 (and NODE_EXTRA_CA_CERTS if needed).
//
// No dependencies beyond Node built-ins. Data © OpenStreetMap contributors, ODbL 1.0.

import { mkdir, readFile, writeFile, copyFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const AIRPORTS = Object.freeze({
  DEL: "VIDP",
  BOM: "VABB",
  BLR: "VOBL",
  HYD: "VOHS",
  MAA: "VOMM",
  CCU: "VECC",
  COK: "VOCI",
  AMD: "VAAH",
});

export const ATTRIBUTION = "© OpenStreetMap contributors (ODbL)";
export const MAX_BYTES = 400 * 1024;
export const DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter";

const AEROWAY_KINDS = ["terminal", "gate", "apron", "parking_position", "taxiway", "runway", "jet_bridge"];

/** Kinds drawn as areas when the way is closed. */
const AREA_KINDS = new Set(["terminal", "apron", "baggage_claim"]);
/** Kinds that only make sense as areas: a node or unclosed way with these tags is dropped (it cannot be drawn). */
const AREA_ONLY_KINDS = new Set(["terminal", "apron"]);
/** Every kind a file may contain (the apps' parsers know exactly these). */
export const KINDS = Object.freeze([
  ...AEROWAY_KINDS, "baggage_claim", "toilets", "food", "atm", "lounge", "prayer_room", "pharmacy", "charging", "shop",
]);
/** Kinds the apps show as points (POIs and gates); areas collapse to a representative point. */
const POINT_KINDS = new Set([
  "gate", "parking_position", "toilets", "food", "atm", "lounge", "prayer_room", "pharmacy", "charging", "shop",
]);

/** Simplification tolerance (degrees; 1e-5 deg is about 1.1 m) per kind at size step 0. */
const BASE_TOLERANCE = {
  terminal: 0.000004,
  baggage_claim: 0.000004,
  apron: 0.00001,
  taxiway: 0.00001,
  runway: 0.00001,
  jet_bridge: 0.000004,
};

/** Kinds dropped, in order, if the file is still over the cap after max simplification. */
const DROP_ORDER = ["parking_position", "jet_bridge", "shop", "taxiway"];

// ---------------------------------------------------------------------------
// Overpass query

/** Overpass QL for one airport, identified by its ICAO code. */
export function buildQuery(icao) {
  if (!/^[A-Z]{4}$/.test(icao)) throw new Error(`bad ICAO code: ${icao}`);
  const aeroways = AEROWAY_KINDS.join("|");
  return `[out:json][timeout:180];
(
  way["aeroway"="aerodrome"]["icao"="${icao}"];
  relation["aeroway"="aerodrome"]["icao"="${icao}"];
)->.ap;
.ap out tags;
.ap map_to_area->.a;
(
  nwr(area.a)["aeroway"~"^(${aeroways}|baggage_claim)$"];
  nwr(area.a)["building"="terminal"];
)->.aw;
(
  way.aw["aeroway"="terminal"];
  relation.aw["aeroway"="terminal"];
  way.aw["building"="terminal"];
  relation.aw["building"="terminal"];
)->.terms;
.terms map_to_area->.ta;
(
  nwr(area.ta)["amenity"];
  nwr(area.ta)["shop"];
)->.pois;
(.aw; .pois;);
out geom;`;
}

// ---------------------------------------------------------------------------
// Tag -> {kind, ref, name, level}

const FOOD = new Set(["restaurant", "cafe", "fast_food", "food_court", "bar", "pub", "ice_cream", "biergarten"]);
const MONEY = new Set(["atm", "bank", "bureau_de_change"]);
const CHARGING = new Set(["charging_station", "device_charging_station", "mobile_charging"]);

/** Maps OSM tags to our feature kind, or null to drop the element. */
export function classify(tags = {}) {
  const aeroway = tags.aeroway;
  if (aeroway && AEROWAY_KINDS.includes(aeroway)) return aeroway;
  if (aeroway === "baggage_claim" || tags.amenity === "baggage_claim") return "baggage_claim";
  if (tags.building === "terminal") return "terminal";
  const amenity = tags.amenity;
  if (amenity) {
    if (amenity === "toilets") return "toilets";
    if (FOOD.has(amenity)) return "food";
    if (MONEY.has(amenity)) return "atm";
    if (amenity === "lounge" || tags.lounge || /\blounge\b/i.test(tags.name ?? "")) return "lounge";
    if (amenity === "place_of_worship" || amenity === "prayer_room") return "prayer_room";
    if (amenity === "pharmacy" || tags.healthcare === "pharmacy") return "pharmacy";
    if (CHARGING.has(amenity)) return "charging";
  }
  if (tags.shop) {
    if (tags.shop === "chemist") return "pharmacy";
    return "shop";
  }
  return null;
}

/** The kept properties. `ref` and `name` are always present (null when unknown); `level` only when tagged. */
export function properties(kind, tags = {}) {
  const name = clean(tags["name:en"]) ?? clean(tags.name);
  let ref = clean(tags.ref);
  if (!ref && kind === "gate" && name) {
    const m = /^gate\s+([A-Z0-9][A-Z0-9-]*)$/i.exec(name);
    if (m) ref = m[1].toUpperCase();
  }
  const props = { kind, ref: ref ?? null, name: name ?? null };
  const level = clean(tags.level) ?? clean(tags["level:ref"]);
  if (level) props.level = level;
  return props;
}

function clean(v) {
  if (typeof v !== "string") return undefined;
  const s = v.trim().replace(/\s+/g, " ");
  return s ? s.slice(0, 80) : undefined;
}

// ---------------------------------------------------------------------------
// Geometry helpers. Points are [lon, lat].

export function quantize(n) {
  return Math.round(n * 1e6) / 1e6;
}

function perpDist(p, a, b) {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  if (dx === 0 && dy === 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
  const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
}

/** Douglas-Peucker on an open polyline (iterative; keeps both ends). */
export function simplifyLine(points, tolerance) {
  if (points.length <= 2 || tolerance <= 0) return points.slice();
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [s, e] = stack.pop();
    let maxD = 0;
    let idx = -1;
    for (let i = s + 1; i < e; i++) {
      const d = perpDist(points[i], points[s], points[e]);
      if (d > maxD) {
        maxD = d;
        idx = i;
      }
    }
    if (idx !== -1 && maxD > tolerance) {
      keep[idx] = 1;
      stack.push([s, idx], [idx, e]);
    }
  }
  return points.filter((_, i) => keep[i]);
}

/** Simplifies a closed ring; returns null when it collapses below a valid ring (4 positions). */
export function simplifyRing(ring, tolerance) {
  if (ring.length < 4) return null;
  // Split at the vertex farthest from the start so both halves keep their shape.
  const open = ring.slice(0, -1);
  let far = 0;
  let farD = -1;
  for (let i = 1; i < open.length; i++) {
    const d = Math.hypot(open[i][0] - open[0][0], open[i][1] - open[0][1]);
    if (d > farD) {
      farD = d;
      far = i;
    }
  }
  const a = simplifyLine(open.slice(0, far + 1), tolerance);
  const b = simplifyLine([...open.slice(far), open[0]], tolerance);
  const out = [...a, ...b.slice(1)];
  return out.length >= 4 ? out : null;
}

function quantizeCoords(points) {
  const out = [];
  for (const p of points) {
    const q = [quantize(p[0]), quantize(p[1])];
    const last = out[out.length - 1];
    if (!last || last[0] !== q[0] || last[1] !== q[1]) out.push(q);
  }
  return out;
}

function ringArea(ring) {
  let a = 0;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    a += (ring[j][0] - ring[i][0]) * (ring[j][1] + ring[i][1]);
  }
  return a / 2; // > 0 for counter-clockwise in lon/lat with this formula
}

/** RFC 7946: exterior rings counter-clockwise, holes clockwise. */
function orient(ring, ccw) {
  const isCcw = ringArea(ring) > 0;
  return isCcw === ccw ? ring : ring.slice().reverse();
}

function pointInRing(pt, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > pt[1] !== yj > pt[1] && pt[0] < ((xj - xi) * (pt[1] - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Representative point of a ring/line: area centroid, falling back to the vertex mean. */
export function centroid(points) {
  const pts = points.length > 1 && samePos(points[0], points[points.length - 1]) ? points.slice(0, -1) : points;
  let a = 0;
  let cx = 0;
  let cy = 0;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const f = pts[j][0] * pts[i][1] - pts[i][0] * pts[j][1];
    a += f;
    cx += (pts[j][0] + pts[i][0]) * f;
    cy += (pts[j][1] + pts[i][1]) * f;
  }
  if (Math.abs(a) > 1e-14) return [cx / (3 * a), cy / (3 * a)];
  const n = pts.length || 1;
  return [pts.reduce((s, p) => s + p[0], 0) / n, pts.reduce((s, p) => s + p[1], 0) / n];
}

function samePos(a, b) {
  return a[0] === b[0] && a[1] === b[1];
}

function wayCoords(geometry) {
  return (geometry ?? []).filter((g) => g && typeof g.lat === "number").map((g) => [g.lon, g.lat]);
}

/** Joins way segments end-to-end into closed rings (multipolygon assembly). Unclosable chains are dropped. */
export function assembleRings(segments) {
  const pool = segments.filter((s) => s.length >= 2).map((s) => s.slice());
  const rings = [];
  while (pool.length) {
    let ring = pool.shift();
    let progress = true;
    while (!samePos(ring[0], ring[ring.length - 1]) && progress) {
      progress = false;
      for (let i = 0; i < pool.length; i++) {
        const s = pool[i];
        const end = ring[ring.length - 1];
        if (samePos(s[0], end)) ring = ring.concat(s.slice(1));
        else if (samePos(s[s.length - 1], end)) ring = ring.concat(s.slice().reverse().slice(1));
        else if (samePos(s[s.length - 1], ring[0])) ring = s.concat(ring.slice(1));
        else if (samePos(s[0], ring[0])) ring = s.slice().reverse().concat(ring.slice(1));
        else continue;
        pool.splice(i, 1);
        progress = true;
        break;
      }
    }
    if (ring.length >= 4 && samePos(ring[0], ring[ring.length - 1])) rings.push(ring);
  }
  return rings;
}

// ---------------------------------------------------------------------------
// Overpass JSON -> raw features (unsimplified)

function isClosed(c) {
  return c.length >= 4 && samePos(c[0], c[c.length - 1]);
}

/** Converts Overpass `out geom` elements to unsimplified GeoJSON features (deduplicated, deterministic order). */
export function elementsToFeatures(elements) {
  const features = [];
  const seen = new Set();
  for (const el of elements ?? []) {
    const id = `${el.type}/${el.id}`;
    if (seen.has(id)) continue;
    seen.add(id);
    const kind = classify(el.tags);
    if (!kind) continue;
    const props = properties(kind, el.tags);
    let geometry = null;
    if (el.type === "node") {
      if (typeof el.lat === "number") geometry = { type: "Point", coordinates: [el.lon, el.lat] };
    } else if (el.type === "way") {
      const c = wayCoords(el.geometry);
      if (c.length < 2) continue;
      if (POINT_KINDS.has(kind)) geometry = { type: "Point", coordinates: centroid(c) };
      else if (isClosed(c) && (AREA_KINDS.has(kind) || el.tags?.area === "yes")) geometry = { type: "Polygon", coordinates: [c] };
      else geometry = { type: "LineString", coordinates: c };
    } else if (el.type === "relation") {
      const members = (el.members ?? []).filter((m) => m.type === "way");
      // type=building relations: the `outline` member is the footprint; `part` members are building parts.
      const isOuter = el.tags?.type === "building" ? (m) => m.role === "outline" : (m) => m.role !== "inner";
      const outers = assembleRings(members.filter(isOuter).map((m) => wayCoords(m.geometry)));
      const inners = assembleRings(members.filter((m) => m.role === "inner").map((m) => wayCoords(m.geometry)));
      if (!outers.length) continue;
      if (POINT_KINDS.has(kind)) {
        geometry = { type: "Point", coordinates: centroid(outers[0]) };
      } else {
        const polys = outers.map((o) => [o]);
        for (const inner of inners) {
          const host = polys.find((p) => pointInRing(inner[0], p[0]));
          if (host) host.push(inner);
        }
        geometry = polys.length === 1 ? { type: "Polygon", coordinates: polys[0] } : { type: "MultiPolygon", coordinates: polys };
      }
    }
    if (geometry && AREA_ONLY_KINDS.has(kind) && !/Polygon$/.test(geometry.type)) geometry = null;
    if (geometry) features.push({ type: "Feature", id, properties: props, geometry });
  }
  const order = (k) => {
    const i = ["apron", "runway", "taxiway", "terminal", "baggage_claim", "jet_bridge", "parking_position", "gate"].indexOf(k);
    return i === -1 ? 99 : i;
  };
  features.sort((a, b) => order(a.properties.kind) - order(b.properties.kind) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return features;
}

// ---------------------------------------------------------------------------
// Simplify + quantize + cap

function simplifyGeometry(geometry, tol) {
  switch (geometry.type) {
    case "Point":
      return { type: "Point", coordinates: [quantize(geometry.coordinates[0]), quantize(geometry.coordinates[1])] };
    case "LineString": {
      const c = quantizeCoords(simplifyLine(geometry.coordinates, tol));
      return c.length >= 2 ? { type: "LineString", coordinates: c } : null;
    }
    case "Polygon": {
      const rings = simplifyPolygon(geometry.coordinates, tol);
      return rings ? { type: "Polygon", coordinates: rings } : null;
    }
    case "MultiPolygon": {
      const polys = geometry.coordinates.map((p) => simplifyPolygon(p, tol)).filter(Boolean);
      if (!polys.length) return null;
      return polys.length === 1 ? { type: "Polygon", coordinates: polys[0] } : { type: "MultiPolygon", coordinates: polys };
    }
    default:
      return null;
  }
}

function simplifyPolygon(rings, tol) {
  const out = [];
  rings.forEach((ring, i) => {
    let r = simplifyRing(ring, tol) ?? (i === 0 ? ring : null); // never lose an outer ring to simplification
    if (!r) return;
    r = quantizeCoords(r);
    if (!samePos(r[0], r[r.length - 1])) r.push(r[0]);
    if (r.length < 4) return;
    out.push(orient(r, i === 0));
  });
  return out.length && out[0] ? out : null;
}

function collection(iata, icao, features, generatedAt) {
  return {
    type: "FeatureCollection",
    airport: iata,
    icao,
    attribution: ATTRIBUTION,
    license: "ODbL-1.0",
    generated_at: generatedAt,
    features,
  };
}

/**
 * Simplifies the features and returns the smallest-effort FeatureCollection under `maxBytes`.
 * Tolerance doubles per step; after that, low-priority kinds are dropped in DROP_ORDER.
 */
export function finalize(iata, icao, rawFeatures, { maxBytes = MAX_BYTES, generatedAt = new Date().toISOString() } = {}) {
  const build = (step, dropped) => {
    const factor = 2 ** step;
    const feats = [];
    for (const f of rawFeatures) {
      if (dropped.has(f.properties.kind)) continue;
      const tol = (BASE_TOLERANCE[f.properties.kind] ?? 0.000004) * factor;
      const g = simplifyGeometry(f.geometry, tol);
      if (g) feats.push({ type: "Feature", id: f.id, properties: f.properties, geometry: g });
    }
    const fc = collection(iata, icao, feats, generatedAt);
    return { fc, json: JSON.stringify(fc) };
  };
  const dropped = new Set();
  let step = 0;
  let out = build(step, dropped);
  while (Buffer.byteLength(out.json) > maxBytes && step < 4) out = build(++step, dropped);
  for (const kind of DROP_ORDER) {
    if (Buffer.byteLength(out.json) <= maxBytes) break;
    dropped.add(kind);
    out = build(step, dropped);
  }
  return { ...out, bytes: Buffer.byteLength(out.json), step, dropped: [...dropped] };
}

/** Feature counts by kind, for the build report and verification. */
export function countKinds(fc) {
  const counts = {};
  for (const f of fc.features ?? []) counts[f.properties?.kind] = (counts[f.properties?.kind] ?? 0) + 1;
  return counts;
}

/** Validation used by the build and by tests: returns a list of problems (empty when OK). */
export function validate(fc, { maxBytes = MAX_BYTES } = {}) {
  const problems = [];
  if (fc?.type !== "FeatureCollection" || !Array.isArray(fc.features)) return ["not a FeatureCollection"];
  if (fc.attribution !== ATTRIBUTION) problems.push("missing attribution");
  const bytes = Buffer.byteLength(JSON.stringify(fc));
  if (bytes > maxBytes) problems.push(`too large: ${bytes} bytes`);
  const counts = countKinds(fc);
  if (!counts.terminal) problems.push("no terminal polygons");
  if (!counts.gate) problems.push("no gates");
  for (const f of fc.features) {
    const p = f.properties ?? {};
    if (typeof p.kind !== "string" || !("ref" in p) || !("name" in p)) problems.push(`bad properties on ${f.id}`);
    const keys = Object.keys(p).filter((k) => !["kind", "ref", "name", "level"].includes(k));
    if (keys.length) problems.push(`extra properties on ${f.id}: ${keys.join(",")}`);
    if (typeof p.kind === "string" && !KINDS.includes(p.kind)) problems.push(`unknown kind on ${f.id}: ${p.kind}`);
    if (!validGeometry(f.geometry)) problems.push(`bad geometry on ${f.id}`);
    if (AREA_ONLY_KINDS.has(p.kind) && !/Polygon$/.test(f.geometry?.type ?? "")) problems.push(`${p.kind} ${f.id} is not a polygon`);
  }
  return problems;
}

function validPosition(p) {
  return Array.isArray(p) && p.length === 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]) &&
    Math.abs(p[0]) <= 180 && Math.abs(p[1]) <= 90;
}

function validRing(r) {
  return Array.isArray(r) && r.length >= 4 && r.every(validPosition) && samePos(r[0], r[r.length - 1]);
}

function validGeometry(g) {
  const c = g?.coordinates;
  switch (g?.type) {
    case "Point": return validPosition(c);
    case "LineString": return Array.isArray(c) && c.length >= 2 && c.every(validPosition);
    case "Polygon": return Array.isArray(c) && c.length >= 1 && c.every(validRing);
    case "MultiPolygon": return Array.isArray(c) && c.length >= 1 && c.every((p) => Array.isArray(p) && p.length >= 1 && p.every(validRing));
    default: return false;
  }
}

/** True when the Overpass response contains the aerodrome itself (from `.ap out tags`). */
export function hasAerodrome(elements) {
  return (elements ?? []).some((el) => el?.tags?.aeroway === "aerodrome");
}

// ---------------------------------------------------------------------------
// Network

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * POSTs the query with retries (exponential backoff with jitter) on network errors, 429 and 5xx.
 * `fetchImpl` and `sleepImpl` are injectable for tests.
 */
export async function fetchOverpass(query, {
  url = process.env.OVERPASS_URL || DEFAULT_OVERPASS_URL,
  attempts = 6,
  baseDelayMs = 5000,
  fetchImpl = globalThis.fetch,
  sleepImpl = sleep,
  log = () => {},
} = {}) {
  let lastError;
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetchImpl(url, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded", "user-agent": "flytrack-terminal-maps/1.0 (build script)" },
        body: "data=" + encodeURIComponent(query),
        signal: AbortSignal.timeout(240_000),
      });
      if (res.ok) {
        const body = await res.json();
        // Overpass reports timeouts and memory exhaustion as HTTP 200 with a `remark` and partial data.
        if (typeof body?.remark === "string" && /runtime error|timed out|out of memory/i.test(body.remark)) {
          throw Object.assign(new Error(`Overpass ${body.remark.trim().slice(0, 120)}`), { retryable: true });
        }
        return body;
      }
      lastError = new Error(`Overpass HTTP ${res.status}`);
      if (res.status !== 429 && res.status < 500) throw Object.assign(lastError, { fatal: true });
    } catch (e) {
      if (e?.fatal) throw e;
      lastError = e;
    }
    if (i < attempts - 1) {
      const wait = baseDelayMs * 2 ** i + Math.floor(Math.random() * 1000);
      log(`  attempt ${i + 1} failed (${lastError?.cause?.code ?? lastError?.message}); retrying in ${Math.round(wait / 1000)} s`);
      await sleepImpl(wait);
    }
  }
  throw Object.assign(new Error(`Overpass unreachable after ${attempts} attempts: ${lastError?.cause?.code ?? lastError?.message}`), { unreachable: true });
}

// ---------------------------------------------------------------------------
// CLI

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT_DIR = join(ROOT, "data/terminal-maps");
const COPY_DIRS = [join(ROOT, "android/app/src/main/assets/terminal-maps"), join(ROOT, "ios/FlyTrack/Resources/TerminalMaps")];

function parseArgs(argv) {
  const opts = { airports: [], fromDir: null, saveRaw: null, copy: true, gapMs: 10_000 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--from-dir") opts.fromDir = argv[++i];
    else if (a === "--save-raw") opts.saveRaw = argv[++i];
    else if (a === "--no-copy") opts.copy = false;
    else if (a === "--gap") opts.gapMs = Number(argv[++i]) * 1000;
    else if (/^[A-Z]{3}$/.test(a.toUpperCase())) opts.airports.push(a.toUpperCase());
    else throw new Error(`unknown argument: ${a}`);
  }
  if (!opts.airports.length) opts.airports = Object.keys(AIRPORTS);
  for (const a of opts.airports) if (!AIRPORTS[a]) throw new Error(`unsupported airport: ${a}`);
  return opts;
}

async function main(argv) {
  const opts = parseArgs(argv);
  await mkdir(OUT_DIR, { recursive: true });
  if (opts.saveRaw) await mkdir(opts.saveRaw, { recursive: true });
  const failures = [];
  let first = true;
  for (const iata of opts.airports) {
    const icao = AIRPORTS[iata];
    console.log(`${iata} (${icao})`);
    let raw;
    try {
      if (opts.fromDir) {
        raw = JSON.parse(await readFile(join(opts.fromDir, `${iata}.json`), "utf8"));
      } else {
        if (!first) await sleep(opts.gapMs); // be polite: one airport at a time
        first = false;
        raw = await fetchOverpass(buildQuery(icao), { log: console.log });
        if (opts.saveRaw) await writeFile(join(opts.saveRaw, `${iata}.json`), JSON.stringify(raw));
      }
    } catch (e) {
      console.error(`  FAILED: ${e.message}`);
      failures.push({ iata, reason: e.message, unreachable: !!e.unreachable });
      continue;
    }
    if (!hasAerodrome(raw.elements)) {
      const reason = `no aeroway=aerodrome with icao=${icao} in OSM (or its area is not built yet)`;
      console.error(`  NOT WRITTEN: ${reason}`);
      failures.push({ iata, reason });
      continue;
    }
    const features = elementsToFeatures(raw.elements);
    const result = finalize(iata, icao, features, { generatedAt: raw.osm3s?.timestamp_osm_base ?? new Date().toISOString() });
    const problems = validate(result.fc);
    const counts = countKinds(result.fc);
    console.log(`  ${result.fc.features.length} features, ${(result.bytes / 1024).toFixed(1)} KB, simplify step ${result.step}` +
      (result.dropped.length ? `, dropped ${result.dropped.join(",")}` : ""));
    console.log(`  ${Object.entries(counts).map(([k, v]) => `${k}=${v}`).join(" ")}`);
    if (problems.length) {
      console.error(`  NOT WRITTEN: ${problems.join("; ")}`);
      failures.push({ iata, reason: problems.join("; ") });
      continue;
    }
    const file = join(OUT_DIR, `${iata}.geojson`);
    await writeFile(file, result.json + "\n");
    if (opts.copy) {
      for (const dir of COPY_DIRS) {
        await mkdir(dir, { recursive: true });
        await copyFile(file, join(dir, `${iata}.geojson`));
      }
    }
  }
  if (failures.length) {
    const allUnreachable = failures.every((f) => f.unreachable);
    console.error(`\n${failures.length} airport(s) not built: ${failures.map((f) => f.iata).join(", ")}`);
    if (allUnreachable) console.error("Overpass was unreachable. Existing files were left untouched; the apps show \"Terminal map not available yet\" for airports without a file.");
    process.exit(2);
  }
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main(process.argv.slice(2)).catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}

export { parseArgs };
