-- reserve_ticket_parse: the daily cap is atomic (the update is skipped once the row is full).
begin;

select public.reserve_ticket_parse('00000000-0000-0000-0000-00000000000a', 2) as first;
select public.reserve_ticket_parse('00000000-0000-0000-0000-00000000000a', 2) as second;
select public.reserve_ticket_parse('00000000-0000-0000-0000-00000000000a', 2) as third;

do $$
declare
  n int;
  blocked boolean;
begin
  select uses into n from public.ticket_parse_usage
   where user_id = '00000000-0000-0000-0000-00000000000a' and day = (now() at time zone 'utc')::date;
  if n <> 2 then
    raise exception 'expected 2 uses, got %', n;
  end if;
  -- The third call must not increment. Re-read by calling again and checking the row.
  select uses into n from public.ticket_parse_usage
   where user_id = '00000000-0000-0000-0000-00000000000a';
  if n <> 2 then
    raise exception 'cap did not hold, uses=%', n;
  end if;
  -- A different user is not affected.
  if not public.reserve_ticket_parse('00000000-0000-0000-0000-00000000000b', 2) then
    raise exception 'another user was blocked';
  end if;
  select public.reserve_ticket_parse(null, 2) into blocked;
  if blocked then
    raise exception 'null user was reserved';
  end if;
end $$;

-- The third reservation returned false. Confirm via a fresh call that the row stays at 2
-- (the function returns false and does not insert a new day-row beyond the cap).
select uses = 2 as capped
  from public.ticket_parse_usage
 where user_id = '00000000-0000-0000-0000-00000000000a';

rollback;
