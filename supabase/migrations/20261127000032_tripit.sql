-- P4 section 2 (docs/P4_CONTRACTS.md): TripIt sync (Pro).
--   * tripit_connections: one row per user, service role only. The request token is kept in clear (it is
--     public: it travels in the authorize URL) so tripit-callback can find the row; the secrets and the
--     access token are encrypted by the Edge Functions (AES-256-GCM with the function secret
--     TRIPIT_TOKEN_KEY, values prefixed "v1:") and only fall back to clear text in this service-role-only
--     table when that secret is not set.
--   * tripit_segments: segments a sync already handled (idempotency without spending a flight search on
--     every hourly run). Deleted with the connection.
--   * tripit_status() for the caller; tripit_track_flight() / tripit_sync_targets() for the functions.
--   * user_flights.source: + 'tripit'.
--   * pg_cron: hourly tripit-sync, same Vault pattern as invoke_poll_flights() (project_url, cron_secret).

-- ---------- tables -------------------------------------------------------------------------------
create table public.tripit_connections (
  user_id        uuid primary key references auth.users (id) on delete cascade,
  request_token  text unique check (char_length(request_token) <= 200),
  request_secret text check (char_length(request_secret) <= 500),
  requested_at   timestamptz,
  access_token   text check (char_length(access_token) <= 500),
  access_secret  text check (char_length(access_secret) <= 500),
  connected_at   timestamptz,
  last_sync_at   timestamptz,
  last_error     text check (char_length(last_error) <= 200),
  constraint tripit_connected_has_secret check (access_token is null or access_secret is not null)
);
alter table public.tripit_connections enable row level security;
revoke all on public.tripit_connections from public, anon, authenticated;
grant select, insert, update, delete on public.tripit_connections to service_role;

create table public.tripit_segments (
  user_id      uuid not null references public.tripit_connections (user_id) on delete cascade,
  -- "6E2341:2026-12-01:DEL" (operating carrier + number, local departure date, departure airport)
  segment_key  text not null check (segment_key ~ '^[A-Z0-9]{2}[0-9]{1,4}[A-Z]?:[0-9]{4}-[0-9]{2}-[0-9]{2}:[A-Z]{3}$'),
  flight_id    uuid references public.flights (id) on delete set null,
  outcome      text not null check (outcome in ('added', 'exists', 'not_found', 'limit', 'error')),
  attempted_at timestamptz not null default now(),
  primary key (user_id, segment_key)
);
alter table public.tripit_segments enable row level security;
revoke all on public.tripit_segments from public, anon, authenticated;
grant select, insert, update, delete on public.tripit_segments to service_role;

-- ---------- user_flights.source: 'tripit' -------------------------------------------------------
-- The latest list is from 20261126000021_limits_sources.sql; it is re-created with every value.
alter table public.user_flights
  drop constraint user_flights_source_check,
  add constraint user_flights_source_check
    check (source in ('manual', 'pdf', 'share', 'screenshot', 'gmail', 'scan', 'email', 'calendar', 'tripit'));

-- ---------- RPCs ---------------------------------------------------------------------------------
-- The caller's connection state. Never returns tokens.
create or replace function public.tripit_status() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'connected', coalesce((select c.access_token is not null from public.tripit_connections c
                            where c.user_id = auth.uid()), false),
    'last_sync_at', (select c.last_sync_at from public.tripit_connections c
                      where c.user_id = auth.uid() and c.access_token is not null),
    'last_error', (select c.last_error from public.tripit_connections c
                    where c.user_id = auth.uid() and c.access_token is not null));
$$;

-- Tracks a flight for a user with source 'tripit'. 'added' | 'exists' | 'limit'.
-- An archived row also counts as 'exists': a flight the user removed is not brought back every hour.
create or replace function public.tripit_track_flight(p_user uuid, p_flight uuid) returns text
language plpgsql volatile security definer set search_path = '' as $$
begin
  if exists (select 1 from public.user_flights where user_id = p_user and flight_id = p_flight) then
    return 'exists';
  end if;
  begin
    insert into public.user_flights (user_id, flight_id, source) values (p_user, p_flight, 'tripit');
  exception
    when unique_violation then return 'exists'; -- a parallel sync won
    when raise_exception then
      if sqlerrm = 'tracking_limit_reached' then return 'limit'; end if;
      raise;
  end;
  return 'added';
end $$;

-- Connected Pro users due for the hourly sync (not synced in the last 50 minutes), oldest first.
create or replace function public.tripit_sync_targets(p_limit int default 50) returns setof uuid
language sql stable security definer set search_path = '' as $$
  select c.user_id from public.tripit_connections c
   where c.access_token is not null
     and (c.last_sync_at is null or c.last_sync_at < now() - interval '50 minutes')
     and public.is_pro(c.user_id)
   order by c.last_sync_at nulls first
   limit greatest(1, least(p_limit, 500));
$$;

-- ---------- scheduler ----------------------------------------------------------------------------
-- pg_cron → pg_net → tripit-sync. Same Vault secrets as invoke_poll_flights(); nothing secret here.
create or replace function public.invoke_tripit_sync() returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_url    text := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url');
  v_secret text := (select decrypted_secret from vault.decrypted_secrets where name = 'cron_secret');
begin
  if v_url is null or v_secret is null then
    raise notice 'tripit-sync: vault secrets project_url/cron_secret not set, skipping';
    return;
  end if;
  if not exists (select 1 from public.tripit_connections where access_token is not null) then return; end if;
  perform net.http_post(
    url := v_url || '/functions/v1/tripit-sync',
    headers := jsonb_build_object('content-type', 'application/json', 'x-cron-secret', v_secret),
    body := '{}'::jsonb,
    timeout_milliseconds := 60000
  );
end $$;

-- Hourly at :41. Guarded so the migration also applies where pg_cron is absent (local SQL tests).
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('flytrack-tripit-sync', '41 * * * *', 'select public.invoke_tripit_sync()');
  else
    raise notice 'pg_cron not installed: flytrack-tripit-sync not scheduled';
  end if;
end $$;

-- ---------- execute rights -----------------------------------------------------------------------
revoke execute on function public.tripit_status() from public, anon;
grant execute on function public.tripit_status() to authenticated;
revoke execute on function public.tripit_track_flight(uuid, uuid) from public, anon, authenticated;
grant execute on function public.tripit_track_flight(uuid, uuid) to service_role;
revoke execute on function public.tripit_sync_targets(int) from public, anon, authenticated;
grant execute on function public.tripit_sync_targets(int) to service_role;
revoke execute on function public.invoke_tripit_sync() from public, anon, authenticated;
