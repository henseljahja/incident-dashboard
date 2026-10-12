-- =============================================================================
-- LOCAL DEV init script — runs once on first container start
-- (gvenzl/oracle-free executes everything under /container-entrypoint-initdb.d
-- against the ORACLE_DATABASE PDB after it is created).
--
-- Creates the two schemas used by the incident dashboard:
--
--   REDMINE_MIRROR  - raw 1:1 mirror of EXACTLY 7 Redmine tables: issues,
--                      issue_statuses, enumerations, trackers, custom_values,
--                      journals, journal_details. Read-only to the app in prod.
--                      NOT delivered, and deliberately absent here:
--                      custom_field_enumerations, users, email_addresses,
--                      projects. The first three are covered by our own
--                      REF_CUSTOM_FIELD_ENUM / REF_USER tables in
--                      INCIDENT_DASH; projects is a dead join in the source
--                      report. Do not add them to this list. The mirror
--                      tables themselves do NOT exist yet at this point —
--                      they are created by the app/test harness (ETL job or
--                      fixture loader) after this container boots. This
--                      script only provisions the user/schema and the
--                      privileges needed so that future object-level grants
--                      (issued by the fixture loader, see below) will work.
--
--   INCIDENT_DASH   - our own writable schema: the mart (INCIDENT_FACT),
--                      config/reference tables, RBAC tables. Flyway
--                      migrates ONLY this schema.
--
-- NOTE: Per-table SELECT grants from REDMINE_MIRROR to INCIDENT_DASH are
-- issued by the dev fixture loader / ETL job once the mirror tables exist
-- (a GRANT SELECT ON <table> TO INCIDENT_DASH per table, or a
-- CREATE ROLE ... run after the mirror is populated). We cannot grant on
-- objects that don't exist yet, so here we only grant the roles that make
-- those future per-table grants possible, plus a standing ANY SELECT
-- fallback scoped to this schema pair for local dev convenience only
-- (never do this in prod).
-- =============================================================================

ALTER SESSION SET CONTAINER = INCIDENTDB;

-- ---------------------------------------------------------------------------
-- REDMINE_MIRROR: raw mirror schema
-- ---------------------------------------------------------------------------
CREATE USER REDMINE_MIRROR IDENTIFIED BY "localdev";
GRANT CONNECT, RESOURCE TO REDMINE_MIRROR;
GRANT UNLIMITED TABLESPACE TO REDMINE_MIRROR;

-- ---------------------------------------------------------------------------
-- INCIDENT_DASH: writable app schema (Flyway target)
-- ---------------------------------------------------------------------------
CREATE USER INCIDENT_DASH IDENTIFIED BY "localdev";
GRANT CONNECT, RESOURCE TO INCIDENT_DASH;
GRANT UNLIMITED TABLESPACE TO INCIDENT_DASH;

-- Lets INCIDENT_DASH query any mirror table once the fixture loader / ETL
-- job creates them, without waiting on per-table grants each time a table
-- is added. Local dev convenience only — prod grants are per-table SELECT,
-- issued explicitly against REDMINE_MIRROR objects.
GRANT SELECT ANY TABLE TO INCIDENT_DASH;

EXIT;
