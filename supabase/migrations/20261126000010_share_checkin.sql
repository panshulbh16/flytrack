-- P1 share + check-in (docs/P1_CONTRACTS.md sections 2, 3 and 5):
--   * flight_shares + create_flight_share() / revoke_flight_share() + get_shared_flight() (public page)
--   * checkin_rules, flights.checkin_* columns kept by a trigger, CHECKIN_OPEN / CHECKIN_CLOSING events
--   * user_flights.source accepts 'scan' (boarding-pass barcode)

-- ---------- user_flights.source: 'scan' -----------------------------------------------------------
alter table public.user_flights
  drop constraint user_flights_source_check,
  add constraint user_flights_source_check
    check (source in ('manual','pdf','share','screenshot','gmail','scan'));

-- ---------- flight_events.type: check-in reminders -------------------------------------------------
alter table public.flight_events
  drop constraint flight_events_type_check,
  add constraint flight_events_type_check check (type in (
    'DELAY','DELAY_REDUCED','SCHEDULE_CHANGE','GATE_ASSIGNED','GATE_CHANGE','TERMINAL_CHANGE','BOARDING','DEPARTED',
    'LANDED','BELT_ASSIGNED','BELT_CHANGE','CANCELLED','DIVERTED','REINSTATED','INBOUND_LATE',
    'CHECKIN_OPEN','CHECKIN_CLOSING'));

-- =================================================================================================
-- Share a flight
-- =================================================================================================
-- A token is the only secret of a share link: 32 random bytes (two v4 UUIDs, 244 random bits), base64url,
-- 43 characters. gen_random_uuid() is core Postgres (13+), so this needs no pgcrypto/extensions schema.
create or replace function public.new_share_token() returns text
language sql volatile set search_path = '' as $$
  select translate(
    encode(decode(replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''), 'hex'), 'base64'),
    E'+/=\n', '-_');
$$;

create table public.flight_shares (
  id         uuid primary key default gen_random_uuid(),
  token      text not null unique default public.new_share_token()
               check (token ~ '^[A-Za-z0-9_-]{22,128}$'),
  user_id    uuid not null references auth.users (id) on delete cascade,
  flight_id  uuid not null references public.flights (id) on delete cascade,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  expires_at timestamptz not null  -- the flight's arrival + 2 days, set by create_flight_share()
);
create index flight_shares_owner_idx on public.flight_shares (user_id, flight_id) where revoked_at is null;
create index flight_shares_flight_idx on public.flight_shares (flight_id);

alter table public.flight_shares enable row level security;
revoke all on public.flight_shares from public, anon, authenticated;
grant select on public.flight_shares to authenticated;
grant select, insert, update, delete on public.flight_shares to service_role;
create policy flight_shares_select_own on public.flight_shares for select to authenticated
  using (user_id = auth.uid());

-- The caller's active token for a flight they track, or a new one.
create or replace function public.create_flight_share(p_flight_id uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v_uid   uuid := auth.uid();
  v_exp   timestamptz;
  v_token text;
begin
  if v_uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  select coalesce(f.act_arr, f.est_arr, f.sched_arr, (f.flight_date + 1)::timestamptz) + interval '2 days'
    into v_exp
    from public.flights f where f.id = p_flight_id;
  if not found then raise exception 'flight_not_found' using errcode = 'P0002'; end if;
  if not exists (select 1 from public.user_flights uf where uf.user_id = v_uid and uf.flight_id = p_flight_id) then
    raise exception 'not_tracking' using errcode = '42501';
  end if;
  -- one call at a time per user and flight, so a double tap returns one token
  perform pg_advisory_xact_lock(hashtextextended('flight_share:' || v_uid::text || ':' || p_flight_id::text, 0));
  select s.token into v_token
    from public.flight_shares s
   where s.user_id = v_uid and s.flight_id = p_flight_id and s.revoked_at is null and s.expires_at > now()
   order by s.created_at desc
   limit 1;
  if v_token is not null then return v_token; end if;
  insert into public.flight_shares (user_id, flight_id, expires_at)
  values (v_uid, p_flight_id, greatest(v_exp, now() + interval '1 day'))
  returning token into v_token;
  return v_token;
end $$;

-- Owner only. Unknown or someone else's token: nothing happens (no oracle for other people's tokens).
create or replace function public.revoke_flight_share(p_token text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  update public.flight_shares
     set revoked_at = now()
   where token = p_token and user_id = auth.uid() and revoked_at is null;
end $$;

-- Untracking a flight ends its share links.
create or replace function public.user_flights_revoke_shares() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.flight_shares set revoked_at = now()
   where user_id = old.user_id and flight_id = old.flight_id and revoked_at is null;
  return null;
end $$;
create trigger user_flights_revoke_shares after delete on public.user_flights
  for each row execute function public.user_flights_revoke_shares();

-- Public fields of a shared flight, or null for an unknown / revoked / expired token.
-- Service role only (the shared-flight Edge Function). Nothing about the user, PNR, seat or device.
-- A flight that runs later than planned keeps its link until 2 days after its real arrival.
create or replace function public.get_shared_flight(p_token text) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'carrier', f.carrier_iata, 'number', f.flight_number, 'date', f.flight_date, 'airline', f.airline_name,
    'status', f.status,
    'sched_dep', f.sched_dep, 'est_dep', f.est_dep, 'act_dep', f.act_dep,
    'sched_arr', f.sched_arr, 'est_arr', f.est_arr, 'act_arr', f.act_arr,
    'dep', jsonb_build_object('iata', f.dep_iata, 'name', da.name, 'city', da.city, 'lat', da.lat, 'lon', da.lon,
                              'tz', da.tz, 'terminal', f.dep_terminal, 'gate', f.dep_gate),
    'arr', jsonb_build_object('iata', f.arr_iata, 'name', aa.name, 'city', aa.city, 'lat', aa.lat, 'lon', aa.lon,
                              'tz', aa.tz, 'terminal', f.arr_terminal, 'gate', f.arr_gate, 'belt', f.baggage_belt),
    'aircraft', jsonb_build_object('model', f.aircraft_model),
    'live', case when f.live_lat is not null and f.live_lon is not null then
              jsonb_build_object('lat', f.live_lat, 'lon', f.live_lon, 'alt_ft', f.live_alt_ft,
                                 'speed_kts', f.live_speed_kts, 'heading', f.live_heading, 'at', f.live_updated_at)
            end,
    'updated_at', f.updated_at)
  from public.flight_shares s
  join public.flights f on f.id = s.flight_id
  left join public.airports da on da.iata = f.dep_iata
  left join public.airports aa on aa.iata = f.arr_iata
  where p_token is not null and s.token = p_token and s.revoked_at is null
    and greatest(s.expires_at, coalesce(f.act_arr, f.est_arr, f.sched_arr) + interval '2 days') > now();
$$;

-- =================================================================================================
-- Check-in reminders
-- =================================================================================================
create table public.checkin_rules (
  carrier_iata               text primary key check (carrier_iata ~ '^[A-Z0-9]{2}$'),
  opens_hours_before         integer not null check (opens_hours_before between 0 and 720),
  closes_minutes_before      integer not null check (closes_minutes_before between 0 and 1440),
  closes_minutes_before_intl integer not null check (closes_minutes_before_intl between 0 and 1440),
  checkin_url                text check (checkin_url ~ '^https://[^\s]+$'),
  verified                   boolean not null default false,
  notes                      text,
  updated_at                 timestamptz not null default now()
);
create trigger checkin_rules_updated_at before update on public.checkin_rules
  for each row execute function public.set_updated_at();

alter table public.checkin_rules enable row level security;
revoke all on public.checkin_rules from public, anon, authenticated;
grant select on public.checkin_rules to authenticated;
grant select, insert, update, delete on public.checkin_rules to service_role;
create policy checkin_rules_read on public.checkin_rules for select to authenticated using (true);

insert into public.checkin_rules
  (carrier_iata, opens_hours_before, closes_minutes_before, closes_minutes_before_intl, checkin_url, verified, notes)
values
  ('6E', 48, 60, 75, 'https://www.goindigo.in/web-check-in.html', true, 'IndiGo web check-in'),
  ('AI', 48, 60, 75, 'https://www.airindia.com/in/en/manage/web-checkin.html', false, 'Air India: usual times, not confirmed'),
  ('IX', 48, 60, 75, 'https://www.airindiaexpress.com/checkin-home', false, 'Air India Express: usual times, not confirmed'),
  ('QP', 48, 60, 75, 'https://www.akasaair.com/check-in', false, 'Akasa Air: usual times, not confirmed'),
  ('SG', 48, 60, 75, 'https://www.spicejet.com/check-in', false, 'SpiceJet: usual times, not confirmed')
on conflict (carrier_iata) do nothing;

alter table public.flights
  add column checkin_opens_at         timestamptz,
  add column checkin_closes_at        timestamptz,
  add column checkin_open_notified    boolean not null default false,
  add column checkin_closing_notified boolean not null default false;

-- Window from the carrier's rule, counted from the estimated departure when known, else the timetable
-- (the apps use the same rule). International = the two airports' countries differ; an unknown country
-- counts as international, which only makes the close earlier.
create or replace function public.flights_set_checkin() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  r      public.checkin_rules;
  v_base timestamptz := coalesce(new.est_dep, new.sched_dep);
  v_intl boolean;
begin
  select * into r from public.checkin_rules where carrier_iata = new.carrier_iata;
  if r.carrier_iata is null or v_base is null then
    new.checkin_opens_at := null;
    new.checkin_closes_at := null;
    return new;
  end if;
  select (da.country is null or aa.country is null or da.country <> aa.country) into v_intl
    from (select 1) one
    left join public.airports da on da.iata = new.dep_iata
    left join public.airports aa on aa.iata = new.arr_iata;
  new.checkin_opens_at := v_base - make_interval(hours => greatest(r.opens_hours_before, 0));
  new.checkin_closes_at := v_base - make_interval(mins => greatest(
    case when v_intl then r.closes_minutes_before_intl else r.closes_minutes_before end, 0));
  return new;
end $$;
create trigger flights_checkin_window before insert or update on public.flights
  for each row execute function public.flights_set_checkin();

-- A rule edit re-times the carrier's upcoming flights (the trigger above recomputes on any update).
create or replace function public.checkin_rules_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.flights f
     set checkin_opens_at = null
   where f.carrier_iata in (coalesce(new.carrier_iata, old.carrier_iata), coalesce(old.carrier_iata, new.carrier_iata))
     and coalesce(f.est_dep, f.sched_dep) > now();
  return null;
end $$;
create trigger checkin_rules_retime after insert or update or delete on public.checkin_rules
  for each row execute function public.checkin_rules_changed();

-- Backfill the window of flights that have not left yet.
update public.flights set checkin_opens_at = null where coalesce(est_dep, sched_dep) > now();

create index flights_checkin_due_idx on public.flights (checkin_closes_at)
  where checkin_closes_at is not null and not checkin_closing_notified;

-- Flights with a check-in reminder due now: tracked, not gone, window not closed, and either the open
-- reminder (window open) or the closing one (last 30 minutes) not sent yet. No provider involved.
create or replace function public.due_checkin_flights(p_limit int default 100)
returns setof public.flights
language sql stable security definer set search_path = '' as $$
  select f.* from public.flights f
   where f.checkin_closes_at is not null
     and not f.checkin_closing_notified
     and f.checkin_closes_at > now()
     and f.tracker_count > 0
     and f.act_dep is null
     and f.status not in ('cancelled', 'diverted', 'departed', 'en_route', 'approaching', 'landed', 'arrived')
     and ((not f.checkin_open_notified and f.checkin_opens_at <= now())
          or f.checkin_closes_at - interval '30 minutes' <= now())
   order by f.checkin_closes_at
   limit greatest(coalesce(p_limit, 100), 1);
$$;

-- ---------- function execute rights ---------------------------------------------------------------
revoke execute on function public.new_share_token() from public, anon, authenticated;
revoke execute on function public.create_flight_share(uuid) from public, anon;
grant execute on function public.create_flight_share(uuid) to authenticated;
revoke execute on function public.revoke_flight_share(text) from public, anon;
grant execute on function public.revoke_flight_share(text) to authenticated;
revoke execute on function public.get_shared_flight(text) from public, anon, authenticated;
grant execute on function public.get_shared_flight(text) to service_role;
revoke execute on function public.due_checkin_flights(int) from public, anon, authenticated;
grant execute on function public.due_checkin_flights(int) to service_role;
revoke execute on function public.user_flights_revoke_shares() from public, anon, authenticated;
revoke execute on function public.flights_set_checkin() from public, anon, authenticated;
revoke execute on function public.checkin_rules_changed() from public, anon, authenticated;
