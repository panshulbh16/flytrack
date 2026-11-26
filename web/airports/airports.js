// HeyGate public airport pages (docs/P3_CONTRACTS.md B8). Static, no build step.
//   index.html          the metros, each with its live level
//   airport.html#DEL    one airport: level, headline, weather, today's departures, a small globe
// Data: GET {SUPABASE_URL}/functions/v1/airport-status?iata=DEL (public, no key, cached 5 min server-side).
// Reuses web/share: config.js (SUPABASE_URL), share.css (palette, type, dark mode) and the vendored d3-geo /
// topojson / world-atlas files, so both sites deploy from one web/ folder.

const MIN = 60_000;

/** The metros listed on index.html, in this order. Names are only a fallback until the API answers. */
export const METROS = [
  { iata: "DEL", city: "New Delhi", name: "Indira Gandhi International" },
  { iata: "BOM", city: "Mumbai", name: "Chhatrapati Shivaji Maharaj International" },
  { iata: "BLR", city: "Bengaluru", name: "Kempegowda International" },
  { iata: "HYD", city: "Hyderabad", name: "Rajiv Gandhi International" },
  { iata: "MAA", city: "Chennai", name: "Chennai International" },
  { iata: "CCU", city: "Kolkata", name: "Netaji Subhas Chandra Bose International" },
  { iata: "COK", city: "Kochi", name: "Cochin International" },
  { iata: "AMD", city: "Ahmedabad", name: "Sardar Vallabhbhai Patel International" },
  { iata: "PNQ", city: "Pune", name: "Pune International" },
  { iata: "GOX", city: "Goa (Mopa)", name: "Manohar International" },
];

// ---------- pure helpers (tested in supabase/functions/tests/public-web.test.ts) ----------

/** "#del" → "DEL"; null when the hash is not a 3-character airport code. */
export function iataFromHash(hash) {
  let v = String(hash ?? "").replace(/^#/, "");
  try {
    v = decodeURIComponent(v);
  } catch {
    return null;
  }
  v = v.trim().toUpperCase();
  return /^[A-Z0-9]{3}$/.test(v) ? v : null;
}

const LEVELS = {
  normal: { label: "Operating normally", short: "Normal", tone: "ok" },
  busy: { label: "Busy", short: "Busy", tone: "warn" },
  disrupted: { label: "Disrupted", short: "Disrupted", tone: "bad" },
  severe: { label: "Severe disruption", short: "Severe", tone: "bad" },
};

/** Text and colour tone for an Airport Health level; unknown levels read as "No data". */
export function levelInfo(level) {
  return LEVELS[level] ?? { label: "No data yet", short: "No data", tone: "none" };
}

/** Whole numbers with Indian digit grouping ("1,23,456"). */
export function formatCount(n) {
  return Number.isFinite(n) ? new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 }).format(n) : "–";
}

/** The "today" tiles: [{ label, value, sub }]. delayed_pct null → "–" with the reason. */
export function todayTiles(today) {
  const t = today ?? {};
  return [
    { label: "Departures today", value: formatCount(t.departures ?? 0), sub: "known to HeyGate" },
    { label: "Departed", value: formatCount(t.departed ?? 0), sub: null },
    {
      label: "Left 15+ min late",
      value: Number.isFinite(t.delayed_pct) ? `${t.delayed_pct}%` : "–",
      sub: Number.isFinite(t.delayed_pct) ? "of departed flights" : "too few departures yet",
    },
    { label: "Cancelled", value: formatCount(t.cancelled ?? 0), sub: null },
  ];
}

/** "Updated 4 min ago" style; null for a missing / bad time. */
export function updatedAgo(iso, now = Date.now()) {
  const t = Date.parse(iso ?? "");
  if (Number.isNaN(t)) return null;
  const m = Math.max(0, Math.round((now - t) / MIN));
  if (m < 1) return "Updated just now";
  if (m < 60) return `Updated ${m} min ago`;
  return `Updated ${Math.round(m / 60)} h ago`;
}

/** Weather line for the airport page, or null without a recent report. */
export function weatherLine(w) {
  if (!w) return null;
  const parts = [w.summary || "Good flying weather"];
  if (Number.isFinite(w.visibility_m)) {
    parts.push(w.visibility_m >= 9999 ? "visibility 10 km or more" : `visibility ${formatCount(w.visibility_m)} m`);
  }
  return parts.join(" · ");
}

// ---------- network ----------

function config() {
  const cfg = globalThis.HEYGATE_SHARE_CONFIG ?? {};
  const base = String(cfg.SUPABASE_URL ?? "").replace(/\/+$/, "");
  return { base: base && !base.includes("YOUR-PROJECT-REF") ? base : null, key: cfg.SUPABASE_ANON_KEY || "" };
}

async function fetchStatus(iata) {
  const { base, key } = config();
  if (!base) throw new Error("not_configured");
  const res = await fetch(`${base}/functions/v1/airport-status?iata=${encodeURIComponent(iata)}`, {
    headers: key ? { apikey: key } : {},
    credentials: "omit",
    referrerPolicy: "no-referrer",
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()).airport ?? null;
}

// ---------- DOM helpers ----------

const el = (id) => document.getElementById(id);
function node(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null) continue;
    if (k === "dataset") Object.assign(n.dataset, v);
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v);
  }
  for (const c of children) if (c != null) n.append(c);
  return n;
}

// ---------- index.html ----------

function renderIndex() {
  const list = el("metros");
  list.textContent = "";
  for (const m of METROS) {
    const chip = node("span", { class: "chip", dataset: { tone: "none" }, text: "…" });
    list.append(node(
      "li",
      {},
      node(
        "a",
        { href: `airport.html#${m.iata}`, "aria-label": `${m.city} airport (${m.iata})` },
        node(
          "span",
          { class: "place" },
          node("span", { class: "code", text: m.iata }),
          node("span", {}, node("span", { class: "city", text: m.city }), node("span", { class: "name", text: m.name })),
        ),
        chip,
      ),
    ));
    fetchStatus(m.iata)
      .then((s) => {
        const info = levelInfo(s?.level);
        chip.textContent = info.short;
        chip.dataset.tone = info.tone;
        if (s?.headline) chip.title = s.headline;
      })
      .catch(() => {
        chip.textContent = "Unavailable";
        chip.dataset.tone = "none";
      });
  }
}

// ---------- airport.html ----------

function palette(dark) {
  return dark
    ? { sea: "#253e36", grat: "#466055", land: "#8ca890", landEdge: "#a0b99f", rim: "#647c68", dot: "#f6f5ef", dotEdge: "#d56844" }
    : { sea: "#e2e9df", grat: "#c2d0c1", land: "#3e6b56", landEdge: "#527761", rim: "#bdcbbb", dot: "#f6f5ef", dotEdge: "#d56844" };
}

let land = null;
async function loadLand() {
  if (land) return land;
  const res = await fetch("../share/vendor/world-atlas@2.0.2-land-110m.json");
  const topo = await res.json();
  land = globalThis.topojson.feature(topo, topo.objects.land);
  return land;
}

/** A small globe centred on the airport (same look as the share page's globe). */
function drawAirportGlobe(canvas, lon, lat) {
  const d3 = globalThis.d3;
  if (!d3 || !Number.isFinite(lon) || !Number.isFinite(lat)) return;
  const c = palette(globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false);
  const dpr = Math.min(globalThis.devicePixelRatio || 1, 3);
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const r = Math.min(w, h) * 0.9;
  const p = d3.geoOrthographic().translate([w / 2, h / 2 + r * 0.35]).scale(r).rotate([-lon, -lat + 8, 0]).clipAngle(90);
  const path = d3.geoPath(p, ctx);
  ctx.beginPath();
  path({ type: "Sphere" });
  ctx.fillStyle = c.sea;
  ctx.fill();
  ctx.beginPath();
  path(d3.geoGraticule10());
  ctx.strokeStyle = c.grat;
  ctx.lineWidth = 0.65;
  ctx.stroke();
  if (land) {
    ctx.beginPath();
    path(land);
    ctx.fillStyle = c.land;
    ctx.fill();
    ctx.strokeStyle = c.landEdge;
    ctx.lineWidth = 0.5;
    ctx.stroke();
  }
  const [px, py] = p([lon, lat]);
  ctx.beginPath();
  ctx.arc(px, py, 6, 0, Math.PI * 2);
  ctx.fillStyle = c.dot;
  ctx.fill();
  ctx.strokeStyle = c.dotEdge;
  ctx.lineWidth = 2.5;
  ctx.stroke();
}

function showState(which) {
  el("state-loading").hidden = which !== "loading";
  el("state-error").hidden = which !== "error";
  el("airport").hidden = which !== "airport";
}

function renderAirport(s, coords) {
  const info = levelInfo(s.level);
  const city = s.city || s.iata;
  document.title = `${city} airport (${s.iata}) delays today · HeyGate`;
  el("title").textContent = `${city} ${s.iata}`;
  el("name").textContent = s.name ?? "";
  const status = el("level");
  status.textContent = info.label;
  status.dataset.tone = info.tone === "none" ? "ok" : info.tone;
  el("headline").textContent = s.headline && s.headline !== info.label ? s.headline : "";
  const wx = weatherLine(s.weather);
  el("weather").textContent = wx ?? "No recent weather report.";
  const tiles = el("today");
  tiles.textContent = "";
  for (const t of todayTiles(s.today)) {
    const dd = node("dd", { text: t.value });
    if (t.sub) dd.append(node("span", { class: "sub", text: t.sub }));
    tiles.append(node("div", { class: "tile" }, node("dt", { text: t.label }), dd));
  }
  el("meta").textContent = [updatedAgo(s.updated_at), "Times and dates are local to the airport."]
    .filter(Boolean).join(". ");
  showState("airport");
  if (coords) drawAirportGlobe(el("globe"), coords.lon, coords.lat);
}

function bootAirport() {
  let current = null;
  async function load() {
    const iata = iataFromHash(location.hash);
    if (!iata) {
      el("error-title").textContent = "Which airport?";
      el("error-body").textContent = "Pick one from the list.";
      showState("error");
      return;
    }
    showState("loading");
    try {
      const s = await fetchStatus(iata);
      if (!s) {
        el("error-title").textContent = `We don't know ${iata}`;
        el("error-body").textContent = "Check the code, or pick an airport from the list.";
        showState("error");
        return;
      }
      current = { s, coords: Number.isFinite(s.lat) && Number.isFinite(s.lon) ? { lon: s.lon, lat: s.lat } : null };
      renderAirport(current.s, current.coords);
    } catch (e) {
      el("error-title").textContent = String(e?.message) === "not_configured"
        ? "Not set up yet"
        : "Can't reach HeyGate right now";
      el("error-body").textContent = String(e?.message) === "not_configured"
        ? "This page needs SUPABASE_URL in ../share/config.js."
        : "Check your connection and try again.";
      showState("error");
    }
  }
  loadLand().then(() => current && renderAirport(current.s, current.coords)).catch(() => {});
  addEventListener("hashchange", load);
  addEventListener("resize", () => current?.coords && drawAirportGlobe(el("globe"), current.coords.lon, current.coords.lat));
  // Airport Health moves slowly; the API caches 5 minutes, so refresh on that rhythm while visible.
  setInterval(() => document.visibilityState === "visible" && load(), 5 * MIN);
  load();
}

if (typeof document !== "undefined" && typeof location !== "undefined") {
  if (document.getElementById("metros")) renderIndex();
  else if (document.getElementById("airport")) bootAirport();
}
