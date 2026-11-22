-- Daily quota for the parse-ticket Edge Function. Service role only: the function
-- reserves a use before it calls the model, so a burst of requests cannot slip past the cap.
create table public.ticket_parse_usage (
  user_id uuid not null,
  day date not null,
  uses integer not null default 0 check (uses >= 0),
  primary key (user_id, day)
);

alter table public.ticket_parse_usage enable row level security;
revoke all on public.ticket_parse_usage from public, anon, authenticated;

create or replace function public.reserve_ticket_parse(p_user uuid, p_limit int)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v int;
begin
  if p_user is null or p_limit is null or p_limit < 1 then
    return false;
  end if;
  insert into public.ticket_parse_usage (user_id, day, uses)
  values (p_user, (now() at time zone 'utc')::date, 1)
  on conflict (user_id, day) do update
    set uses = ticket_parse_usage.uses + 1
    where ticket_parse_usage.uses < p_limit
  returning uses into v;
  return v is not null;
end;
$$;

revoke all on function public.reserve_ticket_parse(uuid, int) from public, anon, authenticated;
grant execute on function public.reserve_ticket_parse(uuid, int) to service_role;
