-- P2 wave 2 calendar feed (docs/P2_WAVE2_CONTRACTS.md section 3): profiles.calendar_token,
-- my_calendar_url(), rotate_calendar_token(), calendar_feed() (service role only).
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000099c1'),
  ('00000000-0000-0000-0000-0000000099c2')
on conflict (id) do nothing;
update public.profiles set trial_used = true
 where id in ('00000000-0000-0000-0000-0000000099c1', '00000000-0000-0000-0000-0000000099c2');

-- C1 future, C2 10 days ago, C3 40 days ago (outside the feed), C4 future but archived, C5 tracked by c2 only
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, est_dep, sched_arr,
                            dep_terminal, dep_gate, arr_terminal, baggage_belt, airline_name)
values ('99c99c99-0000-0000-0000-000000000001', '6E', '9901', current_date + 2, 'DEL', 'BOM', 'scheduled',
        '2030-01-02 08:00+00', '2030-01-02 08:25+00', '2030-01-02 10:10+00', '1', '42B', '2', '5', 'IndiGo'),
       ('99c99c99-0000-0000-0000-000000000002', 'AI', '9902', current_date - 10, 'BOM', 'DEL', 'arrived',
        now() - interval '10 days', null, now() - interval '10 days' + interval '2 hours', null, null, null, null, 'Air India'),
       ('99c99c99-0000-0000-0000-000000000003', 'AI', '9903', current_date - 40, 'BOM', 'DEL', 'arrived',
        now() - interval '40 days', null, now() - interval '40 days' + interval '2 hours', null, null, null, null, null),
       ('99c99c99-0000-0000-0000-000000000004', 'SG', '9904', current_date + 5, 'DEL', 'GOI', 'scheduled',
        now() + interval '5 days', null, now() + interval '5 days 2 hours', null, null, null, null, null),
       ('99c99c99-0000-0000-0000-000000000005', 'QP', '9905', current_date + 5, 'BLR', 'DEL', 'scheduled',
        now() + interval '5 days', null, now() + interval '5 days 3 hours', null, null, null, null, null);
-- airports come from seed.sql
insert into public.user_flights (user_id, flight_id, archived_at) values
  ('00000000-0000-0000-0000-0000000099c1', '99c99c99-0000-0000-0000-000000000001', null),
  ('00000000-0000-0000-0000-0000000099c1', '99c99c99-0000-0000-0000-000000000002', null),
  ('00000000-0000-0000-0000-0000000099c1', '99c99c99-0000-0000-0000-000000000003', null),
  ('00000000-0000-0000-0000-0000000099c1', '99c99c99-0000-0000-0000-000000000004', now()),
  ('00000000-0000-0000-0000-0000000099c2', '99c99c99-0000-0000-0000-000000000005', null);

-- ===== no base URL configured → not_configured =====
delete from public.app_config where key = 'supabase_url';
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099c1"}';
do $$ begin
  begin
    perform public.my_calendar_url();
    raise exception 'FAIL: URL without a base';
  exception when object_not_in_prerequisite_state then
    assert sqlerrm = 'not_configured', sqlerrm;
  end;
end $$;
rollback;
insert into public.app_config (key, value) values ('supabase_url', 'https://abc.supabase.co/');

-- ===== my_calendar_url / rotate_calendar_token =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000099c1"}';
do $$ declare u1 text; u2 text; u3 text; begin
  u1 := public.my_calendar_url();
  assert u1 ~ '^https://abc\.supabase\.co/functions/v1/calendar-feed\?t=[A-Za-z0-9]{32}$', u1;
  u2 := public.my_calendar_url();
  assert u1 = u2, 'stable URL';
  u3 := public.rotate_calendar_token();
  assert u3 ~ '^https://abc\.supabase\.co/functions/v1/calendar-feed\?t=[A-Za-z0-9]{32}$', u3;
  assert u3 <> u1, 'rotated';
  assert public.my_calendar_url() = u3, 'the new URL sticks';
  begin
    update public.profiles set calendar_token = repeat('a', 32) where id = auth.uid();
    raise exception 'FAIL: calendar_token is user-editable';
  exception when insufficient_privilege then null; end;
  begin
    perform public.calendar_feed(substring(u3 from '[A-Za-z0-9]{32}$'));
    raise exception 'FAIL: clients can call calendar_feed';
  exception when insufficient_privilege then null; end;
end $$;
commit;
begin;
set local role anon;
do $$ begin
  begin
    perform public.my_calendar_url();
    raise exception 'FAIL: anon got a calendar URL';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- ===== calendar_feed =====
do $$ declare t text; feed jsonb; e jsonb; begin
  select calendar_token into t from public.profiles where id = '00000000-0000-0000-0000-0000000099c1';
  feed := public.calendar_feed(t);
  assert jsonb_typeof(feed) = 'array', 'array';
  assert jsonb_array_length(feed) = 2, 'last 30 days + future, not archived, own flights only: ' || feed::text;
  assert feed -> 0 ->> 'flight_id' = '99c99c99-0000-0000-0000-000000000002', 'oldest first';
  e := feed -> 1;
  assert e ->> 'flight_id' = '99c99c99-0000-0000-0000-000000000001', 'future flight';
  assert e ->> 'carrier' = '6E' and e ->> 'number' = '9901', 'number';
  assert e ->> 'est_dep' = '2030-01-02T08:25:00Z', 'UTC ISO: ' || (e ->> 'est_dep');
  assert e ->> 'sched_arr' = '2030-01-02T10:10:00Z', 'UTC ISO arr';
  assert e ->> 'act_dep' is null, 'null stays null';
  assert e -> 'dep' ->> 'iata' = 'DEL' and e -> 'dep' ->> 'terminal' = '1' and e -> 'dep' ->> 'gate' = '42B', 'dep';
  assert e -> 'dep' ->> 'name' is not null, 'airport name from airports';
  assert e -> 'arr' ->> 'belt' = '5', 'belt';
  assert e ? 'updated_at', 'updated_at';
  assert not (e ? 'pnr') and not (e ? 'user_id'), 'no personal fields';
  -- unknown / malformed tokens → null
  assert public.calendar_feed(repeat('Z', 32)) is null, 'unknown token';
  assert public.calendar_feed('short') is null, 'malformed token';
  assert public.calendar_feed(null) is null, 'null token';
  -- a user with no flights gets an empty array, not null
  perform public.new_calendar_token('00000000-0000-0000-0000-0000000099c2');
  delete from public.user_flights where user_id = '00000000-0000-0000-0000-0000000099c2';
  select calendar_token into t from public.profiles where id = '00000000-0000-0000-0000-0000000099c2';
  assert public.calendar_feed(t) = '[]'::jsonb, 'empty feed';
end $$;

-- the same result in any session time zone
set timezone = 'Asia/Kolkata';
do $$ declare t text; begin
  select calendar_token into t from public.profiles where id = '00000000-0000-0000-0000-0000000099c1';
  assert public.calendar_feed(t) -> 1 ->> 'est_dep' = '2030-01-02T08:25:00Z', 'UTC regardless of session tz';
end $$;
reset timezone;

delete from public.app_config where key = 'supabase_url';

\echo '99_calendar: ok'
