-- Live Activity tokens: registered only for the caller's own device and a flight they track; RLS hides others' rows.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values ('00000000-0000-0000-0000-0000000000e1'), ('00000000-0000-0000-0000-0000000000e2')
on conflict (id) do nothing;
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status)
values ('33333333-3333-3333-3333-333333333333', 'AI', '302', current_date, 'DEL', 'BOM', 'scheduled');

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000000e1"}';
select public.register_device('laTok_' || repeat('a', 24), '0.1.0', '26.1', 'en-IN', 'Asia/Kolkata', 'ios');
do $$ begin
  begin
    perform public.register_live_activity('laTok_' || repeat('a', 24), '33333333-3333-3333-3333-333333333333', 'abcdef0123456789');
    raise exception 'FAIL: token accepted for an untracked flight';
  exception when sqlstate '22023' then null; end;
end $$;
insert into public.user_flights (flight_id) values ('33333333-3333-3333-3333-333333333333');
select public.register_live_activity('laTok_' || repeat('a', 24), '33333333-3333-3333-3333-333333333333', 'abcdef0123456789');
-- a new token for the same activity replaces the old one
select public.register_live_activity('laTok_' || repeat('a', 24), '33333333-3333-3333-3333-333333333333', 'ffffffffffffffff');
do $$ begin
  assert (select count(*) from public.live_activities) = 1, 'one row per device and flight';
  assert (select activity_token from public.live_activities) = 'ffffffffffffffff', 'token refreshed';
  begin
    perform public.register_live_activity('laTok_' || repeat('a', 24), '33333333-3333-3333-3333-333333333333', 'NOT-HEX!!');
    raise exception 'FAIL: non-hex token accepted';
  exception when sqlstate '22023' then null; end;
  begin
    insert into public.live_activities (user_id, device_id, flight_id, activity_token)
    select '00000000-0000-0000-0000-0000000000e1', id, '33333333-3333-3333-3333-333333333333', 'aaaaaaaaaaaaaaaa'
      from public.devices limit 1;
    raise exception 'FAIL: direct insert allowed';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- user e2 cannot use e1's device, and cannot see or delete e1's row
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000000e2"}';
insert into public.user_flights (flight_id) values ('33333333-3333-3333-3333-333333333333');
do $$ begin
  begin
    perform public.register_live_activity('laTok_' || repeat('a', 24), '33333333-3333-3333-3333-333333333333', 'abcdef0123456789');
    raise exception 'FAIL: token attached to another user''s device';
  exception when sqlstate '22023' then null; end;
  assert (select count(*) from public.live_activities) = 0, 'other users'' rows hidden';
end $$;
delete from public.live_activities;
commit;

do $$ begin
  assert (select count(*) from public.live_activities) = 1, 'other user could not delete the row';
end $$;

-- the owner can delete their own row (the app does this when the activity ends on the phone)
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000000e1"}';
delete from public.live_activities;
commit;

do $$ begin
  assert (select count(*) from public.live_activities) = 0, 'owner deleted their row';
end $$;
