-- V2: the seed IS the parity contract.
--
-- Every id below was read out of docs/reference/*.sql, not transcribed from prose. Changing
-- a row here changes the report. See
-- .agents/artifacts/2026-08-20-incident-dashboard/seed-ground-truth.md.

-- The singleton must exist BEFORE the seeds: V1's triggers bump it with an UPDATE, and an
-- UPDATE against zero rows is a no-op, not an error. GENERATION is reset to 0 at the very
-- bottom of this file -- see the note there.
INSERT INTO CFG_VERSION (SINGLETON, GENERATION) VALUES ('X', 0);

-- --------------------------------------------------------------------------
-- CFG_CUSTOM_FIELD_MAP -- 9 rows: 6 ENUM + 3 DATE.
-- Source: the custom_field_id literals in "Query Detail Redmine Incident, Problem, SR.sql".
-- --------------------------------------------------------------------------
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES ( 41, 'type_of_incident',  'TYPE_OF_INCIDENT',  'ENUM', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES ( 77, 'category_incident', 'CATEGORY_INCIDENT', 'ENUM', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES ( 93, 'impact_severity',   'IMPACT_SEVERITY',   'ENUM', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES ( 94, 'urgency_severity',  'URGENCY_SEVERITY',  'ENUM', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES (101, 'institusi',         'INSTITUSI',         'ENUM', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES (349, 'external_internal', 'EXTERNAL_INTERNAL', 'ENUM', 1);
-- The three DATE fields are longtext in Redmine and are read as text, then parsed. Their
-- ROW PRESENCE (not their value) is the SOLUTION discriminator -- see
-- oracle-empty-clob-finding.md: on Oracle, `value = ''` is never true.
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES (105, 'close_date',        'CLOSE_DATE',        'DATE', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES (106, 'resolution_date',   'RESOLUTION_DATE',   'DATE', 1);
INSERT INTO CFG_CUSTOM_FIELD_MAP (CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND, IS_ACTIVE) VALUES (110, 'migration_date',    'MIGRATION_DATE',    'DATE', 1);

-- --------------------------------------------------------------------------
-- CFG_JOURNAL_PROP_MAP -- 4 rows.
-- Note the mixed key space: three numeric custom-field ids and one attribute NAME, which is
-- exactly why PROP_KEY is VARCHAR2 and why PROPERTY must be matched alongside it.
-- --------------------------------------------------------------------------
INSERT INTO CFG_JOURNAL_PROP_MAP (PROP_KEY, PROPERTY, LOGICAL_NAME, TARGET_COLUMN, VALUE_KIND, IS_ACTIVE) VALUES ('115',       'cf',   'workaround',         'WORKAROUND',         'TEXT',   1);
INSERT INTO CFG_JOURNAL_PROP_MAP (PROP_KEY, PROPERTY, LOGICAL_NAME, TARGET_COLUMN, VALUE_KIND, IS_ACTIVE) VALUES ('116',       'cf',   'rootcause',          'ROOTCAUSE',          'TEXT',   1);
INSERT INTO CFG_JOURNAL_PROP_MAP (PROP_KEY, PROPERTY, LOGICAL_NAME, TARGET_COLUMN, VALUE_KIND, IS_ACTIVE) VALUES ('126',       'cf',   'developer_assigned', 'DEVELOPER_ASSIGNED', 'USER',   1);
INSERT INTO CFG_JOURNAL_PROP_MAP (PROP_KEY, PROPERTY, LOGICAL_NAME, TARGET_COLUMN, VALUE_KIND, IS_ACTIVE) VALUES ('status_id', 'attr', 'status_change',      'LAST_STATUS',        'STATUS', 1);

-- --------------------------------------------------------------------------
-- CFG_TRACKER_SCOPE -- 5 rows. Names verified against docs/reference/04. redmine.trackers.sql.
-- --------------------------------------------------------------------------
INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) VALUES ( 7, 'Incident',   1);
INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) VALUES (16, 'SR - Infra', 1);
INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) VALUES (32, 'SR - BAU',   1);
INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) VALUES (33, 'SR - Patch', 1);
INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) VALUES (41, 'Problem',    1);

-- --------------------------------------------------------------------------
-- CFG_SLA_POLICY -- 4 rows, ids read from docs/reference/03. redmine.enumerations.sql
-- (type = 'IssuePriority').
--
-- The ids are neither contiguous nor severity-ordered: 19 = Medium sits between 3 = High and
-- 21 = Critical. Anyone tempted to "derive" the ladder from the id ordering would invert the
-- SLA for half the estate. This is the concrete reason the table keys on the id.
-- --------------------------------------------------------------------------
INSERT INTO CFG_SLA_POLICY (PRIORITY_ID, PRIORITY_NAME, SLA_DAYS) VALUES ( 1, 'Low',      60);
INSERT INTO CFG_SLA_POLICY (PRIORITY_ID, PRIORITY_NAME, SLA_DAYS) VALUES (19, 'Medium',   14);
INSERT INTO CFG_SLA_POLICY (PRIORITY_ID, PRIORITY_NAME, SLA_DAYS) VALUES ( 3, 'High',      7);
INSERT INTO CFG_SLA_POLICY (PRIORITY_ID, PRIORITY_NAME, SLA_DAYS) VALUES (21, 'Critical',  3);

-- --------------------------------------------------------------------------
-- CFG_STATUS_STAGE_MAP -- 40 rows. NOT 41.
--
-- docs/reference/02. redmine.issue_statuses.sql holds 74 statuses. Exactly the 40 with
-- id <= 74 belong to the five in-scope trackers; ids 75-108 are HR-recruitment and
-- procurement workflow statuses (Sourcing, Screening, Anwizing, Price Negotiation, Review
-- Legal, ...) on trackers we do not report on. They are deliberately NOT seeded and surface
-- as 'Unmapped'.
--
-- The plan's prose says "41 rows" while its own table lists 40; all 40 names below were
-- diffed against the dump programmatically and match character-for-character. Do not invent
-- a 41st row to reach the prose number.
-- --------------------------------------------------------------------------

-- New
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (20, 'Open',                     'New', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (49, 'New Idea',                 'New', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (51, 'Backlog',                  'New', 0);

-- Triage
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (24, 'Analysis',                 'Triage', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (50, 'Design/Feasibility',       'Triage', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (64, 'Request for Approval',     'Triage', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (67, 'Respond',                  'Triage', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (73, 'Need Approval',            'Triage', 0);

-- In Progress
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (10, 'CCB',                      'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES ( 8, 'Develop',                  'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (52, 'Develop/Rework',           'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (38, 'On Progress',              'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (65, 'Fixing',                   'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (53, 'Internal Test',            'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (68, 'Ready to Test',            'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (72, 'Testing',                  'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES ( 2, 'UAT',                      'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (41, 'Pra-UAT (I)',              'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (43, 'Pre-UAT (P)',              'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (37, 'UAT-RSLV',                 'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (46, 'UAT-DF',                   'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (54, 'User Acceptance',          'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (15, 'RTM',                      'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (61, 'Rollback',                 'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (66, 'Escalate',                 'In Progress', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (71, 'Re-Open (For Some Reason)','In Progress', 0);

-- Blocked
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (13, 'On Hold',                  'Blocked', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (45, 'UAT-On Hold',              'Blocked', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (11, 'PTR',                      'Blocked', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (63, 'PTR with permission',      'Blocked', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (47, 'RTM - Pending',            'Blocked', 0);

-- Resolved.
-- 40 'Closed Wkaround' is is_closed = 1 in Redmine but maps to Resolved, NOT Closed: the
-- incident still owes a permanent fix. That workaround debt is the signal this dashboard
-- exists to surface, so the stage deliberately disagrees with Redmine's is_closed flag.
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (62, 'Warranty',                 'Resolved', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (74, 'Recom To Close',           'Resolved', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (40, 'Closed Wkaround',          'Resolved', 0);

-- Closed
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES ( 5, 'Closed',                   'Closed', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (28, 'Complete',                 'Closed', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (70, 'Completed',                'Closed', 0);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (48, 'Closed (No CCB)',          'Closed', 0);

-- Cancelled -- the only stage excluded from backlog ageing: a dropped or rejected ticket
-- is not work anyone still owes.
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES ( 6, 'Drop',                     'Cancelled', 1);
INSERT INTO CFG_STATUS_STAGE_MAP (STATUS_ID, STATUS_NAME, STAGE, EXCLUDE_FROM_BACKLOG) VALUES (55, 'Reject',                   'Cancelled', 1);

-- --------------------------------------------------------------------------
-- Baseline the generation.
--
-- Every INSERT above fired a V1 trigger, so GENERATION now reads 62. That number is an
-- artefact of how the seed was written, not a fact about the config, and the mart's first
-- build should start from a clean token. Reset it once, here, at the end.
--
-- Safe: CFG_VERSION carries no trigger of its own, so this UPDATE does not re-bump.
-- --------------------------------------------------------------------------
UPDATE CFG_VERSION SET GENERATION = 0, UPDATED_AT = SYSTIMESTAMP WHERE SINGLETON = 'X';
