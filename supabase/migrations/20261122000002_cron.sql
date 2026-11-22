-- =============================================================================================
-- Scheduler: pg_cron → pg_net → poll-flights Edge Function, every 30 seconds.
-- Secrets live in Supabase Vault (never in this file). Create them once per project:
--   select vault.create_secret('https://<project-ref>.supabase.co', 'project_url');
--   select vault.create_secret('<same value as the CRON_SECRET function secret>', 'cron_secret');
-- =============================================================================================
create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

create or replace function public.invoke_poll_flights() returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_url    text := (select decrypted_secret from vault.decrypted_secrets where name = 'project_url');
  v_secret text := (select decrypted_secret from vault.decrypted_secrets where name = 'cron_secret');
begin
  if v_url is null or v_secret is null then
    raise notice 'poll-flights: vault secrets project_url/cron_secret not set, skipping';
    return;
  end if;
  -- Cheap pre-check: don't even wake the Edge Function if nothing is due.
  if not exists (
    select 1 from public.flights
     where next_poll_at <= now()
       and (tracker_count > 0 or inbound_watch_until > now())
       and (lease_until is null or lease_until < now())
  ) then
    return;
  end if;
  perform net.http_post(
    url := v_url || '/functions/v1/poll-flights',
    headers := jsonb_build_object('content-type', 'application/json', 'x-cron-secret', v_secret),
    body := '{}'::jsonb,
    timeout_milliseconds := 30000
  );
end $$;
revoke execute on function public.invoke_poll_flights() from public, anon, authenticated;

-- Housekeeping: retention + correct Pro counters after subscriptions expire by time.
create or replace function public.flytrack_housekeeping() returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  delete from public.push_log where created_at < now() - interval '30 days';
  delete from public.provider_calls where created_at < now() - interval '90 days';
  delete from public.flight_status_history h using public.flights f
   where h.flight_id = f.id and f.tracker_count = 0 and h.observed_at < now() - interval '90 days';
  update public.flights set lease_until = null where lease_until < now() - interval '10 minutes';
  -- repair counter drift in both directions (expired Pro subscriptions, any missed recount)
  for r in select f.id from public.flights f
            where f.tracker_count > 0 or f.pro_tracker_count > 0
               or exists (select 1 from public.user_flights uf where uf.flight_id = f.id and uf.archived_at is null) loop
    perform public.refresh_flight_trackers(r.id);
  end loop;
end $$;
revoke execute on function public.flytrack_housekeeping() from public, anon, authenticated;

select cron.schedule('flytrack-poll', '30 seconds', 'select public.invoke_poll_flights()');
select cron.schedule('flytrack-housekeeping', '17 21 * * *', 'select public.flytrack_housekeeping()'); -- 02:47 IST
