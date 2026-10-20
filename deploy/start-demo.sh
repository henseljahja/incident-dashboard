#!/usr/bin/env bash
# Brings the demo stack up in the ONE order that works, end to end.
#
#   ./deploy/start-demo.sh
#
# Why a script rather than a plain `docker compose up -d`: seeding has to happen on BOTH sides of
# the service's Flyway run, so no single compose invocation can express it.
#
#   mirror DDL  ->  service starts (Flyway)  ->  fixture rows
#
# Get it wrong in either direction and you get ORA-00942, just on a different table:
#   all at once   -> V3__mart.sql cannot create V_INCIDENT over mirror tables that do not exist
#   all up front  -> ref_custom_field_enum and ref_user target REF_ tables Flyway has not made yet
#
# Tier 2 sequences the same three steps the same way — see AbstractOracleIT, where the ordering is
# called out as load-bearing.
#
# Deliberately NOT solved with a restart policy: letting the service crash-loop until seeding
# happens to finish would turn a deterministic ordering requirement into a race that usually
# works.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

if [[ ! -f .env ]]; then
  echo "deploy/.env not found. Run: cp deploy/.env.example deploy/.env  (then change the values)" >&2
  exit 1
fi

echo "==> 1/4  Oracle"
docker compose up -d oracle

printf '     waiting for Oracle to report healthy (cold start takes ~2 minutes)'
for _ in $(seq 1 90); do
  status="$(docker inspect -f '{{.State.Health.Status}}' incident-demo-oracle 2>/dev/null || echo missing)"
  [[ "$status" == "healthy" ]] && break
  printf '.'
  sleep 5
done
echo
if [[ "$(docker inspect -f '{{.State.Health.Status}}' incident-demo-oracle 2>/dev/null)" != "healthy" ]]; then
  echo "Oracle did not become healthy in ~7 minutes. Check: docker compose logs oracle" >&2
  exit 1
fi

echo "==> 2/4  Mirror schema (before Flyway — see the note at the top of this file)"
./seed-demo.sh schema

echo "==> 3/4  Service + dashboard (this is where Flyway runs)"
# --build: images are tagged `latest` and never rebuilt otherwise, so a re-run after `git pull`
# kept serving the previous code (and the previous Flyway version) from the old image.
docker compose up -d --build

printf '     waiting for the service to report healthy'
for _ in $(seq 1 60); do
  status="$(docker inspect -f '{{.State.Health.Status}}' incident-demo-service 2>/dev/null || echo missing)"
  [[ "$status" == "healthy" ]] && break
  if [[ "$(docker inspect -f '{{.State.Status}}' incident-demo-service 2>/dev/null)" == "exited" ]]; then
    echo
    echo "The service exited during startup. Its Flyway run is the usual cause:" >&2
    echo "  docker compose logs incident-service" >&2
    exit 1
  fi
  printf '.'
  sleep 5
done
echo

echo "==> 4/4  Fixture rows (after Flyway, so the REF_ tables exist)"
./seed-demo.sh data

echo
echo "Dashboard:  ${DASHBOARD_ORIGIN:-http://localhost:8090}"
echo "Login:      admin  /  the INCIDENT_ADMIN_PASSWORD from deploy/.env"
echo "First login forces a password change — that is intended."
echo
echo "The mart is built by the service's own ETL on its next tick (~30s), so the dashboard may"
echo "be empty for a moment after the service reports healthy."
