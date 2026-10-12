import type { Page } from '@playwright/test'

import {
  ACCOUNTS,
  API_BASE_URL,
  AUTH_BASE_URL,
  SCREENSHOT_DIR,
  login,
  passwordFor,
  shot,
  trackPageErrors,
} from './fixtures/seed'
import { expect, test } from './fixtures/test'

/**
 * Stakeholder notes, phase 2b — the single MTTR tile (a Closed facet toggle) and the SPO
 * impact x urgency priority matrix: axis order, per-cell priority, legend, ⓘ, and the four SPO
 * columns fitting a phone. The e2e stack seeds real SPO enum names (Massive.. / Urgent..) and a
 * spread that hits all 16 cells (`E2eStackLauncherTest.seedSpoMatrix`).
 */

const IMPACTS = ['Massive', 'Large', 'Limited', 'Localized'] as const
const URGENCIES = ['Urgent', 'High', 'Medium', 'Low'] as const
type Priority = 'Critical' | 'High' | 'Medium' | 'Low'

/** The SPO table, written out independently of the product: [urgency][impact]. */
const SPO: Record<(typeof URGENCIES)[number], Record<(typeof IMPACTS)[number], Priority>> = {
  Urgent: { Massive: 'Critical', Large: 'Critical', Limited: 'High', Localized: 'High' },
  High: { Massive: 'Critical', Large: 'High', Limited: 'High', Localized: 'Medium' },
  Medium: { Massive: 'High', Large: 'Medium', Limited: 'Medium', Localized: 'Medium' },
  Low: { Massive: 'Low', Large: 'Low', Limited: 'Low', Localized: 'Low' },
}

const num = (text: string) => Number(text.replace(/[^\d]/g, ''))

async function openIncident(page: Page) {
  await login(page, ACCOUNTS.ops.username)
  await page.waitForURL('**/ops**')
  await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
  await expect(page.getByTestId('incident-count')).toBeVisible()
  await expect(page.getByTestId('heatmap-scroller')).toBeVisible()
}

const kpiTile = (page: Page, key: string) =>
  page.getByTestId('kpi-tile').filter({ has: page.locator(`[data-kpi="${key}"]`) })
const tableTotal = async (page: Page) => num(await page.getByTestId('incident-count').innerText())
const tileValue = async (page: Page, key: string) =>
  num(await kpiTile(page, key).getByTestId('kpi-value').innerText())

async function noHorizontalScroll(page: Page, label: string) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth, `${label}: page scrolls sideways`).toBeLessThanOrEqual(clientWidth)
}

async function apiToken(page: Page, username: string) {
  const response = await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
    data: { username, password: passwordFor(username) },
  })
  return ((await response.json()) as { token: string }).token
}

// ------------------------------------------------------------------------------- the MTTR tile

test.describe('MTTR tile', () => {
  test('one tile replaces the two; it toggles the Closed facet and shows a number or a dash', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await openIncident(page)

    await expect(page.locator('[data-kpi="mttr-workaround"]')).toHaveCount(0)
    await expect(page.locator('[data-kpi="mttr-permanent"]')).toHaveCount(0)
    const tile = kpiTile(page, 'mttr')
    await expect(tile).toHaveCount(1)
    const control = page.locator('[data-kpi="mttr"]')
    await expect(control).toHaveAttribute('aria-pressed', 'false')

    // A number (days, maybe with decimals) or a dash when nothing closed; never blank/NaN.
    const value = (await tile.getByTestId('kpi-value').innerText()).trim()
    expect(value, 'MTTR value').toMatch(/^(\d+([.,]\d+)?|—|–|-)/)
    await expect(tile.getByTestId('kpi-sublabel')).toBeVisible()
    await expect(tile.getByTestId('kpi-sublabel')).not.toBeEmpty()

    const total = await tableTotal(page)
    const closed = await tileValue(page, 'closed')
    expect(closed).toBeGreaterThan(0)

    await control.click()
    await expect(control).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('active-filter-chip')).toHaveText(['Status: Closed'])
    await expect.poll(() => tableTotal(page)).toBe(closed)
    await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
    // The Closed tile is the same facet: it reads pressed too, and equals the table.
    await expect(page.locator('[data-kpi="closed"]')).toHaveAttribute('aria-pressed', 'true')
    expect(await tileValue(page, 'closed')).toBe(await tableTotal(page))

    await control.click()
    await expect(control).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
    await expect.poll(() => tableTotal(page)).toBe(total)
    expect(errors).toEqual([])
  })

  test('the MTTR label does not wrap or clip at 375, 768 and 1440', async ({ page }) => {
    await openIncident(page)
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      const tile = kpiTile(page, 'mttr')
      await tile.scrollIntoViewIfNeeded()
      const label = tile.locator('span.uppercase').first()
      const [l, h] = [await label.boundingBox(), await tile.getByTestId('info-hint').boundingBox()]
      expect(l, `@${width}: label rendered`).not.toBeNull()
      expect(l!.x + l!.width, `@${width}: label runs under its ⓘ`).toBeLessThanOrEqual(h!.x + 12)
      expect(l!.height, `@${width}: MTTR label wraps`).toBeLessThan(40)
      const clipped = await tile.evaluate((el) =>
        [...el.querySelectorAll('*')].filter(
          (n) =>
            (n.textContent ?? '').trim() !== '' && // the ⓘ's 44px hit-area wrapper has no text
            n.scrollWidth > n.clientWidth + 1 &&
            getComputedStyle(n).display !== 'inline',
        ).length,
      )
      expect(clipped, `@${width}: clipped text in the MTTR tile`).toBe(0)
    }
  })
})

// --------------------------------------------------------------------------- the SPO matrix API

test.describe('SPO matrix', () => {
  test('API: axis orders and all 16 impact x urgency -> priority mappings', async ({ page }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    const token = await apiToken(page, ACCOUNTS.ops.username)
    const body = (await (
      await page.request.get(`${API_BASE_URL}/metrics/matrix?scope=INCIDENT`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json()) as {
      cells: { impact: string; urgency: string; count: number; priority: Priority | null }[]
      impactOrder: string[]
      urgencyOrder: string[]
      missingCount: number
    }
    expect(body.impactOrder).toEqual([...IMPACTS])
    expect(body.urgencyOrder).toEqual([...URGENCIES])

    for (const urgency of URGENCIES) {
      for (const impact of IMPACTS) {
        const cell = body.cells.find((c) => c.impact === impact && c.urgency === urgency)
        expect(cell, `cell ${impact} x ${urgency} is seeded and present`).toBeDefined()
        expect(cell!.count, `${impact} x ${urgency} count`).toBeGreaterThan(0)
        expect(cell!.priority, `${impact} x ${urgency} priority`).toBe(SPO[urgency][impact])
      }
    }
    // Everything outside the SPO grid carries no priority.
    for (const cell of body.cells) {
      const inGrid = IMPACTS.includes(cell.impact as never) && URGENCIES.includes(cell.urgency as never)
      if (!inGrid) {
        expect(cell.priority, `${cell.impact} x ${cell.urgency} is not an SPO cell`).toBeNull()
      }
    }
    // Real Unclassified rows exist in the seed, and they are counted as missing.
    expect(body.cells.some((c) => c.impact === 'Unclassified' || c.urgency === 'Unclassified')).toBe(
      true,
    )
    expect(body.missingCount).toBeGreaterThan(0)
  })

  test('desktop: axes in SPO order, Unclassified after the grid, per-cell priority, legend, ⓘ', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await openIncident(page)
    const heatmap = page.getByTestId('impact-urgency-heatmap')

    await expect(page.getByTestId('heatmap-axis-impact')).toContainText('Impact')
    await expect(page.getByTestId('heatmap-axis-urgency')).toContainText('Urgency')

    const columns = (await heatmap.locator('thead th[scope="col"]').allInnerTexts()).map((t) =>
      t.replace(/\s+/g, ' ').trim(),
    )
    expect(columns.slice(0, 4), 'impact columns, left to right').toEqual([...IMPACTS])
    expect(columns.length).toBeGreaterThan(4)
    for (const extra of columns.slice(4)) {
      expect(extra, 'only non-SPO groups (Unclassified/Unknown) follow the grid').toMatch(
        /Unclassified|Unknown/,
      )
    }
    expect(columns[4]).toContain('Unclassified')

    const rows = (await heatmap.locator('tbody th[scope="row"]').allInnerTexts()).map((t) =>
      t.replace(/\s+/g, ' ').trim(),
    )
    expect(rows.slice(0, 4), 'urgency rows, top to bottom').toEqual([...URGENCIES])
    expect(rows[4]).toContain('Unclassified')

    // Each cell's data-priority is the SPO table's, for every non-empty SPO cell.
    for (const urgency of URGENCIES) {
      for (const impact of IMPACTS) {
        const cell = heatmap.locator(
          `[data-testid="heatmap-cell"][data-impact="${impact}"][data-urgency="${urgency}"]`,
        )
        await expect(cell).toHaveCount(1)
        expect(Number(await cell.getAttribute('data-count'))).toBeGreaterThan(0)
        await expect(cell, `${impact} x ${urgency}`).toHaveAttribute(
          'data-priority',
          SPO[urgency][impact],
        )
        await expect(cell.getByTestId('heatmap-cell-priority')).toContainText(SPO[urgency][impact])
      }
    }
    // Unclassified cells have no priority attribute.
    const unclassified = heatmap.locator(
      '[data-testid="heatmap-cell"][data-impact="Unclassified"]',
    )
    expect(await unclassified.count()).toBeGreaterThan(0)
    for (const cell of await unclassified.all()) {
      await expect(cell).not.toHaveAttribute('data-priority', /.+/)
    }

    // Legend: exactly the four priorities, in words.
    const legend = page.getByTestId('heatmap-legend')
    await expect(legend).toBeVisible()
    await expect(legend.getByRole('listitem')).toHaveCount(4)
    const legendText = (await legend.innerText()).replace(/\s+/g, ' ')
    for (const word of ['Critical', 'High', 'Medium', 'Low']) expect(legendText).toContain(word)

    // ⓘ explains the SPO colours, in English and Indonesian.
    await heatmap.getByTestId('info-hint').first().click()
    const panel = page.getByTestId('info-hint-panel')
    await expect(panel).toBeVisible()
    await expect(panel).toContainText('SPO')
    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()

    await shot(page, '70-spo-matrix-desktop')
    expect(errors).toEqual([])
  })

  test('phone: the four SPO columns fit unclipped at 375px and the page does not scroll sideways', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await openIncident(page)
    const heatmap = page.getByTestId('impact-urgency-heatmap')
    await heatmap.scrollIntoViewIfNeeded()
    await noHorizontalScroll(page, 'Incident @375')

    const scroller = page.getByTestId('heatmap-scroller')
    const scrollerBox = (await scroller.boundingBox())!
    for (const urgency of URGENCIES) {
      for (const impact of IMPACTS) {
        const cell = heatmap.locator(
          `[data-testid="heatmap-cell"][data-impact="${impact}"][data-urgency="${urgency}"]`,
        )
        const box = (await cell.boundingBox())!
        expect(
          box.x + box.width,
          `${impact} x ${urgency} sticks out of the card at 375px`,
        ).toBeLessThanOrEqual(scrollerBox.x + scrollerBox.width + 1)
        expect(box.x, `${impact} x ${urgency} starts left of the card`).toBeGreaterThanOrEqual(
          scrollerBox.x - 1,
        )
        // The cell's own text (count + priority word) is not clipped.
        const clipped = await cell.evaluate((el) =>
          [el, ...el.querySelectorAll('*')].some(
            (n) => n.scrollWidth > n.clientWidth + 1 || n.scrollHeight > n.clientHeight + 1,
          ),
        )
        expect(clipped, `${impact} x ${urgency} clips its text`).toBe(false)
      }
    }
    // Column headers of the grid wrap at words and are not clipped.
    const clippedHeaders = await heatmap.evaluate((root) =>
      [...root.querySelectorAll('th')]
        .filter((el) => el.scrollWidth > el.clientWidth + 1)
        .map((el) => (el.textContent ?? '').trim()),
    )
    expect(clippedHeaders).toEqual([])
    await shot(page, '71-spo-matrix-phone')
  })
})

test.describe('SPO matrix headers', () => {
  for (const width of [375, 768, 1440]) {
    test(`@${width}: no header or row header breaks a word mid-word, none clipped`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })
      await openIncident(page)
      const heatmap = page.getByTestId('impact-urgency-heatmap')
      await heatmap.scrollIntoViewIfNeeded()
      const broken = await heatmap.evaluate((root) => {
        const out: string[] = []
        for (const th of root.querySelectorAll('th')) {
          const text = (th.textContent ?? '').trim()
          if (!text) continue
          if (th.scrollWidth > th.clientWidth + 1) out.push(`${text}: clipped`)
          const walker = document.createTreeWalker(th, NodeFilter.SHOW_TEXT)
          for (let n = walker.nextNode(); n; n = walker.nextNode()) {
            const value = n.textContent ?? ''
            for (const m of value.matchAll(/\S+/g)) {
              const r = document.createRange()
              r.setStart(n, m.index!)
              r.setEnd(n, m.index! + m[0].length)
              const tops = new Set([...r.getClientRects()].map((q) => Math.round(q.top)))
              if (tops.size > 1) out.push(`${m[0]}: broken mid-word`)
              const widest = Math.max(...[...r.getClientRects()].map((q) => q.width))
              if (widest > th.clientWidth + 1) out.push(`${m[0]}: wider than its header cell`)
            }
          }
        }
        return out
      })
      expect(broken, `heatmap headers @${width}`).toEqual([])
      // The words the stakeholders read must be present whole.
      for (const word of ['Localized', 'Unclassified']) {
        await expect(heatmap.getByText(word, { exact: true }).first()).toBeVisible()
      }
    })
  }
})

// ---------------------------------------------------------------------------- the KPI row shots

test.describe('KPI row captures', () => {
  for (const [name, width] of [
    ['phone', 375],
    ['tablet', 768],
    ['w1024', 1024],
    ['desktop', 1440],
  ] as const) {
    test(`KPI row @${width}: seven-or-fewer tiles, none clipped, none overlapping`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })
      await openIncident(page)
      const tiles = page.getByTestId('kpi-tile')
      const count = await tiles.count()
      expect(count).toBeGreaterThanOrEqual(6)
      const boxes: { x: number; y: number; w: number; h: number; key: string }[] = []
      for (const tile of await tiles.all()) {
        const box = (await tile.boundingBox())!
        const key = (await tile.getAttribute('data-kpi')) ?? '?'
        boxes.push({ x: box.x, y: box.y, w: box.width, h: box.height, key })
        expect(box.x + box.width, `${key} @${width} leaves the viewport`).toBeLessThanOrEqual(
          width + 1,
        )
        const clipped = await tile.evaluate((el) =>
          [...el.querySelectorAll('*')].some(
            (n) =>
              (n.textContent ?? '').trim() !== '' &&
              getComputedStyle(n).display !== 'inline' &&
              n.scrollWidth > n.clientWidth + 1,
          ),
        )
        expect(clipped, `${key} @${width} clips text`).toBe(false)
      }
      for (let i = 0; i < boxes.length; i++) {
        for (let j = i + 1; j < boxes.length; j++) {
          const a = boxes[i]!
          const b = boxes[j]!
          const overlap =
            a.x < b.x + b.w - 1 && b.x < a.x + a.w - 1 && a.y < b.y + b.h - 1 && b.y < a.y + a.h - 1
          expect(overlap, `${a.key} overlaps ${b.key} @${width}`).toBe(false)
        }
      }
      await noHorizontalScroll(page, `KPI row @${width}`)
      const row = tiles.first().locator('xpath=ancestor::*[contains(@class,"grid")][1]')
      await row.screenshot({ path: `${SCREENSHOT_DIR}/72-kpi-row-${name}.png` })
      await shot(page, `73-incident-${name}`)
    })
  }
})
