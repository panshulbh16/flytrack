import { assertEquals } from "@std/assert";
import { fanOut } from "../_shared/engine.ts";
import type { PushResult } from "../_shared/fcm.ts";
import { buildLiveActivityMessage, type FcmMessage } from "../_shared/notify.ts";
import type { FlightRow } from "../_shared/types.ts";
import { makeDeps } from "./helpers.ts";

const NOW = new Date("2026-11-25T12:00:00Z");

async function flight(over: Partial<FlightRow> = {}) {
  const h = makeDeps(NOW.toISOString());
  const row = await h.repo.insertFlight({
    carrier_iata: "6E", flight_number: "2341", flight_date: "2026-11-25", dep_iata: "DEL", arr_iata: "BOM",
    status: "boarding", sched_dep: "2001-01-01T00:01:00Z", est_dep: "2001-01-01T00:02:00Z",
    dep_terminal: "1", dep_gate: "29", ...over,
  });
  h.repo.liveActivities.push({
    id: "la1", flightId: row.id, user_id: "u1", device_id: "d1", fcm_token: "fcm-token", activity_token: "abcd1234",
  });
  return { h, row };
}

const aps = (m: FcmMessage) => (m.apns!.payload as { aps: Record<string, unknown> }).aps;

Deno.test("live activity update: FCM token + activity token, liveactivity headers, Swift-epoch dates", async () => {
  const { row } = await flight();
  const m = buildLiveActivityMessage("fcm-token", "abcd1234", row, NOW, false);
  assertEquals(m.token, "fcm-token");
  assertEquals(m.apns!.live_activity_token, "abcd1234");
  assertEquals(m.apns!.headers, {
    "apns-push-type": "liveactivity",
    "apns-topic": "app.heygate.flytrack.push-type.liveactivity",
    "apns-priority": "5",
  });
  assertEquals(aps(m).event, "update");
  assertEquals(aps(m).timestamp, NOW.getTime() / 1000);
  assertEquals(aps(m)["dismissal-date"], undefined);
  const state = aps(m)["content-state"] as Record<string, unknown>;
  // Swift's Date decodes from seconds since 2001-01-01, so one minute past that epoch is 60.
  assertEquals(state.schedDep, 60);
  assertEquals(state.estDep, 120);
  assertEquals(state.estArr, null);
  assertEquals([state.status, state.depGate, state.depTerminal, state.belt], ["boarding", "29", "1", null]);
  assertEquals(buildLiveActivityMessage("f", "a", row, NOW, true).apns!.headers, {
    "apns-push-type": "liveactivity",
    "apns-topic": "app.heygate.flytrack.push-type.liveactivity",
    "apns-priority": "10",
  });
});

Deno.test("live activity ends once landed with a belt, or cancelled; not on landing alone", async () => {
  for (const [over, end] of [
    [{ status: "landed" }, false],
    [{ status: "landed", baggage_belt: "5" }, true],
    [{ status: "arrived", baggage_belt: "5" }, true],
    [{ status: "cancelled" }, true],
  ] as [Partial<FlightRow>, boolean][]) {
    const { row } = await flight(over);
    const a = aps(buildLiveActivityMessage("f", "a", row, NOW, false));
    assertEquals(a.event, end ? "end" : "update", JSON.stringify(over));
    assertEquals(a["dismissal-date"], end ? NOW.getTime() / 1000 + 3600 : undefined);
  }
});

Deno.test("fan-out sends the live activity with the sync and drops the row after the end", async () => {
  const { h, row } = await flight();
  await fanOut(h.deps, row, [], true);
  const sent = h.pusher.sent.filter((m) => m.apns?.live_activity_token === "abcd1234");
  assertEquals(sent.length, 1);
  assertEquals(h.repo.pushes.map((p) => p.kind), ["live_activity"]); // no tracker → no sync, activity still updates
  assertEquals(h.repo.liveActivities.length, 1);

  await fanOut(h.deps, { ...row, status: "landed", baggage_belt: "7" }, [], true);
  assertEquals(aps(h.pusher.sent.at(-1)!).event, "end");
  assertEquals(h.repo.liveActivities.length, 0, "ended activity forgotten");

  await fanOut(h.deps, row, [], false); // event-only fan-outs (the sweeper) never touch activities
  assertEquals(h.pusher.sent.length, 2);
});

Deno.test("fan-out: a rejected live activity token drops the activity, never the phone's FCM token", async () => {
  const { h, row } = await flight();
  h.repo.track(row.id, { userId: "u1", isPro: false, prefs: {}, devices: [{ id: "d1", token: "fcm-token" }] });
  h.deps.pusher = {
    send: (m: FcmMessage): Promise<PushResult> =>
      Promise.resolve(m.apns?.live_activity_token ? { ok: false, invalidToken: true, error: "410" } : { ok: true }),
  };
  await fanOut(h.deps, row, [], true);
  assertEquals(h.repo.liveActivities.length, 0);
  assertEquals((await h.repo.getRecipients(row.id)).map((r) => r.fcm_token), ["fcm-token"]);
});
