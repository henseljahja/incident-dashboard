# Frontend product audit — incident dashboard (stakeholder notes P1)

## Metadata
- **App:** `FrontEnd/incident-dashboard` (Vue 3, PrimeVue 4 with the Yokke preset, Tailwind 4)
- **Routes:**
  - `/login`
  - `/ops`, including the tile facets, ⓘ popovers and drawer
  - `/exec`
  - `/wallboard` and `/wallboard?tv=1`
- **Languages:** EN and ID
- **Viewports:** 360×800, 768×1024, 1024×768, 1440×900, and 1920×1080 for the wallboard
- **Stack:**
  - Backend: isolated Testcontainers Oracle via `E2E_STACK=true ./mvnw test -Dtest=E2eStackLauncherTest`.
  - Frontend: the Vite dev server started by Playwright on :5175.
- **Screenshots:** `FrontEnd/incident-dashboard/e2e/screenshots/audit-before/` and `.../audit/` (after the fixes).
  - These are gitignored on purpose: they render seeded, production-derived incident subjects. Keep them local.
- **Design system:** the Yokke PrimeVue preset (`src/main.ts`) plus `src/main.css` tokens, kept in step with yob.
  - The 505base brand system was checked and rejected: it is a different company's brand (red hero, Geist), and applying it would re-skin a Yokke product.
- **Blockers:** none.

## Executive summary
The P1 features read clearly: tiles that filter, a chip row, the No Redmine column, a bilingual ⓘ on every component, and Closed replacing Backlog.

The biggest problems were in the details, not the concept:
- Operational controls were below the 44px touch target.
- The trend chart still showed Backlog.
- The Impact × Urgency matrix was clipped inside a one-third-width card.
- Backend stale-reason codes (`mirror-lagging`) leaked into the UI.
- At phone width, the tile header crowded the label.

All P1 and P2 findings are fixed, and each is guarded by an e2e assertion in `e2e/stakeholder-notes.spec.ts`.

## Findings

| ID | Sev | Category | Issue → fix | Files | Acceptance check (e2e) | Status |
|---|---|---|---|---|---|---|
| FPA-001 | P1 | Touch targets | 13 controls under 44px: Export, More filters, Clear filters, Prev/Next, link-style "Clear filters", sidebar nav link (38px), logout and nav toggle (40px), date pickers (42px), wallboard chrome (40px). All raised to `min-h-11` (`min-w-11` for icon buttons). | `IncidentTable.vue`, `IncidentFilterBar.vue`, `AppLayout.vue`, `AppSidebar.vue`, `WallboardLayout.vue` | phone test: export, advanced-filter and clear-all controls ≥ 44px; all ⓘ ≥ 44px | fixed |
| FPA-002 | P1 | Product consistency | The trend chart was still titled "Created / Closed / Backlog" and drew a Backlog line, after Backlog was removed everywhere else. Removed the series, the second y-axis and the screen-reader column; title and ⓘ copy updated in EN and ID. | `CreatedClosedTrend.vue`, `locales/*.json` | desktop test: heading "Created / Closed", sr-table headers Period/Created/Closed, no "backlog" | fixed |
| FPA-003 | P2 | Layout | Real data has up to 6 impact columns at 64px each, which a one-third card clipped behind a scroll container with no visible cue. The matrix now spans 2/3, and the trend takes a full-width row with a fixed 224px chart height and bars capped at 48px, so a few periods don't render as slabs. | `OpsView.vue`, `CreatedClosedTrend.vue` | desktop test: the matrix scroll container does not overflow at 1440px | fixed |
| FPA-004 | P2 | Copy / i18n | Stale banners showed machine codes (`mirror-lagging`, `etl-stalled`) in both languages. Added `useStaleReason()` with `common.freshness.reason.*` in EN and ID; an unknown code falls back to the raw code. The error copy "Could not reach the freshness endpoint" became plain language. | `utils/freshness.ts`, `FreshnessIndicator.vue`, `WallboardView.vue`, `locales/*.json` | ID and wallboard tests: body contains no raw stale code | fixed |
| FPA-005 | P2 | Responsive craft | At 360px, the pressed ✓ and label ran under the tone icon and ⓘ, and "AT RISK" wrapped. Fixes: ✓ now leads the label; the decorative tone icon hides below `md` (wrapped in a span, because the unlayered primeicons `.pi {display}` beats utility classes); the icon cluster sits at `right-1`; label tracking is normal below `md`. | `KpiCard.vue` | phone test: every tile label ends before its ⓘ glyph and doesn't wrap on short labels; tone icon hidden | fixed |
| FPA-006 | P3 | Hierarchy | 7 tiles in a 4-column grid leave one empty cell on desktop. A candidate fix is to move Workaround Debt next to the MTTR pair as a 3-up secondary row. | `KpiRow.vue` | — | open |
| FPA-007 | P3 | Mobile density | At 360px, the stale banner, freshness ⓘ and language switch stack about 150px above the first tile. A candidate fix is to move the language switch into the sidebar on narrow widths. | `AppLayout.vue` | — | open |
| FPA-008 | P3 | Responsive | At 360px the matrix still scrolls horizontally inside its card, by design, but with no scroll affordance. Consider an edge fade. | `ImpactUrgencyHeatmap.vue` | — | open |
| FPA-009 | P3 | Consistency | Exec: only the Workaround Debt tile has a tone rule, and the four headline tiles have none. | `ExecView.vue` | — | open (pre-existing) |

**Measured, not a finding:** the only remaining sub-44px element is the 166×24 hidden input inside PrimeVue `Select`. Its clickable trigger is the full-size control.

## Checks performed (after the fixes)
- **Horizontal scroll:** none at 360, 768, 1024 or 1440, nor on the wallboard at 1920 in TV mode.
- **Popovers:** every ⓘ on Ops (≥ 14 surfaces), in EN and ID:
  - meaning, formula and source are all non-empty
  - no raw i18n key and no `{placeholder}` leaks
  - the popover stays inside the viewport
  - Esc closes it
- **Indonesian:**
  - chrome is translated on Ops, Exec and the wallboard
  - Redmine status names stay unchanged
  - facet chips are translated
  - the choice survives a reload
- **Export:** `sla=BREACHED` carries into `/api/incidents/export`, every exported row is Breached, and an `.xlsx` downloads.
- **Drawer:** opened from a filtered table, it deep-links the same ticket (href, `target=_blank`, `rel=noopener noreferrer`), and Esc closes it.
- **Dark OS setting:** the light dashboard stays legible (explicit body ground, white tiles). Only the wallboard is dark, which is intended.
- **Wallboard:** shows the 4 incident tiles and no Backlog; the ⓘ shows off TV mode and hides in TV mode.

## Verification
- **Frontend unit gate:** `pnpm type-check && pnpm lint && pnpm format:check && pnpm test --run` passes, 237 tests.
- **Playwright:** `pnpm exec playwright test` on the isolated stack gives **45 passed**:
  - 34 pre-existing
  - 2 new in `ops-facets.spec.ts`
  - 9 new in `stakeholder-notes.spec.ts`

## Next pass
`$frontend-polish-pass fix report found in .agents/audits/frontend-product-audit/20260915-135941` covers FPA-006 to FPA-009, all P3.
