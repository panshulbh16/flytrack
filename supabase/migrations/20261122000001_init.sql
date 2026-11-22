-- =============================================================================================
-- FlyTrack v0.1 — core schema
-- Rule: the app NEVER talks to the flight API. Edge Functions (service_role) write flight data;
-- users only read flight data and manage their own trackers/devices (enforced by RLS + grants).
-- =============================================================================================

-- ---------- helpers ----------------------------------------------------------------------------
create or replace function public.set_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------- profiles (1:1 with auth.users) -----------------------------------------------------
create table public.profiles (
  id           uuid primary key references auth.users (id) on delete cascade,
  display_name text check (char_length(display_name) <= 80),
  plan         text not null default 'free' check (plan in ('free', 'pro')),
  pro_until    timestamptz,               -- null + plan=pro = lifetime/manual grant
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create trigger profiles_updated_at before update on public.profiles
  for each row execute function public.set_updated_at();

create or replace function public.is_pro(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.profiles p
    where p.id = p_user and p.plan = 'pro' and (p.pro_until is null or p.pro_until > now())
  );
$$;

-- API-facing: answers only for the caller (is_pro(uuid) itself is internal, not executable by clients).
create or replace function public.current_user_is_pro() returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and public.is_pro(auth.uid());
$$;

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id) values (new.id) on conflict (id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- devices (FCM tokens) ---------------------------------------------------------------
create table public.devices (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null references auth.users (id) on delete cascade,
  fcm_token             text not null unique,
  platform              text not null default 'android' check (platform in ('android')),
  app_version           text,
  os_version            text,
  locale                text,
  timezone              text,
  notifications_enabled boolean not null default true,
  last_seen_at          timestamptz not null default now(),
  created_at            timestamptz not null default now()
);
create index devices_user_idx on public.devices (user_id);

-- ---------- airports (reference data, also bundled in the app for offline estimates) -----------
create table public.airports (
  iata    char(3) primary key,
  icao    char(4),
  name    text not null,
  city    text,
  country char(2),
  lat     double precision not null,
  lon     double precision not null,
  tz      text not null              -- IANA, e.g. Asia/Kolkata
);

-- ---------- flights: ONE row per unique flight leg, shared by all users -----------------------
create table public.flights (
  id                 uuid primary key default gen_random_uuid(),
  carrier_iata       text not null check (carrier_iata ~ '^[A-Z0-9]{2}$'),
  flight_number      text not null check (flight_number ~ '^[1-9][0-9]{0,3}[A-Z]?$'),
  flight_date        date not null,                      -- LOCAL departure date at origin
  dep_iata           char(3) not null,
  arr_iata           char(3) not null,
  carrier_icao       text,
  airline_name       text,

  status             text not null default 'unknown' check (status in (
                       'unknown','scheduled','check_in','boarding','gate_closed','departed','en_route',
                       'approaching','landed','arrived','cancelled','diverted')),
  sched_dep          timestamptz,
  est_dep            timestamptz,
  act_dep            timestamptz,
  dep_terminal       text,
  dep_gate           text,
  dep_checkin        text,
  sched_arr          timestamptz,
  est_arr            timestamptz,
  act_arr            timestamptz,
  arr_terminal       text,
  arr_gate           text,
  baggage_belt       text,
  aircraft_model     text,
  aircraft_reg       text,                               -- tail number
  delay_reason       text,                               -- provider/airline-given only

  live_lat           double precision,
  live_lon           double precision,
  live_alt_ft        integer,
  live_heading       double precision,
  live_speed_kts     double precision,
  live_updated_at    timestamptz,

  -- "Where's my plane" (Pro)
  inbound_flight_id   uuid references public.flights (id) on delete set null,
  inbound_checked_at  timestamptz,
  inbound_watch_until timestamptz,                       -- set on the INBOUND row: keep polling it until then
  prediction          jsonb,                             -- owned by the inbound evaluator only

  -- provider bookkeeping
  provider            text,
  provider_ref        text,
  provider_updated_at timestamptz,
  webhook_subscription_id text,
  webhook_expires_at  timestamptz,

  -- alerting + scheduler state (internal, never exposed to clients)
  alert_state        jsonb not null default '{}'::jsonb, -- what users were last told
  tracker_count      integer not null default 0,
  pro_tracker_count  integer not null default 0,
  last_polled_at     timestamptz,
  next_poll_at       timestamptz,
  lease_until        timestamptz,
  poll_error_count   integer not null default 0,
  last_error         text,

  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint flights_unique_leg unique (carrier_iata, flight_number, flight_date, dep_iata)
);
create trigger flights_updated_at before update on public.flights
  for each row execute function public.set_updated_at();
create index flights_due_idx on public.flights (next_poll_at) where next_poll_at is not null;
create index flights_inbound_idx on public.flights (inbound_flight_id) where inbound_flight_id is not null;
create index flights_reg_date_idx on public.flights (aircraft_reg, flight_date) where aircraft_reg is not null;

-- ---------- user_flights: who tracks what ------------------------------------------------------
create table public.user_flights (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  flight_id    uuid not null references public.flights (id) on delete cascade,
  pnr          text check (pnr ~ '^[A-Z0-9]{5,8}$'),
  seat         text check (char_length(seat) <= 5),
  cabin        text check (cabin in ('economy','premium_economy','business','first')),
  source       text not null default 'manual' check (source in ('manual','pdf','share','screenshot')),
  notify_prefs jsonb not null default '{}'::jsonb,     -- e.g. {"DEPARTED": false} to mute a type
  constraint user_flights_notify_prefs_shape check (
    jsonb_typeof(notify_prefs) = 'object'
    and octet_length(notify_prefs::text) <= 1024
    and not jsonb_path_exists(notify_prefs, '$.* ? (@.type() != "boolean")')),
  note         text check (char_length(note) <= 200),
  archived_at  timestamptz,
  created_at   timestamptz not null default now(),
  constraint user_flights_unique unique (user_id, flight_id)
);
create index user_flights_flight_idx on public.user_flights (flight_id) where archived_at is null;

-- ---------- history / events / push log / provider cost log ------------------------------------
create table public.flight_status_history (
  id             bigint generated always as identity primary key,
  flight_id      uuid not null references public.flights (id) on delete cascade,
  observed_at    timestamptz not null default now(),
  source         text not null check (source in ('poll','webhook','search','inbound',
                                                   'poll_stale','webhook_stale','search_stale','inbound_stale')),
  changed_fields text[] not null,
  snapshot       jsonb not null
);
create index history_flight_idx on public.flight_status_history (flight_id, observed_at desc);

create table public.flight_events (
  id         bigint generated always as identity primary key,
  flight_id  uuid not null references public.flights (id) on delete cascade,
  type       text not null check (type in (
               'DELAY','DELAY_REDUCED','SCHEDULE_CHANGE','GATE_ASSIGNED','GATE_CHANGE','TERMINAL_CHANGE','BOARDING','DEPARTED',
               'LANDED','BELT_ASSIGNED','BELT_CHANGE','CANCELLED','DIVERTED','REINSTATED','INBOUND_LATE')),
  severity   text not null check (severity in ('critical','info')),
  pro_only   boolean not null default false,
  old_value  text,
  new_value  text,
  payload    jsonb not null default '{}'::jsonb,
  dedupe_key text not null unique,                     -- exactly-once alerts, even with poll+webhook races
  created_at timestamptz not null default now(),
  pushed_at  timestamptz
);
create index events_flight_idx on public.flight_events (flight_id, created_at desc);

create table public.push_log (
  id             bigint generated always as identity primary key,
  event_id       bigint references public.flight_events (id) on delete cascade,
  device_id      uuid references public.devices (id) on delete set null,
  user_id        uuid,
  kind           text not null check (kind in ('alert','sync')),
  status         text not null check (status in ('sent','failed','invalid_token','dry_run')),
  fcm_message_id text,
  error          text,
  created_at     timestamptz not null default now()
);
create index push_log_created_idx on public.push_log (created_at);

create table public.provider_calls (
  id          bigint generated always as identity primary key,
  provider    text not null,
  endpoint    text not null,
  status_code integer,
  units       numeric not null default 0,
  duration_ms integer,
  flight_id   uuid references public.flights (id) on delete set null,
  user_id     uuid,
  created_at  timestamptz not null default now()
);
create index provider_calls_user_idx on public.provider_calls (user_id, created_at) where user_id is not null;
create index provider_calls_created_idx on public.provider_calls (created_at);

-- ---------- tracker counters (drive polling + Pro cadence) -------------------------------------
create or replace function public.refresh_flight_trackers(p_flight uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_total int;
  v_pro   int;
begin
  -- Serialize recounts per flight (NO KEY UPDATE doesn't conflict with the FK KEY SHARE lock of inserts),
  -- so concurrent track/untrack can never leave a stale count (e.g. tracker_count=0 → never polled).
  perform 1 from public.flights where id = p_flight for no key update;
  select count(*), count(*) filter (where public.is_pro(uf.user_id))
    into v_total, v_pro
    from public.user_flights uf
   where uf.flight_id = p_flight and uf.archived_at is null;

  update public.flights f
     set tracker_count = v_total,
         pro_tracker_count = v_pro,
         -- resume polling if it had stopped but the flight is still relevant
         next_poll_at = case
           when v_total > 0 and f.next_poll_at is null
                and coalesce(f.act_arr, f.est_arr, f.sched_arr, now()) > now() - interval '45 minutes'
                and (f.last_polled_at is null or f.last_polled_at < now() - interval '15 minutes') -- no toggle abuse
             then now()
           else f.next_poll_at end
   where f.id = p_flight;
end $$;

create or replace function public.user_flights_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op in ('INSERT', 'UPDATE') then
    perform public.refresh_flight_trackers(new.flight_id);
  end if;
  if tg_op = 'DELETE' or (tg_op = 'UPDATE' and old.flight_id is distinct from new.flight_id) then
    perform public.refresh_flight_trackers(old.flight_id);
  end if;
  return null;
end $$;
create trigger user_flights_counts after insert or delete or update of archived_at, flight_id
  on public.user_flights for each row execute function public.user_flights_changed();

create or replace function public.profile_plan_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  for r in select flight_id from public.user_flights where user_id = new.id and archived_at is null loop
    perform public.refresh_flight_trackers(r.flight_id);
  end loop;
  return null;
end $$;
create trigger profiles_plan_counts after update of plan, pro_until on public.profiles
  for each row execute function public.profile_plan_changed();

-- Cost guard: limit active tracked flights per user (free 10, pro 50).
create or replace function public.enforce_tracking_limit() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_active int;
  v_limit  int;
begin
  if new.archived_at is not null then return new; end if;
  -- one check at a time per user, otherwise parallel inserts all see "9 active" and pass
  perform pg_advisory_xact_lock(hashtextextended('tracking_limit:' || new.user_id::text, 0));
  v_limit := case when public.is_pro(new.user_id) then 50 else 10 end;
  select count(*) into v_active
    from public.user_flights uf join public.flights f on f.id = uf.flight_id
   where uf.user_id = new.user_id and uf.archived_at is null and uf.id <> new.id
     and coalesce(f.act_arr, f.est_arr, f.sched_arr, now()) > now() - interval '1 day';
  if v_active >= v_limit then
    raise exception 'tracking_limit_reached' using errcode = 'P0001',
      hint = 'Archive an old flight or upgrade to Pro';
  end if;
  return new;
end $$;
create trigger user_flights_limit before insert or update of archived_at on public.user_flights
  for each row execute function public.enforce_tracking_limit();

-- ---------- RPCs ---------------------------------------------------------------------------------
-- Poller: atomically lease due flights. SKIP LOCKED → concurrent invocations never double-poll.
create or replace function public.claim_due_flights(p_limit int default 10, p_lease_seconds int default 120)
returns setof public.flights
language sql security definer set search_path = '' as $$
  with due as (
    select id from public.flights
     where next_poll_at <= now()
       and (tracker_count > 0 or inbound_watch_until > now())
       and (lease_until is null or lease_until < now())
     order by next_poll_at
     limit p_limit
     for update skip locked
  )
  update public.flights f
     set lease_until = now() + make_interval(secs => p_lease_seconds)
    from due
   where f.id = due.id
  returning f.*;
$$;

create or replace function public.get_flight_recipients(p_flight_id uuid)
returns table (user_id uuid, is_pro boolean, notify_prefs jsonb, device_id uuid, fcm_token text)
language sql stable security definer set search_path = '' as $$
  select uf.user_id, public.is_pro(uf.user_id), uf.notify_prefs, d.id, d.fcm_token
    from public.user_flights uf
    join public.devices d on d.user_id = uf.user_id and d.notifications_enabled
   where uf.flight_id = p_flight_id and uf.archived_at is null;
$$;

-- App: register/refresh this phone's FCM token (token may move between accounts on one phone).
create or replace function public.register_device(
  p_token text, p_app_version text default null, p_os_version text default null,
  p_locale text default null, p_timezone text default null
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_token is null or char_length(p_token) < 20 or char_length(p_token) > 4096 then
    raise exception 'invalid_token' using errcode = '22023';
  end if;
  insert into public.devices (user_id, fcm_token, app_version, os_version, locale, timezone, last_seen_at)
  values (auth.uid(), p_token, p_app_version, p_os_version, p_locale, p_timezone, now())
  on conflict (fcm_token) do update
     set user_id = excluded.user_id, app_version = excluded.app_version, os_version = excluded.os_version,
         locale = excluded.locale, timezone = excluded.timezone, last_seen_at = now(),
         -- keep the user's own mute; reset only when the phone moved to another account
         notifications_enabled = case when public.devices.user_id is distinct from excluded.user_id
                                      then true else public.devices.notifications_enabled end
  returning id into v_id;
  return v_id;
end $$;

create or replace function public.unregister_device(p_token text) returns void
language sql security definer set search_path = '' as $$
  delete from public.devices where fcm_token = p_token and user_id = auth.uid();
$$;

-- ---------- public read model for the app --------------------------------------------------------
-- Clients never read public.flights directly (it holds scheduler/alert internals).
-- Pro-only fields (inbound plane, prediction) are masked for free users here, server-side.
create view public.flight_view as
select
  f.id, f.carrier_iata, f.flight_number, f.flight_date, f.dep_iata, f.arr_iata, f.airline_name, f.status,
  f.sched_dep, f.est_dep, f.act_dep, f.dep_terminal, f.dep_gate, f.dep_checkin,
  f.sched_arr, f.est_arr, f.act_arr, f.arr_terminal, f.arr_gate, f.baggage_belt,
  f.aircraft_model, f.aircraft_reg, f.delay_reason,
  f.live_lat, f.live_lon, f.live_alt_ft, f.live_heading, f.live_speed_kts, f.live_updated_at,
  f.last_polled_at, f.updated_at,
  da.name as dep_name, da.city as dep_city, da.lat as dep_lat, da.lon as dep_lon, da.tz as dep_tz,
  aa.name as arr_name, aa.city as arr_city, aa.lat as arr_lat, aa.lon as arr_lon, aa.tz as arr_tz,
  case when public.current_user_is_pro() then f.inbound_flight_id end as inbound_flight_id,
  case when public.current_user_is_pro() then f.prediction - 'alertedBucket' - 'inboundReg' end as prediction,
  (f.prediction is not null and (f.prediction ->> 'lateMin')::int >= 10) as has_pro_insight  -- teaser for paywall
from public.flights f
left join public.airports da on da.iata = f.dep_iata
left join public.airports aa on aa.iata = f.arr_iata
where auth.uid() is not null;

-- ---------- RLS + grants -------------------------------------------------------------------------
alter table public.profiles              enable row level security;
alter table public.devices               enable row level security;
alter table public.airports              enable row level security;
alter table public.flights               enable row level security;
alter table public.user_flights          enable row level security;
alter table public.flight_status_history enable row level security;
alter table public.flight_events         enable row level security;
alter table public.push_log              enable row level security;
alter table public.provider_calls        enable row level security;

-- Supabase grants everything to anon/authenticated by default; tighten explicitly.
revoke all on public.flights, public.push_log, public.provider_calls from anon, authenticated;
revoke all on public.profiles, public.devices, public.user_flights, public.flight_status_history,
              public.flight_events, public.airports from anon;
revoke insert, update, delete on public.airports, public.flight_status_history, public.flight_events
  from authenticated;
revoke all on public.flight_view from anon;
grant select on public.flight_view to authenticated;

-- profiles: read own; only display_name is user-editable (plan is set by billing, server-side)
revoke insert, update, delete on public.profiles from authenticated;
grant update (display_name) on public.profiles to authenticated;
create policy profiles_select_own on public.profiles for select to authenticated using (id = auth.uid());
create policy profiles_update_own on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- devices: read/delete own, toggle notifications; create/update via register_device()
revoke insert, update on public.devices from authenticated;
grant update (notifications_enabled) on public.devices to authenticated;
create policy devices_select_own on public.devices for select to authenticated using (user_id = auth.uid());
create policy devices_update_own on public.devices for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy devices_delete_own on public.devices for delete to authenticated using (user_id = auth.uid());

-- airports: public reference data
create policy airports_read on public.airports for select to authenticated using (true);

-- user_flights: full control of own rows (flight_id cannot be changed after insert)
revoke update on public.user_flights from authenticated;
grant update (pnr, seat, cabin, notify_prefs, note, archived_at) on public.user_flights to authenticated;
create policy uf_select_own on public.user_flights for select to authenticated using (user_id = auth.uid());
create policy uf_insert_own on public.user_flights for insert to authenticated with check (user_id = auth.uid());
create policy uf_update_own on public.user_flights for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy uf_delete_own on public.user_flights for delete to authenticated using (user_id = auth.uid());

-- history/events: only for flights I track; Pro-only events only for Pro users
create policy history_select_tracked on public.flight_status_history for select to authenticated using (
  exists (select 1 from public.user_flights uf
           where uf.flight_id = flight_status_history.flight_id and uf.user_id = auth.uid()));
create policy events_select_tracked on public.flight_events for select to authenticated using (
  exists (select 1 from public.user_flights uf
           where uf.flight_id = flight_events.flight_id and uf.user_id = auth.uid())
  and (not pro_only or public.current_user_is_pro()));

-- function execute rights
revoke execute on function public.is_pro(uuid) from public, anon, authenticated;
revoke execute on function public.current_user_is_pro() from public, anon;
grant execute on function public.current_user_is_pro() to authenticated;
revoke execute on function public.claim_due_flights(int, int) from public, anon, authenticated;
revoke execute on function public.get_flight_recipients(uuid) from public, anon, authenticated;
revoke execute on function public.refresh_flight_trackers(uuid) from public, anon, authenticated;
revoke execute on function public.register_device(text, text, text, text, text) from public, anon;
revoke execute on function public.unregister_device(text) from public, anon;
grant execute on function public.claim_due_flights(int, int) to service_role;
grant execute on function public.get_flight_recipients(uuid) to service_role;
grant execute on function public.refresh_flight_trackers(uuid) to service_role;
grant execute on function public.register_device(text, text, text, text, text) to authenticated;
grant execute on function public.unregister_device(text) to authenticated;
