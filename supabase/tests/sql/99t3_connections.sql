-- P3 connection assistant (docs/P3_CONTRACTS.md B4): trip pairing, MCT lookup (airport row, terminal pair,
-- domestic / international, default), risk transitions ok → tight → at_risk → missed, terminal changes,
-- my_connections() gating, and the poll cycle's connection_alert_candidates() / save_connection_state().
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000a3c01'),  -- Pro, tracks every leg
  ('00000000-0000-0000-0000-0000000a3c02'),  -- free, trial used, tracks C1
  ('00000000-0000-0000-0000-0000000a3c03')   -- Pro, tracks only the arriving leg of C1
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id in ('00000000-0000-0000-0000-0000000a3c01', '00000000-0000-0000-0000-0000000a3c03');
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-0000000a3c02';

-- seed sanity
do $$ begin
  assert (select count(distinct airport) from public.min_connection_times
           where airport in ('DEL', 'BOM', 'BLR', 'HYD', 'MAA', 'CCU', 'COK', 'AMD')) = 8, 'seeded airports';
  assert not exists (select 1 from public.min_connection_times where verified), 'seed rows are unverified';
  assert (select dom_dom from public.min_connection_times where airport = 'DEL' and from_terminal = '1' and to_terminal = '3')
       = (select dom_dom from public.min_connection_times where airport = 'DEL' and from_terminal is null) + 60, 'DEL T1↔T3 +60';
  assert (select intl_intl from public.min_connection_times where airport = 'BOM' and from_terminal = '2' and to_terminal = '1')
       = (select intl_intl from public.min_connection_times where airport = 'BOM' and from_terminal is null) + 60, 'BOM T1↔T2 +60';
  assert (select mct_min from public.connection_mct('XYZ', null, null, true, true)) = 45, 'default dom_dom';
  assert (select mct_min from public.connection_mct('XYZ', null, null, true, false)) = 75, 'default dom_intl';
  assert (select mct_min from public.connection_mct('XYZ', null, null, false, true)) = 90, 'default intl_dom';
  assert (select mct_min from public.connection_mct('XYZ', null, null, false, false)) = 60, 'default intl_intl';
end $$;

-- C1 BOM→DEL (T1) then DEL (T3)→BLR 150 min later; C2 HYD→DEL (T3) then DEL (T3)→DXB 100 min later;
-- C3 IXA→IXB then IXB→IXZ 60 min later (no MCT row); X a second IXB departure 3 h later (not the next leg);
-- Y DEL→BOM 26 h after C2 arrives (outside 24 h).
create temp table t0 as select date_trunc('minute', now()) + interval '10 hours' as t;
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status,
                            sched_dep, sched_arr, dep_terminal, arr_terminal)
select x.id::uuid, 'ZZ', x.n, '2031-02-01', x.dep, x.arr, 'scheduled', t0.t + x.dep_off, t0.t + x.arr_off, x.dt, x.at
  from t0, (values
    ('a3c3c3c3-0000-0000-0000-000000000011', '901', 'BOM', 'DEL', interval '-130 minutes', interval '0 minutes', '1', '1'),
    ('a3c3c3c3-0000-0000-0000-000000000012', '902', 'DEL', 'BLR', interval '150 minutes', interval '320 minutes', '3', '1'),
    ('a3c3c3c3-0000-0000-0000-000000000021', '903', 'HYD', 'DEL', interval '8 hours', interval '10 hours', '1', '3'),
    ('a3c3c3c3-0000-0000-0000-000000000022', '904', 'DEL', 'DXB', interval '700 minutes', interval '940 minutes', '3', '1'),
    ('a3c3c3c3-0000-0000-0000-000000000023', '905', 'DEL', 'BOM', interval '36 hours', interval '38 hours', '1', '2'),
    ('a3c3c3c3-0000-0000-0000-000000000031', '906', 'IXA', 'IXB', interval '1 hour', interval '2 hours', null, null),
    ('a3c3c3c3-0000-0000-0000-000000000032', '907', 'IXB', 'IXZ', interval '3 hours', interval '5 hours', null, null),
    ('a3c3c3c3-0000-0000-0000-000000000033', '908', 'IXB', 'IXZ', interval '5 hours', interval '7 hours', null, null)
  ) as x(id, n, dep, arr, dep_off, arr_off, dt, at);
insert into public.user_flights (user_id, flight_id)
select '00000000-0000-0000-0000-0000000a3c01', f.id from public.flights f where f.id::text like 'a3c3c3c3-%';
insert into public.user_flights (user_id, flight_id) values
  ('00000000-0000-0000-0000-0000000a3c02', 'a3c3c3c3-0000-0000-0000-000000000011'),
  ('00000000-0000-0000-0000-0000000a3c02', 'a3c3c3c3-0000-0000-0000-000000000012'),
  ('00000000-0000-0000-0000-0000000a3c03', 'a3c3c3c3-0000-0000-0000-000000000011');

-- risk of C1 as computed now
create function pg_temp.c1() returns record language sql as $$
  select (c.layover_min, c.mct_min, c.buffer_min, c.risk, c.terminal_change)
    from public.connections_for_user('00000000-0000-0000-0000-0000000a3c01') c
   where c.in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000011';
$$;

-- ===== pairing + risk transitions (as the service) =====
do $$ declare r record; begin
  assert (select count(*) from public.connections_for_user('00000000-0000-0000-0000-0000000a3c01')) = 3,
         'C1, C2, C3 (X is not the next leg, Y is more than 24 h later)';
  assert (select out_flight_id from public.connections_for_user('00000000-0000-0000-0000-0000000a3c01')
           where in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000031') = 'a3c3c3c3-0000-0000-0000-000000000032';
  assert not exists (select 1 from public.connections_for_user('00000000-0000-0000-0000-0000000a3c03')), 'one leg is no trip';

  -- C1: T1→T3 at DEL = 60 + 60 min; layover 150 → buffer 30 → ok
  assert pg_temp.c1()::text = '(150,120,30,ok,T1→T3)', pg_temp.c1()::text;
  update public.flights set est_arr = (select t from t0) + interval '15 minutes' where id = 'a3c3c3c3-0000-0000-0000-000000000011';
  assert pg_temp.c1()::text = '(135,120,15,tight,T1→T3)', pg_temp.c1()::text;
  update public.flights set est_arr = (select t from t0) + interval '40 minutes' where id = 'a3c3c3c3-0000-0000-0000-000000000011';
  assert pg_temp.c1()::text = '(110,120,-10,at_risk,T1→T3)', pg_temp.c1()::text;
  -- the same delay without a terminal change is fine (DEL base row, 60 min)
  update public.flights set dep_terminal = '1' where id = 'a3c3c3c3-0000-0000-0000-000000000012';
  assert pg_temp.c1()::text = '(110,60,50,ok,)', pg_temp.c1()::text;
  update public.flights set dep_terminal = '3' where id = 'a3c3c3c3-0000-0000-0000-000000000012';
  -- the departing leg pushed back before the arriving one reached the gate
  update public.flights set act_dep = (select t from t0) + interval '35 minutes', status = 'departed'
   where id = 'a3c3c3c3-0000-0000-0000-000000000012';
  assert (pg_temp.c1()::text) like '%,missed,%', pg_temp.c1()::text;
  update public.flights set act_dep = null, status = 'scheduled', est_arr = null
   where id = 'a3c3c3c3-0000-0000-0000-000000000012' or id = 'a3c3c3c3-0000-0000-0000-000000000011';

  -- C2: domestic → international at DEL (T3 → T3): dom_intl 90, layover 100 → tight, no terminal change
  select * into r from public.connections_for_user('00000000-0000-0000-0000-0000000a3c01')
   where in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000021';
  assert r.mct_min = 90 and r.buffer_min = 10 and r.risk = 'tight' and r.terminal_change is null, r::text;
  -- C3: no MCT row at IXB → 45, layover 60 → tight
  select * into r from public.connections_for_user('00000000-0000-0000-0000-0000000a3c01')
   where in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000031';
  assert r.mct_min = 45 and r.buffer_min = 15 and r.risk = 'tight' and r.transfer_note is null, r::text;
end $$;

-- ===== alert candidates: only Pro trackers, only changes of the stored risk =====
do $$ begin
  assert (select count(*) from public.connection_alert_candidates(100) c
           where c.in_flight_id::text like 'a3c3c3c3-%') = 3, 'no state yet: every Pro connection';
  assert not exists (select 1 from public.connection_alert_candidates(100) c
                      where c.user_id <> '00000000-0000-0000-0000-0000000a3c01' and c.in_flight_id::text like 'a3c3c3c3-%'),
         'the free user and the one-leg Pro user have no candidates';
  perform public.save_connection_state(c.in_flight_id, c.out_flight_id, c.risk, c.gen)
     from public.connection_alert_candidates(100) c where c.in_flight_id::text like 'a3c3c3c3-%';
  assert not exists (select 1 from public.connection_alert_candidates(100) c where c.in_flight_id::text like 'a3c3c3c3-%'),
         'state stored: nothing changed';
  update public.flights set est_arr = (select t from t0) + interval '40 minutes' where id = 'a3c3c3c3-0000-0000-0000-000000000011';
  assert (select last_risk || '>' || risk || ':' || gen || ':' || buffer_min from public.connection_alert_candidates(100) c
           where c.in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000011') = 'ok>at_risk:0:-10', 'transition reported';
  perform public.save_connection_state('a3c3c3c3-0000-0000-0000-000000000011', 'a3c3c3c3-0000-0000-0000-000000000012', 'at_risk', 1);
  perform public.save_connection_state('a3c3c3c3-0000-0000-0000-000000000011', 'a3c3c3c3-0000-0000-0000-000000000012', 'at_risk', 0);
  assert (select gen from public.connection_alert_state where in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000011') = 1,
         'gen never moves backwards';
  -- landed: no longer a candidate
  update public.flights set act_arr = now(), status = 'landed', est_arr = null where id = 'a3c3c3c3-0000-0000-0000-000000000011';
  assert not exists (select 1 from public.connection_alert_candidates(100) c
                      where c.in_flight_id = 'a3c3c3c3-0000-0000-0000-000000000011'), 'arrived leg';
  update public.flights set act_arr = null, status = 'scheduled' where id = 'a3c3c3c3-0000-0000-0000-000000000011';
end $$;

-- ===== my_connections =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3c02"}';
do $$ begin
  begin
    perform public.my_connections();
    raise exception 'FAIL: free user got connections';
  exception when insufficient_privilege then assert sqlerrm = 'pro_required', sqlerrm; end;
  begin
    perform public.connections_for_user('00000000-0000-0000-0000-0000000a3c01');
    raise exception 'FAIL: clients can read anyone''s trips';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3c01"}';
do $$ declare r jsonb; begin
  r := public.my_connections();
  assert jsonb_array_length(r) = 3, r::text;
  assert (select array_agg(x ->> 'out_flight' order by o) from jsonb_array_elements(r) with ordinality t(x, o))
       = array['ZZ 902', 'ZZ 907', 'ZZ 904'], r::text;
  assert r -> 0 ->> 'in_flight_id' = 'a3c3c3c3-0000-0000-0000-000000000011', 'ordered by departure: ' || r::text;
  assert (r -> 0) - 'in_arr' - 'out_dep' = jsonb_build_object(
    'in_flight_id', 'a3c3c3c3-0000-0000-0000-000000000011', 'out_flight_id', 'a3c3c3c3-0000-0000-0000-000000000012',
    'airport', 'DEL', 'in_flight', 'ZZ 901', 'out_flight', 'ZZ 902', 'layover_min', 150, 'mct_min', 120,
    'buffer_min', 30, 'risk', 'ok', 'terminal_change', 'T1→T3',
    'transfer_note', 'Shuttle between T1 and T3, about 20–30 min'), r::text;
end $$;
rollback;

-- ===== CONNECTION_AT_RISK rows: only users who track the connecting flight see them =====
insert into public.flight_events (flight_id, type, severity, pro_only, payload, dedupe_key)
values ('a3c3c3c3-0000-0000-0000-000000000011', 'CONNECTION_AT_RISK', 'critical', true,
        jsonb_build_object('out_flight', 'ZZ 902', 'out_flight_id', 'a3c3c3c3-0000-0000-0000-000000000012',
                           'airport', 'DEL', 'buffer_min', -10),
        'a3c3c3c3-0000-0000-0000-000000000011:a3c3c3c3-0000-0000-0000-000000000012:CONN:0');
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3c03"}';
do $$ begin
  assert not exists (select 1 from public.flight_events where type = 'CONNECTION_AT_RISK'
                      and flight_id = 'a3c3c3c3-0000-0000-0000-000000000011'),
         'a Pro stranger on the arriving flight must not see someone else''s connection';
end $$;
rollback;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3c01"}';
do $$ begin
  assert exists (select 1 from public.flight_events where type = 'CONNECTION_AT_RISK'
                  and flight_id = 'a3c3c3c3-0000-0000-0000-000000000011'), 'the connection''s owner sees it';
end $$;
rollback;

\echo '99t3_connections: ok'
