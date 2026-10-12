# Reference artifacts

**Production-derived. Local only.** These files hold real customer names, staff names and
incident descriptions pulled from the MTI Redmine database. `.gitignore` excludes every
`.sql` and `.xlsx` in this directory. Do not commit them, do not paste rows into external
services, do not attach them to tickets.

| File | What it is |
|---|---|
| `ddl.sql` | `CREATE TABLE` for the 7 Redmine tables the report reads. The mirror contract. |
| `Query Detail Redmine Incident, Problem, SR.sql` | **The canonical report query — this is the spec.** Any dashboard or API must reproduce its columns and semantics. The 35-row report column contract in the plan was extracted from it. |
| `Detail Incident, Problem, SR 2025 - 2026.xlsx` | The report as currently delivered to stakeholders. `ExcelParityIT` diffs against its `Query1` sheet. |
| `01.`–`07. redmine.*.sql` | Sample `INSERT` dumps — 100 issues from `2026-07-01` onward plus dependent rows. |

## Loading the dumps

They are HeidiSQL/DBeaver exports with an **empty table name**: ``INSERT INTO `` (...)``.
They will not execute as-is. Rewrite the target table first — the file number is the mapping
(`01.` → `issues`, `06.` → `journals`, …):

```bash
sed "s/INSERT INTO \`\` /INSERT INTO \`issues\` /" "01. redmine.issues.sql" > /tmp/issues.sql
```

## Two known gaps

1. `ddl.sql` and the dumps **omit** `projects`, `users`, `email_addresses` and
   `custom_field_enumerations`, all of which the report query joins. `projects` is a verified
   dead join; the other three are covered by our local `REF_*` tables.
2. `05. redmine.custom_values.sql` was extracted with `WHERE id IN (<issue ids>)` instead of
   `WHERE customized_id IN (...)`. Its 23 rows therefore do **not** correspond to the 100
   sampled issues. Re-extract on `customized_id` before trusting any custom-field column.

`scripts/extract-fixtures.py` (Task 6) converts these dumps into the CSV fixtures the
Testcontainers harness loads.
