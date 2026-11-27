-- P3 B7 / B8 / B11 (docs/P3_CONTRACTS.md): airport_trends (Pro), airport_status_public (no user data,
-- service role only) and global_report (k-anonymity: unique users rounded to 100 and hidden under 1,000,
-- ranked rows need 200 legs). All data here uses BDQ and the year 2024, which no other SQL test touches.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000099a201'),  -- free
  ('00000000-0000-0000-0000-00000099a202')   -- pro
on conflict (id) do nothing;
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-00000099a201';
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-00000099a202';

-- =================================================================================================
-- airport_status_public (B8)
-- =================================================================================================
-- Today at BDQ (IST date): 5 departed (3 more than 15 min late), 1 cancelled, 1 still scheduled.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, act_dep)
select ('99a2a2a2-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, '6E', (7100 + i)::text,
       (now() at time zone 'Asia/Kolkata')::date, 'BDQ', 'DEL',
       case when i = 6 then 'cancelled' when i = 7 then 'scheduled' else 'departed' end,
       now() - interval '1 hour',
       case when i <= 5 then now() - interval '1 hour' + make_interval(mins => case when i <= 3 then 40 else 5 end) end
  from generate_series(1, 7) i;

begin;
set local role service_role;
do $$ declare j jsonb; begin
  j := public.airport_status_public('bdq');
  assert j ->> 'iata' = 'BDQ', 'normalised code';
  assert j ->> 'name' = 'Vadodara Airport' and j ->> 'city' = 'Vadodara' and j ->> 'tz' = 'Asia/Kolkata', 'airport fields';
  assert (j ->> 'lat')::numeric = 22.3362 and (j ->> 'lon')::numeric = 73.2263, 'coordinates for the page globe';
  assert j ? 'level' and j ? 'headline' and j ? 'updated_at', 'Airport Health fields';
  assert not j ? 'detail', 'no Pro detail in the public status';
  assert j -> 'weather' = 'null'::jsonb, 'no recent METAR → weather null';
  assert (j -> 'today' ->> 'departures')::int = 7, 'departures today: ' || (j -> 'today')::text;
  assert (j -> 'today' ->> 'departed')::int = 5, 'departed';
  assert (j -> 'today' ->> 'cancelled')::int = 1, 'cancelled';
  assert (j -> 'today' ->> 'delayed_pct')::int = 60, '3 of 5 departed late';
  assert j -> 'today' ->> 'date' = to_char((now() at time zone 'Asia/Kolkata')::date, 'YYYY-MM-DD'), 'local date';
  -- nothing about users anywhere in the object
  assert j::text !~ '(user|pnr|seat|device|token)', 'no user data: ' || j::text;
  assert public.airport_status_public('ZZZ') is null, 'unknown airport → null';
  begin
    perform public.airport_status_public('DELHI');
    raise exception 'FAIL: bad code accepted';
  exception when invalid_parameter_value then null; end;
end $$;
-- fewer than 5 departed legs → delayed_pct null (too few to say)
update public.flights set act_dep = null, status = 'scheduled' where id = '99a2a2a2-0000-0000-0000-000000000005';
do $$ begin
  assert public.airport_status_public('BDQ') -> 'today' -> 'delayed_pct' = 'null'::jsonb, 'delayed_pct hidden under 5';
end $$;
-- fog: level and weather summary come from the shared Airport Health core
insert into public.airport_weather (iata, icao, observed_at, raw_metar, visibility_m, wx, flight_category, fog)
values ('BDQ', 'VABO', now() - interval '10 minutes', 'VABO 260100Z 00000KT 0300 FG', 300, 'FG', 'LIFR', true);
do $$ declare j jsonb; begin
  j := public.airport_status_public('BDQ');
  assert j ->> 'level' = 'severe', 'fog → severe: ' || j::text;
  assert j ->> 'headline' = 'Fog: visibility 300 m', j ->> 'headline';
  assert j -> 'weather' ->> 'summary' = 'Fog: visibility 300 m', 'weather summary';
  assert (j -> 'weather' ->> 'visibility_m')::int = 300 and (j -> 'weather' ->> 'fog')::boolean, 'weather numbers';
end $$;
rollback;

-- signed-in users: get_airport_health keeps its contract (level/headline for all, detail for Pro only)
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a201"}';
do $$ declare j jsonb; begin
  j := public.get_airport_health('BDQ');
  assert j ->> 'iata' = 'BDQ' and j ? 'level' and j -> 'detail' = 'null'::jsonb, 'free: no detail';
  assert not j ? 'weather' and not j ? 'today', 'the public-only fields stay out of get_airport_health';
  begin
    perform public.airport_status_public('BDQ');
    raise exception 'FAIL: client called airport_status_public';
  exception when insufficient_privilege then null; end;
  begin
    perform public.global_report(2024);
    raise exception 'FAIL: client called global_report';
  exception when insufficient_privilege then null; end;
  begin
    perform public.airport_health_core('BDQ', 'pro');
    raise exception 'FAIL: client called airport_health_core';
  exception when insufficient_privilege then null; end;
end $$;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a202"}';
do $$ declare j jsonb; begin
  j := public.get_airport_health('BDQ');
  assert jsonb_typeof(j -> 'detail') = 'object' and (j -> 'detail' ? 'sample_size'), 'pro: detail';
end $$;
rollback;

begin;
set local role anon;
do $$ begin
  begin
    perform public.airport_status_public('BDQ');
    raise exception 'FAIL: anon called airport_status_public';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- =================================================================================================
-- airport_trends (B7)
-- =================================================================================================
-- BDQ legs: yesterday 06:30 IST (10 min late), 2 days ago 06:45 IST (30 min late), yesterday 18:10 IST
-- (60 min late), 2 days ago 18:20 IST cancelled, 30 days ago 06:30 (outside the default 14 days).
create temp table tl (id uuid, days int, hhmm text, delay int, status text);
insert into tl values
  ('99a2a2a2-0000-0000-0000-0000000001a1', 1, '06:30', 10, 'arrived'),
  ('99a2a2a2-0000-0000-0000-0000000001a2', 2, '06:45', 30, 'arrived'),
  ('99a2a2a2-0000-0000-0000-0000000001a3', 1, '18:10', 60, 'arrived'),
  ('99a2a2a2-0000-0000-0000-0000000001a4', 2, '18:20', null, 'cancelled'),
  ('99a2a2a2-0000-0000-0000-0000000001a5', 30, '06:30', 90, 'arrived');
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep)
select t.id, 'IX', (7200 + row_number() over (order by t.id))::text,
       (now() at time zone 'Asia/Kolkata')::date - t.days, 'BDQ', 'BOM', t.status,
       (((now() at time zone 'Asia/Kolkata')::date - t.days)::text || ' ' || t.hhmm)::timestamp at time zone 'Asia/Kolkata'
  from tl t;
insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, sched_dep, dep_delay_min, final_status)
select f.id, f.carrier_iata, f.flight_number, f.flight_date, f.dep_iata, f.arr_iata, f.sched_dep, t.delay, t.status
  from public.flights f join tl t on t.id = f.id;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a201"}';
do $$ begin
  begin
    perform public.airport_trends('BDQ');
    raise exception 'FAIL: free user got trends';
  exception when insufficient_privilege then assert sqlerrm = 'pro_required', sqlerrm; end;
end $$;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a202"}';
do $$ declare j jsonb; h6 jsonb; h18 jsonb; begin
  j := public.airport_trends('bdq');
  assert (j ->> 'days')::int = 14, 'default 14 days';
  assert jsonb_array_length(j -> 'by_hour') = 2, 'two hours: ' || (j -> 'by_hour')::text;
  h6 := j -> 'by_hour' -> 0;
  h18 := j -> 'by_hour' -> 1;
  assert (h6 ->> 'hour')::int = 6 and (h6 ->> 'samples')::int = 2 and (h6 ->> 'avg_dep_delay')::int = 20
         and (h6 ->> 'delayed_pct')::int = 50, 'hour 6: ' || h6::text;
  assert (h18 ->> 'hour')::int = 18 and (h18 ->> 'samples')::int = 1 and (h18 ->> 'avg_dep_delay')::int = 60
         and (h18 ->> 'delayed_pct')::int = 100, 'hour 18 (cancelled leg left out): ' || h18::text;
  assert jsonb_array_length(j -> 'by_day') = 2, 'two days';
  assert (j -> 'by_day' -> 0 ->> 'cancelled')::int = 1 and (j -> 'by_day' -> 0 ->> 'avg_dep_delay')::int = 30,
         'two days ago: ' || (j -> 'by_day' -> 0)::text;
  assert (j -> 'by_day' -> 1 ->> 'avg_dep_delay')::int = 35 and (j -> 'by_day' -> 1 ->> 'cancelled')::int = 0,
         'yesterday: ' || (j -> 'by_day' -> 1)::text;
  -- 31 days covers the old leg too
  j := public.airport_trends('BDQ', 31);
  assert (j -> 'by_hour' -> 0 ->> 'samples')::int = 3, 'old leg inside 31 days';
  assert public.airport_trends('ZZZ') is null, 'unknown airport';
  begin
    perform public.airport_trends('BDQ', 0);
    raise exception 'FAIL: 0 days accepted';
  exception when invalid_parameter_value then null; end;
  begin
    perform public.airport_trends('BDQ', 91);
    raise exception 'FAIL: 91 days accepted';
  exception when invalid_parameter_value then null; end;
end $$;
rollback;

-- =================================================================================================
-- global_report (B11), year 2024
-- =================================================================================================
-- Totals: 1,060 users each track one of two DEL→BOM flights on 2024-12-20.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
values ('99a2a2a2-0000-0000-0000-000000002401', '6E', '2401', '2024-12-20', 'DEL', 'BOM', 'arrived',
        '2024-12-20 03:00+00', '2024-12-20 05:10+00'),
       ('99a2a2a2-0000-0000-0000-000000002402', '6E', '2402', '2024-12-21', 'DEL', 'BOM', 'arrived',
        '2024-12-21 03:00+00', '2024-12-21 05:10+00');
insert into auth.users (id)
select ('99a2a2a2-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid from generate_series(1, 1060) i;
insert into public.user_flights (user_id, flight_id, archived_at)
select ('99a2a2a2-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
       case when i % 2 = 0 then '99a2a2a2-0000-0000-0000-000000002401'::uuid else '99a2a2a2-0000-0000-0000-000000002402'::uuid end,
       now()
  from generate_series(1, 1060) i;

-- Legs (flight_leg_history needs a flights row each):
--   Z1 DEL→BOM 200 legs, 5 min late      → ranked (on time 100%)
--   Z2 BDQ→DEL 199 legs, 60 min late     → under 200: airline, airport and route all left out
--   Z3 BOM→DEL 250 legs, 40 min late     → ranked (on time 0%)
--   Z4 DEL→BOM 3 legs on 2024-12-21, 90 min late → too few to rank; makes 12-21 a fog-delay day at DEL
create temp table gl (carrier text, n int, dep text, arr text, delay int, fixed_date date);
insert into gl values ('Z1', 200, 'DEL', 'BOM', 5, null), ('Z2', 199, 'BDQ', 'DEL', 60, null),
                      ('Z3', 250, 'BOM', 'DEL', 40, null), ('Z4', 3, 'DEL', 'BOM', 90, '2024-12-21');
insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, airline_name, sched_dep)
select g.carrier, i::text, coalesce(g.fixed_date, date '2024-01-01' + (i % 300)), g.dep, g.arr, 'arrived',
       g.carrier || ' Airways', coalesce(g.fixed_date, date '2024-01-01' + (i % 300)) + time '04:00'
  from gl g cross join lateral generate_series(1, g.n) i;
insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, sched_dep, dep_delay_min, final_status)
select f.id, f.carrier_iata, f.flight_number, f.flight_date, f.dep_iata, f.arr_iata, f.sched_dep, g.delay, 'arrived'
  from public.flights f join gl g on g.carrier = f.carrier_iata
 where f.flight_date between '2024-01-01' and '2024-12-31';

-- Fog alerts at DEL on 2024-12-20 and 2024-12-21 (two keys on the 20th: counted once).
insert into public.flight_events (flight_id, type, severity, dedupe_key) values
  ('99a2a2a2-0000-0000-0000-000000002401', 'FOG_ALERT', 'critical', '99a2a2a2-0000-0000-0000-000000002401:FOG:DEL:2024-12-20'),
  ('99a2a2a2-0000-0000-0000-000000002402', 'FOG_ALERT', 'critical', '99a2a2a2-0000-0000-0000-000000002402:FOG:DEL:2024-12-21'),
  ('99a2a2a2-0000-0000-0000-000000002402', 'FOG_ALERT', 'critical', '99a2a2a2-0000-0000-0000-000000002402:FOG:DEL:2024-12-20'),
  ('99a2a2a2-0000-0000-0000-000000002402', 'FOG_ALERT', 'critical', '99a2a2a2-0000-0000-0000-000000002402:FOG:BOM:2024-12-21');

begin;
set local role service_role;
do $$ declare j jsonb; km double precision; begin
  j := public.global_report(2024);
  assert (j ->> 'year')::int = 2024 and (j ->> 'k_min_legs')::int = 200, 'header';
  assert (j -> 'totals' ->> 'flights_tracked')::int = 1060, 'flights tracked: ' || (j -> 'totals')::text;
  assert (j -> 'totals' ->> 'unique_users')::int = 1100, '1,060 users → 1,100 (nearest 100)';
  select public.great_circle_km(d.lat, d.lon, a.lat, a.lon) into km
    from public.airports d, public.airports a where d.iata = 'DEL' and a.iata = 'BOM';
  assert (j -> 'totals' ->> 'distance_km')::bigint = round(1060 * km)::bigint, 'distance';
  assert (j -> 'totals' ->> 'legs')::int = 652, 'all finished legs of the year';
  -- airlines: Z2 (199 legs) and Z4 (3) never appear
  assert j -> 'airlines' -> 'most_delayed' -> 0 ->> 'carrier' = 'Z3', 'most delayed airline';
  assert (j -> 'airlines' -> 'most_delayed' -> 0 ->> 'on_time_pct')::int = 0, 'Z3 on time 0%';
  assert (j -> 'airlines' -> 'most_delayed' -> 0 ->> 'legs')::int = 250, 'Z3 legs';
  assert j -> 'airlines' -> 'most_delayed' -> 0 ->> 'name' = 'Z3 Airways', 'airline name from flights';
  assert j -> 'airlines' -> 'most_punctual' -> 0 ->> 'carrier' = 'Z1', 'most punctual airline';
  assert jsonb_array_length(j -> 'airlines' -> 'most_delayed') = 2, 'only Z1 and Z3 ranked';
  assert j::text !~ '"Z2"' and j::text !~ '"Z4"', 'airlines under 200 legs never appear';
  -- airports: BDQ has 199 departures → left out; DEL 203, BOM 250
  assert j -> 'airports' -> 'most_delayed' -> 0 ->> 'iata' = 'BOM', 'most delayed airport';
  assert j -> 'airports' -> 'most_punctual' -> 0 ->> 'iata' = 'DEL', 'most punctual airport';
  assert j -> 'airports' -> 'most_punctual' -> 0 ->> 'city' = 'New Delhi', 'airport city';
  assert j::text !~ 'BDQ', 'BDQ (199 legs) never appears';
  -- routes: BOM→DEL 250, DEL→BOM 203; BDQ→DEL 199 left out
  assert jsonb_array_length(j -> 'busiest_routes') = 2, 'two routes: ' || (j -> 'busiest_routes')::text;
  assert (j -> 'busiest_routes' -> 0) - '{dep_lat,dep_lon,arr_lat,arr_lon}'::text[]
           = '{"dep":"BOM","arr":"DEL","legs":250}'::jsonb, 'busiest route';
  assert (j -> 'busiest_routes' -> 1) - '{dep_lat,dep_lon,arr_lat,arr_lon}'::text[]
           = '{"dep":"DEL","arr":"BOM","legs":203}'::jsonb, 'second route';
  -- the airports' public coordinates ride along for the report page's globe
  assert (j -> 'busiest_routes' -> 0 ->> 'arr_lat')::numeric = (select lat from public.airports where iata = 'DEL')
     and (j -> 'busiest_routes' -> 0 ->> 'dep_lon')::numeric = (select lon from public.airports where iata = 'BOM'),
         'route coordinates: ' || (j -> 'busiest_routes' -> 0)::text;
  -- fog at DEL: 2 days, 1 with departures averaging more than 30 min late
  assert j -> 'fog' = '{"airport":"DEL","fog_days":2,"fog_delay_days":1}'::jsonb, 'fog: ' || (j -> 'fog')::text;
  -- no user ids anywhere
  assert j::text !~ '99a2a2a2-0000-4000', 'no user ids in the report';

  begin
    perform public.global_report(2023);
    raise exception 'FAIL: 2023 accepted';
  exception when invalid_parameter_value then null; end;
  begin
    perform public.global_report(extract(year from now())::int + 2);
    raise exception 'FAIL: future year accepted';
  exception when invalid_parameter_value then null; end;
end $$;
-- under 1,000 users the count is withheld
delete from public.user_flights where user_id in (
  select ('99a2a2a2-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid from generate_series(1, 100) i);
do $$ declare j jsonb; begin
  j := public.global_report(2024);
  assert (j -> 'totals' ->> 'flights_tracked')::int = 960, '960 flights';
  assert j -> 'totals' -> 'unique_users' = 'null'::jsonb, '960 users → withheld';
end $$;
rollback;

select '99s2 trends + public: ok' as result;
