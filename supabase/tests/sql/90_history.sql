-- Leg history and provider coverage (P1 contract section 4): flight_leg_history upsert, flights.live_seen,
-- provider_coverage_daily percentages, and that none of it is readable by app users.
\set QUIET on
set client_min_messages = warning;

insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
values ('99999999-0000-0000-0000-0000000000a1', '6E', '9101', date '2026-11-20', 'DEL', 'BOM', 'arrived', '2026-11-20 04:00Z', '2026-11-20 06:10Z'),
       ('99999999-0000-0000-0000-0000000000a2', '6E', '9102', date '2026-11-20', 'DEL', 'BLR', 'arrived', '2026-11-20 05:00Z', '2026-11-20 07:40Z'),
       ('99999999-0000-0000-0000-0000000000a3', 'AI', '9103', date '2026-11-20', 'DEL', 'HYD', 'cancelled', '2026-11-20 06:00Z', '2026-11-20 08:10Z'),
       ('99999999-0000-0000-0000-0000000000a4', 'QP', '9104', date '2026-11-20', 'BOM', 'DEL', 'arrived', '2026-11-20 06:00Z', '2026-11-20 08:10Z');

do $$ begin
  assert (select not live_seen from public.flights where id = '99999999-0000-0000-0000-0000000000a1'), 'live_seen defaults to false';
  update public.flights set live_seen = true where id = '99999999-0000-0000-0000-0000000000a1';
end $$;

-- what SupabaseRepo.upsertLegHistory sends (PostgREST upsert on flight_id)
insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, sched_dep, act_dep, sched_arr, act_arr,
  dep_delay_min, arr_delay_min, dep_terminal, dep_gate, arr_terminal, baggage_belt, aircraft_reg, aircraft_model,
  final_status, had_gate, had_belt, had_tail, had_position, provider)
values
  ('99999999-0000-0000-0000-0000000000a1', '6E', '9101', '2026-11-20', 'DEL', 'BOM', '2026-11-20 04:00Z', '2026-11-20 04:20Z',
   '2026-11-20 06:10Z', '2026-11-20 06:20Z', 20, 10, '1', '42B', '2', '5', 'VT-ABC', 'A320neo', 'arrived', true, true, true, true, 'mock'),
  ('99999999-0000-0000-0000-0000000000a2', '6E', '9102', '2026-11-20', 'DEL', 'BLR', '2026-11-20 05:00Z', '2026-11-20 05:00Z',
   '2026-11-20 07:40Z', '2026-11-20 07:35Z', 0, -5, null, null, null, null, null, null, 'arrived', false, false, false, false, 'mock'),
  ('99999999-0000-0000-0000-0000000000a3', 'AI', '9103', '2026-11-20', 'DEL', 'HYD', '2026-11-20 06:00Z', null,
   '2026-11-20 08:10Z', null, null, null, '3', null, null, null, null, null, 'cancelled', false, false, false, false, 'mock'),
  ('99999999-0000-0000-0000-0000000000a4', 'QP', '9104', '2026-11-20', 'BOM', 'DEL', '2026-11-20 06:00Z', '2026-11-20 06:05Z',
   '2026-11-20 08:10Z', '2026-11-20 08:00Z', 5, -10, '1', '7', '1', '3', 'VT-QP1', 'B737', 'arrived', true, true, true, false, 'mock');

-- polling stopped again later (re-tracked flight): the same leg is replaced, not duplicated
insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, final_status, had_gate, provider)
values ('99999999-0000-0000-0000-0000000000a2', '6E', '9102', '2026-11-20', 'DEL', 'BLR', 'arrived', true, 'mock')
on conflict (flight_id) do update set had_gate = excluded.had_gate, finalized_at = now();

do $$ declare c record; begin
  assert (select count(*) from public.flight_leg_history where flight_id::text like '99999999-%') = 4, 'one row per leg';
  assert (select had_gate from public.flight_leg_history where flight_id = '99999999-0000-0000-0000-0000000000a2'), 'upserted';

  select * into c from public.provider_coverage_daily where dep = 'DEL' and date = '2026-11-20';
  assert c.legs = 3 and c.cancelled = 1 and c.flown = 2, format('counts %s', row_to_json(c));
  -- flown legs a1 (everything) and a2 (gate only after the upsert); the cancelled leg is not in the percentages
  assert c.pct_terminal = 50.0, format('terminal %s', c.pct_terminal);
  assert c.pct_gate = 100.0, format('gate %s', c.pct_gate);
  assert c.pct_belt = 50.0 and c.pct_tail = 50.0 and c.pct_position = 50.0, format('belt/tail/pos %s', row_to_json(c));
  assert c.avg_dep_delay_min = 10.0, format('avg delay %s', c.avg_dep_delay_min);

  select * into c from public.provider_coverage_daily where dep = 'BOM' and date = '2026-11-20';
  assert c.legs = 1 and c.pct_position = 0.0 and c.pct_gate = 100.0, format('BOM %s', row_to_json(c));

  -- deleting the flight drops its history row
  delete from public.flights where id = '99999999-0000-0000-0000-0000000000a4';
  assert not exists (select 1 from public.flight_leg_history where flight_id = '99999999-0000-0000-0000-0000000000a4'), 'cascade';
end $$;

-- app users and anon see none of it
insert into auth.users (id) values ('00000000-0000-0000-0000-0000000009a1') on conflict (id) do nothing;
insert into public.user_flights (user_id, flight_id) values
  ('00000000-0000-0000-0000-0000000009a1', '99999999-0000-0000-0000-0000000000a1');
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000009a1"}';
do $$ begin
  begin perform 1 from public.flight_leg_history; raise exception 'FAIL: user read flight_leg_history';
  exception when insufficient_privilege then null; end;
  begin perform 1 from public.provider_coverage_daily; raise exception 'FAIL: user read provider_coverage_daily';
  exception when insufficient_privilege then null; end;
  -- flight_view is unchanged by this migration (no live_seen / checkin internals needed by the apps)
  assert (select count(*) from public.flight_view where id = '99999999-0000-0000-0000-0000000000a1') = 1, 'own flight visible';
end $$;
rollback;
begin;
set local role anon;
do $$ begin
  begin perform 1 from public.provider_coverage_daily; raise exception 'FAIL: anon read coverage';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- service role reads the view (security_invoker: service_role has the table grant)
begin;
set local role service_role;
do $$ begin
  assert (select count(*) from public.provider_coverage_daily where dep = 'DEL') >= 1, 'service role reads coverage';
end $$;
rollback;

select 'history tests: OK' as result;
