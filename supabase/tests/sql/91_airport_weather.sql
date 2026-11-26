-- Airport Health (P2 contract section 1): airport_weather access + upsert, weather_refresh_targets(),
-- get_airport_health() levels / headline / Pro detail, fog_alert_candidates() and the poll pre-check.
-- FOG_ALERT event rows are added to the type check in migration 15, so the dedupe filter is tested here
-- with an ordinary event carrying the same key; the FOG_ALERT emission itself is tested in Deno.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000091a1'),  -- free
  ('00000000-0000-0000-0000-0000000091a2')   -- pro
on conflict (id) do nothing;
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-0000000091a1';
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-0000000091a2';

-- ===== upsert_airport_weather (service role) =====
begin;
set local role service_role;
do $$ declare n int; begin
  n := public.upsert_airport_weather(jsonb_build_array(
    jsonb_build_object('iata', 'ATQ', 'icao', 'VIAR', 'observed_at', now() - interval '20 minutes', 'fetched_at', now(),
      'raw_metar', 'VIAR 260030Z 00000KT 0300 R34/0450N FG VV001 08/08 Q1019 NOSIG', 'visibility_m', 300,
      'ceiling_ft', 100, 'wind_kts', 0, 'gust_kts', null, 'wx', 'FG', 'flight_category', 'LIFR', 'fog', true),
    jsonb_build_object('iata', 'AMD', 'icao', 'VAAH', 'observed_at', now() - interval '10 minutes',
      'raw_metar', 'VAAH 260100Z 36004KT 9999 FEW030 22/12 Q1015 NOSIG', 'visibility_m', 9999,
      'ceiling_ft', null, 'wind_kts', 4, 'wx', null, 'flight_category', 'VFR', 'fog', false),
    jsonb_build_object('iata', 'JAI', 'icao', 'VIJP', 'observed_at', now() - interval '25 minutes',
      'raw_metar', 'VIJP 260030Z 00000KT 1200 BR NSC 12/11 Q1018 NOSIG', 'visibility_m', 1200,
      'ceiling_ft', null, 'wind_kts', 0, 'wx', 'BR', 'flight_category', 'IFR', 'fog', false),
    jsonb_build_object('iata', 'CCU', 'icao', 'VECC', 'observed_at', now() - interval '4 hours',
      'raw_metar', 'VECC 252030Z 00000KT 0100 FG VV001 18/18 Q1014', 'visibility_m', 100,
      'ceiling_ft', 100, 'wind_kts', 0, 'wx', 'FG', 'flight_category', 'LIFR', 'fog', true),
    -- not in airports: skipped, the batch still succeeds
    jsonb_build_object('iata', 'QQQ', 'icao', 'QQQQ', 'observed_at', now(), 'raw_metar', 'QQQQ 260000Z 9999', 'fog', false)));
  assert n = 4, format('4 rows written, got %s', n);
  assert (select fetched_at is not null from public.airport_weather where iata = 'AMD'), 'fetched_at defaults to now()';

  -- an older report never replaces a newer one
  n := public.upsert_airport_weather(jsonb_build_array(jsonb_build_object('iata', 'ATQ', 'icao', 'VIAR',
    'observed_at', now() - interval '50 minutes', 'raw_metar', 'VIAR 252330Z 00000KT 2000 BR', 'visibility_m', 2000,
    'wx', 'BR', 'flight_category', 'IFR', 'fog', false)));
  assert n = 0, 'older report ignored';
  assert (select visibility_m = 300 and fog from public.airport_weather where iata = 'ATQ'), 'ATQ keeps the newer report';

  -- duplicates in one call: the newest wins, no "affect row a second time" error
  n := public.upsert_airport_weather(jsonb_build_array(
    jsonb_build_object('iata', 'pnq', 'icao', 'vapo', 'observed_at', now() - interval '40 minutes',
      'raw_metar', 'VAPO 260000Z 9999', 'visibility_m', 9999, 'flight_category', 'VFR', 'fog', false),
    jsonb_build_object('iata', 'PNQ', 'icao', 'VAPO', 'observed_at', now() - interval '10 minutes',
      'raw_metar', 'VAPO 260030Z 6000 HZ', 'visibility_m', 6000, 'wx', 'HZ', 'flight_category', 'MVFR', 'fog', false)));
  assert n = 1, 'one PNQ row';
  assert (select visibility_m = 6000 and icao = 'VAPO' from public.airport_weather where iata = 'PNQ'), 'newest PNQ kept, upper-cased';

  begin
    perform public.upsert_airport_weather('{"iata":"DEL"}'::jsonb);
    raise exception 'FAIL: non-array accepted';
  exception when invalid_parameter_value then null; end;
  begin
    perform public.upsert_airport_weather(jsonb_build_array(jsonb_build_object('iata', 'AMD', 'icao', 'VAAH',
      'observed_at', now(), 'raw_metar', 'x', 'visibility_m', 20000, 'fog', false)));
    raise exception 'FAIL: visibility above 9999 accepted';
  exception when check_violation then null; end;
end $$;
commit;

-- ===== flights =====
-- AMD: 8 departures in the last 3 h, delays 0,10,20,30,40,25,35,0 → avg 20, delayed (>15) 5/8 = 62.5 → 63 %
-- plus one cancelled today, and an untracked stale row (never re-polled) that must not count.
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, act_dep,
                            sched_arr, last_polled_at)
select ('91919191-0000-0000-0000-0000000001' || lpad(i::text, 2, '0'))::uuid, '6E', (9100 + i)::text, current_date,
       'AMD', 'DEL', 'departed', now() - interval '100 minutes', now() - interval '100 minutes' + make_interval(mins => d),
       now() + interval '1 hour', now() - interval '5 minutes'
  from unnest(array[0, 10, 20, 30, 40, 25, 35, 0]) with ordinality as t(d, i);
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr, last_polled_at)
values ('91919191-0000-0000-0000-000000000120', '6E', '9120', (now() at time zone 'Asia/Kolkata')::date, 'AMD', 'BOM', 'cancelled',
        now() + interval '2 hours', now() + interval '3 hours', now()),
       ('91919191-0000-0000-0000-000000000121', '6E', '9121', current_date, 'AMD', 'BOM', 'scheduled',
        now() - interval '60 minutes', now() + interval '20 minutes', now() - interval '5 hours');

-- PNQ: 7 departures (one still on the ground, 60 min after its slot) averaging 60 min late: too few for delay rules
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, act_dep,
                            sched_arr, last_polled_at)
select ('91919191-0000-0000-0000-0000000002' || lpad(i::text, 2, '0'))::uuid, 'QP', (9200 + i)::text, current_date,
       'PNQ', 'DEL', 'departed', now() - interval '150 minutes', now() - interval '90 minutes', now(), now()
  from generate_series(1, 6) as i;
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr, last_polled_at)
values ('91919191-0000-0000-0000-000000000207', 'QP', '9207', current_date, 'PNQ', 'DEL', 'scheduled',
        now() - interval '60 minutes', now() + interval '60 minutes', now() - interval '2 minutes');

-- IXC: 10 cancellations today
insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr)
select 'AI', (9300 + i)::text, (now() at time zone 'Asia/Kolkata')::date, 'IXC', 'DEL', 'cancelled',
       now() + interval '1 hour', now() + interval '2 hours'
  from generate_series(1, 10) as i;

-- ===== get_airport_health =====
begin;
set local role anon;
do $$ begin
  begin perform public.get_airport_health('ATQ'); raise exception 'FAIL: anon called get_airport_health';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000091a1"}';
do $$ declare h jsonb; begin
  begin perform 1 from public.airport_weather; raise exception 'FAIL: user read airport_weather';
  exception when insufficient_privilege then null; end;
  begin perform public.fog_alert_candidates(10); raise exception 'FAIL: user called fog_alert_candidates';
  exception when insufficient_privilege then null; end;
  begin perform public.weather_refresh_targets(); raise exception 'FAIL: user called weather_refresh_targets';
  exception when insufficient_privilege then null; end;
  begin perform public.upsert_airport_weather('[]'); raise exception 'FAIL: user called upsert_airport_weather';
  exception when insufficient_privilege then null; end;

  -- fog 300 m → severe; free users get no detail
  h := public.get_airport_health('atq');
  assert h ->> 'iata' = 'ATQ', format('iata %s', h);
  assert h ->> 'level' = 'severe', format('ATQ level %s', h);
  assert h ->> 'headline' = 'Fog: visibility 300 m', format('ATQ headline %s', h);
  assert h -> 'detail' = 'null'::jsonb and h ? 'detail', 'detail is null for free users';
  assert (h ->> 'updated_at')::timestamptz > now() - interval '30 minutes', 'updated_at from the observation';

  -- AMD: clear weather, avg 20 min over 8 flights → busy
  h := public.get_airport_health('AMD');
  assert h ->> 'level' = 'busy', format('AMD %s', h);
  assert h ->> 'headline' = 'Departures running 20 min late', format('AMD headline %s', h);

  -- PNQ: 7 flights, 60 min late on average, haze at 6000 m (MVFR) → still normal
  h := public.get_airport_health('PNQ');
  assert h ->> 'level' = 'normal' and h ->> 'headline' = 'Operating normally', format('PNQ %s', h);

  -- JAI: mist, 1200 m → disrupted
  h := public.get_airport_health('JAI');
  assert h ->> 'level' = 'disrupted' and h ->> 'headline' = 'Low visibility: 1200 m', format('JAI %s', h);

  -- IXC: 10 cancellations today → severe
  h := public.get_airport_health('IXC');
  assert h ->> 'level' = 'severe' and h ->> 'headline' = '10 flights cancelled today', format('IXC %s', h);

  -- CCU: the fog report is 4 h old → ignored
  h := public.get_airport_health('CCU');
  assert h ->> 'level' = 'normal', format('CCU stale weather %s', h);

  assert public.get_airport_health('ZZZ') is null, 'unknown airport → null';
  begin perform public.get_airport_health('D1'); raise exception 'FAIL: bad iata accepted';
  exception when invalid_parameter_value then null; end;
end $$;
rollback;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000091a2"}';
do $$ declare h jsonb; d jsonb; begin
  h := public.get_airport_health('AMD');
  d := h -> 'detail';
  assert d ->> 'avg_dep_delay_min' = '20', format('avg %s', d);
  assert d ->> 'delayed_pct' = '63', format('pct %s', d);
  assert d ->> 'cancelled_today' = '1', format('cancelled %s', d);
  assert d ->> 'sample_size' = '8', format('sample (stale untracked row excluded) %s', d);
  assert d -> 'weather' ->> 'visibility_m' = '9999' and d -> 'weather' ->> 'category' = 'VFR', format('weather %s', d);
  assert d -> 'weather' ->> 'fog' = 'false' and d -> 'weather' ->> 'wind_kts' = '4', format('weather %s', d);
  assert d -> 'weather' ? 'observed_at' and d -> 'weather' -> 'wx' = 'null'::jsonb, format('weather keys %s', d);

  d := public.get_airport_health('PNQ') -> 'detail';
  assert d ->> 'sample_size' = '7' and d ->> 'avg_dep_delay_min' = '60' and d ->> 'delayed_pct' = '100', format('PNQ %s', d);

  d := public.get_airport_health('ATQ') -> 'detail';
  assert d -> 'weather' ->> 'visibility_m' = '300' and d -> 'weather' ->> 'category' = 'LIFR', format('ATQ %s', d);
  assert d -> 'weather' ->> 'fog' = 'true' and d -> 'weather' ->> 'wx' = 'FG', format('ATQ %s', d);
  assert d ->> 'sample_size' = '0' and d -> 'avg_dep_delay_min' = 'null'::jsonb and d -> 'delayed_pct' = 'null'::jsonb,
    format('ATQ empty sample %s', d);

  -- stale weather: no weather block
  assert public.get_airport_health('CCU') -> 'detail' -> 'weather' = 'null'::jsonb, 'stale weather not shown';
end $$;
rollback;

-- ===== weather_refresh_targets =====
-- tracked: GAU in 5 h (dep), into IXB in 20 h (arr); out of range: TRV in 30 h; untracked: VNS in 2 h
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr, tracker_count)
values ('91919191-0000-0000-0000-000000000301', '6E', '9401', current_date, 'GAU', 'TRV', 'scheduled', now() + interval '5 hours', now() + interval '30 hours', 1),
       ('91919191-0000-0000-0000-000000000302', '6E', '9402', current_date, 'IXR', 'IXB', 'scheduled', now() + interval '25 hours', now() + interval '20 hours', 1),
       ('91919191-0000-0000-0000-000000000303', '6E', '9403', current_date, 'VNS', 'IXA', 'scheduled', now() + interval '2 hours', now() + interval '4 hours', 0);
begin;
set local role service_role;
do $$ declare t text[]; begin
  select array_agg(iata order by iata) into t from public.weather_refresh_targets();
  assert t @> array['DEL','BOM','BLR','HYD','MAA','CCU','COK','AMD','PNQ','GOI','GOX','LKO','ATQ','JAI'], format('metros %s', t);
  assert 'GAU' = any(t) and 'IXB' = any(t), format('tracked airports %s', t);
  assert not ('TRV' = any(t)) and not ('IXR' = any(t)), format('outside 24 h %s', t);
  assert not ('VNS' = any(t)) and not ('IXA' = any(t)), format('untracked %s', t);
  assert (select icao from public.weather_refresh_targets() where iata = 'DEL') = 'VIDP', 'icao returned';
end $$;
rollback;

-- ===== fog_alert_candidates =====
-- ATQ has fog (300 m, 20 min old).
--   F1 ATQ→DEL departs in 2 h            → dep candidate
--   F2 DEL→ATQ arrives in 3 h            → arr candidate
--   F3 ATQ→BOM departs in 6 h            → too early
--   F4 ATQ→BLR already departed          → none
--   F5 ATQ→DEL in 1 h but nobody tracks  → none
--   F6 ATQ→HYD cancelled                 → none
--   F7 ATQ→DEL 2 h past its slot, still on the ground (fog) → dep candidate
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, act_dep, sched_arr, tracker_count)
values ('91919191-0000-0000-0000-000000000401', 'AI', '9501', current_date, 'ATQ', 'DEL', 'scheduled', now() + interval '2 hours', null, now() + interval '3 hours', 1),
       ('91919191-0000-0000-0000-000000000402', 'AI', '9502', current_date, 'DEL', 'ATQ', 'scheduled', now() + interval '2 hours', null, now() + interval '3 hours', 2),
       ('91919191-0000-0000-0000-000000000403', 'AI', '9503', current_date, 'ATQ', 'BOM', 'scheduled', now() + interval '6 hours', null, now() + interval '8 hours', 1),
       ('91919191-0000-0000-0000-000000000404', 'AI', '9504', current_date, 'ATQ', 'BLR', 'departed', now() - interval '10 minutes', now() - interval '5 minutes', now() + interval '3 hours', 1),
       ('91919191-0000-0000-0000-000000000405', 'AI', '9505', current_date, 'ATQ', 'DEL', 'scheduled', now() + interval '1 hour', null, now() + interval '2 hours', 0),
       ('91919191-0000-0000-0000-000000000406', 'AI', '9506', current_date, 'ATQ', 'HYD', 'cancelled', now() + interval '1 hour', null, now() + interval '3 hours', 1),
       ('91919191-0000-0000-0000-000000000407', 'AI', '9507', current_date, 'ATQ', 'DEL', 'scheduled', now() - interval '2 hours', null, now() - interval '1 hour', 1);

begin;
set local role service_role;
do $$ declare c record; n int; k text; begin
  select count(*) into n from public.fog_alert_candidates(100) where flight_id::text like '91919191-%';
  assert n = 3, format('3 candidates, got %s', n);

  select * into c from public.fog_alert_candidates(100) where flight_id = '91919191-0000-0000-0000-000000000401';
  assert c.side = 'dep' and c.airport = 'ATQ' and c.visibility_m = 300 and c.wx = 'FG', format('F1 %s', row_to_json(c));
  k := '91919191-0000-0000-0000-000000000401:FOG:ATQ:'
       || to_char(timezone('Asia/Kolkata', (select sched_dep from public.flights where id = '91919191-0000-0000-0000-000000000401')), 'YYYY-MM-DD');
  assert c.dedupe_key = k, format('key %s vs %s', c.dedupe_key, k);

  select * into c from public.fog_alert_candidates(100) where flight_id = '91919191-0000-0000-0000-000000000402';
  assert c.side = 'arr' and c.airport = 'ATQ', format('F2 %s', row_to_json(c));
  assert c.dedupe_key like '91919191-0000-0000-0000-000000000402:FOG:ATQ:____-__-__', format('F2 key %s', c.dedupe_key);

  assert exists (select 1 from public.fog_alert_candidates(100) where flight_id = '91919191-0000-0000-0000-000000000407' and side = 'dep'),
    'F7 still on the ground';
  assert not exists (select 1 from public.fog_alert_candidates(100)
                      where flight_id in ('91919191-0000-0000-0000-000000000403', '91919191-0000-0000-0000-000000000404',
                                          '91919191-0000-0000-0000-000000000405', '91919191-0000-0000-0000-000000000406')),
    'F3-F6 excluded';
  assert (select count(*) from public.fog_alert_candidates(1)) = 1, 'limit';

  -- an event with the same dedupe key (already sent) removes the candidate
  insert into public.flight_events (flight_id, type, severity, dedupe_key)
  values ('91919191-0000-0000-0000-000000000401', 'DELAY', 'critical', k);
  assert not exists (select 1 from public.fog_alert_candidates(100) where flight_id = '91919191-0000-0000-0000-000000000401'),
    'already alerted';

  -- the poll pre-check wakes poll-flights for fog alone
  update public.flights set next_poll_at = null, checkin_closing_notified = true where next_poll_at is not null or checkin_closes_at is not null;
  assert not exists (select 1 from public.due_checkin_flights(1)), 'no check-in reminder due';
  assert public.poll_work_due(), 'fog alert due → poll';

  -- visibility improves (1500 m, no FG) → nothing more to send
  perform public.upsert_airport_weather(jsonb_build_array(jsonb_build_object('iata', 'ATQ', 'icao', 'VIAR',
    'observed_at', now(), 'raw_metar', 'VIAR 260300Z 00000KT 1500 BR', 'visibility_m', 1500, 'wx', 'BR',
    'flight_category', 'IFR', 'fog', false)));
  assert not exists (select 1 from public.fog_alert_candidates(100) where flight_id::text like '91919191-%'), 'fog lifted';
  assert not public.poll_work_due(), 'nothing due';

  -- visibility under 1000 m without FG (smoke / haze) still alerts; an old report does not
  update public.airport_weather set visibility_m = 800, wx = 'FU' where iata = 'ATQ';
  assert exists (select 1 from public.fog_alert_candidates(100) where flight_id = '91919191-0000-0000-0000-000000000402'), '800 m alerts';
  update public.airport_weather set observed_at = now() - interval '3 hours' where iata = 'ATQ';
  assert not exists (select 1 from public.fog_alert_candidates(100) where flight_id::text like '91919191-%'), 'old report ignored';
end $$;
rollback;

select 'airport weather tests: OK' as result;
