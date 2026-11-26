-- P1 history and provider coverage (docs/P1_CONTRACTS.md section 4).
--   * flight_leg_history: one row per finished leg, upserted by the poller when polling stops
--   * flights.live_seen: set the first time a live position arrives
--   * provider_coverage_daily: per dep airport and day, the share of finished legs with each field
-- All of it is internal (service role only): it feeds scripts/coverage-report.sql, not the apps.

alter table public.flights add column live_seen boolean not null default false;
update public.flights set live_seen = true where live_updated_at is not null or live_lat is not null;

create table public.flight_leg_history (
  flight_id      uuid primary key references public.flights (id) on delete cascade,
  carrier        text not null,
  number         text not null,
  date           date not null,
  dep            char(3) not null,
  arr            char(3) not null,
  sched_dep      timestamptz,
  act_dep        timestamptz,
  sched_arr      timestamptz,
  act_arr        timestamptz,
  dep_delay_min  integer,
  arr_delay_min  integer,
  dep_terminal   text,
  dep_gate       text,
  arr_terminal   text,
  baggage_belt   text,
  aircraft_reg   text,
  aircraft_model text,
  final_status   text not null,
  had_gate       boolean not null default false,
  had_belt       boolean not null default false,
  had_tail       boolean not null default false,
  had_position   boolean not null default false,
  provider       text,
  finalized_at   timestamptz not null default now()
);
create index flight_leg_history_dep_day_idx on public.flight_leg_history (dep, date);
create index flight_leg_history_finalized_idx on public.flight_leg_history (finalized_at);

alter table public.flight_leg_history enable row level security;
revoke all on public.flight_leg_history from public, anon, authenticated;
grant select, insert, update, delete on public.flight_leg_history to service_role;

-- Cancelled legs are counted but left out of the percentages: they never get a gate or a belt.
create or replace view public.provider_coverage_daily with (security_invoker = true) as
select
  h.dep,
  h.date,
  count(*)                                                    as legs,
  count(*) filter (where h.final_status = 'cancelled')        as cancelled,
  count(*) filter (where h.final_status <> 'cancelled')       as flown,
  round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.dep_terminal is not null)
        / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as pct_terminal,
  round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_gate)
        / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as pct_gate,
  round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_belt)
        / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as pct_belt,
  round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_tail)
        / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as pct_tail,
  round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_position)
        / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as pct_position,
  round(avg(h.dep_delay_min) filter (where h.final_status <> 'cancelled'), 1) as avg_dep_delay_min
from public.flight_leg_history h
group by h.dep, h.date;

revoke all on public.provider_coverage_daily from public, anon, authenticated;
grant select on public.provider_coverage_daily to service_role;
