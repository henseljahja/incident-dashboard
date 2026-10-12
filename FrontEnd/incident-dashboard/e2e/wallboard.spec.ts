import { expect, test } from './fixtures/test'

import { ACCOUNTS, API_BASE_URL, login, shot } from './fixtures/seed'

/**
 * The unattended TV surface. Two properties matter and neither is cosmetic:
 *
 *  - it is FORCED dark, regardless of the viewer's OS/browser preference, because there is no
 *    user session on a wallboard to read a preference from;
 *  - it carries NO nav chrome — no header, no logout, no filter bar — because there is nobody
 *    standing at it to click anything.
 *
 * The colour-scheme emulation below is the test: the browser is told to prefer LIGHT, and the
 * wallboard has to come up dark anyway.
 */
test.use({ colorScheme: 'light' })

test.describe('wallboard', () => {
  test('lands dark, chrome-free and showing real numbers', async ({ page }) => {
    const wallboardResponse = page.waitForResponse((response) =>
      response.url().startsWith(`${API_BASE_URL}/metrics/wallboard`),
    )

    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard')
    expect(new URL(page.url()).pathname).toBe('/wallboard')

    const layout = page.getByTestId('wallboard-layout')
    await expect(layout).toBeVisible()

    // ------------------------------------------------------------------ forced dark mode
    // `app-dark` is PrimeVue's `darkModeSelector` (see main.ts). Asserting the class alone
    // would pass even if the styling were dropped, so the rendered background is checked too.
    await expect(layout).toHaveClass(/\bapp-dark\b/)
    // Resolved through a 1x1 canvas rather than parsed out of the computed value: Tailwind 4
    // emits `oklch(...)`, whose first component is a 0..1 lightness, and a naive digit scrape
    // reads `oklch(0.129 ...)` as "129" — a very dark colour mistaken for a very light one.
    const { background, channels } = await layout.evaluate((el) => {
      const value = getComputedStyle(el).backgroundColor
      const canvas = document.createElement('canvas')
      canvas.width = 1
      canvas.height = 1
      const context = canvas.getContext('2d')!
      context.fillStyle = value
      context.fillRect(0, 0, 1, 1)
      const [r, g, b] = context.getImageData(0, 0, 1, 1).data
      return { background: value, channels: [r, g, b] as number[] }
    })
    expect(
      Math.max(...channels),
      `the wallboard must render dark even under prefers-color-scheme: light (got ${background})`,
    ).toBeLessThan(60)

    // ------------------------------------------------------------------ no nav chrome
    await expect(page.locator('header')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Log out' })).toHaveCount(0)
    await expect(page.getByTestId('export-button')).toHaveCount(0)
    await expect(page.locator('#filter-priority')).toHaveCount(0)

    // ------------------------------------------------------------------ real, whole-population numbers
    const board = (await (await wallboardResponse).json()) as {
      total: number
      breached: number
      atRisk: number
      open: number
      closed: number
    }
    const english = new Intl.NumberFormat('en')
    await expect(page.getByTestId('wallboard-tiles')).toBeVisible()

    // Screen 1 ("Now"): the urgent pair, the open count and the year total. Resolved lives on
    // screen 2 only, and Backlog is gone (TM036, AC4.3).
    await expect(page.getByTestId('wallboard-tiles')).toHaveAttribute('data-screen', 'now')
    await expect(page.getByTestId('wallboard-breached')).toContainText(english.format(board.breached))
    await expect(page.getByTestId('wallboard-at-risk')).toContainText(english.format(board.atRisk))
    await expect(page.getByTestId('wallboard-open')).toContainText(english.format(board.open))
    await expect(page.getByTestId('wallboard-total')).toContainText(english.format(board.total))
    await expect(page.getByTestId('wallboard-resolved')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-closed')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-backlog')).toHaveCount(0)
    expect(board.open + board.closed, 'open + resolved is the year total').toBe(board.total)

    // Screen 2 ("Trend"): Resolved is the closed count; breached/at-risk are not repeated.
    await page.getByTestId('wallboard-rotation-next').click()
    await expect(page.getByTestId('wallboard-tiles')).toHaveAttribute('data-screen', 'trend')
    await expect(page.getByTestId('wallboard-resolved')).toContainText(english.format(board.closed))
    await expect(page.getByTestId('wallboard-open')).toContainText(english.format(board.open))
    await expect(page.getByTestId('wallboard-breached')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-at-risk')).toHaveCount(0)
    await shot(page, '30-wallboard-trend')
    await page.getByTestId('wallboard-rotation-next').click()
    await expect(page.getByTestId('wallboard-tiles')).toHaveAttribute('data-screen', 'now')

    // A wallboard shows the whole population: its request must carry no filter params.
    const boardUrl = new URL((await wallboardResponse).url())
    expect(
      [...boardUrl.searchParams.keys()],
      'the wallboard is unfiltered by design — a leaked filter would understate the room',
    ).toEqual([])

    await shot(page, '30-wallboard')
  })

  test('announces stale data at TV-legible size when the mirror is behind', async ({ page }) => {
    const freshnessResponse = page.waitForResponse(
      (response) => response.url() === `${API_BASE_URL}/freshness`,
    )

    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard')

    const freshness = (await (await freshnessResponse).json()) as { stale: boolean }
    const warning = page.getByTestId('wallboard-stale-warning')

    if (freshness.stale) {
      await expect(warning).toBeVisible()
      await expect(warning).toContainText('Stale Data')
      await expect(warning).toHaveAttribute('role', 'alert')
      await shot(page, '31-wallboard-stale-warning')
    } else {
      await expect(warning).toHaveCount(0)
      await shot(page, '31-wallboard-fresh')
    }
  })

  test('the wallboard role cannot reach the ops surface', async ({ page }) => {
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard')

    // WALLBOARD holds incident.wallboard:view ONLY (V5__seed_rbac.sql). `/api/metrics/matrix`
    // is incident.ops-gated server-side, so an ops surface for this role is a 403, not data.
    const forbidden = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${API_BASE_URL}/incidents`) && response.status() === 403,
    )
    await page.goto('/ops')
    expect(
      (await forbidden).status(),
      'the server, not the SPA, is the authority on what a role may read',
    ).toBe(403)

    await expect(page.getByTestId('incident-row')).toHaveCount(0)
    await shot(page, '32-wallboard-role-denied-on-ops')
  })
})
