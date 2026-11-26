-- Billing (P1 contract section 1): trial on the first flight, is_pro_for_flight in counts /
-- recipients / flight_view, server-only billing columns and billing_events.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000006e1'),  -- free user: gets the trial on their first flight
  ('00000000-0000-0000-0000-0000000006e2'),  -- pro user: no trial needed
  ('00000000-0000-0000-0000-0000000006e3')   -- free user: two parallel-looking first inserts
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days'
 where id = '00000000-0000-0000-0000-0000000006e2';

insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr, prediction)
values ('66666666-0000-0000-0000-000000000001', '6E', '601', current_date + 3, 'DEL', 'BOM', 'scheduled',
        now() + interval '3 days', now() + interval '3 days 2 hours', '{"lateMin": 30, "alertedBucket": 30, "inboundReg": "VT-A"}'),
       ('66666666-0000-0000-0000-000000000002', '6E', '602', current_date + 3, 'BOM', 'DEL', 'scheduled',
        now() + interval '3 days', now() + interval '3 days 2 hours', '{"lateMin": 15}'),
       ('66666666-0000-0000-0000-000000000003', '6E', '603', current_date + 3, 'BLR', 'DEL', 'scheduled',
        now() + interval '3 days', now() + interval '3 days 2 hours', null);

-- new profiles start without a trial
do $$ begin
  assert (select not trial_used and trial_flight_id is null from public.profiles
           where id = '00000000-0000-0000-0000-0000000006e1'), 'new profile has an unused trial';
end $$;

-- ===== free user: first flight becomes the trial flight, the second does not =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000006e1"}';
insert into public.user_flights (flight_id) values ('66666666-0000-0000-0000-000000000001');
insert into public.user_flights (flight_id) values ('66666666-0000-0000-0000-000000000002');
select public.register_device('billTokE1_' || repeat('q', 30)) is not null as ok \gset
do $$ declare v jsonb; p record; begin
  select trial_flight_id, trial_used into p from public.profiles where id = auth.uid();
  assert p.trial_used, 'trial marked used';
  assert p.trial_flight_id = '66666666-0000-0000-0000-000000000001', 'first flight is the trial flight';
  -- trial flight shows Pro data, the other flight stays masked
  select prediction into v from public.flight_view where id = '66666666-0000-0000-0000-000000000001';
  assert v ->> 'lateMin' = '30', 'trial flight shows the Pro prediction';
  assert not (v ? 'alertedBucket') and not (v ? 'inboundReg'), 'internal prediction keys stripped';
  assert (select prediction is null and has_pro_insight from public.flight_view
           where id = '66666666-0000-0000-0000-000000000002'), 'non-trial flight masked, teaser kept';
  assert public.current_user_is_pro_for_flight('66666666-0000-0000-0000-000000000001'), 'caller sees own trial';
  assert not public.current_user_is_pro_for_flight('66666666-0000-0000-0000-000000000002'), 'only on the trial flight';
  assert not public.current_user_is_pro(), 'trial is not account-wide Pro';
end $$;
-- billing columns are server-managed
do $$ begin
  begin
    update public.profiles set trial_flight_id = '66666666-0000-0000-0000-000000000002' where id = auth.uid();
    raise exception 'FAIL: user moved their trial';
  exception when insufficient_privilege then null; end;
  begin
    update public.profiles set trial_used = false where id = auth.uid();
    raise exception 'FAIL: user reset their trial';
  exception when insufficient_privilege then null; end;
  begin
    update public.profiles set pro_until = now() + interval '1 year' where id = auth.uid();
    raise exception 'FAIL: user extended pro_until';
  exception when insufficient_privilege then null; end;
  begin
    perform public.is_pro_for_flight(auth.uid(), '66666666-0000-0000-0000-000000000002');
    raise exception 'FAIL: is_pro_for_flight callable by clients';
  exception when insufficient_privilege then null; end;
  begin
    perform 1 from public.billing_events;
    raise exception 'FAIL: billing_events readable';
  exception when insufficient_privilege then null; end;
end $$;
commit;

-- untracking and re-adding a different flight never grants a second trial
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000006e1"}';
delete from public.user_flights where user_id = auth.uid();
insert into public.user_flights (flight_id) values ('66666666-0000-0000-0000-000000000003');
commit;
do $$ begin
  assert (select trial_flight_id from public.profiles where id = '00000000-0000-0000-0000-0000000006e1')
         = '66666666-0000-0000-0000-000000000001', 'trial stays on the first flight';
  assert not public.is_pro_for_flight('00000000-0000-0000-0000-0000000006e1', '66666666-0000-0000-0000-000000000003'),
    'no second trial';
end $$;
-- re-adding the trial flight brings its trial back (same flight)
insert into public.user_flights (user_id, flight_id)
values ('00000000-0000-0000-0000-0000000006e1', '66666666-0000-0000-0000-000000000001'),
       ('00000000-0000-0000-0000-0000000006e1', '66666666-0000-0000-0000-000000000002');

-- ===== pro user: no trial consumed =====
insert into public.user_flights (user_id, flight_id)
values ('00000000-0000-0000-0000-0000000006e2', '66666666-0000-0000-0000-000000000002');
do $$ begin
  assert (select not trial_used and trial_flight_id is null from public.profiles
           where id = '00000000-0000-0000-0000-0000000006e2'), 'Pro users keep their trial unused';
  assert public.is_pro_for_flight('00000000-0000-0000-0000-0000000006e2', '66666666-0000-0000-0000-000000000003'),
    'Pro is Pro on every flight';
end $$;

-- ===== counts and recipients use is_pro_for_flight =====
do $$ declare f record; r record; begin
  select tracker_count, pro_tracker_count into f from public.flights where id = '66666666-0000-0000-0000-000000000001';
  assert f.tracker_count = 1 and f.pro_tracker_count = 1, format('trial flight counts %s/%s', f.tracker_count, f.pro_tracker_count);
  select tracker_count, pro_tracker_count into f from public.flights where id = '66666666-0000-0000-0000-000000000002';
  assert f.tracker_count = 2 and f.pro_tracker_count = 1, format('flight 2 counts %s/%s', f.tracker_count, f.pro_tracker_count);
  select * into r from public.get_flight_recipients('66666666-0000-0000-0000-000000000001')
   where user_id = '00000000-0000-0000-0000-0000000006e1';
  assert r.is_pro, 'recipient is Pro on the trial flight';
  select * into r from public.get_flight_recipients('66666666-0000-0000-0000-000000000002')
   where user_id = '00000000-0000-0000-0000-0000000006e1';
  assert not r.is_pro, 'recipient is free on other flights';
end $$;

-- the pro user expires → flight 2 loses its Pro tracker (profile trigger recounts)
update public.profiles set plan = 'free', pro_until = null where id = '00000000-0000-0000-0000-0000000006e2';
do $$ begin
  assert (select pro_tracker_count from public.flights where id = '66666666-0000-0000-0000-000000000002') = 0,
    'pro count drops after expiry';
end $$;
-- an expired pro_until with plan still 'pro' is not Pro
update public.profiles set plan = 'pro', pro_until = now() - interval '1 minute' where id = '00000000-0000-0000-0000-0000000006e2';
do $$ begin
  assert not public.is_pro_for_flight('00000000-0000-0000-0000-0000000006e2', '66666666-0000-0000-0000-000000000002'),
    'expired pro_until is not Pro';
end $$;

-- ===== concurrent first inserts grant one trial (row lock + WHERE re-check) =====
insert into public.user_flights (user_id, flight_id)
values ('00000000-0000-0000-0000-0000000006e3', '66666666-0000-0000-0000-000000000002'),
       ('00000000-0000-0000-0000-0000000006e3', '66666666-0000-0000-0000-000000000003');
do $$ begin
  assert (select trial_flight_id from public.profiles where id = '00000000-0000-0000-0000-0000000006e3')
         = '66666666-0000-0000-0000-000000000002', 'multi-row insert: only the first row is the trial';
end $$;

-- deleting the trial flight row clears trial_flight_id but keeps trial_used
delete from public.flights where id = '66666666-0000-0000-0000-000000000003';
delete from public.flights where id = '66666666-0000-0000-0000-000000000002';
do $$ begin
  assert (select trial_flight_id is null and trial_used from public.profiles
           where id = '00000000-0000-0000-0000-0000000006e3'), 'trial cleared with its flight, still used';
end $$;

-- ===== archived inserts keep the trial; Pro-only events follow the trial flight =====
insert into auth.users (id) values ('00000000-0000-0000-0000-0000000006e4') on conflict (id) do nothing;
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
values ('66666666-0000-0000-0000-000000000004', '6E', '604', current_date - 1, 'DEL', 'BOM', 'landed',
        now() - interval '1 day', now() - interval '22 hours'),
       ('66666666-0000-0000-0000-000000000005', '6E', '605', current_date + 3, 'BOM', 'DEL', 'scheduled',
        now() + interval '3 days', now() + interval '3 days 2 hours'),
       ('66666666-0000-0000-0000-000000000006', '6E', '606', current_date + 4, 'DEL', 'BLR', 'scheduled',
        now() + interval '4 days', now() + interval '4 days 2 hours');
insert into public.flight_events (flight_id, type, severity, pro_only, dedupe_key)
values ('66666666-0000-0000-0000-000000000005', 'INBOUND_LATE', 'info', true, 'bill-e4-pro-5'),
       ('66666666-0000-0000-0000-000000000006', 'INBOUND_LATE', 'info', true, 'bill-e4-pro-6'),
       ('66666666-0000-0000-0000-000000000006', 'GATE_ASSIGNED', 'info', false, 'bill-e4-free-6');
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000006e4"}';
insert into public.user_flights (flight_id, archived_at) values ('66666666-0000-0000-0000-000000000004', now());
do $$ begin
  assert (select not trial_used and trial_flight_id is null from public.profiles where id = auth.uid()),
    'an archived insert does not use the trial';
end $$;
insert into public.user_flights (flight_id) values ('66666666-0000-0000-0000-000000000005');
insert into public.user_flights (flight_id) values ('66666666-0000-0000-0000-000000000006');
do $$ begin
  assert (select trial_flight_id from public.profiles where id = auth.uid())
         = '66666666-0000-0000-0000-000000000005', 'first active flight is the trial flight';
  assert exists (select 1 from public.flight_events where dedupe_key = 'bill-e4-pro-5'),
    'Pro-only event visible on the trial flight';
  assert not exists (select 1 from public.flight_events where dedupe_key = 'bill-e4-pro-6'),
    'Pro-only event hidden on a non-trial flight';
  assert exists (select 1 from public.flight_events where dedupe_key = 'bill-e4-free-6'),
    'free event visible on a non-trial flight';
end $$;
commit;

-- ===== billing_events: idempotency key =====
begin;
set local role service_role;
insert into public.billing_events (id, type, app_user_id) values ('evt_1', 'INITIAL_PURCHASE', '00000000-0000-0000-0000-0000000006e1');
insert into public.billing_events (id, type, app_user_id) values ('evt_1', 'INITIAL_PURCHASE', '00000000-0000-0000-0000-0000000006e1')
  on conflict (id) do nothing;
update public.billing_events set processed_at = now(), outcome = 'applied' where id = 'evt_1';
commit;
do $$ begin
  assert (select count(*) from public.billing_events where id = 'evt_1') = 1, 'one row per event id';
end $$;

select 'billing tests: OK' as result;
