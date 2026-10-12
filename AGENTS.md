# Agent Instructions — Incident Dashboard

Canonical agent-facing instruction file for `mti/incident-dashboard`.

Read-only ITIL incident/problem/service-request dashboard over a near-real-time Oracle
mirror of the MTI Redmine (MySQL) database. **Redmine is the write side, permanently.**
This system never mutates incident data — every "act on this" path deep-links to Redmine.

---

## Data contract (fixed, not negotiable)

A separate team streams Redmine → Oracle via Kafka, near real time. We start at the Oracle
table boundary; the pipeline is not ours to change or debug.

- **Delivered:** a raw 1:1 mirror of exactly the 7 tables in `docs/reference/ddl.sql` —
  `issues`, `issue_statuses`, `enumerations`, `trackers`, `custom_values`, `journals`,
  `journal_details`. Read-only to us.
- **NOT delivered:** `custom_field_enumerations`, `users`, `email_addresses`, `projects`.
  - `projects` is a dead join in the source report — genuinely unneeded.
  - The other three are needed for human labels. We own local reference tables instead
    (`REF_CUSTOM_FIELD_ENUM`, `REF_USER`) and resolve labels behind an interface so a
    future stream of the real tables is a config swap, not a redesign.
- **Deletes** arrive as status changes, never row removals. No tombstone handling.
- **Our own writable schema** holds the mart, reference and RBAC tables.

Consequence to keep front of mind: custom fields 41/77/93/94/101/349 arrive as raw
enumeration IDs (`'160'`, `'48'`), NOT labels. Unresolved IDs must surface as
`Unknown (<id>)` in the UI — never silently dropped from a chart.

## Hardcoded Redmine IDs are configuration, not code

Custom-field IDs (41, 77, 93, 94, 101, 105, 106, 110, 349), journal prop_keys (115, 116,
126) and the 40+ `issue_statuses` rows must live in config tables, never in a Java
`switch`. Redmine admins add fields and statuses without telling us; an unmapped value
must show up as "Unmapped" in the UI rather than vanishing from an aggregate.

## Stack

stack: [java, vue]

## Test Commands

test_commands:
  tier1: "(cd BackEnd/incident-service && ./mvnw -q test) && (cd FrontEnd/incident-dashboard && pnpm type-check && pnpm lint && pnpm test --run)"
  tier2: "(cd BackEnd/incident-service && ./mvnw -q verify)"
  tier3: "(cd FrontEnd/incident-dashboard && pnpm test:e2e)"

## Substrate

substrate_ready: true
# Flipped by Task 5 after Phase -1 landed both sub-projects and tier1 was observed
# to exit 0. tier2 is SELF-CONTAINED: failsafe drives Testcontainers directly, so no
# docker-compose stack need be up and it never touches BackEnd/local-infra's volume.
# A developer's local dev data survives a test run — this is what satisfies the
# isolated-stack invariant in ~/Project/CLAUDE.md.
# tier3 fails on a missing playwright.config.ts until Task 23. That is the honest signal.
isolated_stack_file: "BackEnd/incident-service/src/test/java/id/co/yokke/incidentdashboard/support/OracleTestContainer.java"
tier3_driver: "playwright-mcp"

## Git Rules

- Never add `Co-Authored-By: Claude` or any AI authorship trailer.
- Commit messages use only the human author's attribution per `git config`.

## Data Sensitivity

`docs/reference/` holds production-derived Redmine exports — customer names, staff names,
incident descriptions. Keep local. Never paste rows into external services.
