# Test fixtures — Redmine mirror tables

CSV fixtures loaded into the Testcontainers Oracle instance by
`id.co.yokke.incidentdashboard.support.MirrorFixtureLoader`.

## Regenerating

From `BackEnd/incident-service/`:

```bash
python3 scripts/extract-fixtures.py
```

It reads the dumps in `docs/reference/` and writes `src/test/resources/fixtures/*.csv`.
No arguments needed; both paths are derived from the script's own location. To override:
`python3 scripts/extract-fixtures.py <dump-dir> <out-dir>`.

Current output (2026-08-21):

```
issues:          kept 100/100 rows
issue_statuses:  kept  74/74  rows
enumerations:    kept   4/4   rows
trackers:        kept   5/5   rows
custom_values:   kept   0/23  rows   <-- see "custom_values" below
journals:        kept 437/437 rows
journal_details: kept 1000/1000 rows
```

## What is tracked in git, and why

| File | Tracked? | Why |
|---|---|---|
| `custom_values.csv` | **yes** | Hand-authored and synthetic. Unregenerable — see below. |
| the other six `*.csv` | **no** | Production-derived: real customer names, staff names and incident text. |

`.gitignore` ignores `BackEnd/incident-service/src/test/resources/fixtures/**/*.csv` and then
negates `custom_values.csv`. Verify with `git check-ignore -v <path>`.

A fresh clone therefore has only `custom_values.csv`; run the extractor before `./mvnw verify`.
`MirrorFixtureLoader.load` skips a table whose CSV is missing rather than failing, so the absence
shows up as a row-count assertion failure with a clear message instead of a stack trace.

## `custom_values.csv` is hand-authored

`docs/reference/05. redmine.custom_values.sql` was exported with
`WHERE id IN (<issue ids>)` instead of `WHERE customized_id IN (...)`. Its 23 rows are therefore
arbitrary `custom_values` rows that merely happen to carry those primary keys; **none** of them
belong to the 100 sampled issues. The extractor re-applies the correct filter and prints
`custom_values: kept 0/23 rows`, then refuses to overwrite this file.

The 16 rows here are synthetic, use issue ids that do exist in `issues.csv`, and exist to
exercise specific report semantics:

| issue | fields | exercises |
|---|---|---|
| 22868 | 93, 94, 77, 101, 41, 349, 105=`2026-07-01`, 106=`2026-07-01` | fully populated → **Permanent Solution** |
| 22878 | 105=`` (empty string), 106=`2026-07-02` | close blank + resolution set → **Workaround Solution** |
| 22882 | 105=`2026-07-09`, 106=`2026-07-03` | close > resolution → **Workaround & Pemanent Solution** |
| 22885 | 93=`999999`, 105=`not-a-date`, 106=SQL NULL | unresolvable enum → `Unknown (999999)`; unparseable date → quarantine |
| 22891 | 110=`2026-07-05` | migration date only → **Unclassified** in the heatmap |

Row `900015` (22885 / field 106 = SQL NULL) is not in the original task table; it was added
because the NULL-vs-empty-string round-trip test needs a genuine NULL to contrast against
22878's empty string. Semantically it is equivalent to the field being absent.

## NULL vs. the empty string — the one detail that matters

`custom_values.value` is a MySQL `longtext`, and the report's SOLUTION classifier and AGING_NEW
branch differently on SQL NULL and `''`. Plain CSV renders both as an empty field, so:

* the extractor writes the sentinel `\N` for SQL NULL;
* a genuinely empty field means the empty string;
* `MirrorFixtureLoader.bind()` reverses the mapping, and additionally maps an empty field to
  SQL NULL for **numeric and date** columns (MySQL has no zero-length number) while preserving
  it for **CLOB** columns.

### What Oracle actually does with a zero-length CLOB

Oracle treats a zero-length character string as NULL. `INSERT ... VALUES ('')` into a CLOB, and
`PreparedStatement.setString(i, "")` against a CLOB column, both store **NULL** — the distinction
we went to the trouble of encoding in CSV would be destroyed on write.

Oracle *can* hold a non-NULL, zero-length CLOB, but only as an **empty LOB locator**:
`EMPTY_CLOB()` in SQL, or a zero-length temporary `java.sql.Clob` from `Connection.createClob()`
over JDBC. That is what `bind()` uses. The resulting value is `IS NOT NULL` and has
`DBMS_LOB.GETLENGTH(value) = 0`.

Consequently **`WHERE value = ''` can never be used to detect it** — in Oracle that expression is
`value = NULL`, which is never true, for empty locators and NULLs alike. The round-trip test
asserts the real behaviour:

```sql
-- the NULL row (900015)
"VALUE" IS NULL
-- the empty-string row (900009)
"VALUE" IS NOT NULL AND DBMS_LOB.GETLENGTH("VALUE") = 0
```

Any production code ported from the MySQL query's `value IS NULL OR value = ''` test must use
`value IS NULL OR DBMS_LOB.GETLENGTH(value) = 0` on Oracle.

## Environment note: Docker API version

`OracleTestContainer` pins `api.version=1.41`. docker-java, as shipped with Testcontainers
1.19.x, negotiates API 1.32 by default; OrbStack (and any daemon with `MinAPIVersion` >= 1.40)
rejects that with `client version 1.32 is too old`, which Testcontainers surfaces as the
misleading `Could not find a valid Docker environment`. The pin is skipped if `api.version` or
`DOCKER_API_VERSION` is already set, so it can be overridden per environment.

## CSV format notes

* RFC4180: `,` separator, `"` quoting, doubled `""` for a literal quote, embedded newlines allowed.
* `\N` (backslash, capital N) as a whole field means SQL NULL. A source value that is literally
  the two characters `\N` would be indistinguishable; none exist in these dumps.
* Timestamps are `YYYY-MM-DD HH:MM:SS`, dates `YYYY-MM-DD`. MySQL zero dates (`0000-00-00`) have
  no Oracle equivalent and are bound as NULL.

## The two REF_ fixtures (Task 7)

`ref_custom_field_enum.csv` and `ref_user.csv` are **synthetic and tracked in git**, and for a
stronger reason than `custom_values.csv`: `redmine.custom_field_enumerations`, `redmine.users`
and `redmine.email_addresses` were never delivered at all, so there is no export to regenerate
them from. Every name and address in them is machine-generated; none is a real person.

They are not mirror tables. `V1__config_tables.sql` creates `REF_CUSTOM_FIELD_ENUM` and
`REF_USER` unprefixed, and `MirrorFixtureLoader` loads them last, after the mirror rows whose
ids they resolve.

Two deliberate properties, both of which a "tidy-up" would quietly destroy:

* **`999999` is absent from `ref_custom_field_enum.csv`.** `custom_values.csv` points impact
  severity at that id precisely so the `Unknown (999999)` degradation path stays reachable.
* **Every seventh user has no `address`.** That is the row the assignee label rule must fall
  back to `LASTNAME` for; without it, that branch is never exercised.

Production seeds come from `scripts/extract-ref-tables.py`, which reads the delivered workbook.
Its output lands in `target/ref-seed/` and is git-ignored — it carries real names.
