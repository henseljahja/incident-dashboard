-- V10: scoped menus. Incident / Problem / Service Request become three menus over one engine.
--
-- Plan: .agents/plans/2026-10-07-stakeholder-notes-p2.md (D1, D2).
--
-- Additive only. One config column, two menus, their grants. V1..V9 are NOT edited.
--
-- D1: `incident.ops` stays the Incident menu (route /ops), relabelled. Every default route, grant,
-- deep link and e2e path keeps working. `incident.problem` (/problem) and `incident.sr`
-- (/service-requests) are new.
--
-- D2: the scope is resolved through config. CFG_TRACKER_SCOPE.SCOPE_GROUP maps a tracker to one
-- of INCIDENT | PROBLEM | SERVICE_REQUEST; the `scope` request param is looked up here, never
-- hard-coded in Java.
--
-- REPAIR SAFETY. Oracle auto-commits DDL, so the ALTER is guarded by a USER_TAB_COLUMNS check
-- (see V9). The seed block below is idempotent: UPDATEs by key, INSERTs guarded by NOT EXISTS.

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = 'CFG_TRACKER_SCOPE' AND COLUMN_NAME = 'SCOPE_GROUP';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE CFG_TRACKER_SCOPE ADD ('
            || 'SCOPE_GROUP VARCHAR2(20 CHAR) NULL, '
            || 'CONSTRAINT CFG_TS_SCOPE_GROUP_CK CHECK '
            || '(SCOPE_GROUP IN (''INCIDENT'', ''PROBLEM'', ''SERVICE_REQUEST'')))';
    END IF;
END;
/

-- --------------------------------------------------------------------------
-- Seed: scope groups, menus, grants, and ONE generation bump.
--
-- The tracker UPDATEs fire the V1 CFG_TRACKER_SCOPE_BUMP trigger once per row (5 rows -> 5
-- bumps). The count is an artefact of how the seed is written, so the block records the
-- generation first and leaves it at exactly that + 1 (the V9 pattern).
--
-- Grants are COPIED from each role's incident.ops grants -- ADMIN (view, export), OPS_MANAGER
-- (view, export), VIEWER (view). EXEC and WALLBOARD hold no incident.ops and get none: handing
-- them the new menus would hand a TV in a corridor the row-level lists (V5 rationale).
--
-- The markers are load-bearing: the V10 IT re-executes the text between them.
-- --------------------------------------------------------------------------
-- V10-SEED-BEGIN
DECLARE
    v_generation NUMBER;
BEGIN
    SELECT GENERATION INTO v_generation FROM CFG_VERSION WHERE SINGLETON = 'X';

    UPDATE CFG_TRACKER_SCOPE
       SET SCOPE_GROUP = CASE TRACKER_ID
                             WHEN  7 THEN 'INCIDENT'
                             WHEN 41 THEN 'PROBLEM'
                             WHEN 16 THEN 'SERVICE_REQUEST'
                             WHEN 32 THEN 'SERVICE_REQUEST'
                             WHEN 33 THEN 'SERVICE_REQUEST'
                         END
     WHERE TRACKER_ID IN (7, 41, 16, 32, 33);

    UPDATE APP_MENU SET LABEL = 'Incident' WHERE MENU_ID = 'incident.ops';

    INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
    SELECT 'incident.problem', 'Problem', '/problem', 11 FROM DUAL
     WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.problem');

    INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
    SELECT 'incident.sr', 'Service Request', '/service-requests', 12 FROM DUAL
     WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.sr');

    INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE)
    SELECT p.ROLE_ID, m.MENU_ID, p.PRIVILEGE
      FROM APP_ROLE_PRIVILEGE p
     CROSS JOIN (SELECT 'incident.problem' AS MENU_ID FROM DUAL
                 UNION ALL
                 SELECT 'incident.sr' FROM DUAL) m
     WHERE p.MENU_ID = 'incident.ops'
       AND p.ROLE_ID IN ('ADMIN', 'OPS_MANAGER', 'VIEWER')
       AND NOT EXISTS (SELECT 1 FROM APP_ROLE_PRIVILEGE x
                        WHERE x.ROLE_ID = p.ROLE_ID
                          AND x.MENU_ID = m.MENU_ID
                          AND x.PRIVILEGE = p.PRIVILEGE);

    UPDATE CFG_VERSION
       SET GENERATION = v_generation + 1,
           UPDATED_AT = SYSTIMESTAMP
     WHERE SINGLETON = 'X';
END;
/
-- V10-SEED-END
