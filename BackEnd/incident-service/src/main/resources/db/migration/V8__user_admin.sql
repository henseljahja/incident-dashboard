-- V8 — user management: the screen V6 said had to exist before the menu came back.
--
-- V6 deleted the `incident.admin` menu because it pointed at a route the SPA did not have,
-- and its closing comment set the condition for restoring it: "restore both halves together
-- — a menu row without a matching SPA route reintroduces exactly this bug". Both halves ship
-- in this change (`/admin/users` + the `/api/admin/**` endpoints behind
-- `incident.admin:manage`), so the row comes back here rather than being re-added by hand.
--
-- Written as MERGE / conditional INSERT rather than a bare INSERT: V5 seeds these same rows
-- and V6 deletes them, so on a database provisioned before V6 existed the rows are already
-- gone, while a database restored from a V5-era dump may still have them. A plain INSERT
-- would raise ORA-00001 on the second case and leave the schema un-migratable.

-- --------------------------------------------------------------------------
-- EMAIL — the one field the admin screen shows that APP_USER did not carry.
--
-- Nullable, and deliberately NOT unique: the bootstrap administrator is seeded with no
-- address at all (R__SeedAdmin creates it from an environment variable holding only a
-- password), and a NOT NULL column would fail that seed on a fresh database. Uniqueness is
-- not asserted because USERNAME is already the identity here — an email collision is a data
-- quality question for whoever administers the accounts, not a reason to refuse the write.
-- --------------------------------------------------------------------------
ALTER TABLE APP_USER ADD (EMAIL VARCHAR2(128 CHAR));

MERGE INTO APP_MENU tgt
     USING (SELECT 'incident.admin' AS MENU_ID FROM DUAL) src
        ON (tgt.MENU_ID = src.MENU_ID)
 WHEN NOT MATCHED THEN
      INSERT (MENU_ID, LABEL, ROUTE, SORT_ORDER)
      VALUES ('incident.admin', 'User Management', '/admin/users', 90);

-- `view` renders the sidebar entry and the list; `manage` is the write boundary every
-- /api/admin/** handler checks. They are separate rows so a future read-only auditor role can
-- be granted the former without the latter — the split is the point, not ceremony.
INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE)
SELECT 'ADMIN', 'incident.admin', 'view' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_ROLE_PRIVILEGE
                    WHERE ROLE_ID = 'ADMIN' AND MENU_ID = 'incident.admin' AND PRIVILEGE = 'view');

INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE)
SELECT 'ADMIN', 'incident.admin', 'export' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_ROLE_PRIVILEGE
                    WHERE ROLE_ID = 'ADMIN' AND MENU_ID = 'incident.admin' AND PRIVILEGE = 'export');

INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE)
SELECT 'ADMIN', 'incident.admin', 'manage' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM APP_ROLE_PRIVILEGE
                    WHERE ROLE_ID = 'ADMIN' AND MENU_ID = 'incident.admin' AND PRIVILEGE = 'manage');

-- --------------------------------------------------------------------------
-- ADMIN_AUDIT — ported from yob's AuditTrailService, which writes a row for every
-- CREATE_USER / UPDATE_USER / CHANGE_STATUS_USER. Account administration is the one write
-- path in an otherwise read-only service, so "who granted whom which role, and when" has to
-- be reconstructable after the fact.
--
-- Deliberately NOT foreign-keyed to APP_USER in either direction, for the same reason
-- EXPORT_AUDIT is not (V4): the trail must outlive both the administrator who acted and the
-- account they acted on. A cascade here would erase exactly the rows worth investigating.
-- --------------------------------------------------------------------------
CREATE TABLE ADMIN_AUDIT (
    AUDIT_ID     VARCHAR2(36 CHAR)  NOT NULL,
    ACTION       VARCHAR2(32 CHAR)  NOT NULL,
    ACTOR        VARCHAR2(64 CHAR)  NOT NULL,
    TARGET       VARCHAR2(64 CHAR)  NOT NULL,
    DETAIL       VARCHAR2(512 CHAR),
    OCCURRED_AT  TIMESTAMP(6)       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ADMIN_AUDIT_PK PRIMARY KEY (AUDIT_ID),
    CONSTRAINT ADMIN_AUDIT_ACTION_CK CHECK (
        ACTION IN ('CREATE_USER', 'UPDATE_USER', 'CHANGE_STATUS_USER', 'RESET_PASSWORD'))
);

CREATE INDEX ADMIN_AUDIT_TARGET_IX ON ADMIN_AUDIT (TARGET, OCCURRED_AT);

-- NOTE ON NUMBERING: this is V8, not V7, because the TEST classpath carries its own
-- V7__seed_test_users.sql. Flyway scans main and test resources into one history when the
-- integration tests run, and two migrations sharing a version number abort the whole migrate
-- with "Found more than one migration with version 7" — i.e. every IT fails at startup, and
-- the failure names Flyway rather than the collision. Check src/test/resources/db/migration
-- before claiming the next version number.
