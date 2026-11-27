-- P4 section 2 (docs/P4_CONTRACTS.md): TripIt login CSRF fix and the manual-sync cooldown.
--
-- TripIt's OAuth 1.0 flow sends no oauth_verifier, so the request token alone cannot prove that the person who
-- approved in TripIt is the HeyGate user who started the connect. Before this migration tripit-callback exchanged
-- the token for whoever created it: a stranger could send their authorize link to a victim and receive the
-- victim's trips. Now:
--   * tripit-callback (public) never exchanges. It stores sha256(one-time code) + a 10-minute expiry on the
--     pending row and redirects to heygate://tripit/callback?code=<code>. A second callback for the same request
--     token destroys the pending link (only one browser can ever hold a code for it).
--   * tripit-complete (user JWT) claims the code with tripit_claim_callback(): the pending request token and the
--     code are cleared in the same statement (single use), whoever the caller is. The function exchanges only when
--     the row's user_id is the caller; otherwise it answers 403 not_your_link and the link is already gone.
--   * last_manual_sync_at: "Sync now" is limited to once per 60 s per user (tripit_claim_manual_sync()); the hourly
--     cron does not touch it.

alter table public.tripit_connections
  add column callback_code_hash       text check (callback_code_hash ~ '^[0-9a-f]{64}$'),
  add column callback_code_expires_at timestamptz,
  add column last_manual_sync_at      timestamptz,
  add constraint tripit_callback_code_pair
    check ((callback_code_hash is null) = (callback_code_expires_at is null));

create unique index tripit_connections_callback_code_hash_key
  on public.tripit_connections (callback_code_hash) where callback_code_hash is not null;

-- Claims a callback code (sha256 hex). Whatever the outcome for a matching row (fresh or expired), the pending
-- request token and the code are cleared, so a code works at most once and a leaked or late code also kills the
-- pending link. Returns the pending request (user_id, request_token, request_secret, requested_at) only for a
-- code that has not expired; no row otherwise. The caller compares user_id with the signed-in user.
create or replace function public.tripit_claim_callback(p_code_hash text)
returns table (user_id uuid, request_token text, request_secret text, requested_at timestamptz)
language plpgsql volatile security definer set search_path = '' as $$
declare
  r public.tripit_connections%rowtype;
begin
  if p_code_hash is null or p_code_hash !~ '^[0-9a-f]{64}$' then return; end if;
  select c.* into r from public.tripit_connections c where c.callback_code_hash = p_code_hash for update;
  if not found then return; end if;
  update public.tripit_connections c
     set request_token = null, request_secret = null, requested_at = null,
         callback_code_hash = null, callback_code_expires_at = null
   where c.user_id = r.user_id;
  if r.callback_code_expires_at <= now() or r.request_token is null or r.request_secret is null then return; end if;
  user_id := r.user_id;
  request_token := r.request_token;
  request_secret := r.request_secret;
  requested_at := r.requested_at;
  return next;
end $$;

-- "Sync now" cooldown. 0 = go (last_manual_sync_at is set to now()); otherwise the seconds left (>= 1).
-- A user without a connected row gets 0, so tripit-sync answers not_connected as before.
create or replace function public.tripit_claim_manual_sync(p_user uuid, p_cooldown_s int default 60) returns int
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_last timestamptz;
  v_cooldown interval := make_interval(secs => greatest(coalesce(p_cooldown_s, 60), 1));
begin
  update public.tripit_connections c set last_manual_sync_at = now()
   where c.user_id = p_user and c.access_token is not null
     and (c.last_manual_sync_at is null or c.last_manual_sync_at <= now() - v_cooldown);
  if found then return 0; end if;
  select c.last_manual_sync_at into v_last
    from public.tripit_connections c where c.user_id = p_user and c.access_token is not null;
  if v_last is null then return 0; end if;
  return greatest(1, ceil(extract(epoch from (v_last + v_cooldown - now())))::int);
end $$;

revoke execute on function public.tripit_claim_callback(text) from public, anon, authenticated;
grant execute on function public.tripit_claim_callback(text) to service_role;
revoke execute on function public.tripit_claim_manual_sync(uuid, int) from public, anon, authenticated;
grant execute on function public.tripit_claim_manual_sync(uuid, int) to service_role;
