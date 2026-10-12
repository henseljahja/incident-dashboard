import { expect, test } from './fixtures/test'

import { ACCOUNTS, API_BASE_URL, login, shot, trackPageErrors } from './fixtures/seed'

/** `YYYY-MM-DD` for a local-time date, matching `ExecView.formatDate`. */
function isoDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * The executive pulse. The load-bearing property is the WINDOW: this view is a monthly
 * summary, and a window that silently widened (or inherited the ops filter bar's state) would
 * change every number a stakeholder reads without changing anything visible on the page.
 *
 * So the window is asserted on the wire, against the real request the view issues.
 */
test.describe('exec pulse', () => {
  test('requests the current calendar month to date, and renders it', async ({ page }) => {
    const errors = trackPageErrors(page)

    const kpiRequest = page.waitForRequest((request) =>
      request.url().startsWith(`${API_BASE_URL}/metrics/kpi`),
    )

    await login(page, ACCOUNTS.exec.username)
    await page.waitForURL('**/exec')
    expect(new URL(page.url()).pathname, 'EXEC lands on /exec by role, not by choice').toBe(
      '/exec',
    )

    const now = new Date()
    const firstOfMonth = new Date(now.getFullYear(), now.getMonth(), 1)
    const params = new URL((await kpiRequest).url()).searchParams

    expect(params.get('dateFrom'), 'the window opens on the 1st of the current month').toBe(
      isoDate(firstOfMonth),
    )
    expect(params.get('dateTo'), 'and closes today — never an open bound').toBe(isoDate(now))
    // Bounded on BOTH sides is what makes the backend derive a real `previousPeriod`; an open
    // bound returns null and every delta on this page silently becomes "No prior period".
    expect(params.get('dateFrom')).not.toBeNull()
    expect(params.get('dateTo')).not.toBeNull()

    await expect(
      page.getByRole('heading', { level: 1, name: 'Executive' }),
    ).toBeVisible()
    await expect(page.getByTestId('exec-headline-tiles')).toBeVisible()

    // ...and the comparison window is stated exactly once for the whole section.
    await expect(page.getByText('compared with the previous period')).toHaveCount(1)

    // Backlog was replaced by Closed (stakeholder notes, AC4.1); it was the first key here.
    await expect(page.getByTestId('exec-tile-backlog')).toHaveCount(0)
    for (const key of ['open', 'closed', 'breached', 'at-risk']) {
      const value = page.getByTestId(`exec-tile-${key}-value`)
      await expect(value).toBeVisible()
      // A tile that renders the em-dash placeholder means the KPI call never resolved.
      // Grouped with `Intl` now (`1,234` in English), so the digits may carry thousands commas.
      await expect(value, `${key} must carry a real number from the mart`).toHaveText(
        /^\d{1,3}(,\d{3})*$/,
      )
      // The window used to be repeated on every tile ("+3 vs. previous period"); it is now
      // named once in the section header. Assert the stronger property the old copy-match was
      // standing in for: the delta is a real signed figure against a real prior period.
      // "No prior period" — the placeholder when the mart has no comparison window — fails this.
      await expect(
        page.getByTestId(`exec-tile-${key}-delta`),
        `${key} must carry a signed delta against the prior period`,
      ).toHaveText(/^([+-]\d{1,3}(,\d{3})*|\u00b10)$/)
    }

    await shot(page, '40-exec-monthly-pulse')
    expect(errors).toEqual([])
  })

  test('offers no edit affordance — every follow-up is a Redmine deep link', async ({ page }) => {
    await login(page, ACCOUNTS.exec.username)
    await page.waitForURL('**/exec')

    await expect(page.getByTestId('exec-view')).toBeVisible()
    // Strictly read-only, permanently: this system never mutates incident data.
    await expect(
      page.getByRole('button', { name: /save|edit|update|delete|assign/i }),
    ).toHaveCount(0)
    await expect(page.locator('form')).toHaveCount(0)
    await shot(page, '41-exec-read-only')
  })

  test('the exec role cannot read the ops incident list', async ({ page }) => {
    await login(page, ACCOUNTS.exec.username)
    await page.waitForURL('**/exec')

    // EXEC holds incident.exec:view only (V5__seed_rbac.sql).
    const forbidden = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${API_BASE_URL}/incidents`) && response.status() === 403,
    )
    await page.goto('/ops')
    expect((await forbidden).status()).toBe(403)
    await expect(page.getByTestId('incident-row')).toHaveCount(0)
    await shot(page, '42-exec-role-denied-on-ops')
  })
})
