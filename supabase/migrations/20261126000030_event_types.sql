-- P3 event types (docs/P3_CONTRACTS.md section C). The only place the five new types are added to the
-- flight_events check, in the order of EVENT_TYPES in supabase/functions/_shared/types.ts:
--   GATE_CLOSED, TAKEOFF, AT_GATE (free, info) · AIRCRAFT_SWAP, CONNECTION_AT_RISK (Pro, critical)
alter table public.flight_events
  drop constraint flight_events_type_check,
  add constraint flight_events_type_check check (type in (
    'DELAY','DELAY_REDUCED','SCHEDULE_CHANGE','GATE_ASSIGNED','GATE_CHANGE','TERMINAL_CHANGE','BOARDING','DEPARTED',
    'LANDED','BELT_ASSIGNED','BELT_CHANGE','CANCELLED','DIVERTED','REINSTATED','INBOUND_LATE',
    'CHECKIN_OPEN','CHECKIN_CLOSING',
    'FOG_ALERT','DIVERTED_DEST',
    'GATE_CLOSED','TAKEOFF','AT_GATE','AIRCRAFT_SWAP','CONNECTION_AT_RISK'));
