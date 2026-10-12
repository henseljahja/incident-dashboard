-- V6 — remove the `incident.admin` menu, which pointed at a screen that was never built.
--
-- V5 seeded `incident.admin` -> `/admin/users` and granted ADMIN view/export/manage on it.
-- No such route exists in the SPA and no endpoint checks `manage`, so the only observable
-- effect was a "User Management" entry in every administrator's sidebar that navigated to a
-- blank page. The nav is driven entirely by this table (deliberately — the server decides what
-- exists), so the fix belongs here and NOT in the sidebar component: special-casing the route
-- in the client would leave the server still claiming a screen it does not have.
--
-- ROUTE is NOT NULL, so "keep the menu, drop the link" is not expressible without a schema
-- change. Since nothing reads `incident.admin:manage`, the grant is a stub rather than a live
-- authorization boundary, and the row goes with it.
--
-- TO RE-ADD when the user-management screen ships, restore both halves together — a menu row
-- without a matching SPA route reintroduces exactly this bug:
--
--   INSERT INTO APP_MENU (MENU_ID, LABEL, ROUTE, SORT_ORDER)
--        VALUES ('incident.admin', 'User Management', '/admin/users', 90);
--   INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE)
--        VALUES ('ADMIN', 'incident.admin', 'view'), ('ADMIN', 'incident.admin', 'export'),
--               ('ADMIN', 'incident.admin', 'manage');

DELETE FROM APP_ROLE_PRIVILEGE WHERE MENU_ID = 'incident.admin';
DELETE FROM APP_MENU           WHERE MENU_ID = 'incident.admin';
