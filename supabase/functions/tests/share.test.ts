import { assert, assertEquals } from "@std/assert";
import { searchFlight } from "../_shared/engine.ts";
import { handleSharedFlight, toPublicFlight, TOKEN_RE } from "../_shared/share.ts";
import { makeDeps } from "./helpers.ts";
// the public page's pure helpers (web/share/share.js, plain JS, no build step)
import { bestTime, depDelayMin, localTime, planePosition, statusWord, tokenFromHash } from "../../../web/share/share.js";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcd"; // 42 chars, base64url
const URL_BASE = "https://x.supabase.co/functions/v1/shared-flight";

async function setup() {
  const h = makeDeps("2026-11-24T00:00:00Z");
  h.repo.tz = { DEL: "Asia/Kolkata", BOM: "Asia/Kolkata" };
  const [row] = await searchFlight(h.deps, "owner", { flight: "6E 2340", date: "2026-11-25" });
  await h.repo.updateFlight(row.id, {
    // things that must never leak
    alert_state: { depGate: "12" }, prediction: { lateMin: 30 }, aircraft_reg: "VT-SECRET", provider_ref: "ref-1",
    dep_gate: "42B", baggage_belt: "5",
  });
  h.repo.share(TOKEN, "owner", row.id, "2026-11-27T12:00:00Z");
  const call = (url: string, init?: RequestInit) =>
    handleSharedFlight(new Request(url, init), { lookup: (t) => h.repo.getSharedFlight(t), now: h.clock.now });
  return { h, row, call };
}

Deno.test("shared-flight: 200 with exactly the contract's public fields", async () => {
  const { row, call } = await setup();
  const res = await call(`${URL_BASE}?t=${TOKEN}`);
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("access-control-allow-origin"), "*");
  assertEquals(res.headers.get("cache-control"), "no-store");
  const body = await res.json();
  assertEquals(Object.keys(body), ["flight"]);
  const f = body.flight;
  assertEquals(Object.keys(f).sort(), [
    "act_arr", "act_dep", "aircraft", "airline", "arr", "carrier", "date", "dep", "est_arr", "est_dep", "live", "number",
    "sched_arr", "sched_dep", "status", "updated_at",
  ]);
  assertEquals(Object.keys(f.dep).sort(), ["city", "gate", "iata", "lat", "lon", "name", "terminal", "tz"]);
  assertEquals(Object.keys(f.arr).sort(), ["belt", "city", "gate", "iata", "lat", "lon", "name", "terminal", "tz"]);
  assertEquals(f.aircraft, { model: row.aircraft_model });
  assertEquals([f.carrier, f.number, f.date, f.status], ["6E", "2340", "2026-11-25", row.status]);
  assertEquals([f.dep.iata, f.dep.gate, f.dep.tz, f.arr.iata, f.arr.belt], ["DEL", "42B", "Asia/Kolkata", "BOM", "5"]);
  const text = JSON.stringify(body);
  for (const secret of ["VT-SECRET", "ref-1", "owner", "alert_state", "prediction", "lateMin", row.id, "pnr", "seat"]) {
    assert(!text.includes(secret), `leaked ${secret}`);
  }
});

Deno.test("shared-flight: 404 for malformed, unknown, revoked and expired tokens", async () => {
  const { h, row, call } = await setup();
  for (const t of ["", "short", "x".repeat(21), "has space in it 0123456789", "../../etc/passwd/aaaaaaaaaaaaaa", "Z".repeat(129)]) {
    const res = await call(`${URL_BASE}?t=${encodeURIComponent(t)}`);
    assertEquals(res.status, 404, t);
    assertEquals(await res.json(), { error: "not_found" });
  }
  assertEquals((await call(`${URL_BASE}?t=${"Q".repeat(43)}`)).status, 404, "unknown");

  h.repo.shares[0].revokedAt = "2026-11-24T00:00:00Z";
  assertEquals((await call(`${URL_BASE}?t=${TOKEN}`)).status, 404, "revoked");
  h.repo.shares[0].revokedAt = null;

  h.clock.t = Date.parse("2026-11-28T00:00:00Z"); // arrival + 2 days and expires_at both passed
  assertEquals((await call(`${URL_BASE}?t=${TOKEN}`)).status, 404, "expired");

  // a flight running much later than planned keeps its link until 2 days after its real arrival
  await h.repo.updateFlight(row.id, { est_arr: "2026-11-27T10:00:00Z" });
  assertEquals((await call(`${URL_BASE}?t=${TOKEN}`)).status, 200, "late flight still shared");
});

Deno.test("shared-flight: CORS preflight, methods, lookup failure", async () => {
  const { h, call } = await setup();
  const pre = await call(`${URL_BASE}?t=${TOKEN}`, { method: "OPTIONS" });
  assertEquals(pre.status, 204);
  assert(pre.headers.get("access-control-allow-methods")!.includes("GET"));
  assertEquals((await call(`${URL_BASE}?t=${TOKEN}`, { method: "POST", body: "{}" })).status, 405);
  const broken = await handleSharedFlight(new Request(`${URL_BASE}?t=${TOKEN}`), {
    lookup: () => Promise.reject(new Error("db down")), now: h.clock.now,
  });
  assertEquals(broken.status, 503);
});

Deno.test("shared-flight: live position only while airborne and fresh", () => {
  const now = new Date("2026-11-25T10:00:00Z");
  const raw = {
    carrier: "6E", number: "2341", date: "2026-11-25", status: "en_route", dep: { iata: "DEL" }, arr: { iata: "BOM" },
    live: { lat: 22.1, lon: 75.2, alt_ft: 36000, speed_kts: 450, heading: 210, at: "2026-11-25T09:55:00Z" },
    pnr: "ABC123", user_id: "u", // anything extra is dropped
  };
  const f = toPublicFlight(raw, now);
  assertEquals(f.live, { lat: 22.1, lon: 75.2, alt_ft: 36000, speed_kts: 450, heading: 210, at: "2026-11-25T09:55:00Z" });
  assert(!("pnr" in f) && !("user_id" in f));
  assertEquals(toPublicFlight({ ...raw, live: { ...raw.live, at: "2026-11-25T09:00:00Z" } }, now).live, null, "stale");
  assertEquals(toPublicFlight({ ...raw, status: "landed" }, now).live, null, "on the ground");
  assertEquals(toPublicFlight({ ...raw, live: null }, now).live, null);
  assertEquals(toPublicFlight({}, now).dep.iata, "", "garbage in, empty shape out");
});

Deno.test("share tokens: the SQL format (43 base64url chars) passes the gate", () => {
  assert(TOKEN_RE.test("q0x3VQb2Tn6c1bq6l8gYw0n5vHk2c3d4e5f6g7h8i9j"));
  assert(TOKEN_RE.test("a".repeat(22)));
  assert(!TOKEN_RE.test("a".repeat(21)));
  assert(!TOKEN_RE.test("abc+def/ghi=jklmnopqrstuvw"));
});


Deno.test("share page: token from the hash, status words, airport-local times, plane estimate", () => {
  assertEquals(tokenFromHash("#q0x3VQb2Tn6c1bq6l8gYw0n5vHk2c3d4e5f6g7h8i9j"), "q0x3VQb2Tn6c1bq6l8gYw0n5vHk2c3d4e5f6g7h8i9j");
  assertEquals(tokenFromHash("#short"), null);
  assertEquals(tokenFromHash("#%E0%A4%A"), null, "malformed escape");
  assertEquals(tokenFromHash(""), null);

  const base = {
    carrier: "6E", number: "2341", status: "scheduled", sched_dep: "2026-11-25T13:20:00Z", est_dep: null, act_dep: null,
    sched_arr: "2026-11-25T15:30:00Z", est_arr: null, act_arr: null, dep: { iata: "DEL", gate: "42B" }, arr: { iata: "BOM", belt: null },
    live: null,
  };
  assertEquals(statusWord(base).text, "On time");
  assertEquals(statusWord({ ...base, est_dep: "2026-11-25T13:45:00Z" }).text, "Delayed 25 min");
  assertEquals(depDelayMin({ ...base, est_dep: "2026-11-25T13:30:00Z" }), 10);
  assertEquals(statusWord({ ...base, est_dep: "2026-11-25T13:30:00Z" }).text, "On time", "under 15 min is on time");
  assertEquals(statusWord({ ...base, status: "boarding" }).text, "Boarding · Gate 42B");
  assertEquals(statusWord({ ...base, status: "en_route", act_dep: "2026-11-25T13:30:00Z" }).text, "In the air");
  assertEquals(statusWord({ ...base, status: "arrived", arr: { iata: "BOM", belt: "5" } }).text, "Landed · Belt 5");
  assertEquals(statusWord({ ...base, status: "cancelled" }).tone, "bad");

  assertEquals(localTime("2026-11-25T13:20:00Z", "Asia/Kolkata", new Date("2026-11-25T10:00:00Z")), "18:50");
  assertEquals(localTime("2026-11-25T13:20:00Z", "Asia/Dubai", new Date("2026-11-20T10:00:00Z")), "17:20, 25 Nov");
  assertEquals(localTime(null, "Asia/Kolkata"), null);
  assertEquals(bestTime("2026-11-25T13:20:00Z", "2026-11-25T13:50:00Z", null), {
    best: "2026-11-25T13:50:00Z", was: "2026-11-25T13:20:00Z", kind: "estimated",
  });

  assertEquals(planePosition(base, Date.parse("2026-11-25T14:00:00Z")), null, "not departed");
  const air = { ...base, status: "en_route", act_dep: "2026-11-25T13:30:00Z", est_arr: "2026-11-25T15:30:00Z" };
  assertEquals(planePosition(air, Date.parse("2026-11-25T14:30:00Z")), { frac: 0.5, estimated: true });
  assertEquals(planePosition({ ...air, live: { lat: 22, lon: 75 } }, 0), { lat: 22, lon: 75, estimated: false });
  assertEquals(planePosition({ ...air, status: "landed", act_arr: "2026-11-25T15:20:00Z" }, 0), null);
});
