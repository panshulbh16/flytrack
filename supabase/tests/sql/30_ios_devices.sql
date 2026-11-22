-- iOS tokens use the same register_device() RPC. The five-argument call stays Android.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values ('00000000-0000-0000-0000-0000000000d1')
on conflict (id) do nothing;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000000d1"}';

-- The Android app omits p_platform. That must keep writing 'android'.
select public.register_device('androidTok_' || repeat('a', 24), '0.1.0', '16', 'en-IN', 'Asia/Kolkata');

-- An iPhone passes p_platform explicitly. A mute on that token must survive a refresh.
select public.register_device(
  'iosTok_' || repeat('b', 28),
  '0.1.0', '26.5', 'en-IN', 'Asia/Kolkata',
  'ios'
);
update public.devices set notifications_enabled = false where fcm_token like 'iosTok_%';
select public.register_device(
  'iosTok_' || repeat('b', 28),
  '0.1.1', '26.5', 'en-IN', 'Asia/Kolkata',
  'ios'
);
commit;

do $$ begin
  assert (select platform from public.devices where fcm_token like 'androidTok_%') = 'android',
    'five-arg register_device stays android';
  assert (select platform from public.devices where fcm_token like 'iosTok_%') = 'ios',
    'ios platform stored';
  assert (select app_version from public.devices where fcm_token like 'iosTok_%') = '0.1.1',
    'ios token refresh updates the version';
  assert (select notifications_enabled from public.devices where fcm_token like 'iosTok_%') = false,
    'mute kept when the same account refreshes an ios token';
end $$;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000000d1"}';
do $$ begin
  begin
    perform public.register_device(repeat('c', 24), null, null, null, null, 'web');
    raise exception 'FAIL: platform web was accepted';
  exception when sqlstate '22023' then null; end;
end $$;
commit;
