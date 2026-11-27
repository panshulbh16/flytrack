-- P4 section 1 (docs/P4_CONTRACTS.md): radar rate limit.
--   * radar_quota: one row per user, a fixed window of p_window_seconds with a request count.
--   * radar_take_quota(user, limit, window): counts one request, false when over the limit.
--     Service role only (called by the radar Edge Function after its Pro check).

create table public.radar_quota (
  user_id      uuid primary key references auth.users (id) on delete cascade,
  window_start timestamptz not null default now(),
  count        int not null default 0 check (count >= 0)
);
alter table public.radar_quota enable row level security;
revoke all on public.radar_quota from public, anon, authenticated;
grant select, insert, update, delete on public.radar_quota to service_role;

-- Atomic: one upsert per request. A window older than p_window_seconds starts over at 1.
create or replace function public.radar_take_quota(p_user uuid, p_limit int default 60,
                                                   p_window_seconds int default 600)
returns boolean
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_count int;
begin
  if p_user is null or p_limit < 1 or p_window_seconds < 1 then return false; end if;
  insert into public.radar_quota as q (user_id, window_start, count)
  values (p_user, now(), 1)
  on conflict (user_id) do update
    set window_start = case when q.window_start <= now() - make_interval(secs => p_window_seconds)
                            then now() else q.window_start end,
        count        = case when q.window_start <= now() - make_interval(secs => p_window_seconds)
                            then 1 else q.count + 1 end
  returning count into v_count;
  return v_count <= p_limit;
end $$;

revoke execute on function public.radar_take_quota(uuid, int, int) from public, anon, authenticated;
grant execute on function public.radar_take_quota(uuid, int, int) to service_role;
