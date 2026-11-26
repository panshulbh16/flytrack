-- iOS Live Activities: one ActivityKit push token per (device, flight). The app registers it with
-- register_live_activity(); the engine (service role) reads the rows in fanOut, sends each change
-- through FCM, and deletes the row once it has sent the "end" event.

create table public.live_activities (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users (id) on delete cascade,
  device_id      uuid not null references public.devices (id) on delete cascade,
  flight_id      uuid not null references public.flights (id) on delete cascade,
  activity_token text not null check (activity_token ~ '^[0-9a-f]{16,}$' and char_length(activity_token) <= 512), -- hex, as ActivityKit hands it out
  updated_at     timestamptz not null default now(),
  constraint live_activities_unique unique (device_id, flight_id)
);
create index live_activities_flight_idx on public.live_activities (flight_id);

alter table public.live_activities enable row level security;
revoke all on public.live_activities from anon;
revoke insert, update on public.live_activities from authenticated; -- writes go through the RPC
create policy live_activities_select_own on public.live_activities for select to authenticated
  using (user_id = auth.uid());
create policy live_activities_delete_own on public.live_activities for delete to authenticated
  using (user_id = auth.uid());

-- The device must be the caller's and the flight one they track, so nobody can attach a token to
-- someone else's phone or follow a flight they do not track.
create function public.register_live_activity(p_fcm_token text, p_flight_id uuid, p_activity_token text)
returns void
language plpgsql security definer set search_path = '' as $$
declare v_device uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_activity_token is null or p_activity_token !~ '^[0-9a-f]{16,}$' or char_length(p_activity_token) > 512 then
    raise exception 'invalid_token' using errcode = '22023';
  end if;
  select id into v_device from public.devices where fcm_token = p_fcm_token and user_id = auth.uid();
  if v_device is null then raise exception 'device_not_registered' using errcode = '22023'; end if;
  if not exists (select 1 from public.user_flights
                  where user_id = auth.uid() and flight_id = p_flight_id and archived_at is null) then
    raise exception 'not_tracked' using errcode = '22023';
  end if;
  insert into public.live_activities (user_id, device_id, flight_id, activity_token)
  values (auth.uid(), v_device, p_flight_id, p_activity_token)
  on conflict (device_id, flight_id) do update
     set user_id = excluded.user_id, activity_token = excluded.activity_token, updated_at = now();
end $$;

revoke execute on function public.register_live_activity(text, uuid, text) from public, anon;
grant execute on function public.register_live_activity(text, uuid, text) to authenticated;

-- 'test' is kept from 20261124000005_push_log_test_kind (send-test-push).
alter table public.push_log drop constraint push_log_kind_check;
alter table public.push_log
  add constraint push_log_kind_check check (kind in ('alert', 'sync', 'test', 'live_activity'));
