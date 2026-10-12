-- V1: configuration and reference tables.
--
-- Redmine ids are DATA, not code. Every id the source report hardcodes
-- ("Query Detail Redmine Incident, Problem, SR.sql") lands in one of these tables, so a
-- Redmine admin's change is a config edit rather than a redeploy.
--
-- CFG_* = policy we own and edit.
-- REF_* = stand-ins for Redmine tables that are NOT delivered in the mirror
--         (custom_field_enumerations, users, email_addresses).

-- --------------------------------------------------------------------------
-- CFG_VERSION — the cache-invalidation token for the whole mart.
--
-- Exactly one row, ever: the SINGLETON check constraint plus the primary key make a second
-- row impossible rather than merely discouraged. INCIDENT_FACT.CFG_GENERATION and the
-- reconcile predicates key on GENERATION, so a stale value silently means a half-mapped
-- mart -- see the trigger block at the bottom of this file.
-- --------------------------------------------------------------------------
CREATE TABLE CFG_VERSION (
    SINGLETON  CHAR(1)     DEFAULT 'X' NOT NULL,
    GENERATION NUMBER(10)  DEFAULT 0   NOT NULL,
    UPDATED_AT TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT CFG_VERSION_PK PRIMARY KEY (SINGLETON),
    CONSTRAINT CFG_VERSION_SINGLETON_CK CHECK (SINGLETON = 'X')
);

-- --------------------------------------------------------------------------
-- CFG_CUSTOM_FIELD_MAP — custom_values.custom_field_id -> report column.
--
-- FIELD_KIND drives HOW the value is read, not just what it is called:
--   ENUM -> join REF_CUSTOM_FIELD_ENUM to resolve the id to a label
--   DATE -> read custom_values.value as text and parse
--   TEXT -> pass through
-- --------------------------------------------------------------------------
CREATE TABLE CFG_CUSTOM_FIELD_MAP (
    CUSTOM_FIELD_ID NUMBER(10)         NOT NULL,
    LOGICAL_NAME    VARCHAR2(64 CHAR)  NOT NULL,
    TARGET_COLUMN   VARCHAR2(64 CHAR)  NOT NULL,
    FIELD_KIND      VARCHAR2(8 CHAR)   NOT NULL,
    IS_ACTIVE       NUMBER(1)          DEFAULT 1 NOT NULL,
    CONSTRAINT CFG_CUSTOM_FIELD_MAP_PK PRIMARY KEY (CUSTOM_FIELD_ID),
    CONSTRAINT CFG_CFM_LOGICAL_NAME_UK UNIQUE (LOGICAL_NAME),
    CONSTRAINT CFG_CFM_FIELD_KIND_CK CHECK (FIELD_KIND IN ('ENUM', 'DATE', 'TEXT')),
    CONSTRAINT CFG_CFM_IS_ACTIVE_CK CHECK (IS_ACTIVE IN (0, 1))
);

-- --------------------------------------------------------------------------
-- CFG_JOURNAL_PROP_MAP — journal_details.(property, prop_key) -> report column.
--
-- PROP_KEY is VARCHAR2, NOT numeric: Redmine keys custom fields by their numeric id
-- ('115') but attributes by name ('status_id') in the same column.
--
-- PROPERTY ('cf' | 'attr') is therefore load-bearing and not decoration: a custom field
-- could itself be keyed 'status_id', and reading it as the status attribute would corrupt
-- the lifecycle history. Consumers MUST match on the pair.
-- --------------------------------------------------------------------------
CREATE TABLE CFG_JOURNAL_PROP_MAP (
    PROP_KEY      VARCHAR2(30 CHAR)  NOT NULL,
    PROPERTY      VARCHAR2(8 CHAR)   NOT NULL,
    LOGICAL_NAME  VARCHAR2(64 CHAR)  NOT NULL,
    TARGET_COLUMN VARCHAR2(64 CHAR)  NOT NULL,
    VALUE_KIND    VARCHAR2(8 CHAR)   NOT NULL,
    IS_ACTIVE     NUMBER(1)          DEFAULT 1 NOT NULL,
    CONSTRAINT CFG_JOURNAL_PROP_MAP_PK PRIMARY KEY (PROP_KEY),
    CONSTRAINT CFG_JPM_LOGICAL_NAME_UK UNIQUE (LOGICAL_NAME),
    CONSTRAINT CFG_JPM_PROPERTY_CK CHECK (PROPERTY IN ('cf', 'attr')),
    CONSTRAINT CFG_JPM_VALUE_KIND_CK CHECK (VALUE_KIND IN ('TEXT', 'USER', 'STATUS')),
    CONSTRAINT CFG_JPM_IS_ACTIVE_CK CHECK (IS_ACTIVE IN (0, 1))
);

-- --------------------------------------------------------------------------
-- CFG_STATUS_STAGE_MAP — issue_statuses.id -> lifecycle stage.
--
-- A status absent from this table is never dropped from an aggregate; it surfaces as
-- 'Unmapped' (Task 9) so a stale seed degrades loudly rather than silently.
-- --------------------------------------------------------------------------
CREATE TABLE CFG_STATUS_STAGE_MAP (
    STATUS_ID             NUMBER(10)         NOT NULL,
    STATUS_NAME           VARCHAR2(64 CHAR)  NOT NULL,
    STAGE                 VARCHAR2(16 CHAR)  NOT NULL,
    EXCLUDE_FROM_BACKLOG  NUMBER(1)          DEFAULT 0 NOT NULL,
    CONSTRAINT CFG_STATUS_STAGE_MAP_PK PRIMARY KEY (STATUS_ID),
    CONSTRAINT CFG_SSM_STAGE_CK CHECK (STAGE IN
        ('New', 'Triage', 'In Progress', 'Blocked', 'Resolved', 'Closed', 'Cancelled')),
    CONSTRAINT CFG_SSM_EXCLUDE_CK CHECK (EXCLUDE_FROM_BACKLOG IN (0, 1))
);

-- --------------------------------------------------------------------------
-- CFG_TRACKER_SCOPE — the trackers the dashboard reports on.
-- --------------------------------------------------------------------------
CREATE TABLE CFG_TRACKER_SCOPE (
    TRACKER_ID   NUMBER(10)         NOT NULL,
    LOGICAL_NAME VARCHAR2(64 CHAR)  NOT NULL,
    IS_ACTIVE    NUMBER(1)          DEFAULT 1 NOT NULL,
    CONSTRAINT CFG_TRACKER_SCOPE_PK PRIMARY KEY (TRACKER_ID),
    CONSTRAINT CFG_TS_IS_ACTIVE_CK CHECK (IS_ACTIVE IN (0, 1))
);

-- --------------------------------------------------------------------------
-- CFG_SLA_POLICY — the SLA ladder, KEYED ON PRIORITY_ID, not on the name.
--
-- The source query switches on the priority NAME ('Low' -> 60 ... ELSE 3). Keying on the
-- name means a Redmine admin renaming "Low" to "Low Priority" silently moves those
-- incidents from a 60-day budget to the 3-day ELSE bucket. PRIORITY_NAME is stored for
-- human readability only; nothing looks up by it.
-- --------------------------------------------------------------------------
CREATE TABLE CFG_SLA_POLICY (
    PRIORITY_ID   NUMBER(10)         NOT NULL,
    PRIORITY_NAME VARCHAR2(64 CHAR)  NOT NULL,
    SLA_DAYS      NUMBER(5)          NOT NULL,
    CONSTRAINT CFG_SLA_POLICY_PK PRIMARY KEY (PRIORITY_ID),
    CONSTRAINT CFG_SLA_DAYS_CK CHECK (SLA_DAYS > 0)
);

-- --------------------------------------------------------------------------
-- REF_CUSTOM_FIELD_ENUM — stand-in for redmine.custom_field_enumerations.
--
-- Not delivered in the mirror, so it is seeded out-of-band (scripts/extract-ref-tables.py
-- in production, a synthetic fixture CSV in tests). An id with no row here resolves to
-- 'Unknown (<id>)' rather than NULL, so a stale seed is visible in the report.
-- --------------------------------------------------------------------------
CREATE TABLE REF_CUSTOM_FIELD_ENUM (
    CUSTOM_FIELD_ID NUMBER(10)          NOT NULL,
    ENUM_VALUE_ID   NUMBER(10)          NOT NULL,
    LABEL           VARCHAR2(255 CHAR)  NOT NULL,
    POSITION        NUMBER(10)          DEFAULT 0 NOT NULL,
    CONSTRAINT REF_CUSTOM_FIELD_ENUM_PK PRIMARY KEY (CUSTOM_FIELD_ID, ENUM_VALUE_ID)
);

-- --------------------------------------------------------------------------
-- REF_USER — stand-in for redmine.users AND redmine.email_addresses (neither delivered).
--
-- All four value columns are REQUIRED as columns because Task 8's three label rules read
-- three different ones:
--   ADDRESS   -> author label (email)
--   LASTNAME  -> assigned_to fallback when the assignee has no email_addresses row
--   FIRSTNAME -> Developer_assigned label
-- Dropping any one makes that report column unbuildable. They are nullable because the
-- upstream rows genuinely can be missing; the label rules handle that, the schema does not.
-- --------------------------------------------------------------------------
CREATE TABLE REF_USER (
    USER_ID      NUMBER(10)          NOT NULL,
    ADDRESS      VARCHAR2(255 CHAR),
    FIRSTNAME    VARCHAR2(255 CHAR),
    LASTNAME     VARCHAR2(255 CHAR),
    DISPLAY_NAME VARCHAR2(255 CHAR),
    CONSTRAINT REF_USER_PK PRIMARY KEY (USER_ID)
);

-- --------------------------------------------------------------------------
-- The generation bump is a TRIGGER, not a convention.
--
-- "Remember to bump CFG_VERSION after editing config" is not enforceable, and a missed bump
-- is silently a half-mapped mart -- the MERGE skips rows it believes are already current.
-- One row-level AFTER INSERT OR UPDATE OR DELETE trigger per CFG_* mapping table makes the
-- bump a property of the schema instead.
--
-- No ORA-04091 (mutating table) here and no autonomous transaction: each trigger updates
-- CFG_VERSION, a DIFFERENT table from the one it fires on. Verified by V2 (which seeds 62
-- rows through these triggers) and by aConfigWriteIncrementsTheGeneration.
--
-- CFG_VERSION deliberately carries NO trigger of its own: it is a CFG_* table, and a
-- trigger on it that updates it would recurse.
-- --------------------------------------------------------------------------
CREATE OR REPLACE TRIGGER CFG_CUSTOM_FIELD_MAP_BUMP
AFTER INSERT OR UPDATE OR DELETE ON CFG_CUSTOM_FIELD_MAP
FOR EACH ROW
BEGIN
    UPDATE CFG_VERSION
       SET GENERATION = GENERATION + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/

CREATE OR REPLACE TRIGGER CFG_JOURNAL_PROP_MAP_BUMP
AFTER INSERT OR UPDATE OR DELETE ON CFG_JOURNAL_PROP_MAP
FOR EACH ROW
BEGIN
    UPDATE CFG_VERSION
       SET GENERATION = GENERATION + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/

CREATE OR REPLACE TRIGGER CFG_STATUS_STAGE_MAP_BUMP
AFTER INSERT OR UPDATE OR DELETE ON CFG_STATUS_STAGE_MAP
FOR EACH ROW
BEGIN
    UPDATE CFG_VERSION
       SET GENERATION = GENERATION + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/

CREATE OR REPLACE TRIGGER CFG_TRACKER_SCOPE_BUMP
AFTER INSERT OR UPDATE OR DELETE ON CFG_TRACKER_SCOPE
FOR EACH ROW
BEGIN
    UPDATE CFG_VERSION
       SET GENERATION = GENERATION + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/

CREATE OR REPLACE TRIGGER CFG_SLA_POLICY_BUMP
AFTER INSERT OR UPDATE OR DELETE ON CFG_SLA_POLICY
FOR EACH ROW
BEGIN
    UPDATE CFG_VERSION
       SET GENERATION = GENERATION + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/
