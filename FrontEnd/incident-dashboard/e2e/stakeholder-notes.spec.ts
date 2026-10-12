import type { Page } from '@playwright/test'

import {
  ACCOUNTS,
  API_BASE_URL,
  REDMINE_BASE_URL,
  login,
  shot,
  trackPageErrors,
} from './fixtures/seed'
import { expect, test } from './fixtures/test'

/**
 * Stakeholder notes, phase 1 — the end-to-end walk over what 14 Sep asked for, against the real
 * isolated stack: no Backlog anywhere, tiles that filter, the Redmine number, an explanation on
 * every component, English and Indonesian, and the layout holding at phone and desktop widths.
 *
 * `ops-facets.spec.ts` owns the tile/table arithmetic; this file owns everything around it.
 */

/** i18n keys leaking into the page instead of their text, e.g. "kpi.atRisk". */
const RAW_KEY =
  /\b(common|nav|kpi|filters|table|drawer|charts|wallboard|auth|admin|errors|definitions)\.[a-z][A-Za-z]*(\.[A-Za-z]+)*\b/

/** Backend freshness codes that must reach the reader as sentences. */
const RAW_STALE_CODE = /\b(etl-never-run|etl-stalled|mirror-lagging)\b/

async function switchTo(page: Page, locale: 'en' | 'id') {
  await page.getByTestId('language-switch').getByTestId(`language-option-${locale}`).click()
  await expect(page.locator('html')).toHaveAttribute('lang', locale)
}

async function openOps(page: Page) {
  await login(page, ACCOUNTS.ops.username)
  await page.waitForURL('**/ops**')
  await expect(page.getByTestId('incident-row').first()).toBeVisible()
  await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
}

async function noHorizontalScroll(page: Page) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth, 'page body scrolls sideways').toBeLessThanOrEqual(clientWidth)
}

test.describe('stakeholder notes — ops', () => {
  test('desktop: no Backlog, the matrix fits its card, the trend is Created / Closed', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await openOps(page)

    await expect(page.locator('[data-kpi="backlog"]')).toHaveCount(0)
    await expect(page.getByTestId('kpi-tile')).not.toContainText([/backlog/i])
    for (const key of ['open', 'closed', 'breached', 'at-risk']) {
      await expect(page.locator(`[data-kpi="${key}"]`)).toHaveAttribute('aria-pressed', 'false')
    }

    // The Impact × Urgency grid is not clipped behind its own scroll container at desktop width.
    const matrix = page.getByTestId('impact-urgency-heatmap').locator('.overflow-x-auto')
    const fit = await matrix.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)
    expect(fit, 'matrix columns clipped at 1440px').toBe(true)

    const trend = page.getByTestId('created-closed-trend')
    await expect(trend.getByRole('heading', { level: 2 })).toHaveText('Created / Closed')
    await expect(trend).not.toContainText(/backlog/i)
    await expect(page.getByTestId('created-closed-trend-table').locator('thead th')).toHaveText([
      'Period',
      'Created',
      'Closed',
    ])

    await noHorizontalScroll(page)
    await shot(page, '40-stakeholder-ops-desktop')
    expect(errors).toEqual([])
  })

  test('every ⓘ on Ops explains itself — English, then Indonesian — and closes with Esc', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await openOps(page)

    const hints = page.getByTestId('info-hint')
    const count = await hints.count()
    // 7 KPI tiles + 3 charts + freshness + 3 table headers (No Redmine, SLA, Aging).
    expect(count).toBeGreaterThanOrEqual(14)

    for (const locale of ['en', 'id'] as const) {
      if (locale === 'id') await switchTo(page, 'id')
      for (let i = 0; i < count; i++) {
        const hint = hints.nth(i)
        const term = await hint.getAttribute('data-term')
        await hint.scrollIntoViewIfNeeded()
        await hint.click()

        const panel = page.getByTestId('info-hint-panel')
        await expect(panel, `${locale} ${term}`).toBeVisible()
        for (const part of ['info-hint-meaning', 'info-hint-formula', 'info-hint-source']) {
          await expect(panel.getByTestId(part), `${locale} ${term} ${part}`).not.toBeEmpty()
        }
        const text = await panel.innerText()
        expect(text, `${locale} ${term} leaks an i18n key`).not.toMatch(RAW_KEY)
        expect(text, `${locale} ${term} leaks a placeholder`).not.toMatch(/\{[a-zA-Z]+\}/)
        // The popover stays inside the viewport — nothing to scroll to find the end of a sentence.
        const box = await page.locator('.p-popover').boundingBox()
        expect(box!.x).toBeGreaterThanOrEqual(0)
        expect(box!.x + box!.width).toBeLessThanOrEqual(1440)

        await page.keyboard.press('Escape')
        // The panel leaves via a transition; wait for the trigger to report closed, then
        // assert the panel is really gone (same assertion, longer budget under load).
        await expect(hint).toHaveAttribute('aria-expanded', 'false')
        await expect(panel, `${locale} ${term} stays open after Esc`).toBeHidden({
          timeout: 15_000,
        })
      }
    }
    expect(errors).toEqual([])
  })

  test('Indonesian: chrome is translated, Redmine data is not, and no key or code leaks', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await openOps(page)
    const statusCell = await page
      .getByTestId('incident-row')
      .first()
      .locator('td')
      .nth(3)
      .innerText()

    await switchTo(page, 'id')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Insiden')
    await expect(page.locator('[data-kpi="open"]')).toContainText('Terbuka')
    await expect(page.locator('[data-kpi="closed"]')).toContainText('Ditutup')
    await expect(page.locator('[data-kpi="breached"]')).toContainText('Melewati SLA')
    await expect(page.locator('[data-kpi="at-risk"]')).toContainText('Berisiko')
    await expect(page.getByTestId('col-no-redmine')).toContainText('No Redmine')
    await expect(page.getByTestId('created-closed-trend').getByRole('heading')).toHaveText(
      'Dibuat / Ditutup',
    )
    // Redmine's own status names are data, shown exactly as Redmine stores them.
    await expect(page.getByTestId('incident-row').first().locator('td').nth(3)).toHaveText(
      statusCell,
    )

    const body = await page.locator('body').innerText()
    expect(body).not.toMatch(RAW_KEY)
    expect(body).not.toMatch(RAW_STALE_CODE)

    // Facet chips speak the UI language too.
    await page.locator('[data-kpi="breached"]').click()
    await expect(page.getByTestId('active-filter-chip')).toHaveText(['SLA: Melewati SLA'])

    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('lang', 'id')
    await expect(page.locator('[data-kpi="breached"]')).toHaveAttribute('aria-pressed', 'true')
    await shot(page, '41-stakeholder-ops-indonesian')
    expect(errors).toEqual([])
  })

  test('export carries the active facets and produces a workbook', async ({ page }) => {
    const errors = trackPageErrors(page)
    await openOps(page)
    await page.locator('[data-kpi="breached"]').click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(1)

    const exportResponse = page.waitForResponse((r) =>
      r.url().startsWith(`${API_BASE_URL}/incidents/export`),
    )
    const download = page.waitForEvent('download')
    await page.getByTestId('export-button').click()

    const response = await exportResponse
    expect(response.status()).toBe(200)
    expect(new URL(response.url()).searchParams.get('sla')).toBe('BREACHED')
    const body = (await response.json()) as {
      rowCount: number
      content: { SLA_BREACHED: number; SLA_BUCKET: string }[]
    }
    expect(body.rowCount).toBeGreaterThan(0)
    expect(body.content.every((row) => row.SLA_BUCKET === 'Breached')).toBe(true)

    const file = await download
    expect(file.suggestedFilename()).toMatch(/\.xlsx$/)
    expect(errors).toEqual([])
  })

  test('the drawer from a filtered table deep-links the same Redmine ticket', async ({ page }) => {
    const errors = trackPageErrors(page)
    await openOps(page)
    // "At risk" is time-relative: as the fixture ages, every open ticket slides into Breached and
    // the At Risk tile reads 0 (an empty table has no row to link). Filter by whichever of the
    // two urgent tiles has tickets today — the property under test is the filtered deep link.
    const atRisk = Number(
      (
        await page
          .getByTestId('kpi-tile')
          .filter({ has: page.locator('[data-kpi="at-risk"]') })
          .getByTestId('kpi-value')
          .innerText()
      ).replace(/\D/g, ''),
    )
    await page.locator(`[data-kpi="${atRisk > 0 ? 'at-risk' : 'breached'}"]`).click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(1)

    const row = page.getByTestId('incident-row').first()
    const number = (await row.getByTestId('incident-redmine-link').innerText()).replace(/\D/g, '')
    await expect(row.getByTestId('incident-redmine-link')).toHaveAttribute(
      'href',
      `${REDMINE_BASE_URL}/issues/${number}`,
    )

    await row.getByTestId('incident-row-select').click()
    const header = page.getByRole('dialog').getByTestId('drawer-redmine-number')
    await expect(header).toHaveText(`#${number}`)
    await expect(header).toHaveAttribute('href', `${REDMINE_BASE_URL}/issues/${number}`)
    await expect(header).toHaveAttribute('target', '_blank')
    await expect(header).toHaveAttribute('rel', 'noopener noreferrer')

    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeHidden()
    expect(errors).toEqual([])
  })

  test('phone: tiles filter, ticket numbers show, targets are thumb-sized, no sideways scroll', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 360, height: 800 })
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await expect(page.getByTestId('incident-card').first()).toBeVisible()
    await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)

    await expect(page.getByTestId('incident-card-number').first()).toHaveText(/^#\d+$/)
    await noHorizontalScroll(page)

    const breached = page.locator('[data-kpi="breached"]')
    await breached.click()
    await expect(breached).toHaveAttribute('aria-pressed', 'true')
    const tileValue = Number(
      (
        await page
          .getByTestId('kpi-tile')
          .filter({ has: breached })
          .getByTestId('kpi-value')
          .innerText()
      ).replace(/\D/g, ''),
    )
    await expect
      .poll(async () =>
        Number((await page.getByTestId('incident-count').innerText()).replace(/\D/g, '')),
      )
      .toBe(tileValue)

    // Every tile label ends before its icon cluster, and the decorative tone icon yields.
    await expect(page.getByTestId('kpi-tone-icon').first()).toBeHidden()
    for (const tile of await page.getByTestId('kpi-tile').all()) {
      const label = tile.locator('span.uppercase').first()
      const [l, h] = [await label.boundingBox(), await tile.getByTestId('info-hint').boundingBox()]
      // The ⓘ's 44px hit area overhangs by 12px (-m-3); its glyph starts 12px in.
      expect(l!.x + l!.width, `${await label.innerText()} runs under its ⓘ`).toBeLessThanOrEqual(
        h!.x + 12,
      )
      expect(l!.height, `${await label.innerText()} wraps`).toBeLessThan(40)
    }

    // Operational controls meet the 44px touch target.
    for (const id of ['export-button', 'filter-advanced-toggle', 'filter-clear-all']) {
      const box = await page.getByTestId(id).boundingBox()
      expect(box!.height, `${id} height`).toBeGreaterThanOrEqual(44)
    }
    for (const box of await Promise.all(
      (await page.getByTestId('info-hint').all()).map((h) => h.boundingBox()),
    )) {
      if (box) expect(box.height).toBeGreaterThanOrEqual(44)
    }

    await noHorizontalScroll(page)
    await shot(page, '42-stakeholder-ops-phone-breached')
    expect(errors).toEqual([])
  })

  test('a machine set to dark still renders the light dashboard legibly', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' })
    await openOps(page)
    const colours = await page.evaluate(() => ({
      body: getComputedStyle(document.body).backgroundColor,
      tile: getComputedStyle(document.querySelector('[data-testid="kpi-tile"]')!).backgroundColor,
    }))
    expect(colours.body).not.toBe('rgb(0, 0, 0)')
    expect(colours.tile).toBe('rgb(255, 255, 255)')
  })
})

test.describe('stakeholder notes — exec and wallboard', () => {
  test('exec: Open / Closed / Breached / At Risk in both languages, never Backlog', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.exec.username)
    await page.waitForURL('**/exec**')
    await expect(page.getByTestId('exec-headline-tiles')).toBeVisible()

    for (const key of ['open', 'closed', 'breached', 'at-risk']) {
      await expect(page.getByTestId(`exec-tile-${key}`)).toBeVisible()
    }
    await expect(page.getByTestId('exec-tile-backlog')).toHaveCount(0)
    await expect(page.getByTestId('exec-headline-tiles').getByTestId('info-hint')).not.toHaveCount(
      0,
    )

    await switchTo(page, 'id')
    await expect(page.getByTestId('exec-tile-closed')).toContainText('Ditutup')
    await expect(page.getByTestId('exec-tile-at-risk')).toContainText('Berisiko')
    expect(await page.locator('body').innerText()).not.toMatch(RAW_KEY)
    await shot(page, '43-stakeholder-exec-indonesian')
    expect(errors).toEqual([])
  })

  test('wallboard: incident tiles per screen, ⓘ off in TV mode, stale reason in words', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 1920, height: 1080 })
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')

    // Screen 1 carries breached / at-risk / open; screen 2 carries resolved / open.
    for (const key of ['breached', 'at-risk', 'open']) {
      await expect(page.getByTestId(`wallboard-${key}`)).toBeVisible()
    }
    await expect(page.getByTestId('wallboard-backlog')).toHaveCount(0)
    await expect(page.getByTestId('info-hint')).not.toHaveCount(0)
    await page.getByTestId('wallboard-rotation-next').click()
    for (const key of ['resolved', 'open']) {
      await expect(page.getByTestId(`wallboard-${key}`)).toBeVisible()
    }
    await expect(page.getByTestId('wallboard-backlog')).toHaveCount(0)

    await page.goto('/wallboard?tv=1')
    await expect(page.getByTestId('wallboard-open')).toBeVisible()
    await expect(page.getByTestId('wallboard-rotation-controls')).toHaveCount(0)
    await expect(page.getByTestId('info-hint')).toHaveCount(0)
    await noHorizontalScroll(page)

    await switchTo(page, 'id')
    await expect(page.getByTestId('wallboard-at-risk')).toContainText(/berisiko/i)
    const text = await page.locator('body').innerText()
    expect(text).not.toMatch(RAW_KEY)
    expect(text).not.toMatch(RAW_STALE_CODE)
    await shot(page, '44-stakeholder-wallboard-tv-indonesian')
    expect(errors).toEqual([])
  })
})
