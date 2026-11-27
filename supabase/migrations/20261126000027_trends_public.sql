-- P3 B7, B8, B11 (docs/P3_CONTRACTS.md): airport delay trends, the public airport status and the global
-- yearly report.
--   * airport_health_core(iata, mode): the Airport Health computation of 20261126000013 (unchanged levels
--     and headlines), shared by get_airport_health (signed-in users, numbers for Pro) and
--     airport_status_public (the public airport page). Internal.
--   * get_airport_health(iata): same contract as before, now a wrapper of the core.
--   * airport_status_public(iata): service role only (airport-status Edge Function). The public part of
--     Airport Health (level, headline, weather summary) and today's counts. No user data: counts only.
--   * airport_trends(iata, days): Pro. Delay by airport-local hour and by day from flight_leg_history.
--   * global_report(year): service role only (global-report Edge Function). Anonymous aggregates with
--     k-anonymity: unique users rounded to the nearest 100 and omitted under 1,000; airline / airport /
--     route rows need at least 200 legs.

-- =================================================================================================
-- Airport Health core
-- =================================================================================================
-- p_mode: 'pro' (detail numbers), 'free' (no detail), 'public' (no detail, + a weather summary).
create or replace function public.airport_health_core(p_iata text, p_mode text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_iata      text := upper(btrim(coalesce(p_iata, '')));
  v_tz        text;
  w           public.airport_weather%rowtype;
  v_has_w     boolean;
  v_sample    integer;
  v_avg       numeric;
  v_delayed   integer;
  v_last_poll timestamptz;
  v_cancel    integer;
  v_avg_i     integer;
  v_pct       integer;
  v_delay_ok  boolean;
  v_fog_bad   boolean;
  v_wx_bad    boolean;
  v_wx_text   text;
  v_level     text;
  v_headline  text;
  v_detail    jsonb;
  v_out       jsonb;
begin
  if v_iata !~ '^[A-Z0-9]{3}$' then raise exception 'invalid_iata' using errcode = '22023'; end if;
  if p_mode not in ('pro', 'free', 'public') then raise exception 'invalid_mode' using errcode = '22023'; end if;
  select a.tz into v_tz from public.airports a where a.iata = v_iata;
  if not found then return null; end if;

  select * into w from public.airport_weather aw
   where aw.iata = v_iata and aw.observed_at > now() - interval '3 hours';
  v_has_w := found;

  with recent as (
    select f.last_polled_at,
           greatest(0, extract(epoch from (
             case when f.act_dep is not null then f.act_dep
                  when f.status in ('departed', 'en_route', 'approaching', 'landed', 'arrived', 'diverted')
                    then coalesce(f.est_dep, f.sched_dep)
                  else greatest(coalesce(f.est_dep, f.sched_dep), now()) end - f.sched_dep)) / 60.0) as delay_min
      from public.flights f
     where f.dep_iata = v_iata
       and f.sched_dep is not null
       and f.status <> 'cancelled'
       and (f.sched_dep between now() - interval '3 hours' and now()
            or f.act_dep between now() - interval '3 hours' and now())
       and (f.act_dep is not null or f.last_polled_at > now() - interval '1 hour')
  )
  select count(*), avg(delay_min), count(*) filter (where delay_min > 15), max(last_polled_at)
    into v_sample, v_avg, v_delayed, v_last_poll
    from recent;

  select count(*) into v_cancel from public.flights f
   where f.dep_iata = v_iata and f.status = 'cancelled' and f.flight_date = (now() at time zone v_tz)::date;

  v_avg_i    := round(v_avg)::integer;
  v_pct      := round(100.0 * v_delayed / nullif(v_sample, 0))::integer;
  v_delay_ok := v_sample >= 8;
  v_fog_bad  := v_has_w and w.fog and coalesce(w.visibility_m < 550, false);
  v_wx_bad   := v_has_w and (coalesce(w.visibility_m < 1500, false) or coalesce(w.flight_category = 'LIFR', false));
  v_wx_text  := case
    when not v_has_w then null
    when w.fog and w.visibility_m is not null then format('Fog: visibility %s m', w.visibility_m)
    when w.fog then 'Fog'
    when w.wx ~ 'TS' then 'Thunderstorms at the airport'
    when w.visibility_m < 5000 then format('Low visibility: %s m', w.visibility_m)
    when w.ceiling_ft < 1000 then format('Low cloud: %s ft', w.ceiling_ft)
    else null end;

  if v_fog_bad or v_cancel >= 10 then
    v_level := 'severe';
    v_headline := case when v_fog_bad then v_wx_text else format('%s flights cancelled today', v_cancel) end;
  elsif v_wx_bad or (v_delay_ok and v_avg >= 45) then
    v_level := 'disrupted';
    v_headline := case when v_wx_bad then coalesce(v_wx_text, 'Poor flying weather')
                       else format('Departures running %s min late', v_avg_i) end;
  elsif v_delay_ok and (v_avg >= 20 or v_pct >= 30) then
    v_level := 'busy';
    v_headline := case when v_avg >= 20 then format('Departures running %s min late', v_avg_i)
                       else format('%s%% of departures delayed', v_pct) end;
  else
    v_level := 'normal';
    v_headline := 'Operating normally';
  end if;

  if p_mode = 'pro' then
    v_detail := jsonb_build_object(
      'avg_dep_delay_min', v_avg_i,
      'delayed_pct', v_pct,
      'cancelled_today', v_cancel,
      'sample_size', v_sample,
      'weather', case when v_has_w then jsonb_build_object(
        'visibility_m', w.visibility_m, 'category', w.flight_category, 'fog', w.fog,
        'wind_kts', w.wind_kts, 'wx', w.wx, 'observed_at', w.observed_at) end);
  end if;

  v_out := jsonb_build_object(
    'iata', v_iata,
    'level', v_level,
    'headline', v_headline,
    'updated_at', coalesce(greatest(case when v_has_w then w.observed_at end, v_last_poll), now()),
    'detail', v_detail);
  if p_mode = 'public' then
    v_out := v_out - 'detail' || jsonb_build_object('weather', case when v_has_w then jsonb_build_object(
      'summary', coalesce(v_wx_text, 'Good flying weather'),
      'visibility_m', w.visibility_m, 'category', w.flight_category, 'fog', w.fog,
      'observed_at', w.observed_at) end);
  end if;
  return v_out;
end $$;

-- Same contract as 20261126000013_airport_weather.sql.
create or replace function public.get_airport_health(p_iata text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  return public.airport_health_core(p_iata, case when public.current_user_is_pro() then 'pro' else 'free' end);
end $$;

-- =================================================================================================
-- Public airport status (B8)
-- =================================================================================================
-- { iata, name, city, tz, lat, lon, level, headline, updated_at, weather: {summary, visibility_m, category, fog,
--   observed_at} | null, today: { date, departures, departed, delayed_pct, cancelled } }
-- today counts the departures FlyTrack knows of (flights rows) on the airport-local date; delayed_pct is the
-- share of departed legs that left more than 15 min late, null under 5 departed legs (too few to say).
create or replace function public.airport_status_public(p_iata text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_iata   text := upper(btrim(coalesce(p_iata, '')));
  a        public.airports%rowtype;
  v_today  date;
  v_health jsonb;
  v_counts jsonb;
begin
  if v_iata !~ '^[A-Z0-9]{3}$' then raise exception 'invalid_iata' using errcode = '22023'; end if;
  select * into a from public.airports ap where ap.iata = v_iata;
  if not found then return null; end if;
  v_today := (now() at time zone a.tz)::date;
  v_health := public.airport_health_core(v_iata, 'public');

  select jsonb_build_object(
           'date', to_char(v_today, 'YYYY-MM-DD'),
           'departures', count(*),
           'departed', count(*) filter (where f.act_dep is not null),
           'delayed_pct', case when count(*) filter (where f.act_dep is not null and f.sched_dep is not null) >= 5
             then round(100.0 * count(*) filter (where f.act_dep > f.sched_dep + interval '15 minutes')
                        / count(*) filter (where f.act_dep is not null and f.sched_dep is not null))::int end,
           'cancelled', count(*) filter (where f.status = 'cancelled'))
    into v_counts
    from public.flights f
   where f.dep_iata = v_iata and f.flight_date = v_today;

  return v_health || jsonb_build_object('name', a.name, 'city', a.city, 'tz', a.tz, 'lat', a.lat, 'lon', a.lon,
                                        'today', v_counts);
end $$;

-- =================================================================================================
-- Airport delay trends (B7, Pro)
-- =================================================================================================
-- { iata, days, by_hour: [{hour, avg_dep_delay, delayed_pct, samples}], by_day: [{date, avg_dep_delay,
--   cancelled, samples}] } over the last p_days airport-local days (1..90, default 14) of flight_leg_history.
-- hour = airport-local hour of the scheduled departure. Delays are of flown legs (cancelled legs are only
-- counted in by_day.cancelled); delayed = more than 15 min late. Not Pro → pro_required (42501).
create or replace function public.airport_trends(p_iata text, p_days int default 14) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_iata  text := upper(btrim(coalesce(p_iata, '')));
  v_tz    text;
  v_days  int := coalesce(p_days, 14);
  v_from  date;
  v_today date;
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if v_iata !~ '^[A-Z0-9]{3}$' then raise exception 'invalid_iata' using errcode = '22023'; end if;
  if v_days < 1 or v_days > 90 then raise exception 'invalid_days' using errcode = '22023'; end if;
  if not public.current_user_is_pro() then raise exception 'pro_required' using errcode = '42501'; end if;
  select a.tz into v_tz from public.airports a where a.iata = v_iata;
  if not found then return null; end if;
  v_today := (now() at time zone v_tz)::date;
  v_from := v_today - (v_days - 1);

  return (
    with legs as (
      select h.date, h.final_status, h.dep_delay_min,
             extract(hour from h.sched_dep at time zone v_tz)::int as hour
        from public.flight_leg_history h
       where h.dep = v_iata and h.date between v_from and v_today
    )
    select jsonb_build_object(
      'iata', v_iata,
      'days', v_days,
      'by_hour', (select coalesce(jsonb_agg(jsonb_build_object(
                           'hour', x.hour, 'avg_dep_delay', x.avg_delay, 'delayed_pct', x.pct, 'samples', x.n)
                         order by x.hour), '[]'::jsonb)
                    from (select l.hour, count(*)::int as n,
                                 round(avg(l.dep_delay_min))::int as avg_delay,
                                 round(100.0 * count(*) filter (where l.dep_delay_min > 15) / count(*))::int as pct
                            from legs l
                           where l.final_status <> 'cancelled' and l.dep_delay_min is not null and l.hour is not null
                           group by l.hour) x),
      'by_day', (select coalesce(jsonb_agg(jsonb_build_object(
                          'date', to_char(x.date, 'YYYY-MM-DD'), 'avg_dep_delay', x.avg_delay,
                          'cancelled', x.cancelled, 'samples', x.n)
                        order by x.date), '[]'::jsonb)
                   from (select l.date, count(*)::int as n,
                                round(avg(l.dep_delay_min) filter (where l.final_status <> 'cancelled'))::int as avg_delay,
                                count(*) filter (where l.final_status = 'cancelled')::int as cancelled
                           from legs l group by l.date) x))
  );
end $$;

-- =================================================================================================
-- Global yearly report (B11)
-- =================================================================================================
-- {
--   year, generated_at, k_min_legs: 200,
--   totals: { flights_tracked, distance_km, unique_users (nearest 100, null under 1,000), legs },
--   airlines: { most_delayed: [...], most_punctual: [...] }   rows {carrier, name, legs, on_time_pct, avg_dep_delay}
--   airports: { most_delayed: [...], most_punctual: [...] }   rows {iata, name, city, legs, on_time_pct, avg_dep_delay}
--   busiest_routes: [{dep, arr, legs, dep_lat, dep_lon, arr_lat, arr_lon}]  (coordinates for the routes globe)
--   fog: { airport: "DEL", fog_days, fog_delay_days }
-- }
-- totals count tracked user_flights of flights whose local departure date is in the year (no user ids leave).
-- Rankings come from flight_leg_history (finished legs; on time = departure delay <= 15 min, of flown legs
-- with a known delay); every ranked airline / airport / route needs at least 200 legs. Top 5 each, top 10
-- routes. fog_days: DEL dates with a FOG_ALERT for DEL; fog_delay_days: those days on which DEL's finished
-- departures averaged more than 30 min late.
create or replace function public.global_report(p_year int) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_k        constant int := 200;
  v_cur_year int := extract(year from (now() at time zone 'Asia/Kolkata'))::int;
  v_from     date;
  v_to       date;
  v_flights  bigint;
  v_users    bigint;
  v_km       bigint;
  v_legs     bigint;
begin
  if p_year is null or p_year < 2024 or p_year > v_cur_year then
    raise exception 'invalid_year' using errcode = '22023';
  end if;
  v_from := make_date(p_year, 1, 1);
  v_to := make_date(p_year, 12, 31);

  select count(*), count(distinct uf.user_id),
         coalesce(round(sum(public.great_circle_km(da.lat, da.lon, aa.lat, aa.lon))), 0)::bigint
    into v_flights, v_users, v_km
    from public.user_flights uf
    join public.flights f on f.id = uf.flight_id
    left join public.airports da on da.iata = f.dep_iata
    left join public.airports aa on aa.iata = f.arr_iata
   where f.flight_date between v_from and v_to;

  select count(*) into v_legs from public.flight_leg_history h where h.date between v_from and v_to;

  return (
    with legs as (
      select h.carrier, h.dep, h.arr, h.date, h.final_status, h.dep_delay_min
        from public.flight_leg_history h
       where h.date between v_from and v_to
    ),
    by_airline as (
      select l.carrier, count(*)::int as n,
             round(100.0 * count(*) filter (where l.final_status <> 'cancelled' and l.dep_delay_min <= 15)
                   / nullif(count(*) filter (where l.final_status <> 'cancelled' and l.dep_delay_min is not null), 0))::int as on_time,
             round(avg(l.dep_delay_min) filter (where l.final_status <> 'cancelled'))::int as avg_delay
        from legs l group by l.carrier having count(*) >= v_k
    ),
    by_airport as (
      select l.dep as iata, count(*)::int as n,
             round(100.0 * count(*) filter (where l.final_status <> 'cancelled' and l.dep_delay_min <= 15)
                   / nullif(count(*) filter (where l.final_status <> 'cancelled' and l.dep_delay_min is not null), 0))::int as on_time,
             round(avg(l.dep_delay_min) filter (where l.final_status <> 'cancelled'))::int as avg_delay
        from legs l group by l.dep having count(*) >= v_k
    ),
    airline_rows as (
      select b.*, (select f.airline_name from public.flights f
                    where f.carrier_iata = b.carrier and f.airline_name is not null
                    order by f.flight_date desc limit 1) as name
        from by_airline b where b.on_time is not null
    ),
    airport_rows as (
      select b.*, ap.name, ap.city from by_airport b left join public.airports ap on ap.iata = b.iata
       where b.on_time is not null
    ),
    del_days as (
      select l.date, avg(l.dep_delay_min) filter (where l.final_status <> 'cancelled') as avg_delay
        from legs l where l.dep = 'DEL' group by l.date
    ),
    fog_days as (
      select distinct split_part(e.dedupe_key, ':', 4)::date as day
        from public.flight_events e
       where e.type = 'FOG_ALERT' and split_part(e.dedupe_key, ':', 3) = 'DEL'
         and split_part(e.dedupe_key, ':', 4) ~ '^\d{4}-\d{2}-\d{2}$'
         and split_part(e.dedupe_key, ':', 4) between to_char(v_from, 'YYYY-MM-DD') and to_char(v_to, 'YYYY-MM-DD')
    )
    select jsonb_build_object(
      'year', p_year,
      'generated_at', now(),
      'k_min_legs', v_k,
      'totals', jsonb_build_object(
        'flights_tracked', v_flights,
        'distance_km', v_km,
        'unique_users', case when v_users >= 1000 then (round(v_users / 100.0) * 100)::bigint end,
        'legs', v_legs),
      'airlines', jsonb_build_object(
        'most_delayed', (select coalesce(jsonb_agg(to_jsonb(x) order by x.on_time_pct, x.avg_dep_delay desc, x.carrier), '[]'::jsonb)
                           from (select r.carrier, r.name, r.n as legs, r.on_time as on_time_pct, r.avg_delay as avg_dep_delay
                                   from airline_rows r order by r.on_time, r.avg_delay desc, r.carrier limit 5) x),
        'most_punctual', (select coalesce(jsonb_agg(to_jsonb(x) order by x.on_time_pct desc, x.avg_dep_delay, x.carrier), '[]'::jsonb)
                            from (select r.carrier, r.name, r.n as legs, r.on_time as on_time_pct, r.avg_delay as avg_dep_delay
                                    from airline_rows r order by r.on_time desc, r.avg_delay, r.carrier limit 5) x)),
      'airports', jsonb_build_object(
        'most_delayed', (select coalesce(jsonb_agg(to_jsonb(x) order by x.on_time_pct, x.avg_dep_delay desc, x.iata), '[]'::jsonb)
                           from (select r.iata::text, r.name, r.city, r.n as legs, r.on_time as on_time_pct, r.avg_delay as avg_dep_delay
                                   from airport_rows r order by r.on_time, r.avg_delay desc, r.iata limit 5) x),
        'most_punctual', (select coalesce(jsonb_agg(to_jsonb(x) order by x.on_time_pct desc, x.avg_dep_delay, x.iata), '[]'::jsonb)
                            from (select r.iata::text, r.name, r.city, r.n as legs, r.on_time as on_time_pct, r.avg_delay as avg_dep_delay
                                    from airport_rows r order by r.on_time desc, r.avg_delay, r.iata limit 5) x)),
      'busiest_routes', (select coalesce(jsonb_agg(jsonb_build_object('dep', x.dep, 'arr', x.arr, 'legs', x.n,
                                                                       'dep_lat', da.lat, 'dep_lon', da.lon,
                                                                       'arr_lat', aa.lat, 'arr_lon', aa.lon)
                                                   order by x.n desc, x.dep, x.arr), '[]'::jsonb)
                           from (select l.dep::text as dep, l.arr::text as arr, count(*)::int as n
                                   from legs l group by l.dep, l.arr having count(*) >= v_k
                                  order by count(*) desc, l.dep, l.arr limit 10) x
                           left join public.airports da on da.iata = x.dep
                           left join public.airports aa on aa.iata = x.arr),
      'fog', jsonb_build_object(
        'airport', 'DEL',
        'fog_days', (select count(*) from fog_days),
        'fog_delay_days', (select count(*) from fog_days fd join del_days d on d.date = fd.day where d.avg_delay > 30)))
  );
end $$;

-- =================================================================================================
-- Execute rights
-- =================================================================================================
revoke execute on function public.airport_health_core(text, text) from public, anon, authenticated;
grant execute on function public.airport_health_core(text, text) to service_role;
revoke execute on function public.get_airport_health(text) from public, anon;
grant execute on function public.get_airport_health(text) to authenticated;
revoke execute on function public.airport_status_public(text) from public, anon, authenticated;
grant execute on function public.airport_status_public(text) to service_role;
revoke execute on function public.airport_trends(text, int) from public, anon;
grant execute on function public.airport_trends(text, int) to authenticated;
revoke execute on function public.global_report(int) from public, anon, authenticated;
grant execute on function public.global_report(int) to service_role;
