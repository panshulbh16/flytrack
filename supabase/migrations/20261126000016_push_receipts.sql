-- P2 delivery receipts (docs/P2_CONTRACTS.md section 3):
--   * push_log.delivered_at, set by the ack-push Edge Function through ack_push() (service role only)
--   * push_latency_daily: p50/p90 of delivered_at - flight_events.created_at, service role only

alter table public.push_log add column delivered_at timestamptz;

-- ack_push looks rows up by (event, user).
create index push_log_event_user_idx on public.push_log (event_id, user_id) where event_id is not null;

-- Marks the caller's sent alert rows for one event as delivered. The Edge Function passes the user id from the
-- verified JWT, so a user can only ever touch their own rows.
--   * delivered_at = least(received_at, now()): a phone clock that runs ahead can't post-date it;
--   * never before the row was sent (a phone clock that runs behind can't make latency negative);
--   * only the first receipt counts ("arrived" or "opened", whichever is first), later ones are no-ops.
-- Returns the number of rows updated.
create or replace function public.ack_push(p_user uuid, p_event_id bigint, p_received_at timestamptz default null)
returns integer
language plpgsql security definer set search_path = '' as $$
declare v_count integer;
begin
  if p_user is null or p_event_id is null then return 0; end if;
  update public.push_log l
     set delivered_at = greatest(l.created_at, least(coalesce(p_received_at, now()), now()))
   where l.event_id = p_event_id
     and l.user_id = p_user
     and l.kind = 'alert'
     and l.status = 'sent'          -- a push FCM refused was never delivered
     and l.delivered_at is null;
  get diagnostics v_count = row_count;
  return v_count;
end $$;
revoke execute on function public.ack_push(uuid, bigint, timestamptz) from public, anon, authenticated;
grant execute on function public.ack_push(uuid, bigint, timestamptz) to service_role;

-- One row per day (India time, by when the event was created): how fast alerts reached phones.
-- Only alert pushes that FCM accepted count; "delivered" is how many of those were acknowledged.
create or replace view public.push_latency_daily with (security_invoker = true) as
select
  (e.created_at at time zone 'Asia/Kolkata')::date                                   as day,
  count(*)                                                                            as sent,
  count(l.delivered_at)                                                               as delivered,
  round(100.0 * count(l.delivered_at) / nullif(count(*), 0), 1)                       as delivered_pct,
  percentile_cont(0.5) within group (order by extract(epoch from l.delivered_at - e.created_at))
    filter (where l.delivered_at is not null)                                         as p50_seconds,
  percentile_cont(0.9) within group (order by extract(epoch from l.delivered_at - e.created_at))
    filter (where l.delivered_at is not null)                                         as p90_seconds
from public.push_log l
join public.flight_events e on e.id = l.event_id
where l.kind = 'alert' and l.status = 'sent'
group by 1;

revoke all on public.push_latency_daily from public, anon, authenticated;
grant select on public.push_latency_daily to service_role;
