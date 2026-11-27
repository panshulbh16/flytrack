-- P3 B10 + B12 backend (docs/P3_CONTRACTS.md): compare Passports with friends, and crew mode.
--   * profiles.share_passport_with_friends (default true) and profiles.crew_mode (default false), both
--     user-editable (column grants).
--   * passport_core(user, year, pro): my_passport's computation for any user (internal), + block_minutes
--     (off-block to on-block, for crew mode). my_passport(year) is now a wrapper with the same contract.
--   * friend_passport(friend, year): a reduced passport of an accepted friend who shares it, with the same
--     year rules as my_passport, decided by the caller's Pro. Otherwise not_shared (42501).
--   * get_flight_recipients(): + crew_mode, so the engine skips CHECKIN_OPEN / CHECKIN_CLOSING / BOARDING
--     for crew users (when-to-leave is computed on the device, which reads profiles.crew_mode itself).

alter table public.profiles
  add column share_passport_with_friends boolean not null default true,
  add column crew_mode boolean not null default false;
grant update (share_passport_with_friends, crew_mode) on public.profiles to authenticated;

-- =================================================================================================
-- Passport core (the body of 20261126000014_passport.sql's my_passport, for a given user)
-- =================================================================================================
create or replace function public.passport_core(p_user uuid, p_year int, p_pro boolean) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid      uuid := p_user;
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
  v_pro := coalesce(p_pro, false);

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
                then extract(epoch from f.act_dep - f.sched_dep) / 60.0 end as dep_delay,
           -- crew mode: block time, off-block to on-block (migration 22 columns), else actual departure to
           -- arrival, else the scheduled block
           case when f.dep_offblock_at is not null and f.arr_onblock_at is not null
                     and f.arr_onblock_at > f.dep_offblock_at
                  then extract(epoch from f.arr_onblock_at - f.dep_offblock_at) / 60.0
                when f.act_dep is not null and f.act_arr is not null and f.act_arr > f.act_dep
                  then extract(epoch from f.act_arr - f.act_dep) / 60.0
                when f.sched_dep is not null and f.sched_arr is not null and f.sched_arr > f.sched_dep
                  then extract(epoch from f.sched_arr - f.sched_dep) / 60.0 end as block_min
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
    'block_minutes', (select coalesce(round(sum(block_min)), 0)::integer from base),
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

-- Same contract as 20261126000014_passport.sql (+ block_minutes).
create or replace function public.my_passport(p_year int default null) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  return public.passport_core(auth.uid(), p_year, public.current_user_is_pro());
end $$;

-- =================================================================================================
-- friend_passport (B10)
-- =================================================================================================
-- { friend_id, display_name, scope, year, pro_limited, flights, distance_km, airports (count), countries
--   (count), country_codes, airlines (top 3: {carrier, name, count}), longest } — the friend's own numbers
-- under the caller's year rules (a free caller sees the last 12 months / the current year, like their own
-- Passport). Not an accepted friend, or the friend turned sharing off → not_shared (42501); the two cases
-- are deliberately the same error so the call can't probe someone's setting.
create or replace function public.friend_passport(p_friend uuid, p_year int default null) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_me uuid := auth.uid();
  v_p  jsonb;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_friend is null or not public.are_friends(v_me, p_friend)
     or not coalesce((select p.share_passport_with_friends from public.profiles p where p.id = p_friend), false) then
    raise exception 'not_shared' using errcode = '42501';
  end if;
  v_p := public.passport_core(p_friend, p_year, public.current_user_is_pro());
  return jsonb_build_object(
    'friend_id', p_friend,
    'display_name', public.friend_display_name(p_friend),
    'scope', v_p -> 'scope',
    'year', v_p -> 'year',
    'pro_limited', v_p -> 'pro_limited',
    'flights', v_p -> 'flights',
    'distance_km', v_p -> 'distance_km',
    'airports', jsonb_array_length(v_p -> 'airports'),
    'countries', jsonb_array_length(v_p -> 'countries'),
    'country_codes', v_p -> 'countries',
    'airlines', coalesce((select jsonb_agg(a.value order by a.ord)
                            from jsonb_array_elements(v_p -> 'airlines') with ordinality a(value, ord)
                           where a.ord <= 3), '[]'::jsonb),
    'longest', case when v_p -> 'longest' is null or v_p -> 'longest' = 'null'::jsonb then null
                    else (v_p -> 'longest') - 'flight_id' end);
end $$;

-- =================================================================================================
-- get_flight_recipients: + crew_mode
-- =================================================================================================
-- Rows exactly as in 20261126000017_friends.sql, plus the recipient's profiles.crew_mode.
drop function public.get_flight_recipients(uuid);
create function public.get_flight_recipients(p_flight_id uuid)
returns table (
  user_id uuid, is_pro boolean, notify_prefs jsonb, device_id uuid, fcm_token text,
  alert_language text, quiet_hours_enabled boolean, quiet_start time, quiet_end time, quiet_tz text,
  role text, friend_name text, crew_mode boolean
)
language sql stable security definer set search_path = '' as $$
  select uf.user_id, public.is_pro_for_flight(uf.user_id, p_flight_id), uf.notify_prefs, d.id, d.fcm_token,
         coalesce(p.alert_language, 'en'), coalesce(p.quiet_hours_enabled, false),
         coalesce(p.quiet_start, time '23:00'), coalesce(p.quiet_end, time '07:00'),
         coalesce(p.quiet_tz, 'Asia/Kolkata'),
         'tracker'::text, null::text, coalesce(p.crew_mode, false)
    from public.user_flights uf
    join public.devices d on d.user_id = uf.user_id and d.notifications_enabled
    left join public.profiles p on p.id = uf.user_id
   where uf.flight_id = p_flight_id and uf.archived_at is null
  union all
  select s.friend_id, false, '{}'::jsonb, s.device_id, s.fcm_token,
         s.alert_language, s.quiet_hours_enabled, s.quiet_start, s.quiet_end, s.quiet_tz,
         'friend'::text, public.friend_display_name(s.tracker_id), s.crew_mode
    from (
      select distinct on (fu.friend_id, d.id)
             fu.friend_id, fu.tracker_id, d.id as device_id, d.fcm_token,
             coalesce(p.alert_language, 'en') as alert_language, coalesce(p.quiet_hours_enabled, false) as quiet_hours_enabled,
             coalesce(p.quiet_start, time '23:00') as quiet_start, coalesce(p.quiet_end, time '07:00') as quiet_end,
             coalesce(p.quiet_tz, 'Asia/Kolkata') as quiet_tz, coalesce(p.crew_mode, false) as crew_mode
        from (
          select uf.user_id as tracker_id, uf.created_at as tracked_at,
                 case when fr.user_low = uf.user_id then fr.user_high else fr.user_low end as friend_id
            from public.user_flights uf
            join public.friendships fr
              on (fr.user_high = uf.user_id and fr.notify_low)    -- friend is user_low
              or (fr.user_low = uf.user_id and fr.notify_high)    -- friend is user_high
           where uf.flight_id = p_flight_id and uf.archived_at is null and uf.share_with_friends
        ) fu
        join public.devices d on d.user_id = fu.friend_id and d.notifications_enabled
        left join public.profiles p on p.id = fu.friend_id
       where not exists (select 1 from public.user_flights own
                          where own.flight_id = p_flight_id and own.user_id = fu.friend_id
                            and own.archived_at is null)
       order by fu.friend_id, d.id, fu.tracked_at, fu.tracker_id
    ) s;
$$;

-- =================================================================================================
-- Execute rights
-- =================================================================================================
revoke execute on function public.passport_core(uuid, int, boolean) from public, anon, authenticated;
grant execute on function public.passport_core(uuid, int, boolean) to service_role;
revoke execute on function public.my_passport(int) from public, anon;
grant execute on function public.my_passport(int) to authenticated;
revoke execute on function public.friend_passport(uuid, int) from public, anon;
grant execute on function public.friend_passport(uuid, int) to authenticated;
revoke execute on function public.get_flight_recipients(uuid) from public, anon, authenticated;
grant execute on function public.get_flight_recipients(uuid) to service_role;
