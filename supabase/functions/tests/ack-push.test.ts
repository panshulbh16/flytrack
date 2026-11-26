import { assertEquals } from "@std/assert";
import { type AckDeps, handleAckPush, parseEventId, parseReceivedAt } from "../ack-push/handler.ts";

const NOW = new Date("2026-11-26T10:00:00Z");

function deps(over: Partial<AckDeps> = {}) {
  const calls: { userId: string; eventId: number; receivedAt: string }[] = [];
  const d: AckDeps = {
    userId: (auth) => Promise.resolve(auth === "Bearer good" ? "user-1" : null),
    ack: (userId, eventId, receivedAt) => {
      calls.push({ userId, eventId, receivedAt: receivedAt.toISOString() });
      return Promise.resolve(1);
    },
    now: () => NOW,
    log: () => {},
    ...over,
  };
  return { d, calls };
}

const post = (body: unknown, auth = "Bearer good") =>
  new Request("http://x/ack-push", {
    method: "POST",
    headers: { authorization: auth, "content-type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

Deno.test("ack-push: acks the caller's rows with the phone's receive time", async () => {
  const { d, calls } = deps();
  const r = await handleAckPush(post({ event_id: 123, received_at: "2026-11-26T09:59:30Z" }), d);
  assertEquals(r.status, 200);
  assertEquals(await r.json(), { ok: true, updated: 1 });
  assertEquals(calls, [{ userId: "user-1", eventId: 123, receivedAt: "2026-11-26T09:59:30.000Z" }]);
});

Deno.test("ack-push: the user id only ever comes from the JWT", async () => {
  const { d, calls } = deps();
  const r = await handleAckPush(post({ event_id: 1, user_id: "someone-else" }), d);
  assertEquals(r.status, 200);
  assertEquals(calls[0].userId, "user-1");
  assertEquals((await handleAckPush(post({ event_id: 1 }, "Bearer bad"), d)).status, 401);
  assertEquals((await handleAckPush(post({ event_id: 1 }, ""), d)).status, 401);
  assertEquals(calls.length, 1, "no ack without a valid user");
});

Deno.test("ack-push: received_at missing → now, in the future → now", async () => {
  const { d, calls } = deps();
  await handleAckPush(post({ event_id: "77" }), d);
  await handleAckPush(post({ event_id: 77, received_at: "2026-11-26T12:00:00Z" }), d);
  assertEquals(calls.map((c) => c.receivedAt), [NOW.toISOString(), NOW.toISOString()]);
});

Deno.test("ack-push: bad input, method, CORS, failures", async () => {
  const { d, calls } = deps();
  for (
    const body of [{}, { event_id: 0 }, { event_id: -3 }, { event_id: 1.5 }, { event_id: "abc" }, { event_id: 1e20 }, [
      1,
    ], "nope"]
  ) {
    assertEquals((await handleAckPush(post(body), d)).status, 400, JSON.stringify(body));
  }
  assertEquals((await handleAckPush(post({ event_id: 1, received_at: "yesterday" }), d)).status, 400);
  assertEquals(calls.length, 0);
  assertEquals((await handleAckPush(new Request("http://x", { method: "GET" }), d)).status, 405);
  assertEquals((await handleAckPush(new Request("http://x", { method: "OPTIONS" }), d)).status, 200);
  const failing = deps({ ack: () => Promise.reject(new Error("db down")) });
  assertEquals((await handleAckPush(post({ event_id: 1 }), failing.d)).status, 500);
  assertEquals([parseEventId(42), parseEventId("42"), parseEventId(null)], [42, 42, null]);
  assertEquals(parseReceivedAt(undefined, NOW), NOW);
  assertEquals(parseReceivedAt(12345, NOW), null);
});
