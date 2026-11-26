-- P3 B13 (docs/P3_CONTRACTS.md): family sharing. Owner eligibility (yearly / lifetime own Pro), invites
-- and error codes, the 5-member limit, entitlement inheritance through is_pro and everything built on it
-- (current_user_is_pro, is_pro_for_flight, flight_view masking, tracker counts, the tracking cap), no
-- inheritance past one level, RLS isolation, leave / remove, my_family, family_pro_source.
\set QUIET on
set client_min_messages = warning;

-- O = owner (yearly: Pro for 300 more days), M1..M6 = would-be members, L = lifetime owner,
-- Y = monthly Pro (not eligible), R = renewing yearly near its end (eligible via billing_events), S = stranger
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000099a4a0'),
  ('00000000-0000-0000-0000-00000099a4a1'), ('00000000-0000-0000-0000-00000099a4a2'),
  ('00000000-0000-0000-0000-00000099a4a3'), ('00000000-0000-0000-0000-00000099a4a4'),
  ('00000000-0000-0000-0000-00000099a4a5'), ('00000000-0000-0000-0000-00000099a4a6'),
  ('00000000-0000-0000-0000-00000099a4b1'), ('00000000-0000-0000-0000-00000099a4b2'),
  ('00000000-0000-0000-0000-00000099a4b3'), ('00000000-0000-0000-0000-00000099a4b4')
on conflict (id) do nothing;
update public.profiles set trial_used = true where id::text like '00000000-0000-0000-0000-00000099a4%';
update public.profiles set display_name = 'Owen', plan = 'pro', pro_until = now() + interval '300 days'
 where id = '00000000-0000-0000-0000-00000099a4a0';
update public.profiles set plan = 'pro', pro_until = null where id = '00000000-0000-0000-0000-00000099a4b1';   -- L
update public.profiles set plan = 'pro', pro_until = now() + interval '20 days'
 where id in ('00000000-0000-0000-0000-00000099a4b2', '00000000-0000-0000-0000-00000099a4b3');               -- Y, R
insert into public.billing_events (id, type, app_user_id, product_id, expires_at, processed_at) values
  ('fam-y-1', 'INITIAL_PURCHASE', '00000000-0000-0000-0000-00000099a4b2', 'heygate_pro_monthly', now() + interval '20 days', now()),
  ('fam-r-1', 'INITIAL_PURCHASE', '00000000-0000-0000-0000-00000099a4b3', 'heygate_pro_monthly', now() - interval '345 days', now()),
  ('fam-r-2', 'RENEWAL', '00000000-0000-0000-0000-00000099a4b3', 'heygate_pro_yearly', now() + interval '20 days', now());

-- M1 tracks a flight (for tracker counts and flight_view masking)
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr,
                            prediction)
values ('99a4a4a4-0000-0000-0000-000000000001', '6E', '7401', current_date + 2, 'DEL', 'BOM', 'scheduled',
        now() + interval '2 days', now() + interval '2 days 2 hours', '{"lateMin": 30}');
insert into public.user_flights (user_id, flight_id)
values ('00000000-0000-0000-0000-00000099a4a1', '99a4a4a4-0000-0000-0000-000000000001');

create temp table t_fam (k text primary key, v text);
grant all on t_fam to authenticated;

-- ===== eligibility =====
do $$ begin
  assert public.family_owner_eligible('00000000-0000-0000-0000-00000099a4a0'), 'yearly (300 days left) eligible';
  assert public.family_owner_eligible('00000000-0000-0000-0000-00000099a4b1'), 'lifetime eligible';
  assert not public.family_owner_eligible('00000000-0000-0000-0000-00000099a4b2'), 'monthly not eligible';
  assert public.family_owner_eligible('00000000-0000-0000-0000-00000099a4b3'), 'yearly near its end eligible (billing_events)';
  assert not public.family_owner_eligible('00000000-0000-0000-0000-00000099a4a1'), 'free not eligible';
  assert (select pro_tracker_count from public.flights where id = '99a4a4a4-0000-0000-0000-000000000001') = 0,
         'M1 free: no Pro tracker yet';
end $$;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4b2"}';
do $$ begin
  begin
    perform public.create_family_invite();
    raise exception 'FAIL: monthly owner created a family';
  exception when insufficient_privilege then assert sqlerrm = 'family_requires_yearly', sqlerrm; end;
  assert public.my_family() is null, 'no family';
end $$;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a1"}';
do $$ begin
  begin
    perform public.create_family_invite();
    raise exception 'FAIL: free user created a family';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== owner creates an invite; members join =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a0"}';
do $$ declare c1 text; c2 text; f jsonb; begin
  c1 := public.create_family_invite();
  assert c1 ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$', 'code shape: ' || c1;
  c2 := public.create_family_invite();
  assert c1 = c2, 'unused code reused';
  insert into t_fam values ('code', c1);
  f := public.my_family();
  assert f ->> 'role' = 'owner' and jsonb_array_length(f -> 'members') = 0, 'owner, no members yet';
  assert (f ->> 'can_invite')::boolean and f -> 'invite' ->> 'code' = c1, 'owner sees the code';
  assert (f ->> 'max_members')::int = 5 and (f ->> 'owner_pro')::boolean and not (f ->> 'pro_via_family')::boolean, 'flags';
  begin
    perform public.accept_family_invite(c1);
    raise exception 'FAIL: owner accepted own code';
  exception when invalid_parameter_value then assert sqlerrm = 'own_code', sqlerrm; end;
  -- clients never write the tables
  begin
    insert into public.family_members (family_id, user_id) values ((f ->> 'family_id')::uuid, auth.uid());
    raise exception 'FAIL: client inserted a member';
  exception when insufficient_privilege then null; end;
  begin
    insert into public.families (owner_id) values ('00000000-0000-0000-0000-00000099a4a6');
    raise exception 'FAIL: client inserted a family';
  exception when insufficient_privilege then null; end;
end $$;

-- M1 joins with a messy code
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a1"}';
do $$ declare r jsonb; f jsonb; c text := (select v from t_fam where k = 'code'); begin
  r := public.accept_family_invite(lower(substr(c, 1, 4)) || '-' || substr(c, 5));
  assert r ->> 'owner_name' = 'Owen' and r ->> 'owner_id' = '00000000-0000-0000-0000-00000099a4a0', 'accept result';
  f := public.my_family();
  assert f ->> 'role' = 'member' and (f ->> 'pro_via_family')::boolean, 'member, Pro via family';
  assert f -> 'owner' ->> 'display_name' = 'Owen', 'owner name';
  assert f -> 'invite' = 'null'::jsonb and not (f ->> 'can_invite')::boolean, 'members see no code';
  assert public.current_user_is_pro(), 'member is Pro';
  assert public.current_user_is_pro_for_flight('99a4a4a4-0000-0000-0000-000000000001'), 'Pro for their flight';
  assert (select prediction is not null from public.flight_view where id = '99a4a4a4-0000-0000-0000-000000000001'),
         'flight_view shows Pro fields to the member';
  -- the code is single-use
  begin
    perform public.accept_family_invite(c);
    raise exception 'FAIL: code reused';
  exception when no_data_found then assert sqlerrm = 'invalid_code', sqlerrm; end;
  -- a member can never start a family of their own (Pro is not passed on)
  begin
    perform public.create_family_invite();
    raise exception 'FAIL: member created a family';
  exception when unique_violation then assert sqlerrm = 'already_in_family', sqlerrm; end;
end $$;
commit;

do $$ begin
  assert public.is_pro('00000000-0000-0000-0000-00000099a4a1'), 'is_pro via family';
  assert not public.is_pro_own('00000000-0000-0000-0000-00000099a4a1'), 'no own Pro';
  assert public.tracking_cap('00000000-0000-0000-0000-00000099a4a1') = 300, 'member gets the Pro cap';
  assert (select pro_tracker_count from public.flights where id = '99a4a4a4-0000-0000-0000-000000000001') = 1,
         'joining recounted the Pro tracker';
  assert (select count(*) from public.get_flight_recipients('99a4a4a4-0000-0000-0000-000000000001')) = 0
         or (select bool_and(is_pro) from public.get_flight_recipients('99a4a4a4-0000-0000-0000-000000000001')),
         'recipients are Pro';
  assert public.family_pro_source('00000000-0000-0000-0000-00000099a4a1') ->> 'owner_name' = 'Owen', 'family_pro_source';
  assert public.family_pro_source('00000000-0000-0000-0000-00000099a4a0') is null, 'owner: own Pro, no source';
  assert public.family_pro_source('00000000-0000-0000-0000-00000099a4b4') is null, 'stranger: no source';
end $$;

-- ===== M2..M5 join; the 6th member is refused =====
create or replace function pg_temp.join_as(p_user uuid) returns jsonb language plpgsql as $$
declare c text; r jsonb;
begin
  perform set_config('request.jwt.claims', json_build_object('sub', '00000000-0000-0000-0000-00000099a4a0')::text, true);
  c := public.create_family_invite();
  perform set_config('request.jwt.claims', json_build_object('sub', p_user)::text, true);
  r := public.accept_family_invite(c);
  return r;
end $$;
grant execute on function pg_temp.join_as(uuid) to authenticated;
begin;
set local role authenticated;
do $$ begin
  perform pg_temp.join_as('00000000-0000-0000-0000-00000099a4a2');
  perform pg_temp.join_as('00000000-0000-0000-0000-00000099a4a3');
  perform pg_temp.join_as('00000000-0000-0000-0000-00000099a4a4');
  perform pg_temp.join_as('00000000-0000-0000-0000-00000099a4a5');
  -- full: the owner can't even create a code
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000099a4a0"}', true);
  assert jsonb_array_length(public.my_family() -> 'members') = 5, '5 members';
  assert not (public.my_family() ->> 'can_invite')::boolean, 'full: no invite';
  begin
    perform public.create_family_invite();
    raise exception 'FAIL: 6th invite';
  exception when raise_exception then assert sqlerrm = 'family_full', sqlerrm; end;
end $$;
commit;

-- a code made before the family filled up can't take a 6th seat either
do $$ declare fid uuid; begin
  select id into fid from public.families where owner_id = '00000000-0000-0000-0000-00000099a4a0';
  insert into public.family_invites (code, family_id) values ('FVWFAM22', fid);
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a6"}';
do $$ begin
  begin
    perform public.accept_family_invite('FVWFAM22');
    raise exception 'FAIL: 6th member joined';
  exception when raise_exception then assert sqlerrm = 'family_full', sqlerrm; end;
  begin
    perform public.accept_family_invite('ZZZZZZZZ');
    raise exception 'FAIL: unknown code';
  exception when no_data_found then assert sqlerrm = 'invalid_code', sqlerrm; end;
  begin
    perform public.accept_family_invite('bad');
    raise exception 'FAIL: malformed code';
  exception when no_data_found then null; end;
end $$;
rollback;

-- ===== RLS isolation =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a3"}';
do $$ begin
  assert (select count(*) from public.families) = 1, 'member sees their family';
  assert (select count(*) from public.family_members) = 5, 'member sees the members';
  assert (select count(*) from public.family_invites) = 0, 'members never see codes';
  begin
    delete from public.family_members where user_id = '00000000-0000-0000-0000-00000099a4a1';
    raise exception 'FAIL: member deleted another member';
  exception when insufficient_privilege then null; end;
end $$;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4b4"}';
do $$ begin
  assert (select count(*) from public.families) = 0, 'stranger sees no family';
  assert (select count(*) from public.family_members) = 0, 'stranger sees no members';
  assert (select count(*) from public.family_invites) = 0, 'stranger sees no codes';
  assert public.my_family() is null, 'stranger: my_family null';
  begin
    perform public.is_pro('00000000-0000-0000-0000-00000099a4a1');
    raise exception 'FAIL: client called is_pro(uuid)';
  exception when insufficient_privilege then null; end;
  begin
    perform public.family_pro_source('00000000-0000-0000-0000-00000099a4a1');
    raise exception 'FAIL: client called family_pro_source';
  exception when insufficient_privilege then null; end;
end $$;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a0"}';
do $$ begin
  assert (select count(*) from public.family_invites) >= 1, 'owner sees their codes';
end $$;
rollback;

-- ===== no inheritance past one level =====
-- Even if a member somehow owned a family (forced here, bypassing the RPCs), their members get nothing.
do $$ declare fid uuid; begin
  insert into public.families (owner_id) values ('00000000-0000-0000-0000-00000099a4a1') returning id into fid;
  insert into public.family_members (family_id, user_id) values (fid, '00000000-0000-0000-0000-00000099a4b4');
  assert public.is_pro('00000000-0000-0000-0000-00000099a4a1'), 'M1 Pro via Owen';
  assert not public.is_pro('00000000-0000-0000-0000-00000099a4b4'), 'S never Pro through M1';
  assert public.family_pro_source('00000000-0000-0000-0000-00000099a4b4') is null, 'no source for S';
  delete from public.families where id = fid;
end $$;

-- ===== owner's plan drives members; counts follow =====
update public.profiles set pro_until = now() - interval '1 minute' where id = '00000000-0000-0000-0000-00000099a4a0';
do $$ begin
  assert not public.is_pro('00000000-0000-0000-0000-00000099a4a1'), 'owner lapsed → member not Pro';
  assert (select pro_tracker_count from public.flights where id = '99a4a4a4-0000-0000-0000-000000000001') = 0,
         'owner plan change recounted the member''s flight';
  assert public.tracking_cap('00000000-0000-0000-0000-00000099a4a1') = 100, 'free cap again';
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a1"}';
do $$ declare f jsonb; begin
  f := public.my_family();
  assert not (f ->> 'pro_via_family')::boolean and not (f ->> 'owner_pro')::boolean, 'flags follow the owner';
end $$;
rollback;
-- a lapsed owner's leftover code can't add anyone
do $$ declare fid uuid; begin
  select id into fid from public.families where owner_id = '00000000-0000-0000-0000-00000099a4a0';
  delete from public.family_members where family_id = fid and user_id = '00000000-0000-0000-0000-00000099a4a5';
  insert into public.family_invites (code, family_id) values ('APSED222', fid);
end $$;
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a6"}';
do $$ begin
  begin
    perform public.accept_family_invite('APSED222');
    raise exception 'FAIL: joined a lapsed family';
  exception when raise_exception then assert sqlerrm = 'family_inactive', sqlerrm; end;
end $$;
rollback;
update public.profiles set pro_until = now() + interval '300 days' where id = '00000000-0000-0000-0000-00000099a4a0';
do $$ begin
  assert public.is_pro('00000000-0000-0000-0000-00000099a4a1'), 'owner renewed → member Pro again';
  assert (select pro_tracker_count from public.flights where id = '99a4a4a4-0000-0000-0000-000000000001') = 1, 'recounted';
end $$;

-- ===== remove / leave =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a2"}';
do $$ begin
  begin
    perform public.remove_family_member('00000000-0000-0000-0000-00000099a4a3');
    raise exception 'FAIL: member removed someone';
  exception when insufficient_privilege then assert sqlerrm = 'not_owner', sqlerrm; end;
  perform public.leave_family();
  assert public.my_family() is null, 'M2 left';
  perform public.leave_family();  -- no-op
end $$;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a0"}';
do $$ begin
  perform public.remove_family_member('00000000-0000-0000-0000-00000099a4a1');
  begin
    perform public.remove_family_member('00000000-0000-0000-0000-00000099a4b4');
    raise exception 'FAIL: removed a non-member';
  exception when no_data_found then assert sqlerrm = 'not_member', sqlerrm; end;
  assert jsonb_array_length(public.my_family() -> 'members') = 2, 'M3, M4 left';
end $$;
commit;
do $$ begin
  assert not public.is_pro('00000000-0000-0000-0000-00000099a4a1'), 'removed member loses Pro';
  assert (select pro_tracker_count from public.flights where id = '99a4a4a4-0000-0000-0000-000000000001') = 0,
         'removal recounted';
end $$;

-- an owner of an empty family may join another family (the empty one is given up); one with members may not
begin;
set local role authenticated;
do $$ declare c text; begin
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000099a4b1"}', true);  -- L, lifetime
  c := public.create_family_invite();                                                                 -- L's own family
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000099a4a0"}', true);
  c := public.create_family_invite();
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000099a4b1"}', true);
  perform public.accept_family_invite(c);
  assert public.my_family() ->> 'role' = 'member', 'L joined Owen''s family';
  assert not exists (select 1 from public.families where owner_id = auth.uid()), 'L''s empty family is gone';
  -- Owen (owner with members) can't join someone else's family
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000099a4b3"}', true);
  c := public.create_family_invite();
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000099a4a0"}', true);
  begin
    perform public.accept_family_invite(c);
    raise exception 'FAIL: owner with members joined another family';
  exception when unique_violation then assert sqlerrm = 'already_in_family', sqlerrm; end;
end $$;
rollback;

-- the owner leaving ends the family for everyone
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000099a4a0"}';
do $$ begin perform public.leave_family(); end $$;
commit;
do $$ begin
  assert not exists (select 1 from public.families where owner_id = '00000000-0000-0000-0000-00000099a4a0'), 'family gone';
  assert not exists (select 1 from public.family_members where user_id::text like '00000000-0000-0000-0000-00000099a4%'),
         'members released';
  assert not public.is_pro('00000000-0000-0000-0000-00000099a4a3'), 'former member not Pro';
end $$;

select '99s4 family: ok' as result;
