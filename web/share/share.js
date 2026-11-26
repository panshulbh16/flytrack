// HeyGate public share page. Reads the token from location.hash (never sent to the page's host),
// fetches {SUPABASE_URL}/functions/v1/shared-flight?t=<token> every REFRESH_SECONDS and draws the
// route on HeyTurbulence's globe. No build step: d3-geo, d3-array and topojson-client are vendored
// UMD builds (globals `d3`, `topojson`), loaded before this module.

const MIN = 60_000;

// ---------- pure helpers (also used by supabase/functions/tests/share.test.ts) ----------

/** The token after '#', or null when it does not look like one (22..128 base64url chars). */
export function tokenFromHash(hash) {
  let t = String(hash ?? "").replace(/^#/, "");
  try {
    t = decodeURIComponent(t).trim();
  } catch {
    return null;
  }
  return /^[A-Za-z0-9_-]{22,128}$/.test(t) ? t : null;
}

const ms = (iso) => {
  const t = Date.parse(iso ?? "");
  return Number.isNaN(t) ? null : t;
};

/** Minutes late at departure: actual or estimate vs timetable. */
export function depDelayMin(f) {
  const s = ms(f.sched_dep), b = ms(f.act_dep) ?? ms(f.est_dep);
  return s == null || b == null ? 0 : Math.round((b - s) / MIN);
}

/** One status word (and a tone for its colour), like the apps' status line. */
export function statusWord(f) {
  const st = f.status;
  if (st === "cancelled") return { text: "Cancelled", tone: "bad" };
  if (st === "diverted") return { text: f.arr?.iata ? `Diverted to ${f.arr.iata}` : "Diverted", tone: "bad" };
  if (f.act_arr || st === "landed" || st === "arrived") {
    return { text: f.arr?.belt ? `Landed · Belt ${f.arr.belt}` : "Landed", tone: "ok" };
  }
  if (f.act_dep || st === "departed" || st === "en_route" || st === "approaching") {
    return { text: st === "approaching" ? "Landing soon" : "In the air", tone: "air" };
  }
  if (st === "boarding") return { text: f.dep?.gate ? `Boarding · Gate ${f.dep.gate}` : "Boarding", tone: "warn" };
  if (st === "gate_closed") return { text: "Gate closed", tone: "warn" };
  const late = depDelayMin(f);
  if (late >= 15) return { text: `Delayed ${late} min`, tone: "warn" };
  if (st === "unknown" && !f.sched_dep) return { text: "Scheduled", tone: "ok" };
  return { text: "On time", tone: "ok" };
}

/** "18:50" in the airport's own zone, plus "25 Nov" when that is not the same local day as `now`. */
export function localTime(iso, tz, now = new Date()) {
  const t = ms(iso);
  if (t == null) return null;
  const zone = tz || "Asia/Kolkata";
  const time = new Intl.DateTimeFormat("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: zone })
    .format(new Date(t));
  const day = (d) => new Intl.DateTimeFormat("en-CA", { timeZone: zone }).format(d);
  if (day(new Date(t)) === day(now)) return time;
  const date = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: zone }).format(new Date(t));
  return `${time}, ${date}`;
}

/** Best known time and, if it moved by 5+ min, the timetable one to show struck through. */
export function bestTime(sched, est, act) {
  const best = act ?? est ?? sched ?? null;
  const moved = best && sched && Math.abs((ms(best) - ms(sched)) / MIN) >= 5;
  return { best, was: moved ? sched : null, kind: act ? "actual" : est ? "estimated" : "scheduled" };
}

/** Where to draw the plane: the live position, else an estimate along the route while airborne. */
export function planePosition(f, now = Date.now()) {
  if (f.live && Number.isFinite(f.live.lat) && Number.isFinite(f.live.lon)) {
    return { lat: f.live.lat, lon: f.live.lon, estimated: false };
  }
  const airborne = (f.act_dep || ["departed", "en_route", "approaching"].includes(f.status)) && !f.act_arr &&
    !["landed", "arrived", "cancelled", "diverted"].includes(f.status);
  if (!airborne) return null;
  const off = ms(f.act_dep) ?? ms(f.est_dep) ?? ms(f.sched_dep);
  const on = ms(f.est_arr) ?? ms(f.sched_arr);
  if (off == null || on == null || on <= off) return null;
  const frac = Math.min(0.98, Math.max(0.02, (now - off) / (on - off)));
  return { frac, estimated: true };
}

// ---------- globe (adapted from HeyTurbulence src/globe.js) ----------

function palette(dark) {
  return dark
    ? { sea: "#253e36", grat: "#466055", land: "#8ca890", landEdge: "#a0b99f", rim: "#647c68", label: "rgba(22,43,33,.94)",
      labelInk: "#f6f5ef", done: "#df754e", todo: "#798b77", dot: "#f6f5ef", dotEdge: "#d56844", plane: "#f6f5ef" }
    : { sea: "#e2e9df", grat: "#c2d0c1", land: "#3e6b56", landEdge: "#527761", rim: "#bdcbbb", label: "rgba(250,250,243,.94)",
      labelInk: "#244c37", done: "#df754e", todo: "#b8a285", dot: "#f6f5ef", dotEdge: "#d56844", plane: "#244735" };
}

let land = null;
async function loadLand() {
  if (land) return land;
  const res = await fetch("vendor/world-atlas@2.0.2-land-110m.json");
  const topo = await res.json();
  land = globalThis.topojson.feature(topo, topo.objects.land);
  return land;
}

export function drawGlobe(canvas, f, now = Date.now()) {
  const d3 = globalThis.d3;
  const dark = globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
  const c = palette(dark);
  const dpr = Math.min(globalThis.devicePixelRatio || 1, 3);
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const a = [f.dep.lon, f.dep.lat], b = [f.arr.lon, f.arr.lat];
  const haveRoute = a.every(Number.isFinite) && b.every(Number.isFinite);
  const interp = haveRoute ? d3.geoInterpolate(a, b) : null;
  const mid = interp ? interp(0.5) : [78.9, 21.5]; // India
  const dist = haveRoute ? d3.geoDistance(a, b) : 0.6;
  // Zoom so the route spans about 55% of the narrow side (a domestic hop fills the card); long routes
  // zoom out until the whole globe shows, as on HeyTurbulence.
  const side = Math.min(w, h);
  const fit = (0.55 * side) / (2 * Math.max(Math.sin(dist / 2), 0.01));
  const r = Math.max(side * 0.44, Math.min(fit, side * 8));
  const x = w / 2, y = h / 2;
  const p = d3.geoOrthographic().translate([x, y]).scale(r).rotate([-mid[0], -mid[1], 0]).clipAngle(90);
  const path = d3.geoPath(p, ctx);

  ctx.save();
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fillStyle = c.sea;
  ctx.fill();
  const shade = ctx.createRadialGradient(x - r * 0.4, y - r * 0.5, r * 0.1, x, y, r);
  shade.addColorStop(0, "rgba(255,255,255,.24)");
  shade.addColorStop(1, "rgba(15,53,37,.10)");
  ctx.fillStyle = shade;
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
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.strokeStyle = c.rim;
  ctx.lineWidth = 1;
  ctx.stroke();

  if (!interp) {
    ctx.restore();
    return;
  }
  const N = 96;
  const all = Array.from({ length: N + 1 }, (_, i) => interp(i / N));
  const plane = planePosition(f, now);
  let frac = 0;
  if (plane && !plane.estimated) {
    const total = d3.geoDistance(a, b) || 1;
    frac = Math.min(1, d3.geoDistance(a, [plane.lon, plane.lat]) / total);
  } else if (plane) frac = plane.frac;
  const landed = !!f.act_arr || f.status === "landed" || f.status === "arrived";
  if (landed) frac = 1;
  const cut = Math.max(1, Math.round(frac * N));

  // the whole route faint and dashed, the flown part solid vermilion
  ctx.beginPath();
  path({ type: "LineString", coordinates: all });
  ctx.setLineDash([5, 5]);
  ctx.strokeStyle = c.todo;
  ctx.lineWidth = 1.6;
  ctx.stroke();
  ctx.setLineDash([]);
  if (frac > 0) {
    ctx.beginPath();
    path({ type: "LineString", coordinates: all.slice(0, cut + 1) });
    ctx.strokeStyle = c.done;
    ctx.lineWidth = 2.8;
    ctx.stroke();
  }

  const center = [-p.rotate()[0], -p.rotate()[1]];
  const visible = (ll) => d3.geoDistance(center, ll) < Math.PI / 2 - 0.02;
  const labels = [];
  for (const [ap, ll] of [[f.dep, a], [f.arr, b]]) {
    if (!visible(ll)) continue;
    const [px, py] = p(ll);
    ctx.beginPath();
    ctx.arc(px, py, 4.5, 0, Math.PI * 2);
    ctx.fillStyle = c.dot;
    ctx.fill();
    ctx.strokeStyle = c.dotEdge;
    ctx.lineWidth = 2;
    ctx.stroke();
    labels.push({ ap, px, py });
  }
  const size = Math.max(12, Math.min(15, side / 26));
  ctx.font = `600 ${size}px "DM Sans", sans-serif`;
  for (const { ap, px, py } of labels) {
    const text = ap.city ? `${ap.iata}  ${ap.city}` : ap.iata;
    const tw = ctx.measureText(text).width, pad = 9, hh = size + 12;
    let lx = px + 10, ly = py - hh - 8;
    if (lx + tw + pad * 2 > w - 6) lx = px - tw - pad * 2 - 10;
    lx = Math.max(6, lx);
    ly = Math.max(6, Math.min(h - hh - 6, ly));
    ctx.fillStyle = c.label;
    ctx.beginPath();
    ctx.roundRect ? ctx.roundRect(lx, ly, tw + pad * 2, hh, 5) : ctx.rect(lx, ly, tw + pad * 2, hh);
    ctx.fill();
    ctx.fillStyle = c.labelInk;
    ctx.fillText(text, lx + pad, ly + size + 3);
  }

  if (plane && !landed) {
    const at = plane.estimated ? interp(frac) : [plane.lon, plane.lat];
    if (visible(at)) {
      const [px, py] = p(at);
      const ahead = p(interp(Math.min(1, frac + 0.02)));
      const behind = p(interp(Math.max(0, frac - 0.02)));
      const angle = Math.atan2(ahead[1] - behind[1], ahead[0] - behind[0]);
      drawPlane(ctx, px, py, angle, Math.max(20, side / 13), c.plane, c.dot);
    }
  }
  ctx.restore();
}

/** A small top-view airliner pointing along +x, rotated to the route's direction. */
function drawPlane(ctx, x, y, angle, s, fill, halo) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(angle);
  ctx.scale(s / 24, s / 24);
  ctx.beginPath();
  ctx.moveTo(12, 0);
  ctx.quadraticCurveTo(11, -1.6, 8, -1.6);
  ctx.lineTo(2, -1.6);
  ctx.lineTo(-3, -10);
  ctx.lineTo(-6, -10);
  ctx.lineTo(-3, -1.6);
  ctx.lineTo(-8, -1.6);
  ctx.lineTo(-10.5, -5);
  ctx.lineTo(-12, -5);
  ctx.lineTo(-11, 0);
  ctx.lineTo(-12, 5);
  ctx.lineTo(-10.5, 5);
  ctx.lineTo(-8, 1.6);
  ctx.lineTo(-3, 1.6);
  ctx.lineTo(-6, 10);
  ctx.lineTo(-3, 10);
  ctx.lineTo(2, 1.6);
  ctx.lineTo(8, 1.6);
  ctx.quadraticCurveTo(11, 1.6, 12, 0);
  ctx.closePath();
  ctx.lineWidth = 3;
  ctx.strokeStyle = halo;
  ctx.stroke();
  ctx.fillStyle = fill;
  ctx.fill();
  ctx.restore();
}

// ---------- page ----------

function el(id) {
  return document.getElementById(id);
}

function setText(id, text) {
  el(id).textContent = text ?? "";
}

function show(which) {
  el("state-loading").hidden = which !== "loading";
  el("state-error").hidden = which !== "error";
  el("flight").hidden = which !== "flight";
}

function showError(title, body, canRetry) {
  setText("error-title", title);
  setText("error-body", body);
  el("retry").hidden = !canRetry;
  show("error");
}

function ago(iso, now) {
  const t = ms(iso);
  if (t == null) return null;
  const m = Math.max(0, Math.round((now - t) / MIN));
  return m < 1 ? "just now" : m === 1 ? "1 min ago" : m < 60 ? `${m} min ago` : `${Math.round(m / 60)} h ago`;
}

function render(f, now = new Date()) {
  const flightNo = `${f.carrier} ${f.number}`;
  document.title = `${flightNo} ${f.dep.iata}→${f.arr.iata} · HeyGate`;
  setText("airline", f.airline ?? "");
  setText("title", flightNo);
  setText("route", `${f.dep.city || f.dep.iata} → ${f.arr.city || f.arr.iata}`);
  const s = statusWord(f);
  setText("status", s.text);
  el("status").dataset.tone = s.tone;

  for (const [side, ap, t] of [
    ["dep", f.dep, bestTime(f.sched_dep, f.est_dep, f.act_dep)],
    ["arr", f.arr, bestTime(f.sched_arr, f.est_arr, f.act_arr)],
  ]) {
    setText(`${side}-code`, ap.iata);
    setText(`${side}-city`, ap.name || ap.city || "");
    // "13:45, 27 Nov": keep the big time on one line and put the date under it in small text.
    const [clock, date] = (localTime(t.best, ap.tz, now) ?? "--:--").split(", ");
    const timeEl = el(`${side}-time`);
    timeEl.textContent = clock;
    if (date) {
      const d = document.createElement("span");
      d.className = "time-date";
      d.textContent = date;
      timeEl.append(d);
    }
    const was = el(`${side}-was`);
    was.textContent = "";
    if (t.was) {
      was.append("was ");
      const strike = document.createElement("s");
      strike.textContent = localTime(t.was, ap.tz, now);
      was.append(strike);
    } else if (t.kind === "actual") was.textContent = side === "dep" ? "departed" : "landed";
    else if (t.kind === "estimated") was.textContent = "estimated";
    setText(`${side}-terminal`, ap.terminal ? `T${ap.terminal}` : "–");
  }
  setText("dep-gate", f.dep.gate || "–");
  setText("arr-belt", f.arr.belt || "–");

  const plane = planePosition(f, now.getTime());
  const note = el("globe-note");
  note.hidden = !(plane && plane.estimated);
  note.textContent = plane && plane.estimated ? "Plane position estimated from the timetable" : "";
  el("globe").setAttribute("aria-label", `Route ${f.dep.iata} to ${f.arr.iata}. ${s.text}.`);

  const updated = ago(f.updated_at, now.getTime());
  setText("meta", `${updated ? `Updated ${updated}. ` : ""}This page refreshes every minute.`);
  show("flight"); // before drawing: a hidden canvas has no size
  drawGlobe(el("globe"), f, now.getTime());
}

function boot() {
  const cfg = globalThis.HEYGATE_SHARE_CONFIG ?? {};
  const base = String(cfg.SUPABASE_URL ?? "").replace(/\/+$/, "");
  const every = Math.max(30, Number(cfg.REFRESH_SECONDS) || 60) * 1000;
  let token = tokenFromHash(location.hash);
  let last = null;
  let timer = null;

  if (!base || base.includes("YOUR-PROJECT-REF")) {
    showError("Not set up yet", "This page needs SUPABASE_URL in config.js.", false);
    return;
  }
  loadLand().then(() => last && drawGlobe(el("globe"), last)).catch(() => {});

  async function refresh() {
    clearTimeout(timer);
    if (!token) {
      showError("No flight in this link", "Ask for the link again: it ends with # and a long code.", false);
      return;
    }
    try {
      const headers = cfg.SUPABASE_ANON_KEY ? { apikey: cfg.SUPABASE_ANON_KEY } : {};
      const res = await fetch(`${base}/functions/v1/shared-flight?t=${encodeURIComponent(token)}`, {
        headers, cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer",
      });
      if (res.status === 404) {
        last = null;
        showError("This link isn't working", "It may have expired or been turned off by the person who shared it.", false);
        return; // nothing will change for this token
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
      last = body.flight;
      render(last);
    } catch {
      if (!last) showError("Can't reach HeyGate right now", "Check your connection. We'll keep trying.", true);
      else setText("meta", "Couldn't refresh. Showing the last update; trying again soon.");
    }
    if (document.visibilityState !== "hidden") timer = setTimeout(refresh, every);
  }

  el("retry").addEventListener("click", refresh);
  addEventListener("hashchange", () => {
    token = tokenFromHash(location.hash);
    last = null;
    show("loading");
    refresh();
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refresh();
    else clearTimeout(timer);
  });
  let resizeTimer = null;
  addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => last && drawGlobe(el("globe"), last), 120);
  });
  globalThis.matchMedia?.("(prefers-color-scheme: dark)").addEventListener?.("change", () => last && drawGlobe(el("globe"), last));
  // keep the plane and "updated x min ago" moving between fetches
  setInterval(() => last && document.visibilityState === "visible" && render(last), 15_000);
  refresh();
}

if (typeof document !== "undefined" && typeof location !== "undefined") boot();
