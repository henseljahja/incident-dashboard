-- V5: pure data. Roles, menus and the grants between them. No DDL.
--
-- ROLE_PRIORITY is UNIQUE (V4), so a user holding several roles always lands on exactly one
-- route: the lowest number wins. ADMIN=10 is first because an admin who also holds VIEWER
-- must not be dropped onto a view-only landing page.

INSERT INTO APP_ROLE (ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, ROLE_PRIORITY) VALUES ('ADMIN',       'Administrator',    '/ops',       10);
INSERT INTO APP_ROLE (ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, ROLE_PRIORITY) VALUES ('OPS_MANAGER', 'Ops Manager',      '/ops',       20);
INSERT INTO APP_ROLE (ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, ROLE_PRIORITY) VALUES ('EXEC',        'Executive',        '/exec',      30);
INSERT INTO APP_ROLE (ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, ROLE_PRIORITY) VALUES ('WALLBOARD',   'Wallboard',        '/wallboard', 40);
-- VIEWER lands on /ops like the ops roles: it differs from OPS_MANAGER by holding no
-- `export`, NOT by seeing a different page. It was previously never seeded at all, which
-- left the export negative-case test with no account to run as.
INSERT INTO APP_ROLE (ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, ROLE_PRIORITY) VALUES ('VIEWER',      'Viewer (read only)', '/ops',     50);

INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER) VALUES ('incident.ops',       'Operations',    '/ops',       10);
INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER) VALUES ('incident.exec',      'Executive',     '/exec',      20);
INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER) VALUES ('incident.wallboard', 'Wallboard',     '/wallboard', 30);
INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER) VALUES ('incident.admin',     'User Management', '/admin/users', 90);

-- --------------------------------------------------------------------------
-- Grants.
--
-- ADMIN holds EVERY menu at view + export, plus incident.admin:manage. Without this the
-- bootstrap admin logs in to an empty menus[], a null defaultRoute and a fail-closed 403 on
-- every /api/** call — i.e. there is no path to a usable production account.
--
-- `manage` is scoped to incident.admin and nothing else: it governs OUR APP_USER* tables,
-- never Redmine data.
-- --------------------------------------------------------------------------
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.ops',       'view');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.ops',       'export');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.exec',      'view');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.exec',      'export');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.wallboard', 'view');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.wallboard', 'export');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.admin',     'view');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.admin',     'export');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('ADMIN', 'incident.admin',     'manage');

-- Ops: view AND export on the ops surface.
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('OPS_MANAGER', 'incident.ops', 'view');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('OPS_MANAGER', 'incident.ops', 'export');

-- --------------------------------------------------------------------------
-- EXEC and WALLBOARD hold `view` on their OWN menu and on nothing else.
--
-- Task 14 guards /api/metrics/{kpi,trend,top-categories} and /api/freshness with
-- "view on ANY OF incident.ops | incident.exec | incident.wallboard", so incident.exec:view
-- and incident.wallboard:view are each already sufficient for those routes. Granting these
-- roles incident.ops:view "to be safe" would NOT be safe: /api/ops/** is guarded by
-- incident.ops:view, so it would hand the executive and the lobby wallboard the full
-- row-level incident list, customer names included.
-- --------------------------------------------------------------------------
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('EXEC',      'incident.exec',      'view');
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('WALLBOARD', 'incident.wallboard', 'view');

-- VIEWER: incident.ops:view and NOTHING else. The missing `export` row IS the role.
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) VALUES ('VIEWER', 'incident.ops', 'view');
