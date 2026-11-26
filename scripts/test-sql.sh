#!/usr/bin/env bash
# Applies shim + migrations + seed to a throwaway local Postgres DB, then runs SQL tests.
# Usage: scripts/test-sql.sh   (needs a local postgres; PSQL env var to override the psql command)
set -euo pipefail
cd "$(dirname "$0")/.."
PSQL=${PSQL:-"psql"}
DB=${DB:-flytrack_test}
$PSQL -q -d postgres -c "drop database if exists $DB" -c "create database $DB" >/dev/null
for f in supabase/tests/sql/00_supabase_shim.sql supabase/migrations/20261122000001_init.sql supabase/migrations/20261122000003_ticket_parse.sql supabase/migrations/20261122000004_ios_devices.sql supabase/migrations/20261124000005_push_log_test_kind.sql supabase/migrations/20261124000006_live_activities.sql supabase/migrations/20261124000007_gmail_source.sql supabase/migrations/20261126000008_billing.sql supabase/migrations/20261126000009_security.sql supabase/migrations/20261126000010_share_checkin.sql supabase/migrations/20261126000011_history.sql supabase/migrations/20261126000012_poll_precheck.sql supabase/migrations/20261126000013_airport_weather.sql supabase/migrations/20261126000014_passport.sql supabase/migrations/20261126000015_alert_prefs.sql supabase/migrations/20261126000016_push_receipts.sql supabase/migrations/20261126000017_friends.sql supabase/migrations/20261126000018_email_import.sql supabase/migrations/20261126000019_calendar.sql supabase/migrations/20261126000020_forecast.sql supabase/migrations/20261126000021_limits_sources.sql supabase/migrations/20261126000022_timeline.sql supabase/migrations/20261126000023_aircraft.sql supabase/migrations/20261126000024_predictions.sql supabase/migrations/20261126000025_connections.sql supabase/migrations/20261126000026_route_search.sql supabase/migrations/20261126000027_trends_public.sql supabase/migrations/20261126000028_social.sql supabase/migrations/20261126000029_family.sql supabase/migrations/20261126000030_event_types.sql supabase/seed.sql; do
  $PSQL -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f" >/dev/null
done
for t in supabase/tests/sql/[1-9]*.sql; do
  echo "== $t"
  $PSQL -q -v ON_ERROR_STOP=1 -d "$DB" -f "$t"
done
echo "ALL SQL TESTS PASSED"
