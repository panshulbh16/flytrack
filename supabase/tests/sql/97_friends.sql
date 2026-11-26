-- Friends (docs/P2_WAVE2_CONTRACTS.md section 1): invite lifecycle + error codes, the 50-friend limit,
-- RLS isolation, share_with_friends, can_read_flight / flight_view for friends, friends_flights(), and the
-- friend rows of get_flight_recipients (role, friend_name, alerts toggle, own trackers excluded).
\set QUIET on
set client_min_messages = warning;

-- A = Asha (tracks flights), B = friend with alerts, C = friend who muted Asha, D = stranger,
-- E = friend who also tracks Asha's flight, G = a user who already has 50 friends.
insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000097a1'),
  ('00000000-0000-0000-0000-0000000097b1'),
  ('00000000-0000-0000-0000-0000000097c1'),
  ('00000000-0000-0000-0000-0000000097d1'),
  ('00000000-0000-0000-0000-0000000097e1'),
  ('00000000-0000-0000-0000-0000000097f1')
on conflict (id) do nothing;
update public.profiles set trial_used = true where id::text like '00000000-0000-0000-0000-000000009%';
update public.profiles set display_name = '  Asha ' where id = '00000000-0000-0000-0000-0000000097a1';
update public.profiles set display_name = 'Bharat', alert_language = 'hi', quiet_hours_enabled = true
 where id = '00000000-0000-0000-0000-0000000097b1';

-- F1 upcoming (shared), F2 upcoming (share_with_friends = false), F3 departed 30 h ago, F4 departed 2 h ago.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr, act_dep,
                            prediction, inbound_flight_id)
values ('97979797-0000-0000-0000-000000000001', '6E', '9701', current_date, 'DEL', 'BOM', 'scheduled',
        now() + interval '5 hours', now() + interval '7 hours', null, '{"lateMin": 40}', null),
       ('97979797-0000-0000-0000-000000000002', 'AI', '9702', current_date, 'DEL', 'BLR', 'scheduled',
        now() + interval '6 hours', now() + interval '9 hours', null, null, null),
       ('97979797-0000-0000-0000-000000000003', 'QP', '9703', current_date - 2, 'BOM', 'DEL', 'arrived',
        now() - interval '31 hours', now() - interval '29 hours', now() - interval '30 hours', null, null),
       ('97979797-0000-0000-0000-000000000004', 'SG', '9704', current_date, 'DEL', 'BOM', 'en_route',
        now() - interval '2 hours', now() + interval '10 minutes', now() - interval '2 hours', null, null);

insert into public.user_flights (user_id, flight_id, pnr, seat, share_with_friends) values
  ('00000000-0000-0000-0000-0000000097a1', '97979797-0000-0000-0000-000000000001', 'ABC123', '12A', true),
  ('00000000-0000-0000-0000-0000000097a1', '97979797-0000-0000-0000-000000000002', null, null, false),
  ('00000000-0000-0000-0000-0000000097a1', '97979797-0000-0000-0000-000000000003', null, null, true),
  ('00000000-0000-0000-0000-0000000097a1', '97979797-0000-0000-0000-000000000004', null, null, true),
  ('00000000-0000-0000-0000-0000000097e1', '97979797-0000-0000-0000-000000000001', null, null, true);

insert into public.devices (user_id, fcm_token) values
  ('00000000-0000-0000-0000-0000000097a1', 'friendTokA_' || repeat('a', 30)),
  ('00000000-0000-0000-0000-0000000097b1', 'friendTokB1_' || repeat('b', 30)),
  ('00000000-0000-0000-0000-0000000097b1', 'friendTokB2_' || repeat('b', 30)),
  ('00000000-0000-0000-0000-0000000097c1', 'friendTokC_' || repeat('c', 30)),
  ('00000000-0000-0000-0000-0000000097d1', 'friendTokD_' || repeat('d', 30)),
  ('00000000-0000-0000-0000-0000000097e1', 'friendTokE_' || repeat('e', 30));

-- ===== defaults and direct-write protection =====
do $$ begin
  assert (select share_with_friends from public.user_flights
           where user_id = '00000000-0000-0000-0000-0000000097e1'), 'share_with_friends defaults to true';
end $$;

-- ===== invite lifecycle =====
create temp table t_codes (who text primary key, code text);
grant all on t_codes to authenticated;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097a1"}';
do $$ declare c1 text; c2 text; begin
  c1 := public.create_friend_invite();
  assert c1 ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$', 'code shape: ' || c1;
  c2 := public.create_friend_invite();
  assert c1 = c2, 'unexpired unused code is reused';
  assert (select count(*) from public.friend_invites) = 1, 'owner sees their invite';
  assert (select expires_at from public.friend_invites where code = c1)
         between now() + interval '6 days 23 hours' and now() + interval '7 days 1 hour', 'expires in 7 days';
  -- own code
  begin
    perform public.accept_friend_invite(c1);
    raise exception 'FAIL: accepted own code';
  exception when invalid_parameter_value then assert sqlerrm = 'own_code', sqlerrm; end;
  -- clients cannot write the tables
  begin
    insert into public.friend_invites (code, user_id) values ('ABCDEFGH', auth.uid());
    raise exception 'FAIL: client inserted an invite';
  exception when insufficient_privilege then null; end;
  begin
    insert into public.friendships (user_low, user_high)
    values ('00000000-0000-0000-0000-0000000097a1', '00000000-0000-0000-0000-0000000097d1');
    raise exception 'FAIL: client inserted a friendship';
  exception when insufficient_privilege then null; end;
  insert into t_codes values ('a', c1);
end $$;
commit;

-- the stranger can't see Asha's invite
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097d1"}';
do $$ begin
  assert (select count(*) from public.friend_invites) = 0, 'invites are private to their owner';
  begin
    perform public.accept_friend_invite('ZZZZZZZZ');
    raise exception 'FAIL: unknown code accepted';
  exception when no_data_found then assert sqlerrm = 'invalid_code', sqlerrm; end;
  begin
    perform public.accept_friend_invite('not a code');
    raise exception 'FAIL: malformed code accepted';
  exception when no_data_found then assert sqlerrm = 'invalid_code', sqlerrm; end;
end $$;
rollback;

-- B accepts (lower case, with a dash: normalised)
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ declare r jsonb; c text := (select code from t_codes where who = 'a'); begin
  r := public.accept_friend_invite(lower(substr(c, 1, 4)) || '-' || substr(c, 5));
  assert r ->> 'friend_id' = '00000000-0000-0000-0000-0000000097a1', r::text;
  assert r ->> 'display_name' = 'Asha', 'display name is trimmed: ' || r::text;
  assert (select count(*) from public.friendships) = 1, 'B sees the friendship';
end $$;
commit;

-- the code is single use; a new one is issued afterwards
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097c1"}';
do $$ begin
  begin
    perform public.accept_friend_invite((select code from t_codes where who = 'a'));
    raise exception 'FAIL: used code accepted again';
  exception when no_data_found then assert sqlerrm = 'invalid_code', sqlerrm; end;
end $$;
rollback;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097a1"}';
do $$ declare c text; begin
  c := public.create_friend_invite();
  assert c <> (select code from t_codes where who = 'a'), 'a used code is not reused';
  update t_codes set code = c where who = 'a';
end $$;
commit;

-- already friends (B again) → 23505, and the code stays usable
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ begin
  begin
    perform public.accept_friend_invite((select code from t_codes where who = 'a'));
    raise exception 'FAIL: became friends twice';
  exception when unique_violation then assert sqlerrm = 'already_friends', sqlerrm; end;
end $$;
rollback;

-- C and E accept
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097c1"}';
do $$ begin perform public.accept_friend_invite((select code from t_codes where who = 'a')); end $$;
commit;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097a1"}';
update t_codes set code = public.create_friend_invite() where who = 'a';
commit;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097e1"}';
do $$ begin perform public.accept_friend_invite((select code from t_codes where who = 'a')); end $$;
commit;

-- expired code → invalid_code
insert into public.friend_invites (code, user_id, expires_at)
values ('EXP2EXP2', '00000000-0000-0000-0000-0000000097c1', now() - interval '1 second');
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097d1"}';
do $$ begin
  begin
    perform public.accept_friend_invite('EXP2EXP2');
    raise exception 'FAIL: expired code accepted';
  exception when no_data_found then assert sqlerrm = 'invalid_code', sqlerrm; end;
end $$;
rollback;

-- ===== friend limit (50) =====
insert into auth.users (id)
select ('00000000-0000-0000-0000-0000009f' || lpad(to_hex(g), 4, '0'))::uuid from generate_series(1, 50) g
on conflict (id) do nothing;
insert into public.friendships (user_low, user_high)
select least(u, '00000000-0000-0000-0000-0000000097f1'::uuid), greatest(u, '00000000-0000-0000-0000-0000000097f1'::uuid)
  from (select ('00000000-0000-0000-0000-0000009f' || lpad(to_hex(g), 4, '0'))::uuid as u from generate_series(1, 50) g) x;
insert into public.friend_invites (code, user_id) values ('FWWW2345', '00000000-0000-0000-0000-0000000097f1');
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097d1"}';
do $$ begin
  begin
    perform public.accept_friend_invite('FWWW2345');
    raise exception 'FAIL: 51st friend accepted';
  exception when raise_exception then assert sqlerrm = 'friend_limit', sqlerrm; end;
end $$;
rollback;
-- the limit applies to the one accepting, too
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097f1"}';
do $$ begin
  begin
    perform public.accept_friend_invite('EXP2EXP2');  -- expired → invalid_code comes first
    raise exception 'FAIL';
  exception when no_data_found then null; end;
end $$;
rollback;
update public.friend_invites set expires_at = now() + interval '1 day' where code = 'EXP2EXP2';
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097f1"}';
do $$ begin
  begin
    perform public.accept_friend_invite('EXP2EXP2');
    raise exception 'FAIL: accepter over the limit';
  exception when raise_exception then assert sqlerrm = 'friend_limit', sqlerrm; end;
end $$;
rollback;
delete from public.friendships where user_low = '00000000-0000-0000-0000-0000000097f1' or user_high = '00000000-0000-0000-0000-0000000097f1';

-- ===== my_friends / set_friend_alerts =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097c1"}';
do $$ declare j jsonb; begin
  perform public.set_friend_alerts('00000000-0000-0000-0000-0000000097a1', false);  -- C mutes Asha
  j := public.my_friends();
  assert jsonb_array_length(j) = 1, j::text;
  assert j -> 0 ->> 'friend_id' = '00000000-0000-0000-0000-0000000097a1', j::text;
  assert j -> 0 ->> 'display_name' = 'Asha', j::text;
  assert (j -> 0 ->> 'alerts_enabled')::boolean = false, j::text;
  assert j -> 0 ->> 'since' is not null, j::text;
  begin
    perform public.set_friend_alerts('00000000-0000-0000-0000-0000000097d1', true);
    raise exception 'FAIL: alerts set for a non-friend';
  exception when no_data_found then assert sqlerrm = 'not_friends', sqlerrm; end;
end $$;
commit;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097a1"}';
do $$ declare j jsonb; begin
  j := public.my_friends();
  assert jsonb_array_length(j) = 3, 'Asha has B, C, E: ' || j::text;
  -- C muting Asha does not change what Asha gets for C
  assert (select (x ->> 'alerts_enabled')::boolean from jsonb_array_elements(j) x
           where x ->> 'friend_id' = '00000000-0000-0000-0000-0000000097c1'), 'alerts are per direction';
  assert (select x ->> 'display_name' from jsonb_array_elements(j) x
           where x ->> 'friend_id' = '00000000-0000-0000-0000-0000000097c1') is null, 'unset name is null';
  assert (select count(*) from public.friendships) = 3, 'Asha sees her 3 friendships';
end $$;
rollback;

-- ===== RLS isolation: the stranger sees nothing =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097d1"}';
do $$ begin
  assert (select count(*) from public.friendships) = 0, 'no friendships visible to a stranger';
  assert public.my_friends() = '[]'::jsonb, 'stranger has no friends';
  assert public.friends_flights() = '[]'::jsonb, 'stranger sees no friends'' flights';
  assert not public.can_read_flight('97979797-0000-0000-0000-000000000001'), 'stranger cannot read Asha''s flight';
  assert (select count(*) from public.flight_view where id = '97979797-0000-0000-0000-000000000001') = 0, 'flight_view';
  assert (select count(*) from public.user_flights) = 0, 'user_flights stay private';
end $$;
rollback;

-- ===== friend reads shared flights, not share_with_friends = false =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ declare j jsonb; begin
  assert public.can_read_flight('97979797-0000-0000-0000-000000000001'), 'friend reads shared flight';
  assert not public.can_read_flight('97979797-0000-0000-0000-000000000002'), 'not a share_with_friends=false flight';
  assert (select count(*) from public.flight_view where id = '97979797-0000-0000-0000-000000000001') = 1, 'flight_view row';
  assert (select count(*) from public.flight_view where id = '97979797-0000-0000-0000-000000000002') = 0, 'hidden flight';
  -- Pro fields stay masked for the friend (not Pro for that flight)
  assert (select prediction is null and inbound_flight_id is null from public.flight_view
           where id = '97979797-0000-0000-0000-000000000001'), 'Pro fields masked';
  -- the friend still can't see Asha's user_flights (PNR, seat)
  assert (select count(*) from public.user_flights) = 0, 'PNR rows stay private';

  j := public.friends_flights();
  -- F1 (upcoming, shared) and F4 (departed 2 h ago); not F2 (hidden) nor F3 (departed 30 h ago)
  assert jsonb_array_length(j) = 2, j::text;
  assert (select array_agg(x ->> 'flight_id' order by x ->> 'flight_id') from jsonb_array_elements(j) x)
       = array['97979797-0000-0000-0000-000000000001', '97979797-0000-0000-0000-000000000004'], j::text;
  assert j -> 0 ->> 'flight_id' = '97979797-0000-0000-0000-000000000004', 'earliest departure first';
  assert j -> 0 ->> 'friend_id' = '00000000-0000-0000-0000-0000000097a1', j::text;
  assert j -> 0 ->> 'display_name' = 'Asha', j::text;
  assert j -> 1 -> 'flight' ->> 'carrier' = '6E' and j -> 1 -> 'flight' ->> 'number' = '9701', j::text;
  assert j -> 1 -> 'flight' -> 'dep' ->> 'iata' = 'DEL' and j -> 1 -> 'flight' -> 'arr' ->> 'iata' = 'BOM', j::text;
  assert not (j::text ~ 'ABC123|12A|pnr|seat|prediction'), 'no PNR, seat or Pro data: ' || j::text;
end $$;
rollback;

-- archived flights are no longer visible to friends
update public.user_flights set archived_at = now()
 where user_id = '00000000-0000-0000-0000-0000000097a1' and flight_id = '97979797-0000-0000-0000-000000000004';
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ begin
  assert not public.can_read_flight('97979797-0000-0000-0000-000000000004'), 'archived flight hidden';
  assert jsonb_array_length(public.friends_flights()) = 1, 'archived flight not listed';
end $$;
rollback;

-- Asha turns sharing on/off herself (column grant)
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097a1"}';
update public.user_flights set share_with_friends = false where flight_id = '97979797-0000-0000-0000-000000000001';
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ begin
  assert not public.can_read_flight('97979797-0000-0000-0000-000000000001'), 'unshared flight hidden';
  assert public.friends_flights() = '[]'::jsonb, 'unshared flight not listed';
end $$;
rollback;

-- ===== can_read_flight keeps earlier conditions =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097a1"}';
do $$ begin
  assert public.can_read_flight('97979797-0000-0000-0000-000000000002'), 'own unshared flight';
  assert public.can_read_flight('97979797-0000-0000-0000-000000000004'), 'own archived flight';
end $$;
rollback;

-- ===== get_flight_recipients: trackers + friends =====
do $$ declare n int; r record; begin
  -- F1: trackers A and E; friends B (2 devices, alerts on), C (muted), E (tracks it: tracker only)
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'tracker') = 2, 'two tracker rows (A, E)';
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'tracker' and friend_name is null) = 2, 'tracker rows have no friend_name';
  select count(*) into n from public.get_flight_recipients('97979797-0000-0000-0000-000000000001') where role = 'friend';
  assert n = 2, 'B''s two devices only, got ' || n;
  for r in select * from public.get_flight_recipients('97979797-0000-0000-0000-000000000001') where role = 'friend' loop
    assert r.user_id = '00000000-0000-0000-0000-0000000097b1', 'friend row is B';
    assert r.friend_name = 'Asha', 'friend_name is the tracker''s name';
    assert r.is_pro = false and r.notify_prefs = '{}'::jsonb, 'friend rows are never Pro';
    assert r.alert_language = 'hi' and r.quiet_hours_enabled, 'the friend''s own language and quiet hours';
  end loop;
  -- E tracks the flight AND is Asha's friend: one tracker row, no friend row
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where user_id = '00000000-0000-0000-0000-0000000097e1') = 1, 'own tracking wins over friend';
  -- F2 not shared → no friend rows
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000002')
           where role = 'friend') = 0, 'share_with_friends=false → no friend alerts';
  -- stranger D never appears
  assert not exists (select 1 from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
                      where user_id = '00000000-0000-0000-0000-0000000097d1'), 'stranger gets nothing';
end $$;

-- muted device of the friend → no row; Asha's name unset → friend_name null
update public.devices set notifications_enabled = false where fcm_token = 'friendTokB2_' || repeat('b', 30);
update public.profiles set display_name = '   ' where id = '00000000-0000-0000-0000-0000000097a1';
do $$ begin
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'friend') = 1, 'muted device skipped';
  assert (select friend_name from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'friend') is null, 'blank name → null (apps / templates fall back)';
end $$;
-- a name can't carry line breaks or bidi overrides into an alert or list row
update public.profiles set display_name = E' A‮sh⁦a\n  Sharma  ' where id = '00000000-0000-0000-0000-0000000097a1';
do $$ begin
  assert (select friend_name from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'friend') = 'Asha Sharma', 'name cleaned to one plain line';
end $$;
update public.profiles set display_name = 'Asha' where id = '00000000-0000-0000-0000-0000000097a1';

-- B mutes Asha → no friend rows; clients can't call the recipients function
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ begin perform public.set_friend_alerts('00000000-0000-0000-0000-0000000097a1', false); end $$;
do $$ begin
  begin
    perform * from public.get_flight_recipients('97979797-0000-0000-0000-000000000001');
    raise exception 'FAIL: client called get_flight_recipients';
  exception when insufficient_privilege then null; end;
end $$;
reset role;
do $$ begin
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'friend') = 0, 'muted friend gets no rows';
end $$;
rollback;

-- ===== remove_friend =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000097b1"}';
do $$ begin perform public.remove_friend('00000000-0000-0000-0000-0000000097a1'); end $$;
do $$ begin perform public.remove_friend('00000000-0000-0000-0000-0000000097d1'); end $$;  -- not friends: no-op
do $$ begin
  assert public.my_friends() = '[]'::jsonb, 'friendship gone';
  assert not public.can_read_flight('97979797-0000-0000-0000-000000000001'), 'no access after removal';
  assert public.friends_flights() = '[]'::jsonb, 'no flights after removal';
end $$;
reset role;
do $$ begin
  assert (select count(*) from public.get_flight_recipients('97979797-0000-0000-0000-000000000001')
           where role = 'friend') = 0, 'no alerts after removal';
  assert (select count(*) from public.friendships where user_low = '00000000-0000-0000-0000-0000000097a1') = 2,
         'removed for both sides';
end $$;
rollback;

-- ===== anon has no access =====
begin;
set local role anon;
do $$ begin
  begin
    perform public.create_friend_invite();
    raise exception 'FAIL: anon created an invite';
  exception when insufficient_privilege then null; end;
  begin
    perform * from public.friendships;
    raise exception 'FAIL: anon read friendships';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

drop table t_codes;
select 'friends tests: OK' as result;
