-- Flights added from a Gmail suggestion are tracked with source 'gmail'.
-- The init check is unnamed, so this uses Postgres's generated name.

alter table public.user_flights
  drop constraint user_flights_source_check,
  add constraint user_flights_source_check check (source in ('manual','pdf','share','screenshot','gmail'));
