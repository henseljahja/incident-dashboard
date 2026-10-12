# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A data-extraction workspace for an **incident/problem/service-request dashboard** sourced from a
**Redmine** MySQL database (schema `redmine`). Reference artifacts live under `docs/reference/` (git-ignored, production-derived).
The implementation lives in `BackEnd/incident-service` and `FrontEnd/incident-dashboard`.

- [ddl.sql](docs/reference/ddl.sql) — `CREATE TABLE` for the 7 Redmine tables the report reads.
- [Query Detail Redmine Incident, Problem, SR.sql](docs/reference/Query%20Detail%20Redmine%20Incident,%20Problem,%20SR.sql) — the canonical report query. This is the spec: any dashboard/API must reproduce its columns and semantics.
- `docs/reference/01.`–`07. redmine.*.sql` — sample `INSERT` dumps (100 issues from `2026-07-01` onward) plus their dependent rows.
- `docs/reference/Detail Incident, Problem, SR 2025 - 2026.xlsx` — the report output as currently delivered to stakeholders (the target shape).

## Loading the sample data

The dumps are HeidiSQL/DBeaver-style exports with an **empty table name**: `INSERT INTO `` (...)`.
They will not execute as-is. Rewrite the target table before loading, e.g.:

```bash
sed "s/INSERT INTO \`\` /INSERT INTO \`issues\` /" "docs/reference/01. redmine.issues.sql" > /tmp/issues.sql
```

The file number → table mapping is in each filename (`01.` → `issues`, `06.` → `journals`, …).

Two gaps to expect when running the full query against only these files:

1. `ddl.sql` and the dumps **omit** `projects`, `users`, `email_addresses`, and
   `custom_field_enumerations`, all of which the report query joins. Stub them or pull from the real DB.
2. `05. redmine.custom_values.sql` was extracted with `WHERE id IN (<issue ids>)` instead of
   `WHERE customized_id IN (...)`. Its 23 rows therefore do **not** correspond to the 100 sampled
   issues. Re-extract on `customized_id` before trusting any custom-field column.

## Domain model (Redmine → report)

An "incident" is a row in `issues`. Scope is fixed by tracker:

| tracker_id | name |
|---|---|
| 7 | Incident |
| 16 | SR - Infra |
| 32 | SR - BAU |
| 33 | SR - Patch |
| 41 | (see `docs/reference/04. redmine.trackers.sql`) |

Everything interesting lives outside `issues` and is reconstructed by joins:

- **Custom fields** (`custom_values`, keyed by `customized_id` = issue id, filtered on `custom_field_id`).
  Enum-valued fields join `custom_field_enumerations` to resolve `value` → `name`; date fields are read
  straight from `custom_values.value` as text. Field-id map used by the query:
  `41` type of incident · `77` category · `93` impact severity · `94` urgency severity · `101` institusi ·
  `105` close date · `106` resolution date · `110` migration date · `349` external/internal.
- **Change history** (`journals` + `journal_details`). `prop_key` is either a Redmine attribute name
  (`status_id`, `estimated_hours`, `relates`) or a **numeric custom-field id**: `115` workaround ·
  `116` rootcause · `126` developer assigned. Each of these is pulled as the *latest* entry per issue via
  a `MAX(journal_id)` (or `MAX(created_on)` for status) self-join — preserve that "last value wins"
  semantics in any rewrite.
- **Priority** comes from `enumerations` filtered to `type = 'IssuePriority'` (Low/Medium/High/Critical).

## Derived columns defined by the query — do not re-invent

- **SLA days** from priority name: Low 60 · Medium 14 · High 7 · else (Critical) 3.
- **AGING_NEW** = days from `created_on` to close date, or to `NOW()` if still open.
- **SOLUTION** classifies close date vs. resolution date into
  `Workaround Solution` / `Permanent Solution` / `Workaround & Pemanent Solution` / null.
  Both dates are `longtext`, so the query tests for `''` as well as `NULL` — keep both checks.
- **Date window**: issues created since Jan 1 of *last* year, compared as `%Y%m%d` strings.
- `assigned_to` falls back to `users.lastname` when the assignee has no `email_addresses` row.

Note `DATE_FORMAT(a.created_on, "%Y%-%m%-%d")` in `DATE_CREATE` — the stray `%-` is in the original;
match the existing output rather than silently "fixing" it unless the change is requested.

## Conventions

- MySQL 8 dialect, `utf8mb3`, `redmine.`-qualified table names throughout.
- Column aliases in the report are UPPER_SNAKE; mixed-case in the source query is not meaningful.
- Data here is production-derived (customer names, staff names, incident descriptions). Keep it local —
  do not paste rows into external services or commit them anywhere public.
