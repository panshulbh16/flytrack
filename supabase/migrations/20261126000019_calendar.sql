-- P2 wave 2, calendar feed (docs/P2_WAVE2_CONTRACTS.md section 3).
--   * profiles.calendar_token (32 chars): the only key to the user's read-only calendar feed
--   * my_calendar_url(), rotate_calendar_token(): authenticated, return the feed URL
--   * calendar_feed(p_token): service role only, the rows the calendar-feed function turns into iCalendar
-- The feed URL is {supabase_url}/functions/v1/calendar-feed?t={token}. supabase_url comes from
-- app_config key 'supabase_url', else from the Vault secret 'project_url' that the cron jobs already use.

alter table public.profiles add column calendar_token text unique check (calendar_token ~ '^[A-Za-z0-9]{32}$');

-- Base URL of the project (no trailing slash), or null when neither setting exists.
create or replace function public.project_base_url() returns text
language plpgsql stable security definer set search_path = '' as $$
declare
  v_url text := nullif(trim((select value from public.app_config where key = 'supabase_url')), '');
begin
  if v_url is null and to_regclass('vault.decrypted_secrets') is not null then
    execute 'select decrypted_secret from vault.decrypted_secrets where name = $1 limit 1'
      into v_url using 'project_url';
  end if;
  return nullif(rtrim(trim(v_url), '/'), '');
end $$;
revoke execute on function public.project_base_url() from public, anon, authenticated;

create or replace function public.calendar_url_for(p_token text) returns text
language plpgsql stable security definer set search_path = '' as $$
declare
  v_base text := public.project_base_url();
begin
  if v_base is null then raise exception 'not_configured' using errcode = '55000'; end if;
  return v_base || '/functions/v1/calendar-feed?t=' || p_token;
end $$;
revoke execute on function public.calendar_url_for(text) from public, anon, authenticated;

-- Sets a fresh token for p_user (retrying on the very unlikely collision) and returns it.
create or replace function public.new_calendar_token(p_user uuid) returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_token text;
begin
  loop
    begin
      update public.profiles
         set calendar_token = public.random_text_token(32, 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789')
       where id = p_user
      returning calendar_token into v_token;
      if v_token is null then raise exception 'no_profile' using errcode = 'P0002'; end if;
      return v_token;
    exception when unique_violation then
      null; -- draw again
    end;
  end loop;
end $$;
revoke execute on function public.new_calendar_token(uuid) from public, anon, authenticated;

-- The caller's feed URL. Creates the token on first use.
create or replace function public.my_calendar_url() returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user  uuid := auth.uid();
  v_token text;
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  select calendar_token into v_token from public.profiles where id = v_user for update;
  if not found then raise exception 'no_profile' using errcode = 'P0002'; end if;
  if v_token is null then v_token := public.new_calendar_token(v_user); end if;
  return public.calendar_url_for(v_token);
end $$;

-- Replaces the caller's token (the old URL stops working at once) and returns the new feed URL.
create or replace function public.rotate_calendar_token() returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  perform 1 from public.profiles where id = v_user for update;
  if not found then raise exception 'no_profile' using errcode = 'P0002'; end if;
  return public.calendar_url_for(public.new_calendar_token(v_user));
end $$;

revoke execute on function public.my_calendar_url() from public, anon;
revoke execute on function public.rotate_calendar_token() from public, anon;
grant execute on function public.my_calendar_url() to authenticated;
grant execute on function public.rotate_calendar_token() to authenticated;

-- The feed's flights: every non-archived tracked flight that departs (best known time) in the last
-- 30 days or later, oldest first, at most 500. Null for an unknown token. Times are ISO-8601 UTC.
create or replace function public.calendar_feed(p_token text) returns jsonb
language sql stable security definer set search_path = '' as $$
  with owner as (
    select p.id from public.profiles p
     where p_token ~ '^[A-Za-z0-9]{32}$' and p.calendar_token = p_token
  ), legs as (
    select f.*, da.name as dep_name, da.city as dep_city, da.tz as dep_tz,
           aa.name as arr_name, aa.city as arr_city, aa.tz as arr_tz,
           coalesce(f.act_dep, f.est_dep, f.sched_dep) as best_dep
      from owner o
      join public.user_flights uf on uf.user_id = o.id and uf.archived_at is null
      join public.flights f on f.id = uf.flight_id
      left join public.airports da on da.iata = f.dep_iata
      left join public.airports aa on aa.iata = f.arr_iata
     where coalesce(f.act_dep, f.est_dep, f.sched_dep, f.flight_date::timestamptz) >= now() - interval '30 days'
  )
  select case when not exists (select 1 from owner) then null else coalesce((
    select jsonb_agg(jsonb_build_object(
             'flight_id', l.id,
             'carrier', l.carrier_iata, 'number', l.flight_number, 'date', l.flight_date,
             'airline', l.airline_name, 'status', l.status,
             'sched_dep', to_char(l.sched_dep at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'est_dep',   to_char(l.est_dep   at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'act_dep',   to_char(l.act_dep   at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'sched_arr', to_char(l.sched_arr at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'est_arr',   to_char(l.est_arr   at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'act_arr',   to_char(l.act_arr   at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'dep', jsonb_build_object('iata', l.dep_iata, 'name', l.dep_name, 'city', l.dep_city, 'tz', l.dep_tz,
                                       'terminal', l.dep_terminal, 'gate', l.dep_gate),
             'arr', jsonb_build_object('iata', l.arr_iata, 'name', l.arr_name, 'city', l.arr_city, 'tz', l.arr_tz,
                                       'terminal', l.arr_terminal, 'gate', l.arr_gate, 'belt', l.baggage_belt),
             'updated_at', to_char(l.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
           order by l.best_dep nulls last, l.id)
      from (select * from legs order by best_dep nulls last, id limit 500) l
  ), '[]'::jsonb) end;
$$;
revoke execute on function public.calendar_feed(text) from public, anon, authenticated;
grant execute on function public.calendar_feed(text) to service_role;
