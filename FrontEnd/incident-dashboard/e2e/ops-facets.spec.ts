import type { Locator, Page } from '@playwright/test'

import { ACCOUNTS, API_BASE_URL, login, shot, trackPageErrors } from './fixtures/seed'
import { expect, test } from './fixtures/test'

/**
 * TM033 — the KPI tiles ARE the table's status/SLA filter (US3).
 *
 * Walked against the real stack: click Breached, and the table, the chip row, the URL and the
 * tile's own number all have to agree. Nothing is stubbed; the counts come off the wire.
 */
/** A tile's number as rendered, e.g. "1,234" -> 1234. */
async function tileValue(page: Page, key: string): Promise<number> {
  const text = await page
    .getByTestId('kpi-tile')
    .filter({ has: page.locator(`[data-kpi="${key}"]`) })
    .getByTestId('kpi-value')
    .innerText()
  return Number(text.replace(/[^\d]/g, ''))
}

/** The table heading's total, e.g. "(95)" -> 95. */
async function tableTotal(page: Page): Promise<number> {
  return Number((await page.getByTestId('incident-count').innerText()).replace(/[^\d]/g, ''))
}

/**
 * Clicks a tile, waits for its pressed state, then waits until the table total has settled on
 * the tile's own number. A tile/table disagreement that never converges fails on timeout.
 */
async function press(page: Page, tile: Locator, key: string, pressed: boolean): Promise<number> {
  await tile.click()
  await expect(tile).toHaveAttribute('aria-pressed', String(pressed))
  await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
  if (pressed) {
    await expect
      .poll(async () => (await tileValue(page, key)) === (await tableTotal(page)))
      .toBe(true)
  }
  return tableTotal(page)
}

test.describe('ops KPI facets', () => {
  test('Breached filters the table to breached rows, names itself as a chip, and clears', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)

    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await expect(page.getByTestId('incident-row').first()).toBeVisible()

    const breached = page.locator('[data-kpi="breached"]')
    await expect(breached).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)

    // ------------------------------------------------------------------ apply
    const incidents = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${API_BASE_URL}/incidents?`) &&
        new URL(response.url()).searchParams.get('sla') === 'BREACHED',
    )
    const kpi = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${API_BASE_URL}/metrics/kpi`) &&
        new URL(response.url()).searchParams.get('sla') === 'BREACHED',
    )
    await breached.click()

    const page0 = (await (await incidents).json()) as {
      content: { SLA_BUCKET: string | null }[]
      totalElements: number
    }
    const tiles = (await (await kpi).json()) as { breached: number }

    await expect(breached).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('kpi-pressed-icon')).toHaveCount(1)
    await expect(page.getByTestId('active-filter-chip')).toHaveText(['SLA: Breached'])
    await expect.poll(() => new URL(page.url()).searchParams.get('sla')).toBe('BREACHED')

    // Only breached rows, on the wire and on screen.
    expect(page0.content.every((row) => row.SLA_BUCKET === 'Breached')).toBe(true)
    const rows = page.getByTestId('incident-row')
    await expect(rows).toHaveCount(page0.content.length)
    for (const row of await rows.all()) {
      await expect(row.locator('td').nth(8)).toHaveText('Breached')
    }

    // The active tile's number is the table total (the backend applies the tile's OTHER facet only).
    const english = new Intl.NumberFormat('en')
    expect(tiles.breached).toBe(page0.totalElements)
    await expect(page.getByTestId('incident-count')).toHaveText(
      `(${english.format(page0.totalElements)})`,
    )
    await expect(page.getByTestId('table-total-announcement')).toContainText(
      english.format(page0.totalElements),
    )
    await shot(page, '16-ops-facet-breached')

    // ------------------------------------------------------------------ remove the chip
    await page.getByTestId('active-filter-chip').click()

    await expect(breached).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
    await expect.poll(() => new URL(page.url()).searchParams.get('sla')).toBeNull()

    expect(errors).toEqual([])
  })

  test('a facet in a pasted URL comes back pressed; an unknown one is dropped', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    await page.goto('/ops?status=OPEN&page=0&size=25')
    await expect(page.locator('[data-kpi="open"]')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('active-filter-chip')).toHaveText(['Status: Open'])

    await page.goto('/ops?sla=SOMEDAY&page=0&size=25')
    await expect(page.getByTestId('incident-row').first()).toBeVisible()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
    await expect(page.locator('[data-kpi="breached"]')).toHaveAttribute('aria-pressed', 'false')
  })

  test('every status tile equals the table total it filters, alone and combined', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await expect(page.getByTestId('incident-row').first()).toBeVisible()
    await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)

    const tile = (key: string) => page.locator(`[data-kpi="${key}"]`)
    const chips = page.getByTestId('active-filter-chip')
    const keys = ['open', 'closed', 'breached', 'at-risk'] as const
    const base = Object.fromEntries(
      await Promise.all(keys.map(async (k) => [k, await tileValue(page, k)] as const)),
    ) as Record<(typeof keys)[number], number>
    const unfiltered = await tableTotal(page)

    // With no facet, Open and Closed split the whole population (Backlog's replacement, US4).
    expect(base.open + base.closed).toBe(unfiltered)

    // Each tile alone: its number is the filtered table total and does not move; then it clears.
    for (const key of keys) {
      const total = await press(page, tile(key), key, true)
      expect(total, `${key} tile vs table total`).toBe(base[key])
      await expect(chips).toHaveCount(1)
      await press(page, tile(key), key, false)
      await expect(chips).toHaveCount(0)
      await expect.poll(() => tableTotal(page)).toBe(unfiltered)
    }

    // Open + Breached: both active tiles equal the table; Open and Closed now split Breached.
    await press(page, tile('open'), 'open', true)
    const both = await press(page, tile('breached'), 'breached', true)
    expect(await tileValue(page, 'open')).toBe(both)
    expect((await tileValue(page, 'open')) + (await tileValue(page, 'closed'))).toBe(base.breached)
    await expect(chips).toHaveCount(2)

    // Swap to At Risk within Open: At Risk is open-only, so Open ∧ At Risk is all of At Risk.
    const risk = await press(page, tile('at-risk'), 'at-risk', true)
    expect(risk).toBe(base['at-risk'])
    await expect(tile('breached')).toHaveAttribute('aria-pressed', 'false')

    // Closed ∧ At Risk is empty by definition — a closed ticket is never at risk.
    const closedRisk = await press(page, tile('closed'), 'at-risk', true)
    expect(closedRisk).toBe(0)
    await expect(page.getByTestId('incident-row')).toHaveCount(0)
    await shot(page, '18-ops-facet-closed-at-risk-empty')

    expect(errors).toEqual([])
  })

  test('the At Risk ⓘ opens by keyboard, shows live cushions, and Esc returns focus', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    const hint = page
      .getByTestId('kpi-tile')
      .filter({ has: page.locator('[data-kpi="at-risk"]') })
      .getByTestId('info-hint')
    await hint.focus()
    await page.keyboard.press('Enter')

    const panel = page.getByTestId('info-hint-panel')
    await expect(panel).toBeVisible()
    await expect(hint).toHaveAttribute('aria-expanded', 'true')
    await expect(panel.getByTestId('info-hint-meaning')).not.toBeEmpty()
    // The cushions V9 seeds into CFG_SLA_POLICY, served by /api/metrics/definitions.
    for (const cushion of ['Critical 1', 'High 2', 'Medium 3', 'Low 7']) {
      await expect(panel.getByTestId('info-hint-formula')).toContainText(cushion)
    }
    // Let the Popover's fade-in finish so the evidence shows the panel as a reader sees it.
    await expect(page.locator('.p-popover')).toHaveCSS('opacity', '1')
    await shot(page, '17-ops-at-risk-info-hint')

    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    await expect(hint).toBeFocused()

    // The same explanation in Indonesian after switching language.
    await page.getByTestId('language-switch').getByTestId('language-option-id').click()
    await expect(page.locator('html')).toHaveAttribute('lang', 'id')
    await hint.click()
    await expect(page.getByTestId('info-hint-panel')).toContainText('Terbuka')
    await expect(page.getByTestId('info-hint-formula')).toContainText('Critical 1')
    await page.keyboard.press('Escape')
    expect(errors).toEqual([])
  })
})
