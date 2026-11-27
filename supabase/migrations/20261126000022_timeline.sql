-- P3 full timeline + taxi times (docs/P3_CONTRACTS.md A2 and B3).
--   * flights: block times filled by the providers (dep_offblock_at, dep_runway_at, arr_runway_at, arr_onblock_at).
--     act_dep / act_arr keep their meaning.
--   * flight_leg_history: taxi_out_min (runway − off-block) and taxi_in_min (on-block − runway), written by
--     legHistoryRow() in supabase/functions/_shared/history.ts.
--   * flight_timeline(p_flight_id): the ordered milestone list, free keys + times, Pro detail.
--   * taxi_stats(p_iata): Pro, typical taxi-out / taxi-in at an airport over the last 30 days.
-- The new event types GATE_CLOSED / TAKEOFF / AT_GATE are added to the flight_events check in migration 30.

-- ---------- columns ---------------------------------------------------------------------------------
alter table public.flights
  add column dep_offblock_at timestamptz,
  add column dep_runway_at   timestamptz,
  add column arr_runway_at   timestamptz,
  add column arr_onblock_at  timestamptz;

alter table public.flight_leg_history
  add column taxi_out_min integer check (taxi_out_min between 0 and 180),
  add column taxi_in_min  integer check (taxi_in_min between 0 and 180);

create index if not exists flight_leg_history_arr_day_idx on public.flight_leg_history (arr, date);

-- ---------- flight_timeline -------------------------------------------------------------------------
-- Order: scheduled, checkin_open, gate_assigned, boarding, gate_closed, pushback, takeoff, [diverted], landing,
-- at_gate, belt. A cancelled flight lists the milestones that really happened, then "cancelled".
-- A milestone is done when the row, an event or the status history proves it. When a later milestone is
-- done, an earlier one without proof is either implied (boarding, gate_closed, pushback, takeoff, landing,
-- at_gate: done with at = null) or left out (checkin_open, gate_assigned, belt: they may never happen).
-- state: the last done milestone is "current", earlier ones "done", later ones "next".
-- at: when it happened (done), or the expected time where one is known (next: checkin_open = window open,
-- pushback = best departure estimate, landing = best arrival estimate), else null. ISO-8601 UTC, minutes.
-- detail: Pro only (is_pro_for_flight, so the trial flight counts); null for everyone else.
create or replace function public.flight_timeline(p_flight_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_user     uuid := auth.uid();
  f          public.flights%rowtype;
  v_pro      boolean;
  v_rank     int;
  v_departed boolean;
  v_landed   boolean;
  -- event times
  e_checkin  timestamptz;
  e_gate     timestamptz;
  e_boarding timestamptz;
  e_closed   timestamptz;
  e_belt     timestamptz;
  e_cancel   timestamptz;
  e_divert   timestamptz;
  e_orig_arr text;
  -- status history times
  h_checkin  timestamptz;
  h_gate     timestamptz;
  h_boarding timestamptz;
  h_closed   timestamptz;
  h_belt     timestamptz;
  h_cancel   timestamptz;
  h_divert   timestamptz;
  -- milestone arrays (same index = same milestone)
  k          text[] := '{}';
  done       boolean[] := '{}';
  implied    boolean[] := '{}';  -- true: implied by a later milestone; false: left out without proof
  t          timestamptz[] := '{}';
  d          jsonb[] := '{}';
  v_last     int := 0;
  v_out      jsonb := '[]'::jsonb;
  v_state    text;
  i          int;
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if not public.can_read_flight(p_flight_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  select * into f from public.flights where id = p_flight_id;
  if not found then raise exception 'not_found' using errcode = 'P0002'; end if;
  v_pro := public.is_pro_for_flight(v_user, p_flight_id);

  v_rank := case f.status
    when 'check_in' then 1 when 'boarding' then 2 when 'gate_closed' then 3 when 'departed' then 4
    when 'en_route' then 5 when 'approaching' then 6 when 'diverted' then 5 when 'landed' then 7
    when 'arrived' then 8 else 0 end;
  v_departed := f.act_dep is not null or f.dep_offblock_at is not null or f.dep_runway_at is not null or v_rank >= 4;
  v_landed := f.act_arr is not null or f.arr_runway_at is not null or f.arr_onblock_at is not null or v_rank >= 7;

  select min(e.created_at) filter (where e.type = 'CHECKIN_OPEN'),
         min(e.created_at) filter (where e.type in ('GATE_ASSIGNED', 'GATE_CHANGE')),
         min(e.created_at) filter (where e.type = 'BOARDING'),
         min(e.created_at) filter (where e.type = 'GATE_CLOSED'),
         min(e.created_at) filter (where e.type in ('BELT_ASSIGNED', 'BELT_CHANGE')),
         max(e.created_at) filter (where e.type = 'CANCELLED'),
         min(e.created_at) filter (where e.type = 'DIVERTED'),
         (array_agg(e.payload ->> 'origArr' order by e.created_at) filter (where e.type = 'DIVERTED'))[1]
    into e_checkin, e_gate, e_boarding, e_closed, e_belt, e_cancel, e_divert, e_orig_arr
    from public.flight_events e
   where e.flight_id = p_flight_id;

  select min(h.observed_at) filter (where h.snapshot ->> 'status' = 'check_in'),
         min(h.observed_at) filter (where coalesce(h.snapshot ->> 'depGate', '') <> ''),
         min(h.observed_at) filter (where h.snapshot ->> 'status' in ('boarding', 'gate_closed')),
         min(h.observed_at) filter (where h.snapshot ->> 'status' = 'gate_closed'),
         min(h.observed_at) filter (where coalesce(h.snapshot ->> 'baggageBelt', '') <> ''),
         max(h.observed_at) filter (where h.snapshot ->> 'status' = 'cancelled'),
         min(h.observed_at) filter (where h.snapshot ->> 'status' = 'diverted')
    into h_checkin, h_gate, h_boarding, h_closed, h_belt, h_cancel, h_divert
    from public.flight_status_history h
   where h.flight_id = p_flight_id and h.source not like '%\_stale';

  -- scheduled
  k := array_append(k, 'scheduled'::text); done := done || true; implied := implied || true; t := t || f.sched_dep;
  d := d || jsonb_build_object('sched_dep', public.forecast_iso(f.sched_dep), 'sched_arr', public.forecast_iso(f.sched_arr));
  -- checkin_open
  k := array_append(k, 'checkin_open'::text); implied := implied || false;
  done := done || (e_checkin is not null or h_checkin is not null or v_rank between 1 and 3
                   or (f.checkin_opens_at is not null and f.checkin_opens_at <= now()));
  t := t || coalesce(least(f.checkin_opens_at, e_checkin), e_checkin, h_checkin, f.checkin_opens_at);
  d := d || jsonb_build_object('closes_at', public.forecast_iso(f.checkin_closes_at));
  -- gate_assigned
  k := array_append(k, 'gate_assigned'::text); implied := implied || false;
  done := done || (f.dep_gate is not null or e_gate is not null or h_gate is not null);
  t := t || least(e_gate, h_gate);
  d := d || jsonb_build_object('gate', f.dep_gate, 'terminal', f.dep_terminal);
  -- boarding
  k := array_append(k, 'boarding'::text); implied := implied || true;
  done := done || (e_boarding is not null or h_boarding is not null or f.status in ('boarding', 'gate_closed'));
  t := t || least(e_boarding, h_boarding);
  d := d || jsonb_build_object('gate', f.dep_gate, 'terminal', f.dep_terminal);
  -- gate_closed
  k := array_append(k, 'gate_closed'::text); implied := implied || true;
  done := done || (e_closed is not null or h_closed is not null or f.status = 'gate_closed');
  t := t || least(e_closed, h_closed);
  d := d || jsonb_build_object('gate', f.dep_gate);
  -- pushback
  k := array_append(k, 'pushback'::text); implied := implied || true;
  done := done || (f.dep_offblock_at is not null or (f.act_dep is not null and f.dep_runway_at is null));
  t := t || case when f.dep_offblock_at is not null then f.dep_offblock_at
                 when f.act_dep is not null and f.dep_runway_at is null then f.act_dep
                 when not v_departed then coalesce(f.est_dep, f.sched_dep) end;
  d := d || jsonb_build_object('delay_min', round(extract(epoch from
              coalesce(f.dep_offblock_at, f.act_dep, f.est_dep, f.sched_dep) - f.sched_dep) / 60.0)::int);
  -- takeoff
  k := array_append(k, 'takeoff'::text); implied := implied || true;
  done := done || (f.dep_runway_at is not null);
  t := t || f.dep_runway_at;
  d := d || jsonb_build_object('taxi_out_min', round(extract(epoch from f.dep_runway_at - f.dep_offblock_at) / 60.0)::int);
  -- diverted (only when it happened)
  if f.status = 'diverted' or e_divert is not null then
    k := array_append(k, 'diverted'::text); implied := implied || true; done := done || true;
    t := t || least(e_divert, h_divert);
    d := d || jsonb_build_object('from_arr', e_orig_arr, 'to_arr', f.arr_iata);
  end if;
  -- landing
  k := array_append(k, 'landing'::text); implied := implied || true;
  done := done || (f.arr_runway_at is not null or f.act_arr is not null);
  t := t || case when v_landed then coalesce(f.arr_runway_at, f.act_arr) else coalesce(f.est_arr, f.sched_arr) end;
  d := d || jsonb_build_object('delay_min', round(extract(epoch from
              coalesce(f.arr_runway_at, f.act_arr, f.est_arr, f.sched_arr) - f.sched_arr) / 60.0)::int);
  -- at_gate
  k := array_append(k, 'at_gate'::text); implied := implied || true;
  done := done || (f.arr_onblock_at is not null or f.status = 'arrived');
  t := t || f.arr_onblock_at;
  d := d || jsonb_build_object('gate', f.arr_gate, 'terminal', f.arr_terminal,
              'taxi_in_min', round(extract(epoch from f.arr_onblock_at - f.arr_runway_at) / 60.0)::int);
  -- belt
  k := array_append(k, 'belt'::text); implied := implied || false;
  done := done || (f.baggage_belt is not null);
  t := t || case when f.baggage_belt is not null then coalesce(least(e_belt, h_belt), f.arr_onblock_at) end;
  d := d || jsonb_build_object('belt', f.baggage_belt);

  -- a later proof implies the structural milestones before it
  for i in 1 .. array_length(k, 1) loop
    if done[i] then v_last := i; end if;
  end loop;
  -- the status alone can prove a stage without a time: left the gate, airborne, landed
  if v_departed and not done[array_position(k, 'pushback')] then
    done[array_position(k, 'pushback')] := true;
    t[array_position(k, 'pushback')] := null;
    v_last := greatest(v_last, array_position(k, 'pushback'));
  end if;
  if v_rank >= 5 and not done[array_position(k, 'takeoff')] then
    done[array_position(k, 'takeoff')] := true;
    v_last := greatest(v_last, array_position(k, 'takeoff'));
  end if;
  if v_landed and not done[array_position(k, 'landing')] then
    done[array_position(k, 'landing')] := true;
    v_last := greatest(v_last, array_position(k, 'landing'));
  end if;

  if f.status = 'cancelled' then
    -- only what really happened, then the cancellation
    for i in 1 .. array_length(k, 1) loop
      if done[i] then
        v_out := v_out || jsonb_build_object('key', k[i], 'at', public.forecast_iso(t[i]), 'state', 'done',
                                             'detail', case when v_pro then d[i] end);
      end if;
    end loop;
    return v_out || jsonb_build_object('key', 'cancelled', 'at', public.forecast_iso(coalesce(e_cancel, h_cancel)),
                                       'state', 'current', 'detail',
                                       case when v_pro then jsonb_build_object('sched_dep', public.forecast_iso(f.sched_dep)) end);
  end if;

  for i in 1 .. array_length(k, 1) loop
    if i < v_last and not done[i] and not implied[i] then
      continue;  -- an optional milestone that never happened
    end if;
    v_state := case when i < v_last then 'done' when i = v_last then 'current' else 'next' end;
    v_out := v_out || jsonb_build_object(
      'key', k[i],
      -- a done milestone has its real time; an implied one has none; a next one the expected time
      'at', public.forecast_iso(case when v_state = 'next' or done[i] or k[i] = 'scheduled' then t[i] end),
      'state', v_state,
      'detail', case when v_pro then d[i] end);
  end loop;
  return v_out;
end $$;

-- ---------- taxi_stats ------------------------------------------------------------------------------
-- Pro. Finished legs of the last 30 days: taxi-out of departures from p_iata, taxi-in of arrivals at it.
-- p50 / p80 are whole minutes (null without samples).
create or replace function public.taxi_stats(p_iata text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_iata text := upper(btrim(coalesce(p_iata, '')));
  v_out  jsonb;
  v_in   jsonb;
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if v_iata !~ '^[A-Z0-9]{3}$' then raise exception 'invalid_iata' using errcode = '22023'; end if;
  if not public.current_user_is_pro() then raise exception 'pro_required' using errcode = '42501'; end if;

  select jsonb_build_object(
           'p50', round(percentile_cont(0.5) within group (order by h.taxi_out_min))::int,
           'p80', round(percentile_cont(0.8) within group (order by h.taxi_out_min))::int,
           'samples', count(*))
    into v_out
    from public.flight_leg_history h
   where h.dep = v_iata and h.date >= current_date - 30 and h.taxi_out_min is not null;

  select jsonb_build_object(
           'p50', round(percentile_cont(0.5) within group (order by h.taxi_in_min))::int,
           'p80', round(percentile_cont(0.8) within group (order by h.taxi_in_min))::int,
           'samples', count(*))
    into v_in
    from public.flight_leg_history h
   where h.arr = v_iata and h.date >= current_date - 30 and h.taxi_in_min is not null;

  return jsonb_build_object('iata', v_iata, 'window_days', 30, 'taxi_out', v_out, 'taxi_in', v_in);
end $$;

-- ---------- execute rights --------------------------------------------------------------------------
revoke execute on function public.flight_timeline(uuid) from public, anon;
grant execute on function public.flight_timeline(uuid) to authenticated;
revoke execute on function public.taxi_stats(text) from public, anon;
grant execute on function public.taxi_stats(text) to authenticated;
