// HeyGate yearly report (docs/P3_CONTRACTS.md B11): a public, shareable "year in flights" page in the
// HeyTurbulence style. Static, no build step.
//   index.html?year=2026  (or #2026; default: the current year in India)
// Data: GET {SUPABASE_URL}/functions/v1/global-report?year=2026 (public, anonymous aggregates, cached 1 h).
// Reuses web/share: config.js, share.css and the vendored d3-geo / topojson / world-atlas for the routes globe.

const EARTH_KM = 40_075;

// ---------- pure helpers (tested in supabase/functions/tests/public-web.test.ts) ----------

/** The current year in India (UTC+5:30). */
export function indianYear(now = Date.now()) {
  return new Date(now + 330 * 60_000).getUTCFullYear();
}

/** ?year=2025 or #2025, within 2024..this year; else this year. */
export function yearFromLocation(search, hash, now = Date.now()) {
  const current = indianYear(now);
  const fromSearch = new URLSearchParams(String(search ?? "")).get("year");
  const raw = fromSearch ?? String(hash ?? "").replace(/^#/, "");
  if (!/^\d{4}$/.test(raw)) return current;
  const y = Number(raw);
  return y >= 2024 && y <= current ? y : current;
}

/** Big numbers the Indian way: "12,345", "4.2 lakh", "1.3 crore". */
export function formatBig(n) {
  if (!Number.isFinite(n)) return "–";
  const fmt = (v) => new Intl.NumberFormat("en-IN", { maximumFractionDigits: 1 }).format(v);
  if (n >= 1e7) return `${fmt(Math.round(n / 1e6) / 10)} crore`;
  if (n >= 1e5) return `${fmt(Math.round(n / 1e4) / 10)} lakh`;
  return new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 }).format(Math.round(n));
}

/** How many times around the Earth a distance is, one decimal. */
export function earthLaps(km) {
  return Number.isFinite(km) && km > 0 ? Math.round((km / EARTH_KM) * 10) / 10 : 0;
}

/** Rows for a ranking list: [{ title, value, pct, tone }]. */
export function rankRows(rows, kind, tone) {
  return (rows ?? []).map((r) => ({
    title: kind === "airline" ? (r.name ? `${r.name} (${r.carrier})` : r.carrier) : `${r.city || r.name || r.iata} ${r.iata}`,
    value: `${r.on_time_pct ?? "–"}% on time · ${Number.isFinite(r.avg_dep_delay) ? `${r.avg_dep_delay} min avg delay` : "no delay data"}`,
    pct: Math.max(0, Math.min(100, Number(r.on_time_pct) || 0)),
    tone,
  }));
}

/** The text a "Share" tap sends. */
export function shareText(report) {
  const t = report?.totals ?? {};
  const bits = [`${report?.year ?? ""} in flights on HeyGate`];
  if (Number.isFinite(t.flights_tracked) && t.flights_tracked > 0) bits.push(`${formatBig(t.flights_tracked)} flights tracked`);
  const punctual = report?.airlines?.most_punctual?.[0];
  if (punctual) bits.push(`most punctual: ${punctual.name || punctual.carrier}`);
  const fog = report?.fog;
  if (fog && fog.fog_days > 0) bits.push(`${fog.fog_days} fog days at ${fog.airport}`);
  return bits.join(" · ");
}

// ---------- network ----------

async function fetchReport(year) {
  const cfg = globalThis.HEYGATE_SHARE_CONFIG ?? {};
  const base = String(cfg.SUPABASE_URL ?? "").replace(/\/+$/, "");
  if (!base || base.includes("YOUR-PROJECT-REF")) throw new Error("not_configured");
  const res = await fetch(`${base}/functions/v1/global-report?year=${year}`, {
    headers: cfg.SUPABASE_ANON_KEY ? { apikey: cfg.SUPABASE_ANON_KEY } : {},
    credentials: "omit",
    referrerPolicy: "no-referrer",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()).report;
}

// ---------- DOM ----------

const el = (id) => document.getElementById(id);
function node(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null) continue;
    if (k === "text") n.textContent = v;
    else if (k === "dataset") Object.assign(n.dataset, v);
    else n.setAttribute(k, v);
  }
  for (const c of children) if (c != null) n.append(c);
  return n;
}

function tile(label, value, sub) {
  const dd = node("dd", { text: value });
  if (sub) dd.append(node("span", { class: "sub", text: sub }));
  return node("div", { class: "tile" }, node("dt", { text: label }), dd);
}

function fillRanks(id, rows) {
  const list = el(id);
  list.textContent = "";
  if (!rows.length) {
    list.append(node("li", {}, node("p", { class: "empty", text: "Not enough flights yet to rank fairly." })));
    return;
  }
  for (const r of rows) {
    const bar = node("span", { class: "bar", dataset: { tone: r.tone }, "aria-hidden": "true" });
    const fill = node("span");
    fill.style.width = `${r.pct}%`;
    bar.append(fill);
    list.append(node("li", {}, node("span", { class: "who", text: r.title }), node("span", { class: "val", text: r.value }), bar));
  }
}

// Routes globe: arcs between airports, in the share page's palette. Coordinates come with each route
// (dep_lat/dep_lon/arr_lat/arr_lon); this small table only fills in when a report lacks them.
const LL = {
  DEL: [77.1, 28.56], BOM: [72.87, 19.09], BLR: [77.71, 13.2], HYD: [78.43, 17.24], MAA: [80.17, 12.99],
  CCU: [88.45, 22.65], COK: [76.4, 10.15], AMD: [72.63, 23.08], PNQ: [73.92, 18.58], GOI: [73.83, 15.38],
  GOX: [73.86, 15.73], JAI: [75.81, 26.82], LKO: [80.89, 26.76], GAU: [91.59, 26.11], PAT: [85.09, 25.59],
  IXC: [76.79, 30.67], SXR: [74.77, 33.99], TRV: [76.92, 8.48], BBI: [85.82, 20.24], IXB: [88.33, 26.68],
  DXB: [55.36, 25.25], SIN: [103.99, 1.36], LHR: [-0.46, 51.47], BKK: [100.75, 13.69], DOH: [51.61, 25.27],
};

/** [lon, lat] of a route end: the report's coordinates, else the built-in table, else null. */
export function routeEnd(route, end) {
  const lat = route?.[`${end}_lat`], lon = route?.[`${end}_lon`];
  if (Number.isFinite(lat) && Number.isFinite(lon)) return [lon, lat];
  return LL[route?.[end]] ?? null;
}

let land = null;
async function loadLand() {
  if (land) return land;
  const res = await fetch("../share/vendor/world-atlas@2.0.2-land-110m.json");
  const topo = await res.json();
  land = globalThis.topojson.feature(topo, topo.objects.land);
  return land;
}

function drawRoutes(canvas, routes) {
  const d3 = globalThis.d3;
  if (!d3) return;
  const dark = globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
  const c = dark
    ? { sea: "#253e36", grat: "#466055", land: "#8ca890", route: "#e98a63", dot: "#f6f5ef" }
    : { sea: "#e2e9df", grat: "#c2d0c1", land: "#3e6b56", route: "#df754e", dot: "#f6f5ef" };
  const dpr = Math.min(globalThis.devicePixelRatio || 1, 3);
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const r = Math.min(w, h) * 1.25;
  const p = d3.geoOrthographic().translate([w / 2, h / 2]).scale(r).rotate([-80, -21, 0]).clipAngle(90);
  const path = d3.geoPath(p, ctx);
  ctx.beginPath();
  path({ type: "Sphere" });
  ctx.fillStyle = c.sea;
  ctx.fill();
  ctx.beginPath();
  path(d3.geoGraticule10());
  ctx.strokeStyle = c.grat;
  ctx.lineWidth = 0.6;
  ctx.stroke();
  if (land) {
    ctx.beginPath();
    path(land);
    ctx.fillStyle = c.land;
    ctx.fill();
  }
  const max = Math.max(1, ...routes.map((x) => x.legs));
  const dots = new Set();
  const coords = new Map();
  for (const route of routes) {
    const a = routeEnd(route, "dep"), b = routeEnd(route, "arr");
    if (!a || !b) continue;
    coords.set(route.dep, a).set(route.arr, b);
    ctx.beginPath();
    path({ type: "LineString", coordinates: [a, b] });
    ctx.strokeStyle = c.route;
    ctx.lineWidth = 1.2 + 3 * (route.legs / max);
    ctx.lineCap = "round";
    ctx.stroke();
    dots.add(route.dep).add(route.arr);
  }
  for (const code of dots) {
    const [x, y] = p(coords.get(code));
    ctx.beginPath();
    ctx.arc(x, y, 3.5, 0, Math.PI * 2);
    ctx.fillStyle = c.dot;
    ctx.fill();
    ctx.strokeStyle = c.route;
    ctx.lineWidth = 1.5;
    ctx.stroke();
  }
}

function show(which) {
  el("state-loading").hidden = which !== "loading";
  el("state-error").hidden = which !== "error";
  el("report").hidden = which !== "report";
}

function render(rep) {
  document.title = `${rep.year} in flights · HeyGate`;
  el("year").textContent = String(rep.year);
  const t = rep.totals;
  const tiles = el("totals");
  tiles.textContent = "";
  tiles.append(tile("Flights tracked", formatBig(t.flights_tracked), null));
  tiles.append(tile("Distance", `${formatBig(t.distance_km)} km`, earthLaps(t.distance_km) >= 1
    ? `${earthLaps(t.distance_km)} times around the Earth`
    : null));
  if (Number.isFinite(t.unique_users)) tiles.append(tile("Travellers", formatBig(t.unique_users), "rounded to the nearest 100"));
  tiles.append(tile(`Fog days at ${rep.fog.airport}`, String(rep.fog.fog_days),
    rep.fog.fog_delay_days > 0 ? `${rep.fog.fog_delay_days} with departures 30+ min late` : null));

  fillRanks("airlines-punctual", rankRows(rep.airlines.most_punctual, "airline", "ok"));
  fillRanks("airlines-delayed", rankRows(rep.airlines.most_delayed, "airline", "bad"));
  fillRanks("airports-punctual", rankRows(rep.airports.most_punctual, "airport", "ok"));
  fillRanks("airports-delayed", rankRows(rep.airports.most_delayed, "airport", "bad"));

  const routes = el("routes");
  routes.textContent = "";
  if (!rep.busiest_routes.length) routes.append(node("li", {}, node("p", { class: "empty", text: "Not enough flights yet." })));
  for (const x of rep.busiest_routes) {
    routes.append(node("li", {}, node("span", { class: "who", text: `${x.dep} → ${x.arr}` }),
      node("span", { class: "val", text: `${formatBig(x.legs)} flights` })));
  }
  el("routes-globe").setAttribute("aria-label",
    `Busiest routes: ${rep.busiest_routes.slice(0, 5).map((x) => `${x.dep} to ${x.arr}`).join(", ") || "none yet"}`);
  show("report");
  drawRoutes(el("routes-globe"), rep.busiest_routes);
}

function boot() {
  let rep = null;
  const year = yearFromLocation(location.search, location.hash);
  el("share").addEventListener("click", async () => {
    if (!rep) return;
    const data = { title: `${rep.year} in flights · HeyGate`, text: shareText(rep), url: location.href };
    try {
      if (navigator.share) await navigator.share(data);
      else {
        await navigator.clipboard.writeText(`${data.text} ${data.url}`);
        el("share").textContent = "Link copied";
      }
    } catch {
      // the user closed the share sheet: nothing to do
    }
  });
  loadLand().then(() => rep && drawRoutes(el("routes-globe"), rep.busiest_routes)).catch(() => {});
  addEventListener("resize", () => rep && drawRoutes(el("routes-globe"), rep.busiest_routes));
  fetchReport(year)
    .then((r) => {
      rep = r;
      render(r);
    })
    .catch((e) => {
      const unset = String(e?.message) === "not_configured";
      el("error-title").textContent = unset ? "Not set up yet" : "Can't load the report right now";
      el("error-body").textContent = unset
        ? "This page needs SUPABASE_URL in ../share/config.js."
        : "Check your connection and try again.";
      show("error");
    });
}

if (typeof document !== "undefined" && typeof location !== "undefined") boot();
