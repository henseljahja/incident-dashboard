-- V9: the SLA clock stops when a ticket closes, and At Risk means an OPEN ticket about to breach.
--
-- Plan: .agents/plans/2026-09-15-stakeholder-notes-p1.md, T201 (note 12 of the 14 Sep meeting).
--
-- Additive only. Two config columns, one fact column, and a restatement of V_INCIDENT. V3 is NOT
-- edited: it is applied, and Flyway checksums it. The previous image runs unchanged against this
-- schema -- it never selects the new columns, and IS_CLOSED keeps its name.
--
-- REPAIR SAFETY. Oracle auto-commits every DDL statement, so a V9 that dies half way leaves some
-- columns added and Flyway marks the migration failed. Each ALTER below is therefore guarded by a
-- USER_TAB_COLUMNS check inside a PL/SQL block: after `flyway repair`, re-running V9 skips what
-- already exists instead of dying on ORA-01430 (column already exists). Each column and its CHECK
-- constraint are one ALTER statement, so a column never exists without its constraint.
--
-- The seed UPDATEs are deterministic by primary key, so a re-run writes the same rows.

-- --------------------------------------------------------------------------
-- CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE
--
-- NULL = use Redmine's issue_statuses.is_closed. 1 / 0 overrides it. This is deliberately NOT a
-- stage change: PTR (11) and PTR with permission (63) stay in the Blocked stage for the ops
-- funnel, because operationally they still are blocked -- the business only counts them as
-- closed for the SLA clock and the Open/Closed split.
-- --------------------------------------------------------------------------
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'CFG_STATUS_STAGE_MAP' AND COLUMN_NAME = 'IS_CLOSED_OVERRIDE';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE CFG_STATUS_STAGE_MAP ADD ('
            || 'IS_CLOSED_OVERRIDE NUMBER(1) NULL, '
            || 'CONSTRAINT CFG_SSM_CLOSED_OVR_CK CHECK (IS_CLOSED_OVERRIDE IN (0, 1)))';
    END IF;
END;
/

-- --------------------------------------------------------------------------
-- CFG_SLA_POLICY.AT_RISK_REMAINING_DAYS -- the per-priority at-risk cushion, in calendar days.
--
-- Absolute cushions, not a percentage: 80% of Low's 60 days leaves 12 days (not a risk), and 80%
-- of Critical's 3 days leaves under a day, which rounds to zero at calendar-day grain.
--
-- The CHECK keeps the cushion strictly below the SLA: a cushion >= SLA_DAYS would flag a ticket
-- as at risk on the day it was raised. DEFAULT 1 satisfies it for every existing row (the
-- smallest seeded SLA is 3 days), so the ALTER cannot fail on the data already there.
-- --------------------------------------------------------------------------
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'CFG_SLA_POLICY' AND COLUMN_NAME = 'AT_RISK_REMAINING_DAYS';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE CFG_SLA_POLICY ADD ('
            || 'AT_RISK_REMAINING_DAYS NUMBER(3) DEFAULT 1 NOT NULL, '
            || 'CONSTRAINT CFG_SLA_AT_RISK_CK CHECK '
            || '(AT_RISK_REMAINING_DAYS >= 0 AND AT_RISK_REMAINING_DAYS < SLA_DAYS))';
    END IF;
END;
/

-- --------------------------------------------------------------------------
-- INCIDENT_FACT.SLA_AT_RISK_DAYS -- the cushion, resolved at ETL time exactly as SLA_DAYS is.
--
-- A second place that enumerates an INCIDENT_FACT column, which .agents/patterns/migrations.md
-- permits for an additive ALTER in a later V-migration: the MERGE column list is generated from
-- USER_TAB_COLUMNS, and MartSchemaIT's tripwire list records the addition.
--
-- DEFAULT 1 NOT NULL: until the first refresh after deploy re-derives every row (the seed below
-- bumps the config generation), existing rows carry the default -- At Risk under-counts
-- non-Critical tickets for at most one refresh interval. Accepted in the plan's Risk section.
-- --------------------------------------------------------------------------
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'INCIDENT_FACT' AND COLUMN_NAME = 'SLA_AT_RISK_DAYS';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE INCIDENT_FACT ADD ('
            || 'SLA_AT_RISK_DAYS NUMBER(3) DEFAULT 1 NOT NULL)';
    END IF;
END;
/

-- --------------------------------------------------------------------------
-- Seed: close override + cushions, and ONE generation bump.
--
-- Every UPDATE below fires a V1 CFG_*_BUMP trigger per row (6 rows -> 6 bumps). The number of
-- bumps is an artefact of how the seed is written, not a fact about the config, so the block
-- records the generation first and leaves it at exactly that + 1 -- one config change, one
-- re-ETL. It must still MOVE: the mart's rows are stamped with the old generation and must be
-- re-derived to pick up SLA_AT_RISK_DAYS.
--
-- The markers are load-bearing: ConfigTablesIT.v9SeedsCloseOverrideAndCushions re-executes the
-- text between them to prove the block is repair-safe and bumps exactly once.
-- --------------------------------------------------------------------------
-- V9-SEED-BEGIN
DECLARE
    v_generation NUMBER;
BEGIN
    SELECT GENERATION INTO v_generation FROM CFG_VERSION WHERE SINGLETON = 'X';

    UPDATE CFG_STATUS_STAGE_MAP SET IS_CLOSED_OVERRIDE = 1 WHERE STATUS_ID IN (11, 63);

    -- Keyed on PRIORITY_ID, as the ladder is (V2): 1 Low, 19 Medium, 3 High, 21 Critical.
    UPDATE CFG_SLA_POLICY
       SET AT_RISK_REMAINING_DAYS = CASE PRIORITY_ID
                                        WHEN  1 THEN 7
                                        WHEN 19 THEN 3
                                        WHEN  3 THEN 2
                                        WHEN 21 THEN 1
                                    END
     WHERE PRIORITY_ID IN (1, 19, 3, 21);

    UPDATE CFG_VERSION
       SET GENERATION = v_generation + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/
-- V9-SEED-END

-- --------------------------------------------------------------------------
-- V_INCIDENT, restated in full. Diff it against V3__mart.sql line by line: every V3 column is
-- here with the same expression except ELAPSED_DAYS (the close clock) and IS_CLOSED (the
-- override); the new columns are REDMINE_IS_CLOSED, STOP_AT, CLOSE_BASIS and SLA_AT_RISK.
--
-- FOUR levels now, still on purpose, and still "each expression written once":
--
--   j  live dimension joins      -> IS_CLOSED, REDMINE_IS_CLOSED (and everything V3 had here)
--   c  the stop timestamp        -> STOP_AT, CLOSE_BASIS          (reads IS_CLOSED from j)
--   b  the ONE elapsed expression-> ELAPSED_DAYS, TTR_*            (reads STOP_AT from c)
--   outer                        -> AGING_NEW, SLA_*, RPT_*        (reads ELAPSED_DAYS from b)
--
-- Putting STOP_AT in the same block as IS_CLOSED would mean re-typing the override expression
-- inside it, and V3's header records what re-typed expressions did last time.
--
-- STOP_AT precedence, first match wins (every branch CAST AS DATE, so the CASE has one type):
--   1. a cf105 close date                      -> CLOSE_DATE           basis CLOSE_DATE
--   2. closed, and closed in Redmine           -> CLOSED_ON, else the last status change
--                                                                      basis CLOSED_ON / STATUS_CHANGE
--   3. closed by override only (PTR, 63)       -> LAST_DATE_STATUS     basis STATUS_CHANGE
--   4. anything else                           -> NULL                 basis OPEN, or UNKNOWN when
--                                                                      closed with no timestamp
-- Branch 2 also requires IS_CLOSED = 1, so an explicit override of 0 on a Redmine-closed status
-- (none is seeded) keeps the clock running, consistently with the Open/Closed split.
--
-- Branch 1 applies to open tickets too, exactly as V3's NVL(TRUNC(CLOSE_DATE), SYSDATE) did.
-- --------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_INCIDENT AS
SELECT b.*,
       -- Report renderings, unchanged from V3.
       TO_CHAR(b.DATE_CREATE, 'YYYY-MM-DD')  AS RPT_DATE_CREATE,   -- SC003, source line 15
       TO_CHAR(b.DATE_CREATE, 'YYYYMMDD')    AS RPT_CREATE_DATE,   -- SC004, source line 16
       TO_CHAR(b.UPDATED_ON,  'YYYYMMDD')    AS RPT_UPDATED_ON,    -- SC006, source line 18
       TO_CHAR(b.CLOSED_ON,   'YYYYMMDD')    AS RPT_CLOSED_ON,     -- SC007, source line 19
       b.ELAPSED_DAYS                                             AS AGING_NEW,
       ROUND(b.ELAPSED_DAYS / NULLIF(b.SLA_DAYS, 0), 4)           AS SLA_RATIO,
       -- Unchanged from V3, and still with NO `IS_CLOSED = 1 THEN 0` short-circuit: closing
       -- late is a breach. The clock stopping only means it stops growing.
       CASE WHEN b.ELAPSED_DAYS IS NULL THEN 0
            WHEN b.ELAPSED_DAYS > b.SLA_DAYS THEN 1 ELSE 0 END    AS SLA_BREACHED,
       -- The time-used distribution over ALL tickets, unchanged from V3. It is a different
       -- question from SLA_AT_RISK below, and keeps its percentage bands for Excel parity.
       CASE WHEN b.ELAPSED_DAYS IS NULL                THEN 'Unknown'
            WHEN b.ELAPSED_DAYS >  b.SLA_DAYS          THEN 'Breached'
            WHEN b.ELAPSED_DAYS >= b.SLA_DAYS * 0.8    THEN '80-100%'
            WHEN b.ELAPSED_DAYS >= b.SLA_DAYS * 0.5    THEN '50-80%'
            ELSE '0-50%' END                                      AS SLA_BUCKET,
       -- An OPEN ticket, not yet breached, with no more calendar days left than its priority's
       -- cushion. A closed ticket is never at risk: there is nothing left to act on.
       CAST(CASE WHEN b.IS_CLOSED = 0
                  AND b.ELAPSED_DAYS IS NOT NULL
                  AND b.ELAPSED_DAYS <= b.SLA_DAYS
                  AND (b.SLA_DAYS - b.ELAPSED_DAYS) <= b.SLA_AT_RISK_DAYS
                 THEN 1 ELSE 0 END AS NUMBER(1))                  AS SLA_AT_RISK
FROM (
  SELECT c.*,
         -- THE single elapsed expression. Midnight anchor -> CALENDAR days.
         --
         -- Precedence, first match wins. The closed branch is evaluated BEFORE the empty-row
         -- branch: a closed ticket whose cf105 row exists but is empty stops at its Redmine
         -- close time rather than going NULL (fixture 22878).
         CASE WHEN c.IS_CLOSED = 1 AND c.STOP_AT IS NULL THEN NULL
              -- Open, cf105 row present but empty: unknown elapsed, as V3 / the source query.
              WHEN c.IS_CLOSED = 0 AND c.HAS_CLOSE_DATE_ROW = 1 AND c.CLOSE_DATE IS NULL
                   THEN NULL
              ELSE TRUNC(NVL(c.STOP_AT, SYSDATE)) - TRUNC(CAST(c.DATE_CREATE AS DATE))
         END                                                                   AS ELAPSED_DAYS,
         CASE WHEN c.RESOLUTION_DATE IS NOT NULL
              THEN TRUNC(c.RESOLUTION_DATE) - TRUNC(CAST(c.DATE_CREATE AS DATE))
         END                                                                   AS TTR_WORKAROUND_DAYS,
         CASE WHEN c.CLOSE_DATE IS NOT NULL
              THEN TRUNC(c.CLOSE_DATE) - TRUNC(CAST(c.DATE_CREATE AS DATE))
         END                                                                   AS TTR_PERMANENT_DAYS
  FROM (
    SELECT j.*,
           CASE WHEN j.HAS_CLOSE_DATE_ROW = 1 AND j.CLOSE_DATE IS NOT NULL
                     THEN CAST(j.CLOSE_DATE AS DATE)
                WHEN j.IS_CLOSED = 1 AND j.REDMINE_IS_CLOSED = 1
                     THEN CAST(NVL(j.CLOSED_ON, j.LAST_DATE_STATUS) AS DATE)
                WHEN j.IS_CLOSED = 1
                     THEN CAST(j.LAST_DATE_STATUS AS DATE)
                ELSE CAST(NULL AS DATE)
           END                                                                 AS STOP_AT,
           CAST(CASE WHEN j.HAS_CLOSE_DATE_ROW = 1 AND j.CLOSE_DATE IS NOT NULL
                          THEN 'CLOSE_DATE'
                     WHEN j.IS_CLOSED = 0
                          THEN 'OPEN'
                     WHEN j.REDMINE_IS_CLOSED = 1 AND j.CLOSED_ON IS NOT NULL
                          THEN 'CLOSED_ON'
                     WHEN j.LAST_DATE_STATUS IS NOT NULL
                          THEN 'STATUS_CHANGE'
                     -- Closed with no usable timestamp. Counted in the refresh summary log
                     -- (closeBasisUnknown) so the data-quality cohort is visible.
                     ELSE 'UNKNOWN'
                END AS VARCHAR2(16))                                           AS CLOSE_BASIS
    FROM (
      SELECT f.*,
             f.SLA_DAYS                                         AS SLA,   -- SC011: see V3
             NVL(s.NAME, 'Unknown (' || f.STATUS_ID || ')')     AS STATUS,
             -- The business's notion of closed: the override when set, else Redmine's flag.
             NVL(m.IS_CLOSED_OVERRIDE, NVL(s.IS_CLOSED, 0))     AS IS_CLOSED,
             NVL(t.NAME, 'Unknown (' || f.TRACKER_ID || ')')    AS TRACKER,
             NVL(e.NAME, 'Unclassified')                        AS PRIORITY,
             NVL(m.STAGE, 'Unmapped')                           AS STATUS_STAGE,
             NVL(m.EXCLUDE_FROM_BACKLOG, 0)                     AS EXCLUDE_FROM_BACKLOG,
             -- SYSDATE, never SYSTIMESTAMP: mirror timestamps are offset-naive MySQL wall
             -- clock. See Global Constraints -- one timezone, pinned server-side.
             --
             -- raw-datetime anchor: whole days ELAPSED (source lines 20 and 62)
             TRUNC(SYSDATE - CAST(f.DATE_CREATE AS DATE))                          AS AGING_INCIDENT,
             TRUNC(SYSDATE - CAST(f.LAST_DATE_STATUS AS DATE))                     AS AGING_LAST_STATUS,
             CASE WHEN f.FIRST_STATUS_CHANGE_AT IS NULL THEN NULL
                  ELSE (CAST(f.FIRST_STATUS_CHANGE_AT AS DATE)
                        - CAST(f.DATE_CREATE AS DATE)) * 24
             END                                                                   AS MTTA_HOURS,
             -- Redmine's own flag, kept for debugging the override. Not exported.
             NVL(s.IS_CLOSED, 0)                                AS REDMINE_IS_CLOSED
      FROM   INCIDENT_FACT f
      LEFT   JOIN ${mirror}ISSUE_STATUSES s ON s.ID = f.STATUS_ID
      LEFT   JOIN ${mirror}TRACKERS       t ON t.ID = f.TRACKER_ID
      LEFT   JOIN ${mirror}ENUMERATIONS   e ON e.ID = f.PRIORITY_ID AND e.TYPE = 'IssuePriority'
      LEFT   JOIN CFG_STATUS_STAGE_MAP    m ON m.STATUS_ID = f.STATUS_ID
    ) j
  ) c
) b;
