import { expect, test } from './fixtures/test'

import { ACCOUNTS, API_BASE_URL, AUTH_BASE_URL, login, shot, trackPageErrors } from './fixtures/seed'

/**
 * The unhappy paths — the half of tier 3 that actually earns its keep.
 *
 * Bad credentials live in `login.spec.ts`; this file covers the three that only exist once a
 * session is running: a session that dies mid-flight, stale mirror data, and a filter that
 * matches nothing.
 */
test.describe('ops error paths', () => {
  /**
   * "Expired token mid-session."
   *
   * The access token is held in memory only, so it cannot be expired from the outside; what
   * keeps a session alive across a reload is the `HttpOnly` refresh cookie. Dropping that
   * cookie and reloading is therefore the faithful reproduction of an expired session: the
   * real `POST /auth/refresh-token` is called, really fails, `restoreSession()` really clears
   * the store, and the real router guard has to bounce the user. Nothing is stubbed and no
   * clock is faked.
   */
  test('a session that dies mid-flight lands the operator back on /login', async ({
    page,
    context,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await expect(page.getByRole('heading', { level: 1, name: 'Incident' })).toBeVisible()

    const refreshResponse = page.waitForResponse((response) =>
      response.url().startsWith(`${AUTH_BASE_URL}/auth/refresh-token`),
    )

    await context.clearCookies()
    await page.reload()

    expect(
      (await refreshResponse).status(),
      'the dead session must be discovered by a real 401 from the server',
    ).toBe(401)

    await page.waitForURL('**/login')
    expect(new URL(page.url()).pathname).toBe('/login')
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
    // A bounced session must not leave the previous user's data on screen.
    await expect(page.getByTestId('incident-row')).toHaveCount(0)
    await shot(page, '20-session-expired-redirect')
  })

  /**
   * The stale-freshness banner.
   *
   * This is asserted against whatever `GET /api/freshness` genuinely returns rather than
   * against a forced value: the seeded mirror's newest `updated_on` is historic fixture data,
   * far beyond `incident.etl.mirror-lag-threshold-seconds`, so a correctly-wired backend
   * reports `stale: true` and the operator MUST be told. The spec reads the endpoint first and
   * then insists the UI agrees with it — so it fails both if the banner disappears while the
   * data is stale AND if the banner is shown while the data is fresh.
   */
  test('the freshness indicator agrees with GET /api/freshness', async ({ page }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    const response = await page.waitForResponse((r) => r.url() === `${API_BASE_URL}/freshness`)
    const freshness = (await response.json()) as {
      stale: boolean
      staleReason: string | null
      lastRunAt: string | null
    }

    const staleBanner = page.getByTestId('freshness-stale-banner')
    const liveBadge = page.getByTestId('freshness-live')

    if (freshness.stale) {
      await expect(
        staleBanner,
        'stale data that is not announced is the failure mode this banner exists for',
      ).toBeVisible()
      await expect(staleBanner).toContainText('Dashboard data is stale')
      await expect(staleBanner).toHaveAttribute('role', 'alert')
      await expect(liveBadge).toHaveCount(0)
      await shot(page, '21-freshness-stale-banner')
    } else {
      await expect(liveBadge).toBeVisible()
      await expect(liveBadge).toContainText('Live')
      await expect(
        staleBanner,
        'a stale warning over fresh data trains operators to ignore it',
      ).toHaveCount(0)
      await shot(page, '21-freshness-live-badge')
    }
  })

  /**
   * An empty result must be an empty STATE — not a broken chart, not a blank page, and
   * emphatically not a stack trace. `pageerror` is tracked for the whole test, so an
   * exception thrown while rendering zero rows fails this outright.
   */
  test('a filter that matches nothing renders an empty state, not a broken page', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)

    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    // Applied via the URL because that is also the path a colleague's pasted link takes.
    await page.goto('/ops?institusi=__no_such_institusi__&page=0&size=25')

    await expect(page.getByTestId('incident-table-empty')).toBeVisible()
    await expect(page.getByTestId('incident-row')).toHaveCount(0)
    // Addressed by testid, not by copy: the count used to read "0 incident(s)" and now lives
    // inside the "Incidents (N)" section heading. What must hold is that the visible count
    // agrees with the row count above, whatever words surround it.
    await expect(page.getByTestId('incident-count')).toHaveText('(0)')

    // The heatmap still renders, with a real zero — it must not vanish, and it must not throw.
    const heatmap = page.getByTestId('impact-urgency-heatmap')
    await expect(heatmap).toBeVisible()
    await expect(page.getByTestId('heatmap-total')).toHaveText('Total: 0')

    // The page is still the page: chrome, filter bar and paging survive an empty result.
    await expect(page.getByRole('heading', { level: 1, name: 'Incident' })).toBeVisible()
    await expect(page.getByText('Page 1 of 1')).toBeVisible()

    await shot(page, '22-empty-filter-result')
    expect(errors, 'an empty result must not throw in the browser').toEqual([])
  })

  /**
   * A filter the SERVER rejects (an unknown sort column is not in `IncidentFilter`'s
   * allowlist) must surface as a handled failure, not as a white screen. The backend answers
   * 400 with its generic body; the SPA must stay on its feet.
   */
  test('a server-rejected filter does not white-screen the dashboard', async ({ page }) => {
    const errors = trackPageErrors(page)

    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    const rejected = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${API_BASE_URL}/incidents`) && response.status() === 400,
    )
    await page.goto('/ops?sort=DROP_TABLE&direction=asc&page=0&size=25')
    expect(
      (await rejected).status(),
      'the sort allowlist is enforced server-side, and must be',
    ).toBe(400)

    // Still a usable page — header, filter bar and heatmap all still rendered.
    await expect(page.getByRole('heading', { level: 1, name: 'Incident' })).toBeVisible()
    await expect(page.getByTestId('impact-urgency-heatmap')).toBeVisible()
    await shot(page, '23-rejected-filter-still-usable')
    expect(errors, 'a rejected request must be handled, not thrown out of the app').toEqual([])
  })
})
