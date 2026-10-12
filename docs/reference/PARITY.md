# Excel parity — how we prove the mart reproduces the delivered report

`ExcelParityIT` (Task 17) checks the mart against
`docs/reference/Detail Incident, Problem, SR 2025 - 2026.xlsx`, the report as currently
delivered to stakeholders.

> **The workbook is production data.** Real customer names, staff names and incident text.
> It is git-ignored and stays that way. **This document records STRUCTURE and COUNTS only —
> never a cell value**, and neither do the test's failure messages: a finding is identified by
> column name and 0-based data-row index, never by echoing content. `ParityDiff.Mismatch`
> deliberately has no `expected`/`actual` field, because AssertJ prints the whole object.

A fresh clone has no workbook. The workbook-dependent tests **skip with a message** rather than
fail, so the build is green without production data.

## The three levels

| Level | Test | Proves | Runs |
|---|---|---|---|
| 1 — column parity | `martExposesEveryColumnTheDeliveredReportHas` | every header in `Query1` resolves to a `V_INCIDENT` field | always (skips if the workbook is absent) |
| 2 — logic parity | `reproducesTheReportsDerivedValuesOnKnownRows` | the derived classifiers are right | always |
| 3 — full row diff | `reproducesEveryRowOfTheDeliveredWorkbook` | the mart reproduces the workbook row for row | **only with `PARITY_FULL=true`** |

Levels 1 and 2 gate the phase. Level 3 does not, and is not silently absent either — it is
`assumeTrue`-gated with a named follow-up in the skip message.

## Level 1 — the column contract

The workbook has exactly one sheet, `Query1`. It is a **Power Query pull of the source SQL**,
so its header row *is* the column contract: `xl/tables/table1.xml` declares it as a
`queryTable`. That is why the header row is the thing under test and not a hand-made summary
tab.

Shape, as of 2026-08-23:

| | |
|---|---|
| sheets | 1 (`Query1`) |
| declared range | `A1:AI4628` |
| columns | **35** |
| data rows | **4 627** |

Result: **35 of 35 headers resolve; `missingColumns` is empty.**

Matching is on normalised names — uppercase, every non-alphanumeric character dropped
(`XlsxReader.normalise`), so `No_incident`, `no incident` and `NOINCIDENT` collapse together —
plus one alias rule:

> an `RPT_X` view column is the **rendered** form of report column `X` (see the `RPT_` block in
> `V3__mart.sql`), so it answers to `X`.

`RPT_*` columns are registered first, so the workbook's `DATE_CREATE` resolves to
`RPT_DATE_CREATE` (the `YYYY-MM-DD` string the report carries) rather than to the native
`DATE_CREATE` timestamp it is derived from. Four headers resolve through that alias:
`DATE_CREATE`, `create_date`, `updated_on`, `closed_on`.

`SLA` is the reason `V_INCIDENT` exposes `SLA_DAYS` a second time under the bare name `SLA`:
normalisation strips `_`, so `SLA_DAYS` would collapse to `SLADAYS` and never match the
workbook's `SLA`.

The same test asserts **`MTTD` appears nowhere** — not as a workbook header, not as a mart
column. It is not in the source report and is not derivable from Redmine's data.

## Level 2 — the classifiers

Runs against the CI fixture, not the workbook. The fixture rows are *not* the workbook's rows,
so this cannot prove row parity — but the classifiers are pure functions of the source
semantics, so a wrong one shows up here regardless of which rows are loaded.

**Every expected value is derived, never typed in.** `SLA` is compared against
`CFG_SLA_POLICY`'s row for the incident's own `PRIORITY_ID`; `AGING_NEW` against the whole-day
difference between the row's own creation and close dates. A hard-coded constant here would
invite an implementer to break `DerivedFields` to make the test green — the exact failure the
tests-as-truth rule exists to prevent.

Asserted:

- the three `SOLUTION` outcomes on the three hand-authored fixture issues
  (`fixtures/custom_values.csv` was written to make each branch unambiguous);
- **the misspelling.** `'Workaround & Pemanent Solution'` — *Pemanent* — is misspelled in the
  source query and therefore in the delivered workbook. `SolutionType` reproduces it verbatim
  and the test asserts the string contains `Pemanent` and does **not** contain `Permanent`.
  Correcting the spelling is a `valueMismatch`, not an improvement;
- `AGING_NEW` is **NULL**, and `SLA_BUCKET` is `Unknown`, for an **open** row whose `close_date`
  custom value exists but is empty — matching MySQL's `timestampdiff(DAY, d, '')`. Not `0`, and
  not days-since-now.
- **Deliberate divergence since V9 (close clock, plan 2026-09-15-stakeholder-notes-p1):** a
  **closed** row with an empty `close_date` value stops its clock at Redmine's `closed_on`
  (`CLOSE_BASIS = 'CLOSED_ON'`), or at the last status change for an override-closed status, so
  its `AGING_NEW` is a number and its `SLA_BUCKET` a real band where the source query yields
  NULL / blank. Level 3 excludes `AGING_NEW` as clock-derived, so this does not score as a
  mismatch there; Level 2 asserts the new rule on its closed empty-close-date fixture.

The branch precedence itself (unparenthesised `AND`/`OR`, so each `WHEN` is `(A AND B) OR
(C AND D)`) is documented in
`.agents/artifacts/2026-08-20-incident-dashboard/solution-precedence-finding.md` and unit-tested
in `DerivedFieldsTest`.

On Oracle, `value = ''` **is** `value = NULL` and is never true, so every ported
`x IS NULL OR x = ''` predicate branches on the `HAS_*_ROW` flags or on
`DBMS_LOB.GETLENGTH(x) = 0` instead. Dates in the data are `YYYY-MM-DD` and nothing else.

## The sentinel rule

`sentinelLabelsAreNotCountedAsValueMismatchesAgainstBlanks`.

The source query emits SQL `NULL` wherever a `LEFT JOIN` misses — an unset custom field, an
unresolvable enum id, an unmapped status — and the delivered workbook renders that as an **empty
cell**. This system renders the same absence as a **countable sentinel** instead, because
unresolved values must never vanish from an aggregate.

So, for parity purposes:

| mart | workbook | verdict |
|---|---|---|
| `Unclassified` / `Unknown (<id>)` / `Unmapped` | blank | **match** |
| `Unclassified` / `Unknown (<id>)` / `Unmapped` | a real label | **mismatch** — we failed to resolve something the source could |
| any value | different value | mismatch |

Without the first row, Level 3 would be non-empty **by construction** on its first real run, for
a reason that is not a defect. The second row is what keeps the carve-out one-directional: it
cannot hide a genuine labelling gap.

Comparison is otherwise case- and content-sensitive (whitespace-trimmed only) — it has to be,
or the `Pemanent` misspelling would pass under a lenient comparison.

## Level 3 — the row-level diff, and why it is opt-in

Level 3 needs a mirror loaded from real UAT/production data. The CI fixture is 100 synthetic-ish
issues from one month; it cannot contain the workbook's 2025–2026 rows, and production data must
not be committed to make it. So Level 3 is env-gated:

```bash
# NOTE the trailing dot: incident.mirror.schema carries its own separator.
PARITY_FULL=true INCIDENT_MIRROR_SCHEMA=REDMINE_MIRROR. ./mvnw verify -Dit.test=ExcelParityIT
```

**Named follow-up:** run Level 3 against a UAT-loaded mirror **before the mart replaces the
manually delivered report**. That is the trigger; it is repeated in the test's own skip message
so it cannot be lost.

`ParityDiff` reports three buckets **separately** — `missingFromMart`, `extraInMart`,
`valueMismatches`. A row the mart never produced, a row the mart invented and a cell that is
wrong are three different bugs with three different causes; one combined count is unreadable as
a release gate.

### Columns Level 3 does not compare, and why

| column | reason |
|---|---|
| `AGING_INCIDENT` | days-since-now. Correct on the day the workbook was pulled and correct again at query time — against different clocks. Comparing it diffs two run dates, not the mart. |
| `AGING_LAST_STATUS` | same. |
| `AGING_NEW` | days-to-close for a closed row, days-to-now for an open one, so it carries the same clock over the open population. Its closed-row arithmetic is asserted at Level 2, where the dates are pinned. |

### Phase 2 divergences from the stakeholders' updated datamart query (`Datamart Redmine.sql`)

- **`AGING_INCIDENT` is dropped** from the API row and the export (their new query dropped it;
  note 16). `V_INCIDENT` still computes it (the view is not rewritten); `IncidentRowDto.INTERNAL_COLUMNS`
  keeps it off the wire and it is no longer a sortable column. The delivered 2025-2026 workbook
  still has the header, which is why the Level 1 and Level 3 notes above keep mentioning it.
- **Our `AGING_NEW` intentionally differs** from their new `CASE`: an open ticket with a
  null/blank close date counts to today, instead of their NULL for a missing row, which would
  reintroduce blank aging on open tickets. Decision D6 in the Phase 2 plan.

Nothing else is carved out. Mart values are rendered to strings **in Oracle** (`TO_CHAR`), not
by a Java `toString`: a `Timestamp.toString()` renders differently from the report and would
score as a mismatch on every dated row.

## Known gap that Level 3 will surface

`REF_CUSTOM_FIELD_ENUM` can only be partially seeded — 16 of 234 `(field, id) → label` pairs are
recoverable from the delivered exports, because `custom_field_enumerations` was never delivered
and `custom_values.value` stores the id, not the label. `category_incident` is the worst hit.
Every unresolved id renders as `Unknown (<id>)`.

Under the sentinel rule those rows are a **match against a blank workbook cell** and a
**mismatch against a populated one** — and the workbook *is* populated for them, since the
source could resolve the labels. **Level 3 will therefore report `valueMismatches` on the enum
label columns until that seed is completed.** This is a known, accepted, tracked gap, not a
classifier bug: see item 1 of `.agents/deferred-followups.md`
("T007 — REF_CUSTOM_FIELD_ENUM cannot be fully seeded"), whose close trigger is a real
`custom_field_enumerations` export. Close that before reading a Level-3 run as a verdict on the
classifiers.
