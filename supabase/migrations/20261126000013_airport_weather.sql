-- P2 Airport Health (docs/P2_CONTRACTS.md section 1):
--   * airport_weather: latest METAR per airport, written by the refresh-airport-weather Edge Function
--   * weather_refresh_targets() / upsert_airport_weather(): what that function reads and writes
--   * get_airport_health(iata): level + headline for everyone, numbers for Pro
--   * fog_alert_candidates(): tracked flights near a foggy airport, read by the poll cycle (runFogAlerts)
--   * pg_cron: refresh every 15 minutes, same Vault pattern as invoke_poll_flights()
-- The FOG_ALERT event type itself is added to the flight_events check in migration 15.

-- ---------- table ---------------------------------------------------------------------------------
create table public.airport_weather (
  iata            char(3) primary key references public.airports (iata) on delete cascade,
  icao            char(4) not null,
  observed_at     timestamptz not null,
  fetched_at      timestamptz not null default now(),
  raw_metar       text not null check (char_length(raw_metar) <= 1000),
  visibility_m    integer check (visibility_m between 0 and 9999),   -- 9999 = 10 km or more
  ceiling_ft      integer check (ceiling_ft >= 0),                   -- null = no ceiling
  wind_kts        integer check (wind_kts >= 0),
  gust_kts        integer check (gust_kts >= 0),
  wx              text check (char_length(wx) <= 60),                -- present weather, e.g. "FG", "-TSRA"
  flight_category text check (flight_category in ('VFR', 'MVFR', 'IFR', 'LIFR')),
  fog             boolean not null default false                      -- FG in wx, or visibility < 1000 m
);

alter table public.airport_weather enable row level security;
revoke all on public.airport_weather from public, anon, authenticated;
grant select, insert, update, delete on public.airport_weather to service_role;

-- ---------- refresh targets -----------------------------------------------------------------------
-- The metros, plus every airport with a tracked flight departing or arriving in the next 24 h. Flights
-- up to 3 h past their time that have not departed / arrived yet stay in (fog keeps them on the ground).
create or replace function public.weather_refresh_targets()
returns table (iata text, icao text)
language sql stable security definer set search_path = '' as $$
  with wanted as (
    select unnest(array['DEL','BOM','BLR','HYD','MAA','CCU','COK','AMD','PNQ','GOI','GOX','LKO','ATQ','JAI']) as iata
    union
    select f.dep_iata from public.flights f
     where f.tracker_count > 0 and f.act_dep is null and f.status not in ('cancelled', 'diverted')
       and coalesce(f.est_dep, f.sched_dep) between now() - interval '3 hours' and now() + interval '24 hours'
    union
    select f.arr_iata from public.flights f
     where f.tracker_count > 0 and f.act_arr is null and f.status not in ('cancelled', 'arrived', 'landed')
       and coalesce(f.est_arr, f.sched_arr) between now() - interval '3 hours' and now() + interval '24 hours'
  )
  select a.iata::text, a.icao::text
    from public.airports a
    join wanted w on w.iata = a.iata
   where a.icao ~ '^[A-Z0-9]{4}$'
   order by a.iata;
$$;

-- ---------- upsert --------------------------------------------------------------------------------
-- p_rows: array of airport_weather rows as JSON (what SupabaseRepo / weather.ts send). An older report
-- never replaces a newer one; unknown airports are skipped instead of failing the whole batch.
-- Returns the number of rows inserted or updated.
create or replace function public.upsert_airport_weather(p_rows jsonb) returns integer
language plpgsql security definer set search_path = '' as $$
declare v_n integer;
begin
  if p_rows is null or jsonb_typeof(p_rows) <> 'array' then
    raise exception 'invalid_rows' using errcode = '22023';
  end if;
  insert into public.airport_weather as w (iata, icao, observed_at, fetched_at, raw_metar, visibility_m, ceiling_ft,
                                           wind_kts, gust_kts, wx, flight_category, fog)
  select distinct on (upper(r.iata))
         upper(r.iata), upper(r.icao), r.observed_at, coalesce(r.fetched_at, now()), r.raw_metar, r.visibility_m,
         r.ceiling_ft, r.wind_kts, r.gust_kts, r.wx, r.flight_category, coalesce(r.fog, false)
    from jsonb_to_recordset(p_rows) as r(iata text, icao text, observed_at timestamptz, fetched_at timestamptz,
                                         raw_metar text, visibility_m integer, ceiling_ft integer, wind_kts integer,
                                         gust_kts integer, wx text, flight_category text, fog boolean)
   where r.iata is not null and r.icao is not null and r.observed_at is not null and r.raw_metar is not null
     and exists (select 1 from public.airports a where a.iata = upper(r.iata))
   order by upper(r.iata), r.observed_at desc
  on conflict (iata) do update
     set icao = excluded.icao, observed_at = excluded.observed_at, fetched_at = excluded.fetched_at,
         raw_metar = excluded.raw_metar, visibility_m = excluded.visibility_m, ceiling_ft = excluded.ceiling_ft,
         wind_kts = excluded.wind_kts, gust_kts = excluded.gust_kts, wx = excluded.wx,
         flight_category = excluded.flight_category, fog = excluded.fog
   where excluded.observed_at >= w.observed_at;
  get diagnostics v_n = row_count;
  return v_n;
end $$;

-- ---------- airport health ------------------------------------------------------------------------
-- Weather older than 3 h is ignored. The flight sample is our own departures from the airport in the last
-- 3 h (scheduled or actual), cancelled ones excluded. A flight that has not left yet counts as late until
-- now (or until its estimate, if later); rows without a recent poll are skipped, so an old search result
-- that nobody tracks never looks hours late. Delay rules need at least 8 flights.
create or replace function public.get_airport_health(p_iata text) returns jsonb
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
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if v_iata !~ '^[A-Z0-9]{3}$' then raise exception 'invalid_iata' using errcode = '22023'; end if;
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
  -- India: winter haze routinely gives 3-5 km (FAA "IFR"), so IFR alone is NOT disruption here.
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

  if public.current_user_is_pro() then
    v_detail := jsonb_build_object(
      'avg_dep_delay_min', v_avg_i,
      'delayed_pct', v_pct,
      'cancelled_today', v_cancel,
      'sample_size', v_sample,
      'weather', case when v_has_w then jsonb_build_object(
        'visibility_m', w.visibility_m, 'category', w.flight_category, 'fog', w.fog,
        'wind_kts', w.wind_kts, 'wx', w.wx, 'observed_at', w.observed_at) end);
  end if;

  return jsonb_build_object(
    'iata', v_iata,
    'level', v_level,
    'headline', v_headline,
    'updated_at', coalesce(greatest(case when v_has_w then w.observed_at end, v_last_poll), now()),
    'detail', v_detail);
end $$;

-- ---------- fog alerts ----------------------------------------------------------------------------
-- Tracked flights whose departure airport (not departed, departure within 4 h, or up to 3 h past it) or
-- arrival airport (not arrived, same window around arrival) reports fog or visibility < 1000 m in an
-- observation at most 2 h old, without a FOG_ALERT for that key yet. Key: {flight}:FOG:{iata}:{local date}
-- with the airport-local date of the scheduled time on that side. Mirrored by fogCandidates() in
-- supabase/functions/_shared/health.ts.
create or replace function public.fog_alert_candidates(p_limit int default 100)
returns table (flight_id uuid, side text, airport text, visibility_m integer, wx text, dedupe_key text)
language sql stable security definer set search_path = '' as $$
  with sides as (
    select f.id, 'dep'::text as side, f.dep_iata as iata, coalesce(f.est_dep, f.sched_dep) as t,
           coalesce(f.sched_dep, f.est_dep) as planned
      from public.flights f
     where f.tracker_count > 0 and f.act_dep is null
       and f.status not in ('departed', 'en_route', 'approaching', 'landed', 'arrived', 'cancelled', 'diverted')
       and coalesce(f.est_dep, f.sched_dep) between now() - interval '3 hours' and now() + interval '4 hours'
    union all
    select f.id, 'arr'::text, f.arr_iata, coalesce(f.est_arr, f.sched_arr), coalesce(f.sched_arr, f.est_arr)
      from public.flights f
     where f.tracker_count > 0 and f.act_arr is null
       and f.status not in ('landed', 'arrived', 'cancelled', 'diverted')
       and coalesce(f.est_arr, f.sched_arr) between now() - interval '3 hours' and now() + interval '4 hours'
  )
  select s.id, s.side, s.iata::text, w.visibility_m, w.wx, k.dedupe_key
    from sides s
    join public.airport_weather w on w.iata = s.iata
    join public.airports a on a.iata = s.iata
   cross join lateral (
     select s.id::text || ':FOG:' || s.iata::text || ':' || to_char((s.planned at time zone a.tz)::date, 'YYYY-MM-DD')
            as dedupe_key) k
   where w.observed_at >= now() - interval '120 minutes'
     and (w.fog or w.visibility_m < 1000)
     and not exists (select 1 from public.flight_events e where e.dedupe_key = k.dedupe_key)
   order by s.t
   limit greatest(coalesce(p_limit, 100), 1);
$$;

-- The poll pre-check also wakes poll-flights when a fog alert is due (body otherwise as in migration 12).
create or replace function public.poll_work_due() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
           select 1 from public.flights
            where next_poll_at <= now()
              and (tracker_count > 0 or inbound_watch_until > now())
              and (lease_until is null or lease_until < now()))
      or exists (select 1 from public.due_checkin_flights(1))
      or exists (select 1 from public.fog_alert_candidates(1));
$$;

-- ---------- scheduler -----------------------------------------------------------------------------
-- pg_cron → pg_net → refresh-airport-weather. Same Vault secrets as invoke_poll_flights()
-- (project_url, cron_secret); nothing secret lives in this file.
create or replace function public.invoke_refresh_airport_weather() returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_url    text := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url');
  v_secret text := (select decrypted_secret from vault.decrypted_secrets where name = 'cron_secret');
begin
  if v_url is null or v_secret is null then
    raise notice 'refresh-airport-weather: vault secrets project_url/cron_secret not set, skipping';
    return;
  end if;
  perform net.http_post(
    url := v_url || '/functions/v1/refresh-airport-weather',
    headers := jsonb_build_object('content-type', 'application/json', 'x-cron-secret', v_secret),
    body := '{}'::jsonb,
    timeout_milliseconds := 30000
  );
end $$;

-- Every 15 minutes, at :05/:20/:35/:50: Indian METARs are observed at :00 and :30 and published a few
-- minutes later. Guarded so the migration also applies where pg_cron is absent (local SQL tests);
-- on Supabase, pg_cron is created by 20261122000002_cron.sql. cron.schedule() replaces a job of the same name.
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('flytrack-airport-weather', '5,20,35,50 * * * *',
                          'select public.invoke_refresh_airport_weather()');
  else
    raise notice 'pg_cron not installed: flytrack-airport-weather not scheduled';
  end if;
end $$;

-- ---------- function execute rights ---------------------------------------------------------------
revoke execute on function public.weather_refresh_targets() from public, anon, authenticated;
grant execute on function public.weather_refresh_targets() to service_role;
revoke execute on function public.upsert_airport_weather(jsonb) from public, anon, authenticated;
grant execute on function public.upsert_airport_weather(jsonb) to service_role;
revoke execute on function public.fog_alert_candidates(int) from public, anon, authenticated;
grant execute on function public.fog_alert_candidates(int) to service_role;
revoke execute on function public.poll_work_due() from public, anon, authenticated;
grant execute on function public.poll_work_due() to service_role;
revoke execute on function public.invoke_refresh_airport_weather() from public, anon, authenticated;
revoke execute on function public.get_airport_health(text) from public, anon;
grant execute on function public.get_airport_health(text) to authenticated;
