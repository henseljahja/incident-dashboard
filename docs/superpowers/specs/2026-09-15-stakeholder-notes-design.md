# Stakeholder notes (14 Sep 2026) — design

Status: **draft for review** · Author: Hensel Jahja · Date: 2026-09-15

## 1. Sources

| Source | What it contributes |
|---|---|
| WhatsApp notes, 14 Sep 2026, items 1–17 | The change requests |
| Meeting action items + summary, 14 Sep 2026 | Owners, the "50-day breach" defect, the "unclassified matrix" defect, wallboard direction |
| `docs/Dashboard Incident Management.pptx` | Mockups: slide 2–3 (TV/summary), slide 5 (table with *No Redmine*), slide 6 (draft OLA) |
| `docs/SPO - IT Service Management 2023 - Final.pdf` | p.9 impact × urgency → priority; p.14 response + fix targets per severity |
| Delivered workbook `docs/reference/Detail Incident, Problem, SR 2025 - 2026.xlsx` (4,627 rows) | Real-data evidence for §3.1 — aggregates only, no rows copied |

## 2. Decision log

Every note and meeting item, with where it lands. **P1** = this plan. **P2** = after the group
reviews the mockup. **P3** = blocked on someone outside this repo.

| # | Request | Decision | Phase |
|---|---|---|---|
| 1 | Add Redmine number | "No Redmine" first column + drawer header, links to Redmine when configured | P1 |
| 2 | Click Breached → only breached | Real server-side filter; tile count must equal table total | P1 |
| 3 | Explain "at risk" etc. | ⓘ icon on **every** component: meaning + formula/reason (user, 15 Sep) | P1 |
| 4 | TV shows Incident / Problem / SR counts | Per-tracker-group count tiles | P2 |
| 5 | Wallboard incidents only | Wallboard scoped to tracker *Incident* | P2 |
| 6, 7 | Hours / minutes / seconds in MTTR and table | Needs a close **timestamp**; cf 105 is date-only (verified) | P3 — DWH |
| 8 | Close date = cf 105 | Stays the primary close date; §3.1 adds a fallback only when it is absent | P1 |
| 9 | Response SLA (received → processed) | Targets from SPO p.14 | P3 — Putra's matrix |
| 10 | SPO matrix from Pras | Wait for the latest file | P3 — Putra |
| 11 | Add Closed status | "Closed" KPI tile, clickable | P1 |
| 12 | Backlog → close; PTR treated as closed | Backlog tile removed (meeting). PTR + "PTR with permission" count as closed via config | P1 |
| 13, 14 | SR not here; separate menus | **Three menus: Incident, Problem, Service Request** (user, 15 Sep) | P2 |
| 15 | Remove At Risk | **Overruled: keep At Risk**, explained by its ⓘ icon (user, 15 Sep) | P1 |
| 16 | Remove incident aging | Replace with duration columns once §6/7 land | P3 |
| 17 | "selang berapa menit" | Wallboard screen auto-rotation interval (meeting) | P2 |
| M1 | Open/close status mapping | Owned by Putra. P1 ships it as **config**, so his list is a data change | P1 (mechanism) |
| M2 | Matrix mostly "Unclassified" | Real missing values, not a label bug (§3.5) — scope to Incident + show the gap | P2 |
| M3 | Share mockup before development | Mockup artifact covering P2 screens | P1 deliverable |
| M6 | Closed same day, shows 50 days, breached | Root-caused — §3.1 | P1 |

## 3. Phase 1 design

### 3.1 Stop the SLA clock when a ticket is closed (M6, notes 8, 12)

**Defect.** `V_INCIDENT.ELAPSED_DAYS` stops only at cf 105:
`NVL(TRUNC(CLOSE_DATE), TRUNC(SYSDATE)) - TRUNC(DATE_CREATE)`. A ticket closed in Redmine without
cf 105 keeps aging until today. 26 Jul → 14 Sep is exactly the reported 50 days. The rule is a
faithful port of line 3 of the source report query, so the old mart has the same defect.

**Scale, in the delivered workbook (4,627 rows):**

| Group | No cf 105 | Of which currently breached | False aging, median |
|---|---|---|---|
| Closed (Redmine `is_closed=1`) | 397 | 312 | 278 days |
| PTR / PTR with permission | 137 | 51 | 169 days |

370 of the closed ones are *SR - BAU*; only 2 are *Incident*.

**Fallback timestamps are trustworthy.** Where cf 105 *is* present on a closed ticket,
`issues.closed_on` falls on the same day in 2,988 of 3,392 rows (88%) and within 7 days in 95%.
Coverage where cf 105 is missing: `closed_on` 373/397 for closed; `LAST_DATE_STATUS` 137/137 for
PTR (Redmine never sets `closed_on` for PTR, because PTR is `is_closed=0`).

**Design** — migration `V9__close_clock.sql`, view-only; no ETL change, both inputs already exist
on `INCIDENT_FACT`.

1. `CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE NUMBER(1) NULL`, check `IN (0,1)`. `NULL` = use Redmine's
   flag. Seed `1` for status 11 *PTR* and 63 *PTR with permission*. Putra's mapping replaces the seed.
2. `V_INCIDENT.REDMINE_IS_CLOSED = NVL(s.IS_CLOSED, 0)` — the raw flag, kept for parity work.
3. `V_INCIDENT.IS_CLOSED = NVL(m.IS_CLOSED_OVERRIDE, REDMINE_IS_CLOSED)` — the dashboard's flag.
4. `STOP_AT`, first match wins:
   1. cf 105 present → `CLOSE_DATE`
   2. dashboard-closed **and** Redmine-closed → `NVL(CLOSED_ON, LAST_DATE_STATUS)`
   3. dashboard-closed by override (PTR) → `LAST_DATE_STATUS` — the moment it entered PTR
   4. otherwise → `NULL` (still open)
5. `CLOSE_BASIS`: `'CLOSE_DATE' | 'CLOSED_ON' | 'STATUS_CHANGE' | 'OPEN' | 'UNKNOWN'` — shown in the
   drawer, so a number is always explainable.
6. `ELAPSED_DAYS`:
   - open **and** cf 105 row present but empty → `NULL` (unchanged — the 8% cohort)
   - closed with no usable timestamp → `NULL`, basis `UNKNOWN` (never "grows forever")
   - else `TRUNC(NVL(STOP_AT, SYSDATE)) - TRUNC(DATE_CREATE)`

`AGING_NEW`, `SLA_RATIO`, `SLA_BREACHED`, `SLA_BUCKET` all derive from `ELAPSED_DAYS`, so they
follow automatically. **Closing late is still a breach** — the clock stops, it is not zeroed (the
existing V3 comment's rule holds).

**Consequences to accept:**
- `OPEN_COUNT` drops by the PTR population.
- Parity: `AGING_NEW` is already outside Level 3's compared set. `ExcelParityIT`'s fixture
  assertion for a *closed* ticket with an empty cf 105 changes to the new rule. This is a
  requirement change from the meeting, recorded in `PARITY.md` — not a weakened assertion.

### 3.2 Redmine number (note 1)

- Desktop table: first column **No Redmine**, sortable, `#<id>`.
- Drawer header shows `#<id>`.
- Link to `${VITE_REDMINE_BASE_URL}/issues/<id>` when configured, plain text otherwise — the
  existing drawer rule.
- Export already carries `NO_INCIDENT`; no change.

### 3.3 Clickable status tiles (note 2, 11, meeting item 8)

- `IncidentFilter` gains `status: OPEN | CLOSED` and `sla: BREACHED | AT_RISK`.
- The SQL fragments move to **one** shared constant used by both `MetricsUseCase.kpiFor` and
  `IncidentQueryUseCase.predicate`. The tile and the table cannot drift.
- Ops tiles **Open, Closed, Breached, At Risk** set the filter, then scroll to the table.
  The filter bar shows a removable chip. Clicking the same tile again clears it.
- **Invariant, tested at every tier:** after a click, the table's total equals the tile's number.
- Exec and Wallboard tiles stay non-clickable (no table there).

### 3.4 Backlog tile removed, Closed tile added (note 11, 12, meeting)

- Remove the Backlog tile from Ops, Exec and Wallboard.
- `/api/metrics/kpi` keeps `backlog` for one release (no silent contract break) and gains `closed`.

### 3.5 ⓘ on every component (note 3, user decision)

- Wording lives in the i18n catalogs (`definitions.<term>.meaning|formula|source`, en + id).
  Numbers (SLA days and At Risk cushion per priority, override-closed statuses, refresh
  interval) come from `GET /api/metrics/definitions`, which reads config — so the ⓘ text cannot
  drift from `CFG_SLA_POLICY`.
- `InfoHint.vue`: a real `<button>` with `pi-info-circle`, `aria-label="About <title>"`, opening a
  PrimeVue Popover. Works by keyboard and touch.
- Covers every KPI tile, chart, the freshness indicator, the SLA and aging table columns, and the
  Exec and Wallboard tiles.
- A unit test enumerates the components and fails if any lacks an InfoHint, or points at a key the
  catalog does not have.
- Copy is **English and Indonesian**, switchable (§3.7).

**At Risk, kept and redefined (user: research current practice, 15 Sep).** Today it is
`SLA_BREACHED = 0 AND SLA_RATIO >= 0.8` over *all* tickets. New rule: **open, not breached, and
days left ≤ a per-priority cushion** — Critical 1 · High 2 · Medium 3 · Low 7 days, stored in
`CFG_SLA_POLICY.AT_RISK_REMAINING_DAYS`. Percentage thresholds (Jira's 80%) fail on a 3–60 day
ladder: 80% of Low leaves 12 days, 80% of Critical rounds to zero at day grain; absolute
time-remaining (Zendesk, Freshservice, ManageEngine) fits. The SLA-bucket chart and heatmap keep
their percentage bands — they show time used across all tickets, and their ⓘ says so.

### 3.7 English / Indonesian switch (user decision, 15 Sep)

`vue-i18n` 11 + `primelocale`. A topbar switch (also on login and wallboard) sets the locale,
persisted per browser (`localStorage`), default English. UI chrome is translated; Redmine data
values (status, tracker, `Unclassified`) are not. A parity test fails the build when a key exists
in one language only; Playwright runs pinned to English plus one Indonesian smoke test.

### 3.6 Why the matrix is mostly "Unclassified" (meeting item 2) — finding, fix in P2

`Unclassified` means the ticket has **no** cf 93/94 value. An unresolvable value would show
`Unknown (<id>)`. So the query is right and the data is empty — most likely SR and Problem
tickets, which do not use those fields. P2 scopes the matrix to *Incident* and shows a
"N tickets missing impact/urgency" count that opens the list.

## 4. Phase 2 — after the group reviews the mockup

- Three menus: **Incident** (tracker 7), **Problem** (41), **Service Request** (16, 32, 33). New
  `APP_MENU` rows and RBAC grants; each view is the Ops layout with a fixed tracker scope.
- Header count tiles per group (note 4).
- Wallboard redesign per PPT slides 2–3: status, severity, top 5 categories, trend, Critical/High
  list, SLA compliance — *Incident* only (note 5).
- Auto-rotation between wallboard screens; default 60 s per screen, configurable (note 17).
- Matrix scoped to Incident, with the missing-data count (§3.6).

## 5. Phase 3 — blocked

| Item | Waiting on | Unblocks |
|---|---|---|
| Close timestamp with hours/minutes | DWH team (MySQL → Oracle sync) | notes 6, 7, 16 |
| Latest SPO / incident matrix | Putra | notes 9, 10 |
| Final open/close status list | Putra | swaps the §3.1 seed — config only |

Fallback if DWH cannot supply it: journal timestamps (`LAST_DATE_STATUS` carries seconds today).

## 6. Testing

| Tier | What |
|---|---|
| 1 — unit (JUnit, vitest) | Filter parsing; predicate fragments; InfoHint coverage; No Redmine column; tile click → store |
| 2 — Testcontainers Oracle | Closed without cf 105 stops at `closed_on`; PTR stops at status change; closed late still breaches; open with empty cf 105 stays `NULL`; tile count == filtered total for all four tiles |
| 3 — Playwright | Click Breached → table total equals tile; ⓘ opens by keyboard; No Redmine visible |

## 7. Open points for review

Resolved 15 Sep: At Risk is open-only with per-priority cushions (§3.5); copy is bilingual (§3.7).
Implementation plan: `.agents/plans/2026-09-15-stakeholder-notes-p1.md`.

Still open: whether PTR → On Hold → PTR should stop the clock at the first or last PTR entry
(confirm with Putra; `LAST_DATE_STATUS` gives the last).
