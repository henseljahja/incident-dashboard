import { existsSync } from 'node:fs'

import { expect, test, type BrowserContext, type Page } from '@playwright/test'

import { DEPLOYED_AUTH_STATE, DEPLOYED_BASE_URL } from '../playwright.deployed.config'

/**
 * Stakeholder notes, phase 1, walked on the DEPLOYED host with real data — read-only.
 *
 * Counts are never hardcoded: the host's data is not the fixture set, so every assertion is a
 * relation (tile == table total, open + closed == all) rather than a number.
 */

const RAW_KEY =
  /\b(common|nav|kpi|filters|table|drawer|charts|wallboard|auth|admin|errors|definitions)\.[a-z][A-Za-z]*(\.[A-Za-z]+)*\b/
const REDMINE = 'https://mtiredmine.yokke.co.id/issues/'

let context: BrowserContext
let page: Page
const pageErrors: string[] = []
const serverErrors: string[] = []

test.describe.configure({ mode: 'default' })

test.beforeAll(async ({ browser }) => {
  test.skip(!existsSync(DEPLOYED_AUTH_STATE), `no saved session at ${DEPLOYED_AUTH_STATE}`)
  context = await browser.newContext({
    storageState: DEPLOYED_AUTH_STATE,
    viewport: { width: 1440, height: 900 },
  })
  // Pin English for the run; the language test switches explicitly.
  await context.addInitScript(() => {
    try {
      window.localStorage.setItem('incident.locale', 'en')
    } catch {
      /* opaque origin */
    }
  })
  page = await context.newPage()
  page.on('pageerror', (e) => pageErrors.push(String(e)))
  page.on('console', (m) => {
    if (m.type() === 'error' && !/401|Failed to load resource/.test(m.text()))
      pageErrors.push(m.text())
  })
  page.on('response', (r) => {
    if (r.status() >= 500) serverErrors.push(`${r.status()} ${r.url()}`)
  })
})

test.afterAll(async () => {
  if (!context) return
  await context.storageState({ path: DEPLOYED_AUTH_STATE })
  await context.close()
})

const num = async (loc: ReturnType<Page['locator']>) =>
  Number((await loc.innerText()).replace(/\D/g, ''))
const tileValue = (key: string) => num(page.locator(`[data-kpi="${key}"]`).getByTestId('kpi-value'))
const tableTotal = () => num(page.getByTestId('incident-count'))

async function openOps() {
  await page.goto('/ops')
  await expect(page, 'session expired — re-save the storage state').not.toHaveURL(/\/login/)
  await expect(page).toHaveURL(/\/ops/)
  await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
  await expect(page.getByTestId('incident-row').first()).toBeVisible()
}

async function setLocale(locale: 'en' | 'id') {
  await page.getByTestId('language-switch').getByTestId(`language-option-${locale}`).click()
  await expect(page.locator('html')).toHaveAttribute('lang', locale)
}

test('ops: no Backlog, and Open + Closed equals every ticket in the table', async () => {
  await openOps()
  await expect(page.locator('[data-kpi="backlog"]')).toHaveCount(0)
  await expect(page.getByTestId('kpi-tile')).not.toContainText([/backlog/i])
  for (const key of ['open', 'closed', 'breached', 'at-risk']) {
    await expect(page.locator(`[data-kpi="${key}"]`)).toBeVisible()
  }
  const [open, closed] = [await tileValue('open'), await tileValue('closed')]
  await expect.poll(tableTotal).toBe(open + closed)
})

test('ops: every status tile equals the table total it filters, alone and combined', async () => {
  await openOps()
  for (const key of ['open', 'closed', 'breached', 'at-risk']) {
    const tile = page.locator(`[data-kpi="${key}"]`)
    await tile.click()
    await expect(tile).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(1)
    await expect.poll(tableTotal, `${key} alone`).toBe(await tileValue(key))
    await tile.click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
  }
  for (const [status, sla] of [
    ['open', 'breached'],
    ['closed', 'breached'],
    ['open', 'at-risk'],
  ]) {
    await page.locator(`[data-kpi="${status}"]`).click()
    await page.locator(`[data-kpi="${sla}"]`).click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(2)
    // Each tile applies the OTHER facet, so with both pressed both tiles show the intersection.
    // Poll the relation, not a value read once: a tile read too early predates its own refetch.
    await expect
      .poll(async () => {
        const [t, a, b] = [await tableTotal(), await tileValue(sla), await tileValue(status)]
        return {
          slaTileMatchesTable: a === t,
          statusTileMatchesTable: b === t,
          table: t,
          slaTile: a,
          statusTile: b,
        }
      }, `${status} + ${sla}`)
      .toMatchObject({ slaTileMatchesTable: true, statusTileMatchesTable: true })
    // Removing a chip clears exactly that facet.
    await page.getByTestId('active-filter-chip').first().click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(1)
    await page.getByTestId('active-filter-chip').first().click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
  }
})

test('ops: every info icon explains itself in English and Indonesian', async () => {
  await openOps()
  const hints = page.getByTestId('info-hint')
  const count = await hints.count()
  expect(count).toBeGreaterThanOrEqual(14)
  for (const locale of ['en', 'id'] as const) {
    await setLocale(locale)
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
      expect(text, `${locale} ${term} raw key`).not.toMatch(RAW_KEY)
      expect(text, `${locale} ${term} placeholder`).not.toMatch(/\{[a-zA-Z]+\}/)
      if (term === 'atRisk') {
        // Cushions come from the deployed SLA policy, not the copy.
        expect(text, 'at-risk cushions').toMatch(/1\D+2\D+3\D+7|7\D+3\D+2\D+1/)
      }
      await page.keyboard.press('Escape')
      await expect(panel).toBeHidden()
    }
  }
  await setLocale('en')
})

test('ops: charts — Created / Closed trend without Backlog, matrix not clipped', async () => {
  await openOps()
  const trend = page.getByTestId('created-closed-trend')
  await expect(trend.getByRole('heading', { level: 2 })).toHaveText('Created / Closed')
  await expect(trend).not.toContainText(/backlog/i)
  await expect(page.getByTestId('created-closed-trend-table').locator('thead th')).toHaveText([
    'Period',
    'Created',
    'Closed',
  ])
  const matrix = page.getByTestId('impact-urgency-heatmap').locator('.overflow-x-auto')
  expect(await matrix.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await expect(page.getByTestId('sla-bucket-bars')).toBeVisible()
})

test('ops: the Redmine number links out, and the drawer header carries it', async () => {
  await openOps()
  const row = page.getByTestId('incident-row').first()
  const link = row.getByTestId('incident-redmine-link')
  const number = (await link.innerText()).replace(/\D/g, '')
  expect(number).not.toBe('')
  await expect(link).toHaveAttribute('href', `${REDMINE}${number}`)
  await row.getByTestId('incident-row-select').click()
  const header = page.getByRole('dialog').getByTestId('drawer-redmine-number')
  await expect(header).toHaveText(`#${number}`)
  await expect(header).toHaveAttribute('href', `${REDMINE}${number}`)
  await expect(header).toHaveAttribute('target', '_blank')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
})

test('ops: export carries the active facet and returns a workbook', async () => {
  await openOps()
  await page.locator('[data-kpi="breached"]').click()
  await expect(page.getByTestId('active-filter-chip')).toHaveCount(1)
  const response = page.waitForResponse((r) => r.url().includes('/incidents/export'))
  const download = page.waitForEvent('download')
  await page.getByTestId('export-button').click()
  const r = await response
  expect(r.status()).toBe(200)
  expect(new URL(r.url()).searchParams.get('sla')).toBe('BREACHED')
  // The endpoint returns rows as JSON; the browser builds the workbook from them.
  const body = (await r.json()) as { rowCount: number; content: { SLA_BUCKET: string }[] }
  expect(body.rowCount).toBe(await tileValue('breached'))
  expect(body.content.every((row) => row.SLA_BUCKET === 'Breached')).toBe(true)
  expect((await download).suggestedFilename()).toMatch(/\.xlsx$/)
  await page.locator('[data-kpi="breached"]').click()
})

test('language: Indonesian translates the chrome, keeps Redmine data, survives reload', async () => {
  await openOps()
  await setLocale('id')
  await expect(page.locator('[data-kpi="open"]')).toContainText('Terbuka')
  await expect(page.locator('[data-kpi="closed"]')).toContainText('Ditutup')
  await expect(page.locator('[data-kpi="breached"]')).toContainText('Melewati SLA')
  await expect(page.locator('[data-kpi="at-risk"]')).toContainText('Berisiko')
  await expect(
    page.getByTestId('created-closed-trend').getByRole('heading', { level: 2 }),
  ).toHaveText('Dibuat / Ditutup')
  expect(await page.locator('body').innerText()).not.toMatch(RAW_KEY)
  // The init script pins `en` on every load, so prove persistence against storage directly.
  expect(await page.evaluate(() => localStorage.getItem('incident.locale'))).toBe('id')
  await setLocale('en')
})

test('exec: headline tiles without Backlog, both languages', async () => {
  await page.goto('/exec')
  await expect(page.getByTestId('exec-headline-tiles')).toBeVisible()
  await expect(page.getByTestId('exec-tile-backlog')).toHaveCount(0)
  for (const key of ['closed', 'at-risk'])
    await expect(page.getByTestId(`exec-tile-${key}`)).toBeVisible()
  await setLocale('id')
  await expect(page.getByTestId('exec-tile-closed')).toContainText('Ditutup')
  expect(await page.locator('body').innerText()).not.toMatch(RAW_KEY)
  await setLocale('en')
})

test('wallboard: no Backlog, info icons hidden in TV mode', async () => {
  await page.goto('/wallboard')
  await expect(page.getByTestId('wallboard-open')).toBeVisible()
  await expect(page.getByTestId('wallboard-backlog')).toHaveCount(0)
  await page.goto('/wallboard?tv=1')
  await expect(page.getByTestId('wallboard-open')).toBeVisible()
  await expect(page.getByTestId('info-hint')).toHaveCount(0)
})

const SCOPE_PAGES = [
  { path: '/ops', scope: 'INCIDENT', menu: 'Incident' },
  { path: '/problem', scope: 'PROBLEM', menu: 'Problem' },
  { path: '/service-requests', scope: 'SERVICE_REQUEST', menu: 'Service Request' },
] as const

test('scopes: three menus, each page counts only its own tickets', async () => {
  await page.goto('/ops')
  for (const { menu } of SCOPE_PAGES) {
    await expect(page.getByRole('link', { name: menu, exact: true }).first()).toBeVisible()
  }
  const openByScope: Record<string, number> = {}
  for (const { path, scope } of SCOPE_PAGES) {
    await page.goto(path)
    await expect(page).toHaveURL(new RegExp(`${path}$`))
    await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
    const [open, closed] = [await tileValue('open'), await tileValue('closed')]
    await expect.poll(tableTotal, `${scope}: open + closed == table`).toBe(open + closed)
    openByScope[scope] = open
    // The matrix belongs to Incident only.
    await expect(page.getByTestId('impact-urgency-heatmap')).toHaveCount(scope === 'INCIDENT' ? 1 : 0)
    // "Clear all" or a hand-edited tracker must not widen the scope.
    await page.goto(`${path}?tracker=Incident&tracker=Problem`)
    await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
    const [o2, c2] = [await tileValue('open'), await tileValue('closed')]
    expect(o2 + c2, `${scope}: URL cannot widen the scope`).toBe(open + closed)
  }
  // The group tiles on a page agree with each page's own Open tile.
  await page.goto('/ops')
  for (const { scope } of SCOPE_PAGES) {
    const tile = page.getByTestId(`scope-tile-${scope}`)
    await expect(tile).toBeVisible()
    await expect(tile.getByTestId('scope-open')).toHaveText(String(openByScope[scope]))
  }
  await expect(page.getByTestId('scope-tile-INCIDENT')).toHaveAttribute('aria-current', 'page')
})

test('scopes: the N-missing link opens exactly those tickets', async () => {
  await openOps()
  const link = page.getByTestId('heatmap-missing-link')
  test.skip((await link.count()) === 0, 'no ticket on this host lacks impact/urgency')
  const missing = Number((await link.innerText()).replace(/\D/g, '').slice(0, 6))
  await link.click()
  await expect(page.getByTestId('active-filter-chip')).toHaveCount(1)
  await expect.poll(tableTotal).toBe(missing)
  await page.getByTestId('active-filter-chip').first().click()
})

test('wallboard: Incident only, two rotating screens, TV mode strips the controls', async () => {
  await page.goto('/wallboard')
  await expect(page.getByTestId('wallboard-total')).toBeVisible()
  const first = await page.getByTestId('wallboard-screen-title').innerText()
  await expect(page.getByTestId('wallboard-rotation-controls')).toBeVisible()
  await page.getByTestId('wallboard-rotation-next').click()
  await expect
    .poll(() => page.getByTestId('wallboard-screen-title').innerText())
    .not.toBe(first)
  await expect(page.getByTestId('wallboard-top-categories')).toBeVisible()
  await expect(page.getByTestId('wallboard-resolved')).toBeVisible()
  await page.goto('/wallboard?tv=1')
  await expect(page.getByTestId('wallboard-total')).toBeVisible()
  await expect(page.getByTestId('info-hint')).toHaveCount(0)
  await expect(page.getByTestId('wallboard-rotation-controls')).toHaveCount(0)
})

test('phone: no sideways scroll, tiles still filter', async () => {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/ops')
  await expect(page.getByTestId('incident-card').first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  await page.locator('[data-kpi="closed"]').click()
  await expect.poll(tableTotal).toBe(await tileValue('closed'))
  await page.locator('[data-kpi="closed"]').click()
  await page.setViewportSize({ width: 1440, height: 900 })
})

test('no page errors and no 5xx across the run', async () => {
  expect(pageErrors, 'page errors').toEqual([])
  expect(serverErrors, 'server errors').toEqual([])
  expect(DEPLOYED_BASE_URL).toMatch(/^http/)
})
