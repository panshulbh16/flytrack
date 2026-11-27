-- P3 A1 + A3 (docs/P3_CONTRACTS.md): unlimited free tracking and the calendar import source.
--   * enforce_tracking_limit(): no product limit any more, only an anti-abuse cap of 100 active flights for
--     free users and 300 for Pro (is_pro, so family members and lifetime grants count as Pro). The error stays
--     'tracking_limit_reached' (P0001), so the apps' existing handling still applies.
--   * user_flights.source: + 'calendar' (flights added from a device-calendar suggestion).

create or replace function public.tracking_cap(p_user uuid) returns integer
language sql stable security definer set search_path = '' as $$
  select case when public.is_pro(p_user) then 300 else 100 end;
$$;

-- Same structure as 20261122000001_init.sql; only the limits changed.
create or replace function public.enforce_tracking_limit() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_active int;
  v_limit  int;
begin
  if new.archived_at is not null then return new; end if;
  -- one check at a time per user, otherwise parallel inserts all see "99 active" and pass
  perform pg_advisory_xact_lock(hashtextextended('tracking_limit:' || new.user_id::text, 0));
  v_limit := public.tracking_cap(new.user_id);
  select count(*) into v_active
    from public.user_flights uf join public.flights f on f.id = uf.flight_id
   where uf.user_id = new.user_id and uf.archived_at is null and uf.id <> new.id
     and coalesce(f.act_arr, f.est_arr, f.sched_arr, now()) > now() - interval '1 day';
  if v_active >= v_limit then
    raise exception 'tracking_limit_reached' using errcode = 'P0001',
      hint = 'Archive some old flights first';
  end if;
  return new;
end $$;

-- The latest list is from 20261126000018_email_import.sql; it is re-created with every value.
alter table public.user_flights
  drop constraint user_flights_source_check,
  add constraint user_flights_source_check
    check (source in ('manual', 'pdf', 'share', 'screenshot', 'gmail', 'scan', 'email', 'calendar'));

revoke execute on function public.tracking_cap(uuid) from public, anon, authenticated;
grant execute on function public.tracking_cap(uuid) to service_role;
revoke execute on function public.enforce_tracking_limit() from public, anon, authenticated;
