-- SQL behaviour tests: triggers, RLS, grants, poller lease. Fails loudly (RAISE) on any regression.
\set QUIET on
set client_min_messages = warning;

-- fixtures (as superuser = like service_role)
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'),  -- free user A
  ('00000000-0000-0000-0000-00000000000b'),  -- pro user B
  ('00000000-0000-0000-0000-00000000000c');  -- free user C
update public.profiles set plan = 'pro' where id = '00000000-0000-0000-0000-00000000000b';
update public.profiles set trial_used = true;

insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr,
                            alert_state, prediction, next_poll_at)
values ('11111111-1111-1111-1111-111111111111', '6E', '2341', current_date + 1, 'DEL', 'BOM', 'scheduled',
        now() + interval '20 hours', now() + interval '22 hours', '{"depGate":"12"}',
        '{"lateMin": 25, "predictedDep": "x", "alertedBucket": 30, "inboundReg": "VT-X"}', now() + interval '1 hour'),
       ('22222222-2222-2222-2222-222222222222', 'AI', '101', current_date, 'DEL', 'JFK', 'scheduled',
        now() + interval '2 hours', now() + interval '18 hours', '{}', null, now() - interval '1 minute');

do $$ begin
  assert (select count(*) from public.profiles) = 3, 'profiles auto-created by auth trigger';
end $$;

-- ===== user A (free) tracks flight 1 =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000000a"}';
insert into public.user_flights (flight_id, pnr, source) values ('11111111-1111-1111-1111-111111111111', 'ABC123', 'pdf');
do $$ declare v jsonb; begin
  -- free user sees flight via view, but NOT the Pro prediction
  select to_jsonb(fv) into v from public.flight_view fv where id = '11111111-1111-1111-1111-111111111111';
  assert v is not null, 'view returns the flight';
  assert v ->> 'prediction' is null, 'prediction masked for free user';
  assert (v ->> 'has_pro_insight')::boolean, 'paywall teaser flag visible';
  assert v ->> 'dep_tz' = 'Asia/Kolkata', 'airport join works';
  assert not (v ? 'alert_state'), 'internal columns not exposed';
end $$;
-- direct table access to flights must be denied
do $$ begin
  begin
    perform 1 from public.flights limit 1;
    raise exception 'FAIL: authenticated could read public.flights';
  exception when insufficient_privilege then null; end;
end $$;
-- cannot escalate own plan
do $$ begin
  begin
    update public.profiles set plan = 'pro' where id = auth.uid();
    raise exception 'FAIL: user changed own plan';
  exception when insufficient_privilege then null; end;
end $$;
-- can edit display_name
update public.profiles set display_name = 'Asha' where id = auth.uid();
-- cannot read push_log / provider_calls / call poller RPC
do $$ begin
  begin perform 1 from public.provider_calls; raise exception 'FAIL: provider_calls readable';
  exception when insufficient_privilege then null; end;
  begin perform public.claim_due_flights(1, 10); raise exception 'FAIL: user could claim flights';
  exception when insufficient_privilege then null; end;
end $$;
-- register a device via RPC
select public.register_device('tokenA_' || repeat('x', 30), '0.1.0', '15', 'en-IN', 'Asia/Kolkata') is not null as ok \gset
commit;

-- ===== user B (pro) tracks flight 1 and sees prediction =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000000b"}';
insert into public.user_flights (flight_id) values ('11111111-1111-1111-1111-111111111111');
select public.register_device('tokenB_' || repeat('y', 30));
do $$ declare v jsonb; begin
  select prediction into v from public.flight_view where id = '11111111-1111-1111-1111-111111111111';
  assert v ->> 'lateMin' = '25', 'pro sees prediction';
  assert not (v ? 'alertedBucket') and not (v ? 'inboundReg'), 'internal prediction keys stripped';
  -- B must not see A's tracker rows or devices
  assert (select count(*) from public.user_flights) = 1, 'only own user_flights visible';
  assert (select count(*) from public.devices) = 1, 'only own devices visible';
end $$;
commit;

-- ===== counters maintained by triggers =====
do $$ declare f record; begin
  select tracker_count, pro_tracker_count into f from public.flights where id = '11111111-1111-1111-1111-111111111111';
  assert f.tracker_count = 2 and f.pro_tracker_count = 1, format('counts wrong: %s/%s', f.tracker_count, f.pro_tracker_count);
end $$;
-- B downgrades → pro count drops via profile trigger
update public.profiles set plan = 'free' where id = '00000000-0000-0000-0000-00000000000b';
do $$ begin
  assert (select pro_tracker_count from public.flights where id = '11111111-1111-1111-1111-111111111111') = 0, 'pro count after downgrade';
end $$;
update public.profiles set plan = 'pro' where id = '00000000-0000-0000-0000-00000000000b';

-- recipients RPC: 2 users x 1 device each
do $$ begin
  assert (select count(*) from public.get_flight_recipients('11111111-1111-1111-1111-111111111111')) = 2, 'recipients';
  assert (select bool_or(is_pro) from public.get_flight_recipients('11111111-1111-1111-1111-111111111111')), 'pro flag in recipients';
end $$;

-- device token moves to another account on the same phone
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000000c"}';
select public.register_device('tokenA_' || repeat('x', 30));
commit;
do $$ begin
  assert (select user_id from public.devices where fcm_token like 'tokenA_%') = '00000000-0000-0000-0000-00000000000c', 'token reassigned';
end $$;

-- ===== archiving stops polling interest =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000000a"}';
update public.user_flights set archived_at = now() where flight_id = '11111111-1111-1111-1111-111111111111';
-- flight_id cannot be changed
do $$ begin
  begin
    update public.user_flights set flight_id = '22222222-2222-2222-2222-222222222222';
    raise exception 'FAIL: flight_id updatable';
  exception when insufficient_privilege then null; end;
end $$;
commit;
do $$ begin
  assert (select tracker_count from public.flights where id = '11111111-1111-1111-1111-111111111111') = 1, 'archive decrements';
end $$;

-- ===== poller lease: due + tracked only, never twice =====
insert into public.user_flights (user_id, flight_id) values ('00000000-0000-0000-0000-00000000000c', '22222222-2222-2222-2222-222222222222');
do $$ declare n int; begin
  select count(*) into n from public.claim_due_flights(10, 120);
  assert n = 1, format('expected 1 due flight (flight 2), got %s', n);      -- flight 1 next_poll is in 1h
  select count(*) into n from public.claim_due_flights(10, 120);
  assert n = 0, 'leased flight must not be claimed again';
end $$;

-- ===== free tracking limit (10 active) =====
do $$ declare i int; begin
  for i in 1..9 loop
    insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_arr)
    values ('SG', (100 + i)::text, current_date + 2, 'BOM', 'GOX', now() + interval '2 days');
    insert into public.user_flights (user_id, flight_id)
    select '00000000-0000-0000-0000-00000000000c', id from public.flights where carrier_iata = 'SG' and flight_number = (100 + i)::text;
  end loop;
  begin
    insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, sched_arr)
    values ('SG', '999', current_date + 2, 'BOM', 'GOX', now() + interval '2 days');
    insert into public.user_flights (user_id, flight_id)
    select '00000000-0000-0000-0000-00000000000c', id from public.flights where carrier_iata = 'SG' and flight_number = '999';
    raise exception 'FAIL: 11th active flight allowed for free user';
  exception when raise_exception then
    if sqlerrm <> 'tracking_limit_reached' then raise; end if;
  end;
end $$;

-- ===== exactly-once events =====
insert into public.flight_events (flight_id, type, severity, dedupe_key)
values ('11111111-1111-1111-1111-111111111111', 'GATE_CHANGE', 'critical', 'k1') on conflict (dedupe_key) do nothing;
insert into public.flight_events (flight_id, type, severity, dedupe_key)
values ('11111111-1111-1111-1111-111111111111', 'GATE_CHANGE', 'critical', 'k1') on conflict (dedupe_key) do nothing;
do $$ begin assert (select count(*) from public.flight_events where dedupe_key = 'k1') = 1, 'dedupe'; end $$;

-- anon sees nothing
begin;
set local role anon;
do $$ begin
  begin perform 1 from public.flight_view; raise exception 'FAIL: anon read flight_view';
  exception when insufficient_privilege then null; end;
end $$;
commit;

select 'rls/trigger tests: OK' as result;

-- ===== review fixes =====
-- is_pro(uuid) is not callable by clients; current_user_is_pro() answers only for the caller
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-00000000000b"}';
do $$ begin
  begin perform public.is_pro('00000000-0000-0000-0000-00000000000a'); raise exception 'FAIL: is_pro callable';
  exception when insufficient_privilege then null; end;
  assert public.current_user_is_pro(), 'pro user B sees own pro status';
end $$;
-- muted device stays muted after re-register
update public.devices set notifications_enabled = false where fcm_token like 'tokenB_%';
select public.register_device('tokenB_' || repeat('y', 30)) is not null as ok \gset
do $$ begin
  assert not (select notifications_enabled from public.devices where fcm_token like 'tokenB_%'), 'mute kept';
end $$;
-- notify_prefs must be a small object of booleans
do $$ begin
  begin
    update public.user_flights set notify_prefs = '{"DEPARTED": "nope"}' where user_id = auth.uid();
    raise exception 'FAIL: non-boolean notify_prefs accepted';
  exception when check_violation then null; end;
  update public.user_flights set notify_prefs = '{"DEPARTED": false}' where user_id = auth.uid();
end $$;
commit;
do $$ begin
  assert (select count(*) from public.flight_events where type = 'SCHEDULE_CHANGE') = 0;
  insert into public.flight_events (flight_id, type, severity, dedupe_key)
  values ('11111111-1111-1111-1111-111111111111', 'SCHEDULE_CHANGE', 'critical', 'sc1');
  insert into public.flight_status_history (flight_id, source, changed_fields, snapshot)
  values ('11111111-1111-1111-1111-111111111111', 'webhook_stale', '{}', '{}');
end $$;
select 'review-fix tests: OK' as result;
