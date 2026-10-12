-- V4: authentication, RBAC and export auditing — OUR schema only.
--
-- Nothing in this file touches the Redmine mirror (`${mirror}`-prefixed tables). Credentials
-- live here, in this service's own tables: auth is fully standalone, it copies yob's
-- AuthFilter *pattern* and nothing of yob's runtime or user store.
--
-- Boolean-ish columns are CHAR(1) 'Y'/'N' with a CHECK rather than NUMBER(1) 0/1, because
-- MUST_CHANGE_PASSWORD is read as 'Y'/'N' by R__SeedAdmin and by V7__seed_test_users.sql.
-- One spelling across the auth tables beats two.

-- --------------------------------------------------------------------------
-- APP_USER — the credential store.
--
-- PASSWORD_HASH is VARCHAR2(100 CHAR), not 60: a bcrypt hash is exactly 60 characters
-- today, and a column sized to exactly 60 turns the first algorithm/cost migration into a
-- silent ORA-12899 at INSERT time. The headroom is deliberate.
--
-- FAILED_LOGIN_COUNT / LOCKED_UNTIL exist because /auth/token is otherwise an unlimited
-- guessing oracle against a single well-known bootstrap username. LoginUseCase (Task 14)
-- increments the count, sets LOCKED_UNTIL after 5 failures, and clears BOTH on success
-- while stamping LAST_LOGIN_AT.
-- --------------------------------------------------------------------------
CREATE TABLE APP_USER (
    USERNAME             VARCHAR2(64 CHAR)   NOT NULL,
    PASSWORD_HASH        VARCHAR2(100 CHAR)  NOT NULL,
    DISPLAY_NAME         VARCHAR2(128 CHAR),
    IS_ACTIVE            CHAR(1)             DEFAULT 'Y' NOT NULL,
    MUST_CHANGE_PASSWORD CHAR(1)             DEFAULT 'N' NOT NULL,
    FAILED_LOGIN_COUNT   NUMBER(5)           DEFAULT 0   NOT NULL,
    LOCKED_UNTIL         TIMESTAMP(6),
    LAST_LOGIN_AT        TIMESTAMP(6),
    PASSWORD_CHANGED_AT  TIMESTAMP(6),
    CREATED_AT           TIMESTAMP(6)        DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT APP_USER_PK PRIMARY KEY (USERNAME),
    CONSTRAINT APP_USER_IS_ACTIVE_CK CHECK (IS_ACTIVE IN ('Y', 'N')),
    CONSTRAINT APP_USER_MUST_CHANGE_CK CHECK (MUST_CHANGE_PASSWORD IN ('Y', 'N')),
    CONSTRAINT APP_USER_FAILED_COUNT_CK CHECK (FAILED_LOGIN_COUNT >= 0)
);

-- --------------------------------------------------------------------------
-- APP_ROLE — RBAC decides where a user lands. DEFAULT_ROUTE is never hardcoded to '/ops'
-- in the router; the row here is the single source of truth.
--
-- ROLE_PRIORITY makes a multi-role user's landing route DETERMINISTIC: lowest priority
-- number wins. The UNIQUE constraint is what makes that a property of the schema instead of
-- a convention — two roles sharing a priority would reintroduce the ambiguity.
-- --------------------------------------------------------------------------
CREATE TABLE APP_ROLE (
    ROLE_ID       VARCHAR2(32 CHAR)   NOT NULL,
    DISPLAY_NAME  VARCHAR2(128 CHAR)  NOT NULL,
    DEFAULT_ROUTE VARCHAR2(128 CHAR)  NOT NULL,
    ROLE_PRIORITY NUMBER(5)           NOT NULL,
    CREATED_AT    TIMESTAMP(6)        DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT APP_ROLE_PK PRIMARY KEY (ROLE_ID),
    CONSTRAINT APP_ROLE_PRIORITY_UK UNIQUE (ROLE_PRIORITY),
    CONSTRAINT APP_ROLE_ROUTE_CK CHECK (DEFAULT_ROUTE LIKE '/%')
);

-- --------------------------------------------------------------------------
-- APP_MENU — the guarded surfaces. MENU_ID is the dotted code itself
-- ('incident.ops'), not a sequence: the privilege rows and the Task 14 route map both read
-- as prose, and there is no surrogate id to get out of step with the code.
-- --------------------------------------------------------------------------
CREATE TABLE APP_MENU (
    MENU_ID    VARCHAR2(64 CHAR)   NOT NULL,
    LABEL      VARCHAR2(128 CHAR)  NOT NULL,
    ROUTE      VARCHAR2(128 CHAR)  NOT NULL,
    SORT_ORDER NUMBER(5)           DEFAULT 0 NOT NULL,
    CONSTRAINT APP_MENU_PK PRIMARY KEY (MENU_ID)
);

CREATE TABLE APP_USER_ROLE (
    USERNAME   VARCHAR2(64 CHAR) NOT NULL,
    ROLE_ID    VARCHAR2(32 CHAR) NOT NULL,
    GRANTED_AT TIMESTAMP(6)      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT APP_USER_ROLE_PK PRIMARY KEY (USERNAME, ROLE_ID),
    CONSTRAINT APP_USER_ROLE_USER_FK FOREIGN KEY (USERNAME)
        REFERENCES APP_USER (USERNAME) ON DELETE CASCADE,
    CONSTRAINT APP_USER_ROLE_ROLE_FK FOREIGN KEY (ROLE_ID)
        REFERENCES APP_ROLE (ROLE_ID)
);

-- --------------------------------------------------------------------------
-- APP_ROLE_PRIVILEGE — the privilege universe is EXACTLY {view, export, manage}.
--
-- The CHECK is the point: a typo'd privilege ('exprot', 'edit') must fail loudly at INSERT
-- rather than being stored and then silently matching nothing at authorization time — a
-- grant that grants nothing looks identical to a grant that works until someone is denied.
--
-- There is deliberately no 'edit'/'create'/'delete' on incident data: Redmine owns those
-- writes, and the ABSENCE of the privilege is the read-only enforcement.
-- --------------------------------------------------------------------------
CREATE TABLE APP_ROLE_PRIVILEGE (
    ROLE_ID   VARCHAR2(32 CHAR) NOT NULL,
    MENU_ID   VARCHAR2(64 CHAR) NOT NULL,
    PRIVILEGE VARCHAR2(16 CHAR) NOT NULL,
    CONSTRAINT APP_ROLE_PRIVILEGE_PK PRIMARY KEY (ROLE_ID, MENU_ID, PRIVILEGE),
    CONSTRAINT APP_ROLE_PRIV_ROLE_FK FOREIGN KEY (ROLE_ID)
        REFERENCES APP_ROLE (ROLE_ID) ON DELETE CASCADE,
    CONSTRAINT APP_ROLE_PRIV_MENU_FK FOREIGN KEY (MENU_ID)
        REFERENCES APP_MENU (MENU_ID),
    CONSTRAINT APP_ROLE_PRIV_PRIVILEGE_CK CHECK (PRIVILEGE IN ('view', 'export', 'manage'))
);

-- --------------------------------------------------------------------------
-- AUTH_REFRESH_TOKEN — without a store the `jti` in a refresh token is unconsumable, and
-- rotation, logout and revocation are all silently impossible. REVOKED_AT NULL means live.
-- --------------------------------------------------------------------------
CREATE TABLE AUTH_REFRESH_TOKEN (
    JTI        VARCHAR2(64 CHAR) NOT NULL,
    USERNAME   VARCHAR2(64 CHAR) NOT NULL,
    ISSUED_AT  TIMESTAMP(6)      DEFAULT SYSTIMESTAMP NOT NULL,
    EXPIRES_AT TIMESTAMP(6)      NOT NULL,
    REVOKED_AT TIMESTAMP(6),
    CONSTRAINT AUTH_REFRESH_TOKEN_PK PRIMARY KEY (JTI),
    CONSTRAINT AUTH_REFRESH_TOKEN_USER_FK FOREIGN KEY (USERNAME)
        REFERENCES APP_USER (USERNAME) ON DELETE CASCADE
);

CREATE INDEX AUTH_REFRESH_TOKEN_USER_IX ON AUTH_REFRESH_TOKEN (USERNAME);

-- --------------------------------------------------------------------------
-- EXPORT_AUDIT — bulk egress of production customer and staff names must be traceable.
--
-- Created HERE in V4, not alongside the export endpoint, because an export that ships
-- before its audit trail is an untraceable pull: the one incident nobody can reconstruct
-- afterwards. Task 16 writes a row per download.
--
-- FILTER_JSON is a CLOB: the whole filter set is what makes a pull reconstructable, and
-- truncating it to VARCHAR2 loses exactly the wide queries worth auditing.
-- TRUNCATED records that incident.export.max-rows capped the result — a capped export and a
-- complete one are different events.
-- --------------------------------------------------------------------------
CREATE TABLE EXPORT_AUDIT (
    EXPORT_ID    VARCHAR2(36 CHAR) NOT NULL,
    USERNAME     VARCHAR2(64 CHAR) NOT NULL,
    REQUESTED_AT TIMESTAMP(6)      DEFAULT SYSTIMESTAMP NOT NULL,
    FILTER_JSON  CLOB,
    ROW_COUNT    NUMBER(10)        DEFAULT 0 NOT NULL,
    TRUNCATED    CHAR(1)           DEFAULT 'N' NOT NULL,
    CONSTRAINT EXPORT_AUDIT_PK PRIMARY KEY (EXPORT_ID),
    CONSTRAINT EXPORT_AUDIT_TRUNCATED_CK CHECK (TRUNCATED IN ('Y', 'N')),
    CONSTRAINT EXPORT_AUDIT_ROW_COUNT_CK CHECK (ROW_COUNT >= 0)
);

-- Deliberately NOT a foreign key to APP_USER: the audit trail must survive the deletion of
-- the account that made the pull. A cascade here would erase the record of exactly the
-- exports worth investigating.
CREATE INDEX EXPORT_AUDIT_USER_IX ON EXPORT_AUDIT (USERNAME, REQUESTED_AT);
