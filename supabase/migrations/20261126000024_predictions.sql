-- P3 delay prediction + gate prediction (docs/P3_CONTRACTS.md B1 and B2). Both Pro (is_pro_for_flight, so the
-- trial flight counts); other callers get 'pro_required' (42501), like airport_trends.
--
-- delay_prediction(p_flight_id, p_lang): "rules-v1", empirical Bayes on flight_leg_history (last 90 days,
-- flown legs = not cancelled / diverted, with a known departure delay; early departures count as 0 min).
--   Levels, each shrunk toward the one above with k = 10 pseudo-legs:
--     prior (no data):  mean 12 min, tail g(t) = 0.6 * exp(-max(t, 0) / 25)   [P(delay >= t)]
--     route             dep → arr, any carrier
--     carrier + route
--     flight number     carrier + number + route
--   est_level = (n * mean_level + k * est_above) / (n + k); the same for P(delay >= t) with counts.
--   Adjustments (minutes, added to the history estimate), only for the departure airport:
--     weather    departure within 6 h, METAR <= 3 h old: thick fog (fog, vis < 550 m) +30, fog or vis < 1000 m +15,
--                thunderstorm +15, vis < 1500 m +10
--     airport    departure within 6 h, >= 8 departures in the last 3 h averaging A >= 15 min late:
--                +0.5 * (A - history estimate), 0..45
--     time_of_day departure-hour bucket at the airport (local 05–09, 09–17, 17–22, 22–05): shrunk difference
--                between the bucket's and the airport's mean delay, weighted by (1 - w_flight) because the
--                flight's own history already carries its departure time (w_flight = n_flight / (n_flight + k))
--     inbound    flights.prediction.lateMin (Where's my plane): a floor, the delay cannot be smaller
--   expected = max(history + weather + airport + time_of_day, inbound); the factors' effect_min add up to it
--   (inbound's effect is what it adds on top). P(delay >= 15 / 60) = 0.95 when inbound already reaches the
--   threshold, else the shrunk share of legs with delay >= threshold − adjustments. Probabilities are kept
--   in 0.01..0.95 and p60 <= p15. Confidence: route samples >= 20 high, >= 8 medium, else low.
--   Available from 72 h before the best known departure until departure (null otherwise, and for cancelled
--   or departed flights). Cached in delay_predictions for 15 minutes; labels are rendered per call.
--
-- gate_prediction(p_flight_id): the most used departure gate of the same carrier + number at the same
-- departure airport over the last 60 days (legs with a gate). Null below 4 samples.

-- ---------- cache -------------------------------------------------------------------------------------
create table public.delay_predictions (
  flight_id   uuid primary key references public.flights (id) on delete cascade,
  json        jsonb not null,
  computed_at timestamptz not null default now()
);
alter table public.delay_predictions enable row level security;
revoke all on public.delay_predictions from public, anon, authenticated;
grant select, insert, update, delete on public.delay_predictions to service_role;

create index if not exists flight_leg_history_carrier_dep_idx on public.flight_leg_history (carrier, number, dep, date);

-- ---------- labels --------------------------------------------------------------------------------------
-- One factor label in en / hi / hinglish (unknown → en). Codes and numbers stay Latin in every language.
create or replace function public.prediction_label(p_key text, p_lang text, p jsonb) returns text
language plpgsql immutable set search_path = '' as $$
declare
  l   text := case when p_lang in ('en', 'hi', 'hinglish') then p_lang else 'en' end;
  b   text := p ->> 'bucket';
  ap  text := coalesce(p ->> 'iata', '');
  n   text := coalesce(p ->> 'min', p ->> 'pct', '');
  pos boolean := coalesce((p ->> 'effect')::numeric, 0) > 0;
  bn  text;
begin
  case p_key
  when 'history' then
    if p ->> 'basis' = 'flight' then
      return case l when 'hi' then format('यह फ़्लाइट %s%% बार लेट होती है', n)
                    when 'hinglish' then format('Yeh flight %s%% baar late hoti hai', n)
                    else format('This flight is late %s%% of the time', n) end;
    end if;
    return case l when 'hi' then format('%s की फ़्लाइट्स %s%% बार लेट होती हैं', p ->> 'route', n)
                  when 'hinglish' then format('%s flights %s%% baar late hoti hain', p ->> 'route', n)
                  else format('%s flights are late %s%% of the time', p ->> 'route', n) end;
  when 'inbound' then
    return case l when 'hi' then format('आपका विमान %s मिनट देर से चल रहा है', n)
                  when 'hinglish' then format('Aapka plane %s min late chal raha hai', n)
                  else format('Your plane is running %s min late', n) end;
  when 'weather' then
    return case p ->> 'kind'
      when 'thick_fog' then case l when 'hi' then format('%s पर घना कोहरा', ap)
                                   when 'hinglish' then format('%s par ghana fog', ap)
                                   else format('Thick fog at %s', ap) end
      when 'fog' then case l when 'hi' then format('%s पर कोहरा', ap)
                             when 'hinglish' then format('%s par fog', ap)
                             else format('Fog at %s', ap) end
      when 'thunderstorm' then case l when 'hi' then format('%s पर आंधी-तूफ़ान', ap)
                                      when 'hinglish' then format('%s par thunderstorm', ap)
                                      else format('Thunderstorms at %s', ap) end
      else case l when 'hi' then format('%s पर कम विज़िबिलिटी', ap)
                  when 'hinglish' then format('%s par low visibility', ap)
                  else format('Low visibility at %s', ap) end
      end;
  when 'airport' then
    return case l when 'hi' then format('%s से उड़ानें औसतन %s मिनट लेट हैं', ap, n)
                  when 'hinglish' then format('%s se flights average %s min late hain', ap, n)
                  else format('%s departures are averaging %s min late', ap, n) end;
  when 'time_of_day' then
    bn := case l
      when 'hi' then case b when 'early' then 'सुबह की' when 'day' then 'दिन की' when 'evening' then 'शाम की' else 'रात की' end
      when 'hinglish' then case b when 'early' then 'Subah ki' when 'day' then 'Din ki' when 'evening' then 'Shaam ki' else 'Raat ki' end
      else case b when 'early' then 'Early-morning' when 'day' then 'Daytime' when 'evening' then 'Evening' else 'Late-night' end
      end;
    return case l
      when 'hi' then format('%s उड़ानें %s', bn, case when pos then 'ज़्यादा लेट होती हैं' else 'ज़्यादा समय पर होती हैं' end)
      when 'hinglish' then format('%s flights zyada %s hoti hain', bn, case when pos then 'late' else 'on time' end)
      else format('%s departures %s', bn, case when pos then 'run later' else 'run more on time' end)
      end;
  else
    return p_key;
  end case;
end $$;

-- The cached prediction with labels in the caller's language (the cache keeps label parameters only).
create or replace function public.prediction_render(p_json jsonb, p_lang text) returns jsonb
language sql immutable set search_path = '' as $$
  select (p_json - 'factors') || jsonb_build_object('factors', coalesce((
    select jsonb_agg(jsonb_build_object(
             'key', x ->> 'key',
             'effect_min', (x ->> 'effect_min')::int,
             'label', public.prediction_label(x ->> 'key', p_lang,
                                              (x -> 'params') || jsonb_build_object('effect', x ->> 'effect_min')))
           order by o)
      from jsonb_array_elements(p_json -> 'factors') with ordinality as t(x, o)), '[]'::jsonb));
$$;

-- ---------- core ------------------------------------------------------------------------------------------
-- Computes the prediction for one flight row (no access checks, no cache). Internal.
create or replace function public.delay_prediction_core(f public.flights) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  k        constant numeric := 10;
  m0       constant numeric := 12;
  v_since  date := current_date - 90;
  v_dep    timestamptz := coalesce(f.est_dep, f.sched_dep);
  v_tz     text;
  v_near   boolean;
  v_bucket text;
  w        public.airport_weather%rowtype;
  v_has_w  boolean;
  -- level stats
  n_r int; n_c int; n_f int;
  mean_r numeric; mean_c numeric; mean_f numeric;
  -- adjustments
  a_weather numeric := 0;
  a_kind    text;
  a_airport numeric := 0;
  a_tod     numeric := 0;
  a_sum     numeric;
  v_recent_n   int;
  v_recent_avg numeric;
  n_b int; mean_b numeric; mean_all numeric;
  v_inbound numeric := 0;
  -- estimates
  m_r numeric; m_c numeric; m_f numeric;
  p15u numeric; p15 numeric; p60 numeric;
  v_expected numeric;
  v_factors jsonb := '[]'::jsonb;
  v_conf text;
begin
  select a.tz into v_tz from public.airports a where a.iata = f.dep_iata;
  v_tz := coalesce(v_tz, 'Asia/Kolkata');
  v_near := v_dep <= now() + interval '6 hours';

  -- 1) per-level means of the delay (early = 0)
  select count(*), avg(greatest(h.dep_delay_min, 0)),
         count(*) filter (where h.carrier = f.carrier_iata),
         avg(greatest(h.dep_delay_min, 0)) filter (where h.carrier = f.carrier_iata),
         count(*) filter (where h.carrier = f.carrier_iata and h.number = f.flight_number),
         avg(greatest(h.dep_delay_min, 0)) filter (where h.carrier = f.carrier_iata and h.number = f.flight_number)
    into n_r, mean_r, n_c, mean_c, n_f, mean_f
    from public.flight_leg_history h
   where h.dep = f.dep_iata and h.arr = f.arr_iata and h.date >= v_since and h.flight_id <> f.id
     and h.final_status not in ('cancelled', 'diverted') and h.dep_delay_min is not null;

  m_r := (n_r * coalesce(mean_r, 0) + k * m0) / (n_r + k);
  m_c := (n_c * coalesce(mean_c, 0) + k * m_r) / (n_c + k);
  m_f := (n_f * coalesce(mean_f, 0) + k * m_c) / (n_f + k);

  -- 2) adjustments
  if v_near then
    select * into w from public.airport_weather aw
     where aw.iata = f.dep_iata and aw.observed_at > now() - interval '3 hours';
    v_has_w := found;
    if v_has_w then
      if w.fog and coalesce(w.visibility_m < 550, false) then a_weather := 30; a_kind := 'thick_fog';
      elsif w.fog or coalesce(w.visibility_m < 1000, false) then a_weather := 15; a_kind := 'fog';
      elsif coalesce(w.wx ~ 'TS', false) then a_weather := 15; a_kind := 'thunderstorm';
      elsif coalesce(w.visibility_m < 1500, false) then a_weather := 10; a_kind := 'low_vis';
      end if;
    end if;

    -- recent departures, as get_airport_health counts them
    select count(*), avg(x.delay_min) into v_recent_n, v_recent_avg
      from (select greatest(0, extract(epoch from (
                     case when r.act_dep is not null then r.act_dep
                          when r.status in ('departed', 'en_route', 'approaching', 'landed', 'arrived', 'diverted')
                            then coalesce(r.est_dep, r.sched_dep)
                          else greatest(coalesce(r.est_dep, r.sched_dep), now()) end - r.sched_dep)) / 60.0) as delay_min
              from public.flights r
             where r.dep_iata = f.dep_iata and r.id <> f.id and r.sched_dep is not null and r.status <> 'cancelled'
               and (r.sched_dep between now() - interval '3 hours' and now()
                    or r.act_dep between now() - interval '3 hours' and now())
               and (r.act_dep is not null or r.last_polled_at > now() - interval '1 hour')) x;
    if v_recent_n >= 8 and v_recent_avg >= 15 then
      a_airport := least(45, greatest(0, 0.5 * (v_recent_avg - m_f)));
    end if;
  end if;

  -- time of day at the departure airport
  v_bucket := case
    when extract(hour from v_dep at time zone v_tz) >= 5 and extract(hour from v_dep at time zone v_tz) < 9 then 'early'
    when extract(hour from v_dep at time zone v_tz) >= 9 and extract(hour from v_dep at time zone v_tz) < 17 then 'day'
    when extract(hour from v_dep at time zone v_tz) >= 17 and extract(hour from v_dep at time zone v_tz) < 22 then 'evening'
    else 'night' end;
  select count(*) filter (where x.bucket = v_bucket), avg(x.d) filter (where x.bucket = v_bucket), avg(x.d)
    into n_b, mean_b, mean_all
    from (select greatest(h.dep_delay_min, 0) as d,
                 case when extract(hour from h.sched_dep at time zone v_tz) >= 5 and extract(hour from h.sched_dep at time zone v_tz) < 9 then 'early'
                      when extract(hour from h.sched_dep at time zone v_tz) >= 9 and extract(hour from h.sched_dep at time zone v_tz) < 17 then 'day'
                      when extract(hour from h.sched_dep at time zone v_tz) >= 17 and extract(hour from h.sched_dep at time zone v_tz) < 22 then 'evening'
                      else 'night' end as bucket
            from public.flight_leg_history h
           where h.dep = f.dep_iata and h.date >= v_since and h.flight_id <> f.id and h.sched_dep is not null
             and h.final_status not in ('cancelled', 'diverted') and h.dep_delay_min is not null) x;
  if n_b > 0 then
    a_tod := (n_b::numeric / (n_b + k)) * (mean_b - mean_all) * (1 - n_f::numeric / (n_f + k));
  end if;

  if f.prediction is not null and (f.prediction ->> 'lateMin') ~ '^\d+$' then
    v_inbound := (f.prediction ->> 'lateMin')::numeric;
  end if;

  a_sum := a_weather + a_airport + a_tod;

  -- 3) shrunk tail probabilities at the shifted thresholds
  with legs as (
    select h.carrier, h.number, h.dep_delay_min as dd
      from public.flight_leg_history h
     where h.dep = f.dep_iata and h.arr = f.arr_iata and h.date >= v_since and h.flight_id <> f.id
       and h.final_status not in ('cancelled', 'diverted') and h.dep_delay_min is not null
  ), c as (
    select t.name, t.thr,
           count(l.*) filter (where l.dd >= t.thr) as c_r,
           count(l.*) filter (where l.dd >= t.thr and l.carrier = f.carrier_iata) as c_c,
           count(l.*) filter (where l.dd >= t.thr and l.carrier = f.carrier_iata and l.number = f.flight_number) as c_f,
           0.6 * exp(-greatest(t.thr, 0) / 25.0) as g
      from (values ('p15u', 15::numeric), ('p15', 15 - a_sum), ('p60', 60 - a_sum)) as t(name, thr)
      left join legs l on true
     group by t.name, t.thr
  ), e as (
    select c.name, c.c_r, c.c_c, c.c_f, (c.c_r + k * c.g) / (n_r + k) as p_r from c
  ), e2 as (
    select e.name, e.c_f, (e.c_c + k * e.p_r) / (n_c + k) as p_c from e
  )
  select max(case when name = 'p15u' then (c_f + k * p_c) / (n_f + k) end),
         max(case when name = 'p15' then (c_f + k * p_c) / (n_f + k) end),
         max(case when name = 'p60' then (c_f + k * p_c) / (n_f + k) end)
    into p15u, p15, p60
    from e2;

  if v_inbound >= 15 then p15 := 0.95; end if;
  if v_inbound >= 60 then p60 := 0.95; end if;
  p15 := least(0.95, greatest(0.01, p15));
  p60 := least(p15, least(0.95, greatest(0.01, p60)));

  v_expected := greatest(m_f + a_sum, v_inbound, 0);

  -- 4) factors. The effects add up to expected_delay_min exactly: each adjustment is rounded on its own and
  -- history takes the rest (so rounding never makes the sum drift by a minute).
  if v_inbound > m_f + a_sum and round(v_inbound - (m_f + a_sum)) >= 1 then
    v_factors := v_factors || jsonb_build_object('key', 'inbound', 'effect_min', round(v_inbound - (m_f + a_sum))::int,
      'params', jsonb_build_object('min', round(v_inbound)::int));
  end if;
  if a_weather > 0 then
    v_factors := v_factors || jsonb_build_object('key', 'weather', 'effect_min', round(a_weather)::int,
      'params', jsonb_build_object('kind', a_kind, 'iata', f.dep_iata));
  end if;
  if round(a_tod) <> 0 then
    v_factors := v_factors || jsonb_build_object('key', 'time_of_day', 'effect_min', round(a_tod)::int,
      'params', jsonb_build_object('bucket', v_bucket));
  end if;
  if round(a_airport) >= 1 then
    v_factors := v_factors || jsonb_build_object('key', 'airport', 'effect_min', round(a_airport)::int,
      'params', jsonb_build_object('iata', f.dep_iata, 'min', round(v_recent_avg)::int));
  end if;

  v_factors := jsonb_build_array(jsonb_build_object('key', 'history',
    'effect_min', round(v_expected)::int - coalesce((select sum((x ->> 'effect_min')::int) from jsonb_array_elements(v_factors) x), 0)::int,
    'params', jsonb_build_object('pct', round(100 * least(1, greatest(0, p15u)))::int,
                                 'basis', case when n_f >= 5 then 'flight' else 'route' end,
                                 'route', f.dep_iata || '→' || f.arr_iata))) || v_factors;

  v_conf := case when n_r >= 20 then 'high' when n_r >= 8 then 'medium' else 'low' end;

  return jsonb_build_object(
    'p_delay_15', round(p15, 2),
    'p_delay_60', round(p60, 2),
    'expected_delay_min', round(v_expected)::int,
    'factors', v_factors,
    'confidence', v_conf,
    'samples', n_r,
    'model', 'rules-v1',
    'computed_at', public.forecast_iso(now()));
end $$;

-- ---------- delay_prediction ------------------------------------------------------------------------------
create or replace function public.delay_prediction(p_flight_id uuid, p_lang text default 'en') returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  f      public.flights%rowtype;
  v_dep  timestamptz;
  v_json jsonb;
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if not public.can_read_flight(p_flight_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  select * into f from public.flights where id = p_flight_id;
  if not found then raise exception 'not_found' using errcode = 'P0002'; end if;
  if not public.is_pro_for_flight(v_user, p_flight_id) then raise exception 'pro_required' using errcode = '42501'; end if;

  v_dep := coalesce(f.est_dep, f.sched_dep);
  if v_dep is null or f.status in ('cancelled', 'departed', 'en_route', 'approaching', 'landed', 'arrived', 'diverted')
     or f.act_dep is not null or f.dep_offblock_at is not null
     or now() < v_dep - interval '72 hours' or now() >= v_dep then
    return null;
  end if;

  select p.json into v_json from public.delay_predictions p
   where p.flight_id = p_flight_id and p.computed_at > now() - interval '15 minutes';
  if not found then
    v_json := public.delay_prediction_core(f);
    insert into public.delay_predictions as p (flight_id, json, computed_at)
    values (p_flight_id, v_json, now())
    on conflict (flight_id) do update set json = excluded.json, computed_at = excluded.computed_at;
  end if;
  return public.prediction_render(v_json, p_lang);
end $$;

-- ---------- gate_prediction -------------------------------------------------------------------------------
create or replace function public.gate_prediction(p_flight_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  f      public.flights%rowtype;
  v_out  jsonb;
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if not public.can_read_flight(p_flight_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  select * into f from public.flights where id = p_flight_id;
  if not found then raise exception 'not_found' using errcode = 'P0002'; end if;
  if not public.is_pro_for_flight(v_user, p_flight_id) then raise exception 'pro_required' using errcode = '42501'; end if;

  -- ties: the gate used most recently wins, then the name
  with g as (
    select upper(btrim(h.dep_gate)) as gate,
           mode() within group (order by h.dep_terminal) as terminal,
           count(*)::int as n,
           max(h.date) as last_seen
      from public.flight_leg_history h
     where h.carrier = f.carrier_iata and h.number = f.flight_number and h.dep = f.dep_iata
       and h.date >= current_date - 60 and h.flight_id <> f.id
       and coalesce(btrim(h.dep_gate), '') <> ''
     group by upper(btrim(h.dep_gate))
  ), r as (
    select g.*, row_number() over (order by g.n desc, g.last_seen desc, g.gate) as rk, sum(g.n) over () as total
      from g
  )
  select jsonb_build_object(
           'gate', top.gate,
           'terminal', top.terminal,
           'share', round(top.n::numeric / top.total, 2),
           'count', top.n,
           'samples', top.total,
           'alternatives', coalesce((
             select jsonb_agg(jsonb_build_object('gate', a.gate, 'share', round(a.n::numeric / a.total, 2)) order by a.rk)
               from r a where a.rk between 2 and 4), '[]'::jsonb))
    into v_out
    from r top
   where top.rk = 1 and top.total >= 4;
  return v_out;
end $$;

-- ---------- execute rights ----------------------------------------------------------------------------------
revoke execute on function public.prediction_label(text, text, jsonb) from public, anon, authenticated;
revoke execute on function public.prediction_render(jsonb, text) from public, anon, authenticated;
revoke execute on function public.delay_prediction_core(public.flights) from public, anon, authenticated;
grant execute on function public.prediction_label(text, text, jsonb) to service_role;
grant execute on function public.prediction_render(jsonb, text) to service_role;
grant execute on function public.delay_prediction_core(public.flights) to service_role;
revoke execute on function public.delay_prediction(uuid, text) from public, anon;
grant execute on function public.delay_prediction(uuid, text) to authenticated;
revoke execute on function public.gate_prediction(uuid) from public, anon;
grant execute on function public.gate_prediction(uuid) to authenticated;
