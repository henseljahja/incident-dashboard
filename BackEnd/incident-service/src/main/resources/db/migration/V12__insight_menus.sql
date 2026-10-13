-- V12: the RD 22524 dashboard menus. Menu rows and grants only -- no table is altered, no
-- config generation is bumped, so the mart is not re-derived by this migration.
--
-- Six new menus (sidebar order 13-18, after Incident 10 / Problem 11 / Service Request 12):
--   incident.dashboard  /dashboard   home, summary & assignment, aging, category
--   incident.forecast   /forecast    volume forecast
--   incident.alerts     /alerts      live alerts
--   incident.rca        /rca         problem management & root-cause analysis
--   incident.ai         /ai-insights daily summary, Q&A, search, recurring patterns
--   incident.sla        /sla-config  the live SLA policy (read-only)
--
-- Grants are COPIED from each role's incident.ops grants (ADMIN, OPS_MANAGER, VIEWER), exactly as
-- V10 did for Problem / Service Request. EXEC and WALLBOARD get none: the new pages that carry
-- ticket text (aging, RCA, alerts, AI) are row-level, and a TV in a corridor must not read them
-- (V5 rationale). incident.sla is view-only for every role -- the policy is edited in
-- CFG_SLA_POLICY by the DBA, as before.
--
-- Idempotent: every INSERT is guarded by NOT EXISTS.
-- V12-SEED-BEGIN
INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
SELECT 'incident.dashboard', 'Dashboard', '/dashboard', 13 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.dashboard');

INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
SELECT 'incident.forecast', 'Forecast', '/forecast', 14 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.forecast');

INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
SELECT 'incident.alerts', 'Alerts', '/alerts', 15 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.alerts');

INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
SELECT 'incident.rca', 'Problem & RCA', '/rca', 16 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.rca');

INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
SELECT 'incident.ai', 'AI Insights', '/ai-insights', 17 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.ai');

INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
SELECT 'incident.sla', 'SLA Config', '/sla-config', 18 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_MENU WHERE MENU_ID = 'incident.sla');

INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE)
SELECT p.ROLE_ID, m.MENU_ID, p.PRIVILEGE
  FROM APP_ROLE_PRIVILEGE p
 CROSS JOIN (SELECT 'incident.dashboard' AS MENU_ID FROM DUAL
             UNION ALL SELECT 'incident.forecast' FROM DUAL
             UNION ALL SELECT 'incident.alerts' FROM DUAL
             UNION ALL SELECT 'incident.rca' FROM DUAL
             UNION ALL SELECT 'incident.ai' FROM DUAL
             UNION ALL SELECT 'incident.sla' FROM DUAL) m
 WHERE p.MENU_ID = 'incident.ops'
   AND p.ROLE_ID IN ('ADMIN', 'OPS_MANAGER', 'VIEWER')
   AND (m.MENU_ID <> 'incident.sla' OR p.PRIVILEGE = 'view')
   AND NOT EXISTS (SELECT 1 FROM APP_ROLE_PRIVILEGE x
                    WHERE x.ROLE_ID = p.ROLE_ID
                      AND x.MENU_ID = m.MENU_ID
                      AND x.PRIVILEGE = p.PRIVILEGE);
-- V12-SEED-END
