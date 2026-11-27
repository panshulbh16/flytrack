-- P3 connection assistant (docs/P3_CONTRACTS.md B4). Pro.
--   * min_connection_times: minimum connection time (MCT) per airport, optionally per terminal pair, in minutes
--     for each kind of connection (arriving leg → departing leg, domestic / international by airport country).
--     No row: 45 / 75 / 90 / 60 (dom_dom / dom_intl / intl_dom / intl_intl). Seeded below, every row
--     verified = false until checked against the airport's published figures.
--   * connections_for_user(user): the user's trips, derived on the fly (no table): for each tracked leg, the
--     next tracked leg that departs from its arrival airport within 24 h of its scheduled arrival.
--   * my_connections(): the caller's connections with layover, MCT, buffer and risk.
--   * connection_alert_state + connection_alert_candidates(): what runConnectionAlerts (poll cycle, DB only)
--     needs to raise CONNECTION_AT_RISK when a connection goes from ok/tight to at_risk.
-- Risk: buffer = layover − MCT; ok >= 30, tight 0..29, at_risk < 0; missed = the departing leg left before the
-- arriving leg reached the gate. Layover = best departure time (actual, estimate, schedule) − best arrival time.

create table public.min_connection_times (
  id            bigint generated always as identity primary key,
  airport       char(3) not null,
  from_terminal text,
  to_terminal   text,
  dom_dom       integer not null check (dom_dom between 0 and 600),
  dom_intl      integer not null check (dom_intl between 0 and 600),
  intl_dom      integer not null check (intl_dom between 0 and 600),
  intl_intl     integer not null check (intl_intl between 0 and 600),
  transfer_note text check (char_length(transfer_note) <= 200),
  verified      boolean not null default false,
  check ((from_terminal is null) = (to_terminal is null))
);
create unique index min_connection_times_key
  on public.min_connection_times (airport, coalesce(from_terminal, ''), coalesce(to_terminal, ''));

alter table public.min_connection_times enable row level security;
revoke all on public.min_connection_times from public, anon, authenticated;
grant select, insert, update, delete on public.min_connection_times to service_role;

-- Seed (unverified). Terminal pairs that need a shuttle add 60 min to the airport's base row.
insert into public.min_connection_times (airport, from_terminal, to_terminal, dom_dom, dom_intl, intl_dom, intl_intl, transfer_note) values
  ('DEL', null, null, 60, 90, 120, 90, null),
  ('DEL', '1', '3', 120, 150, 180, 150, 'Shuttle between T1 and T3, about 20–30 min'),
  ('DEL', '3', '1', 120, 150, 180, 150, 'Shuttle between T3 and T1, about 20–30 min'),
  ('DEL', '2', '3', 75, 90, 120, 90, 'T2 and T3 are connected by a walkway'),
  ('DEL', '3', '2', 75, 90, 120, 90, 'T3 and T2 are connected by a walkway'),
  ('DEL', '1', '2', 120, 150, 180, 150, 'Shuttle between T1 and T2, about 20–30 min'),
  ('DEL', '2', '1', 120, 150, 180, 150, 'Shuttle between T2 and T1, about 20–30 min'),
  ('BOM', null, null, 60, 90, 120, 90, null),
  ('BOM', '1', '2', 120, 150, 180, 150, 'Shuttle between T1 and T2, about 25–40 min'),
  ('BOM', '2', '1', 120, 150, 180, 150, 'Shuttle between T2 and T1, about 25–40 min'),
  ('BLR', null, null, 45, 75, 90, 60, null),
  ('BLR', '1', '2', 60, 90, 105, 75, 'Walk between T1 and T2, about 10–15 min'),
  ('BLR', '2', '1', 60, 90, 105, 75, 'Walk between T2 and T1, about 10–15 min'),
  ('HYD', null, null, 45, 75, 90, 75, null),
  ('MAA', null, null, 45, 90, 90, 75, null),
  ('CCU', null, null, 45, 75, 90, 75, null),
  ('COK', null, null, 45, 75, 90, 60, null),
  ('AMD', null, null, 45, 75, 90, 60, null),
  ('AMD', '1', '2', 75, 105, 120, 90, 'Shuttle between T1 and T2, about 10–15 min'),
  ('AMD', '2', '1', 75, 105, 120, 90, 'Shuttle between T2 and T1, about 10–15 min');

-- ---------- alert state ---------------------------------------------------------------------------------
-- Last risk the poller saw per connection, and the alert generation (dedupe key {in}:{out}:CONN:{gen}).
create table public.connection_alert_state (
  in_flight_id  uuid not null references public.flights (id) on delete cascade,
  out_flight_id uuid not null references public.flights (id) on delete cascade,
  last_risk     text not null check (last_risk in ('ok', 'tight', 'at_risk', 'missed')),
  gen           integer not null default 0 check (gen >= 0),
  updated_at    timestamptz not null default now(),
  primary key (in_flight_id, out_flight_id)
);
create index connection_alert_state_out_idx on public.connection_alert_state (out_flight_id);
alter table public.connection_alert_state enable row level security;
revoke all on public.connection_alert_state from public, anon, authenticated;
grant select, insert, update, delete on public.connection_alert_state to service_role;

-- ---------- event visibility --------------------------------------------------------------------------------
-- A CONNECTION_AT_RISK row sits on the arriving flight but names the user's connecting flight (out_flight,
-- out_dep, out_flight_id). Other trackers of the arriving flight are strangers: they see the row only when
-- they track the connecting flight too (the push goes to the same users). Otherwise as in migration 8.
drop policy events_select_tracked on public.flight_events;
create policy events_select_tracked on public.flight_events for select to authenticated using (
  exists (select 1 from public.user_flights uf
           where uf.flight_id = flight_events.flight_id and uf.user_id = auth.uid())
  and (not pro_only or public.current_user_is_pro_for_flight(flight_events.flight_id))
  and (type <> 'CONNECTION_AT_RISK'
       or exists (select 1 from public.user_flights uo
                   where uo.user_id = auth.uid() and uo.flight_id::text = flight_events.payload ->> 'out_flight_id')));

-- ---------- MCT lookup ------------------------------------------------------------------------------------
-- MCT in minutes and the transfer note for one connection. The terminal pair row wins over the airport row.
create or replace function public.connection_mct(
  p_airport text, p_from_terminal text, p_to_terminal text, p_in_dom boolean, p_out_dom boolean,
  out mct_min integer, out transfer_note text)
language sql stable security definer set search_path = '' as $$
  with r as (
    select m.*, (m.from_terminal is not null) as specific
      from public.min_connection_times m
     where m.airport = upper(p_airport)
       and ((m.from_terminal is null)
            or (upper(m.from_terminal) = upper(btrim(p_from_terminal)) and upper(m.to_terminal) = upper(btrim(p_to_terminal))))
     order by specific desc
     limit 1
  )
  select coalesce(
           (select case when p_in_dom and p_out_dom then r.dom_dom when p_in_dom then r.dom_intl
                        when p_out_dom then r.intl_dom else r.intl_intl end from r),
           case when p_in_dom and p_out_dom then 45 when p_in_dom then 75 when p_out_dom then 90 else 60 end),
         (select r.transfer_note from r);
$$;

-- ---------- trips + risk ------------------------------------------------------------------------------------
create or replace function public.connections_for_user(p_user uuid)
returns table (
  in_flight_id uuid, out_flight_id uuid, airport text, in_flight text, out_flight text,
  in_arr timestamptz, out_dep timestamptz, layover_min integer, mct_min integer, buffer_min integer,
  risk text, terminal_change text, transfer_note text)
language sql stable security definer set search_path = '' as $$
  with mine as (
    select f.*
      from public.user_flights uf
      join public.flights f on f.id = uf.flight_id
     where uf.user_id = p_user and uf.archived_at is null and f.status <> 'cancelled'
  ), pairs as (
    select distinct on (a.id) a.id as in_id, b.id as out_id
      from mine a
      join mine b on b.dep_iata = a.arr_iata and b.id <> a.id
     where coalesce(a.sched_arr, a.est_arr) is not null and coalesce(b.sched_dep, b.est_dep) is not null
       and coalesce(b.sched_dep, b.est_dep) > coalesce(a.sched_arr, a.est_arr)
       and coalesce(b.sched_dep, b.est_dep) <= coalesce(a.sched_arr, a.est_arr) + interval '24 hours'
     order by a.id, coalesce(b.sched_dep, b.est_dep)
  ), x as (
    select a.id as in_id, b.id as out_id, a.arr_iata::text as airport,
           a.carrier_iata || ' ' || a.flight_number as in_flight,
           b.carrier_iata || ' ' || b.flight_number as out_flight,
           coalesce(a.arr_onblock_at, a.act_arr, a.est_arr, a.sched_arr) as in_arr,
           coalesce(b.dep_offblock_at, b.act_dep, b.est_dep, b.sched_dep) as out_dep,
           (b.act_dep is not null or b.dep_offblock_at is not null
             or b.status in ('departed', 'en_route', 'approaching', 'landed', 'arrived', 'diverted')) as out_left,
           coalesce(a.arr_onblock_at, a.act_arr) as in_arrived_at,
           nullif(btrim(a.arr_terminal), '') as in_term, nullif(btrim(b.dep_terminal), '') as out_term,
           coalesce(ad.country = ap.country, true) as in_dom,
           coalesce(ap.country = bd.country, true) as out_dom
      from pairs p
      join public.flights a on a.id = p.in_id
      join public.flights b on b.id = p.out_id
      left join public.airports ad on ad.iata = a.dep_iata
      left join public.airports ap on ap.iata = a.arr_iata
      left join public.airports bd on bd.iata = b.arr_iata
  )
  select x.in_id, x.out_id, x.airport, x.in_flight, x.out_flight, x.in_arr, x.out_dep,
         l.layover, m.mct_min, l.layover - m.mct_min,
         case when x.out_left and (x.in_arrived_at is null or x.in_arrived_at > x.out_dep) then 'missed'
              when l.layover - m.mct_min >= 30 then 'ok'
              when l.layover - m.mct_min >= 0 then 'tight'
              else 'at_risk' end,
         case when x.in_term is not null and x.out_term is not null and upper(x.in_term) <> upper(x.out_term)
              then 'T' || x.in_term || '→T' || x.out_term end,
         m.transfer_note
    from x
   cross join lateral (select round(extract(epoch from x.out_dep - x.in_arr) / 60.0)::int as layover) l
   cross join lateral public.connection_mct(x.airport, x.in_term, x.out_term, x.in_dom, x.out_dom) m;
$$;

-- ---------- my_connections ----------------------------------------------------------------------------------
-- Pro: connections whose arriving or departing leg the caller is Pro for (plan, family or trial flight).
-- Connections whose departing leg left more than 12 h ago are history and left out. Ordered by departure.
create or replace function public.my_connections() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if not public.is_pro(v_user)
     and not exists (select 1 from public.profiles p where p.id = v_user and p.trial_flight_id is not null) then
    raise exception 'pro_required' using errcode = '42501';
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
             'in_flight_id', c.in_flight_id, 'out_flight_id', c.out_flight_id, 'airport', c.airport,
             'in_flight', c.in_flight, 'out_flight', c.out_flight,
             'in_arr', public.forecast_iso(c.in_arr), 'out_dep', public.forecast_iso(c.out_dep),
             'layover_min', c.layover_min, 'mct_min', c.mct_min, 'buffer_min', c.buffer_min, 'risk', c.risk,
             'terminal_change', c.terminal_change, 'transfer_note', c.transfer_note)
           order by c.out_dep)
      from public.connections_for_user(v_user) c
     where c.out_dep > now() - interval '12 hours'
       and (public.is_pro_for_flight(v_user, c.in_flight_id) or public.is_pro_for_flight(v_user, c.out_flight_id))),
    '[]'::jsonb);
end $$;

-- ---------- alert candidates (service role, poll cycle) -------------------------------------------------------
-- One row per (user, connection) for Pro users whose arriving leg has not landed and is due within 36 h
-- (or up to 6 h late), when the risk differs from the stored state (or there is no state yet).
create or replace function public.connection_alert_candidates(p_limit int default 100)
returns table (
  user_id uuid, in_flight_id uuid, out_flight_id uuid, airport text, out_flight text, out_dep timestamptz,
  layover_min integer, mct_min integer, buffer_min integer, risk text, last_risk text, gen integer)
language sql stable security definer set search_path = '' as $$
  with users as (
    select distinct uf.user_id
      from public.user_flights uf
      join public.flights f on f.id = uf.flight_id
     where uf.archived_at is null and f.pro_tracker_count > 0 and f.act_arr is null
       and f.status not in ('cancelled', 'landed', 'arrived', 'diverted')
       and coalesce(f.est_arr, f.sched_arr) between now() - interval '6 hours' and now() + interval '36 hours'
  )
  select u.user_id, c.in_flight_id, c.out_flight_id, c.airport, c.out_flight, c.out_dep,
         c.layover_min, c.mct_min, c.buffer_min, c.risk, s.last_risk, coalesce(s.gen, 0)
    from users u
   cross join lateral public.connections_for_user(u.user_id) c
    join public.flights a on a.id = c.in_flight_id
    left join public.connection_alert_state s
      on s.in_flight_id = c.in_flight_id and s.out_flight_id = c.out_flight_id
   where a.act_arr is null and a.status not in ('landed', 'arrived')
     and public.is_pro_for_flight(u.user_id, c.in_flight_id)
     and s.last_risk is distinct from c.risk
   order by c.out_dep
   limit greatest(coalesce(p_limit, 100), 1);
$$;

-- Stores the risk seen and the alert generation (service role). Never moves gen backwards.
create or replace function public.save_connection_state(p_in uuid, p_out uuid, p_risk text, p_gen integer) returns void
language sql security definer set search_path = '' as $$
  insert into public.connection_alert_state as s (in_flight_id, out_flight_id, last_risk, gen, updated_at)
  values (p_in, p_out, p_risk, greatest(coalesce(p_gen, 0), 0), now())
  on conflict (in_flight_id, out_flight_id) do update
     set last_risk = excluded.last_risk, gen = greatest(s.gen, excluded.gen), updated_at = now();
$$;

-- ---------- execute rights -------------------------------------------------------------------------------------
revoke execute on function public.connection_mct(text, text, text, boolean, boolean) from public, anon, authenticated;
revoke execute on function public.connections_for_user(uuid) from public, anon, authenticated;
revoke execute on function public.connection_alert_candidates(int) from public, anon, authenticated;
revoke execute on function public.save_connection_state(uuid, uuid, text, integer) from public, anon, authenticated;
grant execute on function public.connection_mct(text, text, text, boolean, boolean) to service_role;
grant execute on function public.connections_for_user(uuid) to service_role;
grant execute on function public.connection_alert_candidates(int) to service_role;
grant execute on function public.save_connection_state(uuid, uuid, text, integer) to service_role;
revoke execute on function public.my_connections() from public, anon;
grant execute on function public.my_connections() to authenticated;
