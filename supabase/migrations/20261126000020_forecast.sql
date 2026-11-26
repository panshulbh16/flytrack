-- P2 wave 2, arrival forecast + on-time history (docs/P2_WAVE2_CONTRACTS.md section 4).
-- flight_forecast(p_flight_id): for callers who can read the flight; the detail is only filled in for
-- callers who are Pro for that flight (is_pro_for_flight, so the first-flight trial counts).
--
-- History: finished legs in flight_leg_history from the last 60 days on the same route, first for the
-- same carrier + number, then the same carrier, then any carrier ("basis" flight | carrier_route | route),
-- taking the first level with at least 5 legs (else the route level). Level 1 also requires the route
-- because one flight number can fly several legs with different block times.
--   samples            legs in the chosen level, cancelled included
--   on_time_pct        departure delay <= 15 min, of flown legs with a known delay (as in my_passport)
--   avg_dep_delay_min  average departure delay of flown legs
--   median_arr_delay_min, cancelled
-- Forecast (arr_p50 / arr_p80, ISO-8601 UTC, whole minutes):
--   * arrived: the actual arrival
--   * departed: act_dep + block-time quantiles, capped by the provider's est_arr (p50 at est_arr,
--     p80 at est_arr + 15 min) when that estimate is after act_dep (an older one is stale)
--   * not departed, with a provider estimate that differs from the schedule: est_dep + block quantiles
--   * not departed, on schedule as far as we know: sched_dep + quantiles of (act_arr - sched_dep), so the
--     route's usual departure delay is part of the forecast
--   * not departed and the planned departure has passed: at least now + block-time quantiles
--   * fewer than 3 usable legs: method "schedule": est_arr, else best dep + scheduled block, else
--     sched_arr; p80 = p50 + 15 min
--   * cancelled: forecast null
-- Confidence: high at 20+ samples, medium at 8+, else low ("schedule" is always low).

create index if not exists flight_leg_history_number_idx on public.flight_leg_history (carrier, number, date);
create index if not exists flight_leg_history_route_idx on public.flight_leg_history (dep, arr, date);

-- ISO-8601 UTC rounded to the minute, the same text for every session time zone.
create or replace function public.forecast_iso(p_ts timestamptz) returns text
language sql immutable set search_path = '' as $$
  select to_char(date_trunc('minute', p_ts + interval '30 seconds') at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
$$;
revoke execute on function public.forecast_iso(timestamptz) from public, anon, authenticated;

create or replace function public.flight_forecast(p_flight_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_user    uuid := auth.uid();
  f         public.flights%rowtype;
  v_since   date := current_date - 60;
  v_basis   text;
  v_n       int;
  -- stats of the chosen level
  s_cancel  int;
  s_ontime  numeric;
  s_avgdep  numeric;
  s_medarr  numeric;
  s_block_n int;
  s_b50     double precision;
  s_b80     double precision;
  s_total_n int;
  s_t50     double precision;
  s_t80     double precision;
  -- forecast
  v_method  text;
  v_conf    text;
  v_p50     timestamptz;
  v_p80     timestamptz;
  v_base    timestamptz;
  v_history jsonb;
  v_fc      jsonb;
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if not public.can_read_flight(p_flight_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  select * into f from public.flights where id = p_flight_id;
  if not found then raise exception 'not_found' using errcode = 'P0002'; end if;

  if not public.is_pro_for_flight(v_user, p_flight_id) then
    return jsonb_build_object('history', null, 'forecast', null, 'detail_locked', true);
  end if;

  -- 1) pick the level: same carrier + number, then same carrier, then the route
  select count(*) into v_n from public.flight_leg_history h
   where h.dep = f.dep_iata and h.arr = f.arr_iata and h.date >= v_since and h.flight_id <> f.id
     and h.carrier = f.carrier_iata and h.number = f.flight_number;
  v_basis := 'flight';
  if v_n < 5 then
    select count(*) into v_n from public.flight_leg_history h
     where h.dep = f.dep_iata and h.arr = f.arr_iata and h.date >= v_since and h.flight_id <> f.id
       and h.carrier = f.carrier_iata;
    v_basis := 'carrier_route';
  end if;
  if v_n < 5 then
    select count(*) into v_n from public.flight_leg_history h
     where h.dep = f.dep_iata and h.arr = f.arr_iata and h.date >= v_since and h.flight_id <> f.id;
    v_basis := 'route';
  end if;

  -- 2) statistics of that level. Flown = not cancelled and not diverted (a diversion's times are elsewhere).
  with s as (
    select h.final_status,
           h.dep_delay_min, h.arr_delay_min,
           h.final_status not in ('cancelled', 'diverted') as flown,
           extract(epoch from h.act_arr - h.act_dep) / 60.0 as block_min,
           extract(epoch from h.act_arr - h.sched_dep) / 60.0 as total_min
      from public.flight_leg_history h
     where h.dep = f.dep_iata and h.arr = f.arr_iata and h.date >= v_since and h.flight_id <> f.id
       and (v_basis = 'route' or h.carrier = f.carrier_iata)
       and (v_basis <> 'flight' or h.number = f.flight_number)
  )
  select count(*) filter (where final_status = 'cancelled'),
         100.0 * count(*) filter (where flown and dep_delay_min <= 15)
           / nullif(count(*) filter (where flown and dep_delay_min is not null), 0),
         avg(dep_delay_min) filter (where flown),
         percentile_cont(0.5) within group (order by arr_delay_min) filter (where flown and arr_delay_min is not null),
         count(*) filter (where flown and block_min between 15 and 1440),
         percentile_cont(0.5) within group (order by block_min) filter (where flown and block_min between 15 and 1440),
         percentile_cont(0.8) within group (order by block_min) filter (where flown and block_min between 15 and 1440),
         count(*) filter (where flown and total_min between 15 and 2880),
         percentile_cont(0.5) within group (order by total_min) filter (where flown and total_min between 15 and 2880),
         percentile_cont(0.8) within group (order by total_min) filter (where flown and total_min between 15 and 2880)
    into s_cancel, s_ontime, s_avgdep, s_medarr, s_block_n, s_b50, s_b80, s_total_n, s_t50, s_t80
    from s;

  v_history := jsonb_build_object(
    'samples', v_n,
    'window_days', 60,
    'on_time_pct', round(s_ontime)::int,
    'avg_dep_delay_min', round(s_avgdep)::int,
    'median_arr_delay_min', round(s_medarr)::int,
    'cancelled', s_cancel,
    'basis', v_basis);

  -- 3) forecast
  v_conf := case when v_n >= 20 then 'high' when v_n >= 8 then 'medium' else 'low' end;
  if f.status = 'cancelled' then
    v_fc := null;
  elsif f.act_arr is not null then
    v_method := case when s_block_n >= 3 then 'history' else 'schedule' end;
    v_p50 := f.act_arr;
    v_p80 := f.act_arr;
    v_conf := 'high';
  elsif s_block_n >= 3 and f.act_dep is not null then
    v_method := 'history';
    v_p50 := f.act_dep + make_interval(secs => s_b50 * 60);
    v_p80 := f.act_dep + make_interval(secs => s_b80 * 60);
    -- a stale est_arr (still the schedule, at or before the actual departure) is no cap
    if f.est_arr is not null and f.est_arr > f.act_dep then
      v_p50 := least(v_p50, f.est_arr);
      v_p80 := least(v_p80, f.est_arr + interval '15 minutes');
    end if;
  elsif s_block_n >= 3 and f.est_dep is not null
        and (f.sched_dep is null or abs(extract(epoch from f.est_dep - f.sched_dep)) >= 60) then
    v_method := 'history';
    v_p50 := f.est_dep + make_interval(secs => s_b50 * 60);
    v_p80 := f.est_dep + make_interval(secs => s_b80 * 60);
  elsif s_total_n >= 3 and f.sched_dep is not null then
    v_method := 'history';
    v_p50 := f.sched_dep + make_interval(secs => s_t50 * 60);
    v_p80 := f.sched_dep + make_interval(secs => s_t80 * 60);
  elsif s_block_n >= 3 and coalesce(f.est_dep, f.sched_dep) is not null then
    v_method := 'history';
    v_base := coalesce(f.est_dep, f.sched_dep);
    v_p50 := v_base + make_interval(secs => s_b50 * 60);
    v_p80 := v_base + make_interval(secs => s_b80 * 60);
  else
    v_method := 'schedule';
    v_conf := 'low';
    v_base := coalesce(f.act_dep, f.est_dep, f.sched_dep);
    v_p50 := coalesce(f.est_arr,
                      case when f.sched_arr is not null and f.sched_dep is not null and v_base is not null
                           then v_base + (f.sched_arr - f.sched_dep) end,
                      f.sched_arr);
    v_p80 := v_p50 + interval '15 minutes';
  end if;

  -- not departed yet and the planned departure has passed: it cannot leave before now, so the arrival
  -- is at least now + block time (a forecast in the past is never shown for a flight still on the ground)
  if f.status <> 'cancelled' and f.act_dep is null and f.act_arr is null and v_method = 'history'
     and s_block_n >= 3 then
    v_p50 := greatest(v_p50, now() + make_interval(secs => s_b50 * 60));
    v_p80 := greatest(v_p80, now() + make_interval(secs => s_b80 * 60));
  end if;

  if f.status <> 'cancelled' then
    if v_p50 is null then
      v_fc := null;
    else
      v_p80 := greatest(v_p80, v_p50);
      v_fc := jsonb_build_object('arr_p50', public.forecast_iso(v_p50), 'arr_p80', public.forecast_iso(v_p80),
                                 'method', v_method, 'confidence', v_conf);
    end if;
  end if;

  return jsonb_build_object('history', v_history, 'forecast', v_fc, 'detail_locked', false);
end $$;
revoke execute on function public.flight_forecast(uuid) from public, anon;
grant execute on function public.flight_forecast(uuid) to authenticated;
