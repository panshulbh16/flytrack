#!/usr/bin/env bash
# Applies shim + migrations + seed to a throwaway local Postgres DB, then runs SQL tests.
# Usage: scripts/test-sql.sh   (needs a local postgres; PSQL env var to override the psql command)
set -euo pipefail
cd "$(dirname "$0")/.."
PSQL=${PSQL:-"psql"}
DB=${DB:-flytrack_test}
$PSQL -q -d postgres -c "drop database if exists $DB" -c "create database $DB" >/dev/null
for f in supabase/tests/sql/00_supabase_shim.sql supabase/migrations/20261122000001_init.sql supabase/migrations/20261122000003_ticket_parse.sql supabase/migrations/20261122000004_ios_devices.sql supabase/migrations/20261124000006_live_activities.sql supabase/seed.sql; do
  $PSQL -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f" >/dev/null
done
for t in supabase/tests/sql/[1-9]*.sql; do
  echo "== $t"
  $PSQL -q -v ON_ERROR_STOP=1 -d "$DB" -f "$t"
done
echo "ALL SQL TESTS PASSED"
