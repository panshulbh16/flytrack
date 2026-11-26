-- P2 alert prefs (docs/P2_CONTRACTS.md sections 3 and 4): quiet hours + alert_language on profiles,
-- FOG_ALERT / DIVERTED_DEST event types, get_flight_recipients returns language and quiet hours.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000095a1'),
  ('00000000-0000-0000-0000-0000000095a2')
on conflict (id) do nothing;

insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
values ('95959595-0000-0000-0000-000000000001', '6E', '951', current_date + 2, 'DEL', 'BOM', 'scheduled',
        now() + interval '2 days', now() + interval '2 days 2 hours');

-- ===== defaults =====
do $$ declare p record; begin
  select * into p from public.profiles where id = '00000000-0000-0000-0000-0000000095a1';
  assert not p.quiet_hours_enabled, 'quiet hours off by default';
  assert p.quiet_start = time '23:00' and p.quiet_end = time '07:00', 'default window 23:00-07:00';
  assert p.quiet_tz = 'Asia/Kolkata', 'default zone India';
  assert p.alert_language = 'en', 'default language en';
end $$;

-- ===== users edit their own prefs, nothing else =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000095a1"}';
update public.profiles
   set quiet_hours_enabled = true, quiet_start = '22:30', quiet_end = '06:00', quiet_tz = 'Asia/Dubai',
       alert_language = 'hinglish'
 where id = auth.uid();
-- someone else's row: RLS hides it, nothing changes
update public.profiles set alert_language = 'hi' where id = '00000000-0000-0000-0000-0000000095a2';
do $$ begin
  begin
    update public.profiles set alert_language = 'fr' where id = auth.uid();
    raise exception 'FAIL: unknown alert_language accepted';
  exception when check_violation then null; end;
  begin
    update public.profiles set quiet_tz = 'Asia/Kolkatta' where id = auth.uid();
    raise exception 'FAIL: unknown time zone accepted';
  exception when sqlstate '22023' then null; end;
  begin
    update public.profiles set quiet_tz = 'localtime' where id = auth.uid();
    raise exception 'FAIL: Postgres-only zone name accepted';
  exception when sqlstate '22023' then null; end;
  begin
    update public.profiles set quiet_tz = 'x; drop table y' where id = auth.uid();
    raise exception 'FAIL: junk time zone accepted';
  exception when check_violation or sqlstate '22023' then null; end;
  begin
    update public.profiles set plan = 'pro' where id = auth.uid();
    raise exception 'FAIL: user set their plan';
  exception when insufficient_privilege then null; end;
end $$;
insert into public.user_flights (flight_id) values ('95959595-0000-0000-0000-000000000001');
select public.register_device('prefTokA_' || repeat('p', 30)) is not null as ok \gset
commit;

do $$ declare p record; begin
  select * into p from public.profiles where id = '00000000-0000-0000-0000-0000000095a1';
  assert p.quiet_hours_enabled and p.quiet_start = time '22:30' and p.quiet_end = time '06:00', 'own window saved';
  assert p.quiet_tz = 'Asia/Dubai' and p.alert_language = 'hinglish', 'own zone + language saved';
  assert (select alert_language from public.profiles where id = '00000000-0000-0000-0000-0000000095a2') = 'en',
    'other users untouched';
end $$;

-- second user: defaults, Hindi
update public.profiles set alert_language = 'hi' where id = '00000000-0000-0000-0000-0000000095a2';
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000095a2"}';
insert into public.user_flights (flight_id) values ('95959595-0000-0000-0000-000000000001');
select public.register_device('prefTokB_' || repeat('q', 30)) is not null as ok \gset
do $$ begin
  begin
    perform * from public.get_flight_recipients('95959595-0000-0000-0000-000000000001');
    raise exception 'FAIL: clients can list recipients';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- ===== get_flight_recipients: language + quiet hours, Pro semantics unchanged =====
do $$ declare a record; b record; begin
  select * into a from public.get_flight_recipients('95959595-0000-0000-0000-000000000001')
   where user_id = '00000000-0000-0000-0000-0000000095a1';
  assert a.alert_language = 'hinglish' and a.quiet_hours_enabled, 'recipient language + quiet flag';
  assert a.quiet_start = time '22:30' and a.quiet_end = time '06:00' and a.quiet_tz = 'Asia/Dubai', 'recipient window';
  assert a.fcm_token = 'prefTokA_' || repeat('p', 30), 'device token';
  -- first tracked flight of a free user is their trial flight → Pro for it (is_pro_for_flight)
  assert a.is_pro = public.is_pro_for_flight(a.user_id, '95959595-0000-0000-0000-000000000001'), 'is_pro semantics';
  assert a.is_pro, 'trial flight recipient is Pro';
  select * into b from public.get_flight_recipients('95959595-0000-0000-0000-000000000001')
   where user_id = '00000000-0000-0000-0000-0000000095a2';
  assert b.alert_language = 'hi' and not b.quiet_hours_enabled and b.quiet_tz = 'Asia/Kolkata', 'defaults for user b';
end $$;

-- muted device / archived tracker still excluded
update public.devices set notifications_enabled = false where fcm_token = 'prefTokB_' || repeat('q', 30);
do $$ begin
  assert (select count(*) from public.get_flight_recipients('95959595-0000-0000-0000-000000000001')) = 1,
    'muted device excluded';
end $$;
update public.devices set notifications_enabled = true where fcm_token = 'prefTokB_' || repeat('q', 30);

-- ===== event types =====
insert into public.flight_events (flight_id, type, severity, payload, dedupe_key) values
  ('95959595-0000-0000-0000-000000000001', 'FOG_ALERT', 'critical',
   '{"airport":"DEL","visibility_m":300,"wx":"FG","side":"dep"}', '95-fog'),
  ('95959595-0000-0000-0000-000000000001', 'DIVERTED_DEST', 'critical',
   '{"origArr":"BOM","newArr":"AMD","estArr":null}', '95-divdest');
do $$ begin
  begin
    insert into public.flight_events (flight_id, type, severity, dedupe_key)
    values ('95959595-0000-0000-0000-000000000001', 'VOLCANO', 'critical', '95-bad');
    raise exception 'FAIL: unknown event type accepted';
  exception when check_violation then null; end;
  begin
    insert into public.flight_events (flight_id, type, severity, dedupe_key)
    values ('95959595-0000-0000-0000-000000000001', 'CHECKIN_OPEN', 'info', '95-checkin');
  end;
end $$;

select 'alert prefs tests: OK' as result;
