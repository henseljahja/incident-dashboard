#!/usr/bin/env bash
# Seeds the demo stack's MIRROR tables from the fixture CSVs, reusing the same loader tier 2 and
# tier 3 use (see DemoMirrorSeederTest). The service's own ETL then builds INCIDENT_FACT.
#
#   ./deploy/seed-demo.sh schema    # step 1: mirror DDL, BEFORE the service starts
#   ./deploy/seed-demo.sh data      # step 3: fixture rows, AFTER Flyway has run
#
# ORDERING IS LOAD-BEARING, and it is a three-step sequence with the service in the middle:
#
#   1. schema   mirror DDL. V3__mart.sql declares V_INCIDENT over these tables, so Flyway dies
#               with ORA-00942 if they are absent.
#   2. (service starts, its Flyway runs)
#   3. data     fixture rows. ref_custom_field_enum and ref_user target REF_ tables that
#               V1__config_tables.sql creates, so loading before Flyway dies with ORA-00942 too.
#
# AbstractOracleIT sequences tier 2 identically. Prefer ./start-demo.sh, which runs all three in
# order for you; call this script directly only to re-seed a stack that is already up.
#
# Re-running is safe: both phases are idempotent.
#
# The fixture CSVs are production-derived and gitignored. They are read from your working copy and
# never enter a container image.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

if [[ ! -f "$HERE/.env" ]]; then
  echo "deploy/.env not found. Run: cp deploy/.env.example deploy/.env" >&2
  exit 1
fi
set -a; . "$HERE/.env"; set +a

FIXTURES="$ROOT/BackEnd/incident-service/src/test/resources/fixtures"
if [[ ! -f "$FIXTURES/issues.csv" ]]; then
  echo "Missing $FIXTURES/issues.csv" >&2
  echo "The mirror fixtures are production-derived and gitignored, so a fresh clone will not" >&2
  echo "have them. Copy them from a machine that does, or re-extract from the source DB." >&2
  exit 1
fi

PHASE="${1:-}"
case "$PHASE" in
  schema|data) ;;
  *) echo "usage: $0 <schema|data>   (see the ordering note at the top of this file)" >&2; exit 64 ;;
esac

cd "$ROOT/BackEnd/incident-service"
DEMO_SEED=true \
DEMO_SEED_PHASE="$PHASE" \
DEMO_DB_URL="jdbc:oracle:thin:@//localhost:1523/INCIDENTDB" \
DEMO_DB_USER="INCIDENT_DASH" \
DEMO_DB_PASSWORD="${INCIDENT_DASH_DB_PASSWORD:?set INCIDENT_DASH_DB_PASSWORD in deploy/.env}" \
  ./mvnw -q test -Dtest=DemoMirrorSeederTest -DfailIfNoTests=false

echo
if [[ "$PHASE" == "schema" ]]; then
  echo "Mirror schema created. Now start the service:  docker compose up -d"
else
  echo "Fixtures loaded. Give the ETL ~30s, then open ${DASHBOARD_ORIGIN:-http://localhost:8090}"
fi
