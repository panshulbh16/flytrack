-- Flights added from a Gmail suggestion are tracked with source 'gmail'; unknown sources still fail.
\set QUIET on
set client_min_messages = warning;

insert into auth.users (id) values ('00000000-0000-0000-0000-0000000000f1')
on conflict (id) do nothing;
insert into public.flights (id, carrier_iata, flight_number, flight_date, dep_iata, arr_iata, status)
values ('44444444-4444-4444-4444-444444444444', '6E', '2341', current_date + 1, 'DEL', 'BOM', 'scheduled'),
       ('44444444-4444-4444-4444-444444444445', '6E', '2342', current_date + 1, 'BOM', 'DEL', 'scheduled');

begin;
set local role authenticated;
set local request.jwt.claims = '{"sub":"00000000-0000-0000-0000-0000000000f1"}';
insert into public.user_flights (flight_id, source) values ('44444444-4444-4444-4444-444444444444', 'gmail');
do $$ begin
  assert (select source from public.user_flights where flight_id = '44444444-4444-4444-4444-444444444444') = 'gmail',
    'gmail source stored';
  begin
    insert into public.user_flights (flight_id, source) values ('44444444-4444-4444-4444-444444444445', 'bogus');
    raise exception 'FAIL: unknown source accepted';
  exception when check_violation then null; end;
end $$;
commit;
