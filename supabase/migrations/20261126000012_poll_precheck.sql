-- invoke_poll_flights() also wakes poll-flights when a check-in reminder is due
-- (docs/P1_CONTRACTS.md section 3). Same body as 20261122000002_cron.sql otherwise; the pre-check
-- moved into poll_work_due() so it can be tested without pg_cron / pg_net / Vault.

create or replace function public.poll_work_due() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
           select 1 from public.flights
            where next_poll_at <= now()
              and (tracker_count > 0 or inbound_watch_until > now())
              and (lease_until is null or lease_until < now()))
      or exists (select 1 from public.due_checkin_flights(1));
$$;
revoke execute on function public.poll_work_due() from public, anon, authenticated;
grant execute on function public.poll_work_due() to service_role;

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
  -- Cheap pre-check: don't even wake the Edge Function if no flight and no check-in reminder is due.
  if not public.poll_work_due() then
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
