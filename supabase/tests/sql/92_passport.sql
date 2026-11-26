-- Passport and Year in Review (P2 contract section 2): my_passport() scopes, counting rules and numbers.
-- Distances use the seed coordinates (haversine, R = 6371 km), worked out by hand:
--   DEL–BOM 1138.23 km, DEL–JFK 11754.48 km, BOM–DXB 1926.16 km
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000092a1'),  -- free
  ('00000000-0000-0000-0000-0000000092a2'),  -- pro
  ('00000000-0000-0000-0000-0000000092a3')   -- someone else
on conflict (id) do nothing;
update public.profiles set trial_used = true
 where id in ('00000000-0000-0000-0000-0000000092a1', '00000000-0000-0000-0000-0000000092a3');
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-0000000092a2';

-- P1 6E 101 DEL→BOM 400 days ago: 20 min late, 120 min airborne (actuals), A320neo
-- P2 6E 102 BOM→DEL 30 days ago:   5 min late, 125 min airborne (actuals), A320neo   (archived)
-- P3 AI 101 DEL→JFK 20 days ago: 185 min late, no actual arrival → 960 min block × 0.9 = 864, B777
-- P4 6E 103 DEL→BOM 10 days ago:  no actual times → 130 × 0.9 = 117, no model
-- P5 EK 511 BOM→DXB  5 days ago:  on time, 170 min airborne, A380
-- X1 cancelled, X2 tomorrow, X3 tracked by someone else only: never counted
create temp table pp (id uuid, carrier text, num text, dep text, arr text, days int, status text,
                      block_min int, dep_delay int, air_min int, model text, airline text);
insert into pp values
  ('92929292-0000-0000-0000-000000000001', '6E', '101', 'DEL', 'BOM', 400, 'arrived', 130, 20, 120, 'A320neo', 'IndiGo'),
  ('92929292-0000-0000-0000-000000000002', '6E', '102', 'BOM', 'DEL', 30, 'arrived', 130, 5, 125, 'A320neo', 'IndiGo'),
  ('92929292-0000-0000-0000-000000000003', 'AI', '101', 'DEL', 'JFK', 20, 'arrived', 960, 185, null, 'B777', 'Air India'),
  ('92929292-0000-0000-0000-000000000004', '6E', '103', 'DEL', 'BOM', 10, 'arrived', 130, null, null, null, 'IndiGo'),
  ('92929292-0000-0000-0000-000000000005', 'EK', '511', 'BOM', 'DXB', 5, 'arrived', 190, 0, 170, 'A380', 'Emirates'),
  ('92929292-0000-0000-0000-000000000006', '6E', '104', 'DEL', 'BOM', 3, 'cancelled', 130, null, null, null, 'IndiGo'),
  ('92929292-0000-0000-0000-000000000007', '6E', '105', 'DEL', 'BLR', -1, 'scheduled', 160, null, null, null, 'IndiGo'),
  ('92929292-0000-0000-0000-000000000008', 'SG', '8', 'DEL', 'BOM', 2, 'arrived', 130, 30, 120, null, 'SpiceJet');

insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, airline_name, aircraft_model,
                            sched_dep, sched_arr, act_dep, act_arr)
select p.id, p.carrier, p.num, (t.sd at time zone 'Asia/Kolkata')::date, p.dep, p.arr, p.status, p.airline, p.model,
       t.sd, t.sd + make_interval(mins => p.block_min),
       case when p.dep_delay is not null then t.sd + make_interval(mins => p.dep_delay) end,
       case when p.air_min is not null then t.sd + make_interval(mins => p.dep_delay + p.air_min) end
  from pp p
 cross join lateral (select date_trunc('minute', now()) - make_interval(days => p.days) as sd) t;

-- both test users keep P1..P5, X1 and X2 (archived rows count too); X3 belongs to someone else
insert into public.user_flights (user_id, flight_id, archived_at)
select u.id, p.id, case when p.num = '102' then now() end
  from pp p cross join (values ('00000000-0000-0000-0000-0000000092a1'::uuid), ('00000000-0000-0000-0000-0000000092a2'::uuid)) u(id)
 where p.carrier <> 'SG';
insert into public.user_flights (user_id, flight_id)
values ('00000000-0000-0000-0000-0000000092a3', '92929292-0000-0000-0000-000000000008');

begin;
set local role anon;
do $$ begin
  begin perform public.my_passport(); raise exception 'FAIL: anon called my_passport';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== Pro: all time =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000092a2"}';
do $$ declare p jsonb; begin
  p := public.my_passport();
  assert p ->> 'scope' = 'all' and p ->> 'pro_limited' = 'false', format('scope %s', p);
  assert (p ->> 'year')::int = extract(year from now() at time zone 'Asia/Kolkata')::int, 'year = current year';
  assert p ->> 'flights' = '5', format('flights %s', p ->> 'flights');
  -- 3 × 1138.23 + 11754.48 + 1926.16 = 17095.34
  assert p ->> 'distance_km' = '17095', format('distance %s', p ->> 'distance_km');
  -- 120 + 125 + 864 + 117 + 170
  assert p ->> 'airborne_minutes' = '1396', format('airborne %s', p ->> 'airborne_minutes');
  assert p -> 'airports' = '[{"iata":"BOM","count":4},{"iata":"DEL","count":4},{"iata":"DXB","count":1},{"iata":"JFK","count":1}]'::jsonb,
    format('airports %s', p -> 'airports');
  assert p -> 'countries' = '["AE","IN","US"]'::jsonb, format('countries %s', p -> 'countries');
  assert p -> 'airlines' = '[{"carrier":"6E","name":"IndiGo","count":3},{"carrier":"AI","name":"Air India","count":1},{"carrier":"EK","name":"Emirates","count":1}]'::jsonb,
    format('airlines %s', p -> 'airlines');
  assert p -> 'aircraft' = '[{"model":"A320neo","count":2},{"model":"A380","count":1},{"model":"B777","count":1}]'::jsonb,
    format('aircraft %s', p -> 'aircraft');
  assert p -> 'longest' = '{"flight_id":"92929292-0000-0000-0000-000000000003","carrier":"AI","number":"101","dep":"DEL","arr":"JFK","distance_km":11754}'::jsonb,
    format('longest %s', p -> 'longest');
  assert p -> 'top_route' = '{"dep":"DEL","arr":"BOM","count":2}'::jsonb, format('top route %s', p -> 'top_route');
  -- delays with actuals: 20, 5, 185, 0 → 210 min; on time (≤ 15) 2 of 4
  assert p -> 'delay' = '{"total_min":210,"on_time_pct":50,"worst":{"carrier":"AI","number":"101","dep_delay_min":185}}'::jsonb,
    format('delay %s', p -> 'delay');
  -- 3 × 1138.23 × 0.115 + 11754.48 × 0.085 + 1926.16 × 0.095 = 392.69 + 999.13 + 182.99 = 1574.81
  assert p ->> 'co2_kg' = '1575' and p ->> 'co2_method' = 'estimate-v1', format('co2 %s', p ->> 'co2_kg');
  assert jsonb_array_length(p -> 'by_month') between 2 and 4, format('by_month %s', p -> 'by_month');
  assert (select sum((m ->> 'flights')::int) from jsonb_array_elements(p -> 'by_month') m) = 5, 'by_month adds up';
  assert p -> 'by_month' -> 0 ->> 'month' = to_char((now() at time zone 'Asia/Kolkata') - interval '400 days', 'YYYY-MM'),
    format('first month %s', p -> 'by_month');
  assert p ->> 'first_flight_date' = to_char(date_trunc('minute', now()) at time zone 'Asia/Kolkata' - interval '400 days', 'YYYY-MM-DD'),
    format('first flight %s', p ->> 'first_flight_date');

  -- an earlier year is Pro-only beyond 12 months: Pro gets it in full
  p := public.my_passport(extract(year from (now() at time zone 'Asia/Kolkata') - interval '400 days')::int);
  assert p ->> 'scope' = 'year' and p ->> 'pro_limited' = 'false', format('pro year %s', p);
  assert (p ->> 'flights')::int >= 1, 'P1 is in its year';

  begin perform public.my_passport(1800); raise exception 'FAIL: year 1800 accepted';
  exception when invalid_parameter_value then null; end;
end $$;
rollback;

-- ===== free: last 12 months, Year in Review, earlier years =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000092a1"}';
do $$ declare p jsonb; y int := extract(year from now() at time zone 'Asia/Kolkata')::int; n int; begin
  p := public.my_passport();
  assert p ->> 'scope' = 'last12' and p ->> 'pro_limited' = 'true', format('free scope %s', p);
  -- P1 (400 days ago) is outside the last 12 months
  assert p ->> 'flights' = '4', format('free flights %s', p ->> 'flights');
  assert p ->> 'distance_km' = '15957', format('free distance %s', p ->> 'distance_km');     -- 17095.34 − 1138.23
  assert p ->> 'airborne_minutes' = '1276', format('free airborne %s', p ->> 'airborne_minutes'); -- 1396 − 120
  assert p ->> 'co2_kg' = '1444', format('free co2 %s', p ->> 'co2_kg');                     -- 1574.81 − 130.90
  -- delays 5, 185, 0 → 190 min, on time 2 of 3 = 67 %
  assert p -> 'delay' = '{"total_min":190,"on_time_pct":67,"worst":{"carrier":"AI","number":"101","dep_delay_min":185}}'::jsonb,
    format('free delay %s', p -> 'delay');
  assert p -> 'airports' = '[{"iata":"BOM","count":3},{"iata":"DEL","count":3},{"iata":"DXB","count":1},{"iata":"JFK","count":1}]'::jsonb,
    format('free airports %s', p -> 'airports');
  -- every route once: ties break on dep, then arr
  assert p -> 'top_route' = '{"dep":"BOM","arr":"DEL","count":1}'::jsonb, format('free top route %s', p -> 'top_route');
  assert p ->> 'first_flight_date' = to_char(date_trunc('minute', now()) at time zone 'Asia/Kolkata' - interval '30 days', 'YYYY-MM-DD'),
    format('free first flight %s', p ->> 'first_flight_date');

  -- Year in Review for the current year is free and complete
  p := public.my_passport(y);
  select count(*) into n from public.flight_view f
   where f.id in ('92929292-0000-0000-0000-000000000002', '92929292-0000-0000-0000-000000000003',
                  '92929292-0000-0000-0000-000000000004', '92929292-0000-0000-0000-000000000005')
     and extract(year from f.flight_date) = y;
  assert p ->> 'scope' = 'year' and p ->> 'pro_limited' = 'false' and (p ->> 'year')::int = y, format('free YIR %s', p);
  assert (p ->> 'flights')::int = n, format('free YIR flights %s, expected %s', p ->> 'flights', n);

  -- two years back lies entirely outside the last 12 months: nothing, and marked limited
  p := public.my_passport(y - 2);
  assert p ->> 'scope' = 'year' and p ->> 'pro_limited' = 'true' and p ->> 'flights' = '0', format('free old year %s', p);
  assert p -> 'longest' = 'null'::jsonb and p -> 'top_route' = 'null'::jsonb and p -> 'airports' = '[]'::jsonb,
    format('empty shape %s', p);
  assert p -> 'delay' = '{"total_min":0,"on_time_pct":null,"worst":null}'::jsonb and p ->> 'distance_km' = '0'
     and p -> 'first_flight_date' = 'null'::jsonb and p -> 'by_month' = '[]'::jsonb, format('empty numbers %s', p);
end $$;
rollback;

-- ===== someone else: only their own flight =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000092a3"}';
do $$ declare p jsonb; begin
  p := public.my_passport();
  assert p ->> 'flights' = '1' and p -> 'airlines' -> 0 ->> 'carrier' = 'SG', format('own data only %s', p);
  assert p -> 'delay' = '{"total_min":30,"on_time_pct":0,"worst":{"carrier":"SG","number":"8","dep_delay_min":30}}'::jsonb,
    format('SG delay %s', p -> 'delay');
end $$;
rollback;

drop table pp;
select 'passport tests: OK' as result;
