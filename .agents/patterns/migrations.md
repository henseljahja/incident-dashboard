# migrations

## Intent

Flyway owns **our** schema only. The Redmine mirror (`${mirror}`-prefixed tables) is delivered
by another team and is never migrated, altered or written by this service.

**Surfaces:** `db/migration/V1__config_tables.sql` … `V9__close_clock_and_at_risk.sql` + `R__SeedAdmin.java`.

## Required Checks

- **Never emit DDL against the mirror schema.** Read-only, 1:1, someone else's. A migration
  that touches it is rejected on sight.
- **`V3__mart.sql` is the only place `INCIDENT_FACT`'s columns are enumerated.** The MERGE
  column list is generated from `USER_TAB_COLUMNS`, the DTO is asserted against `V_INCIDENT`,
  and the XLSX header derives from the DTO. `MartSchemaIT.everyFactColumnAppearsInTheMergeStatement`
  enforces it. Adding a column in a second place is the drift this rule exists to stop.
  - **Exception — an additive `ALTER TABLE INCIDENT_FACT ADD` in a later V-migration is allowed**
    (first used by `V9__close_clock_and_at_risk.sql`, `SLA_AT_RISK_DAYS`). It is safe because
    nothing hand-lists the fact's columns downstream: the MERGE is generated from
    `USER_TAB_COLUMNS`, and the DTO/XLSX header read the view catalog. The new column must be
    `DEFAULT … NOT NULL` or nullable (existing rows), the ETL must put the key on every fact
    map (the MERGE binds every catalog column, so a missing key binds NULL), and the addition
    is recorded in `MartSchemaIT.DECLARED_FACT_COLUMNS` in the same change.
- **Restating `V_INCIDENT`** in a later migration is `CREATE OR REPLACE VIEW` with the WHOLE body
  and the `${mirror}` placeholder — never an edit to V3 (applied; Flyway checksums it). Diff the
  restatement against the previous body line by line, and update
  `IncidentQueryIT.DECLARED_VIEW_COLUMNS` in the same change.
- **Oracle DDL auto-commits**, so a multi-statement migration that fails half way cannot roll
  back. Guard each `ALTER … ADD` with a `USER_TAB_COLUMNS` check inside a PL/SQL block (see V9)
  so `flyway repair` + re-run skips what already exists.
- **`${mirror}` in a `.sql` file is a Flyway placeholder, not a Spring property** — different
  substitution mechanisms. `spring.flyway.placeholders.mirror` and the Spring property must
  carry the same value, and it includes its own trailing separator
  (`REDMINE_MIRROR.` prod / `REDMINE_MIRROR_` test).
- **Ordering is load-bearing in tests:** `createMirrorSchema()` completes before Flyway runs,
  because `V3__mart.sql` creates `V_INCIDENT` over the mirror tables. Tests set
  `spring.flyway.enabled=false` and run Flyway once behind a latch.
- **A repeatable migration must override `getChecksum()`.** `BaseJavaMigration` returns `null`;
  null-vs-null compares equal and the migration never re-runs.
- Migrations are **additive**. No destructive DDL on a table holding mart rows without a stated
  rollback note in the plan.
- Tier 2 only — every migration is verified against Testcontainers `gvenzl/oracle-free:23-slim`,
  never a hand-made schema.
