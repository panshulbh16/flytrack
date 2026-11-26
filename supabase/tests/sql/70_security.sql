-- Security (P1 contract section 5): can_read_flight on flight_view, reserve_search_quota (incl. a real
-- race through dblink), and the 5-device cap in register_device.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000007a1'),  -- free user, trial already used elsewhere
  ('00000000-0000-0000-0000-0000000007a2'),  -- pro user
  ('00000000-0000-0000-0000-0000000007a3'),  -- quota user
  ('00000000-0000-0000-0000-0000000007a4'),  -- device-cap user
  ('00000000-0000-0000-0000-0000000007a5')   -- second phone owner
on conflict (id) do nothing;
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-0000000007a1';
update public.profiles set plan = 'pro', pro_until = null where id = '00000000-0000-0000-0000-0000000007a2';

-- F_IN is the inbound leg (same aircraft) of F_OUT; F_OTHER is tracked by nobody.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr)
values ('77777777-0000-0000-0000-0000000000a0', 'AI', '701', current_date + 4, 'BLR', 'DEL', now() + interval '4 days', now() + interval '4 days 3 hours'),
       ('77777777-0000-0000-0000-0000000000b0', 'AI', '702', current_date + 4, 'GOI', 'BOM', now() + interval '4 days', now() + interval '4 days 1 hour');
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr, inbound_flight_id)
values ('77777777-0000-0000-0000-0000000000c0', 'AI', '703', current_date + 4, 'DEL', 'BOM', now() + interval '4 days 4 hours',
        now() + interval '4 days 6 hours', '77777777-0000-0000-0000-0000000000a0');

insert into public.user_flights (user_id, flight_id) values
  ('00000000-0000-0000-0000-0000000007a1', '77777777-0000-0000-0000-0000000000c0'),
  ('00000000-0000-0000-0000-0000000007a2', '77777777-0000-0000-0000-0000000000c0');

-- ===== can_read_flight / flight_view rows =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000007a1"}';
do $$ begin
  assert public.can_read_flight('77777777-0000-0000-0000-0000000000c0'), 'free user reads a tracked flight';
  assert not public.can_read_flight('77777777-0000-0000-0000-0000000000a0'), 'free user cannot read the inbound leg';
  assert not public.can_read_flight('77777777-0000-0000-0000-0000000000b0'), 'untracked flight not readable';
  assert (select count(*) from public.flight_view
           where id in ('77777777-0000-0000-0000-0000000000a0', '77777777-0000-0000-0000-0000000000b0',
                        '77777777-0000-0000-0000-0000000000c0')) = 1, 'flight_view returns only the tracked row';
  assert (select inbound_flight_id is null from public.flight_view where id = '77777777-0000-0000-0000-0000000000c0'),
    'inbound id masked for free users';
  -- no blanket scan of other people's flights
  assert not exists (select 1 from public.flight_view fv
                      where not exists (select 1 from public.user_flights uf where uf.flight_id = fv.id)),
    'flight_view never shows untracked flights to a free user';
end $$;
-- archived flights stay readable (history screen)
update public.user_flights set archived_at = now() where flight_id = '77777777-0000-0000-0000-0000000000c0';
do $$ begin
  assert public.can_read_flight('77777777-0000-0000-0000-0000000000c0'), 'archived tracked flight still readable';
end $$;
update public.user_flights set archived_at = null where flight_id = '77777777-0000-0000-0000-0000000000c0';
commit;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000007a2"}';
do $$ begin
  assert public.can_read_flight('77777777-0000-0000-0000-0000000000a0'), 'pro user reads the inbound leg';
  assert not public.can_read_flight('77777777-0000-0000-0000-0000000000b0'), 'pro user still cannot read random flights';
  assert (select inbound_flight_id from public.flight_view where id = '77777777-0000-0000-0000-0000000000c0')
         = '77777777-0000-0000-0000-0000000000a0', 'pro sees inbound id';
  assert (select count(*) from public.flight_view where id = '77777777-0000-0000-0000-0000000000a0') = 1,
    'inbound row in flight_view for pro';
end $$;
commit;

-- trial flight: the free user gets the inbound leg of their trial flight only
update public.profiles set trial_flight_id = '77777777-0000-0000-0000-0000000000c0'
 where id = '00000000-0000-0000-0000-0000000007a1';
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000007a1"}';
do $$ begin
  assert public.can_read_flight('77777777-0000-0000-0000-0000000000a0'), 'trial flight unlocks its inbound leg';
end $$;
commit;

-- anon and unauthenticated callers read nothing
begin;
set local role authenticated;
set local request.jwt.claims = '{}';
do $$ begin
  assert not public.can_read_flight('77777777-0000-0000-0000-0000000000c0'), 'no uid, no rows';
  assert (select count(*) from public.flight_view) = 0, 'flight_view empty without a user';
end $$;
commit;

-- ===== reserve_search_quota =====
do $$ declare i int; n int := 0; begin
  for i in 1..5 loop
    if public.reserve_search_quota('00000000-0000-0000-0000-0000000007a3', 3) then n := n + 1; end if;
  end loop;
  assert n = 3, format('expected 3 reservations, got %s', n);
  assert (select uses from public.search_quota_usage where user_id = '00000000-0000-0000-0000-0000000007a3') = 3,
    'usage never counts past the limit';
  -- a higher limit (e.g. account upgraded) allows more on the same day
  assert public.reserve_search_quota('00000000-0000-0000-0000-0000000007a3', 4), 'higher limit allows one more';
  assert not public.reserve_search_quota(null, 3), 'null user rejected';
  assert not public.reserve_search_quota('00000000-0000-0000-0000-0000000007a3', 0), 'zero limit rejected';
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000007a3"}';
do $$ begin
  begin
    perform public.reserve_search_quota(auth.uid(), 1000);
    raise exception 'FAIL: clients can reserve quota';
  exception when insufficient_privilege then null; end;
  begin
    perform 1 from public.search_quota_usage;
    raise exception 'FAIL: search_quota_usage readable';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- Race: 12 separate connections reserve at once for a fresh user with limit 5 → exactly 5 succeed.
create extension if not exists dblink;
do $$
declare
  i int;
  n int := 0;
  ok boolean;
  conn_str text := format('dbname=%s', current_database());
  user_id uuid := '00000000-0000-0000-0000-0000000007a4';
begin
  for i in 1..12 loop
    perform dblink_connect('quota' || i, conn_str);
  end loop;
  for i in 1..12 loop
    perform dblink_send_query('quota' || i,
      format('select public.reserve_search_quota(%L::uuid, 5)', user_id));
  end loop;
  for i in 1..12 loop
    select r.ok into ok from dblink_get_result('quota' || i) as r(ok boolean);
    if ok then n := n + 1; end if;
    perform dblink_disconnect('quota' || i);
  end loop;
  assert n = 5, format('race: expected 5 reservations, got %s', n);
  assert (select uses from public.search_quota_usage where search_quota_usage.user_id = '00000000-0000-0000-0000-0000000007a4') = 5,
    'race: usage is exactly the limit';
end $$;

-- ===== device cap: 5 per user, oldest last_seen_at evicted =====
-- Separate transactions so each registration gets its own now().
\set uid4 '{"sub":"00000000-0000-0000-0000-0000000007a4"}'
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok1_' || repeat('a', 24)) is not null as ok \gset
commit;
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok2_' || repeat('a', 24)) is not null as ok \gset
commit;
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok3_' || repeat('a', 24), null, null, null, null, 'ios') is not null as ok \gset
commit;
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok4_' || repeat('a', 24)) is not null as ok \gset
commit;
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok5_' || repeat('a', 24)) is not null as ok \gset
commit;
-- refreshing token 1 makes it recent again; 5 devices, nothing evicted
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok1_' || repeat('a', 24)) is not null as ok \gset
commit;
do $$ begin
  assert (select count(*) from public.devices where user_id = '00000000-0000-0000-0000-0000000007a4') = 5,
    'five devices allowed';
end $$;
-- the sixth device evicts the least recently seen one (token 2)
begin; set local role authenticated; set local request.jwt.claims = :'uid4'; select public.register_device('capTok6_' || repeat('a', 24)) is not null as ok \gset
commit;
do $$ begin
  assert (select count(*) from public.devices where user_id = '00000000-0000-0000-0000-0000000007a4') = 5,
    'still five after the sixth';
  assert not exists (select 1 from public.devices where fcm_token like 'capTok2_%'), 'oldest device evicted';
  assert exists (select 1 from public.devices where fcm_token like 'capTok1_%'), 'refreshed device kept';
  assert exists (select 1 from public.devices where fcm_token like 'capTok6_%'), 'new device kept';
end $$;
-- a phone moving to another account is capped for its new owner and freed for the old one
begin; set local role authenticated; set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000007a5"}';
select public.register_device('capTok3_' || repeat('a', 24), null, null, null, null, 'ios') is not null as ok \gset
commit;
do $$ begin
  assert (select count(*) from public.devices where user_id = '00000000-0000-0000-0000-0000000007a4') = 4, 'old owner has 4';
  assert (select user_id from public.devices where fcm_token like 'capTok3_%') = '00000000-0000-0000-0000-0000000007a5',
    'token moved to the new owner';
end $$;

select 'security tests: OK' as result;
