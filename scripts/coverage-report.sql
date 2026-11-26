-- Provider coverage at the Indian metros (docs/P1_CONTRACTS.md section 4, README "Going live with AeroDataBox").
-- Run with a service-role / owner connection:
--   psql "$DB_URL" -v days=14 -f scripts/coverage-report.sql
-- Rows come from public.flight_leg_history (one per finished, user-tracked leg; cancelled legs are counted
-- but left out of the percentages). Position = the provider sent a live position at least once.
\if :{?days}
\else
  \set days 14
\endif
\pset footer off
\pset null '-'

\echo '== Per airport, last' :days 'days =='
select h.dep                                                                   as airport,
       count(*)                                                                as legs,
       count(*) filter (where h.final_status = 'cancelled')                    as cancelled,
       round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.dep_terminal is not null)
             / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as "terminal %",
       round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_gate)
             / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as "gate %",
       round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_belt)
             / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as "belt %",
       round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_tail)
             / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as "tail %",
       round(100.0 * count(*) filter (where h.final_status <> 'cancelled' and h.had_position)
             / nullif(count(*) filter (where h.final_status <> 'cancelled'), 0), 1) as "position %",
       round(avg(h.dep_delay_min) filter (where h.final_status <> 'cancelled'), 1) as "avg dep delay min"
  from public.flight_leg_history h
 where h.dep in ('DEL', 'BOM', 'BLR', 'HYD', 'MAA', 'CCU')
   and h.date >= current_date - :days
 group by h.dep
 order by array_position(array['DEL', 'BOM', 'BLR', 'HYD', 'MAA', 'CCU'], h.dep::text);

\echo '== Per airport and day (provider_coverage_daily) =='
select dep as airport, date, legs, cancelled, pct_terminal as "terminal %", pct_gate as "gate %",
       pct_belt as "belt %", pct_tail as "tail %", pct_position as "position %"
  from public.provider_coverage_daily
 where dep in ('DEL', 'BOM', 'BLR', 'HYD', 'MAA', 'CCU')
   and date >= current_date - :days
 order by array_position(array['DEL', 'BOM', 'BLR', 'HYD', 'MAA', 'CCU'], dep::text), date desc;

\echo '== Per carrier at the metros =='
select h.carrier, count(*) as legs,
       round(100.0 * count(*) filter (where h.had_gate) / nullif(count(*), 0), 1) as "gate %",
       round(100.0 * count(*) filter (where h.had_belt) / nullif(count(*), 0), 1) as "belt %",
       round(100.0 * count(*) filter (where h.had_tail) / nullif(count(*), 0), 1) as "tail %"
  from public.flight_leg_history h
 where h.dep in ('DEL', 'BOM', 'BLR', 'HYD', 'MAA', 'CCU')
   and h.date >= current_date - :days
   and h.final_status <> 'cancelled'
 group by h.carrier
 order by legs desc;
