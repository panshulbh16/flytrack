-- Friends ("Mom's flight has landed"), free. docs/P2_WAVE2_CONTRACTS.md section 1.
--   * friend_invites: 8-character single-use codes, valid 7 days, owners read their own rows
--   * friendships: one row per pair (user_low < user_high), notify_low / notify_high per direction
--   * user_flights.share_with_friends (default true): false hides that flight from friends
--   * RPCs: create_friend_invite, accept_friend_invite, remove_friend, set_friend_alerts, my_friends,
--     friends_flights
--   * can_read_flight(): + flights an accepted friend tracks with share_with_friends = true
--   * get_flight_recipients(): + role ('tracker' | 'friend') and friend_name; friends with alerts on who
--     don't track the flight themselves get one row per device. The engine decides which events go to them.
--
-- Clients never write these tables directly: every change goes through the security definer RPCs below.

-- =================================================================================================
-- Tables
-- =================================================================================================
create table public.friend_invites (
  -- 8 characters from an alphabet without look-alikes (no I, L, O, 0, 1), easy to read out on a call
  code       text primary key check (code ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$'),
  user_id    uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '7 days',
  used_by    uuid references auth.users (id) on delete set null,
  used_at    timestamptz,
  constraint friend_invites_used_at check (used_by is null or used_at is not null)
);
create index friend_invites_user_idx on public.friend_invites (user_id, created_at desc);

create table public.friendships (
  user_low    uuid not null references auth.users (id) on delete cascade,
  user_high   uuid not null references auth.users (id) on delete cascade,
  created_at  timestamptz not null default now(),
  notify_low  boolean not null default true,  -- user_low gets alerts for user_high's flights
  notify_high boolean not null default true,  -- user_high gets alerts for user_low's flights
  primary key (user_low, user_high),
  constraint friendships_ordered check (user_low < user_high)
);
create index friendships_high_idx on public.friendships (user_high);

alter table public.friend_invites enable row level security;
alter table public.friendships    enable row level security;
revoke all on public.friend_invites, public.friendships from public, anon, authenticated;
grant select on public.friend_invites, public.friendships to authenticated;
grant select, insert, update, delete on public.friend_invites, public.friendships to service_role;

create policy friend_invites_select_own on public.friend_invites for select to authenticated
  using (user_id = auth.uid());
create policy friendships_select_own on public.friendships for select to authenticated
  using (auth.uid() = user_low or auth.uid() = user_high);

-- ---------- user_flights.share_with_friends ------------------------------------------------------
alter table public.user_flights add column share_with_friends boolean not null default true;
grant update (share_with_friends) on public.user_flights to authenticated;

-- =================================================================================================
-- Internal helpers (not callable by clients)
-- =================================================================================================
create or replace function public.are_friends(p_a uuid, p_b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_a is not null and p_b is not null and p_a <> p_b and exists (
    select 1 from public.friendships fr
     where fr.user_low = least(p_a, p_b) and fr.user_high = greatest(p_a, p_b));
$$;

-- The user's display name on one line, trimmed; null when unset or blank (the apps show "Your friend" /
-- "Aapka dost"). Control characters and line / paragraph separators become spaces, runs of spaces collapse,
-- and bidi embedding / override / isolate controls (U+202A-U+202E, U+2066-U+2069) are dropped: a name must
-- not be able to reverse or reorder the rest of a notification or a list row ("Asha" + RLO + "...").
create or replace function public.friend_display_name(p_user uuid) returns text
language sql stable security definer set search_path = '' as $$
  select nullif(btrim(regexp_replace(
           regexp_replace(
             regexp_replace(p.display_name, '[\u202A-\u202E\u2066-\u2069]', '', 'g'),
             '[[:cntrl:]\u2028\u2029]', ' ', 'g'),
           '\s+', ' ', 'g')), '')
    from public.profiles p where p.id = p_user;
$$;

create or replace function public.friend_count(p_user uuid) returns integer
language sql stable security definer set search_path = '' as $$
  select count(*)::int from public.friendships fr where fr.user_low = p_user or fr.user_high = p_user;
$$;

-- 8 random characters from the invite alphabet. The randomness is gen_random_uuid() (core Postgres,
-- strong random), sampled without modulo bias. Bytes 6 and 8 hold the UUID version / variant bits.
create or replace function public.new_friend_code() returns text
language plpgsql volatile set search_path = '' as $$
declare
  v_alpha constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';  -- 31 characters
  v_code  text := '';
  v_bytes bytea;
  v_b     int;
begin
  while char_length(v_code) < 8 loop
    v_bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
    for i in 0..15 loop
      continue when i in (6, 8);
      v_b := get_byte(v_bytes, i);
      continue when v_b >= 248;  -- 248 = 31 * 8: keep every character equally likely
      v_code := v_code || substr(v_alpha, v_b % 31 + 1, 1);
      exit when char_length(v_code) = 8;
    end loop;
  end loop;
  return v_code;
end $$;

-- Public fields of one flight, the same shape as the shared-flight JSON (get_shared_flight). Internal.
create or replace function public.flight_public_json(p_flight uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'carrier', f.carrier_iata, 'number', f.flight_number, 'date', f.flight_date, 'airline', f.airline_name,
    'status', f.status,
    'sched_dep', f.sched_dep, 'est_dep', f.est_dep, 'act_dep', f.act_dep,
    'sched_arr', f.sched_arr, 'est_arr', f.est_arr, 'act_arr', f.act_arr,
    'dep', jsonb_build_object('iata', f.dep_iata, 'name', da.name, 'city', da.city, 'lat', da.lat, 'lon', da.lon,
                              'tz', da.tz, 'terminal', f.dep_terminal, 'gate', f.dep_gate),
    'arr', jsonb_build_object('iata', f.arr_iata, 'name', aa.name, 'city', aa.city, 'lat', aa.lat, 'lon', aa.lon,
                              'tz', aa.tz, 'terminal', f.arr_terminal, 'gate', f.arr_gate, 'belt', f.baggage_belt),
    'aircraft', jsonb_build_object('model', f.aircraft_model),
    'live', case when f.live_lat is not null and f.live_lon is not null then
              jsonb_build_object('lat', f.live_lat, 'lon', f.live_lon, 'alt_ft', f.live_alt_ft,
                                 'speed_kts', f.live_speed_kts, 'heading', f.live_heading, 'at', f.live_updated_at)
            end,
    'updated_at', f.updated_at)
  from public.flights f
  left join public.airports da on da.iata = f.dep_iata
  left join public.airports aa on aa.iata = f.arr_iata
  where f.id = p_flight;
$$;

-- =================================================================================================
-- RPCs (authenticated)
-- =================================================================================================

-- Returns the caller's unexpired, unused code, or a new one. One active code per user at a time.
create or replace function public.create_friend_invite() returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_me   uuid := auth.uid();
  v_code text;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('friend_invite:' || v_me::text, 0));

  select i.code into v_code from public.friend_invites i
   where i.user_id = v_me and i.used_by is null and i.used_at is null and i.expires_at > now()
   order by i.created_at desc limit 1;
  if v_code is not null then return v_code; end if;

  -- dead codes are kept 30 days (they explain "invalid code" to support), then trimmed
  delete from public.friend_invites
   where user_id = v_me and expires_at < now() - interval '30 days';

  loop
    v_code := public.new_friend_code();
    insert into public.friend_invites (code, user_id) values (v_code, v_me)
      on conflict (code) do nothing;
    exit when found;
  end loop;
  return v_code;
end $$;

-- Accepts someone's code. Errors (contract): invalid_code P0002, own_code 22023, already_friends 23505,
-- friend_limit P0001 (either side already has 50 friends).
create or replace function public.accept_friend_invite(p_code text) returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_me     uuid := auth.uid();
  v_code   text := upper(regexp_replace(coalesce(p_code, ''), '[\s-]', '', 'g'));
  v_inv    public.friend_invites;
  v_max    constant int := 50;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if v_code !~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$' then
    raise exception 'invalid_code' using errcode = 'P0002';
  end if;

  select * into v_inv from public.friend_invites i where i.code = v_code for update;
  if not found or v_inv.used_at is not null or v_inv.used_by is not null or v_inv.expires_at <= now() then
    raise exception 'invalid_code' using errcode = 'P0002';
  end if;
  if v_inv.user_id = v_me then raise exception 'own_code' using errcode = '22023'; end if;

  -- Both users' friend counts must not race past the limit: lock both, always in the same order.
  perform pg_advisory_xact_lock(hashtextextended('friends:' || least(v_me, v_inv.user_id)::text, 0));
  perform pg_advisory_xact_lock(hashtextextended('friends:' || greatest(v_me, v_inv.user_id)::text, 0));

  if public.are_friends(v_me, v_inv.user_id) then
    raise exception 'already_friends' using errcode = '23505';
  end if;
  if public.friend_count(v_me) >= v_max or public.friend_count(v_inv.user_id) >= v_max then
    raise exception 'friend_limit' using errcode = 'P0001';
  end if;

  insert into public.friendships (user_low, user_high)
  values (least(v_me, v_inv.user_id), greatest(v_me, v_inv.user_id));
  update public.friend_invites set used_by = v_me, used_at = now() where code = v_code;

  return jsonb_build_object('friend_id', v_inv.user_id,
                            'display_name', public.friend_display_name(v_inv.user_id));
end $$;

-- Ends the friendship both ways. Not friends → no-op.
create or replace function public.remove_friend(p_friend uuid) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare v_me uuid := auth.uid();
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_friend is null or p_friend = v_me then return; end if;
  delete from public.friendships
   where user_low = least(v_me, p_friend) and user_high = greatest(v_me, p_friend);
end $$;

-- Turns on or off the alerts I get for that friend's flights. Not friends → not_friends (P0002).
create or replace function public.set_friend_alerts(p_friend uuid, p_enabled boolean) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare v_me uuid := auth.uid();
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if p_enabled is null then raise exception 'invalid_value' using errcode = '22023'; end if;
  update public.friendships
     set notify_low  = case when user_low  = v_me then p_enabled else notify_low end,
         notify_high = case when user_high = v_me then p_enabled else notify_high end
   where p_friend is not null and p_friend <> v_me
     and user_low = least(v_me, p_friend) and user_high = greatest(v_me, p_friend);
  if not found then raise exception 'not_friends' using errcode = 'P0002'; end if;
end $$;

-- [{friend_id, display_name, alerts_enabled, since}], newest friend first. display_name is null when unset.
create or replace function public.my_friends() returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'friend_id', x.friend_id,
           'display_name', public.friend_display_name(x.friend_id),
           'alerts_enabled', x.alerts_enabled,
           'since', x.since) order by x.since desc, x.friend_id), '[]'::jsonb)
    from (select case when fr.user_low = auth.uid() then fr.user_high else fr.user_low end as friend_id,
                 case when fr.user_low = auth.uid() then fr.notify_low else fr.notify_high end as alerts_enabled,
                 fr.created_at as since
            from public.friendships fr
           where auth.uid() is not null and (fr.user_low = auth.uid() or fr.user_high = auth.uid())) x;
$$;

-- Friends' flights that are upcoming or departed within the last 24 h, shared with friends and not archived,
-- earliest departure first: [{friend_id, display_name, flight_id, flight: <shared-flight public fields>}].
-- flight_id is extra to the contract shape: the apps open the detail screen with it (can_read_flight allows it).
create or replace function public.friends_flights() returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'friend_id', x.friend_id,
           'display_name', public.friend_display_name(x.friend_id),
           'flight_id', x.flight_id,
           'flight', public.flight_public_json(x.flight_id)) order by x.dep_at, x.friend_id, x.flight_id), '[]'::jsonb)
    from (select uf.user_id as friend_id, f.id as flight_id, coalesce(f.act_dep, f.est_dep, f.sched_dep) as dep_at
            from public.friendships fr
            join public.user_flights uf
              on uf.user_id = case when fr.user_low = auth.uid() then fr.user_high else fr.user_low end
            join public.flights f on f.id = uf.flight_id
           where auth.uid() is not null
             and (fr.user_low = auth.uid() or fr.user_high = auth.uid())
             and uf.share_with_friends and uf.archived_at is null
             and coalesce(f.act_dep, f.est_dep, f.sched_dep) >= now() - interval '24 hours') x;
$$;

-- =================================================================================================
-- can_read_flight: + flights an accepted friend tracks and shares
-- =================================================================================================
-- Same signature and earlier conditions as 20261126000009_security.sql (own flights, archived included;
-- inbound legs of own Pro / trial flights). New: a flight an accepted friend tracks (not archived) with
-- share_with_friends = true.
create or replace function public.can_read_flight(p_flight uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and (
    exists (select 1 from public.user_flights uf
             where uf.user_id = auth.uid() and uf.flight_id = p_flight)
    or exists (select 1 from public.flights f
                 join public.user_flights uf on uf.flight_id = f.id and uf.user_id = auth.uid()
                where f.inbound_flight_id = p_flight
                  and public.is_pro_for_flight(auth.uid(), f.id))
    or exists (select 1 from public.user_flights uf
                 join public.friendships fr
                   on (fr.user_low = auth.uid() and fr.user_high = uf.user_id)
                   or (fr.user_high = auth.uid() and fr.user_low = uf.user_id)
                where uf.flight_id = p_flight and uf.archived_at is null and uf.share_with_friends)
  );
$$;

-- =================================================================================================
-- get_flight_recipients: + role and friend_name
-- =================================================================================================
-- Tracker rows are exactly those of 20261126000015_alert_prefs.sql (role 'tracker', friend_name null).
-- Friend rows: users with alerts on for a friend who tracks this flight (not archived, shared with friends),
-- who don't track it themselves; one row per device of theirs. When two of their friends track the same
-- flight, friend_name is the one who added it first. is_pro is false and notify_prefs empty: friends only
-- ever get the free friend events (the engine filters), never Pro ones.
drop function public.get_flight_recipients(uuid);
create function public.get_flight_recipients(p_flight_id uuid)
returns table (
  user_id uuid, is_pro boolean, notify_prefs jsonb, device_id uuid, fcm_token text,
  alert_language text, quiet_hours_enabled boolean, quiet_start time, quiet_end time, quiet_tz text,
  role text, friend_name text
)
language sql stable security definer set search_path = '' as $$
  select uf.user_id, public.is_pro_for_flight(uf.user_id, p_flight_id), uf.notify_prefs, d.id, d.fcm_token,
         coalesce(p.alert_language, 'en'), coalesce(p.quiet_hours_enabled, false),
         coalesce(p.quiet_start, time '23:00'), coalesce(p.quiet_end, time '07:00'),
         coalesce(p.quiet_tz, 'Asia/Kolkata'),
         'tracker'::text, null::text
    from public.user_flights uf
    join public.devices d on d.user_id = uf.user_id and d.notifications_enabled
    left join public.profiles p on p.id = uf.user_id
   where uf.flight_id = p_flight_id and uf.archived_at is null
  union all
  select s.friend_id, false, '{}'::jsonb, s.device_id, s.fcm_token,
         s.alert_language, s.quiet_hours_enabled, s.quiet_start, s.quiet_end, s.quiet_tz,
         'friend'::text, public.friend_display_name(s.tracker_id)
    from (
      select distinct on (fu.friend_id, d.id)
             fu.friend_id, fu.tracker_id, d.id as device_id, d.fcm_token,
             coalesce(p.alert_language, 'en') as alert_language, coalesce(p.quiet_hours_enabled, false) as quiet_hours_enabled,
             coalesce(p.quiet_start, time '23:00') as quiet_start, coalesce(p.quiet_end, time '07:00') as quiet_end,
             coalesce(p.quiet_tz, 'Asia/Kolkata') as quiet_tz
        from (
          select uf.user_id as tracker_id, uf.created_at as tracked_at,
                 case when fr.user_low = uf.user_id then fr.user_high else fr.user_low end as friend_id
            from public.user_flights uf
            join public.friendships fr
              on (fr.user_high = uf.user_id and fr.notify_low)    -- friend is user_low
              or (fr.user_low = uf.user_id and fr.notify_high)    -- friend is user_high
           where uf.flight_id = p_flight_id and uf.archived_at is null and uf.share_with_friends
        ) fu
        join public.devices d on d.user_id = fu.friend_id and d.notifications_enabled
        left join public.profiles p on p.id = fu.friend_id
       where not exists (select 1 from public.user_flights own
                          where own.flight_id = p_flight_id and own.user_id = fu.friend_id
                            and own.archived_at is null)
       order by fu.friend_id, d.id, fu.tracked_at, fu.tracker_id
    ) s;
$$;

-- =================================================================================================
-- Function execute rights
-- =================================================================================================
revoke execute on function public.are_friends(uuid, uuid) from public, anon, authenticated;
revoke execute on function public.friend_display_name(uuid) from public, anon, authenticated;
revoke execute on function public.friend_count(uuid) from public, anon, authenticated;
revoke execute on function public.new_friend_code() from public, anon, authenticated;
revoke execute on function public.flight_public_json(uuid) from public, anon, authenticated;
grant execute on function public.are_friends(uuid, uuid) to service_role;
grant execute on function public.friend_display_name(uuid) to service_role;
grant execute on function public.flight_public_json(uuid) to service_role;

revoke execute on function public.create_friend_invite() from public, anon;
revoke execute on function public.accept_friend_invite(text) from public, anon;
revoke execute on function public.remove_friend(uuid) from public, anon;
revoke execute on function public.set_friend_alerts(uuid, boolean) from public, anon;
revoke execute on function public.my_friends() from public, anon;
revoke execute on function public.friends_flights() from public, anon;
grant execute on function public.create_friend_invite() to authenticated;
grant execute on function public.accept_friend_invite(text) to authenticated;
grant execute on function public.remove_friend(uuid) to authenticated;
grant execute on function public.set_friend_alerts(uuid, boolean) to authenticated;
grant execute on function public.my_friends() to authenticated;
grant execute on function public.friends_flights() to authenticated;

revoke execute on function public.can_read_flight(uuid) from public, anon;
grant execute on function public.can_read_flight(uuid) to authenticated, service_role;
revoke execute on function public.get_flight_recipients(uuid) from public, anon, authenticated;
grant execute on function public.get_flight_recipients(uuid) to service_role;
