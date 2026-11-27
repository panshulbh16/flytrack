-- P3 A1 / A3 / B5 (docs/P3_CONTRACTS.md): the anti-abuse tracking cap (100 free / 300 Pro), the 'calendar'
-- source, route_search_cache access, and reserve_search_units (route searches cost 2 quota units).
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000099a101'),  -- free
  ('00000000-0000-0000-0000-00000099a102'),  -- pro
  ('00000000-0000-0000-0000-00000099a103')   -- quota user
on conflict (id) do nothing;
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-00000099a101';
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-00000099a102';

-- ===== tracking cap =====
do $$ begin
  assert public.tracking_cap('00000000-0000-0000-0000-00000099a101') = 100, 'free cap 100';
  assert public.tracking_cap('00000000-0000-0000-0000-00000099a102') = 300, 'pro cap 300';
end $$;

-- 101 future flights; the Pro user tracks all 101 (over the free cap, within the Pro one)
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr)
select ('99a1a1a1-0000-0000-0000-00000000' || lpad(i::text, 4, '0'))::uuid, 'QP', (7000 + i)::text, current_date + 4,
       'BLR', 'DEL', now() + interval '4 days', now() + interval '4 days 3 hours'
  from generate_series(0, 100) i;
insert into public.user_flights (user_id, flight_id)
select '00000000-0000-0000-0000-00000099a102', ('99a1a1a1-0000-0000-0000-00000000' || lpad(i::text, 4, '0'))::uuid
  from generate_series(0, 100) i;
do $$ begin
  assert (select count(*) from public.user_flights where user_id = '00000000-0000-0000-0000-00000099a102') = 101,
         'Pro user tracks 101 active flights';
end $$;

-- the free user tracks 100; the 101st fails with the same error the apps already handle
insert into public.user_flights (user_id, flight_id)
select '00000000-0000-0000-0000-00000099a101', ('99a1a1a1-0000-0000-0000-00000000' || lpad(i::text, 4, '0'))::uuid
  from generate_series(0, 99) i;
do $$ begin
  begin
    insert into public.user_flights (user_id, flight_id)
    values ('00000000-0000-0000-0000-00000099a101', '99a1a1a1-0000-0000-0000-000000000100');
    raise exception 'FAIL: 101st active flight allowed for a free user';
  exception when raise_exception then
    assert sqlerrm = 'tracking_limit_reached', sqlerrm;
  end;
  -- an archived insert (a past flight for Passport) is never capped
  insert into public.user_flights (user_id, flight_id, archived_at, source)
  values ('00000000-0000-0000-0000-00000099a101', '99a1a1a1-0000-0000-0000-000000000100', now(), 'manual');
end $$;

-- ===== 'calendar' source =====
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr)
values ('99a1a1a1-0000-0000-0000-00000000c001', 'AI', '7999', current_date + 6, 'DEL', 'BOM',
        now() + interval '6 days', now() + interval '6 days 2 hours');
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a103"}';
insert into public.user_flights (flight_id, source) values ('99a1a1a1-0000-0000-0000-00000000c001', 'calendar');
do $$ begin
  assert (select source from public.user_flights where flight_id = '99a1a1a1-0000-0000-0000-00000000c001') = 'calendar',
         'calendar source stored';
  assert (select pg_get_constraintdef(oid) from pg_constraint where conname = 'user_flights_source_check')
         ~ 'manual.*pdf.*share.*screenshot.*gmail.*scan.*email.*calendar', 'every source still listed';
end $$;
rollback;

-- ===== route_search_cache: service role only =====
begin;
set local role service_role;
insert into public.route_search_cache (dep, date, payload) values ('DEL', current_date, '[{"carrier":"6E"}]');
insert into public.route_search_cache (dep, date, payload) values ('DEL', current_date, '[]')
  on conflict (dep, date) do update set payload = excluded.payload, fetched_at = now();
do $$ begin
  assert (select payload = '[]'::jsonb from public.route_search_cache where dep = 'DEL' and date = current_date),
         'upsert replaces the payload';
  begin
    insert into public.route_search_cache (dep, date, payload) values ('BOM', current_date, '{"not":"array"}');
    raise exception 'FAIL: non-array payload accepted';
  exception when check_violation then null; end;
end $$;
-- trim drops rows older than 2 days only
insert into public.route_search_cache (dep, date, payload, fetched_at) values ('BLR', current_date - 5, '[]', now() - interval '5 days');
do $$ begin
  assert public.trim_route_search_cache() = 1, 'one old row trimmed';
  assert exists (select 1 from public.route_search_cache where dep = 'DEL'), 'fresh row kept';
end $$;
commit;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a103"}';
do $$ begin
  begin
    perform 1 from public.route_search_cache;
    raise exception 'FAIL: client read route_search_cache';
  exception when insufficient_privilege then null; end;
  begin
    perform public.reserve_search_units(auth.uid(), 30, 2);
    raise exception 'FAIL: client reserved quota';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== reserve_search_units: 2 units per route search, one counter with flight searches =====
do $$ declare ok int := 0; i int; begin
  for i in 1..20 loop
    if public.reserve_search_units('00000000-0000-0000-0000-00000099a103', 30, 2) then ok := ok + 1; end if;
  end loop;
  assert ok = 15, format('15 route searches fit a 30-unit day, got %s', ok);
  assert (select uses from public.search_quota_usage where user_id = '00000000-0000-0000-0000-00000099a103') = 30,
         'all 30 units used';
  assert not public.reserve_search_quota('00000000-0000-0000-0000-00000099a103', 30), 'flight search shares the counter';
end $$;
do $$ begin
  delete from public.search_quota_usage where user_id = '00000000-0000-0000-0000-00000099a103';
  -- 29 used: a 2-unit route search fails and uses nothing, a 1-unit flight search still fits
  insert into public.search_quota_usage (user_id, day, uses)
  values ('00000000-0000-0000-0000-00000099a103', (now() at time zone 'Asia/Kolkata')::date, 29);
  assert not public.reserve_search_units('00000000-0000-0000-0000-00000099a103', 30, 2), 'not enough units left';
  assert (select uses from public.search_quota_usage where user_id = '00000000-0000-0000-0000-00000099a103') = 29,
         'a failed reservation uses nothing';
  assert public.reserve_search_quota('00000000-0000-0000-0000-00000099a103', 30), 'the last unit is still usable';
  -- bad arguments
  assert not public.reserve_search_units('00000000-0000-0000-0000-00000099a103', 30, 0), 'cost 0';
  assert not public.reserve_search_units('00000000-0000-0000-0000-00000099a103', 1, 2), 'cost above the limit';
  assert not public.reserve_search_units(null, 30, 2), 'no user';
end $$;

select '99s1 limits + route search: ok' as result;
