-- V7: test accounts. TEST CLASSPATH ONLY — src/test/resources, never packaged, never shipped.
--
-- Numbered V7 when production migrations stopped at V5. The gap was meant as a reservation
-- — test-only migrations above production's range — and production has since consumed it:
-- V6 and V8 are both main-classpath files, so V8 now runs AFTER this one. That ordering is
-- safe here only because nothing below depends on anything V8 adds (the accounts hold roles,
-- and the role-level `incident.admin:manage` grant is V8's, not a per-user row in this file).
--
-- THE RESERVATION IS DEAD; DO NOT RELY ON IT. Flyway merges both classpath roots into ONE
-- `classpath:db/migration` location, so a duplicate version across the two roots is a hard
-- "Found more than one migration with version" abort at bootstrap — every IT fails at
-- startup with an error naming Flyway rather than the collision. Before claiming a version
-- number in EITHER root, list BOTH. Renumbering this file to restore the gap was considered
-- and rejected: its name is cited by frozen plan, recap and code-review artifacts, and
-- rewriting those records to tidy a filename trades a real audit trail for a cosmetic one.
--
-- The passwords below are PUBLIC test fixtures. They exist so AuthFlowIT (Task 14) and the
-- Playwright specs (Task 23) can actually log in; they never reach a production database
-- because this file is not on the production classpath.
--
--   admin.test      / AdminTest#2026    ADMIN       everything, incl. incident.admin:manage
--   ops.manager     / OpsManager#2026   OPS_MANAGER incident.ops view + export
--   exec.user       / ExecUser#2026     EXEC        incident.exec:view
--   wallboard.user  / Wallboard#2026    WALLBOARD   incident.wallboard:view
--   viewer          / Viewer#2026       VIEWER      incident.ops:view ONLY - the negative
--                                                   case for the export test
--   inactive.user   / Inactive#2026     VIEWER      IS_ACTIVE = 'N'
--   rotate.me       / RotateMe#2026     OPS_MANAGER MUST_CHANGE_PASSWORD = 'Y'
--
-- Hashes were produced with the project's own encoder
-- (org.springframework.security.crypto.bcrypt, cost 10) and verified with checkpw, so a
-- transcription error fails here rather than as a mystery 401 inside AuthFlowIT.

INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('admin.test', '$2a$10$K8.w4CjEYnH/lIEEvm4C0.Q8KUbg9bF2yH3Ec9VPSJLbvJSGnbn9O', 'Test Administrator', 'Y', 'N');

INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('ops.manager', '$2a$10$RjkrWt6elrZQ4vxzcDEY5OtyOGim33JEYRU0H2z4XUc/X1Oa7CBGe', 'Test Ops Manager', 'Y', 'N');

INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('exec.user', '$2a$10$FZxGedoQ7tZhHpiBMcC.guPB01id4KZP4hmJEYxj8iTQjKk6xfa6u', 'Test Executive', 'Y', 'N');

INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('wallboard.user', '$2a$10$ftq07raK08scKX9lGajCaO9Kmuy.vNWVgdSgHM5zsAIXAh0XucC6q', 'Test Wallboard', 'Y', 'N');

-- The whole point of this account: view on incident.ops, and NO export privilege anywhere.
INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('viewer', '$2a$10$JQNnDpJf5iJDmlfiofKKHOOS9ozpNiba/66fY/wn53ke0cklKM0UC', 'Test Viewer', 'Y', 'N');

-- Correct password, deactivated account. Task 14 asserts this returns the SAME generic
-- failure as a wrong password: a distinct "account disabled" reply confirms the username
-- exists and turns /auth/token into a user-enumeration oracle.
INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('inactive.user', '$2a$10$R1IlJGE55Q1sklWB/dcJMuj8pRexdo02EEAAicVeSut8/xhsU/op.', 'Test Inactive User', 'N', 'N');

-- Forced rotation. Logging in SUCCEEDS and yields a token scoped "change-password"; every
-- route but /auth/change-password refuses it. Refusing the login outright would strand the
-- account with no way back.
INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE, MUST_CHANGE_PASSWORD)
VALUES ('rotate.me', '$2a$10$9lpCodpWLBs1XXNKlQ5AXOuudonOEqkt7IzJfVEk8E7XqcFYIkjZe', 'Test Forced Rotation', 'Y', 'Y');

INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('admin.test',     'ADMIN');
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('ops.manager',    'OPS_MANAGER');
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('exec.user',      'EXEC');
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('wallboard.user', 'WALLBOARD');
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('viewer',         'VIEWER');
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('inactive.user',  'VIEWER');
INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES ('rotate.me',      'OPS_MANAGER');
