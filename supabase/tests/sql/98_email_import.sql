-- P2 wave 2 email import (docs/P2_WAVE2_CONTRACTS.md section 2): app_config, random_text_token(),
-- profiles.inbox_token + my_forwarding_address(), email_imports RLS, inbound_email_user(),
-- import_track_flight(), the 'email' source and the 'import' push kind.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000098a1'),  -- Pro user
  ('00000000-0000-0000-0000-0000000098a2'),  -- free user (trial used)
  ('00000000-0000-0000-0000-0000000098a3')   -- free user, never had a trial
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-0000000098a1';
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-0000000098a2';

insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr)
values ('98989898-0000-0000-0000-000000000001', '6E', '9801', current_date + 3, 'DEL', 'BOM', now() + interval '3 days', now() + interval '3 days 2 hours'),
       ('98989898-0000-0000-0000-000000000002', 'AI', '9802', current_date + 4, 'BOM', 'DEL', now() + interval '4 days', now() + interval '4 days 2 hours');

-- ===== random_text_token: length, alphabet, internal only =====
do $$ declare t text; seen text[] := '{}'; begin
  for i in 1..200 loop
    t := public.random_text_token(10, 'abcdefghjkmnpqrstuvwxyz23456789');
    assert t ~ '^[a-hjkmnp-z2-9]{10}$', 'inbox alphabet: ' || t;
    seen := seen || t;
  end loop;
  assert (select count(distinct x) from unnest(seen) x) = 200, 'tokens are distinct';
  t := public.random_text_token(32, 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789');
  assert t ~ '^[A-Za-z0-9]{32}$', t;
  -- every character of a 2-letter alphabet shows up (no stuck byte)
  t := public.random_text_token(64, 'ab');
  assert t ~ 'a' and t ~ 'b', t;
end $$;

-- ===== app_config: service role only, default domain seeded =====
do $$ begin
  assert (select value from public.app_config where key = 'inbound_email_domain') = 'in.heygate.app', 'default domain';
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000098a1"}';
do $$ begin
  begin
    perform 1 from public.app_config;
    raise exception 'FAIL: clients can read app_config';
  exception when insufficient_privilege then null; end;
  begin
    perform public.random_text_token(10, 'ab');
    raise exception 'FAIL: clients can call random_text_token';
  exception when insufficient_privilege then null; end;
  begin
    perform public.inbound_email_user('abcdefghjk');
    raise exception 'FAIL: clients can call inbound_email_user';
  exception when insufficient_privilege then null; end;
  begin
    perform public.import_track_flight(auth.uid(), '98989898-0000-0000-0000-000000000001', null);
    raise exception 'FAIL: clients can call import_track_flight';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== my_forwarding_address: created once, stable, unique, not editable =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000098a1"}';
do $$ declare a1 text; a2 text; begin
  a1 := public.my_forwarding_address();
  assert a1 ~ '^trips\+[a-hjkmnp-z2-9]{10}@in\.heygate\.app$', a1;
  a2 := public.my_forwarding_address();
  assert a1 = a2, 'same address every time';
  assert (select inbox_token from public.profiles where id = auth.uid()) = substring(a1 from 7 for 10), 'token stored';
  begin
    update public.profiles set inbox_token = 'aaaaaaaaaa' where id = auth.uid();
    raise exception 'FAIL: inbox_token is user-editable';
  exception when insufficient_privilege then null; end;
end $$;
commit;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000098a2"}';
do $$ begin
  assert public.my_forwarding_address() is not null, 'second user gets an address';
end $$;
commit;
do $$ begin
  assert (select count(distinct inbox_token) from public.profiles
           where id in ('00000000-0000-0000-0000-0000000098a1', '00000000-0000-0000-0000-0000000098a2')) = 2, 'per-user token';
end $$;
-- the domain follows app_config
update public.app_config set value = 'mail.example.test' where key = 'inbound_email_domain';
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000098a1"}';
do $$ begin
  assert public.my_forwarding_address() ~ '@mail\.example\.test$', 'configured domain';
end $$;
rollback;
update public.app_config set value = 'in.heygate.app' where key = 'inbound_email_domain';
begin;
set local role anon;
do $$ begin
  begin
    perform public.my_forwarding_address();
    raise exception 'FAIL: anon got an address';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
do $$ begin
  begin
    update public.profiles set inbox_token = 'ABCDEFGHJK' where id = '00000000-0000-0000-0000-0000000098a3';
    raise exception 'FAIL: uppercase inbox token accepted';
  exception when check_violation then null; end;
end $$;

-- ===== inbound_email_user: token → user + is_pro (case-insensitive) =====
do $$ declare t1 text; t2 text; r record; begin
  select inbox_token into t1 from public.profiles where id = '00000000-0000-0000-0000-0000000098a1';
  select inbox_token into t2 from public.profiles where id = '00000000-0000-0000-0000-0000000098a2';
  select * into r from public.inbound_email_user(upper(t1));
  assert r.user_id = '00000000-0000-0000-0000-0000000098a1' and r.is_pro and r.alert_language = 'en', 'pro user found';
  select * into r from public.inbound_email_user(t2);
  assert r.user_id = '00000000-0000-0000-0000-0000000098a2' and not r.is_pro, 'free user found, not pro';
  assert not exists (select 1 from public.inbound_email_user('zzzzzzzzzz')), 'unknown token → no row';
end $$;

-- ===== import_track_flight: added / exists / restored / limit; source 'email' =====
do $$ declare r text; begin
  r := public.import_track_flight('00000000-0000-0000-0000-0000000098a1', '98989898-0000-0000-0000-000000000001', 'abc123');
  assert r = 'added', r;
  assert (select source = 'email' and pnr = 'ABC123' from public.user_flights
           where user_id = '00000000-0000-0000-0000-0000000098a1' and flight_id = '98989898-0000-0000-0000-000000000001'),
         'tracked as email with the PNR';
  r := public.import_track_flight('00000000-0000-0000-0000-0000000098a1', '98989898-0000-0000-0000-000000000001', 'ZZZ999');
  assert r = 'exists', r;
  assert (select pnr from public.user_flights where user_id = '00000000-0000-0000-0000-0000000098a1'
           and flight_id = '98989898-0000-0000-0000-000000000001') = 'ABC123', 'an existing PNR is kept';
  -- a malformed PNR is dropped, not an error
  r := public.import_track_flight('00000000-0000-0000-0000-0000000098a1', '98989898-0000-0000-0000-000000000002', 'not a pnr!');
  assert r = 'added', r;
  assert (select pnr is null from public.user_flights where user_id = '00000000-0000-0000-0000-0000000098a1'
           and flight_id = '98989898-0000-0000-0000-000000000002'), 'bad PNR dropped';
  -- an archived flight comes back
  update public.user_flights set archived_at = now()
   where user_id = '00000000-0000-0000-0000-0000000098a1' and flight_id = '98989898-0000-0000-0000-000000000002';
  r := public.import_track_flight('00000000-0000-0000-0000-0000000098a1', '98989898-0000-0000-0000-000000000002', 'PQR789');
  assert r = 'added', 'restored: ' || r;
  assert (select archived_at is null and pnr = 'PQR789' from public.user_flights
           where user_id = '00000000-0000-0000-0000-0000000098a1' and flight_id = '98989898-0000-0000-0000-000000000002'),
         'unarchived, PNR filled in';
end $$;
-- the tracking limit is reported, not raised (free user a2 with 100 active flights: the P3 cap)
-- flight i has id 98989898-0000-0000-0000-00000001 + 4 digits; i = 0 ends in ...010000, i = 100 in ...010100
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr)
select ('98989898-0000-0000-0000-00000001' || lpad(i::text, 4, '0'))::uuid, 'SG', (8500 + i)::text, current_date + 5,
       'DEL', 'BOM', now() + interval '5 days', now() + interval '5 days 2 hours'
  from generate_series(0, 100) i;
insert into public.user_flights (user_id, flight_id)
select '00000000-0000-0000-0000-0000000098a2', ('98989898-0000-0000-0000-00000001' || lpad(i::text, 4, '0'))::uuid
  from generate_series(0, 99) i;
do $$ begin
  assert public.import_track_flight('00000000-0000-0000-0000-0000000098a2', '98989898-0000-0000-0000-000000010100', null) = 'limit',
         'limit reported';
  assert not exists (select 1 from public.user_flights where user_id = '00000000-0000-0000-0000-0000000098a2'
                      and flight_id = '98989898-0000-0000-0000-000000010100'), 'nothing inserted at the limit';
end $$;
-- a user already over the limit (e.g. after a downgrade) re-importing an active flight gets 'exists' and
-- the PNR, not 'limit': filling the PNR must not re-run the tracking-limit trigger
alter table public.user_flights disable trigger user_flights_limit;
insert into public.user_flights (user_id, flight_id)
values ('00000000-0000-0000-0000-0000000098a2', '98989898-0000-0000-0000-000000010100');
alter table public.user_flights enable trigger user_flights_limit;
do $$ declare r text; begin
  r := public.import_track_flight('00000000-0000-0000-0000-0000000098a2', '98989898-0000-0000-0000-000000010000', 'xyz789');
  assert r = 'exists', 'over the limit, already tracked: ' || r;
  assert (select pnr from public.user_flights where user_id = '00000000-0000-0000-0000-0000000098a2'
           and flight_id = '98989898-0000-0000-0000-000000010000') = 'XYZ789', 'PNR filled in over the limit';
end $$;

-- clients can insert source 'email' rows too (the check accepts every source, unknown ones fail)
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000098a3"}';
insert into public.user_flights (flight_id, source) values ('98989898-0000-0000-0000-000000000001', 'email');
do $$ begin
  begin
    insert into public.user_flights (flight_id, source) values ('98989898-0000-0000-0000-000000000002', 'fax');
    raise exception 'FAIL: unknown source accepted';
  exception when check_violation then null; end;
end $$;
rollback;
do $$ begin
  -- every earlier source still passes the re-created check
  perform 1;
  assert (select pg_get_constraintdef(oid) from pg_constraint where conname = 'user_flights_source_check')
         ~ 'manual.*pdf.*share.*screenshot.*gmail.*scan.*email', 'all sources listed';
end $$;

-- ===== push_log kind 'import' =====
insert into public.push_log (user_id, kind, status) values ('00000000-0000-0000-0000-0000000098a1', 'import', 'sent');
do $$ begin
  begin
    insert into public.push_log (user_id, kind, status) values ('00000000-0000-0000-0000-0000000098a1', 'spam', 'sent');
    raise exception 'FAIL: unknown push kind accepted';
  exception when check_violation then null; end;
end $$;

-- ===== email_imports: owners read their rows, nobody else; clients never write =====
insert into public.email_imports (user_id, from_domain, subject_hash, status, flights_added) values
  ('00000000-0000-0000-0000-0000000098a1', 'goindigo.in', repeat('a', 64), 'added', 2),
  ('00000000-0000-0000-0000-0000000098a2', 'makemytrip.com', repeat('b', 64), 'not_pro', 0);
do $$ begin
  begin
    insert into public.email_imports (user_id, status) values ('00000000-0000-0000-0000-0000000098a1', 'maybe');
    raise exception 'FAIL: unknown status accepted';
  exception when check_violation then null; end;
  begin
    insert into public.email_imports (user_id, status, subject_hash) values ('00000000-0000-0000-0000-0000000098a1', 'error', 'Your IndiGo booking');
    raise exception 'FAIL: a plain subject was accepted';
  exception when check_violation then null; end;
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000098a1"}';
do $$ begin
  assert (select count(*) from public.email_imports) = 1, 'own rows only';
  assert (select from_domain from public.email_imports) = 'goindigo.in', 'own row';
  begin
    insert into public.email_imports (user_id, status) values (auth.uid(), 'added');
    raise exception 'FAIL: clients can write email_imports';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
begin;
set local role anon;
do $$ begin
  begin
    perform 1 from public.email_imports;
    raise exception 'FAIL: anon can read email_imports';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

\echo '98_email_import: ok'
