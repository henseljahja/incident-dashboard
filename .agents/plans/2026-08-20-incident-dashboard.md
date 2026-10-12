---
id: 2026-08-20-incident-dashboard
version: 1
status: completed
approved_at: 2026-08-21
completed_at: 2026-08-23
last_validated_version: 1
last_validated_content_hash: a2bef4c08db964b0866206a2be3a8c19564749b28333658fe8b991c4dced4b00
plan_score: 85
tasks: .agents/tasks/2026-08-20-incident-dashboard.md
parallel_contract: .agents/tasks/2026-08-20-incident-dashboard.parallel.json
---
# Incident Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only ITIL incident/problem/service-request dashboard over a near-real-time Oracle mirror of the MTI Redmine MySQL database, replacing the current Power Query → Excel workflow.

**Architecture:** A Spring Boot service materializes a flat `INCIDENT_FACT` mart from the raw Redmine mirror via a scheduled MERGE, then serves it read-only to a Vue 3 SPA with three role-routed views (ops, wallboard, exec). Redmine remains the permanent write side — this system never mutates incident data; every action path deep-links back to Redmine. All Redmine-specific IDs (custom fields, journal prop keys, statuses) live in config tables, never in Java control flow.

**Tech Stack:** Spring Boot 3.1.5 / Java 17 / Maven / Oracle (ojdbc8 + UCP) / Testcontainers · Vue 3 + TypeScript / Vite / PrimeVue 4 (Aura) / Tailwind 4 / Pinia / TanStack Query / chart.js / vitest / Playwright

**Charter:** none

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Redmine is the write side, permanently.** No POST/PUT/PATCH/DELETE endpoint may ever touch incident data. No forms, no state machine, no "acknowledge" button. Every action affordance is a deep-link to Redmine.
- **The mirror is read-only and fixed.** Only the 7 tables in `docs/reference/ddl.sql` are delivered: `issues`, `issue_statuses`, `enumerations`, `trackers`, `custom_values`, `journals`, `journal_details`. Raw 1:1, near real time. Never issue DDL or DML against the mirror schema.
- **NOT delivered:** `custom_field_enumerations`, `users`, `email_addresses`, `projects`. `projects` is a verified dead join in the source report (joined, never selected) — do not recreate it.
- **Deletes arrive as status changes**, never row removals. No tombstone logic.
- **Unresolved values must never vanish from an aggregate.** Unresolved enum ID → `Unknown (<id>)`. Unmapped status → `Unmapped`. Both are real, countable, filterable values in every chart and table.
- **No Java switch on a Redmine ID.** Custom-field IDs (41, 77, 93, 94, 101, 105, 106, 110, 349), journal prop_keys (115, 116, 126) and the 40+ `issue_statuses` rows are config-table data.
- **MTTD does not exist in this dataset** — we have ticket-creation time, never incident-start time. It must not appear in any API field, chart, label, or doc.
- **JWT signing key comes from configuration.** `incident.jwt.secret` via env. The service must fail fast at startup if it is absent or shorter than 32 bytes. Never hardcode it (yob does, in `JWTUtil.getSignKey()` — that is the anti-pattern being corrected here).
- **Severity never uses the brand primary.** Cyan `#59d8f8`/`#19c9f5` is brand identity. Severity maps to PrimeVue semantic tokens (`danger`/`warn`/`success`/`info`) only.
- **Java package root:** `id.co.yokke.incidentdashboard`. **Layering:** `domain/{model,usecase,config}` + `infrastructure/{rest,datasource}`, matching yob.
- **Oracle has no empty string, and the whole SOLUTION contract depends on one.** Oracle
  stores a zero-length `VARCHAR2`/`CHAR`/`CLOB` as **NULL**: `setString(i, "")` inserts NULL
  and `WHERE VALUE = ''` is never true for any row. `custom_values.value` is `longtext` →
  `CLOB`, so the MySQL `NULL` vs `''` distinction is destroyed by the mirror itself, upstream
  of anything we control. Measured against the delivered workbook: **371 of 4,627 rows (8%)
  carry `close_date = ''`, and 333 of those would flip from `Permanent Solution` to NULL.**
  - **The discriminator is row presence, not value content.** In the source query
    `close_date` comes from a `LEFT JOIN custom_values`: no matching row → `NULL`; a row
    holding `''` → `''`. Oracle collapses the *value* to NULL but the **row still exists**.
    So `CASE WHEN the cv row exists THEN '' ELSE NULL END` reproduces MySQL's semantics
    exactly, with no change required from the sync team.
  - Every reader of cf 105/106 therefore selects **`(row exists, value)`**, never the value
    alone. `DerivedFields.solution` keeps its raw-String signature and receives `""` for
    "row present, empty" and `null` for "no row".
  - **Residual risk to confirm against real data:** a `custom_values` row holding a genuine
    SQL NULL is indistinguishable from `''` under this rule. Count them in UAT
    (`WHERE VALUE IS NULL`) before Task 17 Level 3; if non-zero, the deviation is documented
    rather than silently absorbed.
- **Oracle type mapping** (MySQL → Oracle): `int`→`NUMBER(10)`, `varchar(n)`→`VARCHAR2(n CHAR)`, `longtext`→`CLOB`, `datetime`→`TIMESTAMP(0)`, `date`→`DATE`, `tinyint(1)`→`NUMBER(1)`, `float`→`BINARY_DOUBLE`.
- **Trackers in scope:** 7 Incident · 41 Problem · 16 SR-Infra · 32 SR-BAU · 33 SR-Patch. No other tracker enters the mart. Held in `CFG_TRACKER_SCOPE`, not in SQL literals.
- **One timezone, pinned everywhere: `Asia/Jakarta`.** Redmine's MySQL `datetime` columns carry local wall-clock time with no offset, and the mirror copies them verbatim. Every `SYSTIMESTAMP` comparison in `V_INCIDENT` therefore compares an offset-aware value against an offset-naive one, and a 7-hour skew moves the `TRUNC` day boundary — shifting `AGING_*` by a day for roughly a third of rows. Pin it in **three places or the bug returns**: `TZ=Asia/Jakarta` in `docker-compose.yml`, `.withEnv("TZ","Asia/Jakarta")` on the Testcontainers container, and `-Duser.timezone=Asia/Jakarta` on the service. `V_INCIDENT` uses `SYSDATE`, never `SYSTIMESTAMP`, so the comparison is naive-to-naive on both sides.
  **`SYSDATE` reads the clock of the DATABASE SERVER, not the session and not the client
  JVM** — so the JVM flag alone pins nothing in production. The database host's own timezone
  is the one that matters: set `TZ` on the Oracle container in `docker-compose.yml` and in
  Testcontainers, and for a managed/shared production instance **verify it** rather than
  assume (`SELECT DBTIMEZONE, SYSTIMESTAMP FROM DUAL`) — record the value in the release
  evidence. If it cannot be `Asia/Jakarta`, the view must use
  `CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE) + INTERVAL '7' HOUR` instead, which is
  server-timezone-independent.
- **The SLA ladder is configuration, not a Java switch.** Priority *names* are Redmine-admin-editable, so keying SLA days on the string silently reclassifies incidents from 60-day to 3-day when someone renames a priority. `CFG_SLA_POLICY` keys on `PRIORITY_ID`.
- **Preserve the source report's spelling exactly** where it is user-visible, including `Workaround & Pemanent Solution` (sic). Excel parity means byte parity on labels.

---

## Source-of-truth artifacts

After Task 1 these live in `docs/reference/`:

| File | Role |
|---|---|
| `ddl.sql` | The 7 mirrored tables. The data contract. |
| `Query Detail Redmine Incident, Problem, SR.sql` | **The canonical report spec.** Every mart column derives from it. |
| `01.`–`07. redmine.*.sql` | Sample dumps (100 issues from 2026-07-01) for fixtures. |
| `Detail Incident, Problem, SR 2025 - 2026.xlsx` | The delivered report — the parity target. |

Two known defects in the sample dumps, handled in Task 4:
1. All 7 use `INSERT INTO `` ` (empty table name) — not executable as-is.
2. `05. redmine.custom_values.sql` was extracted with `WHERE id IN (<issue ids>)` instead of `WHERE customized_id IN (...)`. Its 23 rows do **not** correspond to the 100 sampled issues and cannot be used as-is.

### The report column contract (transcribed from the source query, 2026-08-21)

**This table is the parity specification.** It was extracted mechanically from the outer
`SELECT` of `docs/reference/Query Detail Redmine Incident, Problem, SR.sql` (lines 2–8 and
the inner block 13–27), not by inspection. Four rounds of review found column-level parity
bugs one subset at a time — `CREATE_DATE`'s format, `DATE_CREATE`'s type, the `SLA` alias —
because nobody had read those specific lines. **Read from this table instead of re-deriving.**

Rendering rule, stated once: `INCIDENT_FACT` stores **native Oracle types**; the report
formats below are applied at the **DTO boundary**. Storing `%Y%m%d` strings would make
sorting and filtering wrong; rendering them anywhere but one place brings the drift back.

| # | Report alias (workbook header) | Source | Type at the report boundary |
|---|---|---|---|
| SC001 | `No_incident` | `a.id` | number |
| SC002 | `Tracker` | `trackers.name` | text |
| SC003 | `DATE_CREATE` | `DATE_FORMAT(created_on,"%Y%-%m%-%d")` | **string `YYYY-MM-DD`** |
| SC004 | `create_date` | `DATE_FORMAT(created_on,"%Y%m%d")` | **string `YYYYMMDD`** |
| SC005 | `create_month` | `DATE_FORMAT(created_on,"%Y%m")` | string `YYYYMM` |
| SC006 | `updated_on` | `DATE_FORMAT(updated_on,"%Y%m%d")` | **string `YYYYMMDD`**, not a timestamp |
| SC007 | `closed_on` | `DATE_FORMAT(closed_on,"%Y%m%d")` | **string `YYYYMMDD`**, not a timestamp |
| SC008 | `AGING_INCIDENT` | `timestampdiff(day, created_on, now())` | int, **raw-datetime anchor** |
| SC012 | `subject` | `issues.subject` | text |
| SC009 | `status` | `issue_statuses.name` | text |
| SC010 | `priority` | `enumerations.name` | text |
| SC011 | **`SLA`** | the priority ladder | int — **aliased `SLA`, not `SLA_DAYS`** |
| SC013 | `parent_id` | `a.parent_id` | number |
| SC014 | `done_ratio` | `a.done_ratio` | number |
| SC026 | `assigned_to_id` | `a.assigned_to_id` | number |
| SC025 | `author_id` | `a.author_id` | number |
| SC024 | `author` | `email_addresses.address` | text, **NULL when no email row** |
| SC015 | `TYPE_OF_INCIDENT` | cf 41 | text |
| SC016 | `CATEGORY_INCIDENT` | cf 77 | text |
| SC017 | `EXTERNAL_INTERNAL` | cf 349 | text |
| SC018 | `IMPACT_SEVERITY` | cf 93 | text |
| SC019 | `URGENCY_SEVERITY` | cf 94 | text |
| SC020 | `INSTITUSI` | cf 101 | text |
| SC021 | `RESOLUTION_DATE` | cf 106 | text, raw |
| SC022 | `CLOSE_DATE` | cf 105 | text, raw |
| SC023 | `MIGRATION_DATE` | cf 110 | text, raw |
| SC030 | `AGING_NEW` | see below | int **or NULL** |
| SC031 | `SOLUTION` | the four-branch classifier | text or NULL |
| SC032 | `assigned_to` | `address` else `users.lastname` | text |
| SC027 | `Developer_assigned` | `users.firstname` | text |
| SC033 | `LAST_STATUS` | latest status journal | text |
| SC034 | `LAST_DATE_STATUS` | latest status journal date | timestamp |
| SC035 | `AGING_LAST_STATUS` | `timestampdiff(DAY, created_on, NOW())` | int, **raw-datetime anchor** |
| SC028 | `Workaround` | journal cf 115 | text |
| SC029 | `rootcause` | journal cf 116 | text |

> **`XlsxReader` normalises by uppercasing and stripping `_` and spaces**, so the alias must
> match after that transform. `SLA_DAYS` normalises to `SLADAYS` and does **not** match the
> workbook's `SLA` — the always-on Level-1 gate fails on it. Keep `SLA_DAYS` as the *column*
> name if you like, but the view must expose `f.SLA_DAYS AS SLA` and the DTO field must be
> `sla`.

**`AGING_NEW`, verbatim** (source line 3) — the single most-misread expression in the report:

```sql
case when e.close_date is null
     then timestampdiff(DAY, Z.DATE_CREATE, now())
     else timestampdiff(DAY, Z.DATE_CREATE, e.close_date) end
```

Three things follow, and all three are load-bearing:
1. The anchor is `Z.DATE_CREATE` — the **`YYYY-MM-DD` string**, i.e. midnight. This is why
   `AGING_NEW` uses calendar-day arithmetic while `AGING_INCIDENT` and `AGING_LAST_STATUS`,
   anchored on the raw datetime, use elapsed days.
2. It branches on `IS NULL` **only**. With `close_date = ''` the ELSE branch runs and
   `timestampdiff(DAY, d, '')` returns **NULL** — 371 of 4,627 delivered rows (8%).
3. There is no branch for `close_date < resolution_date`; it falls through to NULL.

### Derived-column semantics (read this before Phase 2)

Transcribed from the source query. These are the acceptance contract.

**SLA days** — the source query switches on the priority *name*: `Low`→60, `Medium`→14,
`High`→7, anything else (`Critical`)→3. **We reproduce the ladder but key it on
`PRIORITY_ID`** via `CFG_SLA_POLICY` (Task 7), because priority names are Redmine-admin
editable and a rename would otherwise move incidents from a 60-day to a 3-day SLA silently.
Same numbers, stable key.

**SOLUTION**, from close date (cf 105) and resolution date (cf 106):

**SQL NULL and `''` are NOT interchangeable here.** The source branches, with `AND`
binding tighter than `OR`:

1. `(close IS NULL AND res IS NULL) OR (close = '' AND res = '')` → `NULL`
2. `(close IS NULL AND res IS NOT NULL) OR (close = '' AND res <> '')` → `Workaround Solution`
3. `(res IS NULL AND close IS NOT NULL) OR (res = '' AND close <> '') OR (close = res)` → `Permanent Solution`
4. `(res <> '' AND close > res)` → `Workaround & Pemanent Solution`

In SQL `x = ''` is never true when `x IS NULL`. So **close IS NULL, res = `''`** falls
through branch 1 and matches branch 2 (`res IS NOT NULL` holds for `''`) — yielding
`Workaround Solution` for an incident with *no* resolution date. A source quirk; parity
means reproducing it.

| close_date | resolution_date | SOLUTION |
|---|---|---|
| `NULL` | `NULL` | `NULL` |
| `''` | `''` | `NULL` |
| `NULL` | `''` | **`Workaround Solution`** (quirk — `''` IS NOT NULL) |
| `''` | `NULL` | **`Permanent Solution`** (quirk — `'' <> NULL` is UNKNOWN, so branch 2 fails) |
| `NULL` | set | `Workaround Solution` |
| `''` | set | `Workaround Solution` |
| set | `NULL` | `Permanent Solution` |
| set | `''` | `Permanent Solution` |
| equal | equal | `Permanent Solution` |
| `>` res | set | `Workaround & Pemanent Solution` |
| `<` res | set | `NULL` (source has no branch — preserve) |

Note the asymmetry between rows 3 and 4. `close IS NULL, res = ''` matches branch 2
because `res IS NOT NULL` is TRUE for `''`. But `close = '', res IS NULL` does **not**:
branch 2's second disjunct is `close = '' AND res <> ''`, and `res <> ''` is UNKNOWN when
`res` is NULL — so it falls through to branch 3's `res IS NULL AND close IS NOT NULL`,
giving `Permanent Solution`. Three-valued logic, not symmetry.

The classifier MUST take the raw strings, not parsed dates: a
`solution(LocalDate, LocalDate)` signature structurally cannot represent `NULL` vs `''`
and would silently mis-classify.

**Read-time only** (depend on `NOW()`, must not be materialized): `AGING_NEW`, `AGING_INCIDENT`, `AGING_LAST_STATUS`, and the SLA-breach / at-risk flags.

> **The three aging columns use TWO different anchors. Do not unify them.**
>
> | Column | Source line | Anchor | Oracle form |
> |---|---|---|---|
> | `AGING_NEW` | `timestampdiff(DAY, Z.DATE_CREATE, …)` | `Z.DATE_CREATE` — the `DATE_FORMAT` **string**, i.e. midnight | `TRUNC(d2) - TRUNC(d1)` |
> | `AGING_INCIDENT` | `timestampdiff(day, a.created_on, now())` | raw `datetime` | `TRUNC(d2 - d1)` |
> | `AGING_LAST_STATUS` | `timestampdiff(DAY, a.created_on, NOW())` (subquery `I`) | raw `datetime` | `TRUNC(d2 - d1)` |
>
> `TRUNC(d2) - TRUNC(d1)` is the **calendar-day** difference; `TRUNC(d2 - d1)` is **whole
> days elapsed**, which is what MySQL's `timestampdiff(DAY, …)` returns on raw datetimes.
> They diverge whenever the time-of-day of the later value is earlier than the anchor's:
> created `2026-07-01 23:00`, checked `2026-07-02 01:00` → source says **0**, calendar
> arithmetic says **1**. Applying `TRUNC()` to the anchor of all three columns is an
> off-by-one on two of them.

**Write-time** (materialized): `SLA_DAYS`, `SOLUTION`, `LAST_STATUS`, `LAST_DATE_STATUS`, `WORKAROUND`, `ROOTCAUSE`, `DEVELOPER_ASSIGNED`, `ASSIGNED_TO`, `AUTHOR`, `FIRST_STATUS_CHANGE_AT`, and all six resolved enum labels.

> **Do not "fix" `DATE_FORMAT(a.created_on, "%Y%-%m%-%d")`.** It looks like a typo. MySQL emits an unrecognised specifier's trailing character literally, so `%-` yields `-` and the expression evaluates to `YYYY-MM-DD` — identical to the obvious intent. Changing it changes nothing; misreading it as `%Y%m%d` breaks parity.

---

## File Structure

```
mti/incident-dashboard/
├── AGENTS.md                                  # exists; Task 5 flips substrate_ready
├── .agents/stack-map.md                       # exists; Task 5 updates
├── docs/reference/                            # Task 1 moves artifacts here
├── BackEnd/
│   ├── local-infra/
│   │   ├── docker-compose.yml                 # oracle-free:23-slim
│   │   └── init/01-schemas.sql                # REDMINE_MIRROR + INCIDENT_DASH users
│   └── incident-service/
│       ├── pom.xml
│       └── src/main/java/id/co/yokke/incidentdashboard/
│           ├── IncidentDashboardApplication.java
│           ├── domain/
│           │   ├── config/
│           │   │   ├── security/{JwtService,AuthFilter,SecurityProps}.java
│           │   │   ├── error/{ErrorMapping,GlobalExceptionHandler}.java
│           │   │   └── SchedulingConfig.java
│           │   ├── model/                     # Incident, StatusStage, SolutionType, SlaBucket…
│           │   └── usecase/
│           │       ├── etl/{MartRefreshUseCase,ReconcileUseCase}.java
│           │       ├── label/{EnumLabelResolver,UserLabelResolver}.java
│           │       └── query/{IncidentQueryUseCase,MetricsUseCase}.java
│           └── infrastructure/
│               ├── rest/{IncidentController,MetricsController,AuthController,HealthController}.java
│               │   └── dto/{req,res}/
│               └── datasource/
│                   ├── entity/  repository/  service/
│                   └── label/{LocalRefEnumLabelResolver,MirrorEnumLabelResolver}.java
│       └── src/main/resources/
│           ├── application.yml  application-localdocker.yml
│           └── db/migration/                  # Flyway: V1__…V5__ + R__
└── FrontEnd/incident-dashboard/
    ├── package.json  vite.config.ts
    └── src/
        ├── main.ts  main.css  queryClient.ts
        ├── apis/{client.ts,incidents.ts,metrics.ts,auth.ts}
        ├── stores/{auth.ts,filters.ts}
        ├── utils/{can.ts,usePermission.ts,severity.ts,format.ts}
        ├── components/{kpi,charts,filters,table,common}/
        ├── layout/
        ├── router/  routes/
        └── views/{LoginView,OpsView,WallboardView,ExecView}.vue
```

**Boundary rationale.** `domain/usecase/label` holds the resolver *interfaces*; `infrastructure/datasource/label` holds the two implementations. That split is what makes a future stream of the real `custom_field_enumerations` a config swap rather than a rewrite — the swap point is a `@ConditionalOnProperty` bean, and no use-case code changes.

---

## How to read the task sections

Each task states **Files**, **Interfaces** (the contract other tasks bind to),
**Invariants** (what must be true when it is done, and why), and **Tests** (the assertion
names, which are the acceptance criteria). Implementations are deliberately *not* written
out — `/straventa-tdd` writes them against these contracts.

Two exceptions, where the text below IS the specification and must be reproduced exactly:

- **[SCHEMA]** — `V3__mart.sql`. The only place mart columns are enumerated. Every other
  column list in the codebase (the MERGE, the DTOs, the export) is *derived* from it.
- **[PARITY]** — SQL and seed data transcribed from the source report. Changing any of it
  changes the delivered numbers.

Everything else is intent. Where a task says "port yob's X", read yob's X.

---

## Canonical schema — the single source of truth

`V3__mart.sql` (Task 9) is the **only** place `INCIDENT_FACT`'s columns are listed. Three
other artifacts consume that list and must be regenerated, never hand-maintained, when it
changes:

| Consumer | Task | Rule |
|---|---|---|
| `MERGE_SQL` in `MartRefreshRepository` | 11 | Column list generated from `USER_TAB_COLUMNS`, asserted against it by a test. |
| `IncidentRowDto` | 16 | Field names asserted against `V_INCIDENT`'s columns by a test. |
| The XLSX export header | 18 | Derived from the DTO, not re-typed. |

**A test enforces this**, so the five-place-restate problem cannot come back:

```java
// MartSchemaIT
@Test
void everyFactColumnAppearsInTheMergeStatement() {
    // Reads USER_TAB_COLUMNS for INCIDENT_FACT and asserts MERGE_SQL mentions each one.
    // A column added to V3 without touching the MERGE fails HERE, not in production
    // with a silently-never-populated field.
    assertThat(factColumns()).allMatch(c -> MartRefreshRepository.MERGE_SQL.contains(c));
}
```

---

## User Stories

Three roles, three views, all in v1. Each criterion below names the test that proves it, so the
story and the gate are the same artifact — there is no second, prose-only definition of "done".

### US-1 — Ops engineer: work the queue

*As an ops engineer, I want one filtered, exportable table of every incident, problem and
service request, so that I stop rebuilding the Power Query workbook by hand each week.*

- **AC-1.1** GIVEN the mart holds the delivered workbook's rows, WHEN `ExcelParityIT` runs at
  Level 1, THEN every header in the workbook's `Query1` sheet resolves to a mart field and
  `missingColumns` is empty. *(Task 17)*
- **AC-1.2** GIVEN a filter selection, WHEN the user copies the URL and a colleague opens it,
  THEN the same filter is applied — test `round-trips through the URL`. *(Task 18)*
- **AC-1.3** GIVEN a filtered view showing page 1 of N, WHEN the user exports, THEN the file
  contains the **full filtered set**, not the current page — test `export requests the full
  filtered set, not the current page`. *(Task 18)*
- **AC-1.4** GIVEN an incident whose subject contains markup, WHEN the row renders, THEN the
  markup appears as literal text and no script executes — test `renders a subject containing
  markup as literal text`. *(Task 18)*
- **AC-1.5** GIVEN an export request, WHEN it completes, THEN exactly one `EXPORT_AUDIT` row
  exists for it — test `everyExportWritesAnAuditRow`. *(Task 16)*

### US-2 — Duty manager: an unattended wallboard

*As a duty manager, I want a TV that shows current load without anyone touching it, so that the
floor sees a stalled queue before a customer reports it.*

- **AC-2.1** GIVEN the wallboard is mounted, WHEN the refresh interval elapses, THEN it
  re-queries and, on unmount, clears its timer — test `auto-refreshes on an interval and cleans
  the timer up on unmount`. *(Task 21)*
- **AC-2.2** GIVEN `/api/freshness` reports `stale: true`, WHEN the wallboard renders, THEN a
  prominent stale warning is visible — test `shows a prominent stale warning when freshness
  reports stale`. *(Task 21)*
- **AC-2.3** GIVEN a caller holding only `incident.wallboard:view`, WHEN it requests
  `/api/freshness` and `/api/metrics/kpi`, THEN both return 200, not 403. *(Task 14)*

### US-3 — Service-delivery lead: the monthly picture

*As a service-delivery lead, I want resolution performance over a month with period-over-period
movement, so that I can report improvement without hand-building a deck.*

- **AC-3.1** GIVEN a requested window, WHEN the metrics API responds, THEN it reports **both**
  MTTRs and the workaround debt between them — test
  `reportsBothMttrsAndTheWorkaroundDebtBetweenThem`. *(Task 19)*
- **AC-3.2** GIVEN a requested window, WHEN the comparison period is computed, THEN it has the
  same length as the requested window — test `previousPeriodMirrorsTheRequestedWindowLength`.
  *(Task 19)*
- **AC-3.3** GIVEN incidents with unpopulated impact or urgency, WHEN the matrix renders, THEN
  `Unclassified` and `Unknown` are real, countable cells and the cell totals account for every
  incident — tests `matrixIncludesUnclassifiedAndUnknownAsRealCells` (Task 19) and `accounts
  for every incident in the totals` (Task 20).
- **AC-3.4** GIVEN the exec view loads, WHEN no window is chosen, THEN it defaults to a monthly
  window rather than live — test `defaults to a monthly window, not live`. *(Task 22)*

### Out of scope, stated as a story so it is not re-litigated

*As anyone, I cannot create, edit or close an incident here.* Redmine is the permanent write
side; every action path deep-links back to it. `/api/**` is GET-only — see
[`.agents/patterns/api.md`](../patterns/api.md). A task that adds an incident-write route is a
scope change requiring `/straventa-plan`, not an implementation detail.

---

## Phase -1 — Scaffolding

Nothing exists yet. `AGENTS.md` declares `substrate_ready: false` and its tier-1 command
guards on project existence, so it fails loudly until Task 5.

### Task 1: Relocate reference artifacts

**Files:** create `docs/reference/`; move `ddl.sql`, the report `.sql`, `01.`–`07. redmine.*.sql`
and the `.xlsx` into it; update `CLAUDE.md`'s paths; create `.gitignore` and
`docs/reference/README.md`.

**Interfaces:**
- Produces: `docs/reference/ddl.sql` and `docs/reference/Detail Incident, Problem, SR 2025 - 2026.xlsx`
  — paths Tasks 6 and 17 depend on literally.

**Invariants:**
- **`.gitignore` is written and committed BEFORE anything moves.** `AGENTS.md` § Data
  Sensitivity: the exports carry customer names, staff names and incident descriptions.
  Once they are in git history, removing them is a history rewrite.
- **Patterns must be unanchored, or they silently match nothing.** A pattern containing a
  mid-string `/` is relative to the `.gitignore`'s own directory — the repo root — so
  `src/test/resources/fixtures/*.csv` and `e2e/screenshots/` exclude **nothing**: the real
  paths are under `BackEnd/incident-service/…` and `FrontEnd/incident-dashboard/…`.
  A `.gitignore` that appears to protect production data and does not is worse than none.
  ```gitignore
  /*.sql
  /*.xlsx
  !/ddl.sql                                   # pre-move, repo root
  docs/reference/*.sql
  docs/reference/*.xlsx
  !docs/reference/ddl.sql                     # schema only, no data
  **/src/test/resources/fixtures/*.csv
  !**/fixtures/custom_values.csv              # synthetic, unregenerable — see Task 6
  **/e2e/screenshots/
  .env
  .env.local
  ```
- **`git init` happens here, as Step 0.** `CLAUDE.md` records that this is not yet a git
  repository, and every commit step in this plan assumes it is.
- **But `!fixtures/custom_values.csv` is re-included and IS committed.** It is hand-authored
  precisely because the extractor yields zero usable rows, so **no regeneration command can
  reproduce it** — excluding it means that on a clean checkout `loadAll` has no custom values,
  and every tier-2 IT inheriting `AbstractOracleIT` (Tasks 7, 9, 11, 12, 14, 16, 17, 19)
  fails. No phase could pass its gate on any machine but the original author's. Its five rows
  are synthetic enum ids and dates containing **no production text**, so the exclusion was
  over-broad. The other six CSVs stay excluded — they carry real incident subjects and *are*
  regenerable via `scripts/extract-fixtures.py`.
- `docs/reference/README.md` **is** committed and records what each excluded artifact is
  and where to get it, so a new developer sees what is missing and why.

- [ ] **Step 1:** Write and commit `.gitignore` + `docs/reference/README.md`.
- [ ] **Step 2:** Move the files. Verify with `ls *.sql *.xlsx` that the root is clean.
- [ ] **Step 3:** Update every path in `CLAUDE.md`, including the "Loading the sample data" `sed` example.
- [ ] **Step 4:** Commit, then prove nothing sensitive was staged **repo-wide**. Scoping the
      check to `docs/reference` makes it structurally incapable of catching the fixture CSVs
      or the Playwright screenshots:
      ```bash
      git ls-files | grep -E '\.(sql|xlsx|csv|png)$' \
        | grep -vE '(^|/)(ddl\.sql|custom_values\.csv)$|db/migration/' \
        && echo LEAK || echo clean
      ```

---

### Task 2: Scaffold `BackEnd/local-infra`

**Files:** `BackEnd/local-infra/{docker-compose.yml,init/01-schemas.sql,README.md}`

**Interfaces:**
- Produces: Oracle `gvenzl/oracle-free:23-slim` at `localhost:1522/DMTIDB`; schemas
  `REDMINE_MIRROR` (mirror tables) and `INCIDENT_DASH` (ours, writable).
  Credentials `INCIDENT_DASH/IncidentDash123` — local only. Tasks 3 and 9 connect to exactly this.

**Invariants:**
- **Host port is 1522, not 1521.** `mti/yob/BackEnd/local-infra` already binds 1521 and
  developers run both stacks at once.
- `init/01-schemas.sql` creates `REDMINE_MIRROR` **and issues the actual `GRANT SELECT ... TO
  INCIDENT_DASH` on every mirror table**, reproducing the production split: we can `SELECT`
  the mirror, never write it. Creating the user without the grants leaves the service unable
  to run against local-infra at all, and the missing grant is also the **database-level
  backstop** for the read-only guarantee — `INCIDENT_DASH` holds no INSERT/UPDATE/DELETE on
  mirror tables, so a careless future controller cannot write incident data even if the
  Task 14 structural assertion is bypassed.
- `init/02-mirror-ddl.sql` creates the seven mirror tables in `REDMINE_MIRROR`, Oracle-typed
  per the Global Constraints map. Without it the schema is empty and nothing can start.
- `TZ: Asia/Jakarta` on the container — see Global Constraints.
- `init/` runs only on a *fresh* volume — the README must say that schema changes need
  `docker compose down -v`.

- [ ] **Step 1:** Write the compose file and init SQL.
- [ ] **Step 2:** Bring it up; assert both `REDMINE_MIRROR` and `INCIDENT_DASH` appear in `dba_users`.
- [ ] **Step 3:** Write the README (port rationale, `down` vs `down -v`, connect string). Commit.

---

### Task 3: Scaffold `BackEnd/incident-service`

**Files:** `pom.xml`, `mvnw`, `IncidentDashboardApplication.java`, `HealthController.java`,
`application.yml`, `application-localdocker.yml`, `IncidentDashboardApplicationTests.java`

**Interfaces:**
- Produces: a bootable Spring context on port **8085**; `GET /health` → `{"status":"UP"}`.
  Every later backend task extends this module.

**Invariants:**
- Dependency set mirrors yob's `dashboard-gateway`, minus Elasticsearch/Hazelcast, plus
  Flyway, POI (test), and Testcontainers.
- **`spring-security-crypto` only — NOT `spring-boot-starter-security`.** We need bcrypt;
  the starter's filter chain would fight the hand-rolled `AuthFilter` in Task 14. This is
  also why Task 14 defines its *own* `AccessDeniedException`.
- **Testcontainers module is `oracle-free`**, whose container class is
  `org.testcontainers.oracle.OracleContainer` — *not* `org.testcontainers.containers.OracleContainer`,
  which belongs to the `oracle-xe` module and will not start this image.
- Surefire **excludes** `**/*IT.java`; failsafe **includes** it. Tier 1 is unit-only.
- `incident.jwt.secret` has **no default** (`${INCIDENT_JWT_SECRET:}`). Task 13 fails startup on absent/short.
- **`spring.datasource.password` has no default either.** Shipping `${INCIDENT_DB_PASSWORD:IncidentDash123}` puts a working credential in the production artifact — the exact anti-pattern being corrected for the JWT secret. The local value lives in `application-localdocker.yml`, which is a **dev profile**, not the default one.
- **`spring.autoconfigure.exclude` must list all three** of `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration` and `FlywayAutoConfiguration`. Excluding only the first leaves JPA and Flyway demanding a `DataSource` that no longer exists, and the Task 3 context fails to start.
- `incident.mirror.schema` **carries its own trailing separator** — `REDMINE_MIRROR.` in
  production, `REDMINE_MIRROR_` in tests. Every substitution site concatenates directly and
  NEVER adds a dot of its own.
- `spring.flyway.placeholders.mirror` must be set to the **same value**. `${mirror}` inside
  a `.sql` migration is a *Flyway placeholder*, not a Spring property — different
  substitution mechanisms, and only Flyway's runs at migration time.
- Also configure: `incident.etl.refresh-cron` (2 min), `reconcile-cron` (02:30 daily),
  `watermark-overlap-seconds` (300), `incident.label.enum-resolver` (`local`|`mirror`).

**Tests:** `healthEndpointReportsUp` — with **all three** of `DataSourceAutoConfiguration`,
`HibernateJpaAutoConfiguration` and `FlywayAutoConfiguration` excluded, so the scaffold test
does not require a database. Excluding only the first leaves JPA and Flyway demanding a
`DataSource` and the context fails to start.

- [ ] Step 1: failing test → Step 2: `pom.xml` → Step 3: app + health → Step 4: `application.yml` → Step 5: green → Step 6: commit.

---

### Task 4: Scaffold `FrontEnd/incident-dashboard`

**Files:** `package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.ts`,
`src/{main.ts,main.css,App.vue,queryClient.ts}`, `src/router/index.ts`,
`src/views/OpsView.vue` (shell), `src/utils/severity.ts` + its spec

**Interfaces:**
- Produces: `pnpm dev` on port **5175**; `pnpm type-check`, `pnpm lint`, `pnpm test` green.
- Produces: `severityToken(priority) → 'danger'|'warn'|'info'|'success'|'secondary'`,
  `slaBucketToken(ratio)`, `slaBucket(ratio) → '0-50%'|'50-80%'|'80-100%'|'Breached'`.
  Every later view task imports these.

**Invariants:**
- Dependency set copied from yob's backoffice, minus leaflet/pmtiles/trumbowyg/cropper/jquery.
- The PrimeVue `CustomPreset` (cyan `#59d8f8`/`#19c9f5` primary ramp, Aura base,
  `darkModeSelector: '.dark'`) is copied **verbatim** from
  `mti/yob/FrontEnd/backoffice/src/main.ts`. The two apps must not drift visually.
- **`severity.ts` is the only place a domain severity becomes a visual token**, and
  `'primary'` must be unreachable from it for every input. Brand primary is identity, not meaning.
- **`pnpm lint` is non-mutating.** The auto-fixer is `pnpm fix`, *not* `lint:fix` — because
  `run-s lint:*` globs any `lint:`-prefixed script straight back into the gate, and a gate
  that rewrites the source it is grading can turn a red run green.

**Tests:** `maps Redmine priorities onto PrimeVue semantic tokens` · `maps unknown
priorities to a neutral token, never to primary` · `never returns the brand primary token
for any input` (loops all inputs) · `escalates by aging ratio`.

- [ ] Step 1: failing spec → Step 2: `package.json` → Step 3: `severity.ts` → Step 4: `main.ts` + preset → Step 5: green → Step 6: commit.

---

## Phase 0 — Substrate

### Task 5: Make the tier commands real

**Files:** modify `AGENTS.md` (`test_commands`, `## Substrate`), `.agents/stack-map.md`

**Interfaces:** produces `substrate_ready: true`. Every `/straventa-tdd` phase gate from here depends on tier1/tier2 being executable.

```yaml
test_commands:
  tier1: "(cd BackEnd/incident-service && ./mvnw -q test) && (cd FrontEnd/incident-dashboard && pnpm type-check && pnpm lint && pnpm test --run)"
  tier2: "(cd BackEnd/incident-service && ./mvnw -q verify)"
  tier3: "(cd FrontEnd/incident-dashboard && pnpm test:e2e)"
substrate_ready: true
isolated_stack_file: "BackEnd/incident-service/src/test/java/id/co/yokke/incidentdashboard/support/OracleTestContainer.java"
tier3_driver: "playwright"
```

**Invariants:**
- tier2 is **self-contained**: failsafe drives Testcontainers, so no docker-compose stack
  need be up, and it never touches `BackEnd/local-infra`'s volume. A developer's local data
  survives a test run. This satisfies the isolated-stack invariant in `~/Project/CLAUDE.md`.
- tier3 fails on a missing `playwright.config.ts` until Task 23 — the honest signal.

- [ ] Steps: replace both `AGENTS.md` blocks · drop "not scaffolded yet" from `stack-map.md` · verify tier1 exits 0 · commit.

---

### Task 6: Testcontainers Oracle harness + fixture loader

Makes every later data assertion possible, and is where the two known dump defects get handled.

**Files:** `support/{OracleTestContainer,MirrorFixtureLoader,AbstractOracleIT}.java`,
`scripts/extract-fixtures.py`, `src/test/resources/fixtures/{7 CSVs + README.md}`,
`support/MirrorFixtureLoaderIT.java`

**Interfaces** — inherited by every `*IT` in Tasks 7, 9, 11, 12, 14, 16, 17, 19:
- `OracleTestContainer.INSTANCE` — one container per JVM
- `MirrorFixtureLoader.createMirrorSchema(DataSource)` — **idempotent** (drop-if-exists)
- `MirrorFixtureLoader.loadAll(DataSource)` / `.truncateMirror(DataSource)`
- `AbstractOracleIT`: `dataSource()`, `execute(String)`, `scalar(String)`, `count(String from)`,
  `refreshUseCase()`, `reconcileUseCase()`, `freshnessUseCase()`

**Invariants:**
- `AbstractOracleIT` is `@SpringBootTest` + `@Testcontainers` — Tasks 11/12/16/17/19 need
  real use-case beans, not a bare `DataSource`.
- `@DynamicPropertySource` points the DataSource at the container, sets
  `incident.mirror.schema=REDMINE_MIRROR_` (a **table-name prefix**: Testcontainers gives
  one schema, production uses a real second one), sets a test `incident.jwt.secret`, and
  sets **`spring.flyway.enabled=false`** so Spring cannot run a second, unqualified
  migration behind our back.
- **Ordering is load-bearing: `createMirrorSchema()` must complete BEFORE Flyway runs.**
  `V3__mart.sql` creates `V_INCIDENT` as a view over the mirror tables, so migrating first
  fails with `ORA-00942`. The latch guards the pair as a unit, not each half.
- Flyway is then run **manually, exactly once**, behind a static `bootstrapped` latch, with
  `.placeholders(Map.of("mirror", "REDMINE_MIRROR_"))`. One container is shared by every IT
  class; without the latch the second class dies on `ORA-00955`.
- **`resetAll()` must tolerate mart objects that do not exist yet.** Task 6 is Phase 0 and
  Task 7 is Phase 1, but `INCIDENT_FACT`, `INCIDENT_FACT_REJECT`, `ETL_RECONCILE_LOG` and
  `ETL_WATERMARK` are created by `V3__mart.sql` in **Phase 2**, and both earlier phases are
  declared tier-2 gates. Truncating them unconditionally makes the Phase-0 and Phase-1 gates
  fail on `ORA-00942`. Drive the truncate list from `USER_TABLES` and skip what is absent.
- **The use-case accessors are lazy `ApplicationContext` lookups, not injected fields**, for
  the same reason: `MartRefreshUseCase` and friends do not exist until Tasks 11–12, and an
  injected field fails context startup for every earlier IT.
- `@BeforeEach resetAll()` truncates the seven mirror tables and whichever of `INCIDENT_FACT`,
  `INCIDENT_FACT_REJECT`, `ETL_RECONCILE_LOG` exist, and rewinds `ETL_WATERMARK` if present. Without it the
  second test method in any class hits `ORA-00001` on the ISSUES PK.
- **Every helper closes its `Connection`**, not just its `Statement`. Putting only the
  Statement in try-with-resources leaks the pooled Connection on every call; with
  `resetAll()` running per test that exhausts the Hikari pool and *hangs* tier 2 rather than
  failing it.
- **No `.withReuse(true)`** — it needs per-developer opt-in and carries dirty state into
  tests that assert exact row counts.
- **The CSV round-trip must preserve SQL `NULL` vs `''` in `custom_values.value`.** Plain CSV
  renders both as an empty field, and that is the exact distinction the SOLUTION classifier
  turns on — collapsing it makes the Task 10 quirk tests unreachable through the fixture
  path. The extractor writes a sentinel (`\N`) for SQL NULL and leaves `''` empty; `bind()`
  maps the sentinel to `setNull` and the empty field to a literal empty string.
- `bind()` must also map `""` to SQL NULL for **numeric** columns while preserving it
  verbatim for CLOB columns.
- A fixture round-trip test asserts both survive: after `loadAll`, one row has
  `VALUE IS NULL` and another has `VALUE = ''`.

**The two dump defects** (`scripts/extract-fixtures.py` handles both):
1. Every dump is `INSERT INTO `` (...)` — empty table name. Take the target from the filename.
2. `05. redmine.custom_values.sql` was exported with `WHERE id IN (<issue ids>)` instead of
   `WHERE customized_id IN (...)`. Running the extractor prints `custom_values: kept 0/23
   rows`, confirming empirically that **none** of its rows belong to the sample.

The extractor parses the dumps to CSV and loads via JDBC batch rather than translating
MySQL SQL to Oracle — one parser, seven tables, no dialect translation, no quoting or
datetime-literal edge cases.

Because the real custom_values export is unusable, **hand-author `custom_values.csv`**:

| Issue | cf rows | Exercises |
|---|---|---|
| 22868 | 93=160, 94=164, 77=48, 101=236, 41=27, 349=410, 105=`2026-07-01`, 106=`2026-07-01` | fully populated; close = resolution → `Permanent Solution` |
| 22878 | 105=`` (empty), 106=`2026-07-02` | close empty, resolution set → `Workaround Solution` |
| 22882 | 105=`2026-07-09`, 106=`2026-07-03` | close > resolution → `Workaround & Pemanent Solution` |
| 22885 | 93=`999999`, 105=`not-a-date` | unresolvable enum → `Unknown (999999)`; unparseable date → quarantine |
| 22891 | 110=`2026-07-05` | migration date only, no impact/urgency → `Unclassified` in the heatmap |

**Tests:** `loadsAllSevenMirrorTables` (100 issues, 5 trackers, >40 statuses, 4
enumerations) · `everyCustomValueRowPointsAtAnIssueInTheFixture` (asserts zero orphans —
guards defect 2 from creeping back).

- [ ] Steps: failing IT → extractor → run it and observe `kept 0/23` → hand-author the CSV → harness → loader → green → commit the extractor **and `custom_values.csv`** (synthetic, unregenerable), but **not** the six derived CSVs (production incident text; `fixtures/README.md` documents the regeneration command).
- [ ] **Verify on a clean checkout**, not just locally: `git clean -xdf` a copy, run tier 2, and confirm it still passes. A fixture pipeline that only works on the author's machine is the failure this step exists to catch.

---

## Phase 1 — Schema, config and label resolution

### Task 7: Config and reference tables (Flyway V1–V2)

**Files:** `db/migration/V1__config_tables.sql`, `V2__seed_config.sql`, `ConfigTablesIT.java`

**Interfaces:** produces `CFG_CUSTOM_FIELD_MAP`, `CFG_JOURNAL_PROP_MAP`,
`CFG_STATUS_STAGE_MAP`, `CFG_TRACKER_SCOPE`, `REF_CUSTOM_FIELD_ENUM`, `REF_USER`.
Tasks 8–12 read all six.

**Invariants:**
- **Redmine IDs are data, not code.** Every hardcoded id in the source report becomes a row
  here, so a Redmine admin's change is a config edit rather than a redeploy. Task 11 must
  *read* these tables — see its Invariants.
- `CFG_CUSTOM_FIELD_MAP(CUSTOM_FIELD_ID PK, LOGICAL_NAME UNIQUE, TARGET_COLUMN, FIELD_KIND
  CHECK IN ('ENUM','DATE','TEXT'), IS_ACTIVE)`.
- `CFG_JOURNAL_PROP_MAP(PROP_KEY PK, PROPERTY, LOGICAL_NAME UNIQUE, TARGET_COLUMN,
  VALUE_KIND CHECK IN ('TEXT','USER','STATUS'), IS_ACTIVE)`. `PROPERTY` is
  `journal_details.property` — `'cf'` or `'attr'`. **The distinction matters:** a custom
  field could also be keyed `status_id`.
- `CFG_STATUS_STAGE_MAP(STATUS_ID PK, STATUS_NAME, STAGE CHECK IN ('New','Triage','In
  Progress','Blocked','Resolved','Closed','Cancelled'), EXCLUDE_FROM_BACKLOG)`.
- `CFG_TRACKER_SCOPE(TRACKER_ID PK, LOGICAL_NAME, IS_ACTIVE)` — the five in-scope trackers.
- **`CFG_VERSION(SINGLETON CHAR(1) DEFAULT 'X' PRIMARY KEY CHECK (SINGLETON = 'X'),
  GENERATION NUMBER(10) DEFAULT 0 NOT NULL)`** — one row, ever. `INCIDENT_FACT.CFG_GENERATION`
  and the MERGE and reconcile predicates all key on it, and it previously existed only in
  prose. **The bump is a row-level `AFTER INSERT OR UPDATE OR DELETE` trigger on each `CFG_*`
  table** incrementing `GENERATION`; a "remember to bump it" convention is not enforceable and
  a missed bump is silently a half-mapped mart. Seed generation `0` in V2.
- Test: `aConfigWriteIncrementsTheGeneration`.
- `CFG_SLA_POLICY(PRIORITY_ID PK, PRIORITY_NAME, SLA_DAYS NOT NULL)` — **keyed on the id, not
  the name.** A Redmine admin renaming "Low" to "Low Priority" must not silently move those
  incidents from a 60-day to a 3-day SLA. `SLA_DAYS` is looked up by `PRIORITY_ID`; the name
  is stored for readability only. A priority id absent from this table falls back to **3**
  (the source query's `ELSE`) **and is logged**, because an unrecognised priority quietly
  taking the strictest bucket is a number nobody can explain later.
- `REF_CUSTOM_FIELD_ENUM(CUSTOM_FIELD_ID, ENUM_VALUE_ID, LABEL, POSITION,
  PK (CUSTOM_FIELD_ID, ENUM_VALUE_ID))` — stand-in for `redmine.custom_field_enumerations`.
- `REF_USER(USER_ID PK, ADDRESS, FIRSTNAME, LASTNAME, DISPLAY_NAME)` — stand-in for
  `redmine.users` **and** `redmine.email_addresses`, neither of which is delivered. All four
  value columns are required: Task 8's three label rules read `ADDRESS`, `LASTNAME` and
  `FIRSTNAME` respectively, and omitting any one makes that report column unbuildable.
- Both are swappable via `incident.label.enum-resolver` (Task 8).
- **Both need an owner and a source, and previously had neither** — no task seeded a single
  row into either, while Task 8's three label rules and every enum column depend on them.
  - **Tests:** `ref_custom_field_enum.csv` and `ref_user.csv` join Task 6's fixture set.
    Synthetic and committable on the same argument as `custom_values.csv`, loaded by
    `MirrorFixtureLoader`.
  - **Production:** `scripts/extract-ref-tables.py` builds both from the delivered workbook,
    which carries 4,627 rows of `author` / `assigned_to` / `Developer_assigned` and the six
    enum columns — the labels are literally in the report we are reproducing. Output is
    gitignored (real names); the script is committed. A **Task 7 deliverable**, not a
    deployment afterthought.
  - Every unmapped id stays visible as `Unknown (<id>)`, so a stale seed degrades loudly.

**[PARITY] `V2__seed_config.sql` — the seed data is the contract.**

`CFG_CUSTOM_FIELD_MAP` — 9 rows, 6 ENUM + 3 DATE:

| id | logical_name | target_column | kind |
|---|---|---|---|
| 41 | type_of_incident | TYPE_OF_INCIDENT | ENUM |
| 77 | category_incident | CATEGORY_INCIDENT | ENUM |
| 93 | impact_severity | IMPACT_SEVERITY | ENUM |
| 94 | urgency_severity | URGENCY_SEVERITY | ENUM |
| 101 | institusi | INSTITUSI | ENUM |
| 349 | external_internal | EXTERNAL_INTERNAL | ENUM |
| 105 | close_date | CLOSE_DATE | DATE |
| 106 | resolution_date | RESOLUTION_DATE | DATE |
| 110 | migration_date | MIGRATION_DATE | DATE |

`CFG_JOURNAL_PROP_MAP` — 4 rows:

| prop_key | property | logical_name | target_column | value_kind |
|---|---|---|---|---|
| `115` | cf | workaround | WORKAROUND | TEXT |
| `116` | cf | rootcause | ROOTCAUSE | TEXT |
| `126` | cf | developer_assigned | DEVELOPER_ASSIGNED | USER |
| `status_id` | attr | status_change | LAST_STATUS | STATUS |

`CFG_TRACKER_SCOPE` — 5 rows: 7 Incident · 41 Problem · 16 SR-Infra · 32 SR-BAU · 33 SR-Patch.

**[PARITY] `CFG_SLA_POLICY`** — the SLA ladder from the source query, resolved to the
`enumerations` ids of type `IssuePriority`: Low → **60** · Medium → **14** · High → **7** ·
Critical → **3**. Seed the ids from `docs/reference/03. redmine.enumerations.sql`.

`CFG_STATUS_STAGE_MAP` — 41 rows, transcribed from `docs/reference/02. redmine.issue_statuses.sql`:

| Stage | STATUS_ID → name |
|---|---|
| New | 20 Open · 49 New Idea · 51 Backlog |
| Triage | 24 Analysis · 50 Design/Feasibility · 64 Request for Approval · 67 Respond · 73 Need Approval |
| In Progress | 10 CCB · 8 Develop · 52 Develop/Rework · 38 On Progress · 65 Fixing · 53 Internal Test · 68 Ready to Test · 72 Testing · 2 UAT · 41 Pra-UAT (I) · 43 Pre-UAT (P) · 37 UAT-RSLV · 46 UAT-DF · 54 User Acceptance · 15 RTM · 61 Rollback · 66 Escalate · 71 Re-Open (For Some Reason) |
| Blocked | 13 On Hold · 45 UAT-On Hold · 11 PTR · 63 PTR with permission · 47 RTM - Pending |
| Resolved | 62 Warranty · 74 Recom To Close · **40 Closed Wkaround** |
| Closed | 5 Closed · 28 Complete · 70 Completed · 48 Closed (No CCB) |
| Cancelled *(EXCLUDE_FROM_BACKLOG = 1)* | 6 Drop · 55 Reject |

> **`Closed Wkaround` (40) maps to Resolved, not Closed.** It is `is_closed = 1` in Redmine,
> but the incident still owes a permanent fix. That distinction is exactly the
> workaround-debt signal this dashboard exists to surface.

Statuses appearing later and *not* seeded here surface as `Unmapped` (Task 9). They are
never dropped from an aggregate.

**Tests:** `seedsEveryCustomFieldTheReportUses` (9 total / 6 ENUM / 3 DATE) ·
`mapsEveryKnownStatusToALifecycleStage` (2 Cancelled, ids 6 and 55; **zero** rows outside
the seven stages) · `journalPropMapCoversWorkaroundRootcauseAndDeveloper` ·
`trackerScopeHoldsExactlyTheFiveItilTrackers` ·
`slaPolicyCoversEveryIssuePriorityInTheMirror` (no priority may fall through to the default).

---

### Task 8: Label resolution behind an interface

**Files:** `domain/usecase/label/{EnumLabelResolver,UserLabelResolver}.java`,
`infrastructure/datasource/label/{LocalRefEnumLabelResolver,MirrorEnumLabelResolver,LocalRefUserLabelResolver}.java`,
`EnumLabelResolverTest.java`

**Interfaces:**
- `EnumLabelResolver.resolve(long customFieldId, Long enumValueId) → String`
- `UserLabelResolver.authorLabel(Long userId) → String`
- `UserLabelResolver.assigneeLabel(Long userId) → String`
- `UserLabelResolver.developerLabel(Long userId) → String`
- Constant `EnumLabelResolver.UNCLASSIFIED = "Unclassified"`

**Invariants:**
- **The source resolves three report columns from user identity by three DIFFERENT rules,
  and one `resolve(Long)` cannot express them.** Verified against the delivered workbook:

  | Column | Source | Rule | Workbook evidence |
  |---|---|---|---|
  | `AUTHOR` (SC024) | line 11 | `email_addresses.address`, **no fallback** — NULL when the user has no email row | col Q is 100% email addresses |
  | `ASSIGNED_TO` (SC032) | line 8 | `address` else **`users.lastname`** | col AC: 1,152 of 4,627 (24.9%) are lastname/group values like `01 - Incident Management` |
  | `DEVELOPER_ASSIGNED` (SC027) | line 115 | **`users.firstname`** | col AD is firstnames (`DEV BE - Saut`), never an email |

  With one resolver, whichever rule is implemented makes the other two wrong for most rows.
- **`AUTHOR` reproduces the source's NULL** when there is no email row — it is the one
  exception to the never-null rule below, because parity outranks it and `Unknown (<id>)`
  would be a `valueMismatch`. `ASSIGNED_TO` and `DEVELOPER_ASSIGNED` follow the never-null rule.
- **Enum resolution never returns null or empty.** An aggregate must be able to count every value.
- `enumValueId == null` → `"Unclassified"`. Unresolvable id → `"Unknown (<id>)"`.
  **These are different facts** and the heatmap renders them as different bands: "no value
  was set" is not "we could not resolve the value that was set".
- Interface lives in `domain/usecase/label`, implementations in
  `infrastructure/datasource/label`. That split is what makes a future stream of the real
  `custom_field_enumerations` a config swap rather than a rewrite.
- Selection is `@ConditionalOnProperty("incident.label.enum-resolver")` —
  `local` (default, `matchIfMissing = true`) reads `REF_CUSTOM_FIELD_ENUM`; `mirror` reads
  `<mirror>CUSTOM_FIELD_ENUMERATIONS`. No use-case code changes when it flips.
- The snapshot is refreshed **per ETL run, not per row**.
- `MirrorEnumLabelResolver` **fails fast at startup** with a clear message if the table is
  absent, rather than silently returning `Unknown (…)` for every row in the mart.

**Tests:** `resolvesAKnownEnumValueToItsLabel` · `rendersUnknownIdsVisiblyRatherThanDroppingThem`
· `treatsAbsentValueAsUnclassifiedNotUnknown`.

---

## Phase 2 — The mart

### Task 9: `INCIDENT_FACT`, the reject table, and `V_INCIDENT` (Flyway V3)

**Files:** `db/migration/V3__mart.sql`, `mart/MartSchemaIT.java`

**Interfaces:** produces `INCIDENT_FACT` (write-time), `INCIDENT_FACT_REJECT` (quarantine),
`ETL_WATERMARK`, `ETL_RECONCILE_LOG`, and `V_INCIDENT` (adds read-time columns).
**Every query in Tasks 16–22 reads `V_INCIDENT`, never `INCIDENT_FACT` directly.**

**Invariants:**
- **Nothing depending on `SYSTIMESTAMP` is materialised.** Aging and SLA flags live on the
  view only; materialising them would make every row stale the moment it is written.
- **Dimension *names* are not materialised, only their ids.** `issue_statuses`, `trackers`
  and `enumerations` are 74/5/4 rows and are renamed by Redmine admins *without touching
  `issues.updated_on`* — the watermark would never see it and the mart would serve a stale
  label forever. `V_INCIDENT` joins them live; a rename is correct instantly, with no re-ETL.
- **The fact keeps both the raw enum id and the resolved label.** A later
  `REF_CUSTOM_FIELD_ENUM` fix then re-labels history with an `UPDATE` instead of a full re-ETL.
- **Every join in `V_INCIDENT` is a `LEFT` join** with an `NVL` fallback. An INNER join
  would silently drop any incident whose status or tracker row is missing from the mirror
  (a Kafka gap, or a dimension row deleted in Redmine) — precisely the "unresolved values
  must never vanish from an aggregate" failure the Global Constraints forbid.
- **Indexes may only reference columns this same migration creates.** `STATUS_STAGE` and
  `IS_CLOSED` are view-level, so they are not indexable here.
- **`AGING_NEW`, `SLA_RATIO`, `SLA_BREACHED` and `SLA_BUCKET` all derive from ONE
  `ELAPSED_DAYS` expression that includes the `HAS_CLOSE_DATE_ROW` guard** — implemented as a
  single inline-view column so it is written once, not four times. The 8% `close_date = ''`
  cohort is NULL in all four, matching the workbook.
- **`SLA_RATIO`, `SLA_BREACHED` and `SLA_BUCKET` share one elapsed expression.** Three
  separate formulas made the KPI card and the bucket chart disagree on the same screen, and
  the `IS_CLOSED = 1 THEN 0` short-circuit made a closed incident unbreachable — deleting the
  whole workaround-debt population from breach reporting. Closing late is a breach.
- **`SYSDATE`, never `SYSTIMESTAMP`** — see Global Constraints on timezone.
- Unparseable free-text dates go to `INCIDENT_FACT_REJECT`, never silently nulled. A null
  meaning "no date" and a null meaning "we could not read `31/02/2025`" are different facts,
  and SLA numbers must not quietly absorb the second.

**[SCHEMA] `V3__mart.sql` — the canonical column list. Nothing else may enumerate it.**

```sql
CREATE TABLE INCIDENT_FACT (
  NO_INCIDENT           NUMBER(10) PRIMARY KEY,
  TRACKER_ID            NUMBER(10) NOT NULL,
  SUBJECT               VARCHAR2(255 CHAR),
  DATE_CREATE           TIMESTAMP(0) NOT NULL,
  CREATE_MONTH          VARCHAR2(6 CHAR) NOT NULL,      -- YYYYMM, for grouping
  UPDATED_ON            TIMESTAMP(0),
  CLOSED_ON             TIMESTAMP(0),

  STATUS_ID             NUMBER(10) NOT NULL,
  PRIORITY_ID           NUMBER(10),
  SLA_DAYS              NUMBER(3)  NOT NULL,
  PARENT_ID             NUMBER(10),
  DONE_RATIO            NUMBER(3),

  AUTHOR_ID             NUMBER(10),
  AUTHOR                VARCHAR2(120 CHAR),
  ASSIGNED_TO_ID        NUMBER(10),
  ASSIGNED_TO           VARCHAR2(120 CHAR),
  DEVELOPER_ASSIGNED_ID NUMBER(10),
  DEVELOPER_ASSIGNED    VARCHAR2(120 CHAR),

  -- Resolved label + raw id, for all six enum-valued custom fields.
  TYPE_OF_INCIDENT      VARCHAR2(120 CHAR), TYPE_OF_INCIDENT_ID  NUMBER(10),
  CATEGORY_INCIDENT     VARCHAR2(120 CHAR), CATEGORY_INCIDENT_ID NUMBER(10),
  EXTERNAL_INTERNAL     VARCHAR2(120 CHAR), EXTERNAL_INTERNAL_ID NUMBER(10),
  IMPACT_SEVERITY       VARCHAR2(120 CHAR), IMPACT_SEVERITY_ID   NUMBER(10),
  URGENCY_SEVERITY      VARCHAR2(120 CHAR), URGENCY_SEVERITY_ID  NUMBER(10),
  INSTITUSI             VARCHAR2(120 CHAR), INSTITUSI_ID         NUMBER(10),

  CLOSE_DATE            DATE,
  RESOLUTION_DATE       DATE,
  MIGRATION_DATE        DATE,
  -- Row-presence flags. Oracle cannot store '' (see Global Constraints), so these carry the
  -- NULL-vs-'' distinction that SOLUTION and AGING_NEW both turn on. 1 = a custom_values row
  -- exists for that field; 0 = no row at all.
  HAS_CLOSE_DATE_ROW    NUMBER(1) DEFAULT 0 NOT NULL,
  HAS_RESOLUTION_ROW    NUMBER(1) DEFAULT 0 NOT NULL,
  HAS_DATE_REJECT       NUMBER(1) DEFAULT 0 NOT NULL,   -- badge the row in the UI
  SOLUTION              VARCHAR2(40 CHAR),

  LAST_STATUS           VARCHAR2(30 CHAR),
  LAST_DATE_STATUS      TIMESTAMP(0),
  FIRST_STATUS_CHANGE_AT TIMESTAMP(0),                  -- MTTA proxy anchor
  WORKAROUND            CLOB,
  ROOTCAUSE             CLOB,

  ETL_UPDATED_AT        TIMESTAMP(0) NOT NULL,
  SOURCE_UPDATED_ON     TIMESTAMP(0) NOT NULL,          -- issues.updated_on at ETL time
  CV_DIGEST             NUMBER,                         -- see CV_DIGEST below
  -- Config generation this row was derived under. A CFG_* edit bumps CFG_VERSION.GENERATION;
  -- rows stamped with an older value are stale by definition and re-derived on next scan.
  CFG_GENERATION        NUMBER(10) DEFAULT 0 NOT NULL
);

CREATE INDEX IX_FACT_CREATE   ON INCIDENT_FACT (DATE_CREATE);
CREATE INDEX IX_FACT_MONTH    ON INCIDENT_FACT (CREATE_MONTH);
CREATE INDEX IX_FACT_STATUS   ON INCIDENT_FACT (STATUS_ID);
CREATE INDEX IX_FACT_PRIORITY ON INCIDENT_FACT (PRIORITY_ID);
CREATE INDEX IX_FACT_TRACKER  ON INCIDENT_FACT (TRACKER_ID);
CREATE INDEX IX_FACT_MATRIX   ON INCIDENT_FACT (IMPACT_SEVERITY, URGENCY_SEVERITY);

CREATE TABLE INCIDENT_FACT_REJECT (
  REJECT_ID       NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  NO_INCIDENT     NUMBER(10) NOT NULL,
  CUSTOM_FIELD_ID NUMBER(10) NOT NULL,
  LOGICAL_NAME    VARCHAR2(40 CHAR) NOT NULL,
  RAW_VALUE       VARCHAR2(400 CHAR),
  REASON          VARCHAR2(200 CHAR) NOT NULL,
  DETECTED_AT     TIMESTAMP(0) DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX IX_REJECT_INCIDENT ON INCIDENT_FACT_REJECT (NO_INCIDENT);

CREATE TABLE ETL_WATERMARK (
  WATERMARK_KEY   VARCHAR2(40 CHAR) PRIMARY KEY,
  LAST_SOURCE_TS  TIMESTAMP(0),
  LAST_RUN_AT     TIMESTAMP(0),
  LAST_RUN_STATUS VARCHAR2(20 CHAR),
  ROWS_MERGED     NUMBER(10)
);
INSERT INTO ETL_WATERMARK (WATERMARK_KEY, LAST_SOURCE_TS)
VALUES ('incident_fact', TIMESTAMP '1970-01-01 00:00:00');

CREATE TABLE ETL_RECONCILE_LOG (
  RUN_ID        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  RUN_AT        TIMESTAMP(0) DEFAULT SYSTIMESTAMP NOT NULL,
  SCOPE_MONTH   VARCHAR2(6 CHAR),
  MIRROR_COUNT  NUMBER(10),
  FACT_COUNT    NUMBER(10),
  DRIFT_COUNT   NUMBER(10),
  ACTION_TAKEN  VARCHAR2(200 CHAR)
);

-- CV_DIGEST — THE canonical definition. Tasks 11 and 12 both reference THIS expression and
-- neither may restate it. It must be computed by Oracle on both sides: computing it in
-- Java cannot reproduce ORA_HASH and would report false drift on every reconcile.
--
--   SELECT NVL(SUM(ORA_HASH(cv.ID || '|' || cv.CUSTOM_FIELD_ID || '|'
--                           || NVL(DBMS_LOB.SUBSTR(cv.VALUE, 200), '~'))), 0)
--   FROM   <mirror>CUSTOM_VALUES cv
--   WHERE  cv.CUSTOMIZED_TYPE = 'Issue' AND cv.CUSTOMIZED_ID = :issueId

-- Two levels ON PURPOSE. ELAPSED_DAYS is computed ONCE in the inner block and every SLA
-- column plus AGING_NEW derives from it. Re-typing the expression per column is how an
-- earlier draft ended up with AGING_NEW correctly NULL for the 8% close_date = '' cohort
-- while SLA_BREACHED scored those same rows as 600-day breaches -- and
-- slaBreachedAgreesWithSlaBucket still passed, because both wrong columns agreed.
CREATE OR REPLACE VIEW V_INCIDENT AS
SELECT b.*,
       -- Report renderings. The fact stores native types (so sorting and filtering are
       -- correct); the delivered workbook's formats are applied here and mapped straight
       -- onto the report aliases by the DTO. See "The report column contract".
       TO_CHAR(b.DATE_CREATE, 'YYYY-MM-DD')  AS RPT_DATE_CREATE,   -- SC003, source line 15
       TO_CHAR(b.DATE_CREATE, 'YYYYMMDD')    AS RPT_CREATE_DATE,   -- SC004, source line 16
       TO_CHAR(b.UPDATED_ON,  'YYYYMMDD')    AS RPT_UPDATED_ON,    -- SC006, source line 18
       TO_CHAR(b.CLOSED_ON,   'YYYYMMDD')    AS RPT_CLOSED_ON,     -- SC007, source line 19
       b.ELAPSED_DAYS                                             AS AGING_NEW,
       ROUND(b.ELAPSED_DAYS / NULLIF(b.SLA_DAYS, 0), 4)           AS SLA_RATIO,
       CASE WHEN b.ELAPSED_DAYS IS NULL THEN 0
            WHEN b.ELAPSED_DAYS > b.SLA_DAYS THEN 1 ELSE 0 END    AS SLA_BREACHED,
       -- A NULL elapsed gets its OWN band, not a NULL bucket: a NULL disappears from the
       -- bucket chart, and 8% of rows silently vanishing from a chart is the failure mode
       -- the "unresolved values must never vanish" constraint exists to stop.
       CASE WHEN b.ELAPSED_DAYS IS NULL                THEN 'Unknown'
            WHEN b.ELAPSED_DAYS >  b.SLA_DAYS          THEN 'Breached'
            WHEN b.ELAPSED_DAYS >= b.SLA_DAYS * 0.8    THEN '80-100%'
            WHEN b.ELAPSED_DAYS >= b.SLA_DAYS * 0.5    THEN '50-80%'
            ELSE '0-50%' END                                      AS SLA_BUCKET
FROM (
  SELECT f.*,
         f.SLA_DAYS                                         AS SLA,   -- SC011: the workbook
                                                                      -- header is SLA, and
                                                                      -- XlsxReader strips _,
                                                                      -- so SLA_DAYS would
                                                                      -- normalise to SLADAYS
                                                                      -- and fail Level 1.
         NVL(s.NAME, 'Unknown (' || f.STATUS_ID || ')')     AS STATUS,
         NVL(s.IS_CLOSED, 0)                                AS IS_CLOSED,
         NVL(t.NAME, 'Unknown (' || f.TRACKER_ID || ')')    AS TRACKER,
         NVL(e.NAME, 'Unclassified')                        AS PRIORITY,
         NVL(m.STAGE, 'Unmapped')                           AS STATUS_STAGE,
         NVL(m.EXCLUDE_FROM_BACKLOG, 0)                     AS EXCLUDE_FROM_BACKLOG,
         -- SYSDATE, never SYSTIMESTAMP: mirror timestamps are offset-naive MySQL wall
         -- clock. See Global Constraints -- one timezone, pinned server-side.
         --
         -- raw-datetime anchor: whole days ELAPSED (source lines 20 and 62)
         TRUNC(SYSDATE - CAST(f.DATE_CREATE AS DATE))                          AS AGING_INCIDENT,
         TRUNC(SYSDATE - CAST(f.LAST_DATE_STATUS AS DATE))                     AS AGING_LAST_STATUS,
         CASE WHEN f.FIRST_STATUS_CHANGE_AT IS NULL THEN NULL
              ELSE (CAST(f.FIRST_STATUS_CHANGE_AT AS DATE)
                    - CAST(f.DATE_CREATE AS DATE)) * 24
         END                                                                   AS MTTA_HOURS,
         -- THE single elapsed expression (source line 3). Midnight anchor -> CALENDAR days.
         -- Branches on IS NULL only; close_date = '' takes the ELSE branch and MySQL's
         -- timestampdiff(DAY, d, '') returns NULL -- 371 of 4,627 delivered rows (8%).
         CASE WHEN f.HAS_CLOSE_DATE_ROW = 1 AND f.CLOSE_DATE IS NULL THEN NULL
              ELSE NVL(TRUNC(f.CLOSE_DATE), TRUNC(SYSDATE))
                   - TRUNC(CAST(f.DATE_CREATE AS DATE))
         END                                                                   AS ELAPSED_DAYS,
         CASE WHEN f.RESOLUTION_DATE IS NOT NULL
              THEN TRUNC(f.RESOLUTION_DATE) - TRUNC(CAST(f.DATE_CREATE AS DATE))
         END                                                                   AS TTR_WORKAROUND_DAYS,
         CASE WHEN f.CLOSE_DATE IS NOT NULL
              THEN TRUNC(f.CLOSE_DATE) - TRUNC(CAST(f.DATE_CREATE AS DATE))
         END                                                                   AS TTR_PERMANENT_DAYS
  FROM   INCIDENT_FACT f
  LEFT   JOIN ${mirror}ISSUE_STATUSES s ON s.ID = f.STATUS_ID
  LEFT   JOIN ${mirror}TRACKERS       t ON t.ID = f.TRACKER_ID
  LEFT   JOIN ${mirror}ENUMERATIONS   e ON e.ID = f.PRIORITY_ID AND e.TYPE = 'IssuePriority'
  LEFT   JOIN CFG_STATUS_STAGE_MAP    m ON m.STATUS_ID = f.STATUS_ID
) b;
```

**Tests:** `agingIsComputedAtReadTimeNotStored` (AGING_*/SLA_BREACHED absent from the table,
present on the view) · `factKeepsBothTheRawEnumIdAndTheResolvedLabel` ·
`everyFactColumnAppearsInTheMergeStatement` (the single-source-of-truth guard) ·
`incidentSurvivesAMissingDimensionRow` (delete a `TRACKERS` row → the incident still counts,
as `Unknown (<id>)`) · **`slaBreachedAgreesWithSlaBucketForEveryRow`** (zero rows where
`SLA_BREACHED = 1` XOR `SLA_BUCKET = 'Breached'`) · **`aClosedButLateIncidentIsStillABreach`**
· **`agingIsStableAcrossTheUtcDayBoundary`** (an incident created 23:00 local and read at
01:00 local the next day reports `AGING_INCIDENT = 0`, per the source).

---

### Task 10: Derived-field logic (pure functions, unit-tested)

Get the three tricky classifiers right in isolation before touching SQL.

**Files:** `domain/model/SolutionType.java`, `domain/usecase/etl/DerivedFields.java`,
`etl/DerivedFieldsTest.java`

**Interfaces:**
- `DerivedFields.slaDays(Long priorityId, SlaPolicy policy) → int`
- `DerivedFields.solution(String close, String res) → String` — **raw strings, not dates**
- `DerivedFields.parseRedmineDate(String raw) → DateParse(LocalDate value, boolean rejected, String reason)`

**Invariants:**
- **`solution` takes raw strings.** A `solution(LocalDate, LocalDate)` signature structurally
  cannot represent SQL `NULL` vs `''`, which the source query treats as different inputs
  producing different outputs. The full branch logic and its asymmetry are in
  **§ Derived-column semantics** above — that table is the spec; do not re-derive it here.
- **`slaDays` takes a `PRIORITY_ID` and reads `CFG_SLA_POLICY`** — it is *not* a Java switch
  on the priority name. Names are Redmine-admin-editable, and keying on the string means a
  rename silently moves incidents from a 60-day to a 3-day SLA with no alert. The ladder
  (Low 60 · Medium 14 · High 7 · Critical 3) lives in the seed, not in code.
- An id **absent** from `CFG_SLA_POLICY` falls back to **3** — the source's `ELSE` — and is
  **logged**. An unrecognised priority must not become the *most permissive* bucket, and a
  silent strictest-bucket default is a number nobody can explain later.
- `parseRedmineDate` uses `ResolverStyle.STRICT` with the **`uuuu`** pattern (not `yyyy`).
  That is what makes `2025-02-31` reject instead of silently rolling to March 3rd. A lenient
  parser corrupts SLA numbers invisibly.
- `null` and `""` both mean "no date" **for parsing purposes** — absent, not broken
  (`rejected == false`). Anything else that will not parse is `rejected == true` with
  `value == null`.
- **But they are NOT interchangeable downstream.** `parseRedmineDate` deliberately discards
  the distinction; `solution()` and `AGING_NEW` both need it, and it is carried separately by
  the `HAS_*_ROW` flags — never recovered from the parsed `DATE` column, which cannot hold it.
  A `CLOSE_DATE DATE` column has exactly the defect this plan rejects for
  `solution(LocalDate, LocalDate)`.
- The `"Workaround & Pemanent Solution"` misspelling is preserved deliberately.

**Tests:** `slaDaysFollowsThePriorityLadder` (parameterized, from a seeded policy) ·
`unknownPriorityGetsTheStrictestSlaAndIsLogged` · **`renamingAPriorityDoesNotChangeItsSla`**
· `solutionClassifierMatchesTheSourceReportExactly` ·
**`reproducesTheNullVersusEmptyStringQuirkInBothDirections`** — asserts
`solution(null, "") == "Workaround Solution"` **and** `solution("", null) == "Permanent
Solution"`; the two are *not* symmetric and getting it backwards is invisible without this
assertion · `solutionReturnsNullWhenClosePrecedesResolution` ·
`parsesRedmineDatesAndQuarantinesTheRest`.

---

### Task 11: Watermark ETL — delta selection and MERGE

**Files:** `domain/usecase/etl/MartRefreshUseCase.java`,
`infrastructure/datasource/service/MartRefreshRepository.java`,
`src/main/resources/sql/incident-delta.sql`, `etl/MartRefreshIT.java`

**Interfaces:**
- Consumes: `EnumLabelResolver`, `UserLabelResolver`, `DerivedFields`, the `CFG_*` tables.
- Produces: `MartRefreshUseCase.refresh() → RefreshResult(int scanned, int changed, int rejected, Instant watermark)`.
  `scanned` is inflated by the watermark overlap; **`changed` is the value to assert on.**
- Produces: `MartRefreshUseCase.refreshMonth(String createMonth) → RefreshResult` — a
  month-scoped, watermark-independent re-merge. Task 12's reconcile calls it to repair drift;
  it does not advance the watermark.

**Invariants:**
- Incremental on `issues.updated_on`. Redmine bumps it whenever a journal is added, so
  status changes and notes drag the issue back into the delta — which is what makes a
  timestamp-less `journal_details` workable.
- **The delta carries the source report's date window** — created since Jan 1 of *last*
  year. It is the report's scope, not an optimisation: without it the mart holds rows the
  delivered workbook never had, and Task 17's release-gating `extraInMart().isEmpty()` fails
  by construction. The window is **rolling**, so on 1 January rows age out of scope; the
  Task 12 reconcile prunes fact rows that no longer match, and logs the count.
- **The first run after deploy is a full-history load** of roughly 23k issues. It must not
  be one row-at-a-time pass inside a single `@Transactional` on a 2-minute cron: that run
  overlaps the next tick, and if it fails the watermark never advances so it retries the
  whole thing forever. Instead: process in **bounded chunks** (`incident.etl.chunk-size`,
  default 500), **commit and advance the watermark per chunk**, and hold a non-reentrant lock
  so a slow run cannot overlap its successor.
- **The chunk cursor is a keyset, and the overlap is applied ONCE per run.** `UPDATED_ON` is
  `TIMESTAMP(0)` — second precision — so a bare `> :lastTs` either re-reads or skips every row
  sharing that second, and combined with a per-chunk 300s rewind the loop **does not
  terminate**. The cursor is `(UPDATED_ON, ID) > (:lastTs, :lastId)` ordered by both; the 300s
  overlap applies to the **first chunk only**, subsequent chunks advance strictly.
- Test: `chunksTerminateAndMergeEachRowExactlyOnce` — `chunk-size = 2` over several rows
  sharing one `UPDATED_ON` second. Progress then
  survives a failure, and a partial load is a partial mart rather than an empty one.
- **`custom_values` has no timestamp and is UPDATEd in place**, so it cannot be deltaed on
  its own. For every issue in the delta, re-read **all** of its custom values. The delta is
  small; the alternative is missing edits entirely.
- **The custom-values load returns row PRESENCE alongside the value** — Oracle cannot store
  `''` (Global Constraints), so `(cf_id, value)` alone loses the distinction SOLUTION and
  `AGING_NEW` both need. It populates `HAS_CLOSE_DATE_ROW` / `HAS_RESOLUTION_ROW`, and passes
  `""` (row present) or `null` (no row) to `DerivedFields.solution`.
- The watermark is read back with a **300s overlap** (`incident.etl.watermark-overlap-seconds`).
  A row committed at T−1s with `updated_on = T−3s` would be skipped forever by a strict
  `> watermark`. MERGE makes the re-reads harmless.
- **Consequently the delta is never empty on a re-run, so `changed` cannot be `delta.size()`.**
  `merge()` returns true only when content actually differed.
- `MERGE ... WHEN MATCHED` carries a **content predicate** —
  `WHERE DECODE(t.SOURCE_UPDATED_ON, ?, 0, 1) = 1 OR DECODE(t.CV_DIGEST, ?, 0, 1) = 1
   OR DECODE(t.CFG_GENERATION, ?, 0, 1) = 1` — and `ETL_UPDATED_AT` is set only inside it.
- **`CFG_GENERATION` is why the config tables are not merely readable but *effective*.** The
  first two terms key on `issues.updated_on` and the custom-value digest; a `CFG_*` edit
  changes neither, so without a third term an already-merged row can **never** be re-derived
  after a config change — and the reconcile, which checksums the same two inputs, would
  report zero drift and never repair it. `deactivatingAJournalPropStopsPopulatingItsColumn`
  would fail as written, and an admin adding custom field 110 would get `MIGRATION_DATE`
  populated only for issues touched in Redmine afterwards, leaving the mart permanently and
  invisibly half-mapped. `CFG_GENERATION` is a single counter on a `CFG_VERSION` row, bumped
  by any `CFG_*` write and stamped onto each fact row; a bump makes the next refresh re-derive
  every row it scans, and the reconcile treats a generation mismatch as drift. Without this, every re-read counts as a change
  and `changed()` can never reach zero, which is exactly what `MartRefreshIT` asserts.
- `MERGE_SQL`'s column list is **generated from `USER_TAB_COLUMNS`**, not hand-written, and
  the Task 9 test enforces that it covers every fact column.
- `CV_DIGEST` is loaded **in the same round trip as the custom values**, using the canonical
  expression defined in `V3__mart.sql`. It must not be restated here.
- `saveRejects` **deletes the incident's existing rejects before inserting**, so a corrected
  value in Redmine clears the quarantine rather than accumulating duplicates.
- `${mirror}` in this file is substituted from the Spring property `incident.mirror.schema`
  at load time — a *different* mechanism from the Flyway placeholder of the same name in
  `V3__mart.sql`. Both must carry the same value; `AbstractOracleIT` passes it to Flyway explicitly.

**Config tables must actually be consumed** *(round-2 MAJOR — this was the gap)*:
- Tracker scope comes from `CFG_TRACKER_SCOPE`, **not** a literal `IN (7,16,32,33,41)`.
- Journal props come from `CFG_JOURNAL_PROP_MAP`. Instead of one hardcoded subquery per
  prop key, the delta returns **one row per (issue, logical_name, value)** for the latest
  journal entry of each active mapped prop key, and Java pivots by `TARGET_COLUMN`.
- Custom fields are mapped `CUSTOM_FIELD_ID → TARGET_COLUMN` via `CFG_CUSTOM_FIELD_MAP`,
  with `FIELD_KIND` selecting enum-resolution vs. `parseRedmineDate`.
- **This is testable and must be tested:** inserting a config row changes the mart with no
  code change. If it does not, the config tables are decorative and this task has failed.

**[PARITY] `incident-delta.sql` — the join semantics are transcribed from the source report.**

```sql
-- Every in-scope issue touched since the watermark. Custom values are fetched separately
-- (with their CV_DIGEST) because they need per-field parsing and quarantine.
SELECT i.ID                AS NO_INCIDENT,
       i.TRACKER_ID,
       i.SUBJECT,
       i.CREATED_ON        AS DATE_CREATE,
       TO_CHAR(i.CREATED_ON, 'YYYYMM') AS CREATE_MONTH,
       i.UPDATED_ON,
       i.CLOSED_ON,
       i.STATUS_ID,
       i.PRIORITY_ID,                    -- id only; NAME is joined live in V_INCIDENT
       e.NAME              AS PRIORITY,  -- still needed HERE, to derive SLA_DAYS
       i.PARENT_ID,
       i.DONE_RATIO,
       i.AUTHOR_ID,
       i.ASSIGNED_TO_ID,
       js.FIRST_CHANGE_AT  AS FIRST_STATUS_CHANGE_AT,
       js.LAST_CHANGE_AT   AS LAST_DATE_STATUS,
       ls.NAME             AS LAST_STATUS
FROM   ${mirror}ISSUES i
LEFT   JOIN ${mirror}ENUMERATIONS e
       ON e.ID = i.PRIORITY_ID AND e.TYPE = 'IssuePriority'
-- First and last status transition in one pass over the journals. PROPERTY = 'attr'
-- matters: a custom field could also be keyed 'status_id'.
LEFT   JOIN (
         SELECT j.JOURNALIZED_ID,
                MIN(j.CREATED_ON) AS FIRST_CHANGE_AT,
                MAX(j.CREATED_ON) AS LAST_CHANGE_AT,
                MAX(j.ID) KEEP (DENSE_RANK LAST ORDER BY j.CREATED_ON, j.ID) AS LAST_JOURNAL_ID
         FROM   ${mirror}JOURNALS j
         JOIN   ${mirror}JOURNAL_DETAILS d ON d.JOURNAL_ID = j.ID
         JOIN   CFG_JOURNAL_PROP_MAP      c ON c.PROP_KEY = d.PROP_KEY
                                           AND c.PROPERTY = d.PROPERTY
                                           AND c.VALUE_KIND = 'STATUS'
                                           AND c.IS_ACTIVE = 1
         WHERE  j.JOURNALIZED_TYPE = 'Issue'
         GROUP  BY j.JOURNALIZED_ID
       ) js ON js.JOURNALIZED_ID = i.ID
LEFT   JOIN ${mirror}JOURNAL_DETAILS lsd
       ON lsd.JOURNAL_ID = js.LAST_JOURNAL_ID
      AND lsd.PROPERTY = 'attr' AND lsd.PROP_KEY = 'status_id'
LEFT   JOIN ${mirror}ISSUE_STATUSES ls ON ls.ID = TO_NUMBER(lsd.VALUE)
WHERE  i.TRACKER_ID IN (SELECT TRACKER_ID FROM CFG_TRACKER_SCOPE WHERE IS_ACTIVE = 1)
-- The delivered report is windowed to "created since Jan 1 of LAST year", compared as
-- %Y%m%d strings. Without this the mart holds rows the workbook never had and the
-- release-gating Level-3 assertion extraInMart().isEmpty() fails by construction.
AND    TO_CHAR(i.CREATED_ON, 'YYYYMMDD')
       >= TO_CHAR(ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), -12), 'YYYYMMDD')
AND    i.UPDATED_ON > ?          -- watermark minus incident.etl.watermark-overlap-seconds
ORDER  BY i.UPDATED_ON
FETCH  FIRST ? ROWS ONLY         -- incident.etl.chunk-size; see the chunking invariant
```

Companion query — **latest value per mapped journal prop**, replacing the three hardcoded
subqueries. "Last value wins" is the source report's semantics and must be preserved:

```sql
SELECT * FROM (
  SELECT j.JOURNALIZED_ID, c.LOGICAL_NAME, c.TARGET_COLUMN, c.VALUE_KIND, d.VALUE,
         ROW_NUMBER() OVER (PARTITION BY j.JOURNALIZED_ID, c.PROP_KEY
                            ORDER BY d.JOURNAL_ID DESC, d.ID DESC) rn
  FROM   ${mirror}JOURNAL_DETAILS d
  JOIN   ${mirror}JOURNALS j        ON j.ID = d.JOURNAL_ID
  JOIN   CFG_JOURNAL_PROP_MAP c     ON c.PROP_KEY = d.PROP_KEY
                                   AND c.PROPERTY = d.PROPERTY
                                   AND c.VALUE_KIND <> 'STATUS'
                                   AND c.IS_ACTIVE = 1
  WHERE  j.JOURNALIZED_TYPE = 'Issue' AND j.JOURNALIZED_ID = ?
) WHERE rn = 1
```

**Tests:** `firstRunMaterialisesEveryInScopeIssue` (100; zero rows outside `CFG_TRACKER_SCOPE`)
· **`secondRunWithNoSourceChangeChangesNothing`** (`changed() == 0` **and** `scanned() > 0`)
· `anUpdatedIssueIsPickedUpAndOverwritesTheFactRow` · `resolvesEnumLabelsAndKeepsUnknownIdsVisible`
(22885 → `Unknown (999999)`; 22891 → `Unclassified`) · `unparseableDatesAreQuarantinedNotNulled`
(22885 cf 105 → one reject row, `HAS_DATE_REJECT = 1`, `RAW_VALUE = 'not-a-date'`) ·
`classifiesSolutionAcrossAllThreeShapes` (22868/22878/22882) ·
`mapsStatusesToStagesAndFlagsUnmappedOnes` (unknown status → `STATUS_STAGE = 'Unmapped'` on
the **view**, and the row is still present in the fact) ·
`capturesLastStatusAndFirstStatusChangeFromJournals` (zero rows with
`FIRST_STATUS_CHANGE_AT > LAST_DATE_STATUS`) ·
**`addingATrackerToConfigWidensTheMartWithoutACodeChange`** ·
**`deactivatingAJournalPropStopsPopulatingItsColumn`** ·
**`excludesIssuesOlderThanTheReportWindow`** ·
**`advancesTheWatermarkPerChunkSoAFailedRunKeepsItsProgress`** (kill the run mid-way; the
next run resumes rather than restarting) · **`aSecondRefreshCannotStartWhileOneIsRunning`**.

---

### Task 12: Scheduler, reconcile job and freshness endpoint

**Files:** `domain/usecase/etl/{ReconcileUseCase,FreshnessUseCase}.java`,
`domain/config/SchedulingConfig.java`, `infrastructure/rest/FreshnessController.java`,
`etl/ReconcileIT.java`

**Interfaces:** `GET /api/freshness` → `{lastRunAt, lastSourceTs, ageSeconds, stale}`.
The Task 20 heartbeat and the Task 21 wallboard both poll it.

**Invariants:**
- **The reconcile's mirror-side query reuses `incident-delta.sql`'s scope predicates
  verbatim** — the `CFG_TRACKER_SCOPE` filter *and* the rolling date window. `INCIDENT_FACT`
  holds only five trackers inside that window; `${mirror}ISSUES` holds every Redmine tracker
  and all history (the shipped `02. redmine.issue_statuses.sql` carries 74 statuses including
  recruitment and procurement workflows, and `issues` runs to `AUTO_INCREMENT=23614`).
  Comparing an unscoped mirror population against a scoped fact table reports drift on every
  month forever, `reportsZeroDriftOnAHealthyMart` can never pass, and `ETL_RECONCILE_LOG`
  fills with false positives — destroying the only backstop against a Kafka gap. **One
  predicate builder feeds both sides**, the same discipline Task 19 applies to filters.
- Reconcile compares mirror vs. fact **per `CREATE_MONTH`**: row counts plus a
  `SUM(ORA_HASH(...))` checksum over `(id, updated_on, status_id)`, **`CV_DIGEST`** and
  **`CFG_GENERATION`**.
- **The `CV_DIGEST` term is what makes this a real backstop** rather than a second copy of
  the watermark's blind spot. A `custom_values` row edited in place — or a dropped Kafka
  event — changes the digest while leaving every `issues` column untouched, so a checksum
  over `issues` alone would report zero drift forever.
- Mirror-side digest uses the canonical expression from `V3__mart.sql`. Do not restate it.
- Any month whose counts or checksum differ is re-merged wholesale via
  **`MartRefreshUseCase.refreshMonth(String createMonth) → RefreshResult`** (Task 11
  Interfaces). `refresh()` is watermark-driven and cannot import an out-of-window or
  previously-missed row, so binding the repair to it leaves drift permanently unrepaired.
  `refreshMonth` does **not** advance `ETL_WATERMARK`.
- **Log every run to `ETL_RECONCILE_LOG`, including clean ones** — a reconcile that stops
  running is itself the failure to detect.
- **`ReconcileUseCase.pruneOutOfScope() → int`** deletes fact rows that have aged out of the
  rolling window or whose tracker left `CFG_TRACKER_SCOPE`. It needs its own interface because
  `refreshMonth` is a MERGE over a delta whose scope predicates **exclude** those very rows —
  a MERGE structurally cannot remove them.
  - **This is explicitly carved out of "Deletes arrive as status changes, never row removals.
    No tombstone logic."** That constraint governs *mirror* data and Redmine's lifecycle; this
    deletes **our own mart rows** that the report no longer covers. Without the carve-out an
    implementer has no basis to write the DELETE, and correctly refuses to.
  - Logs to `ETL_RECONCILE_LOG` with `ACTION_TAKEN = 'pruned N out-of-scope rows'`.
  - Test: `prunesFactRowsThatFellOutOfTheRollingWindow`.
- Deletes arrive as status changes, which bump `updated_on`, so the watermark path handles
  them. Reconcile is the backstop for what the watermark misses.
- Both `@Scheduled` methods wrap their body so an exception is logged and swallowed. A
  failed run must not kill the scheduler thread.
- **`stale` measures mirror lag, not just our own scheduler.** `lastRunAt` older than 4× the
  refresh interval is one condition; the other is `MAX(issues.updated_on)` in the mirror
  falling further than a threshold behind wall clock, which is the only way to see the Kafka
  sync itself dying while our ETL keeps running happily over frozen data — the exact failure
  the indicator exists for. `stale` is true when **either** holds, and the response names
  which. The UI shows a
  banner, so a silently dead ETL surfaces to users instead of presenting hours-old numbers as live.
- `/api/freshness` is privilege-annotated per **Task 14's route map, which is the single
  source** — do not restate the annotation here. (It must be reachable by the wallboard and
  exec roles, not just ops; scoping it to `incident.ops` is the bug Task 14 names by example.)

**Tests:** `detectsAndRepairsDriftWhenAFactRowWentMissing` ·
**`detectsAnInPlaceCustomValueEditThatNeverTouchedTheIssue`** — the blind-spot test: after
editing a `custom_values` row directly, `refresh().changed()` is **zero** (the watermark is
blind) and `reconcile().driftCount()` is **1** · `detectsADimensionRenameThatNeverTouchedAnyIssue`
(renaming a status is correct immediately on `V_INCIDENT`, with no ETL at all) ·
`reportsZeroDriftOnAHealthyMart` · `freshnessGoesStaleWhenTheWatermarkStopsMoving`.

---

## Phase 3 — Standalone auth

### Task 13: Auth schema and fail-fast `JwtService` (Flyway V4–V5 + `R__SeedAdmin`)

**Files:** `db/migration/{V4__auth.sql,V5__seed_rbac.sql,R__SeedAdmin.java}`,
`src/test/resources/db/migration/V7__seed_test_users.sql`,
`domain/config/security/{SecurityProps,JwtService}.java`, `security/JwtServiceTest.java`

**Interfaces:**
- `JwtService.createToken(String username) → String`
- `JwtService.createRefreshToken(String username, String jti) → String`
- `JwtService.validate(String token, String expectedTyp) → Claims` — **returns the claims,
  not a bare username.** A `String` return discards `scope` and `mustChangePassword`, leaving
  neither `AuthFilter` nor `PrivilegeInterceptor` any input from which to enforce the forced-
  rotation gate that Task 13 specifies. Tokens carry `sub`, `typ` and `scope`.
- Exceptions `JwtService.JwtInvalidException`, `JwtService.JwtExpiredException`

**Invariants:**
- **The signing key comes from configuration and has no default.** The constructor throws
  `IllegalStateException` when `incident.jwt.secret` is absent/blank, and again when it is
  under 32 bytes for HS256. yob hardcodes its key in `JWTUtil.getSignKey()`; that is the
  anti-pattern this corrects, and a shipped fallback is how signing secrets reach source control.
- **There is deliberately NO single-argument `validate(token)`.** Refresh tokens carry
  `typ=refresh` and a `jti`; a type-blind overload would accept a 24h refresh token wherever
  an access token is expected, making the 1h access expiry decorative — the longest-lived
  credential would also be the most privileged.
- **`AUTH_REFRESH_TOKEN(JTI PK, USER_ID, ISSUED_AT, EXPIRES_AT, REVOKED_AT)`** — without a
  store the `jti` is unconsumable, and rotation, logout and revocation are all silently
  impossible.
- `V4__auth.sql`: `APP_USER` (with `MUST_CHANGE_PASSWORD`, `IS_ACTIVE`, bcrypt
  `PASSWORD_HASH`, **`FAILED_LOGIN_COUNT`, `LOCKED_UNTIL`, `LAST_LOGIN_AT`,
  `PASSWORD_CHANGED_AT`**), `APP_ROLE` (with `DEFAULT_ROUTE` — RBAC decides where a user
  lands), `APP_USER_ROLE`, `APP_MENU`, `APP_ROLE_PRIVILEGE`.
- **The bootstrap credential must be guessable only a bounded number of times.** Without a
  lockout and a throttle, `/auth/token` is an unlimited oracle against a single known
  username. `LoginUseCase` increments `FAILED_LOGIN_COUNT`, sets `LOCKED_UNTIL` after **5**
  failures (15-minute window, exponential thereafter), and clears both on success while
  stamping `LAST_LOGIN_AT`. `/auth/token` additionally carries a **per-IP rate limit**.
- A locked or inactive account returns the **same** generic failure as a wrong password —
  distinguishing them confirms which usernames exist.
- **The privilege universe is exactly `{view, export, manage}`**, enforced by a CHECK on
  `APP_ROLE_PRIVILEGE.PRIVILEGE`. `manage` is scoped to the `incident.admin` menu and nothing
  else. There is deliberately no `edit`, `create` or `delete` **on incident data** — Redmine
  owns those writes, and the absence of the privilege is the enforcement.
- `V5__seed_rbac.sql` is pure data: **`ADMIN` → `/ops`**, `OPS_MANAGER` → `/ops`,
  `EXEC` → `/exec`, `WALLBOARD` → `/wallboard`, **`VIEWER` → `/ops`**, plus the menus and
  their privileges. `VIEWER` is required by `V7__seed_test_users.sql` and was previously never
  seeded, so `exportRequiresTheExportPrivilegeSeparately` had no account to run as.
- **There must be a path to a usable production account, and there currently is none.**
  V6 seeds an admin but assigns it no role, V5 seeded no `ADMIN` role, and there is no
  user-management surface — so the admin logs in with an empty `menus[]`, a null
  `defaultRoute`, and a fail-closed 403 on every `/api/**` call. Fixes, all required:
  - `V5` seeds an `ADMIN` role holding **every** menu at `view` + `export`, plus
    `incident.admin:manage`.
  - `V6` inserts the `APP_USER_ROLE` row binding the bootstrap admin to `ADMIN`, in the same
    transaction as the user insert.
  - **`R__SeedAdmin` is repeatable, and it MUST override `getChecksum()`.** Versioned
    (`V6__`) is wrong: the warn-and-return path marks it applied and nothing ever re-attempts
    it. But repeatable **alone is also not enough** — Flyway re-runs a repeatable only when its
    checksum *changes*, and `BaseJavaMigration.getChecksum()` returns `null` by default, so
    null-vs-null compares equal and it is skipped forever. Override it to derive from the
    **presence** (never the content) of `INCIDENT_ADMIN_PASSWORD`, so the migration goes
    OUTDATED the moment the variable appears. The seed is **insert-only**
    (`MERGE … WHEN NOT MATCHED THEN INSERT`, no matched branch) so it never overwrites a
    rotated password.
  - Task 14 adds `POST /api/admin/users` and `POST /api/admin/users/{id}/roles`, guarded by
    `incident.admin:manage`. This makes change-password **not** the only non-GET endpoint —
    that claim is amended below. Both touch **our** `APP_USER` tables, never Redmine data,
    so the read-only guarantee is untouched.
- `R__SeedAdmin.java` is **Java, not SQL** — the password comes from the environment and
  must be bcrypt-hashed, and neither is expressible in Oracle SQL. It is **repeatable**, not
  versioned; see the bootstrap-account invariants below for why.
- **`V6` must not hard-throw on a missing env var.** Tests run this same migration, so a
  throw aborts *every* tier-2 IT in `@BeforeAll` on a clean CI checkout. It **warns and
  returns** when `INCIDENT_ADMIN_PASSWORD` is unset, and hard-fails only when it is present
  but under 12 characters. Production safety is preserved by `LoginUseCase`: with no admin
  row nobody can log in, which is the same outcome as refusing to boot.
- **`MUST_CHANGE_PASSWORD` must not simply refuse the login** — that locks out the only
  account with no way back. Instead: login succeeds with a token carrying
  **`scope = "change-password"`**, plus `mustChangePassword: true`;
  `AuthenticatedUser` carries that `scope`, and `PrivilegeInterceptor` refuses **every**
  route except `/auth/change-password` while it is set — the claim is the carrier, and
  without it the gate is unenforceable prose. Test:
  `aTokenIssuedUnderForcedRotationIsRefusedOnEveryApiRoute`; that endpoint clears the
  flag and issues a normal token; `PrivilegeInterceptor` rejects every other `/api/**` call
  while the flag is set; the router forces `/change-password`.
- **The non-GET endpoints are exactly:** `/auth/token`, `/auth/refresh-token`,
  `/auth/logout`, `/auth/change-password`, and the two `/api/admin/users*` routes. Every one
  touches **our** `APP_USER*` tables; **none touches Redmine data**. That — not a count of
  endpoints — is the read-only guarantee, and `noWriteEndpointExistsForIncidentData` is what
  enforces it.
- **`V6` sets `MUST_CHANGE_PASSWORD = 1` on the bootstrap admin.** It is the reason the
  column and the scoped-token flow exist, and it was previously stated only in a review-log
  table, i.e. nowhere normative.
- `V7__seed_test_users.sql` lives under `src/test/resources` — **test classpath only, never
  shipped.** Task 14's `AuthFlowIT` and Task 23's Playwright specs log in as these accounts;
  without it they have no usable account at all.

| username | role | privileges |
|---|---|---|
| `ops.manager` | `OPS_MANAGER` | `incident.ops:view`, `incident.ops:export` |
| `exec.user` | `EXEC` | `incident.exec:view` |
| `viewer` | `VIEWER` | `incident.ops:view` only — the negative case for the export test |

**Tests:** `roundTripsAUsername` · `refusesToStartWithoutASecret` · `refusesASecretTooShortForHs256`
· **`refreshTokenIsNotAcceptedAsAnAccessToken`** · `rejectsATokenSignedWithADifferentKey` ·
`seedSkipsLoudlyWithoutAnAdminPassword` · `seedRejectsAWeakAdminPassword` ·
**`repeatedBadPasswordsLockTheAccount`** · **`aLockedAccountIsIndistinguishableFromABadPassword`**
· **`aSuccessfulLoginClearsTheFailureCountAndStampsLastLogin`**.

---

### Task 14: `AuthFilter`, server-side authorization, login endpoint

**Files:** `domain/config/security/{AuthFilter,RequirePrivilege,PrivilegeInterceptor,WebConfig}.java`,
`domain/config/error/exception/AccessDeniedException.java`,
`infrastructure/rest/AuthController.java`,
`domain/usecase/auth/{LoginUseCase,PermissionUseCase}.java`, `security/AuthFlowIT.java`

**Interfaces:**
- `POST /auth/token` → body `{token, displayName, defaultRoute, menus[], mustChangePassword}`
  **plus** a `Set-Cookie: refresh=…; HttpOnly; Secure; SameSite=Lax; Path=/auth`.
  **The refresh token is NEVER in the JSON body** — a token in the body cannot be HttpOnly,
  and returning it both ways means the implementer's cheapest path under TDD is to persist
  the body copy in `localStorage`, re-opening the stored-XSS-to-token-theft chain M18 closed.
- `POST /auth/refresh-token` → a new access token, reading the cookie. **Fully specified
  here because it is in `NO_AUTH`** — an unauthenticated endpoint whose entire job is minting
  access tokens cannot be left undefined. It MUST: call `validate(token, "refresh")` (never
  the access path); look the `jti` up in `AUTH_REFRESH_TOKEN` and reject if absent, revoked
  or expired; **re-check `IS_ACTIVE` and `LOCKED_UNTIL`**; and **preserve
  `mustChangePassword` scoping**. Without the last two, a deactivated or locked account keeps
  minting valid access tokens for the full refresh lifetime, and a user under forced rotation
  simply exchanges their refresh token for an unscoped one — bypassing the gate entirely.
- `POST /auth/logout` → revokes the `jti` and clears the cookie.
- `GET /auth/me` → the same permission payload. Task 15's Pinia store consumes it verbatim.

> **CORS must set `Access-Control-Allow-Credentials: true`** and echo the SPA origin. The
> stack is cross-origin (SPA 5175, API 8085), so with credentials disabled the browser will
> neither store nor send the refresh cookie and `/auth/refresh-token` can never succeed.
> `SameSite=Lax`, not `Strict`: Strict drops the cookie on inbound navigation, so following
> a Redmine link back into the dashboard would silently log the user out.

**Invariants:**
- **`AuthFilter` proves only *who* the caller is. Authorization is separate and server-side.**
  The Vue `v-can` directive hides UI; it does nothing to a `curl`. Without server-side
  checks any authenticated account can `GET /api/incidents?size=100000` and pull the entire
  production incident dataset regardless of role.
- Port yob's `OncePerRequestFilter` shape with
  `NO_AUTH = List.of("/health", "/auth/token", "/auth/refresh-token")`. On success put the
  username in `MDC` and an `AuthenticatedUser` (username + granted `menuId:privilege` set)
  in a request attribute.
- **Port the filter's shape, NOT its request logging.** yob's version logs the request body;
  on `/auth/token` that is a **plaintext password in the application log**. Either omit body
  logging entirely (preferred — this service has one POST) or mask `password` before it is
  written. A test asserts the captured log output for a login contains no password.
- **CORS is an explicit allowlist**, configured once: the SPA origin echoed by name,
  `Authorization` allowed, and **`Access-Control-Allow-Credentials: true`** — required for
  the refresh cookie to be stored and sent across the 5175→8085 origin boundary. There is no
  `*`; a wildcard origin is incompatible with credentials anyway.
- `domain/config/error/GlobalExceptionHandler` is **owned by this task**, not left
  unassigned in the File Structure: it maps `AccessDeniedException` → 403, the JWT
  exceptions → 401, validation failures → 400, and everything else → a 500 whose body
  carries a correlation id and **no stack trace or SQL**.
- `@RequirePrivilege(menu = {…}, privilege = "view")` — `menu()` is an **array**, satisfied
  by any listed menu — is a method annotation every `/api/**` handler must carry.
  `PrivilegeInterceptor` is **fail-closed**: a handler with no annotation throws, because an
  undecorated endpoint is a bug, not a public endpoint.
- **CORS preflight must be exempt, or the whole SPA breaks.** A cross-origin request carrying
  `Authorization` always triggers an `OPTIONS` preflight, which carries **no** `Authorization`
  header and is dispatched to Spring's internal `PreFlightHandler` — not a `HandlerMethod`.
  Spring retains the matched interceptors on the preflight chain, so a fail-closed
  `PrivilegeInterceptor` throws on a handler that *cannot* carry an annotation. Therefore:
  `AuthFilter` short-circuits `OPTIONS` before authenticating, and `PrivilegeInterceptor`
  returns `true` for anything that is not a `HandlerMethod`. Test:
  `aCorsPreflightIsNotAuthenticatedAndNotPrivilegeChecked`.
- **`PrivilegeInterceptor` must be registered.** An unregistered interceptor is inert and
  the entire authorization layer silently does nothing:
  ```java
  @Configuration
  public class WebConfig implements WebMvcConfigurer {
      @Override public void addInterceptors(InterceptorRegistry r) {
          r.addInterceptor(privilegeInterceptor).addPathPatterns("/api/**");
      }
  }
  ```
- **Privilege is per-route, and there are no separate "exec endpoints".** Task 19 defines
  only `/api/metrics/*`, which the Exec and Wallboard views also consume. Annotating all of
  `/api/metrics/**` with `menu="incident.ops"` makes `menu="incident.exec"` map to **zero
  routes**, so `exec.user` (holding only `incident.exec:view`) gets 403 on
  `/api/metrics/kpi`, `/trend` and `/top-categories`, and the wallboard TV gets 403 on
  `/api/metrics/kpi` and `/api/freshness`. **Two of the three v1 views would be structurally
  unreachable by the roles that own them.** The map:

  | Route | menu | privilege |
  |---|---|---|
  | `/api/incidents` | `incident.ops` | `view` |
  | `/api/incidents/export` | `incident.ops` | **`export`** |
  | `/api/metrics/{matrix,sla-buckets}` | `incident.ops` | `view` |
  | `/api/metrics/{kpi,trend,top-categories}` | **any of** `incident.ops`, `incident.exec`, `incident.wallboard` | `view` |
  | `/api/freshness` | **any of** the three | `view` |

  `@RequirePrivilege` therefore takes `menu()` as an **array**, satisfied if the caller holds
  the privilege on **any** listed menu. A single-valued annotation cannot express a route
  three roles legitimately share.
- **`/api/freshness` must be reachable by the wallboard**, or the stale banner — the whole
  point of an unattended TV — never renders.
- `AccessDeniedException` is **ours** — `domain.config.error.exception` — because
  `spring-security-crypto` ships no exception types and the security starter is deliberately
  off the classpath. `@ExceptionHandler` maps it to **403**, not 500.
- `LoginUseCase` verifies the bcrypt hash and rejects inactive users. `PermissionUseCase`
  loads `menuId → [privileges]` and the highest-priority `DEFAULT_ROUTE`.

**Tests:** `loginReturnsTokenAndPermissionPayload` · **`loginNeverLogsThePassword`** ·
`protectedEndpointsRejectAMissingOrBadToken`
· **`anAuthenticatedUserWithoutThePrivilegeIsRefused`** (`exec.user` → `/api/incidents` is
403, not 200) · **`exportRequiresTheExportPrivilegeSeparately`** (`viewer` → 403) ·
**`everyApiHandlerDeclaresARequiredPrivilege`** (walks `RequestMappingHandlerMapping`, so a
future task cannot add an unguarded endpoint without turning this red) ·
`healthAndLoginStayUnauthenticated` · **`noWriteEndpointExistsForIncidentData`** — the method set under every `/api/**` route is
exactly `{GET}`, with **one allowlisted exception: `/api/admin/users*`**, which writes only
`APP_USER*`. The assertion carries that allowlist explicitly and additionally asserts that no
handler references `INCIDENT_FACT` or the mirror in a write statement — an endpoint list can
be extended, so the write-target check is the durable half. Scoping this to `/api/incidents` alone left `/api/metrics/**` and any future
`/api/*` route unguarded, and the assertion is the structural guarantee that the read-only
constraint cannot be violated by a careless later controller. `/auth/change-password` is the
one allowed POST and lives outside `/api`. The **database-level backstop** is the mirror
grant in Task 2: `INCIDENT_DASH` holds no write privilege on mirror tables at all.

---

### Task 15: Frontend auth — store, login view, `v-can`, router guard

**Files:** `src/stores/auth.ts`, `src/utils/{can.ts,usePermission.ts}`,
`src/apis/{client.ts,auth.ts}`, `src/views/LoginView.vue`, `src/router/index.ts`,
`src/{stores,utils}/__tests__/{auth,can}.spec.ts`

**Interfaces:** `useAuthStore()` with `.can(menuId, privilege)` and `.defaultRoute`; the
`v-can` directive; an axios interceptor attaching `Authorization: Bearer`. Tasks 18, 22 and
23 all rely on `v-can`.

**Invariants:**
- `evaluate(perms, [menuId, privilege?])` returns true **only on an explicit match** —
  deny by default, including for unknown menus and an empty permission set.
- `can.ts` ports yob's directive with the same `CanTuple`/`CanRule` types, so components
  move between the two apps unchanged.
- The router guard routes to `auth.defaultRoute`, **not a fixed `/`** — that is where "RBAC
  decides the landing view" is actually implemented. It forces `/change-password` when
  `mustChangePassword` is set.
- **Token storage: the access token lives in memory only** (Pinia state, never
  `localStorage`), and the refresh token in an `HttpOnly; Secure; SameSite=Lax; Path=/auth` cookie
  issued by `/auth/token`. A token in `localStorage` is readable by any script on the page,
  which turns the XSS risk in Task 18 into full account takeover. A page reload
  re-establishes the session from the refresh cookie.

**Tests:** `grants when the menu carries the privilege` · `denies a privilege the role does
not hold` · `denies unknown menus rather than defaulting open`.

---

## Phase 4 — Excel parity (the de-risking milestone)

> Everything before this is plumbing. This phase proves the mart is right.
> **Do not start Phase 5 until Task 17 is green.**

### Task 16: Incident query API

**Files:** `domain/usecase/query/IncidentQueryUseCase.java`, `domain/model/IncidentFilter.java`,
`infrastructure/rest/IncidentController.java` + `dto/{req,res}/`,
`domain/usecase/query/IncidentExportUseCase.java`, `query/IncidentQueryIT.java`

**Interfaces:**
`GET /api/incidents?dateFrom&dateTo&tracker&stage&priority&institusi&category&page&size&sort`
→ `{content[], totalElements, totalPages}`. Tasks 18 and 19 consume it.
`GET /api/incidents/export` → the full filtered set as a streamed XLSX. **Server-side, and
this task owns it** — Task 14 authorizes an export endpoint and `AuthFlowIT` tests it, so it
must exist somewhere.

> **Export cannot be a client-side transform of `/api/incidents`.** If it is, the `export`
> privilege is unenforceable by construction: `viewer` holds `incident.ops:view`, can page
> the query endpoint to completion and rebuild the identical workbook. That is exactly the
> failure Task 14's first invariant warns about — "`v-can` hides UI; it does nothing to a
> `curl`" — and the view/export split is the **only** privilege boundary in the system. A
> server endpoint is also the only place bulk egress of customer and staff names can be
> capped and **audit-logged**.

- **The cap and the audit trail are invariants, not rationale.** `/api/incidents/export` is
  capped at `incident.export.max-rows` (default **50,000**) and sets `truncated: true` plus a
  visible banner when it bites — the `size ≤ 200` bound on `/api/incidents` does not apply
  here, so without this the export is the one unbounded egress path in the system.
- **`EXPORT_AUDIT(EXPORT_ID, USERNAME, REQUESTED_AT, FILTER_JSON, ROW_COUNT, TRUNCATED)`** is
  created in `V4__auth.sql` and written on every export. This data is production-derived
  customer and staff names; an untraceable bulk pull of it is the incident nobody can
  reconstruct afterwards.
- Tests: `exportIsCappedAndFlagsTruncation` · `everyExportWritesAnAuditRow`.

**Invariants:**
- Reads `V_INCIDENT`, never `INCIDENT_FACT`.
- `IncidentRowDto`'s field names are **asserted against `V_INCIDENT`'s columns** rather than
  hand-maintained — see § Canonical schema.
- **The sort column arrives from the client and lands in `ORDER BY`**, so it is checked
  against an **enumerated** allowlist — `NO_INCIDENT, DATE_CREATE, UPDATED_ON, CLOSED_ON,
  CLOSE_DATE, RESOLUTION_DATE, STATUS, STATUS_STAGE, PRIORITY, TRACKER, SLA_DAYS, SLA_RATIO,
  AGING_NEW, AGING_INCIDENT, AGING_LAST_STATUS, INSTITUSI, CATEGORY_INCIDENT,
  IMPACT_SEVERITY, URGENCY_SEVERITY, ASSIGNED_TO` — and the direction against `ASC|DESC`.
  Anything else is a **400, not a silent fallback**: falling back hides a client bug and an
  injection attempt behind identical behaviour. Filters bind as parameters.
- **Every client-controlled numeric is bounded, not just validated.** `size` ≤ 200 (default
  50), `limit` ≤ 100 on top-categories, `granularity` ∈ `{day, week, month}`. An unbounded
  `size` is a full-dataset export through the query endpoint, bypassing the `export`
  privilege entirely.
- **Census rows SC004 `CREATE_DATE`, SC025 `AUTHOR_ID` and SC026 `ASSIGNED_TO_ID` are
  exposed by the DTO.** They exist on `V_INCIDENT` but had no delivery path, which made the
  plan's own 100%-census Completeness criterion unreachable.
- **`NO_INCIDENT` is always appended as the final sort key.** Equal sort keys otherwise
  produce an unstable order and paging silently duplicates rows.
- Cancelled incidents (`EXCLUDE_FROM_BACKLOG = 1`) are absent from the default view but
  reachable when the `Cancelled` stage is explicitly requested.

**Tests:** `returnsEveryReportColumn` (asserts **all 35 census field names** by name,
SC001–SC035, so a dropped column fails here rather than in a user's export) ·
`filtersCompose` · `excludesCancelledFromBacklogButKeepsThemReachable` ·
`sortsAndPagesDeterministically` · **`rejectsAnUnknownSortColumn`** (parameterized over
`"; DROP"`, `NO_INCIDENT; --`, `(SELECT 1)`, and a valid-looking non-allowlisted column —
each a 400) · **`rejectsAnOversizedPageRequest`** (`size=100000` → 400, not a full dump).

---

### Task 17: Excel parity test

**Files:** `parity/{ExcelParityIT,XlsxReader,ParityDiff}.java`, `docs/reference/PARITY.md`

**Interfaces:** consumes the delivered `.xlsx` and `V_INCIDENT`; produces the acceptance
evidence that the mart reproduces the delivered report.

**Invariants — three levels, and the third cannot run in CI:**

| Level | What it proves | Runs |
|---|---|---|
| 1 — column parity | Every header in the workbook's `Query1` sheet exists as a mart field. That sheet is a Power Query pull of the source SQL, so its header row **is** the column contract. | always |
| 2 — logic parity on the fixture | The derived classifiers are right. Values are hand-computed from the source query's semantics, so this catches a wrong classifier even though the fixture rows are not the workbook's rows. | always |
| 3 — full row-level diff | The mart reproduces the delivered workbook row for row. | **env-gated on `PARITY_FULL=true`** |

- Level 3 is **gated, not skipped silently**: it needs a mirror loaded from real
  UAT/production data, and the CI fixture cannot contain the workbook's 2025–2026 rows.
  `PARITY.md` records this plainly, with the exact manual command:
  ```bash
  # NOTE the trailing dot: incident.mirror.schema carries its own separator.
  PARITY_FULL=true INCIDENT_MIRROR_SCHEMA=REDMINE_MIRROR. ./mvnw verify -Dit.test=ExcelParityIT
  ```
- `XlsxReader` normalises names (uppercase, strip `_` and spaces) so `No_incident` matches `noIncident`.
- **`ParityDiff` reports three buckets separately** — `missingFromMart`, `extraInMart`,
  `valueMismatches`. A missing row and a wrong value are different bugs and must not be conflated.
- **The sentinel set is `Unclassified`, `Unknown (<id>)`, `Unmapped`, and a sentinel matched
  against a workbook blank is a MATCH, not a mismatch.** The source emits SQL NULL wherever a
  LEFT JOIN misses — an unset custom field, an unresolvable enum id, an unmapped status — and
  this system deliberately renders those as countable sentinels instead, because "unresolved
  values must never vanish from an aggregate". Without this rule the two designs disagree on
  every such row and the Level-3 release gate is non-empty **by construction** on the first
  real run, exactly as `extraInMart()` was before the window fix. The plan already carves out
  `AUTHOR` for the same reason; this generalises it.
- Test: `sentinelLabelsAreNotCountedAsValueMismatchesAgainstBlanks`.

**Tests:** `martExposesEveryColumnTheDeliveredReportHas` ·
`reproducesTheReportsDerivedValuesOnKnownRows` · `reproducesEveryRowOfTheDeliveredWorkbook` (gated).

> **Level-2 expected values must be derived from the fixture, not typed in.** The previous
> draft asserted `SLA_DAYS = 3` for issue 22868 without checking that issue's actual
> `PRIORITY_ID` in `01. redmine.issues.sql`. Under the tests-are-truth rule a wrong constant
> here pushes an implementer to break `DerivedFields` to make it green — the failure mode the
> rule exists to prevent. **Read the fixture first**, then assert:
> `SLA_DAYS` equals `CFG_SLA_POLICY`'s value for that issue's priority; `SOLUTION` for
> 22868/22878/22882 follows from the hand-authored `custom_values.csv` in Task 6, whose rows
> were chosen precisely to make those three outcomes unambiguous.

---

### Task 18: Ops shell — filter bar, detail table, export

**Files:** `src/views/OpsView.vue`, `src/layout/AppLayout.vue`,
`src/components/filters/IncidentFilterBar.vue`,
`src/components/table/{IncidentTable,IncidentDetailDrawer}.vue`,
`src/stores/filters.ts`, `src/apis/incidents.ts`, `src/utils/export.ts`, + specs

**Interfaces:** `useFiltersStore()` with URL sync. Tasks 20, 21 and 23 read the same store,
so every widget shares one filter state.

**Invariants:**
- `fromQuery` is zod-validated: unknown keys are dropped and invalid values fall back to the
  default rather than throwing. **A stale bookmarked URL must still load the page.**
- **The date-range control is ONE PrimeVue `DatePicker` in range mode**, not two inputs and
  not a custom two-calendar popup:
  ```vue
  <DatePicker v-model="period" selectionMode="range" :numberOfMonths="1"
              :manualInput="false" showIcon iconDisplay="input"
              dateFormat="dd/mm/yy" placeholder="Period" class="rounded-xl" />
  ```
  `selectionMode="range"` is what four yob screens use (MerchantApplication,
  AfasManagement, ChannelTable, ContentConfig); we take the same component with
  `numberOfMonths=1` for a single calendar view. PrimeVue cannot produce a popup with two
  independently-navigable calendars, so building one means a custom component — and it costs
  the user two separate navigations to pick a range outside the current month.
- `DataTable` is `lazy` with `paginator` and `sortField`/`sortOrder` wired to the API.
- Row click opens `IncidentDetailDrawer` with the journal timeline and a **Redmine deep
  link — the only "action" this app offers.**
- **All Redmine-authored free text is rendered as text, never as HTML.** `SUBJECT`,
  `WORKAROUND`, `ROOTCAUSE` and the journal timeline are written by Redmine users, so they
  are untrusted input to this app. Use `{{ }}` interpolation; **`v-html` is banned on this
  surface** and a lint rule enforces it. Combined with in-memory token storage (Task 15),
  that closes the stored-XSS-to-token-theft chain.
- The Redmine deep link is built from a **configured base URL plus the numeric issue id** —
  never from a URL field in the data.
- `HAS_DATE_REJECT` rows carry a warning badge with the raw value in the tooltip.
- **Export must re-request the full filtered set** (capped, with an explicit warning above
  the cap), not export the visible page. Server-side paging means the page is not the result
  set, and silently exporting page 1 of 40 is the failure mode to avoid. Gated on the
  `export` privilege via `v-can`.

**Tests:** `serialises to a query string a colleague can paste` · `round-trips through the
URL` · `ignores unknown query keys rather than throwing` · `export requests the full filtered
set, not the current page` · **`renders a subject containing markup as literal text`**
(mount with `<img src=x onerror=alert(1)>` as the subject; assert it appears escaped and no
element was created).

---

## Phase 5 — Metrics and the three views

### Task 19: Metrics API

**Files:** `domain/usecase/query/MetricsUseCase.java`,
`infrastructure/rest/MetricsController.java` + response DTOs, `query/MetricsIT.java`

**Interfaces** — all accept the same filter parameters as `/api/incidents`:
- `GET /api/metrics/kpi` → `{open, breached, atRisk, mttrWorkaroundDays, mttrPermanentDays, workaroundDebt, previousPeriod:{…}}`
- `GET /api/metrics/matrix` → `[{impact, urgency, count}]`
- `GET /api/metrics/sla-buckets` → `[{bucket, count}]`
- `GET /api/metrics/trend?granularity=month` → `[{period, created, closed, backlog}]`
- `GET /api/metrics/top-categories?limit=10` → `[{category, count, deltaPct}]`

**Invariants:**
- All aggregates read `V_INCIDENT` and **share one filter-predicate builder with Task 16**,
  so a filter change can never diverge between the table and the charts.
- `atRisk` is `SLA_RATIO >= 0.8 AND NOT breached`.
- `workaroundDebt` = incidents with a resolution date but no close date. **This is the
  headline metric** — the gap between the two MTTRs is the insight this dataset uniquely
  supports — and it is derived, never stored.
- **Every row in the *matrix's own population* lands in a cell.** `Unclassified` and
  `Unknown (…)` are real cells, and the sum of cell counts equals
  `SELECT COUNT(*) FROM V_INCIDENT <same filter, same EXCLUDE_FROM_BACKLOG rule>` — **not**
  the unfiltered `COUNT(*)`. Stating it as the raw table count contradicts the
  cancelled-exclusion rule directly below, and the two assertions cannot both go green.
  **One filter-predicate builder produces both sides of that equality**, so they cannot drift.
- Cancelled incidents (`EXCLUDE_FROM_BACKLOG = 1`) are excluded from backlog and aging
  aggregates. Whether the matrix includes them is a **single documented choice**, applied by
  the shared builder: it does **not**, matching the backlog rule.
- `previousPeriod` mirrors the **requested window length** — a 30-day window compares
  against the preceding 30 days, not a calendar month.
- **MTTD must not appear in any field, chart, label or doc.** It is not computable from this
  dataset: we have ticket-creation time, never incident-start time.

**Tests:** `kpiCountsOpenBreachedAndAtRisk` · `reportsBothMttrsAndTheWorkaroundDebtBetweenThem`
· `matrixIncludesUnclassifiedAndUnknownAsRealCells` (cell total == the count returned by the
**same filter predicate**, cancelled excluded — see Invariants) ·
`trendReportsCreatedClosedAndBacklogPerMonth` · `previousPeriodMirrorsTheRequestedWindowLength`
· `cancelledIncidentsAreExcludedFromBacklogAndAging` · **`noEndpointExposesAnythingNamedMttd`**.

---

### Task 20: Ops view — KPI row, heatmap, buckets, trend, heartbeat

**Files:** `src/components/kpi/{KpiRow,KpiCard}.vue`,
`src/components/charts/{ImpactUrgencyHeatmap,SlaBucketBars,CreatedClosedTrend}.vue`,
`src/components/common/FreshnessIndicator.vue`, `src/apis/metrics.ts`, + specs

**Interfaces:** consumes `/api/metrics/*`, `/api/freshness`, `useFiltersStore()`.

**Invariants:**
- `KpiCard` shows value + period-over-period arrow, and is **clickable** → applies the
  matching filter and scrolls to the table.
- The heatmap is a **CSS grid, not chart.js** — a matrix of clickable cells with counts
  reads better and stays accessible. It emits `drill` with the cell coordinates.
- `Unclassified` and `Unknown (…)` render as **real cells**, and the displayed total
  accounts for every incident.
- **No cell may be painted with the brand primary** (`#59d8f8`, `#19c9f5`, `--p-primary`).
- `FreshnessIndicator` polls every 30s: green dot + "live" normally, amber banner with the
  last-update time when `stale`.

**Tests:** `renders Unclassified and Unknown as real cells` · `accounts for every incident in
the totals` · `emits a drill-through with the cell coordinates` · `never paints a cell with
the brand primary`.

---

### Task 21: Wallboard view

**Files:** `src/views/WallboardView.vue`, `src/layout/WallboardLayout.vue`, + spec

**Interfaces:** consumes `/api/metrics/kpi` and `/api/freshness`. **No filter store — the
wallboard is deliberately unfiltered.**

**Invariants:**
- Own route `/wallboard`, outside `AppLayout`. No navigation chrome, no filter bar.
- Forces dark mode regardless of user preference. Breach and at-risk counts only, at display
  type size.
- 60s auto-refresh, **timer cleared on unmount**.
- **A prominent stale warning** — an ops-room TV showing silently frozen numbers is worse
  than a blank screen.

**Tests:** `forces dark mode regardless of user preference` · `renders no navigation chrome`
· `auto-refreshes on an interval and cleans the timer up on unmount` · `shows a prominent
stale warning when freshness reports stale`.

---

### Task 22: Exec view

**Files:** `src/views/ExecView.vue`,
`src/components/charts/{MttrComparison,WorkaroundDebtCard,TopCategories}.vue`, + spec

**Interfaces:** consumes `/api/metrics/{kpi,trend,top-categories}` with `granularity=month`.

**Invariants:** monthly pulse layout — both MTTR series plotted together, workaround debt as
a headline figure, top categories, and a period-over-period delta on **every** figure.
Defaults to a monthly window, not live.

**Tests:** `plots time-to-workaround against time-to-permanent` · `surfaces workaround debt
as a headline figure` · `defaults to a monthly window, not live` · `shows period-over-period
deltas on every figure`.

---

### Task 23: Tier-3 Playwright walkthrough driver

**Files:** `playwright.config.ts`,
`e2e/{login,ops-happy-path,ops-error-paths,wallboard,exec}.spec.ts`, `e2e/fixtures/seed.ts`

**Interfaces:** consumes a running backend against the Testcontainers-seeded Oracle; produces
the tier-3 command declared in `AGENTS.md`, with a screenshot per step.

**Invariants:**
- Logs in as the `V7__seed_test_users.sql` accounts (Task 13). Password from `E2E_PASSWORD`.
- **Happy path must assert RBAC landing** — an ops manager arrives at `/ops` without choosing
  — and **that the filter is in the URL**, because a shareable view is the point.
- Then: drill through a heatmap cell → the table narrows; open a row → the detail drawer
  shows a Redmine deep link.
- **Error paths, at minimum:** bad credentials · expired token mid-session → redirect to
  login · the stale-freshness banner · an empty filter result showing an empty state rather
  than a broken chart.
- Screenshot at each key step, under `e2e/screenshots/` — **gitignored**, because the
  captures render real incident subjects.

- [ ] Steps: happy-path spec → error-path spec → run the suite → commit config + specs, **not** screenshots.

---

## Review Gates

| Phase | Tier 1 | Tier 2 | Tier 3 | Notes |
|---|---|---|---|---|
| -1 Scaffolding | ✅ Tasks 3–4 | n/a | n/a | No behaviour to integration-test yet. |
| 0 Substrate | ✅ | ✅ Task 6 | n/a | Task 5 flips `substrate_ready: true`. |
| 1 Schema/labels | ✅ Task 8 | ✅ Task 7 | n/a | |
| 2 Mart | ✅ Task 10 | ✅ Tasks 11–12 | n/a | Migration-touching phase → tier 2 mandatory. |
| 3 Auth | ✅ Tasks 13, 15 | ✅ Task 14 | ⏸ deferred | See deferral 1. |
| 4 Excel parity | ✅ Task 18 | ✅ Tasks 16–17 | ⏸ deferred | See deferral 1. |
| 5 Views | ✅ Tasks 20–22 | ✅ | ✅ Task 23 | Final wave runs all three. |

### Completeness

- **Completeness:** 100% of scope census items have evidence in the artifact bundle.
- GIVEN the final wave has finished, WHEN `verify-wave --final` runs, THEN it MUST report `census_coverage.expected_rows == census_coverage.covered_rows` with no entries in `missing`.

### Deliberate deferrals

**1. Tier 3 is deferred for Phases 3 and 4.**
- **Phase:** 3 (auth) and 4 (Excel parity).
- **Tier:** 3 (Playwright walkthrough).
- **Reason:** the walkthrough driver is built in Task 23. Writing throwaway specs against a half-built ops view twice over is waste.
- **Follow-up trigger:** Task 23 completing. The final wave then runs tier 3 across the whole scope census, including the auth and table surfaces skipped here. **No phase may be marked complete on a tier-3 deferral after Task 23 lands.**

**2. Excel parity Level 3 (full row diff) cannot run in CI.**
- **Phase:** 4.
- **Tier:** 2 (integration), partial.
- **Reason:** the fixture holds 100 issues from 2026-07-01; the delivered workbook holds 2025–2026 production rows. A full row-level diff needs a mirror loaded from real UAT or production data, which CI does not have and should not have.
- **What still gates the phase:** Levels 1 (column parity) and 2 (derived-value parity on known fixture rows) run in CI and must pass.
- **Follow-up trigger:** before `/straventa-release`, run `PARITY_FULL=true ./mvnw verify -Dit.test=ExcelParityIT` against UAT and attach the diff output to the release evidence. **A non-empty `valueMismatches` blocks release** — it means the mart disagrees with the report the business already relies on.

### Not deferred, called out explicitly

- **Impact×urgency fill rate is unmeasured.** The sample `custom_values` export was unusable (wrong filter column), so we do not know how often fields 93/94 are populated in production. The heatmap is built to degrade honestly — `Unclassified` is a real, countable band — but if production turns out to be majority-unclassified, the ops view's headline widget should be reordered to lead with SLA buckets instead. **Check this the first time the service points at real data**, before the ops view is shown to stakeholders.
- **`custom_field_enumerations` and `users` are not streamed.** `REF_CUSTOM_FIELD_ENUM` and `REF_USER` are seeded manually and will drift as Redmine admins add values. Every unmapped id is visible as `Unknown (<id>)` by design, and a recurring check of `SELECT DISTINCT *_ID FROM INCIDENT_FACT WHERE * LIKE 'Unknown%'` is the operational signal that a re-seed is due. If the sync team later agrees to stream those two tables, flip `incident.label.enum-resolver=mirror` — no code change.

---

## Scope Census (auto-derived 2026-08-20)

Surface: incident-dashboard
Derivation: the standard file-based grep recipes return 0 rows — the tree is greenfield
and nothing is scaffolded until Task 4, so there are no files to enumerate. The census is
therefore derived from the artifact that does exist and that defines "done":
the SELECT surface of `docs/reference/Query Detail Redmine Incident, Problem, SR.sql`,
extracted programmatically and pruned of internal join keys
(`USER_ID`, `ADDRESS`, `LASTNAME`, `DATE`, `AGING`).

This is the surface where partial delivery is the real risk: a dashboard that ships 25 of
34 report columns looks finished and silently fails Excel parity.

Pattern: each row must be (a) materialised in `INCIDENT_FACT` or computed in `V_INCIDENT`,
(b) exposed by `GET /api/incidents`, (c) rendered in the detail table, and
(d) present in the XLSX export.
Sites (35):
- SC001 `NO_INCIDENT`           [ ]
- SC002 `TRACKER`               [ ]
- SC003 `DATE_CREATE`           [ ]
- SC004 `CREATE_DATE`           [ ]
- SC005 `CREATE_MONTH`          [ ]
- SC006 `UPDATED_ON`            [ ]
- SC007 `CLOSED_ON`             [ ]
- SC008 `AGING_INCIDENT`        [ ]
- SC009 `STATUS`                [ ]
- SC010 `PRIORITY`              [ ]
- SC011 `SLA`                   [ ]
- SC012 `SUBJECT`               [ ]
- SC013 `PARENT_ID`             [ ]
- SC014 `DONE_RATIO`            [ ]
- SC015 `TYPE_OF_INCIDENT`      [ ]
- SC016 `CATEGORY_INCIDENT`     [ ]
- SC017 `EXTERNAL_INTERNAL`     [ ]
- SC018 `IMPACT_SEVERITY`       [ ]
- SC019 `URGENCY_SEVERITY`      [ ]
- SC020 `INSTITUSI`             [ ]
- SC021 `RESOLUTION_DATE`       [ ]
- SC022 `CLOSE_DATE`            [ ]
- SC023 `MIGRATION_DATE`        [ ]
- SC024 `AUTHOR`                [ ]
- SC025 `AUTHOR_ID`             [ ]
- SC026 `ASSIGNED_TO_ID`        [ ]
- SC027 `DEVELOPER_ASSIGNED`    [ ]
- SC028 `WORKAROUND`            [ ]
- SC029 `ROOTCAUSE`             [ ]
- SC030 `AGING_NEW`             [ ]
- SC031 `SOLUTION`              [ ]
- SC032 `ASSIGNED_TO`           [ ]
- SC033 `LAST_STATUS`           [ ]
- SC034 `LAST_DATE_STATUS`      [ ]
- SC035 `AGING_LAST_STATUS`     [ ]

Verification: Tier 2 — `ExcelParityIT.martExposesEveryColumnTheDeliveredReportHas`
(Task 17, Level 1) asserts the API field set covers every header in the delivered
workbook; `IncidentQueryIT.returnsEveryReportColumn` (Task 16) asserts the same set by
name. Tier 3 — the Playwright walkthrough (Task 23) confirms the columns render and
export. A census row is covered only when its ID appears in a task manifest's
`census_covered` AND both tier-2 assertions pass.

> Pattern Reference TODO (Wave 1 deliverable): `auth` not yet documented. Scaffold under .agents/patterns/.

---

## Scale note

This plan is large: 23 tasks across 7 phases. The natural split point is **after Task 18**
(Excel parity shipped, the Power Query workflow replaced). Everything through Task 18 is
one coherent deliverable that stands on its own and delivers the actual business win.
Tasks 19–23 are the chart and multi-view layer, and could reasonably become a second plan
if the first half runs long.

### Why this document is shaped the way it is

Rounds 1 and 2 both returned BLOCK, and round 2's findings were dominated by
`REGRESSION` — the round-1 fixes had been applied in one place and not propagated to the
four others that restated the same facts. **That was a property of the document.** It ran
to 3,900 lines of near-complete implementations, so every schema change was a five-edit
synchronisation problem.

The plan has since been **cut to interfaces, invariants and test names** (3,967 → ~1,500
lines). Two rules keep it that way:

1. **`V3__mart.sql` is the only place mart columns are enumerated.** The MERGE, the DTO and
   the export derive from it, and `MartSchemaIT.everyFactColumnAppearsInTheMergeStatement`
   fails if they drift.
2. **Implementations are not written out.** Blocks marked `[SCHEMA]` or `[PARITY]` are the
   specification and must be reproduced exactly; everything else is intent, and
   `/straventa-tdd` writes the code against the stated contracts.

If a future amendment reintroduces a second copy of a column list, it has reintroduced the
failure mode.

---

## Quorum Review Log

**Run:** 2026-08-20 · workflow `wf_60391555-73e` · 3 personas, 402k subagent tokens, 10m04s
**Verdict:** **BLOCK** (3/3 personas voted BLOCK) — 16 BLOCKER, 23 MAJOR, 8 MINOR

| Persona | Verdict |
|---|---|
| senior-software-architect | BLOCK |
| mti-monorepo-expert | BLOCK |
| security-engineer | BLOCK |

### Blockers folded in (16 raw → 9 distinct after dedupe)

| # | Area | Fix applied |
|---|---|---|
| B1 | ETL | Dimension names (`STATUS`/`TRACKER`/`PRIORITY`) no longer materialised — joined live in `V_INCIDENT`, so an admin rename is correct instantly. `CV_DIGEST` added to `INCIDENT_FACT` and to the reconcile checksum, closing the shared blind spot. Two new `ReconcileIT` tests. |
| B7 | ETL | `RefreshResult` split into `scanned` / `changed`; the 300s overlap no longer makes assertions unreachable. |
| B8 | ETL | `solution()` takes raw strings, not `LocalDate` — SQL `NULL` and `''` are different inputs and produce different results. The `close IS NULL, res = ''` → `Workaround Solution` quirk is now specified and tested. **Verified against the source query.** |
| B9 | schema | All aging/SLA expressions anchor on `TRUNC(DATE_CREATE)`; the source anchors on a date-formatted string, so the raw `TIMESTAMP` was an off-by-one day. **Verified against the source query.** |
| B2·B5·B6 | testing | `AbstractOracleIT` rewritten: Flyway `migrate()` once via a latch, idempotent mirror DDL, `resetAll()` truncate per test, plus the `execute`/`scalar`/use-case accessors the ITs already assumed. |
| B4·B10 | testing | Mirror addressing unified — `incident.mirror.schema` now carries its own trailing separator (`REDMINE_MIRROR.` prod / `REDMINE_MIRROR_` test) and every test uses the prefix form. |
| B11 | testing | `org.testcontainers.oracle.OracleContainer` (oracle-free module), not `containers.*`. `.withReuse(true)` dropped. |
| B3·B12·B16 | auth | `V5` split: SQL for RBAC data, `V6__SeedAdmin.java` for the bcrypt admin seed (round 4 replaced this with a **repeatable** `R__SeedAdmin.java` overriding `getChecksum()`). `spring-security-crypto` added. `MUST_CHANGE_PASSWORD` forces rotation of the bootstrap credential. |
| B13 | frontend | Missing devDeps added; `pnpm lint` made non-mutating in the tier-1 gate (`lint:fix` split out). |
| B14 | auth | Refresh tokens carry `typ=refresh` + `jti`; `validate()` enforces the expected type. A refresh token is no longer a 24h access token. |
| B15 | auth | **Server-side authorization added** — `@RequirePrivilege` + `PrivilegeInterceptor`, failing closed on any undecorated `/api` handler. Four new `AuthFlowIT` tests. `v-can` alone left the whole dataset readable by any authenticated account. |
| — | data exposure | `.gitignore` added as Task 1 Step 0; the three commit steps that would have pushed production incident text into git history now stage only schema, code and docs. |

### MAJOR/MINOR list (all folded 2026-08-21 — retained as the audit trail)

Full text: `/private/tmp/claude-501/-Users-henseljahja-Project-mti-incident-dashboard/3215587a-ca12-48ee-8609-4f942b5b0635/tasks/w5ichtoo6.output`

- **schema** — Task 9 (V_INCIDENT) + Task 19 (KPI vs sla-buckets): V_INCIDENT's SLA_BREACHED and SLA_BUCKET use different formulas, so the KPI card and the SLA bucket chart on the same Ops screen will disagree, and no closed incident can ever be counted as an SLA breach — which silently deletes the workaround-debt population from breach reporting.
- **ETL** — Task 11 Step 3 (incident-delta.sql WHERE clause) + Task 17 Level 3: The delta query drops the source report's rolling two-calendar-year window, so the mart will contain rows the delivered workbook never had — which makes the release-gating Level-3 parity assertion `extraInMart().isEmpty()` fail by construction.
- **testing** — Task 17 Step 1, `reproducesTheReportsDerivedValuesOnKnownRows`: The Level-2 parity assertion for issue 22868 asserts the wrong value against the shipped sample data, and under the tests-are-truth rule an implementer will be pushed to break DerivedFields to make it green.
- **ETL** — Task 7 (CFG_JOURNAL_PROP_MAP / CFG_CUSTOM_FIELD_MAP) vs Task 11 (incident-delta.sql, MERGE_SQL): The config tables are decorative — the plan's central 'no Redmine ID in code' principle is violated by the very task that is supposed to consume them, so adding a config row does nothing at all.
- **ETL** — Task 10 (DerivedFields.slaDays) + Task 9 (SLA_DAYS materialised NOT NULL): The SLA ladder is a Java switch on a Redmine-admin-controlled string and is materialised into every fact row, so a priority rename silently reclassifies incidents from 60-day to 3-day SLA with no alert and no way to correct history.
- **ETL** — Task 11 Step 4 (MartRefreshUseCase.refresh) + Task 12 Step 4 (SchedulingConfig) + Task 9 (ETL_WATERMARK seed): The first run after deploy is a full-history load of ~23k issues, executed row-at-a-time inside one @Transactional, on a 2-minute cron — and if it fails the watermark never advances, so it retries the whole thing forever.
- **schema** — Task 9 (V_INCIDENT SYSTIMESTAMP expressions) + Task 2 (docker-compose) + Task 6 (OracleTestContainer): Nothing pins a timezone anywhere, yet six view expressions compare SYSTIMESTAMP against mirror timestamps that carry MySQL local wall-clock time — a 7-hour offset shifts the TRUNC day boundary and changes AGING for roughly a third of rows.
- **testing** — Task 19 Step 1, `matrixIncludesUnclassifiedAndUnknownAsRealCells` vs `cancelledIncidentsAreExcludedFromBacklogAndAging`: Two assertions in the same test class contradict each other, so Task 19 cannot go green as written.
- **scope** — Scope Census (SC004, SC025, SC026) vs Task 16 Step 1 `returnsEveryReportColumn`: Three census rows have no delivery path in any task, so the plan's own Completeness criterion (100% census coverage) is unreachable.
- **testing** — Task 3 Step 1 / Step 6 (IncidentDashboardApplicationTests): The Task 3 context will not start at Step 6: excluding only DataSourceAutoConfiguration leaves JPA and Flyway autoconfiguration demanding a DataSource that no longer exists.
- **ETL** — Task 11 Step 3 (`incident-delta.sql`) vs Global Constraints: The delta query hardcodes the journal prop_keys the plan's own Global Constraints forbid hardcoding, leaving `CFG_JOURNAL_PROP_MAP` as dead config that Task 7 still tests.
- **scope** — Task 11 Step 3 (delta WHERE clause) and Task 17 Level 3: The mart materialises all history while the delivered report is windowed to the last two calendar years, so the release-blocking Level-3 parity diff will always report `extraInMart()` as non-empty.
- **ETL** — Task 2 Step 2 (`init/01-schemas.sql`): The local Oracle stack never grants INCIDENT_DASH read access to REDMINE_MIRROR and no task ever creates the mirror tables there, so the service cannot actually run against `BackEnd/local-infra`.
- **testing** — Task 6 Step 5 (`OracleTestContainer`): `withReuse(true)` is both inert by default and, when honoured, actively breaks the harness — and the accompanying comment about Ryuk is factually wrong.
- **testing** — Task 6 Step 3/4 (`extract-fixtures.py`) and Task 10 / Task 11: The CSV fixture pipeline erases the SQL-NULL vs empty-string distinction in `custom_values.value` — the exact distinction the SOLUTION classifier turns on.
- **scope** — Scope Census (SC004, SC025, SC026) vs Task 16 Step 1 and Task 9 Step 3: Three census rows have no column in `INCIDENT_FACT`/`V_INCIDENT` and no assertion anywhere, so the plan's own Completeness criterion is unsatisfiable.
- **auth** — Task 13 Step 4 (V4__auth.sql) + Task 14: The bootstrap admin credential can never be rotated by a human and can be guessed without limit: APP_USER has no forced-rotation, lockout or last-login columns, no password-change endpoint exists anywhere in the plan, and /auth/token has no throttle.
- **data exposure** — Task 1 Step 4, Task 6 Steps 4/8, Task 23 Step 4: Three commit steps push production customer data into git history, directly contradicting this project's own Data Sensitivity rule, and no task ever creates a .gitignore.
- **secrets** — Task 3 Step 5 (application.yml): application.yml ships a fallback database password in the production artifact — the exact anti-pattern the plan corrects for the JWT secret.
- **read-only guarantee** — Task 14 Step 1 (noWriteEndpointExistsForIncidentData) + Task 2 Step 2 (init/01-schemas.sql): The structural read-only assertion is scoped to /api/incidents only, and there is no database-level backstop — the local-infra init script claims to grant read-only mirror access but contains no GRANT to INCIDENT_DASH at all.
- **injection / input validation** — Task 16 Steps 1 & 3, Task 19 Interfaces: The ORDER BY allowlist is asserted but never enumerated, its test has an empty body, and three other client-controlled parameters (size, limit, granularity) reach SQL or the result-set size with no stated bound.
- **frontend / data exposure** — Task 18 Step 4 + Task 15 Step 3: Redmine-authored free text (subject, workaround, rootcause, journal timeline) is rendered in the detail drawer with no stated escaping rule, and the plan never says where the JWT is stored — together that is a stored-XSS-to-token-theft chain.
- **logging / error handling** — Task 14 Step 3 (AuthFilter port): Porting yob's AuthFilter shape without its masking configuration will log the login request body — i.e. plaintext passwords — and the plan additionally specifies no CORS policy and assigns no task to the error handler it lists in the File Structure.

**Status 2026-08-21: all of the above are folded.** Round 2's MAJOR list was substantially
the same set, still unaddressed. See the Round 3 preamble below for the fix map.

---

## Quorum Review Log — Round 2

**Run:** 2026-08-21 · 3 personas (staff-engineer · senior-software-architect · security-engineer)
**Verdict:** **BLOCK** (3/3). Round-1 blocker audit: **3 CLEARED · 14 PARTIAL · 1 REGRESSED** of 18.
29 blockers / 38 regressions / 17 major / 4 minor raised, deduping to ~15 distinct defects.

### Root cause

Almost every round-2 finding carries a `REGRESSION` tag, and they share one cause: **round-1's
fixes were applied surgically and never propagated.** This plan restates the same schema facts in
five places — the DDL, the MERGE column list, the delta query, each task's `Interfaces` block, and
the test assertions. Changing `INCIDENT_FACT` therefore requires five synchronised edits. Round 1
made one each time.

That is a property of this document, not of the reviewers. See **Scale note** below.

### Round-2 fixes applied

| Cluster | Fix |
|---|---|
| **Aging (REGRESSED — self-inflicted)** | Round 1 applied `TRUNC` to all three aging columns; the source truncates only `AGING_NEW`. `AGING_INCIDENT` / `AGING_LAST_STATUS` restored to `TRUNC(d2 - d1)` (elapsed days); `AGING_NEW` keeps `TRUNC(d2) - TRUNC(d1)` (calendar days). Anchor table added to Global Constraints. |
| **SOLUTION truth table** | `close = '' , res IS NULL` returned `Workaround Solution`; the source returns `Permanent Solution` — `res <> ''` is UNKNOWN under three-valued logic, so branch 2 cannot fire. Branch-2 predicate corrected; both directions now in the truth table and asserted. |
| **B1 non-propagation** | `V3` indexed dropped columns (migration would fail); MERGE wrote six removed columns and omitted `PRIORITY_ID` / `CV_DIGEST`; the delta query never selected `PRIORITY_ID`; a test read `STATUS_STAGE` off the fact table. All four corrected. |
| **V_INCIDENT joins** | `INNER JOIN` to the mirror silently dropped incidents whose dimension row was missing — the exact failure the "an incident must never vanish" constraint forbids. All joins now `LEFT`, with `NVL(s.NAME, 'Unknown (' \|\| f.STATUS_ID \|\| ')')`. |
| **CV_DIGEST** | Was not computable identically on both sides → permanent false drift. Expression now defined **once**, in the `V3__mart.sql` comment, and computed by Oracle in the same statement that loads custom values. |
| **Idempotence unreachable** | MERGE had no content predicate and set `ETL_UPDATED_AT` unconditionally, so `changed` always equalled `scanned` and the "second run is a no-op" assertion could never pass. `WHERE DECODE(...)` predicate added on `SOURCE_UPDATED_ON` / `CV_DIGEST`. |
| **Test harness did not compile** | Undefined `context()`, missing `@BeforeEach` import, no Spring context, every helper leaking a pooled connection. Now `@SpringBootTest` + `@Testcontainers` + `@DynamicPropertySource` (which also sets `spring.flyway.enabled=false`, so Spring cannot run a second unqualified migration); all helpers close their connections; `createMirrorSchema` genuinely idempotent. |
| **V6 aborted all of tier 2** | The `INCIDENT_ADMIN_PASSWORD` fail-fast ran inside the harness's `migrate()`, so every IT died in `@BeforeAll` on a clean CI checkout. Now warns and returns when unset; still hard-fails when set but under 12 chars. |
| **Admin lockout** | `MUST_CHANGE_PASSWORD` closed the only login path with no way back. Added `POST /auth/change-password`, the scoped token, and `V7__seed_test_users.sql` — `AuthFlowIT` and the Playwright specs had no seedable accounts at all. |
| **Authorization was inert** | `PrivilegeInterceptor` was never registered with Spring. Added `WebConfig implements WebMvcConfigurer`; annotated `/api/freshness` (fail-closed would otherwise have 500'd every heartbeat); mapped `AccessDeniedException` → 403 and marked it **ours**, since the security starter is deliberately absent. |
| **JwtService did not compile** | `refreshValiditySeconds` and `parse()` were referenced but undefined. Both added; the surviving single-arg `validate(token)` that bypassed the token-type check removed. |
| **Lint gate graded its own rewrite** | `lint:fix` was globbed back in by `run-s lint:*`, so the tier-1 gate rewrote the source it was grading. Renamed to `pnpm fix`; `lint` now names its sub-scripts explicitly. |

### Consistency sweep

Seven greps run after folding, all returning zero: stale `solution(LocalDate` signatures · stale
`RefreshResult(int merged` · `INNER JOIN` to mirror in `V_INCIDENT` · indexes on dropped columns ·
MERGE writing dropped columns · undefined `context()` · surviving single-arg `validate(token)`.

### Outstanding — folded 2026-08-21

The round-2 workflow output file was cleared before it could be transcribed in full, so only
the highest-value item was captured verbatim. **That loss turned out to be small**: round 2's
MAJORs were substantially round 1's, still unaddressed and still listed above. All are now
folded — see the Round 3 preamble.

The captured item, and the one that mattered most:

> **The config tables are decorative.** `incident-delta.sql` hardcodes the same custom-field and
> journal prop-key IDs that `CFG_CUSTOM_FIELD_MAP` / `CFG_JOURNAL_PROP_MAP` are meant to own, so
> inserting a config row changes nothing. This contradicts the plan's own "a hardcoded Redmine ID
> is configuration, not code" principle — stated in `AGENTS.md` and enforced by Task 7, then
> violated by Task 11, the very task that is supposed to consume it.

**Fixed:** the delta now joins `CFG_JOURNAL_PROP_MAP` and `CFG_TRACKER_SCOPE` instead of
hardcoding, custom fields are mapped through `CFG_CUSTOM_FIELD_MAP`, and two tests assert
that a config-row change alters the mart with no code change.

### Scale note — read before round 3

Two consecutive BLOCK verdicts, both dominated by cross-reference drift rather than by wrong
thinking. The plan is ~3,900 lines and carries near-complete implementations. Every schema change
is a 5-edit synchronisation problem, and each round of fixes has introduced a fresh crop of
regressions.

Two ways out, both structural:

1. **Single source of truth** — one `## Canonical Schema` section that the DDL, the MERGE, the
   delta query and the `Interfaces` blocks all *reference* rather than restate.
2. **Trim to intent** — cut the task bodies back to interfaces, invariants and test names; let
   `/straventa-tdd` write the implementations against those contracts.

Option 2 is the smaller change and removes the drift surface entirely, at the cost of the plan no
longer being copy-pasteable. Recommended.

**Round 3 is required either way.** Round 2 fixed regressions introduced by round 1; nothing yet
confirms round 2 did not do the same.

---

## Round 3 preamble — what changed before this review

**Structural.** The plan was cut from 3,967 to ~1,700 lines. Task bodies are now interfaces,
invariants and test names; implementations are left to `/straventa-tdd`. Blocks marked
`[SCHEMA]` / `[PARITY]` are the exception and remain verbatim specification. `V3__mart.sql`
is the sole enumeration of mart columns, enforced by
`MartSchemaIT.everyFactColumnAppearsInTheMergeStatement`. See § Scale note.

**MAJORs folded** (round 1's list; round 2's overlapped it almost entirely):

| # | Finding | Fix |
|---|---|---|
| M1 | `SLA_BREACHED` and `SLA_BUCKET` used different formulas, and `IS_CLOSED = 1 THEN 0` made a closed incident unbreachable — deleting the workaround-debt population from breach reporting | One shared elapsed expression feeds ratio, breach and bucket. Closing late is a breach. Two new tests, incl. an XOR check across every row. |
| M2 | Delta dropped the report's rolling two-calendar-year window → `extraInMart()` non-empty by construction, blocking release | Window added to `incident-delta.sql`; reconcile prunes rows that age out. |
| M3 | Level-2 parity asserted `SLA_DAYS = 3` for issue 22868 without checking its actual priority — tests-as-truth would push an implementer to break `DerivedFields` | Expected values must be **read from the fixture**, not typed in. Stated explicitly. |
| M4 | Config tables decorative | Delta joins `CFG_JOURNAL_PROP_MAP` / `CFG_TRACKER_SCOPE`; custom fields mapped via `CFG_CUSTOM_FIELD_MAP`; two tests prove a config change moves the mart. |
| M5 | SLA ladder was a Java switch on an admin-editable priority **name**, materialised into every row | New `CFG_SLA_POLICY` keyed on `PRIORITY_ID`. Unknown id → 3 **and logged**. |
| M6 | First run = ~23k issues row-at-a-time in one `@Transactional` on a 2-min cron; on failure the watermark never advances and it retries forever | Bounded chunks, commit + advance watermark per chunk, non-reentrant lock. |
| M7 | No timezone pinned anywhere; six view expressions compared `SYSTIMESTAMP` against offset-naive MySQL wall clock — a 7-hour skew moves the `TRUNC` boundary | `Asia/Jakarta` pinned in compose, Testcontainers and the JVM; view uses `SYSDATE`. Day-boundary test added. |
| M8 | Two assertions in `MetricsIT` contradicted each other — Task 19 could not go green | Matrix total is measured against the **same filter predicate**, cancelled excluded. |
| M9 | SC004/SC025/SC026 had no delivery path → the 100%-census Completeness criterion was unreachable | `CREATE_DATE` added to `V_INCIDENT`; all three exposed by the DTO; the test asserts 35 names. |
| M10 | Task 3 context could not start — excluding only `DataSourceAutoConfiguration` left JPA and Flyway demanding one | All three excluded. |
| M11 | local-infra never granted mirror access and never created the mirror tables — the service could not run locally | `GRANT SELECT` per table + `init/02-mirror-ddl.sql`. Doubles as the read-only DB backstop. |
| M13 | The CSV fixture pipeline erased SQL `NULL` vs `''` — the exact distinction SOLUTION turns on | Sentinel encoding + a round-trip test asserting both survive. |
| M14 | Bootstrap admin could not be rotated and could be guessed without limit | Change-password endpoint (round 2) + lockout, per-IP throttle, `LAST_LOGIN_AT`, identical failure messages for locked vs wrong. |
| M15 | `application.yml` shipped a fallback **database** password | No default; the local value lives in the dev profile. |
| M16 | The read-only structural assertion covered `/api/incidents` only, with no DB backstop | Widened to every `/api/**` route; DB backstop is the M11 grant. |
| M17 | `ORDER BY` allowlist asserted but never enumerated, its test empty; `size`/`limit`/`granularity` unbounded | Allowlist enumerated; bounds set; injection cases parameterized; oversized page → 400. |
| M18 | Redmine free text rendered with no escaping rule and JWT storage unspecified — a stored-XSS→token-theft chain | `v-html` banned + lint rule; access token in memory, refresh token in an `HttpOnly` cookie. Escaping test added. |
| M19 | Porting yob's `AuthFilter` would log plaintext passwords; no CORS policy; error handler unassigned | Body logging omitted/masked with a test; CORS an explicit allowlist; `GlobalExceptionHandler` owned by Task 14. |

**Not carried forward:** M12 (`withReuse`) was already fixed in round 2.

**What round 3 should look hardest at:** whether trimming removed a constraint that was only
implied by the deleted code, and whether the M1 SLA unification changes any number the
delivered workbook already reports.

---

## Quorum Review Log — Rounds 3 and 4

Raw persona output: `.agents/artifacts/incident-dashboard-review/round3-raw.json`,
`round4-raw.json`. Same three personas both rounds (senior-software-architect ·
domain expert · security-engineer), same schema, same prompt.

### Why four rounds ended in BLOCK

The gate has **no pass condition**. Three adversarial personas, a schema field named
`blockers`, and no severity threshold: any single blocker forces BLOCK, and a reviewer asked
to find blockers in a 2,000-line design document will always find one. The rounds were not
flat, though — the *class* of finding moved:

| Round | Dominant class | Contradiction-class blockers |
|---|---|---|
| 1 | cross-reference drift (same fact restated ~5×) | — |
| 2 | cross-reference drift | 5 |
| 3 | Oracle semantics (empty string ≡ NULL) | — |
| 4 | parity precision + auth carrier gaps | 1 |

The trim (3,967 → 1,530 lines, plus the single-source-of-truth rule enforced by
`MartSchemaIT`) closed the drift class by construction — the architect's round-4 note was
"The trim worked."

### Round 3 — the foundational finding

**Oracle has no empty string.** A zero-length `VARCHAR2`/`CLOB` is stored as NULL. The source
query tests `close_date`/`resolution_date` for `''` *and* `NULL` separately and gets different
answers; a naive mirror collapses both to NULL and silently changes `SOLUTION` and
`AGING_NEW` for 371 of 4,627 rows (8%).

Resolution — the **row-presence discriminator**: in the source, `close_date` arrives through a
LEFT JOIN, so *no `custom_values` row* → NULL and *a row holding `''`* → `''`. Oracle collapses
the value but the **row still exists**. `HAS_CLOSE_DATE_ROW` / `HAS_RESOLUTION_ROW` carry that
bit into `INCIDENT_FACT`, and `CASE WHEN <row exists> THEN '' ELSE NULL END` reproduces MySQL
exactly with no change asked of the Kafka team.

Residual risk, stated rather than hidden: a row whose value is a genuine SQL NULL is
indistinguishable from `''`. Count these in UAT before claiming Level-3 parity.

### Round 4 — folded

The 35-row **report column contract** (SC001–SC035) was extracted mechanically from the source
query's outer SELECT and inner block rather than re-derived by reading, closing the parity
class by construction. Three of round 4's parity claims were independently re-verified against
the source query before folding; all three held.

Structural blockers folded (15):

| Class | Fold |
|---|---|
| parity | 35-column contract; `AGING_NEW` quoted verbatim with its three consequences; report formats rendered at the DTO boundary, not stored |
| view | `V_INCIDENT` rewritten as a **two-level** view so `ELAPSED_DAYS` is computed once and `AGING_NEW`/`SLA_RATIO`/`SLA_BREACHED`/`SLA_BUCKET` all derive from it; every join LEFT + NVL so an incident can never vanish; `SYSDATE` not `SYSTIMESTAMP` |
| mart | `CV_DIGEST` + `CFG_GENERATION` in the MERGE content predicate; keyset cursor `(UPDATED_ON, ID)`; `pruneOutOfScope()` carved out of the no-tombstones rule |
| security | scoped forced-rotation token now carries `typ`/`scope` claims (`validate → Claims`); CORS preflight exempted from both filter and interceptor; `/api/incidents/export` capped at 50,000 rows with `EXPORT_AUDIT` |
| process | `git init` as step 0; gitignore patterns re-anchored to real paths; `R__SeedAdmin` must override `getChecksum()` |

**Decision: proceed to `/straventa-plan-approve` without a round 5.** The remaining findings
are parity-precision claims about SQL semantics. The cheapest verifier for those is
`ExcelParityIT` running against the delivered workbook at execution time — not a fourth
reviewer reading the same document. Anything round 5 would find, Phase 4 finds with evidence.
