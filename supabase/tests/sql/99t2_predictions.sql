-- P3 delay + gate prediction (docs/P3_CONTRACTS.md B1, B2). Hand-checked empirical-Bayes numbers (k = 10,
-- prior mean 12 min, prior tail g(t) = 0.6·e^(−t/25)), adjustments, the 15-minute cache, availability window,
-- Pro gating, labels in en / hi / hinglish, and gate shares.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values
  ('00000000-0000-0000-0000-0000000a3b01'),  -- Pro
  ('00000000-0000-0000-0000-0000000a3b02')   -- free, trial used
on conflict (id) do nothing;
update public.profiles set plan = 'pro', pro_until = now() + interval '30 days', trial_used = true
 where id = '00000000-0000-0000-0000-0000000a3b01';
update public.profiles set trial_used = true where id = '00000000-0000-0000-0000-0000000a3b02';

-- One finished leg IXA→IXB, scheduled 08:00 UTC (13:30 IST, "day") on p_date.
create function pg_temp.leg(p_carrier text, p_number text, p_date date, p_delay int, p_gate text default null,
                            p_status text default 'arrived', p_dep text default 'IXA', p_arr text default 'IXB',
                            p_terminal text default '1') returns void
language plpgsql as $$
declare fid uuid; d timestamptz := p_date::timestamp at time zone 'UTC' + interval '8 hours';
begin
  insert into public.flights (carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status)
  values (p_carrier, p_number, p_date, p_dep, p_arr, p_status) returning id into fid;
  insert into public.flight_leg_history (flight_id, carrier, number, date, dep, arr, sched_dep, act_dep,
                                         dep_delay_min, dep_terminal, dep_gate, final_status)
  values (fid, p_carrier, p_number, p_date, p_dep, p_arr, d,
          case when p_status = 'cancelled' then null else d + make_interval(mins => p_delay) end,
          case when p_status = 'cancelled' then null else p_delay end, p_terminal, p_gate, p_status);
end $$;

-- Route IXA→IXB, last 90 days: 10 legs of other carriers on time (0), 10 other ZZ numbers 20 min late,
-- 10 legs of ZZ 7001 40 min late (gates: 42B ×7, 41, 40 (most recent of the two), none).
-- Not counted: cancelled and diverted legs, a leg 100 days ago, the same number on another route.
do $$ declare i int; begin
  for i in 0 .. 9 loop
    perform pg_temp.leg('UK', (7100 + i)::text, current_date - 1 - i, 0);
    perform pg_temp.leg('ZZ', (7200 + i)::text, current_date - 1 - i, 20);
    perform pg_temp.leg('ZZ', '7001', current_date - 1 - i, 40,
                        case when i <= 6 then '42B' when i = 7 then '41' when i = 8 then null else '40' end);
  end loop;
  -- i = 9 is the oldest; make "40" the more recent of the two single-use gates
  update public.flight_leg_history set date = current_date - 3 where number = '7001' and dep_gate = '40';
  perform pg_temp.leg('ZZ', '7001', current_date - 20, 300, null, 'cancelled');
  perform pg_temp.leg('ZZ', '7001', current_date - 21, 300, null, 'diverted');
  perform pg_temp.leg('ZZ', '7001', current_date - 100, 300, '99');
  perform pg_temp.leg('ZZ', '7001', current_date - 50, 300, null, 'arrived', 'IXA', 'IXZ');
end $$;

-- P1 tomorrow, P2 with a late inbound aircraft, P3 in 2 h with fog at IXA, P4 too early, P5 departed,
-- P6 another number (no own history, no gate history).
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status, sched_dep, sched_arr,
                            act_dep, prediction)
values ('a3b3b3b3-0000-0000-0000-000000000001', 'ZZ', '7001', '2031-01-01', 'IXA', 'IXB', 'scheduled',
        now() + interval '24 hours', now() + interval '26 hours', null, null),
       ('a3b3b3b3-0000-0000-0000-000000000002', 'ZZ', '7001', '2031-01-02', 'IXA', 'IXB', 'scheduled',
        now() + interval '30 hours', now() + interval '32 hours', null, '{"lateMin": 50}'),
       ('a3b3b3b3-0000-0000-0000-000000000003', 'ZZ', '7001', '2031-01-03', 'IXA', 'IXB', 'scheduled',
        now() + interval '2 hours', now() + interval '4 hours', null, null),
       ('a3b3b3b3-0000-0000-0000-000000000004', 'ZZ', '7001', '2031-01-04', 'IXA', 'IXB', 'scheduled',
        now() + interval '80 hours', now() + interval '82 hours', null, null),
       ('a3b3b3b3-0000-0000-0000-000000000005', 'ZZ', '7001', '2031-01-05', 'IXA', 'IXB', 'departed',
        now() + interval '1 hour', now() + interval '3 hours', now(), null),
       ('a3b3b3b3-0000-0000-0000-000000000006', 'ZZ', '7002', '2031-01-01', 'IXA', 'IXB', 'scheduled',
        now() + interval '24 hours', now() + interval '26 hours', null, null);
insert into public.user_flights (user_id, flight_id)
select u, f.id from unnest(array['00000000-0000-0000-0000-0000000a3b01', '00000000-0000-0000-0000-0000000a3b02']::uuid[]) u,
                    public.flights f where f.id::text like 'a3b3b3b3-%';
insert into public.airport_weather (iata, icao, observed_at, raw_metar, visibility_m, fog, wx)
values ('IXA', 'VEAT', now(), 'VEAT 000000Z 00000KT 0800 FG', 800, true, 'FG')
on conflict (iata) do update set observed_at = excluded.observed_at, visibility_m = 800, fog = true, wx = 'FG';

-- ===== gating and availability =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3b02"}';
do $$ begin
  begin
    perform public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000001');
    raise exception 'FAIL: free user got a prediction';
  exception when insufficient_privilege then assert sqlerrm = 'pro_required', sqlerrm; end;
  begin
    perform public.gate_prediction('a3b3b3b3-0000-0000-0000-000000000001');
    raise exception 'FAIL: free user got a gate prediction';
  exception when insufficient_privilege then assert sqlerrm = 'pro_required', sqlerrm; end;
  begin
    perform public.delay_prediction_core(f) from public.flights f limit 1;
    raise exception 'FAIL: clients can call the core';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3b01"}';
do $$ declare r jsonb; begin
  assert public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000004') is null, 'more than 72 h ahead';
  assert public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000005') is null, 'departed';

  -- P1: route m = (30·20 + 10·12)/40 = 18; carrier+route (20·30 + 10·18)/30 = 26; flight (10·40 + 10·26)/20 = 33.
  -- P(>=15): g(15) = 0.329287 → route (20 + 3.29287)/40 = 0.582322 → carrier (20 + 5.82322)/30 = 0.860774
  --          → flight (10 + 8.60774)/20 = 0.930387. P(>=60): g(60) = 0.054431 → 0.013608 → 0.004536 → 0.002268 → 0.01.
  r := public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000001');
  assert (r ->> 'p_delay_15')::numeric = 0.93, r::text;
  assert (r ->> 'p_delay_60')::numeric = 0.01, r::text;
  assert (r ->> 'expected_delay_min')::int = 33, r::text;
  assert r ->> 'confidence' = 'high' and (r ->> 'samples')::int = 30 and r ->> 'model' = 'rules-v1', r::text;
  assert r -> 'factors' = '[{"key": "history", "effect_min": 33, "label": "This flight is late 93% of the time"}]'::jsonb,
         r::text;
  assert r ->> 'computed_at' ~ '^\d{4}-\d\d-\d\dT\d\d:\d\d:00Z$', r::text;
  assert public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000001', 'hi') -> 'factors' -> 0 ->> 'label'
         = 'यह फ़्लाइट 93% बार लेट होती है';

  -- P2: inbound 50 min late is a floor: expected 50 = history 33 + inbound 17; P(>=15) = 0.95, P(>=60) unchanged.
  r := public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000002', 'hinglish');
  assert (r ->> 'expected_delay_min')::int = 50 and (r ->> 'p_delay_15')::numeric = 0.95
         and (r ->> 'p_delay_60')::numeric = 0.01, r::text;
  assert r -> 'factors' -> 1 = '{"key": "inbound", "effect_min": 17, "label": "Aapka plane 50 min late chal raha hai"}'::jsonb,
         r::text;

  -- P3: fog (800 m) at IXA within 6 h: +15 → 48. Threshold 15 − 15 = 0: all legs count →
  --     (30 + 6)/40 = 0.9 → (20 + 9)/30 → (10 + 9.667)/20 = 0.983 → capped 0.95.
  r := public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000003');
  assert (r ->> 'expected_delay_min')::int = 48 and (r ->> 'p_delay_15')::numeric = 0.95, r::text;
  assert r -> 'factors' -> 1 = '{"key": "weather", "effect_min": 15, "label": "Fog at IXA"}'::jsonb, r::text;
  assert public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000003', 'hi') -> 'factors' -> 1 ->> 'label' = 'IXA पर कोहरा';

  -- P6: no own legs → the carrier + route estimate 26, labelled by route; unknown language → English.
  r := public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000006', 'fr');
  assert (r ->> 'expected_delay_min')::int = 26 and (r ->> 'p_delay_15')::numeric = 0.86, r::text;
  assert r -> 'factors' -> 0 ->> 'label' = 'IXA→IXB flights are late 86% of the time', r::text;

  -- the factors always add up to the expected delay (history takes the rounding remainder)
  for r in select public.delay_prediction(x.id::uuid)
             from (values ('a3b3b3b3-0000-0000-0000-000000000001'), ('a3b3b3b3-0000-0000-0000-000000000002'),
                          ('a3b3b3b3-0000-0000-0000-000000000003'), ('a3b3b3b3-0000-0000-0000-000000000006')) x(id) loop
    assert (select sum((f ->> 'effect_min')::int) from jsonb_array_elements(r -> 'factors') f)
         = (r ->> 'expected_delay_min')::int, 'factors sum: ' || r::text;
    assert r -> 'factors' -> 0 ->> 'key' = 'history', r::text;
  end loop;
end $$;

-- cache: a change within 15 minutes is not seen, an older cache entry is recomputed
reset role;
update public.flights set prediction = '{"lateMin": 90}' where id = 'a3b3b3b3-0000-0000-0000-000000000001';
set local role authenticated;
do $$ begin
  assert (public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000001') ->> 'expected_delay_min')::int = 33, 'cached';
end $$;
reset role;
update public.delay_predictions set computed_at = now() - interval '16 minutes'
 where flight_id = 'a3b3b3b3-0000-0000-0000-000000000001';
set local role authenticated;
do $$ declare r jsonb; begin
  r := public.delay_prediction('a3b3b3b3-0000-0000-0000-000000000001');
  assert (r ->> 'expected_delay_min')::int = 90 and (r ->> 'p_delay_60')::numeric = 0.95, 'recomputed: ' || r::text;
end $$;
rollback;

-- ===== gate prediction =====
begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000a3b01"}';
do $$ declare r jsonb; begin
  r := public.gate_prediction('a3b3b3b3-0000-0000-0000-000000000001');
  assert r = '{"gate": "42B", "terminal": "1", "share": 0.78, "count": 7, "samples": 9,
               "alternatives": [{"gate": "40", "share": 0.11}, {"gate": "41", "share": 0.11}]}'::jsonb, r::text;
  assert public.gate_prediction('a3b3b3b3-0000-0000-0000-000000000006') is null, 'no history';
end $$;
reset role;
-- two samples are not enough
delete from public.flight_leg_history where number = '7001' and dep_gate = '42B';
set local role authenticated;
do $$ begin
  assert public.gate_prediction('a3b3b3b3-0000-0000-0000-000000000001') is null, 'fewer than 4 samples';
end $$;
rollback;

\echo '99t2_predictions: ok'
