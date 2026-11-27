-- P4 section 3 (docs/P4_CONTRACTS.md): Gift Pro.
--   * gift_codes: one code per store transaction (heygate_gift_year 365 days, heygate_gift_trip 7 days),
--     "XXXX-XXXX-XXXX" from the invite alphabet. Service role only; clients use the RPCs.
--   * gift_attempts: failed redemptions, max 10 per user per hour (like friend invites).
--   * create_gift_code / void_gift_code / redeemed_gifts: service role (revenuecat-webhook, billing).
--     Billing keeps redeemed gift time when store Pro ends, but re-lays each gift after the store Pro that
--     really ran (billing.ts giftEnd): a gift stacked on a subscription that is later refunded gives its
--     days from the refund, never the refunded subscription's remaining time.
--   * my_gifts() / redeem_gift(code): authenticated. redeem_gift RETURNS {error} instead of raising, so the
--     failed attempt it records is not rolled back. Error names follow the contract: invalid_code (P0002),
--     already_redeemed (23505), voided (22023), own_gift (22023), plus rate_limited and lifetime_pro
--     (the caller already has Pro without an end; the code is not used up).

create table public.gift_codes (
  code                 text primary key
                       check (code ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}$'),
  -- kept null when the buyer deletes their account: a gift already sent must still work
  purchaser_id         uuid references auth.users (id) on delete set null,
  product_id           text not null check (product_id in ('heygate_gift_year', 'heygate_gift_trip')),
  days                 int not null check (days between 1 and 366),
  store_transaction_id text not null unique check (char_length(store_transaction_id) between 1 and 200),
  created_at           timestamptz not null default now(),
  redeemed_by          uuid references auth.users (id) on delete set null,
  redeemed_at          timestamptz,
  -- the gift's own window at redemption: starts_at = greatest(now, the Pro end it stacked on),
  -- grants_until = starts_at + days (= profiles.pro_until right after the redemption)
  starts_at            timestamptz,
  grants_until         timestamptz,
  voided_at            timestamptz,
  constraint gift_codes_redeemed check ((redeemed_at is null) = (grants_until is null)
                                       and (redeemed_at is null) = (starts_at is null)),
  constraint gift_codes_not_both check (redeemed_at is null or voided_at is null)
);
create index gift_codes_purchaser_idx on public.gift_codes (purchaser_id, created_at desc);
create index gift_codes_redeemed_by_idx on public.gift_codes (redeemed_by) where redeemed_by is not null;
alter table public.gift_codes enable row level security;
revoke all on public.gift_codes from public, anon, authenticated;
grant select, insert, update, delete on public.gift_codes to service_role;

create table public.gift_attempts (
  id      bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  at      timestamptz not null default now()
);
create index gift_attempts_user_idx on public.gift_attempts (user_id, at desc);
alter table public.gift_attempts enable row level security;
revoke all on public.gift_attempts from public, anon, authenticated;
grant select, insert, update, delete on public.gift_attempts to service_role;

-- ---------- service-role helpers -----------------------------------------------------------------
-- The code for a store transaction: the existing one (idempotent on store_transaction_id) or a new one.
-- Returns {code, created} or null when the purchaser has no profile.
create or replace function public.create_gift_code(p_purchaser uuid, p_product text, p_days int, p_txn text)
returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_alpha constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  v_code  text;
  v_raw   text;
begin
  select g.code into v_code from public.gift_codes g where g.store_transaction_id = p_txn;
  if found then return jsonb_build_object('code', v_code, 'created', false); end if;
  if not exists (select 1 from public.profiles p where p.id = p_purchaser) then return null; end if;
  for i in 1..10 loop
    v_raw := public.random_text_token(12, v_alpha);
    v_code := substr(v_raw, 1, 4) || '-' || substr(v_raw, 5, 4) || '-' || substr(v_raw, 9, 4);
    begin
      insert into public.gift_codes (code, purchaser_id, product_id, days, store_transaction_id)
      values (v_code, p_purchaser, p_product, p_days, p_txn);
      return jsonb_build_object('code', v_code, 'created', true);
    exception when unique_violation then
      -- a parallel delivery of the same transaction won, or (31^12 odds) the code exists
      select g.code into v_code from public.gift_codes g where g.store_transaction_id = p_txn;
      if found then return jsonb_build_object('code', v_code, 'created', false); end if;
    end;
  end loop;
  raise exception 'gift code generation failed' using errcode = 'P0001';
end $$;

-- Refund: 'voided' | 'already_voided' | 'redeemed' (kept, the caller logs it) | 'not_found'.
create or replace function public.void_gift_code(p_txn text) returns text
language plpgsql volatile security definer set search_path = '' as $$
declare v public.gift_codes;
begin
  select * into v from public.gift_codes g where g.store_transaction_id = p_txn for update;
  if not found then return 'not_found'; end if;
  if v.redeemed_at is not null then return 'redeemed'; end if;
  if v.voided_at is not null then return 'already_voided'; end if;
  update public.gift_codes set voided_at = now() where code = v.code;
  return 'voided';
end $$;

-- The gifts this user redeemed, oldest redemption first: [{redeemed_at, starts_at, days}]. Billing derives
-- the Pro time they still give from these (billing.ts giftEnd), relative to the store Pro that really ran.
create or replace function public.redeemed_gifts(p_user uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object('redeemed_at', g.redeemed_at, 'starts_at', g.starts_at,
                                               'days', g.days)
                            order by g.redeemed_at, g.code), '[]'::jsonb)
    from public.gift_codes g
   where g.redeemed_by = p_user and g.redeemed_at is not null;
$$;

-- ---------- RPCs (authenticated) -----------------------------------------------------------------
create or replace function public.my_gifts() returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'code', g.code,
           'product_id', g.product_id,
           'days', g.days,
           'status', case when g.voided_at is not null then 'voided'
                          when g.redeemed_at is not null then 'redeemed'
                          else 'unused' end,
           'created_at', g.created_at,
           'redeemed_at', g.redeemed_at)
         order by g.created_at desc, g.code), '[]'::jsonb)
    from public.gift_codes g
   where g.purchaser_id = auth.uid();
$$;

create or replace function public.redeem_gift(p_code text) returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_me    uuid := auth.uid();
  v_raw   text := upper(regexp_replace(coalesce(p_code, ''), '[\s-]', '', 'g'));
  v_code  text;
  v_gift  public.gift_codes;
  v_prof  public.profiles;
  v_err   text;
  v_start timestamptz;
  v_until timestamptz;
  v_max   constant int := 10;
begin
  if v_me is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  -- one redemption at a time per user, so parallel guesses can't race past the attempt limit
  perform pg_advisory_xact_lock(hashtextextended('gift_redeem:' || v_me::text, 0));
  if (select count(*) from public.gift_attempts a
       where a.user_id = v_me and a.at > now() - interval '1 hour') >= v_max then
    return jsonb_build_object('error', 'rate_limited');
  end if;

  if v_raw ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{12}$' then
    v_code := substr(v_raw, 1, 4) || '-' || substr(v_raw, 5, 4) || '-' || substr(v_raw, 9, 4);
    select * into v_gift from public.gift_codes g where g.code = v_code for update;
  end if;

  if v_code is null or v_gift.code is null then v_err := 'invalid_code';
  elsif v_gift.voided_at is not null then v_err := 'voided';
  elsif v_gift.redeemed_at is not null then v_err := 'already_redeemed';
  elsif v_gift.purchaser_id = v_me then v_err := 'own_gift';
  end if;
  if v_err is not null then
    insert into public.gift_attempts (user_id) values (v_me);
    return jsonb_build_object('error', v_err);
  end if;

  select * into v_prof from public.profiles p where p.id = v_me for update;
  if not found then return jsonb_build_object('error', 'invalid_code'); end if;
  -- Pro without an end (lifetime / manual grant): nothing to add, keep the code for someone else
  if v_prof.plan = 'pro' and v_prof.pro_until is null then
    return jsonb_build_object('error', 'lifetime_pro');
  end if;

  v_start := greatest(now(), case when v_prof.plan = 'pro' then coalesce(v_prof.pro_until, now()) else now() end);
  v_until := v_start + make_interval(days => v_gift.days);
  update public.profiles set plan = 'pro', pro_until = v_until where id = v_me;
  update public.gift_codes set redeemed_by = v_me, redeemed_at = now(), starts_at = v_start,
                               grants_until = v_until
   where code = v_gift.code;
  return jsonb_build_object('pro_until', v_until, 'days', v_gift.days);
end $$;

-- ---------- execute rights -----------------------------------------------------------------------
revoke execute on function public.create_gift_code(uuid, text, int, text) from public, anon, authenticated;
grant execute on function public.create_gift_code(uuid, text, int, text) to service_role;
revoke execute on function public.void_gift_code(text) from public, anon, authenticated;
grant execute on function public.void_gift_code(text) to service_role;
revoke execute on function public.redeemed_gifts(uuid) from public, anon, authenticated;
grant execute on function public.redeemed_gifts(uuid) to service_role;
revoke execute on function public.my_gifts() from public, anon;
grant execute on function public.my_gifts() to authenticated;
revoke execute on function public.redeem_gift(text) from public, anon;
grant execute on function public.redeem_gift(text) to authenticated;
