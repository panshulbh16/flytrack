-- P1 security fixes (docs/P1_CONTRACTS.md section 5):
--   * flight_view only returns rows the caller may read (can_read_flight)
--   * reserve_search_quota(): atomic per-user quota for provider calls from search-flight
--   * register_device(): at most 5 devices per user, the least recently seen ones are evicted
-- search-flight answers from its own whitelisted columns (service role), so Add-flight does not
-- depend on flight_view for flights the user does not track yet.

-- ---------- can_read_flight ----------------------------------------------------------------------
-- A flight is readable when the caller tracks it (archived rows included: they stay in the app's
-- history), or when it is the inbound leg of a flight the caller tracks as Pro / as their trial flight
-- ("Where's my plane"). Security definer, so it can look at user_flights and flights for the caller only.
create or replace function public.can_read_flight(p_flight uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and (
    exists (select 1 from public.user_flights uf
             where uf.user_id = auth.uid() and uf.flight_id = p_flight)
    or exists (select 1 from public.flights f
                 join public.user_flights uf on uf.flight_id = f.id and uf.user_id = auth.uid()
                where f.inbound_flight_id = p_flight
                  and public.is_pro_for_flight(auth.uid(), f.id))
  );
$$;

create or replace view public.flight_view as
select
  f.id, f.carrier_iata, f.flight_number, f.flight_date, f.dep_iata, f.arr_iata, f.airline_name, f.status,
  f.sched_dep, f.est_dep, f.act_dep, f.dep_terminal, f.dep_gate, f.dep_checkin,
  f.sched_arr, f.est_arr, f.act_arr, f.arr_terminal, f.arr_gate, f.baggage_belt,
  f.aircraft_model, f.aircraft_reg, f.delay_reason,
  f.live_lat, f.live_lon, f.live_alt_ft, f.live_heading, f.live_speed_kts, f.live_updated_at,
  f.last_polled_at, f.updated_at,
  da.name as dep_name, da.city as dep_city, da.lat as dep_lat, da.lon as dep_lon, da.tz as dep_tz,
  aa.name as arr_name, aa.city as arr_city, aa.lat as arr_lat, aa.lon as arr_lon, aa.tz as arr_tz,
  case when public.current_user_is_pro_for_flight(f.id) then f.inbound_flight_id end as inbound_flight_id,
  case when public.current_user_is_pro_for_flight(f.id) then f.prediction - 'alertedBucket' - 'inboundReg' end as prediction,
  (f.prediction is not null and (f.prediction ->> 'lateMin')::int >= 10) as has_pro_insight  -- teaser for paywall
from public.flights f
left join public.airports da on da.iata = f.dep_iata
left join public.airports aa on aa.iata = f.arr_iata
where auth.uid() is not null and public.can_read_flight(f.id);

revoke all on public.flight_view from anon;
grant select on public.flight_view to authenticated;
revoke execute on function public.can_read_flight(uuid) from public, anon;
grant execute on function public.can_read_flight(uuid) to authenticated, service_role;

-- ---------- search quota -------------------------------------------------------------------------
-- One row per user and day (Indian calendar day, so it resets at midnight IST).
create table public.search_quota_usage (
  user_id uuid not null,
  day     date not null,
  uses    integer not null default 0 check (uses >= 0),
  primary key (user_id, day)
);
alter table public.search_quota_usage enable row level security;
revoke all on public.search_quota_usage from public, anon, authenticated;

-- Reserves one provider call for p_user. Returns false once p_limit is used up today.
-- The per-user advisory lock serialises callers, and the conditional upsert never counts past the limit.
create or replace function public.reserve_search_quota(p_user uuid, p_limit int) returns boolean
language plpgsql security definer set search_path = '' as $$
declare
  v_day  date := (now() at time zone 'Asia/Kolkata')::date;
  v_uses int;
begin
  if p_user is null or p_limit is null or p_limit < 1 then return false; end if;
  perform pg_advisory_xact_lock(hashtextextended('search_quota:' || p_user::text, 0));
  insert into public.search_quota_usage as q (user_id, day, uses)
  values (p_user, v_day, 1)
  on conflict (user_id, day) do update
     set uses = q.uses + 1
   where q.uses < p_limit
  returning q.uses into v_uses;
  -- old days are dead weight; trim this user's rows while we hold their lock
  delete from public.search_quota_usage where user_id = p_user and day < v_day - 7;
  return v_uses is not null;
end $$;

revoke all on function public.reserve_search_quota(uuid, int) from public, anon, authenticated;
grant execute on function public.reserve_search_quota(uuid, int) to service_role;

-- ---------- device cap ---------------------------------------------------------------------------
-- Same signature as 20261122000004_ios_devices.sql; adds the 5-devices-per-user cap. Evicting a
-- device also ends its Live Activities (on delete cascade).
create or replace function public.register_device(
  p_token text,
  p_app_version text default null,
  p_os_version text default null,
  p_locale text default null,
  p_timezone text default null,
  p_platform text default 'android'
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_token is null or char_length(p_token) < 20 or char_length(p_token) > 4096 then
    raise exception 'invalid_token' using errcode = '22023';
  end if;
  if p_platform is null or p_platform not in ('android', 'ios') then
    raise exception 'invalid_platform' using errcode = '22023';
  end if;
  -- one registration at a time per user, so parallel calls cannot both keep 5 others
  perform pg_advisory_xact_lock(hashtextextended('register_device:' || auth.uid()::text, 0));
  insert into public.devices (user_id, fcm_token, platform, app_version, os_version, locale, timezone, last_seen_at)
  values (auth.uid(), p_token, p_platform, p_app_version, p_os_version, p_locale, p_timezone, now())
  on conflict (fcm_token) do update
     set user_id = excluded.user_id,
         platform = excluded.platform,
         app_version = excluded.app_version,
         os_version = excluded.os_version,
         locale = excluded.locale,
         timezone = excluded.timezone,
         last_seen_at = now(),
         -- keep the user's own mute; reset only when the phone moved to another account
         notifications_enabled = case when public.devices.user_id is distinct from excluded.user_id
                                      then true else public.devices.notifications_enabled end
  returning id into v_id;

  delete from public.devices d
   where d.user_id = auth.uid()
     and d.id <> v_id
     and d.id not in (select k.id from public.devices k
                       where k.user_id = auth.uid() and k.id <> v_id
                       order by k.last_seen_at desc, k.created_at desc, k.id
                       limit 4);
  return v_id;
end $$;

revoke execute on function public.register_device(text, text, text, text, text, text) from public, anon;
grant execute on function public.register_device(text, text, text, text, text, text) to authenticated;
