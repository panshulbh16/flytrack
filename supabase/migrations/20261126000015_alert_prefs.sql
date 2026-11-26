-- P2 alert extras + alert language (docs/P2_CONTRACTS.md sections 3 and 4):
--   * profiles: quiet hours (quiet_hours_enabled, quiet_start, quiet_end, quiet_tz) and alert_language,
--     all user-editable through column grants (the existing profiles_update_own policy keeps it to own row)
--   * flight_events.type accepts FOG_ALERT (written by the fog step, data from 20261126000013) and DIVERTED_DEST
--   * get_flight_recipients() also returns alert_language and the quiet-hours fields

-- ---------- profiles: quiet hours + alert language ------------------------------------------------
alter table public.profiles
  add column quiet_hours_enabled boolean not null default false,
  add column quiet_start         time    not null default '23:00',
  add column quiet_end           time    not null default '07:00',
  add column quiet_tz            text    not null default 'Asia/Kolkata'
    check (char_length(quiet_tz) between 1 and 64 and quiet_tz ~ '^[A-Za-z][A-Za-z0-9_+/-]*$'),
  add column alert_language      text    not null default 'en'
    check (alert_language in ('en', 'hi', 'hinglish'));

-- The shape check above keeps junk out; this one rejects names Postgres doesn't know ("Asia/Kolkatta"),
-- so a typo fails the app's save instead of silently falling back to India time in every alert.
create or replace function public.profiles_check_quiet_tz() returns trigger
language plpgsql set search_path = '' as $$
begin
  -- posix/*, right/*, localtime and posixrules are Postgres-only names the Edge Functions' Intl rejects
  -- (the alert would silently use India time), so they count as unknown too.
  if new.quiet_tz ~ '^(posix|right)/' or new.quiet_tz in ('localtime', 'posixrules')
     or not exists (select 1 from pg_catalog.pg_timezone_names where name = new.quiet_tz) then
    raise exception 'invalid_time_zone' using errcode = '22023';
  end if;
  return new;
end $$;
create trigger profiles_quiet_tz before insert or update of quiet_tz on public.profiles
  for each row execute function public.profiles_check_quiet_tz();
revoke execute on function public.profiles_check_quiet_tz() from public, anon, authenticated;

grant update (quiet_hours_enabled, quiet_start, quiet_end, quiet_tz, alert_language) on public.profiles to authenticated;

-- ---------- flight_events.type: fog + diversion airport known --------------------------------------
-- The only place these two are added (migration 13 leaves this constraint alone).
alter table public.flight_events
  drop constraint flight_events_type_check,
  add constraint flight_events_type_check check (type in (
    'DELAY','DELAY_REDUCED','SCHEDULE_CHANGE','GATE_ASSIGNED','GATE_CHANGE','TERMINAL_CHANGE','BOARDING','DEPARTED',
    'LANDED','BELT_ASSIGNED','BELT_CHANGE','CANCELLED','DIVERTED','REINSTATED','INBOUND_LATE',
    'CHECKIN_OPEN','CHECKIN_CLOSING',
    'FOG_ALERT','DIVERTED_DEST'));

-- ---------- get_flight_recipients: + language and quiet hours --------------------------------------
-- The result columns change, so the function is dropped and created again (create or replace can't).
-- Pro semantics are unchanged from 20261126000008_billing.sql: is_pro_for_flight (Pro plan, or this is
-- the user's trial flight).
drop function public.get_flight_recipients(uuid);
create function public.get_flight_recipients(p_flight_id uuid)
returns table (
  user_id uuid, is_pro boolean, notify_prefs jsonb, device_id uuid, fcm_token text,
  alert_language text, quiet_hours_enabled boolean, quiet_start time, quiet_end time, quiet_tz text
)
language sql stable security definer set search_path = '' as $$
  select uf.user_id, public.is_pro_for_flight(uf.user_id, p_flight_id), uf.notify_prefs, d.id, d.fcm_token,
         coalesce(p.alert_language, 'en'), coalesce(p.quiet_hours_enabled, false),
         coalesce(p.quiet_start, time '23:00'), coalesce(p.quiet_end, time '07:00'),
         coalesce(p.quiet_tz, 'Asia/Kolkata')
    from public.user_flights uf
    join public.devices d on d.user_id = uf.user_id and d.notifications_enabled
    left join public.profiles p on p.id = uf.user_id
   where uf.flight_id = p_flight_id and uf.archived_at is null;
$$;
revoke execute on function public.get_flight_recipients(uuid) from public, anon, authenticated;
grant execute on function public.get_flight_recipients(uuid) to service_role;
