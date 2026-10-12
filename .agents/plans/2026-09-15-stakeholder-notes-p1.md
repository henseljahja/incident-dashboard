---
plan_id: 2026-09-15-stakeholder-notes-p1
version: 1
status: approved (plain plan; straventa ceremony skipped per user 2026-09-15)
created: 2026-09-15
---

# Stakeholder notes — Phase 1

**Goal:** Make the dashboard's numbers trustworthy and explainable. Four changes:
- Stop the SLA clock when a ticket closes.
- Let every status tile filter the table it summarises.
- Show the Redmine ticket number, and explain every component.
- Offer the whole UI in English and Indonesian.

**Charter:** none

**Architecture in one line:** Extend `V_INCIDENT` and two config tables in one additive V9 migration. Extend `IncidentFilter`, `KpiResponse` and the existing Vue components. Add one read-only `GET /api/metrics/definitions` route and `vue-i18n`. No new service, table or job.

**Global Constraints (verbatim from the request and the repo):**
- Redmine is the write side, permanently; this system never mutates incident data.
- Hardcoded Redmine IDs are configuration, not code (AGENTS.md).
- Never weaken an assertion to green a test; a changed expectation must come from a changed requirement and be recorded in the Decisions Log.
- Production-derived data stays local; no rows in commits, artifacts or external services.
- Source spec: `docs/superpowers/specs/2026-09-15-stakeholder-notes-design.md` §3.
- User decisions (15 Sep 2026):
  - Three menus (Incident / Problem / SR) are deferred to Phase 2.
  - Keep At Risk: research current practice and redefine it.
  - Every component gets an ⓘ with its meaning and formula.
  - Add an EN/ID language switch.

Out of scope for this plan:
- **Phase 2 (after mockup sign-off):**
  - Three menus.
  - Wallboard rotation.
  - Incident-scoped matrix.
  - SLA compliance tile.
- **Phase 3 (blocked on DWH and Putra):**
  - Hour-level durations.
  - Response SLA / SPO matrix.
  - Final status mapping.

---

## System Design

**Structural change:** extend existing `V_INCIDENT` mart view, `CFG_STATUS_STAGE_MAP`, `CFG_SLA_POLICY`, `INCIDENT_FACT`, `MetricsController`, and the Vue SPA. The one new route, `GET /api/metrics/definitions`, is a read-only GET on the existing controller. It needs no ADR: it adds no service, table, job or integration.

### Context
- `V3__mart.sql` builds `INCIDENT_FACT` (write-time columns) and `V_INCIDENT` (two-level view).
  - `ELAPSED_DAYS` is computed once in the inner block. `AGING_NEW`, `SLA_RATIO`, `SLA_BREACHED` and `SLA_BUCKET` derive from it.
  - For a closed ticket with no cf105 row, it ages to `SYSDATE`. This is the 50-day false breach reported in the 14 Sep meeting.
- `IS_CLOSED` is read from mirror `issue_statuses.is_closed` at `V3__mart.sql:197`.
  - PTR (status 11) and PTR-with-permission (63) are `is_closed = 0` in Redmine, but the business treats them as closed.
- **`MetricsUseCase.kpiFor` (`MetricsUseCase.java:108-133`)** computes Backlog, Open, Breached, At Risk, MTTR ×2 and Workaround Debt in one pass.
  - At Risk is `SLA_BREACHED = 0 AND SLA_RATIO >= 0.8` (constant at `:61`). It includes closed tickets.
  - The trend chart counts closures on `COALESCE(CLOSE_DATE, CLOSED_ON)` (`:236`).
- **`IncidentQueryUseCase.predicate(filter)` (`:129-205`)** is the shared WHERE for the table, export and metrics. It has no status or SLA facet.
- `KpiRow.vue` tiles only scroll to the table. Workaround Debt is the only tile that sets a filter (`hasWorkaround`).
- `IncidentTable.vue` has no ticket-number column. `IncidentDetailDrawer.vue:66` shows the number in the header, with no link.
- The UI is English-only, with string literals across 24 `.vue` files and `utils/date.ts`.
- **Config writes bump `CFG_VERSION`** (trigger `V1__config_tables.sql:187`), which forces a full re-ETL on the next refresh (120 s cadence).

### Components and boundaries
| Component | Owner (package/service) | Change | Talks to |
|---|---|---|---|
| V9 migration | `BackEnd/incident-service/src/main/resources/db/migration/V9__close_clock_and_at_risk.sql` | New file. Adds 2 config columns and 1 fact column, and restates `V_INCIDENT` | Oracle mart schema; mirror via `${mirror}` (read-only) |
| ETL derived fields | `domain/usecase/etl/DerivedFields.java`, `MartRefreshUseCase.java`, `infrastructure/datasource/service/MartRefreshRepository.java` | Resolve `SLA_AT_RISK_DAYS` per priority next to `SLA_DAYS` | `CFG_SLA_POLICY` |
| Filter model | `domain/model/IncidentFilter.java`, `domain/model/StatusFacet.java` (new enum), `domain/model/SlaFacet.java` (new enum), `infrastructure/rest/dto/req/IncidentFilterRequest.java` | Add `status` and `sla` facets | `IncidentQueryUseCase`, `MetricsUseCase` |
| Query predicate | `domain/usecase/query/IncidentQueryUseCase.java` | Split into `basePredicate` and `facetPredicate`; the table uses both | `V_INCIDENT` |
| Metrics | `domain/usecase/query/MetricsUseCase.java`, `infrastructure/rest/dto/res/KpiResponse.java` | Faceted KPI counts, add `closed`, deprecate `backlog`, trend on `STOP_AT` | `V_INCIDENT` |
| Definitions route | `infrastructure/rest/MetricsController.java`, `domain/usecase/query/DefinitionsUseCase.java` (new), `infrastructure/rest/dto/res/DefinitionsResponse.java` (new) | Read-only policy numbers for ⓘ text | `CFG_SLA_POLICY`, `CFG_STATUS_STAGE_MAP`, `incident.etl.refresh-interval-ms` |
| i18n runtime | `FrontEnd/incident-dashboard/src/i18n/index.ts`, `src/i18n/locales/en.json`, `src/i18n/locales/id.json`, `src/stores/locale.ts` (all new), `src/main.ts` | `vue-i18n` 11 (Composition mode, `legacy: false`) plus PrimeVue locale | Every `.vue` in the Scope Census |
| Language switch | `src/components/common/LanguageSwitch.vue` (new), `src/layout/AppLayout.vue`, `src/layout/WallboardLayout.vue`, `src/views/LoginView.vue` | EN/ID toggle, persisted in `localStorage` key `incident.locale` | `stores/locale.ts` |
| InfoHint | `src/components/common/InfoHint.vue` (new), `src/apis/definitions.ts` (new) | ⓘ popover: meaning, formula, source; numbers from the definitions route | PrimeVue `Popover` |
| Tiles, table, drawer | `KpiRow.vue`, `KpiCard.vue`, `ExecView.vue`, `WallboardView.vue`, `IncidentTable.vue`, `IncidentDetailDrawer.vue`, `stores/filters.ts`, `apis/metrics.ts`, `apis/incidents.ts` | Clickable facet tiles, Closed tile, Backlog removed, No Redmine column | Filters store, KPI and incidents APIs |

### Data ownership and flow
- **Incident data:** Redmine (mirror) is the source of truth. The ETL writes `INCIDENT_FACT`; the view derives. Nothing new is written except config seed rows in V9.
- **`IS_CLOSED_OVERRIDE`:** owned by `CFG_STATUS_STAGE_MAP`, written only by migrations and the DBA. NULL means use Redmine's flag; 1 or 0 overrides it.
- **`AT_RISK_REMAINING_DAYS`:** owned by `CFG_SLA_POLICY`, copied onto `INCIDENT_FACT.SLA_AT_RISK_DAYS` at ETL time. This mirrors how `SLA_DAYS` flows, so the view needs no config join for it.
- **Locale:** browser-local only (`localStorage`). No server state and no per-user column.
- **Tenancy:** not applicable. This is a single-tenant internal dashboard, and access is RBAC by menu (`@RequirePrivilege`).
- **Money:** not applicable. No amounts are stored or shown.

### Interface contracts

**DDL — `V9__close_clock_and_at_risk.sql`** (additive; restates the whole view with the `${mirror}` placeholder):
- **`CFG_STATUS_STAGE_MAP`:**
  - Add `IS_CLOSED_OVERRIDE NUMBER(1) NULL`, with `CONSTRAINT CFG_SSM_CLOSED_OVR_CK CHECK (IS_CLOSED_OVERRIDE IN (0,1))`.
  - `UPDATE … SET IS_CLOSED_OVERRIDE = 1 WHERE STATUS_ID IN (11, 63)`.
- **`CFG_SLA_POLICY`:**
  - Add `AT_RISK_REMAINING_DAYS NUMBER(3) DEFAULT 1 NOT NULL`, with `CONSTRAINT CFG_SLA_AT_RISK_CK CHECK (AT_RISK_REMAINING_DAYS >= 0 AND AT_RISK_REMAINING_DAYS < SLA_DAYS)`.
  - Updates: Low (1) → 7, Medium (19) → 3, High (3) → 2, Critical (21) → 1.
- **`INCIDENT_FACT`:** add `SLA_AT_RISK_DAYS NUMBER(3) DEFAULT 1 NOT NULL`. The MERGE column list is generated from `USER_TAB_COLUMNS`, so no hand-listed copy is needed.
- **`V_INCIDENT` inner block adds:**
  - **`REDMINE_IS_CLOSED`** = `NVL(s.IS_CLOSED, 0)` (the raw mirror flag).
  - **`IS_CLOSED`** = `NVL(m.IS_CLOSED_OVERRIDE, NVL(s.IS_CLOSED, 0))`.
  - **`STOP_AT DATE`**, first match wins, and every branch is wrapped in `CAST(… AS DATE)`:
    1. `HAS_CLOSE_DATE_ROW = 1 AND CLOSE_DATE IS NOT NULL` → `CLOSE_DATE`.
    2. Redmine-closed → `NVL(CLOSED_ON, LAST_DATE_STATUS)`.
    3. Override-closed → `LAST_DATE_STATUS`.
    4. Else → NULL.
  - **`CLOSE_BASIS VARCHAR2(16)`**: `'CLOSE_DATE' | 'CLOSED_ON' | 'STATUS_CHANGE' | 'OPEN' | 'UNKNOWN'`. `'UNKNOWN'` means closed with no usable timestamp.
  - **`ELAPSED_DAYS`**, first match wins:
    - Closed AND `STOP_AT IS NULL` → NULL.
    - Open AND `HAS_CLOSE_DATE_ROW = 1 AND CLOSE_DATE IS NULL` → NULL. This keeps the existing sentinel behaviour for open tickets.
    - Otherwise `TRUNC(NVL(STOP_AT, SYSDATE)) - TRUNC(DATE_CREATE)`.
    - Precedence: the closed branches are evaluated before the empty-row branch (fixture 22878).
- **`V_INCIDENT` outer block adds:**
  - **`SLA_AT_RISK NUMBER(1)`**: 1 when `IS_CLOSED = 0 AND ELAPSED_DAYS IS NOT NULL AND ELAPSED_DAYS <= SLA_DAYS AND (SLA_DAYS - ELAPSED_DAYS) <= SLA_AT_RISK_DAYS`, else 0.
  - `SLA_BREACHED` is unchanged: a ticket closed late is still breached.
- **Export:** `IncidentRowDto.INTERNAL_COLUMNS` adds `REDMINE_IS_CLOSED` and `SLA_AT_RISK_DAYS`, which are debugging and config echoes. `STOP_AT`, `CLOSE_BASIS` and `SLA_AT_RISK` are exported.

**Filter — query parameters on `GET /api/incidents`, `GET /api/incidents/export` and `GET /api/metrics/*`:**
- **`status`:** `OPEN | CLOSED`, optional, single value. It maps to `StatusFacet`.
  - `OPEN` → `IS_CLOSED = 0`.
  - `CLOSED` → `IS_CLOSED = 1`.
- **`sla`:** `BREACHED | AT_RISK`, optional, single value. It maps to `SlaFacet`.
  - `BREACHED` → `SLA_BREACHED = 1`.
  - `AT_RISK` → `SLA_AT_RISK = 1`.
- **Invalid values:** any other value returns HTTP 400 with the existing error envelope, code `invalid_filter` and field `status` or `sla`. This follows `.agents/patterns/error.md`.
- **`IncidentFilter` record:** gains `StatusFacet status` and `SlaFacet sla`, both nullable, inserted after `hasWorkaround`. Every positional constructor call is updated, including `MetricsUseCase.previousWindow` (`:154`).
- **SQL fragments:**
  - `IncidentQueryUseCase.basePredicate(IncidentFilter)` handles dates, tracker, stage, priority, institusi, category, hasWorkaround and cancelled.
  - `IncidentQueryUseCase.facetPredicate(StatusFacet, SlaFacet)` handles only the facet terms; a null facet contributes nothing.
  - `predicate(filter)` = base AND facet. Its signature is unchanged.

**`GET /api/metrics/kpi` response (`KpiResponse`):**
- **Existing fields kept:** `windowFrom`, `windowTo`, `windowDays`, `mttrWorkaroundDays`, `mttrPermanentDays`, `mttrGapDays`, `workaroundDebt`, `previousPeriod`.
- **`backlog`:** `@Deprecated`, same value as today (the base predicate ignoring facets), removed in the next release.
- **Faceted counts:**
  - `open` = base AND `sla` facet AND `IS_CLOSED = 0`.
  - `closed` (new) = base AND `sla` facet AND `IS_CLOSED = 1`.
  - `breached` = base AND `status` facet AND `SLA_BREACHED = 1`.
  - `atRisk` = base AND `status` facet AND `SLA_AT_RISK = 1`.
- **Invariants:**
  - With no facets, `open + closed == backlog`.
  - With any facets, the tile of an active facet equals `GET /api/incidents` `total` for the same parameters.
- **`previousPeriod`:** carries the same facets.

**`GET /api/metrics/definitions`** (new; `@RequirePrivilege(menu = {"incident.ops","incident.exec","incident.wallboard"}, privilege = "view")`). Response:
- `slaPolicy`: `[{ priorityId: number, priorityName: string, slaDays: number, atRiskRemainingDays: number }]`, ordered by `slaDays` ascending.
- `unknownPriority`: `{ slaDays: 3, atRiskRemainingDays: 1 }`, taken from the `DerivedFields` constants.
- `closedByOverride`: `[{ statusId: number, statusName: string }]`, the rows where `IS_CLOSED_OVERRIDE = 1`.
- `closeRule`: `["CLOSE_DATE","CLOSED_ON","STATUS_CHANGE"]`, the stop-clock precedence as enum names; the frontend translates them.
- `refreshIntervalMinutes`: number, from `incident.etl.refresh-interval-ms`.

**Frontend contracts:**
- **`stores/locale.ts`:** exports `useLocaleStore()` with `locale: Ref<'en'|'id'>` and `setLocale(l)`.
  - `setLocale` writes `localStorage['incident.locale']` inside try/catch, sets `i18n.global.locale.value`, sets `document.documentElement.lang`, and swaps the PrimeVue locale via `usePrimeVue().config.locale`.
  - Default `'en'`. Anything stored other than `'en'`/`'id'` falls back to `'en'`.
- **`stores/filters.ts`:**
  - Adds `status: Ref<'OPEN'|'CLOSED'|null>` and `sla: Ref<'BREACHED'|'AT_RISK'|null>`.
  - Both are in `toFilterParams()`, in `reset()` and in URL query sync.
  - Adds `toggleStatus(v)` and `toggleSla(v)`: the same value clears the facet, a different value replaces it.
- **`apis/metrics.ts`:** `KpiResponse` adds `closed: number`, keeps `backlog` (marked deprecated in a JSDoc comment), and adds `fetchDefinitions(): Promise<DefinitionsResponse>`.
- **`InfoHint.vue`:**
  - Props: `{ term: DefinitionTerm }`, where `DefinitionTerm` is the union `'open'|'closed'|'breached'|'atRisk'|'mttrWorkaround'|'mttrPermanent'|'workaroundDebt'|'trend'|'topCategories'|'matrix'|'slaBuckets'|'freshness'|'aging'|'slaColumn'|'noRedmine'`.
  - Reads `definitions.<term>.{meaning,formula,source}` from i18n and interpolates numbers from `fetchDefinitions()`.
  - Renders a `<button type="button" aria-label="{t('definitions.about', {name})}">` that opens a PrimeVue `Popover`.
- **i18n keys:** namespaced `common.*`, `nav.*`, `kpi.*`, `filters.*`, `table.*`, `drawer.*`, `charts.*`, `wallboard.*`, `auth.*`, `admin.*`, `errors.*`, `definitions.*`.
  - Data values are never keys: status names, tracker names, `Unclassified`, `Unknown (<id>)`, `Unmapped` and bucket labels stay data.
  - The exception is `SLA_BUCKET` and `CLOSE_BASIS` enum values, which map client-side through `charts.bucket.*` / `definitions.closeBasis.*`.

### Failure modes and degradation
- **V9 fails mid-migration on Oracle:**
  - Flyway marks it failed and the app refuses to start; the previous image keeps serving.
  - Each DDL statement auto-commits on Oracle, so V9 is written to be idempotent-safe on repair: columns are checked through `USER_TAB_COLUMNS` in a PL/SQL block before `ALTER`.
  - Repair: `flyway repair`, then restart.
- **First refresh after V9:** the config bump forces a full re-ETL (~1 cycle).
  - Until it finishes, existing fact rows carry `SLA_AT_RISK_DAYS = 1` (the column default), so At Risk under-counts non-Critical tickets for at most one refresh interval.
  - `FreshnessIndicator` already shows the in-progress state.
- **Definitions route fails:** `InfoHint` still renders meaning and formula from i18n. Number placeholders show the static fallback copy `definitions.numbersUnavailable`. Nothing blocks.
- **Invalid facet in a bookmarked URL:** the store drops unknown values on hydrate, so the API is never called with them. A direct API call gets 400 `invalid_filter`.
- **`localStorage` unavailable (private mode):** the locale stays at the in-memory default `en`, and the switch still works for the session.
- **Missing translation key:** `vue-i18n` `fallbackLocale: 'en'`, with `missingWarn` and `fallbackWarn` enabled in dev only. A vitest parity test fails the build when `id.json` lacks a key present in `en.json`.

### Consistency and idempotency
- Read-only system: no mutations beyond migration seed rows, so no idempotency keys.
- **KPI counts and table total:** both come from the same `basePredicate` and `facetPredicate` fragments against the same view, in separate statements. A refresh landing between the two calls can differ by the delta of one ETL cycle; the existing `FreshnessIndicator` timestamp covers this. The invariant is asserted in tests on a static fixture.
- **V9 seed `UPDATE`s** are deterministic by primary key, so re-running on repair produces the same rows.

### Observability
- **`DerivedFields.slaAtRiskDays`:** logs WARN once per unknown `priorityId`, mirroring `slaDays`. The log line carries `priorityId` only, never subject or customer.
- **`MartRefreshUseCase`:** existing INFO summary line gains `closeBasisUnknown=<count>`, the number of closed rows with no stop timestamp. That count surfaces the data-quality cohort without logging rows.
- **Bad facet values:** the existing request log (correlation id from `.agents/patterns/trace.md`) covers them; the rejected value is logged, and it is an enum token, not PII.
- **Must NOT be logged:** subjects, descriptions, assignee names, institusi values.

### Rejected alternatives
- **Move PTR into the `Closed` stage** (instead of an `IS_CLOSED_OVERRIDE` column). Rejected by the architect:
  - Stage drives the ops funnel, and PTR is operationally still blocked.
  - Conflating stage and closed-ness would move PTR tickets out of "Blocked" views.
- **Percentage At Risk (80% elapsed, as Jira Service Management does).** Rejected:
  - 80% of Low's 60 days leaves 12 days, which is not a risk.
  - 80% of Critical's 3 days leaves under a day at calendar-day grain, which rounds to zero.
  - Per-priority absolute cushions (the Zendesk, Freshservice and ManageEngine pattern) fit a 3–60-day ladder.
- **At-risk cushion joined in the view from `CFG_SLA_POLICY`** (instead of a fact column). Rejected:
  - It duplicates the unknown-priority default in SQL and Java.
  - `SLA_DAYS` already flows through the fact, so one resolution path is kept.
- **Non-faceted tile counts** (every tile applies both facets). Rejected: clicking Breached would zero the At Risk tile and make the tiles useless as navigation.
- **Hard-coding ⓘ numbers in translation files.** Rejected: they would drift from `CFG_SLA_POLICY` the first time the DBA edits it.
- **Backend-served translations.** Rejected: UI chrome copy is a frontend concern, and data values stay data.
- **Changing `SLA_BUCKET` bands and `severity.ts` to the cushion.** Rejected:
  - The bucket chart and heatmap show the distribution of SLA time used across all tickets, which is a different question from "open tickets about to breach".
  - Both get ⓘ text that states the distinction.
  - Changing them would break the Excel-parity bucket semantics.

---

## UI Design Spec

**Design system:** 505base-family legacy surface (`mti/`). The existing Yokke preset is the system of record: `FrontEnd/incident-dashboard/src/main.ts` (PrimeVue Aura with the Yokke cyan primary) and `src/main.css` tokens. The straventa design system does not apply to `mti/`; this is recorded in the Decisions Log.
**Primitive package:** PrimeVue 4 (existing). No new primitives. `InfoHint` and `LanguageSwitch` are compositions of PrimeVue `Button`, `Popover` and `SelectButton`.
**Reference mock:** https://claude.ai/artifact/VkWppMdUKFK4wFEipiixsk (invented data). There is no new route, so no static mock file is required.

### Screen inventory
| # | Route / screen | Template | Primitives used | Signal action (exactly one, or none) | Mock |
|---|---|---|---|---|---|
| S1 | `/ops` OpsView | existing route | KpiCard · InfoHint · Chip · DataTable · Drawer · SelectButton | none (tiles are filters, not a primary CTA) | current route `/ops` + artifact tab "Incident dashboard" |
| S2 | `/exec` ExecView | existing route | KpiCard · InfoHint · Chart | none | current route `/exec` |
| S3 | `/wallboard` WallboardView | existing route | tile · InfoHint (hidden on TV, `?tv=1`) | none | current route `/wallboard` + artifact tab "Wallboard" |
| S4 | IncidentDetailDrawer (on S1) | existing | Drawer · Tag · link Button | "Open in Redmine" | current drawer |
| S5 | `/login` LoginView | existing route | Form · LanguageSwitch | "Sign in" | current route `/login` |
| S6 | LanguageSwitch (topbar of AppLayout and WallboardLayout, and S5) | new composition | SelectButton `EN`/`ID` | none | artifact topbar |
| S7 | InfoHint popover (all tiles, charts, table SLA/Aging/No Redmine headers, freshness) | new composition | Button(text, icon `pi-info-circle`) · Popover | none | artifact tab "Definitions" |

### State matrix (one row per data surface)
| Screen | Loading | Empty | Error | Populated | No-access (403) |
|---|---|---|---|---|---|
| S1 tiles | `Skeleton` per tile (existing) | count `0`, tile still clickable, chip applies | tile shows `—` and `kpi.error` text; tiles are not clickable | counts; active tile `aria-pressed="true"` + ring + check icon | route guard redirects (existing) |
| S1 table | skeleton rows ×10 (existing) | `table.empty` + "Clear filters" link when chips are active | `table.error` + Retry (existing) | rows with No Redmine first column | route guard (existing) |
| S1 filter chips | n/a (synchronous) | no chips row rendered | n/a | one removable chip per active facet: `filters.chip.status.OPEN` etc. | n/a |
| S2 tiles | skeleton (existing) | `0` | `—` + `kpi.error` | counts (not clickable on Exec, which is a summary page) | route guard |
| S3 wallboard | skeleton (existing) | `0` | stale banner `wallboard.stale` with last-good timestamp (existing) | counts: Open / Closed / Breached / At Risk | route guard |
| S4 drawer | skeleton (existing) | n/a | `drawer.error` (existing) | header `#<no>` link + subject | route guard |
| S6 switch | n/a | n/a | n/a (`localStorage` failure is silent, in-memory) | active segment reflects locale | visible pre-login |
| S7 popover | popover body shows meaning + formula immediately; numbers show `…` while definitions load | n/a | numbers replaced by `definitions.numbersUnavailable` | meaning · formula · source with live numbers | n/a (inherits page access) |

### Copy (en + id)
| Key | en | id |
|---|---|---|
| `kpi.open` | Open | Terbuka |
| `kpi.closed` | Closed | Ditutup |
| `kpi.breached` | Breached | Melewati SLA |
| `kpi.atRisk` | At risk | Berisiko |
| `kpi.mttrWorkaround` | MTTR (workaround) | MTTR (workaround) |
| `kpi.mttrPermanent` | MTTR (permanent) | MTTR (permanen) |
| `kpi.workaroundDebt` | Workaround debt | Utang workaround |
| `kpi.error` | Couldn't load this number | Angka ini gagal dimuat |
| `kpi.filterHint` | Click to filter the table | Klik untuk memfilter tabel |
| `filters.chip.status.OPEN` | Status: Open | Status: Terbuka |
| `filters.chip.status.CLOSED` | Status: Closed | Status: Ditutup |
| `filters.chip.sla.BREACHED` | SLA: Breached | SLA: Melewati SLA |
| `filters.chip.sla.AT_RISK` | SLA: At risk | SLA: Berisiko |
| `filters.clearAll` | Clear filters | Hapus filter |
| `table.noRedmine` | No Redmine | No Redmine |
| `table.openInRedmine` | Open #{no} in Redmine | Buka #{no} di Redmine |
| `table.empty` | No incidents match these filters | Tidak ada insiden yang cocok dengan filter ini |
| `common.language` | Language | Bahasa |
| `definitions.about` | About {name} | Tentang {name} |
| `definitions.meaning` | What it shows | Artinya |
| `definitions.formula` | How it's calculated | Cara menghitung |
| `definitions.source` | Source | Sumber |
| `definitions.numbersUnavailable` | Current thresholds couldn't be loaded; see SPO ITSM 2023 p.14 | Ambang saat ini gagal dimuat; lihat SPO ITSM 2023 hal. 14 |
| `definitions.open.meaning` | Tickets not yet closed in the selected period | Tiket yang belum ditutup pada periode terpilih |
| `definitions.open.formula` | Count where status is not closed. PTR and PTR with permission count as closed. | Jumlah tiket dengan status belum ditutup. PTR dan PTR with permission dihitung ditutup. |
| `definitions.closed.meaning` | Tickets closed in Redmine, or in a status the business treats as closed | Tiket yang ditutup di Redmine, atau berstatus yang dianggap ditutup |
| `definitions.closed.formula` | Count where status is closed: {statuses} | Jumlah tiket dengan status ditutup: {statuses} |
| `definitions.breached.meaning` | Tickets whose age passed their SLA, including tickets that closed late | Tiket yang umurnya melewati SLA, termasuk yang ditutup terlambat |
| `definitions.breached.formula` | Age in days > SLA days. Age stops at close date, then Redmine close time, then last status change. | Umur (hari) > SLA (hari). Umur berhenti di tanggal tutup, lalu waktu tutup Redmine, lalu perubahan status terakhir. |
| `definitions.atRisk.meaning` | Open tickets close to breaching their SLA | Tiket terbuka yang hampir melewati SLA |
| `definitions.atRisk.formula` | Open, not breached, and days left ≤ cushion. Cushion: {cushions} | Terbuka, belum melewati SLA, dan sisa hari ≤ batas. Batas: {cushions} |
| `definitions.slaBuckets.meaning` | Share of SLA time used, for all tickets. Different from At risk, which counts open tickets only. | Porsi waktu SLA yang terpakai untuk semua tiket. Berbeda dengan Berisiko yang hanya menghitung tiket terbuka. |
| `definitions.noRedmine.meaning` | Redmine ticket number; opens the ticket in Redmine | Nomor tiket Redmine; membuka tiket di Redmine |
| `definitions.source.spo` | SPO IT Service Management 2023, p.14 | SPO IT Service Management 2023, hal. 14 |

The full catalog, including the remaining `definitions.*`, `nav.*`, `auth.*` and `admin.*` keys, is produced by T102/T404. The two locale files must have key parity (TM030).

### Formats, lexicon, theme, a11y
- **Numbers and dates:**
  - Counts use `Intl.NumberFormat(locale)` with tabular numerals, right-aligned in tables.
  - Dates use `Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric' })` in 24h WIB: `15 Sep 2026` in en, `15 Sep 2026` in id (the id month short names come from Intl).
  - Durations stay in days in Phase 1: `2,5 hari` in id, `2.5 days` in en. `toFixed` calls are replaced by `Intl.NumberFormat` with `maximumFractionDigits: 1`.
- **Lexicon:**
  - SLA states: Breached / At risk / On track, shown as dot + text, never colour alone (WCAG 1.4.1).
  - Status names come from Redmine data and are not translated.
- **Theme:** light and dark, both already supported by the preset. The active tile ring uses the `--p-primary-color` token, never a raw hex.
- **Accessibility:**
  - Tiles are `<button>` with `aria-pressed`. InfoHint is a separate button inside the card, not nested inside the tile button: the tile is the card body, the hint sits in the card header.
  - Targets are ≥44px.
  - Keyboard path: Tab to tile → Enter/Space toggles the facet → Tab to ⓘ → Enter opens the popover → Esc closes it and returns focus.
  - The table total is announced through `aria-live="polite"` after a facet change.
  - `document.documentElement.lang` follows the locale.
  - Reduced motion: no ring animation.

### Design acceptance criteria (BDD — become tests)
- **DAC1:** GIVEN S1 tiles are loading, WHEN KPI data has not arrived, THEN each tile renders a Skeleton, never an empty card.
- **DAC2:** GIVEN S1 renders with KPI data, WHEN the Breached tile is activated, THEN it has `aria-pressed="true"`, a chip "SLA: Breached" appears, and the table total equals the tile count.
- **DAC3:** GIVEN any S1/S2/S3 tile, chart card or the table SLA/Aging/No Redmine header, WHEN the DOM is inspected, THEN an InfoHint button with an accessible name is present.
- **DAC4:** GIVEN locale `id` is selected, WHEN the page reloads, THEN `html[lang="id"]` is set and `kpi.atRisk` renders "Berisiko".
- **DAC5:** GIVEN the definitions route returns 500, WHEN an InfoHint opens, THEN meaning and formula render and `definitions.numbersUnavailable` replaces the numbers.
- **DAC6:** GIVEN S1, S2 or S3, WHEN the tiles render, THEN no tile labelled Backlog exists, and a Closed tile exists.

---

## User Story 1: The SLA clock stops when a ticket closes

- **AC1.1:** GIVEN a ticket closed in Redmine with no cf105 row, created 26 Jul and closed 27 Jul, WHEN the mart refreshes, THEN `ELAPSED_DAYS = 1`, `CLOSE_BASIS = 'CLOSED_ON'` and `SLA_BREACHED = 0` for a Medium priority.
- **AC1.2:** GIVEN a ticket in status PTR (11) with `LAST_DATE_STATUS` 5 days after creation, WHEN the mart refreshes, THEN `IS_CLOSED = 1`, `REDMINE_IS_CLOSED = 0`, `CLOSE_BASIS = 'STATUS_CHANGE'` and `ELAPSED_DAYS = 5`.
- **AC1.3:** GIVEN a ticket with a cf105 close date, WHEN the mart refreshes, THEN cf105 wins over `CLOSED_ON`, `CLOSE_BASIS = 'CLOSE_DATE'`, and a close after SLA days still gives `SLA_BREACHED = 1`.
- **AC1.4:** GIVEN a closed ticket with no cf105 value, no `CLOSED_ON` and no `LAST_DATE_STATUS`, WHEN the mart refreshes, THEN `ELAPSED_DAYS` is NULL, `CLOSE_BASIS = 'UNKNOWN'`, and the ticket is neither breached nor at risk.
- **AC1.5:** GIVEN an open ticket whose cf105 row exists but is empty, WHEN the mart refreshes, THEN `ELAPSED_DAYS` is NULL as today, AND fixture 22878 (closed + empty row) takes the closed branch.
- **AC1.6:** GIVEN the created/closed trend, WHEN a PTR ticket is counted, THEN it appears as closed on its `STOP_AT` date, matching the Closed tile.

## User Story 2: At Risk means an open ticket about to breach

- **AC2.1:** GIVEN an open High ticket (SLA 7, cushion 2) aged 5 days, WHEN KPIs load, THEN it counts as At Risk.
- **AC2.2:** GIVEN an open High ticket aged 4 days, WHEN KPIs load, THEN it does not count as At Risk.
- **AC2.3:** GIVEN a closed ticket at 95% of its SLA, WHEN KPIs load, THEN it does not count as At Risk.
- **AC2.4:** GIVEN a ticket with unknown priority, WHEN the ETL resolves it, THEN `SLA_DAYS = 3`, `SLA_AT_RISK_DAYS = 1`, and one WARN line names the priority id.
- **AC2.5:** GIVEN a DBA sets a cushion ≥ SLA days, WHEN the update runs, THEN Oracle rejects it with `CFG_SLA_AT_RISK_CK`.

## User Story 3: Clicking a status tile filters the table

- **AC3.1:** GIVEN S1 with no facets, WHEN the user clicks Breached, THEN the table shows only breached tickets, a removable chip "SLA: Breached" appears, the URL carries `sla=BREACHED`, and the tile count equals the table total (DAC2).
- **AC3.2:** GIVEN Breached is active, WHEN the user clicks Open, THEN both facets apply, Open equals the table total, and At Risk still shows its count within Open (faceted, not zero).
- **AC3.3:** GIVEN Breached is active, WHEN the user clicks Breached again or removes its chip, THEN the facet clears and the table total returns to the unfaceted total.
- **AC3.4:** GIVEN a bookmarked URL `?sla=NOPE`, WHEN S1 loads, THEN the facet is ignored and no API request carries `sla`.
- **AC3.5:** GIVEN a direct API call `GET /api/incidents?status=MAYBE`, WHEN it is served, THEN the response is 400 with code `invalid_filter`.
- **AC3.6:** GIVEN facets are active, WHEN the user exports, THEN the export applies the same facets as the table.
- **AC3.7:** GIVEN a user without the `incident.ops` view privilege, WHEN they call `GET /api/incidents?sla=BREACHED`, THEN the response is 403 (unchanged authz).

## User Story 4: Backlog is gone and Closed is shown

- **AC4.1:** GIVEN S1, S2 or S3, WHEN tiles render, THEN there is no Backlog tile and there is a Closed tile (DAC6).
- **AC4.2:** GIVEN no facets, WHEN `GET /api/metrics/kpi` returns, THEN `open + closed == backlog` and `backlog` is still present.
- **AC4.3:** GIVEN the wallboard, WHEN it polls, THEN it shows Open / Closed / Breached / At Risk and never Backlog.

## User Story 5: Every row shows its Redmine number

- **AC5.1:** GIVEN S1 on a desktop viewport, WHEN the table renders, THEN the first column is "No Redmine", and each cell is a link to `${VITE_REDMINE_BASE_URL}/issues/<no>` opening in a new tab with `rel="noopener noreferrer"`.
- **AC5.2:** GIVEN S1 on a mobile viewport, WHEN a row card renders, THEN `#<no>` is shown in the card header.
- **AC5.3:** GIVEN `VITE_REDMINE_BASE_URL` is empty, WHEN the table renders, THEN the number shows as plain text with no broken link.
- **AC5.4:** GIVEN the drawer is open, WHEN it renders, THEN the header shows `#<no>` as the Redmine link.

## User Story 6: Every component explains itself

- **AC6.1:** GIVEN any tile, chart card, or the table SLA/Aging/No Redmine header, WHEN inspected, THEN it has an InfoHint (DAC3).
- **AC6.2:** GIVEN the At Risk ⓘ is opened, WHEN definitions have loaded, THEN it lists the cushion per priority from `CFG_SLA_POLICY` (Critical 1 · High 2 · Medium 3 · Low 7 days).
- **AC6.3:** GIVEN definitions failed to load, WHEN an ⓘ opens, THEN meaning and formula still render with the fallback note (DAC5).
- **AC6.4:** GIVEN keyboard-only use, WHEN the user tabs to an ⓘ and presses Enter, THEN the popover opens; Esc closes it and focus returns to the ⓘ.
- **AC6.5:** GIVEN a user without dashboard privileges, WHEN they call `GET /api/metrics/definitions`, THEN the response is 403.

## User Story 7: The UI is available in English and Indonesian

- **AC7.1:** GIVEN a first visit, WHEN any page loads, THEN the locale is English and `html[lang="en"]`.
- **AC7.2:** GIVEN the user selects ID on S6, WHEN the switch changes, THEN visible chrome copy (nav, tiles, filters, table headers, drawer, charts, login, admin, errors) re-renders in Indonesian without a reload, and PrimeVue calendar/paginator labels switch to Indonesian.
- **AC7.3:** GIVEN ID was selected, WHEN the page reloads, THEN ID is restored (DAC4).
- **AC7.4:** GIVEN `localStorage` holds `fr`, or throws, WHEN the app boots, THEN the locale is English and no error is shown.
- **AC7.5:** GIVEN the Indonesian locale, WHEN Redmine data values render (status names, `Unclassified`, tracker names), THEN they are shown unchanged.
- **AC7.6:** GIVEN `id.json` lacks a key present in `en.json`, WHEN tier 1 runs, THEN the key-parity test fails.
- **Completeness:** 100% of Scope Census items have evidence in the artifact bundle.
- GIVEN the final wave has finished, WHEN `verify-wave --final` runs, THEN `census_coverage.expected_rows == covered_rows` with no entries in `missing`.

---

## Test Matrix

| ID | Kind | Case | Tier | Test name (to be written by the task) |
|---|---|---|---|---|
| TM001 | positive | Redmine-closed, no cf105 → `CLOSE_BASIS=CLOSED_ON`, elapsed stops (AC1.1) | 2 | `CloseClockIT.redmineClosedWithoutCloseDateStopsAtClosedOn` |
| TM002 | positive | PTR override → `IS_CLOSED=1`, `STATUS_CHANGE` basis (AC1.2) | 2 | `CloseClockIT.overrideClosedStatusStopsAtLastStatusChange` |
| TM003 | positive | cf105 wins; late close still breached (AC1.3) | 2 | `CloseClockIT.closeDateWinsAndLateCloseStaysBreached` |
| TM004 | negative | closed with no timestamp → NULL elapsed, `UNKNOWN`, not breached/at-risk (AC1.4) | 2 | `CloseClockIT.closedWithoutAnyTimestampIsUnknownNotBreached` |
| TM005 | boundary | open + empty cf105 row → NULL; fixture 22878 closed branch (AC1.5) | 2 | `ExcelParityIT` fixture 22878 assertion updated + `CloseClockIT.closedBeatsEmptyCloseDateRow` |
| TM006 | positive | trend closed series uses `STOP_AT`/`IS_CLOSED` (AC1.6) | 2 | `MetricsIT.trendCountsOverrideClosedTicketsOnStopDate` |
| TM007 | boundary | High, 5 days → at risk; 4 days → not; 7 days → at risk; 8 → breached, not at risk (AC2.1, AC2.2) | 2 | `CloseClockIT.atRiskCushionBoundaries` |
| TM008 | negative | closed at 95% → not at risk (AC2.3) | 2 | `CloseClockIT.closedTicketIsNeverAtRisk` |
| TM009 | boundary | unknown priority → 3 / 1 + WARN (AC2.4) | 1 | `DerivedFieldsTest.unknownPriorityDefaultsAtRiskCushion` |
| TM010 | negative | cushion ≥ SLA days rejected by check constraint (AC2.5) | 2 | `ConfigTablesIT.atRiskCushionMustBeBelowSlaDays` |
| TM011 | positive | V9 seeds override (11, 63) and cushions (1/2/3/7); generation bumps once | 2 | `ConfigTablesIT.v9SeedsCloseOverrideAndCushions` |
| TM012 | positive | `sla=BREACHED` table total == KPI `breached` (AC3.1) | 2 | `IncidentQueryIT.slaFacetTotalMatchesKpiTile` |
| TM013 | positive | both facets: each active tile == table total; At Risk non-zero within Open (AC3.2) | 2 | `MetricsIT.facetedCountsKeepOtherFacetTilesMeaningful` |
| TM014 | negative | `status=MAYBE` / `sla=NOPE` → 400 `invalid_filter` (AC3.5) | 2 | `IncidentQueryIT.invalidFacetValueIs400` |
| TM015 | authz | facet query without `incident.ops` view → 403 (AC3.7) | 2 | `AuthFlowIT.facetQueryStillRequiresOpsPrivilege` |
| TM016 | positive | export applies facets (AC3.6) | 2 | `IncidentQueryIT.exportAppliesStatusAndSlaFacets` |
| TM017 | positive | no facets: `open + closed == backlog`; `backlog` present (AC4.2) | 2 | `MetricsIT.openPlusClosedEqualsBacklog` (replaces the equality at `MetricsIT:222`/`:318`) |
| TM018 | positive | `previousPeriod` carries facets | 2 | `MetricsIT.previousPeriodAppliesSameFacets` |
| TM019 | positive | definitions route returns policy, override statuses, refresh minutes (AC6.2) | 2 | `DefinitionsIT.returnsLivePolicyNumbers` |
| TM020 | authz | definitions route without privilege → 403 (AC6.5) | 2 | `DefinitionsIT.requiresDashboardPrivilege` |
| TM021 | positive | export column list includes `STOP_AT`, `CLOSE_BASIS`, `SLA_AT_RISK`; excludes internals | 2 | `IncidentQueryIT` / `MartSchemaIT` column-list assertions updated |
| TM022 | ui-state | tiles loading → Skeleton (DAC1) | 1 | `KpiRow.spec.ts › renders skeleton per tile while loading` |
| TM023 | ui-state | tile click toggles facet, `aria-pressed`, chip, clear on second click (DAC2, AC3.3) | 1 | `KpiRow.spec.ts › toggles sla facet and chip` |
| TM024 | negative | unknown facet in URL dropped on hydrate (AC3.4) | 1 | `filters.spec.ts › drops unknown facet values from the url` |
| TM025 | ui-state | tile error → `—` + `kpi.error`, not clickable | 1 | `KpiRow.spec.ts › error state disables tile filtering` |
| TM026 | ui-state | no Backlog tile, Closed tile present on Ops/Exec/Wallboard (DAC6, AC4.1, AC4.3) | 1 | `ExecView.spec.ts`, `WallboardView.spec.ts`, `KpiRow.spec.ts › closed tile, no backlog` |
| TM027 | positive | No Redmine column link + href + rel (AC5.1, AC5.4) | 1 | `IncidentTable.spec.ts › no redmine column links to redmine`; `IncidentDetailDrawer.spec.ts › header links ticket` |
| TM028 | boundary | empty `VITE_REDMINE_BASE_URL` → plain text (AC5.3); mobile card header (AC5.2) | 1 | `IncidentTable.spec.ts › plain number without base url` |
| TM029 | ui-state | InfoHint present on every surface (DAC3, AC6.1); fallback on definitions error (DAC5, AC6.3); keyboard open/close (AC6.4) | 1 | `InfoHint.spec.ts`; `OpsView.spec.ts › every component has an info hint` |
| TM030 | negative | `en.json`/`id.json` key parity (AC7.6) | 1 | `i18n/__tests__/parity.spec.ts` |
| TM031 | boundary | stored `fr` / throwing storage → `en` (AC7.4) | 1 | `stores/__tests__/locale.spec.ts` |
| TM032 | ui-state | switch re-renders copy without reload; data values untouched; PrimeVue locale swaps (AC7.2, AC7.5) | 1 | `LanguageSwitch.spec.ts` |
| TM033 | e2e | Ops: click Breached → only breached rows, chip, count == total → remove chip (US3) | 3 | `e2e/ops-facets.spec.ts` |
| TM034 | e2e | switch to ID, reload, Indonesian persists, `html[lang=id]` (US7, DAC4) | 3 | `e2e/locale.spec.ts` |
| TM035 | e2e | existing Playwright specs pass with locale pinned to `en` | 3 | `e2e/fixtures/seed.ts` locale init + all existing specs |
| TM036 | e2e | wallboard shows Open/Closed/Breached/At Risk (AC4.3) | 3 | `e2e/wallboard.spec.ts` updated |

---

## Phases / Tasks

### Phase 1 — Language foundation

#### T101: vue-i18n runtime, locale store and language switch   (risk: medium · parallelizable: false)
- **Files:**
  - Create:
    - `FrontEnd/incident-dashboard/src/i18n/index.ts`
    - `src/i18n/locales/en.json`
    - `src/i18n/locales/id.json`
    - `src/stores/locale.ts`
    - `src/stores/__tests__/locale.spec.ts`
    - `src/components/common/LanguageSwitch.vue`
    - `src/components/common/__tests__/LanguageSwitch.spec.ts`
    - `src/i18n/__tests__/parity.spec.ts`
    - `.agents/patterns/i18n.md`
  - Modify:
    - `package.json` (add `vue-i18n` ^11.4.10 and `primelocale` ^2.5.0)
    - `src/main.ts`
    - `src/layout/AppLayout.vue`
    - `src/layout/WallboardLayout.vue`
    - `src/views/LoginView.vue`
    - `vitest.setup` (register the i18n plugin globally for component tests)
- **Boundary:** i18n runtime; Language switch.
- **Interfaces:**
  - Produces:
    - `i18n` (`createI18n` instance, `legacy: false`, `fallbackLocale: 'en'`).
    - `type AppLocale = 'en' | 'id'`.
    - `useLocaleStore(): { locale: Ref<AppLocale>; setLocale(l: AppLocale): void }`.
    - `LanguageSwitch.vue` (no props).
  - Before writing, fetch Context7 docs for vue-i18n 11 and PrimeVue 4 locale.
- **Matrix rows:** TM030, TM031, TM032
- **Design:** S5, S6 (all state rows).
- **Acceptance:** AC7.1, AC7.2 (switch mechanics), AC7.3, AC7.4, AC7.6

#### T102: Move chrome copy into the catalog   (risk: low · parallelizable: false)
- **Files (modify):**
  - Every file in Scope Census SC001–SC025 and their existing `__tests__` specs.
  - `src/utils/date.ts`: take a locale argument, use `Intl`, replace `toFixed`.
- **Boundary:** i18n runtime.
- **Interfaces:** consumes `i18n` and `useLocaleStore` from T101. Produces keys under `common.* nav.* kpi.* filters.* table.* drawer.* charts.* wallboard.* auth.* admin.* errors.*`, with en and id at parity.
- **Rules:**
  - Data values are not translated.
  - vitest assertions keep matching English text because the test setup pins `en`.
  - No assertion is weakened.
- **Matrix rows:** TM030, TM032
- **Design:** S1–S5 copy.
- **Acceptance:** AC7.2, AC7.5

#### T103: Pin e2e locale and add the Indonesian smoke test   (risk: low · parallelizable: false)
- **Files:**
  - Modify `FrontEnd/incident-dashboard/e2e/fixtures/seed.ts`: an `addInitScript` sets `localStorage['incident.locale']='en'`.
  - Create `e2e/locale.spec.ts`.
- **Boundary:** Language switch.
- **Interfaces:** consumes the `incident.locale` key from T101.
- **Matrix rows:** TM034, TM035
- **Acceptance:** AC7.3, DAC4

**Review Gate G1:**
- **Tier 1:** full suite passes.
- **Tier 3:** `login.spec.ts` + `locale.spec.ts` pass.
- **Evidence:** the parity test passes.

### Phase 2 — Close clock and At Risk in the mart

#### T201: V9 migration — close override, cushions, fact column, view restatement   (risk: HIGH · parallelizable: false)
- **Files:**
  - Create:
    - `BackEnd/incident-service/src/main/resources/db/migration/V9__close_clock_and_at_risk.sql`
    - `src/test/java/id/co/yokke/incidentdashboard/mart/CloseClockIT.java`
  - Modify:
    - `src/test/java/id/co/yokke/incidentdashboard/config/ConfigTablesIT.java`
    - `.agents/patterns/migrations.md`: record that an additive `ALTER TABLE INCIDENT_FACT ADD` in a later V-migration is allowed, because the MERGE list is generated.
- **Boundary:** V9 migration.
- **Interfaces:** produces the view columns `REDMINE_IS_CLOSED`, `IS_CLOSED`, `STOP_AT DATE`, `CLOSE_BASIS VARCHAR2(16)`, `SLA_AT_RISK NUMBER(1)`, the fact column `SLA_AT_RISK_DAYS NUMBER(3)`, and the config columns `CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE` and `CFG_SLA_POLICY.AT_RISK_REMAINING_DAYS`, exactly as in Interface contracts.
- **Matrix rows:** TM001, TM002, TM003, TM004, TM005, TM007, TM008, TM010, TM011
- **Acceptance:** AC1.1–AC1.5, AC2.1–AC2.3, AC2.5

#### T202: ETL resolves the at-risk cushion   (risk: medium · parallelizable: false)
- **Files (modify):**
  - `BackEnd/incident-service/src/main/java/id/co/yokke/incidentdashboard/domain/usecase/etl/DerivedFields.java`
  - `…/etl/MartRefreshUseCase.java`
  - `…/infrastructure/datasource/service/MartRefreshRepository.java` (the `:256` query reads `AT_RISK_REMAINING_DAYS`)
  - `src/test/java/…/DerivedFieldsTest.java`
- **Boundary:** ETL derived fields.
- **Interfaces:**
  - Consumes `CFG_SLA_POLICY.AT_RISK_REMAINING_DAYS` from T201.
  - Produces:
    - `SlaPolicy` gains the per-priority cushion.
    - `static int slaAtRiskDays(Long priorityId, SlaPolicy policy)`.
    - `static final int UNKNOWN_PRIORITY_AT_RISK_DAYS = 1`.
    - The fact map key `"SLA_AT_RISK_DAYS"`.
    - A `closeBasisUnknown` count in the refresh summary log.
- **Matrix rows:** TM009
- **Acceptance:** AC2.4

#### T203: Trend, parity and schema tests follow the new close rule   (risk: medium · parallelizable: false)
- **Files (modify):**
  - `BackEnd/incident-service/src/main/java/…/domain/usecase/query/MetricsUseCase.java`:
    - The trend at `:236` counts closed on `STOP_AT` where `IS_CLOSED = 1`.
    - At Risk `:61` becomes the column reference `SLA_AT_RISK = 1`.
  - `…/infrastructure/rest/dto/res/IncidentRowDto.java`: `INTERNAL_COLUMNS` adds `REDMINE_IS_CLOSED` and `SLA_AT_RISK_DAYS`.
  - `src/test/java/…/query/MetricsIT.java`
  - `src/test/java/…/ExcelParityIT.java`: fixture 22878 expectation changes because the requirement changed (the note 12 decision). This is recorded in the Decisions Log.
  - `src/test/java/…/mart/MartSchemaIT.java`: column list `:92-98`.
  - `src/test/java/…/query/IncidentQueryIT.java`: column list `:92-104`.
  - `docs/reference/PARITY.md` is local and git-ignored: add a note that AGING_NEW diverges from the workbook for closed tickets without cf105.
- **Boundary:** Metrics; V9 migration consumers.
- **Interfaces:** consumes the view columns from T201.
- **Matrix rows:** TM005, TM006, TM021
- **Acceptance:** AC1.5, AC1.6

**Review Gate G2:**
- **Tier 2:** `./mvnw -q verify` passes on Testcontainers Oracle, including `CloseClockIT`, `ConfigTablesIT`, `ExcelParityIT`, `MetricsIT`, `MartSchemaIT` and `IncidentQueryIT`.
- **Also required:** `db-migration-reviewer` read of V9.
- **Evidence:** the `ExcelParityIT` diff shows only the recorded fixture change.

### Phase 3 — Facet filters and the definitions route

#### T301: Status and SLA facets in the filter model   (risk: medium · parallelizable: false)
- **Files:**
  - Create:
    - `BackEnd/incident-service/src/main/java/id/co/yokke/incidentdashboard/domain/model/StatusFacet.java` (enum `OPEN, CLOSED`)
    - `…/domain/model/SlaFacet.java` (enum `BREACHED, AT_RISK`)
  - Modify:
    - `…/domain/model/IncidentFilter.java`
    - `…/infrastructure/rest/dto/req/IncidentFilterRequest.java`
    - `…/infrastructure/rest/IncidentController.java` (400 mapping)
    - `…/domain/usecase/query/IncidentQueryUseCase.java`: `basePredicate(IncidentFilter)`, `facetPredicate(StatusFacet, SlaFacet)`, `predicate(IncidentFilter)`.
    - `src/test/java/…/query/IncidentQueryIT.java`
    - `src/test/java/…/auth/AuthFlowIT.java`
- **Boundary:** Filter model; Query predicate.
- **Interfaces:** produces `record IncidentFilter(…, Boolean hasWorkaround, StatusFacet status, SlaFacet sla, int page, int size, String sortColumn, String sortDirection)` and `IncidentQueryUseCase.basePredicate` / `facetPredicate` returning the existing SQL fragment type.
- **Matrix rows:** TM012 (with T302), TM014, TM015, TM016
- **Acceptance:** AC3.5, AC3.6, AC3.7

#### T302: Faceted KPI counts, `closed`, deprecated `backlog`   (risk: medium · parallelizable: false)
- **Files (modify):**
  - `BackEnd/incident-service/src/main/java/…/domain/usecase/query/MetricsUseCase.java` (`kpiFor`, `previousWindow`)
  - `…/infrastructure/rest/dto/res/KpiResponse.java`
  - `src/test/java/…/query/MetricsIT.java`
- **Boundary:** Metrics.
- **Interfaces:**
  - Consumes `basePredicate` / `facetPredicate` from T301.
  - Produces the `KpiResponse` fields `open`, `closed`, `breached`, `atRisk` (faceted per the contract) and `@Deprecated backlog`.
  - `kpiFor` stays a single SQL statement using conditional `SUM(CASE …)` per tile, each with its own facet fragment.
- **Matrix rows:** TM012, TM013, TM017, TM018
- **Acceptance:** AC3.1, AC3.2, AC4.2

#### T303: Definitions route   (risk: low · parallelizable: true)
- **Files:**
  - Create:
    - `BackEnd/incident-service/src/main/java/…/domain/usecase/query/DefinitionsUseCase.java`
    - `…/infrastructure/rest/dto/res/DefinitionsResponse.java`
    - `src/test/java/…/query/DefinitionsIT.java`
  - Modify: `…/infrastructure/rest/MetricsController.java`
- **Boundary:** Definitions route.
- **Interfaces:**
  - Consumes `DerivedFields.UNKNOWN_PRIORITY_SLA_DAYS` and `UNKNOWN_PRIORITY_AT_RISK_DAYS` (T202), and the V9 config columns (T201).
  - Produces `GET /api/metrics/definitions` → `DefinitionsResponse` per the contract.
- **Matrix rows:** TM019, TM020
- **Acceptance:** AC6.2 (backend half), AC6.5

**Review Gate G3:**
- **Tier 1 + Tier 2:** pass.
- **Also required:** `api-contract-reviewer` read of the `KpiResponse` change and the new route.

### Phase 4 — Dashboard surfaces

#### T401: Facet state in the filters store and chips   (risk: low · parallelizable: false)
- **Files (modify):**
  - `FrontEnd/incident-dashboard/src/stores/filters.ts`
  - `src/stores/__tests__/filters.spec.ts`
  - `src/apis/metrics.ts`
  - `src/apis/incidents.ts`
  - `src/components/filters/IncidentFilterBar.vue` (chips row + clear all)
  - `src/components/filters/__tests__/IncidentFilterBar.spec.ts`
- **Boundary:** Tiles, table, drawer.
- **Interfaces:**
  - Consumes the API params `status` and `sla` (T301).
  - Produces `status`, `sla`, `toggleStatus(v: 'OPEN'|'CLOSED')`, `toggleSla(v: 'BREACHED'|'AT_RISK')` on `useFiltersStore()`, and `KpiResponse.closed`.
- **Matrix rows:** TM024
- **Design:** S1 filter chips row (all states).
- **Acceptance:** AC3.3, AC3.4

#### T402: Clickable tiles, Closed tile, Backlog removed   (risk: medium · parallelizable: false)
- **Files (modify):**
  - `FrontEnd/incident-dashboard/src/components/kpi/KpiRow.vue` (also the `:31` comment)
  - `src/components/kpi/KpiCard.vue` (`pressed` prop, `aria-pressed`)
  - `src/views/ExecView.vue` (`:101-104`)
  - `src/views/WallboardView.vue` (`~:158`, `~:173`)
  - `src/views/OpsView.vue`: `aria-live` table total.
  - Specs under `src/components/kpi/__tests__/` and `src/views/__tests__/`.
  - `e2e/exec.spec.ts`, `e2e/wallboard.spec.ts`, `e2e/ops-happy-path.spec.ts`.
  - Create `e2e/ops-facets.spec.ts`.
- **Boundary:** Tiles, table, drawer.
- **Interfaces:**
  - Consumes `toggleStatus` and `toggleSla` (T401).
  - Produces `KpiCard` props `{ pressed?: boolean; clickable?: boolean }` and emit `select`.
- **Matrix rows:** TM022, TM023, TM025, TM026, TM033, TM036
- **Design:** S1, S2, S3 (all state rows).
- **Acceptance:** AC3.1, AC3.2, AC4.1, AC4.3, DAC1, DAC2, DAC6

#### T403: No Redmine column and drawer link   (risk: low · parallelizable: true)
- **Files (modify):**
  - `FrontEnd/incident-dashboard/src/components/table/IncidentTable.vue`
  - `src/components/table/IncidentDetailDrawer.vue`
  - `src/components/table/__tests__/IncidentTable.spec.ts`
  - `src/components/table/__tests__/IncidentDetailDrawer.spec.ts`
- **Boundary:** Tiles, table, drawer.
- **Interfaces:** consumes the row key `NO_INCIDENT` and `import.meta.env.VITE_REDMINE_BASE_URL`. Produces the table column key `NO_INCIDENT` (already in `IncidentFilter.SORTABLE_COLUMNS`).
- **Matrix rows:** TM027, TM028
- **Design:** S1 table, S4 drawer.
- **Acceptance:** AC5.1–AC5.4

#### T404: InfoHint on every component   (risk: low · parallelizable: false)
- **Files:**
  - Create:
    - `FrontEnd/incident-dashboard/src/components/common/InfoHint.vue`
    - `src/components/common/__tests__/InfoHint.spec.ts`
    - `src/apis/definitions.ts`
  - Modify:
    - `KpiCard.vue`
    - `CreatedClosedTrend.vue`, `ImpactUrgencyHeatmap.vue`, `MttrComparison.vue`, `SlaBucketBars.vue`, `TopCategories.vue`, `WorkaroundDebtCard.vue`
    - `FreshnessIndicator.vue`
    - `IncidentTable.vue` (SLA / Aging / No Redmine headers)
    - `WallboardView.vue` (hidden when `?tv=1`)
    - `src/i18n/locales/en.json`, `id.json` (`definitions.*`)
    - `src/views/__tests__/OpsView.spec.ts`
- **Boundary:** InfoHint.
- **Interfaces:**
  - Consumes `GET /api/metrics/definitions` (T303) and i18n (T101).
  - Produces `InfoHint` props `{ term: DefinitionTerm }` and `fetchDefinitions(): Promise<DefinitionsResponse>` (TanStack Query key `['definitions']`, `staleTime` 10 min).
- **Matrix rows:** TM029
- **Design:** S7 (all state rows).
- **Acceptance:** AC6.1–AC6.4, DAC3, DAC5

**Review Gate G4 (final):**
- **Tier 1, 2 and 3:** all pass.
- **Also required:** `accessibility-reviewer` and `brand-compliance-checker` reads of the UI diff.
- **Scope Census:** every row checked with evidence.
- **Before release:** deploy to mti-dev-inc01 is a separate confirmed step, not part of this plan's gates.

---

## Review Gates

| Gate | After | Tiers | Extra reviewers | Pass condition |
|---|---|---|---|---|
| G1 | Phase 1 | 1, 3 (login + locale) | — | parity test passes; existing vitest and e2e green with `en` pinned |
| G2 | Phase 2 | 1, 2 | db-migration-reviewer | `CloseClockIT` green; `ExcelParityIT` diff limited to the recorded fixture |
| G3 | Phase 3 | 1, 2 | api-contract-reviewer | facet invariants TM012/TM013/TM017 green |
| G4 | Phase 4 | 1, 2, 3 | accessibility-reviewer, brand-compliance-checker | all matrix rows green; census 100% |

No Tier-3 deferrals are authorized.

## Risk

- **HIGH: V9 restates `V_INCIDENT`.**
  - A typo silently changes every metric.
  - Mitigations:
    - `CloseClockIT` pins each branch.
    - `ExcelParityIT` guards everything else.
    - `db-migration-reviewer` reads the view diff against V3 line by line.
- **HIGH: full re-ETL once after deploy.**
  - For one refresh cycle, the At Risk cushion is the default of 1 for every priority.
  - Mitigation: accepted and documented, with freshness shown. Deploy outside the 08:00–10:00 WIB standup window.
- **Medium: changing `IS_CLOSED` changes Open, Closed and the trend for PTR tickets.**
  - This is the requested behaviour (note 12), but the numbers visibly move on deploy.
  - Mitigation: announce in the stakeholder group with before/after counts from dev.
- **Medium: i18n extraction touches 25 files.**
  - Risk of a broken binding or a dropped string.
  - Mitigations:
    - The parity test.
    - vitest pinned to `en`, so existing text assertions keep proving the English copy.
    - Playwright pinned to `en`.
    - One ID smoke test.
- **Medium: `LAST_DATE_STATUS` semantics for PTR → On Hold → PTR.**
  - The clock may stop at the last move into PTR rather than the first.
  - Mitigation: confirm with Putra before G2. If first-entry is wanted, it becomes a Phase 3 item (needs a journal scan); do not block P1.

## Rollback

- **Application:** redeploy the previous image. V9 is additive (new nullable or defaulted columns and a restated view), so the old code runs against the V9 schema unchanged: it never selects the new columns, and `IS_CLOSED` keeps its name.
- **Behaviour-only rollback without a redeploy:**
  - `UPDATE CFG_STATUS_STAGE_MAP SET IS_CLOSED_OVERRIDE = NULL` restores Redmine's closed flag.
  - Cushions are editable in `CFG_SLA_POLICY`.
  - Either write bumps the generation and re-ETLs.
- **Full schema rollback:** a forward `V10` that restates the V3 view body. Flyway never runs down-migrations, so the columns can stay.
- **Frontend:** the previous bundle ignores `closed` and the definitions route. `localStorage['incident.locale']` is harmless to old code.

---

## Scope Census (auto-derived 2026-09-15)

Surface: incident-dashboard
Keywords matched: i18n · locale · migration · schema · accessibility

> Pattern Reference: see .agents/patterns/migrations.md (migration/schema) and .agents/patterns/api.md (facet params, definitions route).
> Pattern Reference: .agents/patterns/i18n.md, scaffolded by T101 before any extraction.
> Pattern Reference override (accessibility): no `.agents/patterns/accessibility.md`. The deliberate override is the a11y rules in this plan's UI Design Spec plus the `accessibility-reviewer` seat at G4. Recorded in the Decisions Log.

Commands run:
- `git ls-files 'FrontEnd/incident-dashboard/src/**/*.vue' 'FrontEnd/incident-dashboard/src/*.vue' | grep -v __tests__`: 24 files.
- `git ls-files BackEnd/incident-service/src | xargs grep -ln -E "IS_CLOSED|AT_RISK|0\.8\b|backlog\(\)|BACKLOG"`: 9 files.

**Pattern — i18n rows:** no user-visible chrome literal remains in the template or script; copy comes from `t()` with en/id keys.
**Pattern — close/at-risk rows:** reads `IS_CLOSED` / `SLA_AT_RISK` from the V9 view; no inline 0.8 ratio for At Risk; no Backlog tile.

Sites (35):
- SC001 FrontEnd/incident-dashboard/src/App.vue   [ ]
- SC002 FrontEnd/incident-dashboard/src/components/charts/CreatedClosedTrend.vue   [ ]
- SC003 FrontEnd/incident-dashboard/src/components/charts/ImpactUrgencyHeatmap.vue   [ ]
- SC004 FrontEnd/incident-dashboard/src/components/charts/MttrComparison.vue   [ ]
- SC005 FrontEnd/incident-dashboard/src/components/charts/SlaBucketBars.vue   [ ]
- SC006 FrontEnd/incident-dashboard/src/components/charts/TopCategories.vue   [ ]
- SC007 FrontEnd/incident-dashboard/src/components/charts/WorkaroundDebtCard.vue   [ ]
- SC008 FrontEnd/incident-dashboard/src/components/common/FreshnessIndicator.vue   [ ]
- SC009 FrontEnd/incident-dashboard/src/components/filters/IncidentFilterBar.vue   [ ]
- SC010 FrontEnd/incident-dashboard/src/components/kpi/KpiCard.vue   [ ]
- SC011 FrontEnd/incident-dashboard/src/components/kpi/KpiRow.vue   [ ]
- SC012 FrontEnd/incident-dashboard/src/components/table/IncidentDetailDrawer.vue   [ ]
- SC013 FrontEnd/incident-dashboard/src/components/table/IncidentTable.vue   [ ]
- SC014 FrontEnd/incident-dashboard/src/layout/AppLayout.vue   [ ]
- SC015 FrontEnd/incident-dashboard/src/layout/AppSidebar.vue   [ ]
- SC016 FrontEnd/incident-dashboard/src/layout/WallboardLayout.vue   [ ]
- SC017 FrontEnd/incident-dashboard/src/views/ChangePasswordView.vue   [ ]
- SC018 FrontEnd/incident-dashboard/src/views/ExecView.vue   [ ]
- SC019 FrontEnd/incident-dashboard/src/views/LoginView.vue   [ ]
- SC020 FrontEnd/incident-dashboard/src/views/NotFoundView.vue   [ ]
- SC021 FrontEnd/incident-dashboard/src/views/OpsView.vue   [ ]
- SC022 FrontEnd/incident-dashboard/src/views/WallboardView.vue   [ ]
- SC023 FrontEnd/incident-dashboard/src/views/admin/UserFormView.vue   [ ]
- SC024 FrontEnd/incident-dashboard/src/views/admin/UserTableView.vue   [ ]
- SC025 FrontEnd/incident-dashboard/src/utils/date.ts   [ ]
- SC026 FrontEnd/incident-dashboard/src/utils/severity.ts: ⓘ states time-used vs At Risk; no At Risk logic here   [ ]
- SC027 BackEnd/incident-service/src/main/java/id/co/yokke/incidentdashboard/domain/usecase/query/IncidentQueryUseCase.java   [ ]
- SC028 BackEnd/incident-service/src/main/java/id/co/yokke/incidentdashboard/domain/usecase/query/MetricsUseCase.java   [ ]
- SC029 BackEnd/incident-service/src/main/resources/db/migration/V3__mart.sql: unchanged; superseded by V9 view   [ ]
- SC030 BackEnd/incident-service/src/main/resources/db/migration/V9__close_clock_and_at_risk.sql (new)   [ ]
- SC031 BackEnd/incident-service/src/test/java/id/co/yokke/incidentdashboard/config/ConfigTablesIT.java   [ ]
- SC032 BackEnd/incident-service/src/test/java/id/co/yokke/incidentdashboard/mart/MartSchemaIT.java   [ ]
- SC033 BackEnd/incident-service/src/test/java/id/co/yokke/incidentdashboard/query/IncidentQueryIT.java   [ ]
- SC034 BackEnd/incident-service/src/test/java/id/co/yokke/incidentdashboard/query/MetricsIT.java   [ ]
- SC035 FrontEnd/incident-dashboard/src/apis/metrics.ts + src/stores/filters.ts (`backlog` consumers)   [ ]

V1 and V2 matched the grep but are excluded: they are applied migrations and are never edited, and V9 alters them forward.

**Verification:**
- **SC001–SC025:** Tier 1. A vitest spec per component renders under `id` and asserts at least one Indonesian chrome string, and the parity test passes.
- **SC026–SC035:** Tier 1 + 2 plus the matrix rows cited in T201–T302.
- **Final:** `verify-wave --final` census coverage.

---

## Decisions Log

- **2026-09-15, design system:** the straventa design system does not apply to `mti/`. The Yokke PrimeVue preset (`src/main.ts`, `src/main.css`) is the system of record; the brand check is `brand-compliance-checker`.
- **2026-09-15, At Risk redefined** (user: "research it for the latest sota … implement it to your liking"):
  - Rule: open, not breached, and remaining calendar days ≤ a per-priority cushion (Critical 1 · High 2 · Medium 3 · Low 7), in `CFG_SLA_POLICY`.
  - Sources surveyed:
    - ServiceNow SLA stages (50/75/100%).
    - Jira Service Management "at risk" (80% elapsed, clock running).
    - Zendesk, Freshservice and ManageEngine absolute time-remaining warnings.
    - WCAG 1.4.1.
  - Absolute cushions chosen because the ladder spans 3–60 days. Calendar-day grain matches breach measurement; move to hours when DWH timestamps land (Phase 3).
- **2026-09-15, close clock** (note 12, "Backlog close aja / PTR treated as closed"):
  - Stop precedence is cf105 → `CLOSED_ON` → `LAST_DATE_STATUS`.
  - PTR (11) and PTR with permission (63) are closed through `IS_CLOSED_OVERRIDE`, not a stage change.
  - A late close stays breached.
  - This intentionally diverges from the source query's `AGING_NEW` for closed tickets without cf105 (the 50-day false breach from the meeting). The `ExcelParityIT` fixture 22878 expectation changes for this recorded requirement change.
- **2026-09-15, faceted tiles** (architect): each tile applies the other facet, not its own dimension's active value; the table applies both.
  - Charts (trend, categories, matrix, buckets, MTTR) do not follow tile facets. They describe the period; the tiles slice the table.
  - The chip row makes the table scope explicit.
- **2026-09-15, Backlog:** the tile is removed everywhere (note 12 + meeting). The API keeps `backlog` for one release as `@Deprecated`; `MetricsIT` equality moves to `open + closed == backlog`.
- **2026-09-15, ⓘ numbers:** served by `GET /api/metrics/definitions` from config; wording lives in i18n (architect).
- **2026-09-15, i18n:**
  - `vue-i18n` 11 Composition mode, with `primelocale` for PrimeVue.
  - Chrome is translated; Redmine data values are not.
  - Default `en`, persisted per browser.
  - Playwright pinned to `en` plus one ID smoke test (user: "Implement a switch for indonesian and english").
- **2026-09-15, accessibility pattern override:** no `.agents/patterns/accessibility.md`. The UI Design Spec a11y rules plus the `accessibility-reviewer` gate stand in for it.
- **2026-09-15, SLA buckets and heatmap keep percentage bands:** they show the time-used distribution. The architect's suggestion to move all three 80% sites is applied only to the At Risk constant (`MetricsUseCase:61`); `V3:185` and `severity.ts:26` stay and get ⓘ text distinguishing them.
- **2026-09-15, `INTERNAL_COLUMNS`:** `REDMINE_IS_CLOSED` and `SLA_AT_RISK_DAYS` are excluded from export; `STOP_AT`, `CLOSE_BASIS` and `SLA_AT_RISK` are exported.
- **Deferred to Phase 2:**
  - Three menus.
  - Wallboard rotation.
  - Incident-scoped matrix with a missing impact/urgency count.
  - SLA compliance = met ÷ closed in period.
- **Deferred to Phase 3:**
  - Hour/minute/second durations (DWH).
  - Response SLA and SPO matrix (Putra).
  - Final status open/close mapping (Putra).
  - The `AGING_NEW` replacement.
