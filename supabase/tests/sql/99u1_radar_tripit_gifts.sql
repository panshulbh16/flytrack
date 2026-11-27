-- P4 (docs/P4_CONTRACTS.md sections 1–3): radar_quota window, TripIt tables / status / tracking / targets /
-- source, and Gift Pro codes: one per transaction, refunds, my_gifts, redeem_gift errors, stacking and the
-- 10-failures-per-hour limit (returned, not raised, so the attempt row is kept).
\set QUIET on
set client_min_messages = warning;

-- P = purchaser, R = recipient, S = second recipient, L = lifetime Pro user, X = guesser
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000a4a1'),
  ('00000000-0000-0000-0000-00000000a4b1'),
  ('00000000-0000-0000-0000-00000000a4c1'),
  ('00000000-0000-0000-0000-00000000a4d1'),
  ('00000000-0000-0000-0000-00000000a4e1')
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = null where id = '00000000-0000-0000-0000-00000000a4d1';
update public.profiles set plan = 'pro', pro_until = now() + interval '10 days'
 where id = '00000000-0000-0000-0000-00000000a4c1';

-- ===== radar_quota =====
do $$ declare ok boolean; u uuid := '00000000-0000-0000-0000-00000000a4a1'; begin
  for i in 1..3 loop
    assert public.radar_take_quota(u, 3, 600), 'within limit ' || i;
  end loop;
  assert not public.radar_take_quota(u, 3, 600), '4th request over the limit';
  -- the window expires → counting starts over
  update public.radar_quota set window_start = now() - interval '601 seconds' where user_id = u;
  assert public.radar_take_quota(u, 3, 600), 'new window';
  assert (select count from public.radar_quota where user_id = u) = 1, 'count reset';
  assert not public.radar_take_quota(null, 3, 600), 'null user';
end $$;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4a1"}';
do $$ begin
  begin
    perform public.radar_take_quota(auth.uid(), 1000000, 1);
    raise exception 'FAIL: client called radar_take_quota';
  exception when insufficient_privilege then null; end;
  begin
    perform count(*) from public.radar_quota;
    raise exception 'FAIL: client read radar_quota';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== TripIt =====
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
values ('a4a4a4a4-0000-0000-0000-000000000001', '6E', '4401', current_date + 3, 'DEL', 'BOM', 'scheduled',
        now() + interval '3 days', now() + interval '3 days 2 hours'),
       ('a4a4a4a4-0000-0000-0000-000000000002', 'AI', '4402', current_date + 4, 'BOM', 'DEL', 'scheduled',
        now() + interval '4 days', now() + interval '4 days 2 hours');

-- not connected yet
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4c1"}';
do $$ declare s jsonb := public.tripit_status(); begin
  assert s = '{"connected": false, "last_sync_at": null, "last_error": null}'::jsonb, s::text;
end $$;
rollback;

-- C has a pending request token (not connected), then connects
insert into public.tripit_connections (user_id, request_token, request_secret, requested_at)
values ('00000000-0000-0000-0000-00000000a4c1', 'reqtok', 'v1:secret', now());
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4c1"}';
do $$ begin
  assert (public.tripit_status() ->> 'connected')::boolean = false, 'pending request is not connected';
  begin
    perform count(*) from public.tripit_connections;
    raise exception 'FAIL: client read tripit_connections';
  exception when insufficient_privilege then null; end;
  begin
    perform public.tripit_track_flight(auth.uid(), 'a4a4a4a4-0000-0000-0000-000000000001');
    raise exception 'FAIL: client called tripit_track_flight';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

update public.tripit_connections
   set request_token = null, request_secret = null, access_token = 'v1:at', access_secret = 'v1:as',
       connected_at = now(), last_sync_at = now() - interval '2 hours'
 where user_id = '00000000-0000-0000-0000-00000000a4c1';
-- a non-Pro connected user is not a sync target
insert into public.tripit_connections (user_id, access_token, access_secret, connected_at)
values ('00000000-0000-0000-0000-00000000a4a1', 'v1:at', 'v1:as', now());

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4c1"}';
do $$ declare s jsonb := public.tripit_status(); begin
  assert (s ->> 'connected')::boolean, s::text;
  assert (s ->> 'last_sync_at') is not null, s::text;
  assert not (s ? 'access_token') and not (s::text like '%v1:%'), 'no tokens in tripit_status';
end $$;
rollback;

do $$ declare r text; begin
  assert (select array_agg(t) from public.tripit_sync_targets(10) t)
         = array['00000000-0000-0000-0000-00000000a4c1'::uuid], 'only connected Pro users are due';
  r := public.tripit_track_flight('00000000-0000-0000-0000-00000000a4c1', 'a4a4a4a4-0000-0000-0000-000000000001');
  assert r = 'added', r;
  assert (select source from public.user_flights where user_id = '00000000-0000-0000-0000-00000000a4c1'
            and flight_id = 'a4a4a4a4-0000-0000-0000-000000000001') = 'tripit', 'source tripit';
  r := public.tripit_track_flight('00000000-0000-0000-0000-00000000a4c1', 'a4a4a4a4-0000-0000-0000-000000000001');
  assert r = 'exists', 'idempotent: ' || r;
  -- a flight the user archived is not brought back
  insert into public.user_flights (user_id, flight_id, source, archived_at)
  values ('00000000-0000-0000-0000-00000000a4c1', 'a4a4a4a4-0000-0000-0000-000000000002', 'manual', now());
  r := public.tripit_track_flight('00000000-0000-0000-0000-00000000a4c1', 'a4a4a4a4-0000-0000-0000-000000000002');
  assert r = 'exists', 'archived stays archived: ' || r;
  assert (select archived_at from public.user_flights where user_id = '00000000-0000-0000-0000-00000000a4c1'
            and flight_id = 'a4a4a4a4-0000-0000-0000-000000000002') is not null;

  insert into public.tripit_segments (user_id, segment_key, flight_id, outcome)
  values ('00000000-0000-0000-0000-00000000a4c1', '6E4401:2026-12-01:DEL', 'a4a4a4a4-0000-0000-0000-000000000001', 'added');
  begin
    insert into public.tripit_segments (user_id, segment_key, outcome)
    values ('00000000-0000-0000-0000-00000000a4c1', 'bad key', 'added');
    raise exception 'FAIL: bad segment key accepted';
  exception when check_violation then null; end;
  -- recently synced → not due
  update public.tripit_connections set last_sync_at = now() where user_id = '00000000-0000-0000-0000-00000000a4c1';
  assert not exists (select 1 from public.tripit_sync_targets(10)), 'synced 0 min ago is not due';
  -- disconnect removes the segments too
  delete from public.tripit_connections where user_id = '00000000-0000-0000-0000-00000000a4c1';
  assert not exists (select 1 from public.tripit_segments where user_id = '00000000-0000-0000-0000-00000000a4c1');
  -- the source check still accepts the older values
  update public.user_flights set source = 'calendar' where user_id = '00000000-0000-0000-0000-00000000a4c1'
     and flight_id = 'a4a4a4a4-0000-0000-0000-000000000002';
end $$;
-- invoke_tripit_sync is not callable by clients
begin;
set local role authenticated;
do $$ begin
  begin
    perform public.invoke_tripit_sync();
    raise exception 'FAIL: client invoked tripit sync';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== Gift codes: webhook side =====
create temp table t_gift (k text primary key, code text);
grant all on t_gift to authenticated;
do $$ declare a jsonb; b jsonb; c jsonb; begin
  a := public.create_gift_code('00000000-0000-0000-0000-00000000a4a1', 'heygate_gift_year', 365, 'GPA.1111');
  assert (a ->> 'created')::boolean, a::text;
  assert a ->> 'code' ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}$', a::text;
  b := public.create_gift_code('00000000-0000-0000-0000-00000000a4a1', 'heygate_gift_year', 365, 'GPA.1111');
  assert b ->> 'code' = a ->> 'code' and not (b ->> 'created')::boolean, 'one code per transaction';
  assert (select count(*) from public.gift_codes where store_transaction_id = 'GPA.1111') = 1;
  c := public.create_gift_code('00000000-0000-0000-0000-00000000a4a1', 'heygate_gift_trip', 7, 'GPA.2222');
  assert c ->> 'code' <> a ->> 'code';
  assert public.create_gift_code('00000000-0000-0000-0000-0000000000ff', 'heygate_gift_trip', 7, 'GPA.3') is null,
         'no profile → null';
  -- the purchaser did not get Pro from buying
  assert (select plan from public.profiles where id = '00000000-0000-0000-0000-00000000a4a1') = 'free';
  insert into t_gift values ('year', a ->> 'code'), ('trip', c ->> 'code');
  -- a refunded, unused code is voided; refunds are idempotent; unknown transactions are reported
  perform public.create_gift_code('00000000-0000-0000-0000-00000000a4a1', 'heygate_gift_trip', 7, 'GPA.4444');
  insert into t_gift select 'refunded', code from public.gift_codes where store_transaction_id = 'GPA.4444';
  assert public.void_gift_code('GPA.4444') = 'voided';
  assert public.void_gift_code('GPA.4444') = 'already_voided';
  assert public.void_gift_code('nope') = 'not_found';
end $$;

-- ===== my_gifts / redeem_gift =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4a1"}';
do $$ declare g jsonb := public.my_gifts(); r jsonb; begin
  assert jsonb_array_length(g) = 3, g::text;
  assert (select count(*) from jsonb_array_elements(g) e where e ->> 'status' = 'unused') = 2, g::text;
  assert (select e ->> 'status' from jsonb_array_elements(g) e
           where e ->> 'code' = (select code from t_gift where k = 'refunded')) = 'voided', g::text;
  assert not (g::text like '%GPA.%'), 'no store transaction ids';
  r := public.redeem_gift((select code from t_gift where k = 'year'));
  assert r = '{"error": "own_gift"}'::jsonb, r::text;
  begin
    perform count(*) from public.gift_codes;
    raise exception 'FAIL: client read gift_codes';
  exception when insufficient_privilege then null; end;
  begin
    perform public.create_gift_code(auth.uid(), 'heygate_gift_year', 365, 'fake');
    raise exception 'FAIL: client created a gift code';
  exception when insufficient_privilege then null; end;
end $$;
commit;
do $$ begin
  assert (select count(*) from public.gift_attempts where user_id = '00000000-0000-0000-0000-00000000a4a1') = 1,
         'own_gift counts as a failed attempt (returned, not raised)';
end $$;

-- R (free) redeems the year gift, typed in lower case without dashes
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4b1"}';
do $$ declare r jsonb; c text := (select code from t_gift where k = 'year'); begin
  r := public.redeem_gift(lower(replace(c, '-', ' ')));
  assert r ? 'pro_until', r::text;
  assert (r ->> 'pro_until')::timestamptz between now() + interval '364 days 23 hours' and now() + interval '365 days 1 hour',
         r::text;
  assert public.current_user_is_pro(), 'recipient is Pro';
  r := public.redeem_gift(c);
  assert r = '{"error": "already_redeemed"}'::jsonb, r::text;
  r := public.redeem_gift((select code from t_gift where k = 'refunded'));
  assert r = '{"error": "voided"}'::jsonb, r::text;
  r := public.redeem_gift('ZZZZ-ZZZZ-ZZZZ');
  assert r = '{"error": "invalid_code"}'::jsonb, r::text;
  r := public.redeem_gift(null);
  assert r = '{"error": "invalid_code"}'::jsonb, r::text;
  assert jsonb_array_length(public.my_gifts()) = 0, 'recipient bought nothing';
end $$;
commit;

do $$ begin
  assert (select status from (select case when voided_at is not null then 'voided' when redeemed_at is not null
            then 'redeemed' else 'unused' end as status from public.gift_codes
            where code = (select code from t_gift where k = 'year')) s) = 'redeemed';
  assert (select redeemed_by from public.gift_codes where code = (select code from t_gift where k = 'year'))
         = '00000000-0000-0000-0000-00000000a4b1';
  assert jsonb_array_length(public.redeemed_gifts('00000000-0000-0000-0000-00000000a4b1')) = 1;
  assert (public.redeemed_gifts('00000000-0000-0000-0000-00000000a4b1') -> 0 ->> 'days')::int = 365;
  assert (select grants_until = starts_at + interval '365 days'
            and grants_until = (select pro_until from public.profiles where id = '00000000-0000-0000-0000-00000000a4b1')
            from public.gift_codes where store_transaction_id = 'GPA.1111'),
         'the gift window is recorded with the redemption';
  assert jsonb_array_length(public.redeemed_gifts('00000000-0000-0000-0000-00000000a4a1')) = 0;
  -- refund after redemption: nothing changes
  assert public.void_gift_code('GPA.1111') = 'redeemed';
  assert (select voided_at from public.gift_codes where store_transaction_id = 'GPA.1111') is null;
  assert (select count(*) from public.gift_attempts where user_id = '00000000-0000-0000-0000-00000000a4b1') = 4;
end $$;

-- S already has Pro for 10 days: the 7-day gift stacks on top
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4c1"}';
do $$ declare r jsonb; begin
  r := public.redeem_gift((select code from t_gift where k = 'trip'));
  assert (r ->> 'pro_until')::timestamptz between now() + interval '16 days 23 hours' and now() + interval '17 days 1 hour',
         'stacks: ' || r::text;
end $$;
commit;
do $$ begin
  assert (select starts_at between now() + interval '9 days 23 hours' and now() + interval '10 days 1 hour'
            from public.gift_codes where store_transaction_id = 'GPA.2222'),
         'starts_at = the Pro end the gift stacked on';
end $$;

-- L has lifetime Pro: the code is not used up
do $$ begin
  perform public.create_gift_code('00000000-0000-0000-0000-00000000a4a1', 'heygate_gift_trip', 7, 'GPA.5555');
  insert into t_gift select 'forL', code from public.gift_codes where store_transaction_id = 'GPA.5555';
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4d1"}';
do $$ declare r jsonb; begin
  r := public.redeem_gift((select code from t_gift where k = 'forL'));
  assert r = '{"error": "lifetime_pro"}'::jsonb, r::text;
end $$;
commit;
do $$ begin
  assert (select redeemed_at from public.gift_codes where store_transaction_id = 'GPA.5555') is null, 'still unused';
  assert (select pro_until from public.profiles where id = '00000000-0000-0000-0000-00000000a4d1') is null, 'still lifetime';
end $$;

-- X guesses: 10 failures, then rate_limited (even for a valid code); the window is one hour
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4e1"}';
do $$ declare r jsonb; begin
  for i in 1..10 loop
    r := public.redeem_gift('AAAA-AAAA-AAA' || substr('23456789AB', i, 1));
    assert r = '{"error": "invalid_code"}'::jsonb, i || ': ' || r::text;
  end loop;
  r := public.redeem_gift((select code from t_gift where k = 'forL'));
  assert r = '{"error": "rate_limited"}'::jsonb, r::text;
end $$;
commit;
do $$ begin
  assert (select count(*) from public.gift_attempts where user_id = '00000000-0000-0000-0000-00000000a4e1') = 10,
         'rate_limited is not itself recorded';
  update public.gift_attempts set at = now() - interval '61 minutes'
   where user_id = '00000000-0000-0000-0000-00000000a4e1';
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000a4e1"}';
do $$ declare r jsonb; begin
  r := public.redeem_gift((select code from t_gift where k = 'forL'));
  assert r ? 'pro_until', 'after an hour the user can redeem again: ' || r::text;
end $$;
commit;

-- anonymous callers (no JWT) cannot redeem
begin;
set local role anon;
do $$ begin
  begin
    perform public.redeem_gift('AAAA-AAAA-AAAA');
    raise exception 'FAIL: anon redeemed';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- clean up (other SQL test files count rows)
delete from public.gift_codes where purchaser_id = '00000000-0000-0000-0000-00000000a4a1';
delete from public.tripit_connections where user_id::text like '00000000-0000-0000-0000-00000000a4%';
delete from public.user_flights where flight_id::text like 'a4a4a4a4-%';
delete from public.flights where id::text like 'a4a4a4a4-%';
\echo 'radar/tripit/gifts: ok'
