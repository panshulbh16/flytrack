-- P1 billing (docs/P1_CONTRACTS.md section 1): RevenueCat webhook bookkeeping, the free Pro trial on a
-- user's first flight, and is_pro_for_flight() wherever Pro decides behaviour.
-- profiles.plan / profiles.pro_until stay the server's source of truth; only Edge Functions (service
-- role) write them, from RevenueCat.

-- ---------- trial: Pro for free on the first flight ----------------------------------------------
alter table public.profiles
  add column trial_flight_id uuid references public.flights (id) on delete set null,
  add column trial_used boolean not null default false;

-- trial_flight_id → flights is "on delete set null": index it so deleting flights doesn't scan profiles.
create index profiles_trial_flight_idx on public.profiles (trial_flight_id) where trial_flight_id is not null;

-- Users who already track flights have had their "first flight"; the trial is for new users only.
update public.profiles p set trial_used = true
 where exists (select 1 from public.user_flights uf where uf.user_id = p.id);

create or replace function public.is_pro_for_flight(p_user uuid, p_flight uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.is_pro(p_user)
      or exists (select 1 from public.profiles p
                  where p.id = p_user and p.trial_flight_id is not null and p.trial_flight_id = p_flight);
$$;

-- API-facing twin of current_user_is_pro(): answers only for the caller (used by flight_view).
create or replace function public.current_user_is_pro_for_flight(p_flight uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and public.is_pro_for_flight(auth.uid(), p_flight);
$$;

-- The first user_flights row of a non-Pro user who never had a trial becomes the trial flight.
-- The WHERE re-check under the profile row lock makes two parallel inserts grant only one trial.
-- Named so it fires before user_flights_counts (AFTER triggers run in name order), so the very
-- first recount already sees the trial.
create or replace function public.assign_trial_flight() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  -- an archived insert (e.g. a past trip added for history) is not tracking, so it keeps the trial
  if new.archived_at is null and not public.is_pro(new.user_id) then
    update public.profiles
       set trial_flight_id = new.flight_id, trial_used = true
     where id = new.user_id and not trial_used;
  end if;
  return null;
end $$;
create trigger user_flights_assign_trial after insert on public.user_flights
  for each row execute function public.assign_trial_flight();

-- Recount Pro trackers when the trial changes too (e.g. its flight row is deleted).
drop trigger profiles_plan_counts on public.profiles;
create trigger profiles_plan_counts after update of plan, pro_until, trial_flight_id on public.profiles
  for each row execute function public.profile_plan_changed();

-- trial columns are server-managed: clients keep their column grant on display_name only.

-- ---------- Pro decisions per flight -------------------------------------------------------------
create or replace function public.refresh_flight_trackers(p_flight uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_total int;
  v_pro   int;
begin
  -- Serialize recounts per flight (NO KEY UPDATE doesn't conflict with the FK KEY SHARE lock of inserts),
  -- so concurrent track/untrack can never leave a stale count (e.g. tracker_count=0 → never polled).
  perform 1 from public.flights where id = p_flight for no key update;
  select count(*), count(*) filter (where public.is_pro_for_flight(uf.user_id, p_flight))
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

create or replace function public.get_flight_recipients(p_flight_id uuid)
returns table (user_id uuid, is_pro boolean, notify_prefs jsonb, device_id uuid, fcm_token text)
language sql stable security definer set search_path = '' as $$
  select uf.user_id, public.is_pro_for_flight(uf.user_id, p_flight_id), uf.notify_prefs, d.id, d.fcm_token
    from public.user_flights uf
    join public.devices d on d.user_id = uf.user_id and d.notifications_enabled
   where uf.flight_id = p_flight_id and uf.archived_at is null;
$$;

-- flight_view: Pro masking per flight (the trial flight shows Pro data). Row filtering by
-- can_read_flight() is added in 20261126000009_security.sql.
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
where auth.uid() is not null;

-- Pro-only flight_events (inbound-delay predictions) are pushed to the trial flight's user
-- (get_flight_recipients), so the in-app event list must show them on that flight too.
drop policy events_select_tracked on public.flight_events;
create policy events_select_tracked on public.flight_events for select to authenticated using (
  exists (select 1 from public.user_flights uf
           where uf.flight_id = flight_events.flight_id and uf.user_id = auth.uid())
  and (not pro_only or public.current_user_is_pro_for_flight(flight_events.flight_id)));

-- ---------- RevenueCat webhook idempotency -------------------------------------------------------
-- One row per RevenueCat event.id. processed_at is set once the event was applied, so a retry of an
-- event that failed half-way is applied again, and a duplicate delivery is a no-op.
-- payload is the event without subscriber_attributes (those can hold e-mail / phone numbers).
create table public.billing_events (
  id           text primary key check (char_length(id) between 1 and 200),
  type         text not null,
  app_user_id  text,
  product_id   text,
  purchased_at timestamptz,
  expires_at   timestamptz,
  environment  text,
  payload      jsonb not null default '{}'::jsonb,
  outcome      text,
  received_at  timestamptz not null default now(),
  processed_at timestamptz
);
create index billing_events_trip_idx on public.billing_events (app_user_id, purchased_at desc)
  where type = 'NON_RENEWING_PURCHASE';

alter table public.billing_events enable row level security;
revoke all on public.billing_events from public, anon, authenticated;
grant select, insert, update on public.billing_events to service_role;

-- ---------- function execute rights ---------------------------------------------------------------
revoke execute on function public.is_pro_for_flight(uuid, uuid) from public, anon, authenticated;
grant execute on function public.is_pro_for_flight(uuid, uuid) to service_role;
revoke execute on function public.current_user_is_pro_for_flight(uuid) from public, anon;
grant execute on function public.current_user_is_pro_for_flight(uuid) to authenticated;
revoke execute on function public.assign_trial_flight() from public, anon, authenticated;
revoke execute on function public.get_flight_recipients(uuid) from public, anon, authenticated;
revoke execute on function public.refresh_flight_trackers(uuid) from public, anon, authenticated;
grant execute on function public.get_flight_recipients(uuid) to service_role;
grant execute on function public.refresh_flight_trackers(uuid) to service_role;
revoke all on public.flight_view from anon;
grant select on public.flight_view to authenticated;
