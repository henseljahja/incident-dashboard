-- V3: the mart.
--
-- INCIDENT_FACT below is THE canonical column list for the mart. Nothing else in the
-- codebase may enumerate it: the MERGE in MartRefreshRepository (Task 11) is generated from
-- USER_TAB_COLUMNS, IncidentRowDto (Task 16) is asserted against V_INCIDENT's columns, and
-- the XLSX header (Task 18) is derived from the DTO. MartSchemaIT enforces that.
--
-- Every query in Tasks 16-22 reads V_INCIDENT, never INCIDENT_FACT directly.
--
-- Two rules this file exists to hold:
--
--   1. NOTHING CLOCK-DERIVED IS MATERIALISED. Aging and SLA columns live on the view only.
--      Storing them would make every row stale the moment it is written.
--   2. DIMENSION *NAMES* ARE NOT MATERIALISED, ONLY THEIR IDS. issue_statuses, trackers and
--      enumerations are 74/5/4 rows and Redmine admins rename them WITHOUT touching
--      issues.updated_on -- the ETL watermark would never see the change and the mart would
--      serve a stale label forever. V_INCIDENT joins them live, so a rename is correct
--      instantly with no re-ETL.
--
--      The six ENUM-valued CUSTOM FIELDS are the deliberate exception: the fact keeps BOTH
--      the raw enum id and the resolved label, so a later REF_CUSTOM_FIELD_ENUM correction
--      re-labels history with an UPDATE instead of a full re-ETL.
--
-- ORACLE EMPTY-STRING RULE (measured in T006, artifacts/oracle-empty-clob-finding.md):
-- on Oracle `value = ''` IS `value = NULL` and is NEVER true. Every predicate ported from
-- the MySQL source's `close_date IS NULL OR close_date = ''` must instead branch on the
-- HAS_*_ROW flags below (write time) or use DBMS_LOB.GETLENGTH(...) = 0 (CLOB read time).
-- There is deliberately no `= ''` anywhere in this file.

-- --------------------------------------------------------------------------
-- INCIDENT_FACT -- write-time columns only.
-- --------------------------------------------------------------------------
CREATE TABLE INCIDENT_FACT (
  NO_INCIDENT           NUMBER(10) PRIMARY KEY,
  TRACKER_ID            NUMBER(10) NOT NULL,
  SUBJECT               VARCHAR2(255 CHAR),
  DATE_CREATE           TIMESTAMP(0) NOT NULL,
  CREATE_MONTH          VARCHAR2(6 CHAR) NOT NULL,      -- YYYYMM, for grouping
  UPDATED_ON            TIMESTAMP(0),
  CLOSED_ON             TIMESTAMP(0),

  STATUS_ID             NUMBER(10) NOT NULL,
  PRIORITY_ID           NUMBER(10),
  SLA_DAYS              NUMBER(3)  NOT NULL,
  PARENT_ID             NUMBER(10),
  DONE_RATIO            NUMBER(3),

  AUTHOR_ID             NUMBER(10),
  AUTHOR                VARCHAR2(120 CHAR),
  ASSIGNED_TO_ID        NUMBER(10),
  ASSIGNED_TO           VARCHAR2(120 CHAR),
  DEVELOPER_ASSIGNED_ID NUMBER(10),
  DEVELOPER_ASSIGNED    VARCHAR2(120 CHAR),

  -- Resolved label + raw id, for all six enum-valued custom fields.
  TYPE_OF_INCIDENT      VARCHAR2(120 CHAR), TYPE_OF_INCIDENT_ID  NUMBER(10),
  CATEGORY_INCIDENT     VARCHAR2(120 CHAR), CATEGORY_INCIDENT_ID NUMBER(10),
  EXTERNAL_INTERNAL     VARCHAR2(120 CHAR), EXTERNAL_INTERNAL_ID NUMBER(10),
  IMPACT_SEVERITY       VARCHAR2(120 CHAR), IMPACT_SEVERITY_ID   NUMBER(10),
  URGENCY_SEVERITY      VARCHAR2(120 CHAR), URGENCY_SEVERITY_ID  NUMBER(10),
  INSTITUSI             VARCHAR2(120 CHAR), INSTITUSI_ID         NUMBER(10),

  CLOSE_DATE            DATE,
  RESOLUTION_DATE       DATE,
  MIGRATION_DATE        DATE,
  -- Row-presence flags. Oracle cannot store '' (see the header), so these carry the
  -- NULL-vs-'' distinction that SOLUTION and AGING_NEW both turn on. 1 = a custom_values row
  -- exists for that field; 0 = no row at all.
  HAS_CLOSE_DATE_ROW    NUMBER(1) DEFAULT 0 NOT NULL,
  HAS_RESOLUTION_ROW    NUMBER(1) DEFAULT 0 NOT NULL,
  HAS_DATE_REJECT       NUMBER(1) DEFAULT 0 NOT NULL,   -- badge the row in the UI
  SOLUTION              VARCHAR2(40 CHAR),

  LAST_STATUS           VARCHAR2(30 CHAR),
  LAST_DATE_STATUS      TIMESTAMP(0),
  FIRST_STATUS_CHANGE_AT TIMESTAMP(0),                  -- MTTA proxy anchor
  WORKAROUND            CLOB,
  ROOTCAUSE             CLOB,

  ETL_UPDATED_AT        TIMESTAMP(0) NOT NULL,
  SOURCE_UPDATED_ON     TIMESTAMP(0) NOT NULL,          -- issues.updated_on at ETL time
  CV_DIGEST             NUMBER,                         -- see CV_DIGEST below
  -- Config generation this row was derived under. A CFG_* edit bumps CFG_VERSION.GENERATION;
  -- rows stamped with an older value are stale by definition and re-derived on next scan.
  CFG_GENERATION        NUMBER(10) DEFAULT 0 NOT NULL
);

-- Indexes may only reference columns THIS migration creates. STATUS_STAGE and IS_CLOSED are
-- view-level (they come from CFG_STATUS_STAGE_MAP / the mirror) and are therefore not
-- indexable here; filters on them resolve through STATUS_ID, which is indexed.
CREATE INDEX IX_FACT_CREATE   ON INCIDENT_FACT (DATE_CREATE);
CREATE INDEX IX_FACT_MONTH    ON INCIDENT_FACT (CREATE_MONTH);
CREATE INDEX IX_FACT_STATUS   ON INCIDENT_FACT (STATUS_ID);
CREATE INDEX IX_FACT_PRIORITY ON INCIDENT_FACT (PRIORITY_ID);
CREATE INDEX IX_FACT_TRACKER  ON INCIDENT_FACT (TRACKER_ID);
CREATE INDEX IX_FACT_MATRIX   ON INCIDENT_FACT (IMPACT_SEVERITY, URGENCY_SEVERITY);

-- --------------------------------------------------------------------------
-- INCIDENT_FACT_REJECT -- quarantine, NOT a nullifier.
--
-- Unparseable free-text dates ('not-a-date', '31/02/2025') land here and the fact row is
-- badged with HAS_DATE_REJECT = 1. A NULL meaning "no date" and a NULL meaning "we could not
-- read this value" are different facts, and the SLA numbers must not quietly absorb the
-- second one.
-- --------------------------------------------------------------------------
CREATE TABLE INCIDENT_FACT_REJECT (
  REJECT_ID       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  NO_INCIDENT     NUMBER(10) NOT NULL,
  CUSTOM_FIELD_ID NUMBER(10) NOT NULL,
  LOGICAL_NAME    VARCHAR2(40 CHAR) NOT NULL,
  RAW_VALUE       VARCHAR2(400 CHAR),
  REASON          VARCHAR2(200 CHAR) NOT NULL,
  DETECTED_AT     TIMESTAMP(0) DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX IX_REJECT_INCIDENT ON INCIDENT_FACT_REJECT (NO_INCIDENT);

-- --------------------------------------------------------------------------
-- ETL_WATERMARK / ETL_RECONCILE_LOG -- consumed by Tasks 11 and 12.
-- --------------------------------------------------------------------------
CREATE TABLE ETL_WATERMARK (
  WATERMARK_KEY   VARCHAR2(40 CHAR) PRIMARY KEY,
  LAST_SOURCE_TS  TIMESTAMP(0),
  LAST_RUN_AT     TIMESTAMP(0),
  LAST_RUN_STATUS VARCHAR2(20 CHAR),
  ROWS_MERGED     NUMBER(10)
);
INSERT INTO ETL_WATERMARK (WATERMARK_KEY, LAST_SOURCE_TS)
VALUES ('incident_fact', TIMESTAMP '1970-01-01 00:00:00');

CREATE TABLE ETL_RECONCILE_LOG (
  RUN_ID        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  RUN_AT        TIMESTAMP(0) DEFAULT SYSTIMESTAMP NOT NULL,
  SCOPE_MONTH   VARCHAR2(6 CHAR),
  MIRROR_COUNT  NUMBER(10),
  FACT_COUNT    NUMBER(10),
  DRIFT_COUNT   NUMBER(10),
  ACTION_TAKEN  VARCHAR2(200 CHAR)
);

-- CV_DIGEST -- THE canonical definition. Tasks 11 and 12 both reference THIS expression and
-- neither may restate it. It must be computed by Oracle on both sides: computing it in
-- Java cannot reproduce ORA_HASH and would report false drift on every reconcile.
--
--   SELECT NVL(SUM(ORA_HASH(cv.ID || '|' || cv.CUSTOM_FIELD_ID || '|'
--                           || NVL(DBMS_LOB.SUBSTR(cv.VALUE, 200), '~'))), 0)
--   FROM   <mirror>CUSTOM_VALUES cv
--   WHERE  cv.CUSTOMIZED_TYPE = 'Issue' AND cv.CUSTOMIZED_ID = :issueId

-- --------------------------------------------------------------------------
-- V_INCIDENT -- the ONLY read surface. Two levels ON PURPOSE.
--
-- ELAPSED_DAYS is computed ONCE in the inner block and AGING_NEW, SLA_RATIO, SLA_BREACHED
-- and SLA_BUCKET all derive from it in the outer block. Re-typing the expression per column
-- is how an earlier draft ended up with AGING_NEW correctly NULL for the 8% close_date = ''
-- cohort while SLA_BREACHED scored those same rows as 600-day breaches -- and the
-- breached-agrees-with-bucket test still passed, because both wrong columns agreed.
--
-- EVERY join is a LEFT join with an NVL fallback. An INNER join would silently drop any
-- incident whose status, tracker or priority row is missing from the mirror (a Kafka gap, or
-- a dimension row deleted in Redmine) -- precisely the "unresolved values must never vanish
-- from an aggregate" failure the Global Constraints forbid.
-- --------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_INCIDENT AS
SELECT b.*,
       -- Report renderings. The fact stores native types (so sorting and filtering are
       -- correct); the delivered workbook's formats are applied here and mapped straight
       -- onto the report aliases by the DTO. See "The report column contract".
       TO_CHAR(b.DATE_CREATE, 'YYYY-MM-DD')  AS RPT_DATE_CREATE,   -- SC003, source line 15
       TO_CHAR(b.DATE_CREATE, 'YYYYMMDD')    AS RPT_CREATE_DATE,   -- SC004, source line 16
       TO_CHAR(b.UPDATED_ON,  'YYYYMMDD')    AS RPT_UPDATED_ON,    -- SC006, source line 18
       TO_CHAR(b.CLOSED_ON,   'YYYYMMDD')    AS RPT_CLOSED_ON,     -- SC007, source line 19
       b.ELAPSED_DAYS                                             AS AGING_NEW,
       ROUND(b.ELAPSED_DAYS / NULLIF(b.SLA_DAYS, 0), 4)           AS SLA_RATIO,
       CASE WHEN b.ELAPSED_DAYS IS NULL THEN 0
            WHEN b.ELAPSED_DAYS > b.SLA_DAYS THEN 1 ELSE 0 END    AS SLA_BREACHED,
       -- A NULL elapsed gets its OWN band, not a NULL bucket: a NULL disappears from the
       -- bucket chart, and 8% of rows silently vanishing from a chart is the failure mode
       -- the "unresolved values must never vanish" constraint exists to stop.
       --
       -- There is deliberately NO `IS_CLOSED = 1 THEN 0` short-circuit: it made a closed
       -- incident unbreachable and deleted the whole workaround-debt population from breach
       -- reporting. Closing late is a breach.
       CASE WHEN b.ELAPSED_DAYS IS NULL                THEN 'Unknown'
            WHEN b.ELAPSED_DAYS >  b.SLA_DAYS          THEN 'Breached'
            WHEN b.ELAPSED_DAYS >= b.SLA_DAYS * 0.8    THEN '80-100%'
            WHEN b.ELAPSED_DAYS >= b.SLA_DAYS * 0.5    THEN '50-80%'
            ELSE '0-50%' END                                      AS SLA_BUCKET
FROM (
  SELECT f.*,
         f.SLA_DAYS                                         AS SLA,   -- SC011: the workbook
                                                                      -- header is SLA, and
                                                                      -- XlsxReader strips _,
                                                                      -- so SLA_DAYS would
                                                                      -- normalise to SLADAYS
                                                                      -- and fail Level 1.
         NVL(s.NAME, 'Unknown (' || f.STATUS_ID || ')')     AS STATUS,
         NVL(s.IS_CLOSED, 0)                                AS IS_CLOSED,
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
         -- THE single elapsed expression (source line 3). Midnight anchor -> CALENDAR days.
         -- Branches on IS NULL only; close_date = '' takes the ELSE branch and MySQL's
         -- timestampdiff(DAY, d, '') returns NULL -- 371 of 4,627 delivered rows (8%).
         CASE WHEN f.HAS_CLOSE_DATE_ROW = 1 AND f.CLOSE_DATE IS NULL THEN NULL
              ELSE NVL(TRUNC(f.CLOSE_DATE), TRUNC(SYSDATE))
                   - TRUNC(CAST(f.DATE_CREATE AS DATE))
         END                                                                   AS ELAPSED_DAYS,
         CASE WHEN f.RESOLUTION_DATE IS NOT NULL
              THEN TRUNC(f.RESOLUTION_DATE) - TRUNC(CAST(f.DATE_CREATE AS DATE))
         END                                                                   AS TTR_WORKAROUND_DAYS,
         CASE WHEN f.CLOSE_DATE IS NOT NULL
              THEN TRUNC(f.CLOSE_DATE) - TRUNC(CAST(f.DATE_CREATE AS DATE))
         END                                                                   AS TTR_PERMANENT_DAYS
  FROM   INCIDENT_FACT f
  LEFT   JOIN ${mirror}ISSUE_STATUSES s ON s.ID = f.STATUS_ID
  LEFT   JOIN ${mirror}TRACKERS       t ON t.ID = f.TRACKER_ID
  LEFT   JOIN ${mirror}ENUMERATIONS   e ON e.ID = f.PRIORITY_ID AND e.TYPE = 'IssuePriority'
  LEFT   JOIN CFG_STATUS_STAGE_MAP    m ON m.STATUS_ID = f.STATUS_ID
) b;
