# Stakeholder notes — Phase 2

Status: approved (plain plan, per 2026-09-15 feedback; user said "do all of those" on 2026-10-07)
Charter: none · Parent spec: `docs/superpowers/specs/2026-09-15-stakeholder-notes-design.md` §4
Mockup (reviewed scope): https://claude.ai/artifact/VkWppMdUKFK4wFEipiixsk

**Goal.** Three scoped menus (Incident, Problem, Service Request), per-group count tiles, a
wallboard that is Incident-only and rotates between two screens, the impact × urgency matrix
scoped to Incident with a "missing data" count, and the stakeholders' updated datamart query
applied where it affects us. Then a UI/UX audit-and-polish pass on every screen touched.

**Out of scope / blocked.** Hours-minutes-seconds durations and the response SLA (P3: DWH, Putra).
Changing which statuses count as closed (open question for Putra: their grouped "Close" list
omits PTR 11 / PTR-with-permission 63 and includes Drop 6 and Request for Approval 64). **Nothing
is deployed:** the host `mti-dev-inc01` has a full disk (root needed). Local verification only.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | `incident.ops` stays the **Incident** menu (route `/ops`, relabelled "Incident"). New menus `incident.problem` (`/problem`) and `incident.sr` (`/service-requests`). | Keeps every default route, grant, deep link and e2e path working. |
| D2 | Scope is a **server-side request param** `scope=INCIDENT\|PROBLEM\|SERVICE_REQUEST` on every filter-bearing endpoint, resolved through config (`CFG_TRACKER_SCOPE.SCOPE_GROUP`), not a client-side tracker pick. | A scope that lives in the browser is undone by "Clear all" or an edited URL. |
| D3 | The filters store holds `scope` in its own ref that `reset()` and `applyFromQueryString()` never touch; the page sets it from route meta. `serialize()` omits it (the path carries it). | One place injects scope into KPI, charts, table and export. |
| D4 | Wallboard data comes from one new endpoint `GET /api/metrics/wallboard`, scope **fixed to INCIDENT on the server**. | The wallboard role has no row-level access, and "Incident only" must not be a client choice. |
| D5 | AGING_INCIDENT is dropped from the API row and export (their new query dropped it; note 16). `V_INCIDENT` is **not** rewritten. | The UI never showed it; a view rewrite is risk for no gain. |
| D6 | Our `AGING_NEW` (open ticket with no/blank close date counts to today) is kept; the divergence from their new `CASE` is documented in `docs/reference/PARITY.md`. | Their NULL-for-missing-row result would reintroduce blank aging on open tickets. |
| D7 | Their grouped status names are NOT adopted as a new column or as the open/closed definition. The existing stage map stays. | Closed-list is Putra's call (open question). |
| D8 | Matrix shown on the Incident page only. Problem / SR tickets do not use impact/urgency. | Meeting defect M2. |

## API contract (backend produces, frontend consumes)

**Scope param.** `scope` (optional) on `/api/incidents`, `/api/incidents/export`,
`/api/metrics/{kpi,trend,top-categories,matrix,sla-buckets}`. Case-insensitive. Unknown value →
400 `invalid_filter`. Absent → all trackers (today's behaviour). Implemented in `IncidentFilter`
and the shared predicate, so KPI tiles, charts and the table can never disagree.

**Config.** Migration `V10__scoped_menus.sql`:
- `ALTER TABLE CFG_TRACKER_SCOPE ADD SCOPE_GROUP VARCHAR2(20)`; seed 7→INCIDENT, 41→PROBLEM,
  16/32/33→SERVICE_REQUEST. Bumps the config generation once (follow `V9`'s single-bump pattern).
- `APP_MENU`: relabel `incident.ops` → `Incident`; add `incident.problem` `/problem` sort 11,
  `incident.sr` `/service-requests` sort 12. Grants for every role = that role's existing
  `incident.ops` grants, copied (ADMIN view+export, OPS_MANAGER view+export, VIEWER view).
  EXEC and WALLBOARD get none of the new menus.
- Every `@RequirePrivilege(menu = {...})` that lists `incident.ops` also lists `incident.problem`
  and `incident.sr` (list, export, kpi, trend, top-categories, matrix, sla-buckets, definitions,
  freshness). `/api/metrics/wallboard` and `scope-counts` per below.

**`GET /api/metrics/scope-counts`** — menus: ops, problem, sr, exec, wallboard. Honors `dateFrom`
and `dateTo` only (ignores `scope`/`tracker`). Always three entries, in this order:
```json
{ "scopes": [
  {"scope":"INCIDENT","open":0,"closed":0,"breached":0,"atRisk":0,"total":0},
  {"scope":"PROBLEM", "open":0,"closed":0,"breached":0,"atRisk":0,"total":0},
  {"scope":"SERVICE_REQUEST","open":0,"closed":0,"breached":0,"atRisk":0,"total":0} ] }
```
Invariant: `open + closed == total` per scope (the KPI invariant).

**Matrix.** Response gains `missingCount` (int): rows in the filtered set whose impact OR urgency
is null/blank. New filter param `matrixMissing=true` on `/api/incidents` and export narrows to
exactly those rows (so the "N missing" link opens the same N).

**`GET /api/metrics/wallboard`** — menus: wallboard, ops, exec. No params honored (scope fixed
INCIDENT; window = 1 Jan of the current year → today, Asia/Jakarta):
```json
{ "generatedAt":"2026-10-07T09:00:00Z", "year":2026,
  "total":0,"open":0,"closed":0,"breached":0,"atRisk":0,
  "slaCompliance":{"met":0,"closedInPeriod":0,"percent":null},
  "byStage":[{"stage":"New","count":0}],
  "byPriority":[{"priority":"Critical","count":0}],
  "criticalHighOpen":[{"noIncident":0,"subject":"","status":"","priority":"","elapsedDays":0,"slaDays":0}],
  "topCategories":[{"category":"","count":0}],
  "monthly":[{"month":"2026-01","created":0,"closed":0}],
  "avgResolutionDays":null,
  "groupCounts":[ /* same entries as scope-counts */ ] }
```
`slaCompliance.percent` = round(met ÷ closedInPeriod × 100) or `null` when nothing closed; `met` =
closed rows with not breached. `byStage` follows the stage ladder order; `byPriority` is
Critical, High, Medium, Low, then Unknown if present. `criticalHighOpen` = open, priority
Critical/High, top 10 by elapsed ÷ SLA descending. `topCategories` top 5. `monthly` covers January
→ current month, zero-filled. `avgResolutionDays` = mean elapsed of closed rows, 1 decimal, or null.

**Export / API row.** `AGING_INCIDENT` removed from both.

## Frontend design

- Router: `/problem` and `/service-requests` render the existing `OpsView` with route meta
  `scope` (`/ops` → `INCIDENT`). One view, three scopes; title from the menu label.
- `stores/filters.ts`: `scope` ref + `setScope()`; included in `toFilterParams()` and
  `toBaseFilterParams()`; excluded from `reset()`, `applyFromQueryString()`, `serialize()`.
  Changing scope resets the other filters and page.
- `OpsView`: matrix and its "N missing" link only when scope is INCIDENT; the tracker filter's
  options are limited to the scope's trackers (SR: three; others: one, so the control is hidden).
- New `components/kpi/ScopeCounts.vue`: three tiles (Incident / Problem / Service Request) with
  open count and a breached badge, each a link to its menu; the current scope is marked
  `aria-current`. Shown at the top of the three ops pages and on wallboard screen 1.
- Sidebar: `MENU_ORDER` and `MENU_ICONS` for the new ids; `nav.menu.problem`, `nav.menu.sr`,
  `nav.menu.ops` = Incident in en/id.
- Wallboard: rewritten to two rotating screens fed by `/api/metrics/wallboard`.
  Screen 1 "Now": group counts, total, SLA compliance, breached, at risk, by stage, by priority,
  Critical/High open list. Screen 2 "Trend": top 5 categories, monthly created/closed, resolved,
  open, average resolution. Interval selector 30 s / 1 min / 2 min / 5 min, default 1 min, stored
  in `localStorage['incident.wallboard.interval']`; pause/resume, next; progress bar and
  "Screen 1 of 2 · next in 42 s" in a live region. `?tv=1` hides ⓘ and the controls. Rotation
  stops while `document.hidden`; respects `prefers-reduced-motion` (no animated progress).
  The 60 s data refresh stays; stale/unreachable banners stay.
- Copy EN + ID for everything new; the parity test keeps the keys in step. ⓘ definitions for
  every new component through the same `definitions` catalog (SLA compliance = met ÷ closed in
  period, group counts, matrix "missing").
- Design system: Yokke PrimeVue preset and `src/main.css` tokens only; no new hex values; ≥ 44 px
  targets; dark mode only on the wallboard; one primary action per screen.

## Test matrix

| ID | Kind | Case | Tier |
|---|---|---|---|
| TM01 | positive | per-scope counts sum to the unscoped total; `open + closed == total` per scope | IT |
| TM02 | negative | `scope=bogus` → 400 `invalid_filter` on list, kpi, matrix | IT |
| TM03 | positive | a Problem-scope KPI equals the Problem-scope table total | IT |
| TM04 | authz | VIEWER sees the three menus; EXEC/WALLBOARD see none of the new ones; wallboard role gets 403 on `/api/incidents` | IT |
| TM05 | positive | V10 seeds menus + grants and scope groups; config generation bumps exactly once | IT |
| TM06 | positive | wallboard payload fields and invariants (compliance, byStage sums to total, monthly zero-filled) | IT |
| TM07 | positive | matrix `missingCount` equals the `matrixMissing=true` table total | IT |
| TM08 | negative | export and API row carry no `AGING_INCIDENT` | IT |
| TM09 | ui-state | scoped pages: loading, empty (no tickets in scope), error, populated | unit |
| TM10 | positive | "Clear all" and URL edits cannot drop the scope | unit |
| TM11 | positive | rotation: default 60 s, interval persists, pause/next, stops when hidden | unit |
| TM12 | e2e | sidebar shows three menus; each page's KPI == its table total; matrix only on Incident | e2e |
| TM13 | e2e | wallboard shows Incident only, rotates between 2 screens, TV mode hides controls, EN + ID | e2e |
| TM14 | e2e | count tiles navigate to the right menu and match each page's Open tile | e2e |
| TM15 | a11y/ux | phone/tablet/desktop: no sideways scroll, ≥ 44 px targets, focus order, dark OS | e2e |

## Work split

1. **Backend lane** (Sonnet): V10, scope filter, endpoints, authz, tests TM01–TM08, PARITY note.
2. **Frontend lane** (Sonnet): everything under "Frontend design", tests TM09–TM11, i18n,
   unit specs; codes against the contract above without waiting for the backend.
3. **Integration** (me): wire, e2e specs TM12–TM15 on the isolated stack, then a
   `frontend-product-audit` pass on the touched screens and a polish pass on its findings.
4. **Verify**: backend `test` + `verify`, frontend type-check/lint/format/unit, full Playwright,
   local demo stack rebuilt so the user can see it. No deploy.
