# local-infra — LOCAL DEV Oracle stack

**This is the developer-workstation Oracle stack for the incident dashboard.
It is NOT the automated test stack.**

Tier-2/Tier-3 integration and E2E tests must run against a separate,
isolated `docker-compose.test.yml` (own compose project name, host ports,
and named volumes) — those fixtures TRUNCATE tables and would destroy this
dev database. If that file does not exist yet, it needs to be created
before any integration/E2E test run; do not point tests at this stack.

## What it is

A single-container Oracle Database Free (`gvenzl/oracle-free:23-slim`)
instance, seeded on first boot with two schemas:

- **`REDMINE_MIRROR`** — raw 1:1 mirror of the 7 Redmine tables the report
  reads (`issues`, `journals`, `journal_details`, `custom_values`,
  `custom_field_enumerations`, `enumerations`, `trackers`, ...). The mirror
  tables themselves are created later by the app/test harness (ETL job or
  fixture loader) — this stack only provisions the user.
- **`INCIDENT_DASH`** — our own writable schema: the mart
  (`INCIDENT_FACT`), config/reference tables, RBAC tables. Flyway migrates
  **only** this schema. It is granted `SELECT ANY TABLE` for local dev
  convenience so it can query `REDMINE_MIRROR` objects as soon as they
  exist; production grants are per-table `SELECT`, issued explicitly.

See [`init/01-schemas.sql`](init/01-schemas.sql) for the exact DDL/grants.

## Compose isolation

- Project name: `incident-dashboard-dev` (see `name:` in `docker-compose.yml`)
- Host port: **1522** (container's 1521), so it can never collide with or be
  mistaken for the isolated test stack
- Named volume: `incident-dashboard-dev-oracle-data`

## Start / stop

```bash
# from BackEnd/local-infra/
docker compose up --wait      # start and block until healthy
docker compose down           # stop, keep data
docker compose down -v        # stop and wipe the volume (fresh re-seed)
```

Override the password by exporting `ORACLE_PASSWORD` before `up` (default is
the obviously-local `localdev` — this stack is never used in production).

## Connecting

JDBC URLs (PDB name `INCIDENTDB`, host port 1522):

```
# REDMINE_MIRROR
jdbc:oracle:thin:@//localhost:1522/INCIDENTDB
  user=REDMINE_MIRROR password=localdev

# INCIDENT_DASH (Flyway target)
jdbc:oracle:thin:@//localhost:1522/INCIDENTDB
  user=INCIDENT_DASH password=localdev
```

## Verify it's up

```bash
docker compose ps                                   # STATUS should be "healthy"
docker exec -it incident-dashboard-dev-oracle sqlplus INCIDENT_DASH/localdev@//localhost:1521/INCIDENTDB
```

or from the host with a SQL client using the JDBC URLs above.
