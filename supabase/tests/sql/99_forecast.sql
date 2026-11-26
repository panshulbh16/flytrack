-- P2 wave 2 arrival forecast (docs/P2_WAVE2_CONTRACTS.md section 4): flight_forecast() access and Pro
-- gating, the history fallback chain (flight → carrier + route → route), statistics, quantile forecasts
-- before and after departure, the est_arr cap, and the "schedule" method without history.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000099f1'),  -- Pro
  ('00000000-0000-0000-0000-0000000099f2'),  -- free, trial used
  ('00000000-0000-0000-0000-0000000099f3'),  -- free, trial on F2
  ('00000000-0000-0000-0000-0000000099f4')   -- tracks nothing
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-0000000099f1';
update public.profiles set trial_used = true
 where id in ('00000000-0000-0000-0000-0000000099f2', '00000000-0000-0000-0000-0000000099f4');

-- One finished leg: scheduled at 08:00 UTC on p_date, departs p_dep_delay late, flies p_block minutes.
create function pg_temp.add_leg(p_carrier text, p_number text, p_dep text, p_arr text, p_date date,
                                p_dep_delay int, p_block int, p_status text default 'arrived') returns void
language plpgsql as $$
declare
  fid uuid;
  d   timestamptz := p_date::timestamp at time zone 'UTC' + interval '8 hours';
begin
  insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status)
  values (p_carrier, p_number, p_date, p_dep, p_arr, p_status) returning id into fid;
  insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, sched_dep, act_dep, sched_arr, act_arr,
                                         dep_delay_min, arr_delay_min, final_status)
  values (fid, p_carrier, p_number, p_date, p_dep, p_arr, d,
          case when p_status = 'cancelled' then null else d + make_interval(mins => p_dep_delay) end,
          d + interval '130 minutes',
          case when p_status = 'cancelled' then null else d + make_interval(mins => p_dep_delay + p_block) end,
          case when p_status = 'cancelled' then null else p_dep_delay end,
          case when p_status = 'cancelled' then null else p_dep_delay + p_block - 130 end,
          p_status);
end $$;

-- 6E 9911 DEL→BOM: 20 flown legs (dep delay i, block 120 + i, i = 0..19), 1 cancelled, 1 outside the window,
-- and 3 legs of the same number on another route (must not count).
do $$ begin perform pg_temp.add_leg('6E', '9911', 'DEL', 'BOM', current_date - 1 - i, i, 120 + i) from generate_series(0, 19) i; end $$;
do $$ begin perform pg_temp.add_leg('6E', '9911', 'DEL', 'BOM', current_date - 30, 0, 0, 'cancelled'); end $$;
do $$ begin perform pg_temp.add_leg('6E', '9911', 'DEL', 'BOM', current_date - 70, 300, 300); end $$;
do $$ begin perform pg_temp.add_leg('6E', '9911', 'BOM', 'GOI', current_date - 1 - i, 0, 60) from generate_series(0, 2) i; end $$;
-- AI BOM→DEL: 3 legs of AI 9912 + 4 of other AI numbers = 7 (carrier + route level); 9 legs of other carriers
do $$ begin perform pg_temp.add_leg('AI', '9912', 'BOM', 'DEL', current_date - 1 - i, 10, 130) from generate_series(0, 2) i; end $$;
do $$ begin perform pg_temp.add_leg('AI', (9920 + i)::text, 'BOM', 'DEL', current_date - 1, 20, 140) from generate_series(0, 3) i; end $$;
do $$ begin perform pg_temp.add_leg('UK', (9930 + i)::text, 'BOM', 'DEL', current_date - 2, 0, 125) from generate_series(0, 8) i; end $$;
-- BLR→HYD: no QP legs, 6 of other carriers (route level)
do $$ begin perform pg_temp.add_leg('6E', (9940 + i)::text, 'BLR', 'HYD', current_date - 3, 5, 70) from generate_series(0, 5) i; end $$;

-- Tracked flights. F1 6E 9911 DEL→BOM; F2 AI 9912 BOM→DEL; F3 QP 9913 BLR→HYD; F4 SG 9914 DEL→GOI (no history)
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, est_dep, sched_arr, est_arr)
values ('99f99f99-0000-0000-0000-000000000001', '6E', '9911', '2030-03-01', 'DEL', 'BOM', 'scheduled',
        '2030-03-01 08:00+00', null, '2030-03-01 10:10+00', null),
       ('99f99f99-0000-0000-0000-000000000002', 'AI', '9912', '2030-03-01', 'BOM', 'DEL', 'scheduled',
        '2030-03-01 08:00+00', null, '2030-03-01 10:10+00', null),
       ('99f99f99-0000-0000-0000-000000000003', 'QP', '9913', '2030-03-01', 'BLR', 'HYD', 'scheduled',
        '2030-03-01 08:00+00', null, '2030-03-01 09:15+00', null),
       ('99f99f99-0000-0000-0000-000000000004', 'SG', '9914', '2030-03-01', 'DEL', 'GOI', 'scheduled',
        '2030-03-01 08:00+00', '2030-03-01 08:40+00', '2030-03-01 10:30+00', null);
insert into public.user_flights (user_id, flight_id) values
  ('00000000-0000-0000-0000-0000000099f1', '99f99f99-0000-0000-0000-000000000001'),
  ('00000000-0000-0000-0000-0000000099f1', '99f99f99-0000-0000-0000-000000000002'),
  ('00000000-0000-0000-0000-0000000099f1', '99f99f99-0000-0000-0000-000000000003'),
  ('00000000-0000-0000-0000-0000000099f1', '99f99f99-0000-0000-0000-000000000004'),
  ('00000000-0000-0000-0000-0000000099f2', '99f99f99-0000-0000-0000-000000000001');
-- f3 never had a trial: their first flight F2 becomes the trial flight
update public.profiles set trial_used = false where id = '00000000-0000-0000-0000-0000000099f3';
insert into public.user_flights (user_id, flight_id) values ('00000000-0000-0000-0000-0000000099f3', '99f99f99-0000-0000-0000-000000000002');
do $$ begin
  assert (select trial_flight_id from public.profiles where id = '00000000-0000-0000-0000-0000000099f3')
         = '99f99f99-0000-0000-0000-000000000002', 'trial set';
end $$;

-- ===== access =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f4"}';
do $$ begin
  begin
    perform public.flight_forecast('99f99f99-0000-0000-0000-000000000001');
    raise exception 'FAIL: forecast for a flight the caller cannot read';
  exception when insufficient_privilege then assert sqlerrm = 'not_allowed', sqlerrm; end;
  begin
    perform public.flight_forecast('99f99f99-0000-0000-0000-00000000ffff');
    raise exception 'FAIL: forecast for an unknown flight';
  exception when insufficient_privilege then null; end;
  begin
    perform public.forecast_iso(now());
    raise exception 'FAIL: clients can call forecast_iso';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
begin;
set local role anon;
do $$ begin
  begin
    perform public.flight_forecast('99f99f99-0000-0000-0000-000000000001');
    raise exception 'FAIL: anon got a forecast';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== free caller: locked, no detail =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f2"}';
do $$ declare r jsonb; begin
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000001');
  assert r = '{"history": null, "forecast": null, "detail_locked": true}'::jsonb, r::text;
end $$;
rollback;

-- ===== trial flight: unlocked =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f3"}';
do $$ declare r jsonb; begin
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000002');
  assert (r ->> 'detail_locked')::boolean = false, r::text;
  assert r -> 'history' is not null and r -> 'forecast' is not null, r::text;
end $$;
rollback;

-- ===== Pro: same number + route, 21 samples → high confidence =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare r jsonb; h jsonb; fc jsonb; begin
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000001');
  h := r -> 'history';
  fc := r -> 'forecast';
  assert (r ->> 'detail_locked')::boolean = false, r::text;
  assert h ->> 'basis' = 'flight', h::text;
  assert (h ->> 'samples')::int = 21, 'samples incl. cancelled, window 60 days, same route: ' || h::text;
  assert (h ->> 'window_days')::int = 60, h::text;
  assert (h ->> 'cancelled')::int = 1, h::text;
  assert (h ->> 'on_time_pct')::int = 80, '16 of 20 flown legs within 15 min: ' || h::text;
  assert (h ->> 'avg_dep_delay_min')::int = 10, 'avg 9.5 rounds to 10: ' || h::text;
  assert (h ->> 'median_arr_delay_min')::int = 9, 'median of 2i - 10: ' || h::text;
  -- not departed, no provider estimate: sched_dep + quantiles of (act_arr - sched_dep) = 120 + 2i
  assert fc ->> 'method' = 'history', fc::text;
  assert fc ->> 'confidence' = 'high', fc::text;
  assert fc ->> 'arr_p50' = '2030-03-01T10:19:00Z', 'p50 = 08:00 + 139 min: ' || fc::text;
  assert fc ->> 'arr_p80' = '2030-03-01T10:30:00Z', 'p80 = 08:00 + 150.4 min: ' || fc::text;
end $$;
rollback;

-- a provider estimate different from the schedule: est_dep + block-time quantiles (120 + i)
begin;
update public.flights set est_dep = '2030-03-01 08:30+00' where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000001') -> 'forecast';
  assert fc ->> 'arr_p50' = '2030-03-01T10:40:00Z', 'est_dep + 129.5 min: ' || fc::text;
  assert fc ->> 'arr_p80' = '2030-03-01T10:45:00Z', 'est_dep + 135.2 min: ' || fc::text;
end $$;
rollback;

-- departed: act_dep + block, capped by est_arr (p50 at est_arr, p80 at est_arr + 15)
begin;
update public.flights set status = 'en_route', act_dep = '2030-03-01 08:10+00', est_arr = '2030-03-01 10:15+00'
 where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000001') -> 'forecast';
  assert fc ->> 'method' = 'history', fc::text;
  assert fc ->> 'arr_p50' = '2030-03-01T10:15:00Z', 'capped at est_arr (08:10 + 129.5 > 10:15): ' || fc::text;
  assert fc ->> 'arr_p80' = '2030-03-01T10:25:00Z', '08:10 + 135.2 min, under est_arr + 15: ' || fc::text;
end $$;
rollback;
begin;
update public.flights set status = 'en_route', act_dep = '2030-03-01 08:10+00', est_arr = '2030-03-01 11:30+00'
 where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000001') -> 'forecast';
  assert fc ->> 'arr_p50' = '2030-03-01T10:20:00Z', 'history below a late est_arr: ' || fc::text;
  assert fc ->> 'arr_p80' = '2030-03-01T10:25:00Z', fc::text;
end $$;
rollback;
-- a stale est_arr (before the actual departure) is no cap
begin;
update public.flights set status = 'en_route', act_dep = '2030-03-01 08:10+00', est_arr = '2030-03-01 08:00+00'
 where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000001') -> 'forecast';
  assert fc ->> 'arr_p50' = '2030-03-01T10:20:00Z', 'stale est_arr ignored: ' || fc::text;
  assert fc ->> 'arr_p80' = '2030-03-01T10:25:00Z', fc::text;
end $$;
rollback;
-- still on the ground after the scheduled departure: never a forecast in the past (now + block time)
begin;
update public.flights set sched_dep = now() - interval '3 hours', sched_arr = now() - interval '1 hour',
                          est_dep = null, est_arr = null
 where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000001') -> 'forecast';
  assert fc ->> 'method' = 'history', fc::text;
  assert (fc ->> 'arr_p50')::timestamptz between now() + interval '129 minutes' and now() + interval '131 minutes',
         'p50 = now + 129.5 min: ' || fc::text;
  assert (fc ->> 'arr_p80')::timestamptz between now() + interval '134 minutes' and now() + interval '136 minutes',
         'p80 = now + 135.2 min: ' || fc::text;
end $$;
rollback;

-- arrived: the actual time; cancelled: no forecast but history stays
begin;
update public.flights set status = 'arrived', act_dep = '2030-03-01 08:10+00', act_arr = '2030-03-01 10:07+00'
 where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000001') -> 'forecast';
  assert fc ->> 'arr_p50' = '2030-03-01T10:07:00Z' and fc ->> 'arr_p80' = '2030-03-01T10:07:00Z', fc::text;
  assert fc ->> 'confidence' = 'high', fc::text;
end $$;
rollback;
begin;
update public.flights set status = 'cancelled' where id = '99f99f99-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare r jsonb; begin
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000001');
  assert r -> 'forecast' = 'null'::jsonb, r::text;
  assert (r -> 'history' ->> 'samples')::int = 21, r::text;
end $$;
rollback;

-- ===== fallback chain =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare r jsonb; begin
  -- AI 9912 has 3 legs (< 5) → AI on BOM→DEL: 7 legs, under 8 → low confidence
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000002');
  assert r -> 'history' ->> 'basis' = 'carrier_route', r::text;
  assert (r -> 'history' ->> 'samples')::int = 7, r::text;
  assert r -> 'forecast' ->> 'method' = 'history', r::text;
  assert r -> 'forecast' ->> 'confidence' = 'low', r::text;
  -- totals: 3 × 140, 4 × 160 → p50 = 160, p80 = 160
  assert r -> 'forecast' ->> 'arr_p50' = '2030-03-01T10:40:00Z', r::text;
  -- QP on BLR→HYD: none → the route, 6 legs of other carriers
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000003');
  assert r -> 'history' ->> 'basis' = 'route', r::text;
  assert (r -> 'history' ->> 'samples')::int = 6, r::text;
  assert (r -> 'history' ->> 'on_time_pct')::int = 100, r::text;
  assert r -> 'forecast' ->> 'arr_p50' = '2030-03-01T09:15:00Z', '08:00 + 5 + 70: ' || r::text;
  -- SG on DEL→GOI: no history anywhere → schedule: est_dep + scheduled block, p80 + 15
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000004');
  assert (r -> 'history' ->> 'samples')::int = 0, r::text;
  assert r -> 'history' -> 'on_time_pct' = 'null'::jsonb, r::text;
  assert r -> 'forecast' ->> 'method' = 'schedule', r::text;
  assert r -> 'forecast' ->> 'confidence' = 'low', r::text;
  assert r -> 'forecast' ->> 'arr_p50' = '2030-03-01T11:10:00Z', '08:40 + 150 min: ' || r::text;
  assert r -> 'forecast' ->> 'arr_p80' = '2030-03-01T11:25:00Z', r::text;
end $$;
rollback;
-- medium at 8+: add one more AI leg on BOM→DEL
begin;
do $$ begin perform pg_temp.add_leg('AI', '9929', 'BOM', 'DEL', current_date - 4, 20, 140); end $$;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare r jsonb; begin
  r := public.flight_forecast('99f99f99-0000-0000-0000-000000000002');
  assert (r -> 'history' ->> 'samples')::int = 8 and r -> 'forecast' ->> 'confidence' = 'medium', r::text;
end $$;
rollback;
-- schedule method with a provider est_arr uses it
begin;
update public.flights set est_arr = '2030-03-01 11:05+00' where id = '99f99f99-0000-0000-0000-000000000004';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099f1"}';
do $$ declare fc jsonb; begin
  fc := public.flight_forecast('99f99f99-0000-0000-0000-000000000004') -> 'forecast';
  assert fc ->> 'arr_p50' = '2030-03-01T11:05:00Z' and fc ->> 'arr_p80' = '2030-03-01T11:20:00Z', fc::text;
end $$;
rollback;

\echo '99_forecast: ok'
