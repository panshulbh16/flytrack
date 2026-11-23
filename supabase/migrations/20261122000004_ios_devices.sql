-- iOS devices share the FCM token table. The Android app keeps calling the five-argument
-- form; p_platform defaults to 'android', so existing clients are unchanged.

alter table public.devices drop constraint if exists devices_platform_check;
alter table public.devices
  add constraint devices_platform_check check (platform in ('android', 'ios'));

drop function public.register_device(text, text, text, text, text);

create function public.register_device(
  p_token text,
  p_app_version text default null,
  p_os_version text default null,
  p_locale text default null,
  p_timezone text default null,
  p_platform text default 'android'
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_token is null or char_length(p_token) < 20 or char_length(p_token) > 4096 then
    raise exception 'invalid_token' using errcode = '22023';
  end if;
  if p_platform is null or p_platform not in ('android', 'ios') then
    raise exception 'invalid_platform' using errcode = '22023';
  end if;
  insert into public.devices (user_id, fcm_token, platform, app_version, os_version, locale, timezone, last_seen_at)
  values (auth.uid(), p_token, p_platform, p_app_version, p_os_version, p_locale, p_timezone, now())
  on conflict (fcm_token) do update
     set user_id = excluded.user_id,
         platform = excluded.platform,
         app_version = excluded.app_version,
         os_version = excluded.os_version,
         locale = excluded.locale,
         timezone = excluded.timezone,
         last_seen_at = now(),
         -- keep the user's own mute; reset only when the phone moved to another account
         notifications_enabled = case when public.devices.user_id is distinct from excluded.user_id
                                      then true else public.devices.notifications_enabled end
  returning id into v_id;
  return v_id;
end $$;

revoke execute on function public.register_device(text, text, text, text, text, text) from public, anon;
grant execute on function public.register_device(text, text, text, text, text, text) to authenticated;
