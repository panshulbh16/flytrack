-- P3 B10 / B12 (docs/P3_CONTRACTS.md): profiles.share_passport_with_friends + crew_mode (defaults, column
-- grants, RLS), friend_passport (friends who share only, reduced shape, the caller's year rules), my_passport
-- unchanged + block_minutes, and get_flight_recipients.crew_mode.
\set QUIET on
set client_min_messages = warning;

-- A = Asha (free), B = Bharat (Asha's friend, pro), C = Chitra (Asha's friend, sharing off), D = stranger
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000099a3a1'),
  ('00000000-0000-0000-0000-00000099a3b1'),
  ('00000000-0000-0000-0000-00000099a3c1'),
  ('00000000-0000-0000-0000-00000099a3d1')
on conflict (id) do nothing;
update public.profiles set trial_used = true where id::text like '00000000-0000-0000-0000-00000099a3%';
update public.profiles set display_name = 'Asha' where id = '00000000-0000-0000-0000-00000099a3a1';
update public.profiles set display_name = 'Bharat', plan = 'pro', pro_until = now() + interval '30 days'
 where id = '00000000-0000-0000-0000-00000099a3b1';
insert into public.friendships (user_low, user_high) values
  ('00000000-0000-0000-0000-00000099a3a1', '00000000-0000-0000-0000-00000099a3b1'),
  ('00000000-0000-0000-0000-00000099a3a1', '00000000-0000-0000-0000-00000099a3c1');

-- Asha's flights: F1 DEL→BOM 10 days ago (6E, block 130), F2 BOM→DXB 20 days ago (EK), F3 DEL→BLR 400 days ago
-- (AI: outside a free caller's 12 months), F4 BLR→HYD 5 days ago (UK), F5 HYD→MAA 3 days ago (QP).
create temp table sp (id uuid, carrier text, num text, dep text, arr text, days int, airline text);
insert into sp values
  ('99a3a3a3-0000-0000-0000-000000000001', '6E', '7301', 'DEL', 'BOM', 10, 'IndiGo'),
  ('99a3a3a3-0000-0000-0000-000000000002', 'EK', '7302', 'BOM', 'DXB', 20, 'Emirates'),
  ('99a3a3a3-0000-0000-0000-000000000003', 'AI', '7303', 'DEL', 'BLR', 400, 'Air India'),
  ('99a3a3a3-0000-0000-0000-000000000004', 'UK', '7304', 'BLR', 'HYD', 5, 'Vistara'),
  ('99a3a3a3-0000-0000-0000-000000000005', 'QP', '7305', 'HYD', 'MAA', 3, 'Akasa Air'),
  ('99a3a3a3-0000-0000-0000-000000000006', '6E', '7306', 'MAA', 'DEL', 2, 'IndiGo');
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, airline_name,
                            sched_dep, sched_arr, act_dep, act_arr)
select s.id, s.carrier, s.num, ((now() - make_interval(days => s.days)) at time zone 'Asia/Kolkata')::date, s.dep, s.arr,
       'arrived', s.airline, now() - make_interval(days => s.days), now() - make_interval(days => s.days) + interval '150 minutes',
       now() - make_interval(days => s.days) + interval '10 minutes', now() - make_interval(days => s.days) + interval '140 minutes'
  from sp s;
insert into public.user_flights (user_id, flight_id, archived_at)
select '00000000-0000-0000-0000-00000099a3a1', s.id, now() from sp s;
insert into public.user_flights (user_id, flight_id, archived_at)
values ('00000000-0000-0000-0000-00000099a3c1', '99a3a3a3-0000-0000-0000-000000000001', now());

-- ===== defaults =====
do $$ begin
  assert (select share_passport_with_friends and not crew_mode from public.profiles
           where id = '00000000-0000-0000-0000-00000099a3d1'), 'defaults: share on, crew off';
end $$;

-- ===== users edit their own flags only =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a3c1"}';
update public.profiles set share_passport_with_friends = false, crew_mode = true where id = auth.uid();
do $$ declare n int; begin
  assert (select not share_passport_with_friends and crew_mode from public.profiles where id = auth.uid()), 'own flags set';
  update public.profiles set crew_mode = true where id = '00000000-0000-0000-0000-00000099a3d1';
  get diagnostics n = row_count;
  assert n = 0, 'cannot touch another profile';
  begin
    update public.profiles set plan = 'pro' where id = auth.uid();
    raise exception 'FAIL: client set plan';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- ===== friend_passport =====
begin;
set local role authenticated;
-- Bharat (Pro) views Asha: all time
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a3b1"}';
do $$ declare j jsonb; begin
  j := public.friend_passport('00000000-0000-0000-0000-00000099a3a1');
  assert j ->> 'display_name' = 'Asha', 'name';
  assert j ->> 'scope' = 'all' and not (j ->> 'pro_limited')::boolean, 'Pro viewer: all time';
  assert (j ->> 'flights')::int = 6, 'six flights: ' || j::text;
  assert (j ->> 'airports')::int = 6, 'airport count (DEL BOM DXB BLR HYD MAA): ' || (j ->> 'airports');
  assert (j ->> 'countries')::int = 2 and j -> 'country_codes' = '["AE", "IN"]'::jsonb, 'countries';
  assert jsonb_array_length(j -> 'airlines') = 3, 'top 3 airlines only';
  assert j -> 'airlines' -> 0 ->> 'carrier' = '6E' and (j -> 'airlines' -> 0 ->> 'count')::int = 2, 'top airline';
  assert j -> 'longest' ->> 'dep' = 'BOM' and j -> 'longest' ->> 'arr' = 'DXB', 'longest';
  assert not (j -> 'longest') ? 'flight_id', 'no flight ids of the friend';
  assert (j ->> 'distance_km')::int > 0, 'distance';
  assert not j ? 'delay' and not j ? 'by_month' and not j ? 'aircraft', 'reduced shape';
  -- same number as Asha's own passport would show a Pro user (checked below, outside the client role)
  perform set_config('p3.friend_km', j ->> 'distance_km', true);
end $$;
-- Asha (free) views Bharat (no flights): last 12 months scope, zeros
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a3a1"}';
do $$ declare j jsonb; begin
  j := public.friend_passport('00000000-0000-0000-0000-00000099a3b1');
  assert j ->> 'scope' = 'last12' and (j ->> 'pro_limited')::boolean, 'free viewer: last 12 months';
  assert (j ->> 'flights')::int = 0 and j -> 'longest' = 'null'::jsonb and j -> 'airlines' = '[]'::jsonb, 'empty passport';
  -- Chitra turned sharing off: same error as a stranger
  begin
    perform public.friend_passport('00000000-0000-0000-0000-00000099a3c1');
    raise exception 'FAIL: sharing off ignored';
  exception when insufficient_privilege then assert sqlerrm = 'not_shared', sqlerrm; end;
  begin
    perform public.friend_passport('00000000-0000-0000-0000-00000099a3d1');
    raise exception 'FAIL: stranger passport shown';
  exception when insufficient_privilege then assert sqlerrm = 'not_shared', sqlerrm; end;
  begin
    perform public.friend_passport(auth.uid());
    raise exception 'FAIL: own id accepted as a friend';
  exception when insufficient_privilege then null; end;
  begin
    perform public.friend_passport(null);
    raise exception 'FAIL: null accepted';
  exception when insufficient_privilege then null; end;
  begin
    perform public.passport_core(auth.uid(), null, true);
    raise exception 'FAIL: client called passport_core';
  exception when insufficient_privilege then null; end;
end $$;
reset role;
do $$ begin
  assert current_setting('p3.friend_km')::int
         = (public.passport_core('00000000-0000-0000-0000-00000099a3a1', null, true) ->> 'distance_km')::int,
         'distance matches the core';
end $$;
set local role authenticated;
-- a free viewer sees the friend's last 12 months only (F3 is 400 days old)
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a3c1"}';
do $$ declare j jsonb; begin
  j := public.friend_passport('00000000-0000-0000-0000-00000099a3a1');
  assert (j ->> 'flights')::int = 5, 'free viewer: 5 flights in 12 months, got ' || (j ->> 'flights');
end $$;
rollback;

begin;
set local role anon;
do $$ begin
  begin
    perform public.friend_passport('00000000-0000-0000-0000-00000099a3a1');
    raise exception 'FAIL: anon called friend_passport';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== my_passport: same contract, + block_minutes =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a3a1"}';
do $$ declare j jsonb; begin
  j := public.my_passport();
  assert j ->> 'scope' = 'last12' and (j ->> 'flights')::int = 5, 'free: last 12 months';
  -- block time = act_arr - act_dep = 130 min each (no off/on-block columns filled)
  assert (j ->> 'block_minutes')::int = 5 * 130, 'block minutes: ' || (j ->> 'block_minutes');
  assert j ? 'airborne_minutes' and j ? 'delay' and j ? 'by_month' and j ? 'co2_kg', 'P2 fields kept';
end $$;
rollback;

-- block time prefers off-block → on-block (migration 22 columns) when both are known
begin;
update public.flights set dep_offblock_at = act_dep - interval '12 minutes', arr_onblock_at = act_arr + interval '8 minutes'
 where id = '99a3a3a3-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a3a1"}';
do $$ declare j jsonb; begin
  j := public.my_passport();
  assert (j ->> 'block_minutes')::int = 4 * 130 + 150, 'off-block to on-block: ' || (j ->> 'block_minutes');
  assert (j ->> 'airborne_minutes')::int = 5 * 130, 'airborne unchanged: ' || (j ->> 'airborne_minutes');
end $$;
rollback;

-- ===== get_flight_recipients.crew_mode =====
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
values ('99a3a3a3-0000-0000-0000-0000000000c1', '6E', '7399', current_date + 1, 'DEL', 'BOM', 'scheduled',
        now() + interval '1 day', now() + interval '1 day 2 hours');
insert into public.user_flights (user_id, flight_id) values
  ('00000000-0000-0000-0000-00000099a3c1', '99a3a3a3-0000-0000-0000-0000000000c1'),
  ('00000000-0000-0000-0000-00000099a3b1', '99a3a3a3-0000-0000-0000-0000000000c1');
insert into public.devices (user_id, fcm_token) values
  ('00000000-0000-0000-0000-00000099a3c1', 'crewTokC_' || repeat('c', 30)),
  ('00000000-0000-0000-0000-00000099a3b1', 'crewTokB_' || repeat('b', 30)),
  ('00000000-0000-0000-0000-00000099a3a1', 'crewTokA_' || repeat('a', 30));
do $$ begin
  assert (select crew_mode from public.get_flight_recipients('99a3a3a3-0000-0000-0000-0000000000c1')
           where user_id = '00000000-0000-0000-0000-00000099a3c1'), 'crew user flagged';
  assert not (select crew_mode from public.get_flight_recipients('99a3a3a3-0000-0000-0000-0000000000c1')
               where user_id = '00000000-0000-0000-0000-00000099a3b1'), 'non-crew user not flagged';
  -- Asha is a friend of both trackers (alerts on): one friend row, crew_mode false
  assert (select count(*) from public.get_flight_recipients('99a3a3a3-0000-0000-0000-0000000000c1')
           where role = 'friend' and user_id = '00000000-0000-0000-0000-00000099a3a1' and not crew_mode) = 1, 'friend row';
end $$;

select '99s3 social: ok' as result;
