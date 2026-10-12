-- incident-delta.sql -- the ETL's read side (Task 11).
--
-- THE JOIN SEMANTICS BELOW ARE TRANSCRIBED FROM THE SOURCE REPORT
-- ("docs/reference/Query Detail Redmine Incident, Problem, SR.sql"). Do not "simplify" them;
-- each one reproduces a column the delivered workbook already has.
--
-- THREE THINGS THIS FILE EXISTS TO ENFORCE
--
-- 1. NO REDMINE ID IS EVER WRITTEN HERE. The source query hardcodes tracker ids
--    (7/16/32/33/41), journal prop keys (115/116/126, 'status_id') and custom field ids
--    (41/77/93/94/101/105/106/110/349). Every one of them is a JOIN against a CFG_* table
--    instead, so a Redmine admin's change is a config edit rather than a redeploy.
--    Grep this file for a bare integer literal: there is none.
--
-- 2. NO `= ''`. On Oracle `x = ''` IS `x = NULL` and is never true -- see
--    .agents/artifacts/2026-08-20-incident-dashboard/oracle-empty-clob-finding.md. The
--    NULL-vs-empty distinction the report's SOLUTION classifier needs is carried by ROW
--    PRESENCE (a custom_values row exists at all) plus DBMS_LOB.GETLENGTH, never by a
--    comparison against a literal empty string.
--
-- 3. THE CURSOR IS A KEYSET, NOT AN OFFSET. issues.updated_on is TIMESTAMP(0) -- second
--    precision -- so many rows routinely share one value. A bare `> :lastTs` either re-reads
--    or skips that whole group; combined with the run's 300s rewind it does not terminate at
--    all. The cursor is therefore (UPDATED_ON, ID), ordered by both, and the rewind is
--    applied by the CALLER to the first chunk only.
--
-- ${mirror} is substituted from the Spring property `incident.mirror.schema` when this file
-- is loaded -- a DIFFERENT mechanism from the Flyway placeholder of the same name used in
-- V3__mart.sql. Both must carry the same value. The value carries its own trailing separator
-- (a dot in production, an underscore in tests); never add one.
--
-- Sections are delimited by `--@ <name>` and read by MartRefreshRepository.

--@ scope
-- THE scope predicate pair. `${scope}` is expanded into every other section of this file, so
-- the ETL's read side (`delta`), the reconcile's mirror side (`reconcile-mirror`), the prune
-- (`prune`) and the mirror-lag probe (`mirror-max-updated`) all select from ONE definition of
-- "an issue this dashboard reports on". They alias the issues table `i` by contract.
--
-- The delivered report is windowed to "created since Jan 1 of LAST year", compared as %Y%m%d
-- STRINGS exactly as the source does. The window is ROLLING, so rows age out of scope on
-- 1 January and ReconcileUseCase.pruneOutOfScope() removes them: a MERGE over this same delta
-- structurally cannot, because the rows it must delete are the ones this predicate excludes.
i.TRACKER_ID IN (SELECT TRACKER_ID FROM CFG_TRACKER_SCOPE WHERE IS_ACTIVE = 1)
AND    TO_CHAR(i.CREATED_ON, 'YYYYMMDD')
       >= TO_CHAR(ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), -12), 'YYYYMMDD')

--@ delta
-- Every in-scope issue touched since the cursor. Custom values and journal-derived props are
-- fetched per chunk by the two queries below, because both need per-field parsing (and, for
-- dates, quarantine) that SQL should not be doing.
SELECT i.ID                             AS NO_INCIDENT,
       i.TRACKER_ID                     AS TRACKER_ID,
       i.SUBJECT                        AS SUBJECT,
       i.CREATED_ON                     AS DATE_CREATE,
       TO_CHAR(i.CREATED_ON, 'YYYYMM')  AS CREATE_MONTH,
       i.UPDATED_ON                     AS UPDATED_ON,
       -- The keyset's first key AND the value stamped into SOURCE_UPDATED_ON (which is NOT
       -- NULL on the fact). An issue with no updated_on falls back to its creation time
       -- rather than dropping out of every chunk forever.
       NVL(i.UPDATED_ON, i.CREATED_ON)  AS CURSOR_TS,
       i.CLOSED_ON                      AS CLOSED_ON,
       i.STATUS_ID                      AS STATUS_ID,
       i.PRIORITY_ID                    AS PRIORITY_ID,   -- id only; the NAME is joined live
                                                          -- by V_INCIDENT so a Redmine rename
                                                          -- needs no re-ETL
       i.PARENT_ID                      AS PARENT_ID,
       i.DONE_RATIO                     AS DONE_RATIO,
       i.AUTHOR_ID                      AS AUTHOR_ID,
       i.ASSIGNED_TO_ID                 AS ASSIGNED_TO_ID,
       js.FIRST_CHANGE_AT               AS FIRST_STATUS_CHANGE_AT,
       js.LAST_CHANGE_AT                AS LAST_DATE_STATUS,
       ls.NAME                          AS LAST_STATUS
FROM   ${mirror}ISSUES i
-- First and last status transition, plus the last status VALUE, in one pass over the
-- journals. Matching on (PROPERTY, PROP_KEY) as a PAIR is load-bearing: a custom field can
-- itself be keyed 'status_id', and reading it as the status attribute corrupts the lifecycle
-- history. CFG_JOURNAL_PROP_MAP supplies both halves.
LEFT   JOIN (
         SELECT jd.JOURNALIZED_ID                        AS JOURNALIZED_ID,
                MIN(jd.CREATED_ON)                       AS FIRST_CHANGE_AT,
                MAX(jd.CREATED_ON)                       AS LAST_CHANGE_AT,
                -- LAST VALUE WINS -- the source report's semantics, preserved exactly.
                MAX(jd.STATUS_VALUE) KEEP (DENSE_RANK LAST
                    ORDER BY jd.CREATED_ON, jd.JOURNAL_ID, jd.DETAIL_ID) AS LAST_STATUS_VALUE
         FROM   (
                  SELECT j.JOURNALIZED_ID              AS JOURNALIZED_ID,
                         j.CREATED_ON                  AS CREATED_ON,
                         j.ID                          AS JOURNAL_ID,
                         d.ID                          AS DETAIL_ID,
                         -- journal_details.value is a CLOB; a CLOB cannot be aggregated, and
                         -- comparing it to a status id as TEXT (below) also avoids TO_NUMBER
                         -- blowing up on a non-numeric journal value.
                         DBMS_LOB.SUBSTR(d.VALUE, 30)  AS STATUS_VALUE
                  FROM   ${mirror}JOURNALS j
                  JOIN   ${mirror}JOURNAL_DETAILS d ON d.JOURNAL_ID = j.ID
                  JOIN   CFG_JOURNAL_PROP_MAP c     ON c.PROP_KEY = d.PROP_KEY
                                                   AND c.PROPERTY = d.PROPERTY
                                                   AND c.VALUE_KIND = 'STATUS'
                                                   AND c.IS_ACTIVE = 1
                  WHERE  j.JOURNALIZED_TYPE = 'Issue'
                ) jd
         GROUP  BY jd.JOURNALIZED_ID
       ) js ON js.JOURNALIZED_ID = i.ID
LEFT   JOIN ${mirror}ISSUE_STATUSES ls ON TO_CHAR(ls.ID) = js.LAST_STATUS_VALUE
-- Scope: tracker set + rolling report window, substituted from the `scope` section below.
-- It is spelled ONCE, there, because the Task 12 reconcile compares mirror against fact and
-- MUST compare the same population; a second, drifted copy of these two predicates reports
-- drift on every month forever. Never re-inline them here.
WHERE  ${scope}
-- The keyset. Oracle has no row-value `>` comparison, so it is spelled out.
AND    (   NVL(i.UPDATED_ON, i.CREATED_ON) >  CAST(? AS TIMESTAMP(0))
        OR (NVL(i.UPDATED_ON, i.CREATED_ON) = CAST(? AS TIMESTAMP(0)) AND i.ID > ?))
-- Month scope for refreshMonth(); NULL (the incremental path) matches every month.
AND    TO_CHAR(i.CREATED_ON, 'YYYYMM')
       = NVL(CAST(? AS VARCHAR2(6 CHAR)), TO_CHAR(i.CREATED_ON, 'YYYYMM'))
ORDER  BY NVL(i.UPDATED_ON, i.CREATED_ON), i.ID
FETCH  FIRST ? ROWS ONLY

--@ custom-values
-- custom_values has NO timestamp and is UPDATEd in place, so it cannot be deltaed on its own:
-- for every issue in the chunk we re-read ALL of its custom values.
--
-- The join to CFG_CUSTOM_FIELD_MAP is a LEFT join ON PURPOSE. Two reasons:
--   * an unmapped custom field still has to contribute to CV_DIGEST, or editing one would be
--     invisible to the MERGE's content predicate;
--   * ROW PRESENCE, not the value, is what HAS_CLOSE_DATE_ROW / HAS_RESOLUTION_ROW record.
--
-- VALUE_LEN carries the Oracle empty-CLOB distinction the value itself cannot: a zero-length
-- LOB locator and a SQL NULL both read back as a NULL VARCHAR2 through DBMS_LOB.SUBSTR.
SELECT cv.CUSTOMIZED_ID                        AS CUSTOMIZED_ID,
       cv.CUSTOM_FIELD_ID                      AS CUSTOM_FIELD_ID,
       m.LOGICAL_NAME                          AS LOGICAL_NAME,
       m.TARGET_COLUMN                         AS TARGET_COLUMN,
       m.FIELD_KIND                            AS FIELD_KIND,
       DBMS_LOB.SUBSTR(cv.VALUE, 400)          AS VALUE_TEXT,
       NVL(DBMS_LOB.GETLENGTH(cv.VALUE), 0)    AS VALUE_LEN,
       -- CV_DIGEST, computed by ORACLE (never in Java -- ORA_HASH is not reproducible there,
       -- and a Java-side digest would report false drift on every reconcile). This is the
       -- canonical expression declared in V3__mart.sql, evaluated here as an analytic so it
       -- arrives in the SAME round trip as the values it summarises.
       NVL(SUM(ORA_HASH(cv.ID || '|' || cv.CUSTOM_FIELD_ID || '|'
                        || NVL(DBMS_LOB.SUBSTR(cv.VALUE, 200), '~')))
           OVER (PARTITION BY cv.CUSTOMIZED_ID), 0) AS CV_DIGEST
FROM   ${mirror}CUSTOM_VALUES cv
LEFT   JOIN CFG_CUSTOM_FIELD_MAP m ON m.CUSTOM_FIELD_ID = cv.CUSTOM_FIELD_ID
                                  AND m.IS_ACTIVE = 1
WHERE  cv.CUSTOMIZED_TYPE = 'Issue'
AND    cv.CUSTOMIZED_ID IN (%s)

--@ journal-props
-- Latest value per mapped journal prop -- workaround, rootcause, developer_assigned and
-- anything a future config row adds. This replaces the source query's three hardcoded
-- MAX(journal_id) self-joins with ONE row per (issue, mapped prop), pivoted in Java by
-- TARGET_COLUMN.
--
-- "Last value wins" is the source report's semantics and is preserved exactly: newest
-- journal first, newest detail within a journal as the tie-break.
SELECT JOURNALIZED_ID, LOGICAL_NAME, TARGET_COLUMN, VALUE_KIND, VALUE_TEXT
FROM (
  SELECT j.JOURNALIZED_ID                      AS JOURNALIZED_ID,
         c.LOGICAL_NAME                        AS LOGICAL_NAME,
         c.TARGET_COLUMN                       AS TARGET_COLUMN,
         c.VALUE_KIND                          AS VALUE_KIND,
         DBMS_LOB.SUBSTR(d.VALUE, 2000)        AS VALUE_TEXT,
         ROW_NUMBER() OVER (PARTITION BY j.JOURNALIZED_ID, c.PROP_KEY
                            ORDER BY d.JOURNAL_ID DESC, d.ID DESC) AS RN
  FROM   ${mirror}JOURNAL_DETAILS d
  JOIN   ${mirror}JOURNALS j    ON j.ID = d.JOURNAL_ID
  JOIN   CFG_JOURNAL_PROP_MAP c ON c.PROP_KEY = d.PROP_KEY
                               AND c.PROPERTY = d.PROPERTY
                               AND c.VALUE_KIND <> 'STATUS'
                               AND c.IS_ACTIVE = 1
  WHERE  j.JOURNALIZED_TYPE = 'Issue'
  AND    j.JOURNALIZED_ID IN (%s)
) WHERE RN = 1

--@ reconcile-mirror
-- Task 12's mirror side, grouped per CREATE_MONTH. Counts PLUS two checksums, because a count
-- alone cannot see an edit-in-place:
--
--   ROW_CHECKSUM  over (id, updated_on, status_id) -- the issue row itself;
--   CV_CHECKSUM   over the CANONICAL CV_DIGEST expression declared in V3__mart.sql. This term
--                 is what makes the reconcile a real backstop rather than a second copy of the
--                 watermark's blind spot: `custom_values` has no timestamp and is UPDATEd in
--                 place, so an edited custom value -- or a dropped Kafka event -- leaves every
--                 `issues` column untouched and a checksum over ISSUES alone reports zero drift
--                 forever.
--
-- Both checksums are computed by ORACLE. ORA_HASH is not reproducible in Java, and a Java-side
-- digest would report false drift on every single run.
--
-- NVL(i.UPDATED_ON, i.CREATED_ON) is not a nicety: it is exactly what the `delta` section
-- stamps into INCIDENT_FACT.SOURCE_UPDATED_ON as CURSOR_TS. Comparing against i.UPDATED_ON
-- raw would flag every issue that has never been updated.
SELECT TO_CHAR(i.CREATED_ON, 'YYYYMM')                            AS CREATE_MONTH,
       COUNT(*)                                                   AS MIRROR_COUNT,
       NVL(SUM(ORA_HASH(i.ID || '|'
             || TO_CHAR(NVL(i.UPDATED_ON, i.CREATED_ON), 'YYYYMMDDHH24MISS')
             || '|' || i.STATUS_ID)), 0)                          AS ROW_CHECKSUM,
       NVL(SUM(NVL(cvd.CV_DIGEST, 0)), 0)                         AS CV_CHECKSUM
FROM   ${mirror}ISSUES i
LEFT   JOIN (
         SELECT cv.CUSTOMIZED_ID                                  AS CUSTOMIZED_ID,
                NVL(SUM(ORA_HASH(cv.ID || '|' || cv.CUSTOM_FIELD_ID || '|'
                                 || NVL(DBMS_LOB.SUBSTR(cv.VALUE, 200), '~'))), 0) AS CV_DIGEST
         FROM   ${mirror}CUSTOM_VALUES cv
         WHERE  cv.CUSTOMIZED_TYPE = 'Issue'
         GROUP  BY cv.CUSTOMIZED_ID
       ) cvd ON cvd.CUSTOMIZED_ID = i.ID
WHERE  ${scope}
GROUP  BY TO_CHAR(i.CREATED_ON, 'YYYYMM')

--@ reconcile-fact
-- The fact side of the same comparison. The two checksum expressions must stay term-for-term
-- identical to `reconcile-mirror`'s, over the columns the MERGE stamped from those very
-- values: SOURCE_UPDATED_ON <- CURSOR_TS, CV_DIGEST <- the canonical digest.
--
-- MIN_GENERATION is the third drift term. A CFG_* edit bumps CFG_VERSION.GENERATION; rows
-- carrying an older value were derived under superseded config and are stale BY DEFINITION
-- even though both checksums still agree, because nothing in the mirror moved.
SELECT f.CREATE_MONTH                                             AS CREATE_MONTH,
       COUNT(*)                                                   AS FACT_COUNT,
       NVL(SUM(ORA_HASH(f.NO_INCIDENT || '|'
             || TO_CHAR(f.SOURCE_UPDATED_ON, 'YYYYMMDDHH24MISS')
             || '|' || f.STATUS_ID)), 0)                          AS ROW_CHECKSUM,
       NVL(SUM(NVL(f.CV_DIGEST, 0)), 0)                           AS CV_CHECKSUM,
       NVL(MIN(f.CFG_GENERATION), 0)                              AS MIN_GENERATION
FROM   INCIDENT_FACT f
GROUP  BY f.CREATE_MONTH

--@ prune
-- THE one sanctioned DELETE in this project.
--
-- "Deletes arrive as status changes, never row removals. No tombstone logic." governs MIRROR
-- data and Redmine's lifecycle. This statement removes OUR OWN mart rows that the report no
-- longer covers -- a row whose issue aged out of the rolling window, whose tracker left
-- CFG_TRACKER_SCOPE, or that vanished from the mirror entirely. refreshMonth() cannot do it:
-- it is a MERGE over a delta whose scope predicates EXCLUDE precisely these rows.
--
-- NOT EXISTS against the shared `scope` predicate covers all three cases at once.
DELETE FROM INCIDENT_FACT f
WHERE  NOT EXISTS (
         SELECT 1
         FROM   ${mirror}ISSUES i
         WHERE  i.ID = f.NO_INCIDENT
         AND    ${scope})

--@ mirror-max-updated
-- Mirror lag, for /api/freshness. This is the ONLY way to see the Kafka sync itself dying
-- while our ETL keeps running happily over frozen data -- the watermark advances, LAST_RUN_AT
-- looks healthy, and the dashboard presents hours-old numbers as live.
--
-- SYSDATE, never SYSTIMESTAMP (project convention), and the age is computed HERE rather than
-- in the SPA so every consumer agrees on one clock.
SELECT MAX(NVL(i.UPDATED_ON, i.CREATED_ON))                       AS MAX_SOURCE_TS,
       (SYSDATE - CAST(MAX(NVL(i.UPDATED_ON, i.CREATED_ON)) AS DATE)) * 86400 AS LAG_SECONDS
FROM   ${mirror}ISSUES i
WHERE  ${scope}
