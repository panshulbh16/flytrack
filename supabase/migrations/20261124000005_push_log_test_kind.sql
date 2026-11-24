-- send-test-push logs its sends as kind 'test', so delivery checks never count as real alerts.

alter table public.push_log drop constraint push_log_kind_check;
alter table public.push_log
  add constraint push_log_kind_check check (kind in ('alert', 'sync', 'test'));
