-- P3 timeline, taxi times, aircraft and the new event types (docs/P3_CONTRACTS.md A2, A5, B3, C):
-- flight_timeline() order and states (en route, cancelled, diverted, not started), free vs Pro detail,
-- taxi_stats() percentiles and window, flight_view aircraft columns, flight_events check.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000a3a01'),  -- Pro
  ('00000000-0000-0000-0000-0000000a3a02'),  -- free, trial used
  ('00000000-0000-0000-0000-0000000a3a03')   -- tracks nothing
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-0000000a3a01';
update public.profiles set trial_used = true
 where id in ('00000000-0000-0000-0000-0000000a3a02', '00000000-0000-0000-0000-0000000a3a03');

insert into public.aircraft (reg, model, icao_type, first_flight_date, delivery_date, age_years, airline_iata)
values ('VT-TST', 'Airbus A320neo', 'A20N', current_date - 3653, current_date - 3600, 9.9, '6E');

-- T1 en route (gate, boarding event, pushback 08:10, takeoff 08:25); T2 cancelled after a gate;
-- T3 diverted to IXZ; T4 nothing happened yet.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status,
                            sched_dep, est_dep, act_dep, sched_arr, est_arr, dep_terminal, dep_gate,
                            dep_offblock_at, dep_runway_at, aircraft_reg)
values ('a3a3a3a3-0000-0000-0000-000000000001', 'ZZ', '701', '2030-01-01', 'IXA', 'IXB', 'en_route',
        '2030-01-01 08:00+00', null, '2030-01-01 08:25+00', '2030-01-01 09:30+00', '2030-01-01 09:40+00', '1', '4',
        '2030-01-01 08:10+00', '2030-01-01 08:25+00', 'VT-TST'),
       ('a3a3a3a3-0000-0000-0000-000000000002', 'ZZ', '702', '2030-01-01', 'IXA', 'IXB', 'cancelled',
        '2030-01-01 08:00+00', null, null, '2030-01-01 09:30+00', null, '1', '6', null, null, null),
       ('a3a3a3a3-0000-0000-0000-000000000003', 'ZZ', '703', '2030-01-01', 'IXA', 'IXZ', 'diverted',
        '2030-01-01 08:00+00', null, '2030-01-01 08:20+00', '2030-01-01 09:30+00', '2030-01-01 10:30+00', '1', null,
        null, null, null),
       ('a3a3a3a3-0000-0000-0000-000000000004', 'ZZ', '704', '2030-01-01', 'IXA', 'IXB', 'scheduled',
        '2030-01-01 08:00+00', null, null, '2030-01-01 09:30+00', null, null, null, null, null, null);

insert into public.flight_events (flight_id, type, severity, payload, dedupe_key, created_at) values
  ('a3a3a3a3-0000-0000-0000-000000000001', 'GATE_ASSIGNED', 'info', '{}', 'a3:t1:gate', '2030-01-01 06:00+00'),
  ('a3a3a3a3-0000-0000-0000-000000000001', 'BOARDING', 'critical', '{}', 'a3:t1:board', '2030-01-01 07:20+00'),
  ('a3a3a3a3-0000-0000-0000-000000000002', 'CANCELLED', 'critical', '{}', 'a3:t2:cancel', '2030-01-01 05:00+00'),
  ('a3a3a3a3-0000-0000-0000-000000000003', 'DIVERTED', 'critical', '{"origArr":"IXB"}', 'a3:t3:div', '2030-01-01 09:00+00'),
  -- every new P3 type passes the check (migration 30)
  ('a3a3a3a3-0000-0000-0000-000000000004', 'GATE_CLOSED', 'info', '{}', 'a3:t4:gc', now()),
  ('a3a3a3a3-0000-0000-0000-000000000004', 'TAKEOFF', 'info', '{}', 'a3:t4:to', now()),
  ('a3a3a3a3-0000-0000-0000-000000000004', 'AT_GATE', 'info', '{}', 'a3:t4:ag', now()),
  ('a3a3a3a3-0000-0000-0000-000000000004', 'AIRCRAFT_SWAP', 'critical', '{}', 'a3:t4:sw', now()),
  ('a3a3a3a3-0000-0000-0000-000000000004', 'CONNECTION_AT_RISK', 'critical', '{}', 'a3:t4:cr', now());
delete from public.flight_events where dedupe_key like 'a3:t4:%';
do $$ begin
  begin
    insert into public.flight_events (flight_id, type, severity, dedupe_key)
    values ('a3a3a3a3-0000-0000-0000-000000000004', 'GATE_OPENED', 'info', 'a3:bad');
    raise exception 'FAIL: unknown event type accepted';
  exception when check_violation then null; end;
end $$;

insert into public.user_flights (user_id, flight_id)
select u, f from unnest(array['00000000-0000-0000-0000-0000000a3a01', '00000000-0000-0000-0000-0000000a3a02']::uuid[]) u,
                 unnest(array['a3a3a3a3-0000-0000-0000-000000000001', 'a3a3a3a3-0000-0000-0000-000000000002',
                              'a3a3a3a3-0000-0000-0000-000000000003', 'a3a3a3a3-0000-0000-0000-000000000004']::uuid[]) f;

-- taxi: departures from IXA 10, 12, 14, 16, 18 (+ one 40 days ago, out of the window); arrivals at IXB 5, 7
do $$ declare fid uuid; i int; begin
  for i in 0 .. 5 loop
    insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status)
    values ('ZZ', (800 + i)::text, current_date - case when i = 5 then 40 else 1 end, 'IXA', 'IXB', 'arrived')
    returning id into fid;
    insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, final_status, taxi_out_min, taxi_in_min)
    values (fid, 'ZZ', (800 + i)::text, current_date - case when i = 5 then 40 else 1 end, 'IXA', 'IXB', 'arrived',
            case when i = 5 then 99 else 10 + 2 * i end, case i when 0 then 5 when 1 then 7 else null end);
  end loop;
  begin
    insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, final_status, taxi_out_min)
    values (fid, 'ZZ', '899', current_date, 'IXA', 'IXB', 'arrived', 500);
    raise exception 'FAIL: absurd taxi time accepted';
  exception when check_violation or unique_violation then null; end;
end $$;

-- ===== access =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3a03"}';
do $$ begin
  begin
    perform public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000001');
    raise exception 'FAIL: timeline of a flight the caller cannot read';
  exception when insufficient_privilege then assert sqlerrm = 'not_allowed', sqlerrm; end;
  begin
    perform public.taxi_stats('IXA');
    raise exception 'FAIL: taxi stats for a free user';
  exception when insufficient_privilege then assert sqlerrm = 'pro_required', sqlerrm; end;
end $$;
rollback;
begin;
set local role anon;
do $$ begin
  begin
    perform public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000001');
    raise exception 'FAIL: anon got a timeline';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== free: keys, times and states, no detail =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3a02"}';
do $$ declare r jsonb; begin
  r := public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000001');
  assert (select array_agg(x ->> 'key' order by o) from jsonb_array_elements(r) with ordinality t(x, o))
       = array['scheduled', 'gate_assigned', 'boarding', 'gate_closed', 'pushback', 'takeoff', 'landing', 'at_gate', 'belt'],
         'en route order (check-in never seen → left out): ' || r::text;
  assert (select array_agg(x ->> 'state' order by o) from jsonb_array_elements(r) with ordinality t(x, o))
       = array['done', 'done', 'done', 'done', 'done', 'current', 'next', 'next', 'next'], r::text;
  assert r -> 0 ->> 'at' = '2030-01-01T08:00:00Z', r::text;
  assert r -> 1 ->> 'at' = '2030-01-01T06:00:00Z', 'gate from the event: ' || r::text;
  assert r -> 2 ->> 'at' = '2030-01-01T07:20:00Z', r::text;
  assert r -> 3 -> 'at' = 'null'::jsonb, 'gate closed implied, no time: ' || r::text;
  assert r -> 4 ->> 'at' = '2030-01-01T08:10:00Z' and r -> 5 ->> 'at' = '2030-01-01T08:25:00Z', r::text;
  assert r -> 6 ->> 'at' = '2030-01-01T09:40:00Z', 'landing next = estimate: ' || r::text;
  assert (select bool_and(x -> 'detail' = 'null'::jsonb) from jsonb_array_elements(r) x), 'free: no detail ' || r::text;
  -- aircraft: type for everyone, age for Pro only
  assert (select aircraft_icao_type = 'A20N' and aircraft_age_years is null
            and dep_offblock_at = '2030-01-01 08:10+00' and dep_runway_at = '2030-01-01 08:25+00'
            from public.flight_view where id = 'a3a3a3a3-0000-0000-0000-000000000001'), 'free flight_view';
end $$;
rollback;

-- ===== Pro: detail + other shapes =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3a01"}';
do $$ declare r jsonb; begin
  r := public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000001');
  assert r -> 1 -> 'detail' = '{"gate": "4", "terminal": "1"}'::jsonb, r::text;
  assert (r -> 4 -> 'detail' ->> 'delay_min')::int = 10, 'pushback 10 min late: ' || r::text;
  assert (r -> 5 -> 'detail' ->> 'taxi_out_min')::int = 15, r::text;
  assert (r -> 6 -> 'detail' ->> 'delay_min')::int = 10, 'landing expected 10 min late: ' || r::text;
  assert (select aircraft_age_years = 10.0 from public.flight_view where id = 'a3a3a3a3-0000-0000-0000-000000000001'),
         'Pro sees the age (first flight 3653 days ago)';

  r := public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000002');
  assert (select array_agg(x ->> 'key' || ':' || (x ->> 'state') order by o) from jsonb_array_elements(r) with ordinality t(x, o))
       = array['scheduled:done', 'gate_assigned:done', 'cancelled:current'], 'cancelled: ' || r::text;
  assert r -> 2 ->> 'at' = '2030-01-01T05:00:00Z', r::text;

  r := public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000003');
  assert (select array_agg(x ->> 'key' || ':' || (x ->> 'state') order by o) from jsonb_array_elements(r) with ordinality t(x, o))
       = array['scheduled:done', 'boarding:done', 'gate_closed:done', 'pushback:done', 'takeoff:done', 'diverted:current',
               'landing:next', 'at_gate:next', 'belt:next'], 'diverted: ' || r::text;
  assert r -> 3 ->> 'at' = '2030-01-01T08:20:00Z', 'pushback = act_dep without runway time: ' || r::text;
  assert r -> 4 -> 'at' = 'null'::jsonb, 'takeoff implied by the status: ' || r::text;
  assert r -> 5 -> 'detail' = '{"from_arr": "IXB", "to_arr": "IXZ"}'::jsonb, r::text;
  assert r -> 6 ->> 'at' = '2030-01-01T10:30:00Z', r::text;

  r := public.flight_timeline('a3a3a3a3-0000-0000-0000-000000000004');
  assert (select array_agg(x ->> 'key' order by o) from jsonb_array_elements(r) with ordinality t(x, o))
       = array['scheduled', 'checkin_open', 'gate_assigned', 'boarding', 'gate_closed', 'pushback', 'takeoff',
               'landing', 'at_gate', 'belt'], r::text;
  assert r -> 0 ->> 'state' = 'current' and (select bool_and(x ->> 'state' = 'next') from jsonb_array_elements(r) x
                                              where x ->> 'key' <> 'scheduled'), r::text;
  assert r -> 5 ->> 'at' = '2030-01-01T08:00:00Z' and r -> 7 ->> 'at' = '2030-01-01T09:30:00Z', r::text;

  r := public.taxi_stats('ixa');
  assert r -> 'taxi_out' = '{"p50": 14, "p80": 16, "samples": 5}'::jsonb, 'p80 of 10..18 = 16.4: ' || r::text;
  assert public.taxi_stats('IXB') -> 'taxi_in' = '{"p50": 6, "p80": 7, "samples": 2}'::jsonb, 'p80 of 5,7 = 6.6';
  assert public.taxi_stats('IXB') -> 'taxi_out' = '{"p50": null, "p80": null, "samples": 0}'::jsonb;
end $$;
rollback;

\echo '99t1_timeline_aircraft: ok'
