-- P3 aircraft age + swap alert (docs/P3_CONTRACTS.md A5).
--   * aircraft: one row per registration, from the provider's aircraft lookup (AeroDataBox
--     GET /aircrafts/reg/{reg}; the mock returns a fixed aircraft). Written by the poller (service role)
--     the first time a Pro-tracked flight has a tail, refreshed after 30 days. A tail the provider does not
--     know is stored with nulls, so it is not asked for again within the 30 days either.
--   * flight_view: + block times (migration 22), aircraft ICAO type for everyone, age for Pro.
--   * AIRCRAFT_SWAP (Pro, critical) is raised by evaluateAlerts() (supabase/functions/_shared/diff.ts) and added
--     to the flight_events check in migration 30. The swap history is the AIRCRAFT_SWAP rows of flight_events,
--     which the events_select_tracked policy already shows only to callers who are Pro for the flight.

create table public.aircraft (
  reg               text primary key check (reg ~ '^[A-Z0-9-]{2,10}$'),
  model             text check (char_length(model) <= 100),
  icao_type         text check (icao_type ~ '^[A-Z0-9]{2,4}$'),
  first_flight_date date,
  delivery_date     date,
  age_years         numeric(4, 1) check (age_years between 0 and 99),
  airline_iata      text check (airline_iata ~ '^[A-Z0-9]{2}$'),
  seat_config       text check (char_length(seat_config) <= 100),
  fetched_at        timestamptz not null default now()
);

alter table public.aircraft enable row level security;
revoke all on public.aircraft from public, anon, authenticated;
grant select, insert, update, delete on public.aircraft to service_role;

-- Age in years (one decimal) as of now: from the first flight, else the delivery date, else the stored value.
create or replace function public.aircraft_age(a public.aircraft) returns numeric
language sql stable set search_path = '' as $$
  select case
    when coalesce(a.first_flight_date, a.delivery_date) is not null
      then round((extract(epoch from now() - coalesce(a.first_flight_date, a.delivery_date)::timestamptz)
                  / (365.25 * 86400))::numeric, 1)
    else a.age_years end;
$$;
-- Called from flight_view, and function rights are checked against the caller: harmless, so authenticated may run it.
revoke execute on function public.aircraft_age(public.aircraft) from public, anon;
grant execute on function public.aircraft_age(public.aircraft) to authenticated, service_role;

-- ---------- flight_view -----------------------------------------------------------------------------
-- Same columns as 20261126000009_security.sql, new ones appended at the end.
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
  (f.prediction is not null and (f.prediction ->> 'lateMin')::int >= 10) as has_pro_insight,  -- teaser for paywall
  -- P3
  f.dep_offblock_at, f.dep_runway_at, f.arr_runway_at, f.arr_onblock_at,
  ac.icao_type as aircraft_icao_type,
  case when public.current_user_is_pro_for_flight(f.id) then public.aircraft_age(ac) end as aircraft_age_years
from public.flights f
left join public.airports da on da.iata = f.dep_iata
left join public.airports aa on aa.iata = f.arr_iata
left join public.aircraft ac on ac.reg = upper(f.aircraft_reg)
where auth.uid() is not null and public.can_read_flight(f.id);

revoke all on public.flight_view from anon;
grant select on public.flight_view to authenticated;
