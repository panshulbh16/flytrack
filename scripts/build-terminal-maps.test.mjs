// Tests for scripts/build-terminal-maps.mjs. Run: node --test scripts/build-terminal-maps.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  AIRPORTS, ATTRIBUTION, buildQuery, classify, properties, simplifyLine, simplifyRing, assembleRings,
  centroid, elementsToFeatures, finalize, validate, countKinds, fetchOverpass, quantize, parseArgs, hasAerodrome, KINDS,
} from "./build-terminal-maps.mjs";

const g = (pts) => pts.map(([lon, lat]) => ({ lat, lon }));
const square = (x, y, s) => [[x, y], [x + s, y], [x + s, y + s], [x, y + s], [x, y]];

/** A small recorded-shape Overpass `out geom` response. */
const FIXTURE = {
  osm3s: { timestamp_osm_base: "2026-11-27T00:00:00Z" },
  elements: [
    { type: "way", id: 10, tags: { aeroway: "terminal", building: "yes", name: "Terminal 1", "name:en": "Terminal 1" }, geometry: g(square(76.39, 10.15, 0.002)) },
    { type: "relation", id: 20, tags: { type: "multipolygon", aeroway: "terminal", name: "Terminal 3" }, members: [
      { type: "way", ref: 1, role: "outer", geometry: g([[76.40, 10.15], [76.403, 10.15], [76.403, 10.153]]) },
      { type: "way", ref: 2, role: "outer", geometry: g([[76.403, 10.153], [76.40, 10.153], [76.40, 10.15]]) },
      { type: "way", ref: 3, role: "inner", geometry: g(square(76.401, 10.151, 0.0005)) },
    ] },
    { type: "node", id: 30, lat: 10.1505, lon: 76.3905, tags: { aeroway: "gate", ref: "4A", level: "1" } },
    { type: "node", id: 31, lat: 10.1506, lon: 76.3906, tags: { aeroway: "gate", name: "Gate 12" } },
    { type: "way", id: 40, tags: { aeroway: "taxiway", ref: "B" }, geometry: g([[76.38, 10.14], [76.381, 10.1400001], [76.382, 10.14]]) },
    { type: "way", id: 41, tags: { aeroway: "apron" }, geometry: g(square(76.37, 10.13, 0.01)) },
    { type: "way", id: 42, tags: { aeroway: "runway", ref: "09/27" }, geometry: g([[76.36, 10.12], [76.40, 10.12]]) },
    { type: "node", id: 50, lat: 10.1507, lon: 76.3907, tags: { amenity: "toilets" } },
    { type: "node", id: 51, lat: 10.1508, lon: 76.3908, tags: { amenity: "cafe", name: "Coffee Day", operator: "x" } },
    { type: "way", id: 52, tags: { amenity: "lounge", name: "Pearl Lounge" }, geometry: g(square(76.391, 10.151, 0.0002)) },
    { type: "node", id: 53, lat: 10.1509, lon: 76.3909, tags: { amenity: "parking" } },
    { type: "node", id: 30, lat: 10.1505, lon: 76.3905, tags: { aeroway: "gate", ref: "4A" } },
  ],
};

test("all eight airports have ICAO codes", () => {
  assert.deepEqual(Object.keys(AIRPORTS), ["DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "COK", "AMD"]);
  for (const icao of Object.values(AIRPORTS)) assert.match(icao, /^V[A-Z]{3}$/);
});

test("query searches the aerodrome area by ICAO and outputs geometry", () => {
  const q = buildQuery("VOCI");
  assert.match(q, /\["aeroway"="aerodrome"\]\["icao"="VOCI"\]/);
  assert.match(q, /map_to_area/);
  assert.match(q, /terminal\|gate\|apron\|parking_position\|taxiway\|runway\|jet_bridge/);
  assert.match(q, /\.ap out tags;/); // lets the build tell "aerodrome missing" from "no features"
  assert.match(q, /out geom;$/);
  assert.throws(() => buildQuery('VOCI"];'), /bad ICAO/);
});

test("classify maps tags to kinds and drops the rest", () => {
  assert.equal(classify({ aeroway: "gate" }), "gate");
  assert.equal(classify({ building: "terminal" }), "terminal");
  assert.equal(classify({ amenity: "fast_food" }), "food");
  assert.equal(classify({ amenity: "bureau_de_change" }), "atm");
  assert.equal(classify({ amenity: "place_of_worship" }), "prayer_room");
  assert.equal(classify({ amenity: "pharmacy" }), "pharmacy");
  assert.equal(classify({ amenity: "charging_station" }), "charging");
  assert.equal(classify({ amenity: "lounge" }), "lounge");
  assert.equal(classify({ shop: "books" }), "shop");
  assert.equal(classify({ amenity: "parking" }), null);
  assert.equal(classify({ highway: "service" }), null);
});

test("properties keep only kind/ref/name/level", () => {
  assert.deepEqual(properties("gate", { ref: " 4A ", level: "1", operator: "x" }), { kind: "gate", ref: "4A", name: null, level: "1" });
  assert.deepEqual(properties("gate", { name: "Gate 12b" }), { kind: "gate", ref: "12B", name: "Gate 12b" });
  assert.deepEqual(properties("food", { name: "Chai", "name:en": "Chai Point" }), { kind: "food", ref: null, name: "Chai Point" });
});

test("Douglas-Peucker drops near-collinear points and keeps ends", () => {
  const line = [[0, 0], [1, 0.00001], [2, 0], [3, 1]];
  assert.deepEqual(simplifyLine(line, 0.001), [[0, 0], [2, 0], [3, 1]]);
  assert.deepEqual(simplifyLine(line, 0), line);
  const ring = [[0, 0], [0.5, 0], [1, 0], [1, 1], [0, 1], [0, 0]];
  const s = simplifyRing(ring, 0.01);
  assert.equal(s.length, 5);
  assert.deepEqual(s[0], s[s.length - 1]);
  assert.equal(simplifyRing([[0, 0], [1, 0], [0, 0]], 0.1), null);
});

test("quantize rounds to 6 decimals", () => {
  assert.equal(quantize(77.12345678), 77.123457);
  assert.equal(quantize(-0.0000004), -0);
});

test("assembleRings joins split and reversed segments", () => {
  const rings = assembleRings([[[0, 0], [1, 0]], [[1, 1], [1, 0]], [[1, 1], [0, 1], [0, 0]]]);
  assert.equal(rings.length, 1);
  assert.equal(rings[0].length, 5);
  assert.deepEqual(assembleRings([[[0, 0], [1, 0]]]), []);
});

test("centroid of a square is its centre", () => {
  const [x, y] = centroid(square(0, 0, 2));
  assert.ok(Math.abs(x - 1) < 1e-9 && Math.abs(y - 1) < 1e-9);
});

test("elements convert to features with the right geometry types", () => {
  const feats = elementsToFeatures(FIXTURE.elements);
  const byId = Object.fromEntries(feats.map((f) => [f.id, f]));
  assert.equal(feats.length, 10); // parking dropped, duplicate gate deduplicated
  assert.equal(byId["way/10"].geometry.type, "Polygon");
  assert.equal(byId["relation/20"].geometry.type, "Polygon");
  assert.equal(byId["relation/20"].geometry.coordinates.length, 2); // outer + hole
  assert.equal(byId["node/30"].geometry.type, "Point");
  assert.equal(byId["way/40"].geometry.type, "LineString");
  assert.equal(byId["way/41"].geometry.type, "Polygon");
  assert.equal(byId["way/42"].geometry.type, "LineString");
  assert.equal(byId["way/52"].geometry.type, "Point"); // POI areas collapse to a point
  assert.equal(feats[0].properties.kind, "apron"); // drawn first (bottom layer)
});

test("finalize simplifies, quantizes, orients and validates", () => {
  const { fc, bytes, step, dropped } = finalize("COK", "VOCI", elementsToFeatures(FIXTURE.elements), { generatedAt: "t" });
  assert.equal(fc.attribution, ATTRIBUTION);
  assert.equal(fc.airport, "COK");
  assert.equal(step, 0);
  assert.deepEqual(dropped, []);
  assert.ok(bytes < 10_000);
  assert.deepEqual(validate(fc), []);
  const taxi = fc.features.find((f) => f.id === "way/40");
  assert.equal(taxi.geometry.coordinates.length, 2);
  for (const f of fc.features) {
    const flat = JSON.stringify(f.geometry.coordinates).match(/-?\d+\.\d+/g) ?? [];
    for (const n of flat) assert.ok(n.split(".")[1].length <= 6, `${n} not quantized`);
  }
  const t1 = fc.features.find((f) => f.id === "way/10").geometry.coordinates[0];
  let area = 0;
  for (let i = 0; i < t1.length - 1; i++) area += t1[i][0] * t1[i + 1][1] - t1[i + 1][0] * t1[i][1];
  assert.ok(area > 0, "exterior ring is counter-clockwise");
  assert.deepEqual(countKinds(fc), { apron: 1, runway: 1, taxiway: 1, terminal: 2, gate: 2, toilets: 1, food: 1, lounge: 1 });
});

test("finalize respects the size cap by simplifying harder, then dropping kinds", () => {
  const wiggly = [];
  for (let i = 0; i <= 4000; i++) wiggly.push({ lon: 76 + i * 0.00001, lat: 10 + (i % 2) * 0.00002 });
  const elements = [
    ...FIXTURE.elements,
    ...Array.from({ length: 20 }, (_, k) => ({ type: "way", id: 900 + k, tags: { aeroway: "taxiway" }, geometry: wiggly })),
  ];
  const { bytes, step } = finalize("COK", "VOCI", elementsToFeatures(elements), { maxBytes: 60_000, generatedAt: "t" });
  assert.ok(bytes <= 60_000, `got ${bytes}`);
  assert.ok(step > 0);
  const tiny = finalize("COK", "VOCI", elementsToFeatures(elements), { maxBytes: 4_000, generatedAt: "t" });
  assert.ok(tiny.dropped.includes("taxiway"));
});

test("validate reports missing terminals and gates", () => {
  const fc = { type: "FeatureCollection", attribution: ATTRIBUTION, features: [] };
  assert.deepEqual(validate(fc), ["no terminal polygons", "no gates"]);
  assert.deepEqual(validate({}), ["not a FeatureCollection"]);
});

test("fetchOverpass retries resets and 5xx, then succeeds", async () => {
  let calls = 0;
  const waits = [];
  const fetchImpl = async (_url, init) => {
    calls++;
    assert.equal(init.method, "POST");
    assert.match(init.body, /^data=/);
    if (calls === 1) throw Object.assign(new TypeError("fetch failed"), { cause: { code: "ECONNRESET" } });
    if (calls === 2) return { ok: false, status: 504 };
    return { ok: true, status: 200, json: async () => FIXTURE };
  };
  const r = await fetchOverpass("q", { fetchImpl, sleepImpl: async (ms) => waits.push(ms), baseDelayMs: 100 });
  assert.equal(r.elements.length, FIXTURE.elements.length);
  assert.equal(calls, 3);
  assert.equal(waits.length, 2);
  assert.ok(waits[1] >= 200, "backoff grows");
});

test("fetchOverpass gives up clearly when unreachable and does not retry 400", async () => {
  const reset = async () => { throw Object.assign(new TypeError("fetch failed"), { cause: { code: "ECONNRESET" } }); };
  await assert.rejects(fetchOverpass("q", { fetchImpl: reset, sleepImpl: async () => {}, attempts: 3 }),
    (e) => e.unreachable === true && /unreachable after 3 attempts: ECONNRESET/.test(e.message));
  let calls = 0;
  const bad = async () => { calls++; return { ok: false, status: 400 }; };
  await assert.rejects(fetchOverpass("q", { fetchImpl: bad, sleepImpl: async () => {} }), /HTTP 400/);
  assert.equal(calls, 1);
});

test("parseArgs", () => {
  assert.equal(parseArgs([]).airports.length, 8);
  assert.deepEqual(parseArgs(["del", "--no-copy"]).airports, ["DEL"]);
  assert.equal(parseArgs(["--no-copy"]).copy, false);
  assert.throws(() => parseArgs(["LHR"]), /unsupported airport/);
});

test("terminal and apron that are not areas are dropped instead of failing the airport", () => {
  const feats = elementsToFeatures([
    ...FIXTURE.elements,
    { type: "node", id: 60, lat: 10.15, lon: 76.39, tags: { aeroway: "terminal", name: "Domestic" } },
    { type: "way", id: 61, tags: { aeroway: "apron" }, geometry: g([[76.3, 10.1], [76.31, 10.1], [76.31, 10.11]]) },
    { type: "node", id: 62, lat: 10.152, lon: 76.392, tags: { aeroway: "baggage_claim", ref: "3" } },
  ]);
  const ids = feats.map((f) => f.id);
  assert.ok(!ids.includes("node/60") && !ids.includes("way/61"));
  assert.equal(feats.find((f) => f.id === "node/62").geometry.type, "Point"); // a belt point is still useful
  assert.deepEqual(validate(finalize("COK", "VOCI", feats, { generatedAt: "t" }).fc), []);
});

test("type=building relations use only the outline member", () => {
  const [f] = elementsToFeatures([{ type: "relation", id: 70, tags: { type: "building", building: "terminal" }, members: [
    { type: "way", ref: 1, role: "outline", geometry: g(square(77, 28, 0.01)) },
    { type: "way", ref: 2, role: "part", geometry: g(square(77.02, 28.02, 0.001)) },
  ] }]);
  assert.equal(f.geometry.type, "Polygon");
  assert.equal(f.geometry.coordinates.length, 1);
});

test("validate rejects unknown kinds and broken geometry; every kind is one the apps parse", () => {
  const base = finalize("COK", "VOCI", elementsToFeatures(FIXTURE.elements), { generatedAt: "t" }).fc;
  const bad = structuredClone(base);
  bad.features.push({ type: "Feature", id: "node/1", properties: { kind: "parking", ref: null, name: null }, geometry: { type: "Point", coordinates: [76, 10] } });
  bad.features.push({ type: "Feature", id: "way/2", properties: { kind: "taxiway", ref: null, name: null }, geometry: { type: "LineString", coordinates: [[76, 10]] } });
  bad.features.push({ type: "Feature", id: "way/3", properties: { kind: "apron", ref: null, name: null }, geometry: { type: "Polygon", coordinates: [[[76, 10], [76.1, 10], [76, 10]]] } });
  const problems = validate(bad);
  assert.ok(problems.includes("unknown kind on node/1: parking"));
  assert.ok(problems.includes("bad geometry on way/2"));
  assert.ok(problems.includes("bad geometry on way/3"));
  assert.ok(validate({ ...base, attribution: "OSM" }).includes("missing attribution"));
  assert.deepEqual([...KINDS].sort(), ["apron", "atm", "baggage_claim", "charging", "food", "gate", "jet_bridge", "lounge",
    "parking_position", "pharmacy", "prayer_room", "runway", "shop", "taxiway", "terminal", "toilets"]);
});

test("hasAerodrome detects the aerodrome element", () => {
  assert.equal(hasAerodrome(FIXTURE.elements), false);
  assert.equal(hasAerodrome([{ type: "way", id: 1, tags: { aeroway: "aerodrome", icao: "VOCI" } }]), true);
});

test("fetchOverpass retries a 200 that carries an Overpass runtime error", async () => {
  let calls = 0;
  const fetchImpl = async () => {
    calls++;
    if (calls === 1) return { ok: true, status: 200, json: async () => ({ elements: [], remark: "runtime error: Query timed out in \"query\" at line 9 after 181 seconds." }) };
    return { ok: true, status: 200, json: async () => FIXTURE };
  };
  const r = await fetchOverpass("q", { fetchImpl, sleepImpl: async () => {}, baseDelayMs: 1 });
  assert.equal(calls, 2);
  assert.equal(r.elements.length, FIXTURE.elements.length);
  await assert.rejects(
    fetchOverpass("q", { fetchImpl: async () => ({ ok: true, status: 200, json: async () => ({ elements: [], remark: "runtime error: out of memory" }) }), sleepImpl: async () => {}, attempts: 2 }),
    (e) => e.unreachable === true && /out of memory/.test(e.message));
});
