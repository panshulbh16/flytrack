-- Share links and check-in reminders (P1 contract sections 2, 3 and 5): flight_shares RLS + RPCs,
-- get_shared_flight() public fields, checkin_rules, the flights check-in window trigger,
-- due_checkin_flights(), poll_work_due(), the 'scan' source and the new event types.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000008a1'),  -- sharer
  ('00000000-0000-0000-0000-0000000008a2'),  -- someone else
  ('00000000-0000-0000-0000-0000000008a3')   -- scan user
on conflict (id) do nothing;
update public.profiles set trial_used = true
 where id in ('00000000-0000-0000-0000-0000000008a1', '00000000-0000-0000-0000-0000000008a2', '00000000-0000-0000-0000-0000000008a3');

-- S1 domestic 6E DEL→BOM in 10h; S2 international 6E DEL→DXB in 20h; S3 carrier without a rule;
-- S4 tracked by nobody.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr,
                            dep_gate, baggage_belt, aircraft_reg, aircraft_model, alert_state, prediction, provider_ref,
                            live_lat, live_lon, live_alt_ft, live_speed_kts, live_updated_at)
values ('88888888-0000-0000-0000-0000000000a1', '6E', '8001', current_date, 'DEL', 'BOM', 'scheduled',
        now() + interval '10 hours', now() + interval '12 hours', '42B', '5', 'VT-SECRET', 'A320neo',
        '{"depGate":"41"}', '{"lateMin": 30}', 'secret-ref', 22.1, 75.2, 36000, 450, now());
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_dep, sched_arr)
values ('88888888-0000-0000-0000-0000000000a2', '6E', '8002', current_date + 1, 'DEL', 'DXB', now() + interval '20 hours', now() + interval '24 hours'),
       ('88888888-0000-0000-0000-0000000000a3', 'UK', '8003', current_date + 1, 'DEL', 'BOM', now() + interval '20 hours', now() + interval '22 hours'),
       ('88888888-0000-0000-0000-0000000000a4', '6E', '8004', current_date, 'BOM', 'DEL', now() + interval '10 hours', now() + interval '12 hours');

insert into public.user_flights (user_id, flight_id, pnr, seat) values
  ('00000000-0000-0000-0000-0000000008a1', '88888888-0000-0000-0000-0000000000a1', 'PNR123', '12A'),
  ('00000000-0000-0000-0000-0000000008a1', '88888888-0000-0000-0000-0000000000a2', null, null),
  ('00000000-0000-0000-0000-0000000008a1', '88888888-0000-0000-0000-0000000000a3', null, null);

-- ===== source 'scan' =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000008a3"}';
insert into public.user_flights (flight_id, source) values ('88888888-0000-0000-0000-0000000000a4', 'scan');
do $$ begin
  begin
    update public.user_flights set note = 'x' where flight_id = '88888888-0000-0000-0000-0000000000a4';
    insert into public.user_flights (flight_id, source) values ('88888888-0000-0000-0000-0000000000a1', 'telepathy');
    raise exception 'FAIL: unknown source accepted';
  exception when check_violation then null; end;
end $$;
rollback;

-- ===== create_flight_share / revoke_flight_share =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000008a1"}';
do $$ declare t1 text; t2 text; t3 text; begin
  t1 := public.create_flight_share('88888888-0000-0000-0000-0000000000a1');
  assert t1 ~ '^[A-Za-z0-9_-]{22,}$', 'token is URL-safe and >= 22 chars: ' || t1;
  assert char_length(t1) = 43, 'token is 32 random bytes as base64url';
  t2 := public.create_flight_share('88888888-0000-0000-0000-0000000000a1');
  assert t1 = t2, 'same active token returned';
  assert (select count(*) from public.flight_shares) = 1, 'owner sees their one share';
  assert (select expires_at from public.flight_shares where token = t1)
         between now() + interval '2 days 11 hours' and now() + interval '2 days 13 hours', 'expires arrival + 2 days';
  -- tokens differ between flights
  t3 := public.create_flight_share('88888888-0000-0000-0000-0000000000a2');
  assert t3 <> t1, 'distinct tokens';
  -- not tracking → 42501
  begin
    perform public.create_flight_share('88888888-0000-0000-0000-0000000000a4');
    raise exception 'FAIL: shared an untracked flight';
  exception when insufficient_privilege then
    assert sqlerrm = 'not_tracking', sqlerrm;
  end;
  -- unknown flight → P0002
  begin
    perform public.create_flight_share('88888888-0000-0000-0000-00000000ffff');
    raise exception 'FAIL: shared an unknown flight';
  exception when no_data_found then null; end;
  -- revoke → a new token next time
  perform public.revoke_flight_share(t1);
  assert (select revoked_at is not null from public.flight_shares where token = t1), 'revoked';
  t2 := public.create_flight_share('88888888-0000-0000-0000-0000000000a1');
  assert t2 <> t1, 'fresh token after revoke';
  -- clients cannot write shares or read the public lookup themselves
  begin
    insert into public.flight_shares (user_id, flight_id, expires_at)
    values (auth.uid(), '88888888-0000-0000-0000-0000000000a1', now() + interval '1 day');
    raise exception 'FAIL: direct insert into flight_shares';
  exception when insufficient_privilege then null; end;
  begin
    update public.flight_shares set revoked_at = null;
    raise exception 'FAIL: direct update of flight_shares';
  exception when insufficient_privilege then null; end;
  begin
    perform public.get_shared_flight(t2);
    raise exception 'FAIL: authenticated called get_shared_flight';
  exception when insufficient_privilege then null; end;
  begin
    perform public.due_checkin_flights(1);
    raise exception 'FAIL: authenticated called due_checkin_flights';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- another user sees none of it and cannot revoke it
create temp table share_tok as
  select token from public.flight_shares
   where flight_id = '88888888-0000-0000-0000-0000000000a1' and revoked_at is null;
grant select on share_tok to authenticated;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000008a2"}';
do $$ begin
  assert (select count(*) from public.flight_shares) = 0, 'RLS: shares are owner-only';
  perform public.revoke_flight_share((select token from share_tok));
end $$;
commit;
do $$ begin
  assert (select revoked_at is null from public.flight_shares where token = (select token from share_tok)),
    'non-owner revoke is a no-op';
end $$;

-- anon: no table, no RPCs
begin;
set local role anon;
do $$ begin
  begin perform 1 from public.flight_shares; raise exception 'FAIL: anon read flight_shares';
  exception when insufficient_privilege then null; end;
  begin perform public.create_flight_share('88888888-0000-0000-0000-0000000000a1'); raise exception 'FAIL: anon created a share';
  exception when insufficient_privilege then null; end;
  begin perform 1 from public.checkin_rules; raise exception 'FAIL: anon read checkin_rules';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== get_shared_flight: public fields only, 404 cases =====
do $$ declare t text; j jsonb; txt text; begin
  select token into t from public.flight_shares
   where flight_id = '88888888-0000-0000-0000-0000000000a1' and revoked_at is null;
  j := public.get_shared_flight(t);
  assert j is not null, 'active token resolves';
  assert (select array_agg(k order by k) from jsonb_object_keys(j) k) = array[
    'act_arr','act_dep','aircraft','airline','arr','carrier','date','dep','est_arr','est_dep','live','number',
    'sched_arr','sched_dep','status','updated_at'], 'top-level keys: ' || j::text;
  assert j #>> '{dep,iata}' = 'DEL' and j #>> '{dep,gate}' = '42B' and j #>> '{dep,tz}' = 'Asia/Kolkata', 'dep block';
  assert j #>> '{arr,belt}' = '5' and j #>> '{arr,city}' is not null, 'arr block';
  assert (j #>> '{dep,lat}')::float > 28, 'airport coordinates for the globe';
  assert j #>> '{aircraft,model}' = 'A320neo' and (j -> 'aircraft') - 'model' = '{}'::jsonb, 'aircraft model only';
  assert (j #>> '{live,alt_ft}')::int = 36000, 'live block';
  txt := j::text;
  assert txt not like '%PNR123%' and txt not like '%12A%' and txt not like '%VT-SECRET%' and txt not like '%secret-ref%'
     and txt not like '%lateMin%' and txt not like '%depGate%' and txt not like '%00000000-0000-0000-0000-0000000008a1%'
     and txt not like '%88888888-%', 'no PII or internals: ' || txt;

  assert public.get_shared_flight('nope') is null, 'unknown token';
  assert public.get_shared_flight(null) is null, 'null token';
  -- revoked tokens do not resolve
  assert public.get_shared_flight((select token from public.flight_shares where revoked_at is not null limit 1)) is null, 'revoked';
  -- expired: flight arrived more than 2 days ago and expires_at passed
  update public.flight_shares set expires_at = now() - interval '1 minute' where token = t;
  update public.flights set sched_arr = now() - interval '3 days' where id = '88888888-0000-0000-0000-0000000000a1';
  assert public.get_shared_flight(t) is null, 'expired';
  -- a much later arrival keeps the link alive until arrival + 2 days
  update public.flights set est_arr = now() + interval '1 hour' where id = '88888888-0000-0000-0000-0000000000a1';
  assert public.get_shared_flight(t) is not null, 'late flight still shared';
  update public.flights set sched_arr = now() + interval '12 hours', est_arr = null where id = '88888888-0000-0000-0000-0000000000a1';
  update public.flight_shares set expires_at = now() + interval '2 days' where token = t;
end $$;

-- untracking ends the share
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000008a1"}';
delete from public.user_flights where flight_id = '88888888-0000-0000-0000-0000000000a2';
commit;
do $$ begin
  assert (select bool_and(revoked_at is not null) from public.flight_shares
           where flight_id = '88888888-0000-0000-0000-0000000000a2'), 'untrack revokes the share';
end $$;
insert into public.user_flights (user_id, flight_id) values
  ('00000000-0000-0000-0000-0000000008a1', '88888888-0000-0000-0000-0000000000a2');

-- ===== checkin_rules: readable by authenticated, exact columns =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000008a2"}';
do $$ declare j jsonb; begin
  assert (select count(*) from public.checkin_rules) = 5, 'seeded 5 carriers';
  select to_jsonb(r) into j from public.checkin_rules r where carrier_iata = '6E';
  assert (select array_agg(k order by k) from jsonb_object_keys(j) k) = array[
    'carrier_iata','checkin_url','closes_minutes_before','closes_minutes_before_intl','notes','opens_hours_before',
    'updated_at','verified'], 'columns: ' || j::text;
  assert (j ->> 'opens_hours_before')::int = 48 and (j ->> 'closes_minutes_before')::int = 60
     and (j ->> 'closes_minutes_before_intl')::int = 75 and (j ->> 'verified')::boolean
     and j ->> 'checkin_url' = 'https://www.goindigo.in/web-check-in.html', '6E rule';
  assert not (select verified from public.checkin_rules where carrier_iata = 'SG'), 'SG unverified';
  begin
    update public.checkin_rules set verified = true where carrier_iata = 'SG';
    raise exception 'FAIL: authenticated edited checkin_rules';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== check-in window trigger =====
do $$ declare f record; begin
  select * into f from public.flights where id = '88888888-0000-0000-0000-0000000000a1';
  assert f.checkin_opens_at = f.sched_dep - interval '48 hours', 'opens 48h before';
  assert f.checkin_closes_at = f.sched_dep - interval '60 minutes', 'domestic close 60 min';
  select * into f from public.flights where id = '88888888-0000-0000-0000-0000000000a2';
  assert f.checkin_closes_at = f.sched_dep - interval '75 minutes', 'international close 75 min (IN→AE)';
  select * into f from public.flights where id = '88888888-0000-0000-0000-0000000000a3';
  assert f.checkin_opens_at is null and f.checkin_closes_at is null, 'no rule → no window';
  -- est_dep moves the window
  update public.flights set est_dep = sched_dep + interval '2 hours' where id = '88888888-0000-0000-0000-0000000000a1';
  select * into f from public.flights where id = '88888888-0000-0000-0000-0000000000a1';
  assert f.checkin_closes_at = f.est_dep - interval '60 minutes', 'est_dep wins';
  update public.flights set est_dep = null where id = '88888888-0000-0000-0000-0000000000a1';
  -- a rule edit re-times upcoming flights
  update public.checkin_rules set closes_minutes_before = 45 where carrier_iata = '6E';
  select * into f from public.flights where id = '88888888-0000-0000-0000-0000000000a1';
  assert f.checkin_closes_at = f.sched_dep - interval '45 minutes', 'rule change applied';
  update public.checkin_rules set closes_minutes_before = 60 where carrier_iata = '6E';
  -- a new rule picks up a carrier
  insert into public.checkin_rules (carrier_iata, opens_hours_before, closes_minutes_before, closes_minutes_before_intl)
  values ('UK', 24, 60, 60);
  assert (select checkin_opens_at is not null from public.flights where id = '88888888-0000-0000-0000-0000000000a3'), 'new rule';
  delete from public.checkin_rules where carrier_iata = 'UK';
  assert (select checkin_opens_at is null from public.flights where id = '88888888-0000-0000-0000-0000000000a3'), 'rule removed';
  begin
    insert into public.checkin_rules (carrier_iata, opens_hours_before, closes_minutes_before, closes_minutes_before_intl, checkin_url)
    values ('ZZ', 24, 60, 60, 'javascript:alert(1)');
    raise exception 'FAIL: non-https check-in URL accepted';
  exception when check_violation then null; end;
end $$;

-- ===== due_checkin_flights + poll_work_due =====
do $$ declare ids uuid[]; begin
  -- park every other flight so only this file's rows can be due
  update public.flights set next_poll_at = null where id::text not like '88888888-%';
  update public.flights set next_poll_at = now() + interval '1 hour' where id::text like '88888888-%';
  update public.flights set checkin_open_notified = true, checkin_closing_notified = true where id::text not like '88888888-%';

  select array_agg(id order by id) into ids from public.due_checkin_flights(100);
  -- S1 (10h out, open), S2 (20h out, open). S3 no rule; S4 tracked only by the scan user (rolled back) → 0 trackers.
  assert ids = array['88888888-0000-0000-0000-0000000000a1', '88888888-0000-0000-0000-0000000000a2']::uuid[],
    'due: ' || coalesce(ids::text, 'none');
  assert public.poll_work_due(), 'a due reminder wakes the poller';

  update public.flights set checkin_open_notified = true where id::text like '88888888-%';
  assert not exists (select 1 from public.due_checkin_flights(100)), 'open sent, closing not yet due';
  assert not public.poll_work_due(), 'nothing due → poller stays asleep';

  -- last 30 minutes before the close
  update public.flights set sched_dep = now() + interval '80 minutes' where id = '88888888-0000-0000-0000-0000000000a1';
  assert (select array_agg(id) from public.due_checkin_flights(100)) = array['88888888-0000-0000-0000-0000000000a1']::uuid[],
    'closing reminder due';
  update public.flights set checkin_closing_notified = true where id = '88888888-0000-0000-0000-0000000000a1';
  assert not exists (select 1 from public.due_checkin_flights(100)), 'closing sent';

  -- after the close, departed or cancelled: never due
  update public.flights set checkin_closing_notified = false, sched_dep = now() + interval '30 minutes'
   where id = '88888888-0000-0000-0000-0000000000a1';
  assert not exists (select 1 from public.due_checkin_flights(100)), 'window closed';
  update public.flights set sched_dep = now() + interval '80 minutes', status = 'cancelled'
   where id = '88888888-0000-0000-0000-0000000000a1';
  assert not exists (select 1 from public.due_checkin_flights(100)), 'cancelled';
  update public.flights set status = 'scheduled', act_dep = now() where id = '88888888-0000-0000-0000-0000000000a1';
  assert not exists (select 1 from public.due_checkin_flights(100)), 'departed';
  update public.flights set act_dep = null where id = '88888888-0000-0000-0000-0000000000a1';
  assert exists (select 1 from public.due_checkin_flights(100)), 'back to due';
  update public.flights set checkin_closing_notified = true where id = '88888888-0000-0000-0000-0000000000a1';

  -- a due poll still wakes it
  update public.flights set next_poll_at = now() - interval '1 minute' where id = '88888888-0000-0000-0000-0000000000a2';
  assert public.poll_work_due(), 'due poll';
  update public.flights set next_poll_at = now() + interval '1 hour' where id = '88888888-0000-0000-0000-0000000000a2';
end $$;

-- ===== new event types =====
do $$ begin
  insert into public.flight_events (flight_id, type, severity, payload, dedupe_key) values
    ('88888888-0000-0000-0000-0000000000a1', 'CHECKIN_OPEN', 'info',
     '{"checkin_url":"https://www.goindigo.in/web-check-in.html"}', '88888888-a1:checkin_open'),
    ('88888888-0000-0000-0000-0000000000a1', 'CHECKIN_CLOSING', 'critical', '{}', '88888888-a1:checkin_closing');
  begin
    insert into public.flight_events (flight_id, type, severity, dedupe_key)
    values ('88888888-0000-0000-0000-0000000000a1', 'CHECKIN_MAYBE', 'info', 'x-bad');
    raise exception 'FAIL: unknown event type accepted';
  exception when check_violation then null; end;
end $$;

select 'share + check-in tests: OK' as result;
