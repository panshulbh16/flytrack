-- P2 wave 2, email forwarding import (docs/P2_WAVE2_CONTRACTS.md section 2).
--   * app_config(key, value): small server settings (inbound_email_domain, supabase_url), service role only
--   * random_text_token(): unbiased random tokens for inbox and calendar links (internal)
--   * profiles.inbox_token + my_forwarding_address()
--   * email_imports: one row per inbound email attempt. The email body is never stored (DPDP).
--   * inbound_email_user(), import_track_flight(): service-role helpers for the inbound-email function
--   * user_flights.source adds 'email'; push_log.kind adds 'import'

-- ---------- app_config -------------------------------------------------------------------------
create table public.app_config (
  key        text primary key check (key ~ '^[a-z][a-z0-9_]{1,62}$'),
  value      text not null check (char_length(value) <= 1000),
  updated_at timestamptz not null default now()
);
create trigger app_config_updated_at before update on public.app_config
  for each row execute function public.set_updated_at();
alter table public.app_config enable row level security;
revoke all on public.app_config from public, anon, authenticated;
grant select, insert, update, delete on public.app_config to service_role;

insert into public.app_config (key, value) values ('inbound_email_domain', 'in.heygate.app')
on conflict (key) do nothing;

-- ---------- random tokens ----------------------------------------------------------------------
-- p_len characters drawn uniformly from p_alphabet. Bytes come from gen_random_uuid() (v4, a CSPRNG in
-- Postgres 13+), skipping the version and variant bytes, with rejection sampling so no character is
-- more likely than another. Internal: callers are security definer functions.
create or replace function public.random_text_token(p_len int, p_alphabet text) returns text
language plpgsql volatile set search_path = '' as $$
declare
  n      int := char_length(p_alphabet);
  cutoff int := 256 - (256 % char_length(p_alphabet));
  out    text := '';
  bytes  bytea;
  b      int;
begin
  if p_len < 1 or p_len > 256 or n < 2 or n > 256 then
    raise exception 'random_text_token: bad arguments' using errcode = '22023';
  end if;
  while char_length(out) < p_len loop
    bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
    for i in 0..15 loop
      continue when i = 6 or i = 8; -- version / variant bits are not random
      b := get_byte(bytes, i);
      continue when b >= cutoff;
      out := out || substr(p_alphabet, (b % n) + 1, 1);
      exit when char_length(out) = p_len;
    end loop;
  end loop;
  return out;
end $$;
revoke execute on function public.random_text_token(int, text) from public, anon, authenticated;

-- ---------- forwarding address -----------------------------------------------------------------
-- Lowercase, no look-alike characters: people may type it, and some mail systems lowercase local parts.
alter table public.profiles add column inbox_token text unique check (inbox_token ~ '^[a-hjkmnp-z2-9]{10}$');
-- Not user-editable: profiles keeps its existing column grants (no update grant on inbox_token).

create or replace function public.my_forwarding_address() returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user   uuid := auth.uid();
  v_token  text;
  v_domain text;
begin
  if v_user is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  select inbox_token into v_token from public.profiles where id = v_user for update;
  if not found then raise exception 'no_profile' using errcode = 'P0002'; end if;
  while v_token is null loop
    begin
      update public.profiles
         set inbox_token = public.random_text_token(10, 'abcdefghjkmnpqrstuvwxyz23456789')
       where id = v_user
      returning inbox_token into v_token;
    exception when unique_violation then
      v_token := null; -- another user drew the same token: draw again
    end;
  end loop;
  v_domain := coalesce(nullif(trim((select value from public.app_config where key = 'inbound_email_domain')), ''),
                       'in.heygate.app');
  return 'trips+' || v_token || '@' || v_domain;
end $$;
revoke execute on function public.my_forwarding_address() from public, anon;
grant execute on function public.my_forwarding_address() to authenticated;

-- ---------- email_imports ----------------------------------------------------------------------
-- Metadata only: the sender's domain and a SHA-256 of the subject, never the body or the address.
create table public.email_imports (
  id            bigint generated always as identity primary key,
  user_id       uuid not null references auth.users (id) on delete cascade,
  received_at   timestamptz not null default now(),
  from_domain   text check (char_length(from_domain) <= 253),
  subject_hash  text check (subject_hash ~ '^[0-9a-f]{64}$'),
  status        text not null check (status in ('added', 'no_flights', 'not_pro', 'error')),
  flights_added integer not null default 0 check (flights_added >= 0),
  error         text check (char_length(error) <= 200)
);
create index email_imports_user_idx on public.email_imports (user_id, received_at desc);
alter table public.email_imports enable row level security;
revoke all on public.email_imports from public, anon, authenticated;
grant select on public.email_imports to authenticated;
grant select, insert, update, delete on public.email_imports to service_role;
create policy email_imports_select_own on public.email_imports for select to authenticated
  using (user_id = auth.uid());

-- ---------- service-role helpers for inbound-email ---------------------------------------------
-- The user behind an inbox token, and whether they are Pro (is_pro: the first-flight trial does not count).
create or replace function public.inbound_email_user(p_token text)
returns table (user_id uuid, is_pro boolean, alert_language text)
language sql stable security definer set search_path = '' as $$
  select p.id, public.is_pro(p.id), p.alert_language
    from public.profiles p
   where p.inbox_token = lower(p_token);
$$;
revoke execute on function public.inbound_email_user(text) from public, anon, authenticated;
grant execute on function public.inbound_email_user(text) to service_role;

-- Tracks a flight for a user as source 'email'. Returns 'added' (a new row, or an archived row brought
-- back), 'exists' (already tracked) or 'limit' (tracking limit reached). The PNR is kept only when it is
-- well formed, and never overwrites one the user already has.
create or replace function public.import_track_flight(p_user uuid, p_flight uuid, p_pnr text default null)
returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_pnr      text := case when upper(p_pnr) ~ '^[A-Z0-9]{5,8}$' then upper(p_pnr) end;
  v_archived timestamptz;
begin
  select archived_at into v_archived from public.user_flights where user_id = p_user and flight_id = p_flight;
  begin
    if found then
      if v_archived is not null then
        -- un-archiving re-runs the tracking-limit trigger (update of archived_at)
        update public.user_flights
           set archived_at = null, pnr = coalesce(pnr, v_pnr)
         where user_id = p_user and flight_id = p_flight;
        return 'added';
      end if;
      -- already active: only fill a missing PNR. archived_at is not in the SET list, so the limit trigger
      -- does not fire and a user over the limit (e.g. after a downgrade) still gets 'exists', not 'limit'.
      if v_pnr is not null then
        update public.user_flights set pnr = v_pnr
         where user_id = p_user and flight_id = p_flight and pnr is null;
      end if;
      return 'exists';
    end if;
    insert into public.user_flights (user_id, flight_id, source, pnr) values (p_user, p_flight, 'email', v_pnr);
  exception
    when unique_violation then return 'exists'; -- a parallel insert won
    when raise_exception then
      if sqlerrm = 'tracking_limit_reached' then return 'limit'; end if;
      raise;
  end;
  return 'added';
end $$;
revoke execute on function public.import_track_flight(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.import_track_flight(uuid, uuid, text) to service_role;

-- ---------- user_flights.source: 'email' ---------------------------------------------------------
-- The latest list is from 20261126000010_share_checkin.sql; it is re-created with every value.
alter table public.user_flights
  drop constraint user_flights_source_check,
  add constraint user_flights_source_check
    check (source in ('manual', 'pdf', 'share', 'screenshot', 'gmail', 'scan', 'email'));

-- ---------- push_log.kind: 'import' ("Added 2 flights from your email") -------------------------
-- The latest list is from 20261124000006_live_activities.sql.
alter table public.push_log drop constraint push_log_kind_check;
alter table public.push_log
  add constraint push_log_kind_check check (kind in ('alert', 'sync', 'test', 'live_activity', 'import'));
