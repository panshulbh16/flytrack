-- P3 B5 (docs/P3_CONTRACTS.md): search by route or airport.
--   * route_search_cache: one row per departure airport and local date with the search-route result
--     (the provider's departures for that day, already in the search-flight row shape minus Pro fields).
--     Fresh for 6 h; the search-route Edge Function (service role) is the only reader and writer.
--   * reserve_search_units(): like reserve_search_quota() but takes a cost, so one route search uses
--     2 units of the same daily search quota (search_quota_usage) as a flight-number search.

create table public.route_search_cache (
  dep        char(3) not null check (dep ~ '^[A-Z0-9]{3}$'),
  date       date not null,
  payload    jsonb not null check (jsonb_typeof(payload) = 'array'),
  fetched_at timestamptz not null default now(),
  primary key (dep, date)
);
create index route_search_cache_fetched_idx on public.route_search_cache (fetched_at);

alter table public.route_search_cache enable row level security;
revoke all on public.route_search_cache from public, anon, authenticated;
grant select, insert, update, delete on public.route_search_cache to service_role;

-- Reserves p_cost units of p_user's daily search quota (Indian calendar day). All or nothing: returns false,
-- and uses nothing, when fewer than p_cost units are left. Same lock as reserve_search_quota, so both kinds of
-- search share one counter without racing.
create or replace function public.reserve_search_units(p_user uuid, p_limit int, p_cost int) returns boolean
language plpgsql security definer set search_path = '' as $$
declare
  v_day  date := (now() at time zone 'Asia/Kolkata')::date;
  v_uses int;
begin
  if p_user is null or p_limit is null or p_limit < 1 or p_cost is null or p_cost < 1 or p_cost > p_limit then
    return false;
  end if;
  perform pg_advisory_xact_lock(hashtextextended('search_quota:' || p_user::text, 0));
  insert into public.search_quota_usage as q (user_id, day, uses)
  values (p_user, v_day, p_cost)
  on conflict (user_id, day) do update
     set uses = q.uses + p_cost
   where q.uses + p_cost <= p_limit
  returning q.uses into v_uses;
  delete from public.search_quota_usage where user_id = p_user and day < v_day - 7;
  return v_uses is not null;
end $$;

-- Housekeeping: rows older than 2 days are never served again (the cache is per date and 6 h fresh).
create or replace function public.trim_route_search_cache() returns integer
language sql volatile security definer set search_path = '' as $$
  with d as (delete from public.route_search_cache where fetched_at < now() - interval '2 days' returning 1)
  select count(*)::int from d;
$$;

revoke all on function public.reserve_search_units(uuid, int, int) from public, anon, authenticated;
grant execute on function public.reserve_search_units(uuid, int, int) to service_role;
revoke all on function public.trim_route_search_cache() from public, anon, authenticated;
grant execute on function public.trim_route_search_cache() to service_role;
