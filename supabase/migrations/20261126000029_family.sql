-- P3 B13 (docs/P3_CONTRACTS.md): family sharing (Pro).
--   * families (one per owner) and family_members (a user is in at most one family; at most 5 members
--     besides the owner), family_invites (8-character single-use codes, 7 days, like friend invites).
--   * is_pro(user) = own Pro (is_pro_own: the old definition) or member of a family whose owner has own
--     Pro. A member's Pro is never passed on: only is_pro_own counts for owners, and a member can't own a
--     family (create_family_invite needs own Pro on a yearly or lifetime plan).
--     Every caller of is_pro (current_user_is_pro, is_pro_for_flight, flight_view, get_flight_recipients,
--     tracker counts, the tracking cap, the trial, Airport Health, Passport, email import) follows.
--   * Pro tracker counts are recounted when someone joins or leaves a family and when an owner's plan
--     changes (profile_plan_changed now also covers the owner's members).
--   * RPCs (authenticated): create_family_invite, accept_family_invite, leave_family,
--     remove_family_member, my_family. Internal: family_pro_source (sync-entitlement).
--
-- Errors: not_authenticated 28000; family_requires_yearly 42501 (owner not on a yearly / lifetime Pro);
-- already_in_family 23505; invalid_code P0002; own_code 22023; family_full P0001 (5 members);
-- family_inactive P0001 (the owner's Pro has ended); not_member P0002; not_owner 42501.
-- Clients never write these tables directly.

-- =================================================================================================
-- Tables
-- =================================================================================================
create table public.families (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null unique references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table public.family_members (
  family_id uuid not null references public.families (id) on delete cascade,
  user_id   uuid not null unique references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (family_id, user_id)
);

create table public.family_invites (
  code       text primary key check (code ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$'),
  family_id  uuid not null references public.families (id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '7 days',
  used_by    uuid references auth.users (id) on delete set null,
  used_at    timestamptz,
  constraint family_invites_used_at check (used_by is null or used_at is not null)
);
create index family_invites_family_idx on public.family_invites (family_id, created_at desc);

alter table public.families       enable row level security;
alter table public.family_members enable row level security;
alter table public.family_invites enable row level security;
revoke all on public.families, public.family_members, public.family_invites from public, anon, authenticated;
grant select on public.families, public.family_members, public.family_invites to authenticated;
grant select, insert, update, delete on public.families, public.family_members, public.family_invites to service_role;

-- =================================================================================================
-- Entitlement
-- =================================================================================================
-- The pre-P3 is_pro: the user's own plan (subscription, trip pass, lifetime / manual grant).
create or replace function public.is_pro_own(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.profiles p
    where p.id = p_user and p.plan = 'pro' and (p.pro_until is null or p.pro_until > now())
  );
$$;

create or replace function public.is_pro(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.is_pro_own(p_user)
      or exists (select 1 from public.family_members m
                   join public.families f on f.id = m.family_id
                  where m.user_id = p_user and public.is_pro_own(f.owner_id));
$$;

-- May own a family: own Pro on a yearly or lifetime plan. Lifetime / manual grants have pro_until null.
-- Yearly: the latest applied subscription event is the yearly product, or (profiles synced from
-- RevenueCat without a webhook event) more than 40 days of Pro remain, which only a yearly plan gives
-- (monthly ends within ~31 days plus grace, a trip pass after 7).
create or replace function public.family_owner_eligible(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.is_pro_own(p_user) and exists (
    select 1 from public.profiles p
     where p.id = p_user
       and (p.pro_until is null
            or p.pro_until > now() + interval '40 days'
            or (select b.product_id from public.billing_events b
                 where b.app_user_id = p_user::text and b.processed_at is not null
                   and b.product_id in ('heygate_pro_yearly', 'heygate_pro_monthly')
                   and b.expires_at is not null
                 order by b.expires_at desc limit 1) = 'heygate_pro_yearly'));
$$;

-- The family the user owns or belongs to, or null.
create or replace function public.family_of(p_user uuid) returns uuid
language sql stable security definer set search_path = '' as $$
  select coalesce((select f.id from public.families f where f.owner_id = p_user),
                  (select m.family_id from public.family_members m where m.user_id = p_user));
$$;

-- The caller's family (RLS helper; answers only for the caller).
create or replace function public.my_family_id() returns uuid
language sql stable security definer set search_path = '' as $$
  select public.family_of(auth.uid());
$$;

-- {owner_id, owner_name, pro_until} when the user is Pro only through their family, else null.
-- Used by sync-entitlement so the apps can show "Pro via {owner name}'s family".
create or replace function public.family_pro_source(p_user uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select case when public.is_pro_own(p_user) then null else (
    select jsonb_build_object('owner_id', f.owner_id, 'owner_name', public.friend_display_name(f.owner_id),
                              'pro_until', p.pro_until)
      from public.family_members m
      join public.families f on f.id = m.family_id
      join public.profiles p on p.id = f.owner_id
     where m.user_id = p_user and public.is_pro_own(f.owner_id)) end;
$$;

-- =================================================================================================
-- Pro tracker counts follow family membership and the owner's plan
-- =================================================================================================
create or replace function public.recount_user_flights(p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  for r in select flight_id from public.user_flights where user_id = p_user and archived_at is null loop
    perform public.refresh_flight_trackers(r.flight_id);
  end loop;
end $$;

-- Same as 20261122000001_init.sql, + the members of a family this user owns.
create or replace function public.profile_plan_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
declare m record;
begin
  perform public.recount_user_flights(new.id);
  for m in select fm.user_id from public.family_members fm
             join public.families f on f.id = fm.family_id
            where f.owner_id = new.id loop
    perform public.recount_user_flights(m.user_id);
  end loop;
  return null;
end $$;

create or replace function public.family_members_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    perform public.recount_user_flights(new.user_id);
  else
    perform public.recount_user_flights(old.user_id);
  end if;
  return null;
end $$;
create trigger family_members_counts after insert or delete on public.family_members
  for each row execute function public.family_members_changed();

-- =================================================================================================
-- RLS: owner and members see their own family
-- =================================================================================================
create policy families_select_own on public.families for select to authenticated
  using (id = public.my_family_id());
create policy family_members_select_own on public.family_members for select to authenticated
  using (family_id = public.my_family_id());
create policy family_invites_select_owner on public.family_invites for select to authenticated
  using (exists (select 1 from public.families f where f.id = family_invites.family_id and f.owner_id = auth.uid()));

-- =================================================================================================
-- RPCs
-- =================================================================================================
-- Returns the owner's unexpired, unused code, or a new one. Creates the family on first use.
create or replace function public.create_family_invite() returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_me     uuid := auth.uid();
  v_family uuid;
  v_code   text;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('family:' || v_me::text, 0));
  if exists (select 1 from public.family_members m where m.user_id = v_me) then
    raise exception 'already_in_family' using errcode = '23505';
  end if;
  if not public.family_owner_eligible(v_me) then
    raise exception 'family_requires_yearly' using errcode = '42501';
  end if;

  select f.id into v_family from public.families f where f.owner_id = v_me;
  if v_family is null then
    insert into public.families (owner_id) values (v_me) returning id into v_family;
  end if;
  if (select count(*) from public.family_members m where m.family_id = v_family) >= 5 then
    raise exception 'family_full' using errcode = 'P0001';
  end if;

  select i.code into v_code from public.family_invites i
   where i.family_id = v_family and i.used_by is null and i.used_at is null and i.expires_at > now()
   order by i.created_at desc limit 1;
  if v_code is not null then return v_code; end if;

  delete from public.family_invites where family_id = v_family and expires_at < now() - interval '30 days';
  loop
    v_code := public.new_friend_code();
    -- one alphabet and length for both kinds of invite: never hand out a code a friend invite holds
    continue when exists (select 1 from public.friend_invites fi where fi.code = v_code);
    insert into public.family_invites (code, family_id) values (v_code, v_family) on conflict (code) do nothing;
    exit when found;
  end loop;
  return v_code;
end $$;

-- Joins the family of the code's owner. An owner of a family without members gives that empty family up;
-- an owner with members must remove them first (already_in_family).
create or replace function public.accept_family_invite(p_code text) returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_me    uuid := auth.uid();
  v_code  text := upper(regexp_replace(coalesce(p_code, ''), '[\s-]', '', 'g'));
  v_inv   public.family_invites;
  v_owner uuid;
  v_mine  uuid;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if v_code !~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$' then
    raise exception 'invalid_code' using errcode = 'P0002';
  end if;
  select * into v_inv from public.family_invites i where i.code = v_code for update;
  if not found or v_inv.used_at is not null or v_inv.used_by is not null or v_inv.expires_at <= now() then
    raise exception 'invalid_code' using errcode = 'P0002';
  end if;
  -- the family row lock serialises joins, so two parallel accepts can't both take the 5th seat
  select f.owner_id into v_owner from public.families f where f.id = v_inv.family_id for update;
  if v_owner = v_me then raise exception 'own_code' using errcode = '22023'; end if;
  perform pg_advisory_xact_lock(hashtextextended('family:' || v_me::text, 0));

  if exists (select 1 from public.family_members m where m.user_id = v_me) then
    raise exception 'already_in_family' using errcode = '23505';
  end if;
  select f.id into v_mine from public.families f where f.owner_id = v_me;
  if v_mine is not null then
    if exists (select 1 from public.family_members m where m.family_id = v_mine) then
      raise exception 'already_in_family' using errcode = '23505';
    end if;
    delete from public.families where id = v_mine;
  end if;
  if not public.is_pro_own(v_owner) then raise exception 'family_inactive' using errcode = 'P0001'; end if;
  if (select count(*) from public.family_members m where m.family_id = v_inv.family_id) >= 5 then
    raise exception 'family_full' using errcode = 'P0001';
  end if;

  insert into public.family_members (family_id, user_id) values (v_inv.family_id, v_me);
  update public.family_invites set used_by = v_me, used_at = now() where code = v_code;
  return jsonb_build_object('family_id', v_inv.family_id, 'owner_id', v_owner,
                            'owner_name', public.friend_display_name(v_owner));
end $$;

-- A member leaves; the owner leaving ends the family for everyone. Not in a family → no-op.
create or replace function public.leave_family() returns void
language plpgsql volatile security definer set search_path = '' as $$
declare v_me uuid := auth.uid();
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  delete from public.family_members where user_id = v_me;
  delete from public.families where owner_id = v_me;
end $$;

-- The owner removes a member. Caller owns no family → not_owner; user not in it → not_member.
create or replace function public.remove_family_member(p_user uuid) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_me     uuid := auth.uid();
  v_family uuid;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  select f.id into v_family from public.families f where f.owner_id = v_me;
  if v_family is null then raise exception 'not_owner' using errcode = '42501'; end if;
  delete from public.family_members where family_id = v_family and user_id = p_user;
  if not found then raise exception 'not_member' using errcode = 'P0002'; end if;
end $$;

-- null when the caller is in no family, else
-- { family_id, role: "owner"|"member", owner: {user_id, display_name}, owner_pro, pro_via_family,
--   members: [{user_id, display_name, joined_at}] (oldest first), max_members: 5, can_invite,
--   invite: {code, expires_at} | null (owner only) }
-- pro_via_family: the caller is Pro through this family (a member without own Pro whose owner is Pro).
create or replace function public.my_family() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_me     uuid := auth.uid();
  v_family uuid;
  v_owner  uuid;
  v_n      int;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  v_family := public.family_of(v_me);
  if v_family is null then return null; end if;
  select f.owner_id into v_owner from public.families f where f.id = v_family;
  select count(*) into v_n from public.family_members m where m.family_id = v_family;
  return jsonb_build_object(
    'family_id', v_family,
    'role', case when v_owner = v_me then 'owner' else 'member' end,
    'owner', jsonb_build_object('user_id', v_owner, 'display_name', public.friend_display_name(v_owner)),
    'owner_pro', public.is_pro_own(v_owner),
    'pro_via_family', v_owner <> v_me and not public.is_pro_own(v_me) and public.is_pro_own(v_owner),
    'members', (select coalesce(jsonb_agg(jsonb_build_object(
                         'user_id', m.user_id, 'display_name', public.friend_display_name(m.user_id),
                         'joined_at', m.joined_at) order by m.joined_at, m.user_id), '[]'::jsonb)
                  from public.family_members m where m.family_id = v_family),
    'max_members', 5,
    'can_invite', v_owner = v_me and v_n < 5 and public.family_owner_eligible(v_me),
    'invite', case when v_owner = v_me then (
                select jsonb_build_object('code', i.code, 'expires_at', i.expires_at)
                  from public.family_invites i
                 where i.family_id = v_family and i.used_by is null and i.used_at is null and i.expires_at > now()
                 order by i.created_at desc limit 1) end);
end $$;

-- =================================================================================================
-- Execute rights
-- =================================================================================================
revoke execute on function public.is_pro_own(uuid) from public, anon, authenticated;
revoke execute on function public.is_pro(uuid) from public, anon, authenticated;
revoke execute on function public.family_owner_eligible(uuid) from public, anon, authenticated;
revoke execute on function public.family_of(uuid) from public, anon, authenticated;
revoke execute on function public.family_pro_source(uuid) from public, anon, authenticated;
revoke execute on function public.recount_user_flights(uuid) from public, anon, authenticated;
revoke execute on function public.profile_plan_changed() from public, anon, authenticated;
revoke execute on function public.family_members_changed() from public, anon, authenticated;
revoke execute on function public.my_family_id() from public, anon;
grant execute on function public.my_family_id() to authenticated;
grant execute on function public.is_pro_own(uuid) to service_role;
grant execute on function public.is_pro(uuid) to service_role;
grant execute on function public.family_owner_eligible(uuid) to service_role;
grant execute on function public.family_pro_source(uuid) to service_role;

revoke execute on function public.create_family_invite() from public, anon;
revoke execute on function public.accept_family_invite(text) from public, anon;
revoke execute on function public.leave_family() from public, anon;
revoke execute on function public.remove_family_member(uuid) from public, anon;
revoke execute on function public.my_family() from public, anon;
grant execute on function public.create_family_invite() to authenticated;
grant execute on function public.accept_family_invite(text) to authenticated;
grant execute on function public.leave_family() to authenticated;
grant execute on function public.remove_family_member(uuid) to authenticated;
grant execute on function public.my_family() to authenticated;
