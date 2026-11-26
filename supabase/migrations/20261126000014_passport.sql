-- P2 Passport and Year in Review (docs/P2_CONTRACTS.md section 2): my_passport(p_year).
-- Counts the caller's own user_flights (archived included, any source) whose flight has departed, or whose
-- scheduled departure is in the past and which was not cancelled.
-- Scope: Pro + null → all time; free + null → last 12 months; any year → that calendar year, which is free
-- for the current year (Year in Review). A free user asking for an earlier year gets only the part inside
-- the last 12 months, with pro_limited = true. Years and months are the flight's local departure date
-- (flights.flight_date); "today" and "current year" are Indian time.

-- Great-circle distance in km (haversine, mean Earth radius 6371 km).
create or replace function public.great_circle_km(p_lat1 double precision, p_lon1 double precision,
                                                  p_lat2 double precision, p_lon2 double precision)
returns double precision
language sql immutable parallel safe set search_path = '' as $$
  select 2 * 6371.0 * asin(least(1.0, sqrt(
           power(sin(radians(p_lat2 - p_lat1) / 2), 2)
           + cos(radians(p_lat1)) * cos(radians(p_lat2)) * power(sin(radians(p_lon2 - p_lon1) / 2), 2))));
$$;

create or replace function public.my_passport(p_year int default null) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid      uuid := auth.uid();
  v_cur_year integer := extract(year from (now() at time zone 'Asia/Kolkata'))::integer;
  v_last12   date := ((now() at time zone 'Asia/Kolkata')::date - interval '12 months')::date;
  v_pro      boolean;
  v_scope    text;
  v_limited  boolean := false;
  v_from     date;   -- inclusive bounds on flights.flight_date, null = open
  v_to       date;
  v_out      jsonb;
begin
  if v_uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_year is not null and (p_year < 1950 or p_year > v_cur_year + 1) then
    raise exception 'invalid_year' using errcode = '22023';
  end if;
  v_pro := public.current_user_is_pro();

  if p_year is not null then
    v_scope := 'year';
    v_from := make_date(p_year, 1, 1);
    v_to := make_date(p_year, 12, 31);
    if not v_pro and p_year <> v_cur_year then
      v_limited := true;
      v_from := greatest(v_from, v_last12);
    end if;
  elsif v_pro then
    v_scope := 'all';
  else
    v_scope := 'last12';
    v_limited := true;
    v_from := v_last12;
  end if;

  with base as (
    select f.id, f.carrier_iata as carrier, f.flight_number as number, f.flight_date,
           f.dep_iata::text as dep, f.arr_iata::text as arr, f.airline_name, f.aircraft_model,
           da.country::text as dep_country, aa.country::text as arr_country,
           case when da.iata is not null and aa.iata is not null
                then public.great_circle_km(da.lat, da.lon, aa.lat, aa.lon) end as km,
           case when f.act_dep is not null and f.act_arr is not null and f.act_arr > f.act_dep
                  then extract(epoch from f.act_arr - f.act_dep) / 60.0
                when f.sched_dep is not null and f.sched_arr is not null and f.sched_arr > f.sched_dep
                  then extract(epoch from f.sched_arr - f.sched_dep) / 60.0 * 0.9 end as air_min,
           case when f.act_dep is not null and f.sched_dep is not null
                then extract(epoch from f.act_dep - f.sched_dep) / 60.0 end as dep_delay
      from public.user_flights uf
      join public.flights f on f.id = uf.flight_id
      left join public.airports da on da.iata = f.dep_iata
      left join public.airports aa on aa.iata = f.arr_iata
     where uf.user_id = v_uid
       and (f.act_dep is not null or (f.sched_dep < now() and f.status <> 'cancelled'))
       and (v_from is null or f.flight_date >= v_from)
       and (v_to is null or f.flight_date <= v_to)
  )
  select jsonb_build_object(
    'scope', v_scope,
    'year', coalesce(p_year, v_cur_year),
    'pro_limited', v_limited,
    'flights', (select count(*) from base),
    'distance_km', (select coalesce(round(sum(km)), 0)::integer from base),
    'airborne_minutes', (select coalesce(round(sum(air_min)), 0)::integer from base),
    'airports', (select coalesce(jsonb_agg(jsonb_build_object('iata', x.iata, 'count', x.n) order by x.n desc, x.iata), '[]'::jsonb)
                   from (select v.iata, count(*) as n
                           from (select dep as iata from base union all select arr from base) v
                          group by v.iata) x),
    'countries', (select coalesce(jsonb_agg(x.c order by x.c), '[]'::jsonb)
                    from (select dep_country as c from base union select arr_country from base) x
                   where x.c is not null),
    'airlines', (select coalesce(jsonb_agg(jsonb_build_object('carrier', x.carrier, 'name', x.name, 'count', x.n)
                                           order by x.n desc, x.carrier), '[]'::jsonb)
                   from (select b.carrier, count(*) as n,
                                (array_agg(b.airline_name order by b.flight_date desc)
                                   filter (where b.airline_name is not null))[1] as name
                           from base b group by b.carrier) x),
    'aircraft', (select coalesce(jsonb_agg(jsonb_build_object('model', x.model, 'count', x.n) order by x.n desc, x.model), '[]'::jsonb)
                   from (select b.aircraft_model as model, count(*) as n
                           from base b where b.aircraft_model is not null group by b.aircraft_model) x),
    'longest', (select jsonb_build_object('flight_id', b.id, 'carrier', b.carrier, 'number', b.number,
                                          'dep', b.dep, 'arr', b.arr, 'distance_km', round(b.km)::integer)
                  from base b where b.km is not null
                 order by b.km desc, b.flight_date, b.id limit 1),
    'top_route', (select jsonb_build_object('dep', x.dep, 'arr', x.arr, 'count', x.n)
                    from (select b.dep, b.arr, count(*) as n from base b group by b.dep, b.arr) x
                   order by x.n desc, x.dep, x.arr limit 1),
    'delay', jsonb_build_object(
      'total_min', (select coalesce(round(sum(greatest(b.dep_delay, 0))), 0)::integer from base b),
      'on_time_pct', (select round(100.0 * count(*) filter (where b.dep_delay <= 15) / nullif(count(*), 0))::integer
                        from base b where b.dep_delay is not null),
      'worst', (select jsonb_build_object('carrier', b.carrier, 'number', b.number,
                                          'dep_delay_min', round(b.dep_delay)::integer)
                  from base b where b.dep_delay > 0
                 order by b.dep_delay desc, b.flight_date, b.id limit 1)),
    'co2_kg', (select coalesce(round(sum(b.km * case when b.km < 1500 then 0.115
                                                     when b.km <= 4000 then 0.095
                                                     else 0.085 end)), 0)::integer
                 from base b where b.km is not null),
    'co2_method', 'estimate-v1',
    'by_month', (select coalesce(jsonb_agg(jsonb_build_object('month', x.m, 'flights', x.n) order by x.m), '[]'::jsonb)
                   from (select to_char(b.flight_date, 'YYYY-MM') as m, count(*) as n from base b group by 1) x),
    'first_flight_date', (select to_char(min(b.flight_date), 'YYYY-MM-DD') from base b))
  into v_out;
  return v_out;
end $$;

revoke execute on function public.great_circle_km(double precision, double precision, double precision, double precision)
  from public, anon, authenticated;
grant execute on function public.great_circle_km(double precision, double precision, double precision, double precision)
  to service_role;
revoke execute on function public.my_passport(int) from public, anon;
grant execute on function public.my_passport(int) to authenticated;
