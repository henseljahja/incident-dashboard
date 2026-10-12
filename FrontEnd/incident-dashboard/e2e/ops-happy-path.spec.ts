import type { Page } from '@playwright/test'

import { expect, test } from './fixtures/test'

import { ACCOUNTS, API_BASE_URL, REDMINE_BASE_URL, login, shot } from './fixtures/seed'

/**
 * The tier-3 happy path an ops manager actually walks:
 *
 *   log in -> RBAC lands them -> filter -> the filter is in the URL -> drill a heatmap cell ->
 *   open a row -> the drawer offers the ONE action this read-only system has: a Redmine link.
 *
 * Every assertion is on something the user or a colleague can see: the address bar, rendered
 * text, a real network request. Nothing is stubbed.
 */

/** The `/api/incidents` page request the table is currently rendering. */
function incidentsRequest(page: Page, predicate: (url: string) => boolean) {
  return page.waitForRequest(
    (request) => request.url().startsWith(`${API_BASE_URL}/incidents`) && predicate(request.url()),
  )
}

test.describe('ops manager happy path', () => {
  test('log in, filter, drill, and reach the Redmine deep link', async ({ page }) => {
    // ---------------------------------------------------------------- 1. RBAC landing
    // No route is chosen anywhere in this block. `login()` goes to /login and nothing else;
    // arriving at /ops proves the role's DEFAULT_ROUTE round-trips through the real
    // /auth/token payload, the auth store and the router guard — not just through the unit
    // test of `resolveEntry()`.
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    expect(new URL(page.url()).pathname, 'RBAC, not the SPA, picks the landing view').toBe('/ops')

    await expect(page.getByRole('heading', { level: 1, name: 'Incident' })).toBeVisible()
    await expect(page.getByTestId('freshness-indicator')).toBeVisible()
    await shot(page, '10-ops-landing')

    // The table has to have data for the rest of the walkthrough to mean anything.
    const rows = page.getByTestId('incident-row')
    await expect(rows.first()).toBeVisible()
    const rowCountBefore = await rows.count()
    expect(rowCountBefore, 'the seeded mart must return incidents').toBeGreaterThan(0)

    // ---------------------------------------------------------------- 2. filter -> URL
    // A shareable view is the entire point of the filter bar: whatever the operator picks has
    // to end up in the address bar AND on the wire.
    const filteredRequest = incidentsRequest(page, (url) => url.includes('priority=High'))

    // The MultiSelect's `input-id` lands on a HIDDEN input that sits behind
    // `.p-multiselect-label-container`, so clicking it by id is intercepted by that container
    // — Playwright reports "subtree intercepts pointer events", which reads like a flake and
    // is not one. Click the visible widget, which is what a person clicks.
    // Priority now lives behind the "More filters" disclosure — the bar shows only date range
    // and tracker inline, and reveals the rest on demand. Open it before reaching for the
    // widget; the toggle is idempotent-safe here because the panel starts collapsed when no
    // advanced filter is active, which is the state this test arrives in.
    await page.getByTestId('filter-advanced-toggle').click()
    await expect(page.locator('#filter-advanced-panel')).toBeVisible()

    await page
      .locator('.p-multiselect')
      .filter({ has: page.locator('#filter-priority') })
      .click()
    await page.getByRole('option', { name: 'High', exact: true }).click()
    await page.keyboard.press('Escape')

    await filteredRequest
    await expect
      .poll(() => new URL(page.url()).searchParams.get('priority'), {
        message: 'the chosen filter must be in the URL — a colleague pastes this link',
      })
      .toBe('High')

    // Paging is in the URL too, so the link reproduces the exact page, not just the filter.
    expect(new URL(page.url()).searchParams.get('page')).toBe('0')
    await shot(page, '11-ops-filter-in-url')

    // ...and the URL genuinely reconstitutes the view: a cold load of the shared link comes
    // back with the same filter applied, which is what "shareable" has to mean.
    const sharedUrl = page.url()
    await page.goto(sharedUrl)
    await expect(page.getByTestId('impact-urgency-heatmap')).toBeVisible()
    await expect
      .poll(() => new URL(page.url()).searchParams.get('priority'))
      .toBe('High')

    // ---------------------------------------------------------------- 3. heatmap drill
    // Back to the unfiltered view so there is a populated cell to drill.
    await page.goto('/ops')
    await expect(page.getByTestId('impact-urgency-heatmap')).toBeVisible()

    // `data-count` lives on the cell button itself, so this is an attribute filter on the
    // element rather than a `hasNot` on a descendant.
    const populatedCell = page
      .locator('[data-testid="heatmap-cell"]:not([data-count="0"])')
      .first()
    await expect(populatedCell).toBeVisible()
    const drilledImpact = await populatedCell.getAttribute('data-impact')
    const drilledUrgency = await populatedCell.getAttribute('data-urgency')
    expect(drilledImpact, 'a cell always carries a real axis label, never null').toBeTruthy()
    expect(drilledUrgency).toBeTruthy()
    await shot(page, '12-ops-heatmap-before-drill')

    await populatedCell.click()

    // Drill-through narrows the operator's ATTENTION to the table, deliberately without
    // touching the filter state: `IncidentFilter` has no impact/urgency dimension (see
    // `OpsView.onHeatmapDrill`), so silently rewriting the query string would produce a
    // shareable URL that does not reproduce what was clicked. This asserts BOTH halves —
    // the table comes into view, and the URL is left honest.
    const table = page.getByTestId('incident-row').first()
    await expect(table).toBeInViewport({ timeout: 15_000 })
    expect(
      new URL(page.url()).searchParams.get('priority'),
      'the drill must not invent a filter the URL cannot reproduce',
    ).toBeNull()
    await shot(page, '13-ops-heatmap-drilled-to-table')

    // ---------------------------------------------------------------- 4. row -> drawer -> Redmine
    // The `<tr>` is not the control. The click handler lives on a `<button>` inside it
    // (`incident-row-select`) — a row with a bare click handler is invisible to the keyboard,
    // so the affordance was moved into a real button. Driving the `<tr>` here silently does
    // nothing and the drawer never opens.
    // The first column is the Redmine number, linked straight to the ticket (AC5.1).
    const numberLink = page.getByTestId('incident-row').first().getByTestId('incident-redmine-link')
    await expect(numberLink).toHaveAttribute(
      'href',
      new RegExp(`^${REDMINE_BASE_URL.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')}/issues/\\d+$`),
    )
    await expect(numberLink).toHaveAttribute('target', '_blank')
    await expect(numberLink).toHaveAttribute('rel', 'noopener noreferrer')

    const firstRow = page.getByTestId('incident-row-select').first()
    await firstRow.click()

    const drawer = page.getByRole('dialog')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByText('Incident detail')).toBeVisible()

    // Redmine is the permanent write side. This link is the ONLY action the drawer offers,
    // and it is built from the configured base URL plus the numeric issue id — never from a
    // URL field carried in row data.
    const redmineLink = drawer.getByRole('link', { name: 'Open in Redmine' })
    await expect(redmineLink).toBeVisible()
    const href = await redmineLink.getAttribute('href')
    expect(href, 'the deep link must be built from the configured Redmine base URL').toMatch(
      new RegExp(`^${REDMINE_BASE_URL.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')}/issues/\\d+$`),
    )
    await expect(redmineLink).toHaveAttribute('target', '_blank')
    await expect(redmineLink).toHaveAttribute('rel', /noopener/)

    // The ticket number in the drawer header is the same deep link (AC5.4).
    const headerLink = drawer.getByTestId('drawer-redmine-number')
    await expect(headerLink).toHaveText(/^#\d+$/)
    await expect(headerLink).toHaveAttribute('href', href!)
    await expect(headerLink).toHaveAttribute('rel', 'noopener noreferrer')

    // Read-only, permanently: no edit/save affordance may appear in the drawer.
    await expect(drawer.getByRole('button', { name: /save|edit|update|delete/i })).toHaveCount(0)
    await shot(page, '14-ops-detail-drawer-redmine-link')
  })

  test('the export affordance is present for a role that holds the export privilege', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    // OPS_MANAGER holds incident.ops:export (V5__seed_rbac.sql), so `v-can` renders the
    // button. This is a UI affordance only — the server is the authority — but its absence
    // for a privileged role would be a real regression.
    await expect(page.getByTestId('export-button')).toBeVisible()
    await shot(page, '15-ops-export-affordance')
  })
})
