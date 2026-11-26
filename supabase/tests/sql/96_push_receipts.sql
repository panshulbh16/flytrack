-- P2 delivery receipts (docs/P2_CONTRACTS.md section 3): push_log.delivered_at via ack_push(),
-- push_latency_daily (service role only). Uses the users, flight and events of 95_alert_prefs.sql.
\set QUIET on
set client_min_messages = warning;

-- event created 30 s before the pushes; user A has 2 devices' rows, user B 1
insert into public.flight_events (flight_id, type, severity, payload, dedupe_key, created_at)
values ('95959595-0000-0000-0000-000000000001', 'GATE_CHANGE', 'critical', '{}', '96-gate', now() - interval '10 minutes');
insert into public.push_log (event_id, user_id, kind, status, created_at)
select e.id, u, 'alert', 'sent', now() - interval '9 minutes 30 seconds'
  from public.flight_events e,
       unnest(array['00000000-0000-0000-0000-0000000095a1'::uuid, '00000000-0000-0000-0000-0000000095a1'::uuid,
                    '00000000-0000-0000-0000-0000000095a2'::uuid]) u
 where e.dedupe_key = '96-gate';
-- a failed alert row for A: never acknowledged, never counted
insert into public.push_log (event_id, user_id, kind, status)
select e.id, '00000000-0000-0000-0000-0000000095a1', 'alert', 'failed' from public.flight_events e where e.dedupe_key = '96-gate';

-- ===== clients can't call ack_push or read the view =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000095a1"}';
do $$ begin
  begin
    perform public.ack_push(auth.uid(), 1, now());
    raise exception 'FAIL: clients can call ack_push';
  exception when insufficient_privilege then null; end;
  begin
    perform 1 from public.push_latency_daily;
    raise exception 'FAIL: clients can read push_latency_daily';
  exception when insufficient_privilege then null; end;
  begin
    update public.push_log set delivered_at = now();
    raise exception 'FAIL: clients can write push_log';
  exception when insufficient_privilege then null; end;
end $$;
commit;
begin;
set local role anon;
do $$ begin
  begin
    perform 1 from public.push_latency_daily;
    raise exception 'FAIL: anon can read push_latency_daily';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- ===== service role acks for user A only =====
begin;
set local role service_role;
do $$ declare ev bigint; n int; begin
  select id into ev from public.flight_events where dedupe_key = '96-gate';
  -- received 20 s after the push was created (event + 40 s)
  n := public.ack_push('00000000-0000-0000-0000-0000000095a1', ev, now() - interval '9 minutes 10 seconds');
  assert n = 2, format('A has 2 sent alert rows, got %s', n);
end $$;
commit;
do $$ declare ev bigint; begin
  select id into ev from public.flight_events where dedupe_key = '96-gate';
  assert (select count(*) from public.push_log where event_id = ev and delivered_at is not null
            and user_id = '00000000-0000-0000-0000-0000000095a1') = 2, 'A sent rows stamped, failed row not';
  assert (select count(*) from public.push_log where event_id = ev and delivered_at is not null
            and user_id = '00000000-0000-0000-0000-0000000095a2') = 0, 'B rows untouched';
end $$;

-- second ack is a no-op (first receipt wins)
begin;
set local role service_role;
do $$ declare ev bigint; d1 timestamptz; begin
  select id into ev from public.flight_events where dedupe_key = '96-gate';
  select max(delivered_at) into d1 from public.push_log where event_id = ev and user_id = '00000000-0000-0000-0000-0000000095a1';
  assert public.ack_push('00000000-0000-0000-0000-0000000095a1', ev, now()) = 0, 'second ack changes nothing';
  assert (select max(delivered_at) from public.push_log where event_id = ev
           and user_id = '00000000-0000-0000-0000-0000000095a1') = d1, 'delivered_at kept';
  -- unknown event / null user
  assert public.ack_push('00000000-0000-0000-0000-0000000095a1', -1, now()) = 0, 'unknown event';
  assert public.ack_push(null, ev, now()) = 0, 'null user';
end $$;
commit;

-- clamps: a phone clock far ahead → now(); far behind → the push's created_at
begin;
set local role service_role;
do $$ declare ev bigint; d timestamptz; c timestamptz; begin
  select id into ev from public.flight_events where dedupe_key = '96-gate';
  perform public.ack_push('00000000-0000-0000-0000-0000000095a2', ev, now() + interval '3 hours');
  select delivered_at into d from public.push_log where event_id = ev and user_id = '00000000-0000-0000-0000-0000000095a2';
  assert d <= now(), 'future received_at clamped to now()';
  update public.push_log set delivered_at = null where event_id = ev and user_id = '00000000-0000-0000-0000-0000000095a2';
  perform public.ack_push('00000000-0000-0000-0000-0000000095a2', ev, now() - interval '2 days');
  select delivered_at, created_at into d, c from public.push_log
   where event_id = ev and user_id = '00000000-0000-0000-0000-0000000095a2';
  assert d = c, 'past received_at clamped to the push time';
end $$;
commit;

-- ===== latency view (service role) =====
begin;
set local role service_role;
do $$ declare r record; begin
  select * into r from public.push_latency_daily where day = (now() at time zone 'Asia/Kolkata')::date;
  assert r.sent = 3, format('3 sent alert rows today, got %s', r.sent);
  assert r.delivered = 3, format('3 acknowledged, got %s', r.delivered);
  -- A: 50 s twice (event at -10 min, received at -9 min 10 s); B: 30 s (clamped to its push time)
  -- (each statement has its own now(), hence the 1 s tolerance)
  assert abs(r.p50_seconds - 50) < 1, format('p50 %s', r.p50_seconds);
  assert abs(r.p90_seconds - 50) < 1, format('p90 %s', r.p90_seconds);
  assert r.delivered_pct = 100.0, format('delivered_pct %s', r.delivered_pct);
end $$;
commit;

select 'push receipts tests: OK' as result;
