-- P4 section 2 (migration 35): TripIt login CSRF fix — tripit_claim_callback() is single use, clears the
-- pending request token whatever the outcome, refuses expired codes; tripit_claim_manual_sync() cooldown;
-- neither is callable by clients.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000c5a1'),
  ('00000000-0000-0000-0000-00000000c5b1')
on conflict (id) do nothing;

-- A = pending request with a fresh code; B = pending request with an expired code
insert into public.tripit_connections (user_id, request_token, request_secret, requested_at,
                                       callback_code_hash, callback_code_expires_at)
values ('00000000-0000-0000-0000-00000000c5a1', 'rt-a', 'v1:sa', now(), repeat('a', 64), now() + interval '10 minutes'),
       ('00000000-0000-0000-0000-00000000c5b1', 'rt-b', 'v1:sb', now(), repeat('b', 64), now() - interval '1 second');

do $$ declare n int; r record; begin
  -- schema guards
  begin
    update public.tripit_connections set callback_code_hash = 'not-hex' where user_id = '00000000-0000-0000-0000-00000000c5a1';
    raise exception 'FAIL: bad hash accepted';
  exception when check_violation then null; end;
  begin
    update public.tripit_connections set callback_code_expires_at = null where user_id = '00000000-0000-0000-0000-00000000c5a1';
    raise exception 'FAIL: hash without expiry accepted';
  exception when check_violation then null; end;
  begin
    update public.tripit_connections set callback_code_hash = repeat('a', 64) where user_id = '00000000-0000-0000-0000-00000000c5b1';
    raise exception 'FAIL: duplicate hash accepted';
  exception when unique_violation then null; end;

  -- unknown / malformed
  assert not exists (select 1 from public.tripit_claim_callback(repeat('c', 64))), 'unknown code';
  assert not exists (select 1 from public.tripit_claim_callback('zz')), 'malformed code';
  assert not exists (select 1 from public.tripit_claim_callback(null)), 'null code';

  -- fresh code: returns the pending request once, and clears it in the same statement
  select * into r from public.tripit_claim_callback(repeat('a', 64));
  assert r.user_id = '00000000-0000-0000-0000-00000000c5a1' and r.request_token = 'rt-a' and r.request_secret = 'v1:sa',
    'claimed: ' || coalesce(r::text, 'null');
  assert (select request_token is null and request_secret is null and requested_at is null
                 and callback_code_hash is null and callback_code_expires_at is null
            from public.tripit_connections where user_id = '00000000-0000-0000-0000-00000000c5a1'), 'pending cleared';
  assert not exists (select 1 from public.tripit_claim_callback(repeat('a', 64))), 'reuse rejected';

  -- expired code: nothing returned, but the pending link is destroyed
  assert not exists (select 1 from public.tripit_claim_callback(repeat('b', 64))), 'expired rejected';
  assert (select request_token is null and callback_code_hash is null
            from public.tripit_connections where user_id = '00000000-0000-0000-0000-00000000c5b1'), 'expired link destroyed';

  -- manual sync cooldown
  assert public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5a1', 60) = 0, 'not connected: 0 (sync answers not_connected)';
  update public.tripit_connections set access_token = 'v1:at', access_secret = 'v1:as', connected_at = now()
   where user_id = '00000000-0000-0000-0000-00000000c5a1';
  assert public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5a1', 60) = 0, 'first manual sync';
  assert (select last_manual_sync_at from public.tripit_connections where user_id = '00000000-0000-0000-0000-00000000c5a1') is not null;
  n := public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5a1', 60);
  assert n between 59 and 60, 'cooldown: ' || n;
  update public.tripit_connections set last_manual_sync_at = now() - interval '45 seconds'
   where user_id = '00000000-0000-0000-0000-00000000c5a1';
  n := public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5a1', 60);
  assert n between 14 and 15, 'cooldown left: ' || n;
  update public.tripit_connections set last_manual_sync_at = now() - interval '61 seconds'
   where user_id = '00000000-0000-0000-0000-00000000c5a1';
  assert public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5a1', 60) = 0, 'after the window';
  assert public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5b1', 60) = 0, 'other user unaffected';
  assert (select last_manual_sync_at from public.tripit_connections where user_id = '00000000-0000-0000-0000-00000000c5b1') is null,
    'not connected: nothing recorded';
end $$;

-- clients can call neither function
begin;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000c5a1","role":"authenticated"}', true);
do $$ begin
  begin
    perform public.tripit_claim_callback(repeat('a', 64));
    raise exception 'FAIL: client called tripit_claim_callback';
  exception when insufficient_privilege then null; end;
  begin
    perform public.tripit_claim_manual_sync('00000000-0000-0000-0000-00000000c5a1', 60);
    raise exception 'FAIL: client called tripit_claim_manual_sync';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

delete from public.tripit_connections where user_id in ('00000000-0000-0000-0000-00000000c5a1', '00000000-0000-0000-0000-00000000c5b1');
