import type { Locator, Page } from '@playwright/test'

import {
  ACCOUNTS,
  API_BASE_URL,
  AUTH_BASE_URL,
  login,
  passwordFor,
  shot,
  trackPageErrors,
} from './fixtures/seed'
import { expect, test } from './fixtures/test'

/**
 * Stakeholder notes, phase 2 — three scoped menus (Incident / Problem / Service Request), the
 * per-group count tiles, the Incident-only matrix with its "N missing" link, and the rotating
 * two-screen wallboard. TM12 (menus + tile/table agreement), TM13 (wallboard), TM14 (count tiles
 * navigate), TM15 (responsive + keyboard + dark).
 *
 * Against the real isolated stack, nothing stubbed. The stack launcher clones a few mirror issues
 * under the Problem (41) and Service Request (16/32/33) trackers so every scope has rows
 * (`E2eStackLauncherTest.seedScopedTrackers`); assertions are relations (tile == table), never a
 * transcription of one fixture's arithmetic.
 */

const RAW_KEY =
  /\b(common|nav|kpi|filters|table|drawer|charts|wallboard|auth|admin|errors|definitions|scopeCounts)\.[a-z][A-Za-z]*(\.[A-Za-z]+)*\b/

type ScopeKey = 'INCIDENT' | 'PROBLEM' | 'SERVICE_REQUEST'

const PAGES: { scope: ScopeKey; path: string; menu: string; shotName: string }[] = [
  { scope: 'INCIDENT', path: '/ops', menu: 'Incident', shotName: 'incident' },
  { scope: 'PROBLEM', path: '/problem', menu: 'Problem', shotName: 'problem' },
  {
    scope: 'SERVICE_REQUEST',
    path: '/service-requests',
    menu: 'Service Request',
    shotName: 'service-request',
  },
]

const num = (text: string) => Number(text.replace(/[^\d]/g, ''))

/** Lands on a scoped page as the ops manager and waits for tiles, scope counts and table to settle. */
async function openScope(page: Page, path: string) {
  await login(page, ACCOUNTS.ops.username)
  await page.waitForURL('**/ops**')
  if (path !== '/ops') await page.goto(path)
  await settled(page)
}

async function settled(page: Page) {
  await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
  await expect(page.getByTestId('scope-skeleton')).toHaveCount(0)
  await expect(page.getByTestId('incident-count')).toBeVisible()
}

const kpiTile = (page: Page, key: string) =>
  page.getByTestId('kpi-tile').filter({ has: page.locator(`[data-kpi="${key}"]`) })

const tileValue = async (page: Page, key: string) =>
  num(await kpiTile(page, key).getByTestId('kpi-value').innerText())

const tableTotal = async (page: Page) => num(await page.getByTestId('incident-count').innerText())

async function noHorizontalScroll(page: Page, label: string) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth, `${label}: page scrolls sideways`).toBeLessThanOrEqual(clientWidth)
}

async function expectTarget(locator: Locator, label: string, min = 44) {
  const box = await locator.boundingBox()
  expect(box, `${label}: not rendered`).not.toBeNull()
  expect(box!.height, `${label}: target height`).toBeGreaterThanOrEqual(min - 0.5)
  expect(box!.width, `${label}: target width`).toBeGreaterThanOrEqual(min - 0.5)
}

/** Tab once (switching the browser into keyboard modality), focus the control, and read its ring. */
async function expectFocusRing(page: Page, locator: Locator, label: string) {
  await page.keyboard.press('Tab')
  await locator.focus()
  await expect(locator, `${label}: not focused`).toBeFocused()
  const ring = await locator.evaluate((el) => {
    const cs = getComputedStyle(el)
    return {
      outlineStyle: cs.outlineStyle,
      outlineWidth: parseFloat(cs.outlineWidth),
      boxShadow: cs.boxShadow,
      focusVisible: el.matches(':focus-visible'),
    }
  })
  expect(ring.focusVisible, `${label}: :focus-visible not matched`).toBe(true)
  const visible =
    (ring.outlineStyle !== 'none' && ring.outlineWidth > 0) ||
    (ring.boxShadow !== 'none' && ring.boxShadow !== '')
  expect(visible, `${label}: no visible focus ring (${JSON.stringify(ring)})`).toBe(true)
}

async function switchTo(page: Page, locale: 'en' | 'id') {
  await page.getByTestId('language-switch').getByTestId(`language-option-${locale}`).click()
  await expect(page.locator('html')).toHaveAttribute('lang', locale)
}

// =========================================================================================== TM12

test.describe('TM12 scoped menus', () => {
  test('the sidebar offers Incident, Problem and Service Request to ops; exec sees none of the new two', async ({
    page,
    browser,
  }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    const links = page.getByRole('navigation', { name: 'Main' }).getByRole('link')
    await expect(links).toHaveText(['Incident', 'Problem', 'Service Request'])
    await expect(links.nth(0)).toHaveAttribute('href', '/ops')
    await expect(links.nth(1)).toHaveAttribute('href', '/problem')
    await expect(links.nth(2)).toHaveAttribute('href', '/service-requests')
    await expect(links.nth(0)).toHaveAttribute('aria-current', 'page')

    await links.nth(1).click()
    await page.waitForURL('**/problem**')
    await expect(page.getByRole('heading', { level: 1, name: 'Problem' })).toBeVisible()
    await expect(links.nth(1)).toHaveAttribute('aria-current', 'page')
    await expect(links.nth(0)).not.toHaveAttribute('aria-current', 'page')
    await links.nth(2).click()
    await page.waitForURL('**/service-requests**')
    await expect(page.getByRole('heading', { level: 1, name: 'Service Request' })).toBeVisible()
    expect(errors).toEqual([])

    // EXEC holds none of the new menus (V10), so none reach its sidebar.
    const execContext = await browser.newContext()
    const execPage = await execContext.newPage()
    await execPage.addInitScript(() => localStorage.setItem('incident.locale', 'en'))
    await login(execPage, ACCOUNTS.exec.username)
    await execPage.waitForURL('**/exec**')
    const execNav = execPage.getByRole('navigation', { name: 'Main' })
    await expect(execNav.getByRole('link', { name: 'Problem' })).toHaveCount(0)
    await expect(execNav.getByRole('link', { name: 'Service Request' })).toHaveCount(0)
    await expect(execNav.getByRole('link', { name: 'Incident' })).toHaveCount(0)
    await execContext.close()
  })

  test('the admin sees all three menus too, and a role without a menu is refused by the API', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.admin.username)
    await page.waitForURL('**/ops**')
    const links = page.getByRole('navigation', { name: 'Main' }).getByRole('link')
    await expect(links.filter({ hasText: /^(Incident|Problem|Service Request)$/ })).toHaveCount(3)

    // The wallboard role holds no ops menu: a typed-in scoped URL is a 403 from the server.
    const token = (
      await (
        await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
          data: {
            username: ACCOUNTS.wallboard.username,
            password: passwordFor(ACCOUNTS.wallboard.username),
          },
        })
      ).json()
    ).token as string
    for (const scope of ['PROBLEM', 'SERVICE_REQUEST']) {
      const response = await page.request.get(`${API_BASE_URL}/incidents?scope=${scope}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      expect(response.status(), `wallboard role on scope=${scope}`).toBe(403)
    }
  })

  for (const scoped of PAGES) {
    test(`${scoped.menu}: Open + Closed == table total; each tile equals the table when pressed`, async ({
      page,
    }) => {
      const errors = trackPageErrors(page)
      const incidentsRequests: string[] = []
      page.on('request', (request) => {
        if (request.url().startsWith(`${API_BASE_URL}/incidents?`)) {
          incidentsRequests.push(request.url())
        }
      })
      await openScope(page, scoped.path)
      expect(new URL(page.url()).pathname).toBe(scoped.path)
      await expect(page.getByRole('heading', { level: 1, name: scoped.menu })).toBeVisible()

      // The scope is a SERVER param on every table request, and never in the address bar.
      expect(incidentsRequests.length).toBeGreaterThan(0)
      for (const url of incidentsRequests.slice(-1)) {
        expect(new URL(url).searchParams.get('scope'), url).toBe(scoped.scope)
      }
      expect(new URL(page.url()).searchParams.has('scope')).toBe(false)

      const total = await tableTotal(page)
      expect(total, `${scoped.menu} has rows in the seeded stack`).toBeGreaterThan(0)
      const open = await tileValue(page, 'open')
      const closed = await tileValue(page, 'closed')
      expect(open + closed, 'Open + Closed tiles == table total').toBe(total)
      await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)

      // Pressed, each tile narrows the table to exactly its own number.
      for (const [key, expected] of [
        ['open', open],
        ['closed', closed],
      ] as const) {
        if (expected === 0) continue
        const tile = kpiTile(page, key)
        await tile.click()
        await expect(tile.locator(`[data-kpi="${key}"]`)).toHaveAttribute('aria-pressed', 'true')
        await expect.poll(async () => tableTotal(page)).toBe(expected)
        expect(await tileValue(page, key), `${key} tile still equals its table`).toBe(expected)
        await page.getByTestId('active-filter-chip').click()
        await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
        await expect.poll(async () => tableTotal(page)).toBe(total)
      }

      // Matrix + "N missing" are Incident-only (D8).
      const heatmap = page.getByTestId('impact-urgency-heatmap')
      if (scoped.scope === 'INCIDENT') {
        await expect(heatmap).toBeVisible()
      } else {
        await expect(heatmap).toHaveCount(0)
        await expect(page.getByTestId('heatmap-missing-link')).toHaveCount(0)
      }
      await shot(page, `60-scope-${scoped.shotName}`)
      expect(errors).toEqual([])
    })
  }

  test('Incident: the "N missing" link opens a table whose count equals missingCount', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    const matrixResponse = page.waitForResponse(
      (r) => r.url().startsWith(`${API_BASE_URL}/metrics/matrix`) && r.ok(),
    )
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    const body = (await (await matrixResponse).json()) as {
      cells: { impact?: string; urgency?: string; count: number }[]
      missingCount: number
    }
    await settled(page)
    expect(Array.isArray(body.cells)).toBe(true)
    // Most seeded tickets carry no impact/urgency (they sit in the Unclassified/Unclassified
    // cell), so "N missing" must be > 0 and the link must render. PRODUCT BUG if it is 0: the mart
    // writes the literal 'Unclassified' for an absent enum value, not NULL, while the missing
    // predicate only tests `TRIM(col) IS NULL`.
    const unclassified = body.cells
      .filter((c) => c.impact === 'Unclassified' || c.urgency === 'Unclassified')
      .reduce((sum, c) => sum + c.count, 0)
    expect(unclassified, 'precondition: the stack has unclassified incidents').toBeGreaterThan(0)
    expect(
      body.missingCount,
      `${unclassified} incidents sit in an Unclassified matrix cell but missingCount is ${body.missingCount}`,
    ).toBeGreaterThan(0)

    const link = page.getByTestId('heatmap-missing-link')
    await expect(link).toBeVisible()
    await expect(link).toContainText(String(body.missingCount))
    const incidents = page.waitForResponse(
      (r) =>
        r.url().startsWith(`${API_BASE_URL}/incidents?`) &&
        new URL(r.url()).searchParams.get('matrixMissing') === 'true',
    )
    const kpi = page.waitForResponse(
      (r) =>
        r.url().startsWith(`${API_BASE_URL}/metrics/kpi`) &&
        new URL(r.url()).searchParams.get('matrixMissing') === 'true',
    )
    await link.click()
    const list = (await (await incidents).json()) as { totalElements: number }
    expect(list.totalElements, 'missing link narrows to exactly missingCount').toBe(
      body.missingCount,
    )
    await expect.poll(async () => tableTotal(page)).toBe(body.missingCount)
    await expect(page.getByTestId('active-filter-chip')).toContainText('Missing impact or urgency')
    await expect.poll(() => new URL(page.url()).searchParams.get('matrixMissing')).toBe('true')

    // With the chip active the KPI tiles are narrowed too: tiles == table.
    await kpi
    await expect(page.getByTestId('kpi-skeleton')).toHaveCount(0)
    await expect
      .poll(async () => (await tileValue(page, 'open')) + (await tileValue(page, 'closed')))
      .toBe(body.missingCount)
    await shot(page, '61-incident-missing-filter')

    // Remove the chip: back to the full table.
    await page.getByTestId('active-filter-chip').click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
    await expect.poll(async () => tableTotal(page)).toBeGreaterThan(body.missingCount)
    expect(errors).toEqual([])
  })

  test('Problem and SR keep their scope against Clear all and a hand-edited URL', async ({
    page,
  }) => {
    const requests: string[] = []
    page.on('request', (request) => {
      const url = request.url()
      if (url.startsWith(`${API_BASE_URL}/incidents?`) || url.startsWith(`${API_BASE_URL}/metrics/kpi`)) {
        // The login landing (/ops) fires its own, Incident-scoped requests before we navigate.
        if (page.url().includes('/problem')) requests.push(url)
      }
    })
    await openScope(page, '/problem')
    const total = await tableTotal(page)
    const open = await tileValue(page, 'open')
    expect(open).toBeGreaterThan(0)

    await kpiTile(page, 'open').click()
    await expect.poll(async () => tableTotal(page)).toBe(open)
    await page.getByTestId('filter-clear-all').click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
    // Every table/KPI request of this session carried the scope: the query cache may answer the
    // post-clear view without a new request, so assert on all of them, not the next one.
    expect(requests.length).toBeGreaterThan(0)
    for (const url of requests) {
      expect(new URL(url).searchParams.get('scope'), `${url} dropped the scope`).toBe('PROBLEM')
    }
    expect(new URL(page.url()).pathname).toBe('/problem')
    await expect.poll(async () => tableTotal(page)).toBe(total)

    // A hand-edited URL cannot widen the scope: `scope` in the query string is ignored.
    await page.goto('/problem?scope=INCIDENT')
    await settled(page)
    await expect.poll(async () => tableTotal(page)).toBe(total)
    await expect(page.getByRole('heading', { level: 1, name: 'Problem' })).toBeVisible()
  })
})

// =========================================================================================== TM14

test.describe('TM14 scope count tiles', () => {
  test('each tile navigates to its menu and its open count equals that page\'s Open tile', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await openScope(page, '/ops')
    await expect(page.getByTestId('scope-counts')).toBeVisible()
    await expect(page.getByTestId('scope-counts-list').getByRole('listitem')).toHaveCount(3)

    for (const start of PAGES) {
      await page.goto(start.path)
      await settled(page)
      // The current page's own tile is marked, the other two are not.
      for (const other of PAGES) {
        const tile = page.getByTestId(`scope-tile-${other.scope}`)
        if (other.scope === start.scope) await expect(tile).toHaveAttribute('aria-current', 'page')
        else await expect(tile).not.toHaveAttribute('aria-current', 'page')
      }

      for (const target of PAGES) {
        await page.goto(start.path)
        await settled(page)
        const tile = page.getByTestId(`scope-tile-${target.scope}`)
        const tileOpen = num(await tile.getByTestId('scope-open').innerText())
        await tile.click()
        await page.waitForURL(`**${target.path}**`)
        expect(new URL(page.url()).pathname).toBe(target.path)
        await expect(page.getByRole('heading', { level: 1, name: target.menu })).toBeVisible()
        await settled(page)
        expect(
          await tileValue(page, 'open'),
          `${start.menu} tile -> ${target.menu}: tile open count == page Open tile`,
        ).toBe(tileOpen)
        // ...and that page's own scope tile agrees with its Open tile.
        expect(
          num(
            await page
              .getByTestId(`scope-tile-${target.scope}`)
              .getByTestId('scope-open')
              .innerText(),
          ),
        ).toBe(tileOpen)
      }
    }
    expect(errors).toEqual([])
  })

  test('Clear all on Problem keeps Problem scope (counts tiles included)', async ({ page }) => {
    await openScope(page, '/problem')
    await kpiTile(page, 'open').click()
    await page.getByTestId('filter-clear-all').click()
    await expect(page.getByTestId('active-filter-chip')).toHaveCount(0)
    expect(new URL(page.url()).pathname).toBe('/problem')
    await expect(page.getByTestId('scope-tile-PROBLEM')).toHaveAttribute('aria-current', 'page')
    await expect(page.getByTestId('heatmap-missing-link')).toHaveCount(0)
  })
})

// =========================================================================================== TM13

test.describe('TM13 wallboard rotation', () => {
  test.use({ colorScheme: 'light' })

  interface Board {
    year: number
    total: number
    open: number
    closed: number
    breached: number
    atRisk: number
    groupCounts: { scope: string; open: number; closed: number; total: number }[]
  }

  test('Incident-only counts that match the Incident scope', async ({ page }) => {
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 1920, height: 1080 })
    const response = page.waitForResponse((r) =>
      r.url().startsWith(`${API_BASE_URL}/metrics/wallboard`),
    )
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')
    const board = (await (await response).json()) as Board

    const english = new Intl.NumberFormat('en')
    await expect(page.getByTestId('wallboard-screen-title')).toHaveText('Now')
    await expect(page.getByTestId('wallboard-total')).toContainText(english.format(board.total))

    const [incident, problem, sr] = board.groupCounts
    expect([incident?.scope, problem?.scope, sr?.scope]).toEqual([
      'INCIDENT',
      'PROBLEM',
      'SERVICE_REQUEST',
    ])
    expect(board.total, 'headline total is the Incident group, not every tracker').toBe(
      incident!.total,
    )
    expect(
      problem!.total + sr!.total,
      'the stack has Problem and SR tickets the wallboard must not count',
    ).toBeGreaterThan(0)
    expect(board.open).toBe(incident!.open)
    expect(board.open + board.closed).toBe(board.total)

    // The group tiles show all three scopes, as plain boxes — this role cannot open the menus.
    const group = page.getByTestId('wallboard-group-counts')
    await expect(group).toBeVisible()
    await expect(group.getByRole('link')).toHaveCount(0)
    for (const g of board.groupCounts) {
      await expect(group.getByTestId(`scope-tile-${g.scope}`).getByTestId('scope-open')).toHaveText(
        english.format(g.open),
      )
    }
    await expect(page.getByTestId('wallboard-sla-compliance')).toBeVisible()
    for (const id of ['by-stage', 'critical-high']) {
      await expect(page.getByTestId(`wallboard-${id}`)).toBeVisible()
    }
    // Screen 2 widgets are not on screen 1.
    await expect(page.getByTestId('wallboard-top-categories')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-monthly')).toHaveCount(0)

    // Independent check against the ops API: same Incident population, same window.
    const token = (
      await (
        await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
          data: {
            username: ACCOUNTS.ops.username,
            password: passwordFor(ACCOUNTS.ops.username),
          },
        })
      ).json()
    ).token as string
    const today = new Date().toISOString().slice(0, 10)
    const counts = (await (
      await page.request.get(
        `${API_BASE_URL}/metrics/scope-counts?dateFrom=${board.year}-01-01&dateTo=${today}`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
    ).json()) as { scopes: { scope: string; total: number }[] }
    expect(counts.scopes.find((s) => s.scope === 'INCIDENT')!.total).toBe(board.total)
    await shot(page, '62-wallboard-screen-1-desktop')

    await page.getByTestId('wallboard-rotation-next').click()
    await expect(page.getByTestId('wallboard-screen-title')).toHaveText('Trend')
    await expect(page.getByTestId('wallboard-top-categories')).toBeVisible()
    await expect(page.getByTestId('wallboard-monthly')).toBeVisible()
    await expect(page.getByTestId('wallboard-by-stage')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-group-counts')).toHaveCount(0)
    await shot(page, '63-wallboard-screen-2-desktop')
    expect(errors).toEqual([])
  })

  test('the Critical/High list shows no ticket subject to the wallboard role, but does to ops', async ({
    page,
    browser,
  }) => {
    const wallboardResponse = page.waitForResponse((r) =>
      r.url().startsWith(`${API_BASE_URL}/metrics/wallboard`),
    )
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')
    const board = (await (await wallboardResponse).json()) as {
      criticalHighOpen: { noIncident: number; subject: string | null }[]
    }
    expect(board.criticalHighOpen.length, 'the stack has open Critical/High incidents').toBeGreaterThan(0)
    for (const row of board.criticalHighOpen) expect(row.subject, `#${row.noIncident}`).toBeNull()

    // The subject text exists in the data (ops can read it) and must not be anywhere on screen.
    const token = (
      await (
        await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
          data: {
            username: ACCOUNTS.ops.username,
            password: passwordFor(ACCOUNTS.ops.username),
          },
        })
      ).json()
    ).token as string
    const opsRows = (await (
      await page.request.get(`${API_BASE_URL}/incidents?scope=INCIDENT&size=200`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json()) as { content: { NO_INCIDENT: number; SUBJECT: string }[] }
    const subjects = new Map(opsRows.content.map((r) => [r.NO_INCIDENT, r.SUBJECT]))

    const list = page.getByTestId('wallboard-critical-high')
    await expect(list.getByTestId('wallboard-critical-row')).toHaveCount(board.criticalHighOpen.length)
    const listText = await list.innerText()
    for (const row of board.criticalHighOpen) {
      const subject = subjects.get(row.noIncident)
      expect(subject, `ops can read #${row.noIncident}`).toBeTruthy()
      expect(listText).toContain(`#${row.noIncident}`)
      expect(listText, 'subject leaked onto the wallboard').not.toContain(subject!.trim())
      expect(await page.locator('body').innerText()).not.toContain(subject!.trim())
    }

    // Ops sees the subject for the same tickets, via the wallboard endpoint it may also call.
    const opsBoard = (await (
      await page.request.get(`${API_BASE_URL}/metrics/wallboard`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json()) as { criticalHighOpen: { noIncident: number; subject: string | null }[] }
    expect(opsBoard.criticalHighOpen.length).toBeGreaterThan(0)
    for (const row of opsBoard.criticalHighOpen) {
      expect(row.subject, `ops sees the subject of #${row.noIncident}`).toBe(
        subjects.get(row.noIncident)?.trim() ?? row.subject,
      )
      expect(row.subject).toBeTruthy()
    }
    void browser
  })

  test('rotates between two screens on the interval, with pause / next / interval persisted', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    // Install BEFORE any navigation, so the app's timers are the fake ones; time still flows
    // naturally, and `runFor` jumps it. No sleeping.
    await page.clock.install()
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')
    const screen = page.getByTestId('wallboard-tiles')
    const status = page.getByTestId('wallboard-rotation-status')
    await expect(screen).toHaveAttribute('data-screen', 'now')

    // Default is one minute.
    await expect(status).toContainText('Screen 1 of 2')
    await expect(status).toContainText('next in')
    const select = page.getByTestId('wallboard-rotation-interval')
    await expect(select).toHaveValue('60000')

    // Choose 30 s: it persists across a reload.
    await select.selectOption('30000')
    expect(await page.evaluate(() => localStorage.getItem('incident.wallboard.interval'))).toBe(
      '30000',
    )
    await expect(status).toContainText('30 s')

    await page.clock.runFor(31_000)
    await expect(screen).toHaveAttribute('data-screen', 'trend')
    await expect(status).toContainText('Screen 2 of 2')
    await page.clock.runFor(31_000)
    await expect(screen).toHaveAttribute('data-screen', 'now')
    await expect(page.getByTestId('wallboard-rotation-live')).toContainText('Screen')

    // Pause holds the screen however long the room waits.
    const toggle = page.getByTestId('wallboard-rotation-toggle')
    await expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'true')
    await expect(status).toContainText('paused')
    await page.clock.runFor(120_000)
    await expect(screen).toHaveAttribute('data-screen', 'now')

    // Next advances even while paused, and wraps.
    await page.getByTestId('wallboard-rotation-next').click()
    await expect(screen).toHaveAttribute('data-screen', 'trend')
    await page.getByTestId('wallboard-rotation-next').click()
    await expect(screen).toHaveAttribute('data-screen', 'now')

    // Resume restarts the clock.
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await page.clock.runFor(31_000)
    await expect(screen).toHaveAttribute('data-screen', 'trend')

    // The chosen interval survives a reload (and a fresh session restore).
    await page.reload()
    await expect(page.getByTestId('wallboard-rotation-interval')).toHaveValue('30000')
    expect(errors).toEqual([])
  })

  test('?tv=1 hides the ⓘ buttons and the controls but keeps rotating', async ({ page }) => {
    await page.clock.install()
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')
    await expect(page.getByTestId('info-hint')).not.toHaveCount(0)
    await expect(page.getByTestId('wallboard-rotation-controls')).toBeVisible()

    await page.goto('/wallboard?tv=1')
    const screen = page.getByTestId('wallboard-tiles')
    await expect(screen).toHaveAttribute('data-screen', 'now')
    await expect(page.getByTestId('wallboard-rotation-controls')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-rotation-next')).toHaveCount(0)
    await expect(page.getByTestId('info-hint')).toHaveCount(0)
    await expect(page.getByTestId('wallboard-rotation-status')).toBeVisible()
    // The stored interval from a previous test may be 30 s, the default 60 s: jump past either.
    await page.clock.runFor(61_000)
    await expect(screen).toHaveAttribute('data-screen', 'trend')
    await expect(page.getByTestId('info-hint')).toHaveCount(0)
  })

  test('both screens render in English and Indonesian with no raw i18n key', async ({ page }) => {
    const errors = trackPageErrors(page)
    await page.setViewportSize({ width: 1920, height: 1080 })
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')
    await expect(page.getByTestId('wallboard-total')).toBeVisible()

    for (const locale of ['en', 'id'] as const) {
      await switchTo(page, locale)
      for (const expected of ['now', 'trend'] as const) {
        if ((await page.getByTestId('wallboard-tiles').getAttribute('data-screen')) !== expected) {
          await page.getByTestId('wallboard-rotation-next').click()
        }
        await expect(page.getByTestId('wallboard-tiles')).toHaveAttribute('data-screen', expected)
        await expect(page.getByTestId('wallboard-loading')).toHaveCount(0)
        const text = await page.locator('body').innerText()
        expect(text, `${locale}/${expected} leaks a raw key`).not.toMatch(RAW_KEY)
        expect(text).not.toMatch(/\{[a-z]+\}/) // un-interpolated {placeholder}
        if (locale === 'id') {
          expect(text).not.toContain('Screen 1 of 2')
          await expect(page.getByTestId('wallboard-screen-title')).toHaveText(
            expected === 'now' ? 'Saat ini' : 'Tren',
          )
        }
      }
    }
    await shot(page, '64-wallboard-indonesian')
    expect(errors).toEqual([])
  })
})

// =========================================================================================== TM15

const VIEWPORTS = [
  { name: 'phone', width: 375, height: 812 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1440, height: 900 },
] as const

test.describe('TM15 responsive, targets, focus, dark', () => {
  for (const vp of VIEWPORTS) {
    test(`${vp.name} (${vp.width}px): scoped pages have no sideways scroll and thumb-sized new controls`, async ({
      page,
    }) => {
      const errors = trackPageErrors(page)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await login(page, ACCOUNTS.ops.username)
      await page.waitForURL('**/ops**')

      for (const scoped of PAGES) {
        await page.goto(scoped.path)
        await settled(page)
        await expect(page.getByTestId('scope-counts-list')).toBeVisible()
        await noHorizontalScroll(page, `${scoped.menu} @${vp.width}`)

        for (const target of PAGES) {
          await expectTarget(
            page.getByTestId(`scope-tile-${target.scope}`),
            `${scoped.menu} @${vp.width}: ${target.menu} scope tile`,
          )
        }
        if (scoped.scope === 'INCIDENT') {
          const link = page.getByTestId('heatmap-missing-link')
          await expect(link).toBeVisible()
          await link.scrollIntoViewIfNeeded()
          await expectTarget(link, `Incident @${vp.width}: missing link`, 44)
          await noHorizontalScroll(page, `Incident @${vp.width} (matrix)`)
        }
        // Keyboard: the first scope tile shows a focus ring.
        await expectFocusRing(
          page,
          page.getByTestId(`scope-tile-${scoped.scope === 'INCIDENT' ? 'PROBLEM' : 'INCIDENT'}`),
          `${scoped.menu} @${vp.width}: scope tile`,
        )
        await shot(page, `65-scope-${scoped.shotName}-${vp.name}`)
      }

      // Focus ring on the missing link too (Incident only), when it renders.
      await page.goto('/ops')
      await settled(page)
      await expectFocusRing(
        page,
        page.getByTestId('heatmap-missing-link'),
        `Incident @${vp.width}: missing link`,
      )
      expect(errors).toEqual([])
    })

    test(`${vp.name} (${vp.width}px): both wallboard screens fit, controls are thumb-sized and show focus`, async ({
      page,
    }) => {
      const errors = trackPageErrors(page)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await login(page, ACCOUNTS.wallboard.username)
      await page.waitForURL('**/wallboard**')
      await expect(page.getByTestId('wallboard-total')).toBeVisible()

      const next = page.getByTestId('wallboard-rotation-next')
      for (const screenName of ['now', 'trend'] as const) {
        await expect(page.getByTestId('wallboard-tiles')).toHaveAttribute('data-screen', screenName)
        await noHorizontalScroll(page, `wallboard ${screenName} @${vp.width}`)
        await expectTarget(next, `wallboard @${vp.width}: next`)
        await expectTarget(
          page.getByTestId('wallboard-rotation-toggle'),
          `wallboard @${vp.width}: pause`,
        )
        await expectTarget(
          page.getByTestId('wallboard-rotation-interval'),
          `wallboard @${vp.width}: interval`,
          44,
        )
        await shot(page, `66-wallboard-${screenName}-${vp.name}`)
        if (screenName === 'now') await next.click()
      }

      // Keyboard path through the rotation controls.
      await page.getByTestId('wallboard-rotation-controls').scrollIntoViewIfNeeded()
      await expectFocusRing(page, page.getByTestId('wallboard-rotation-interval'), 'interval')
      await expectFocusRing(page, page.getByTestId('wallboard-rotation-toggle'), 'pause')
      await expectFocusRing(page, next, 'next')

      await page.goto('/wallboard?tv=1')
      await expect(page.getByTestId('wallboard-total')).toBeVisible()
      await noHorizontalScroll(page, `wallboard tv @${vp.width}`)
      expect(errors).toEqual([])
    })
  }

  for (const width of [375, 768, 1024, 1440]) {
    test(`${width}px: no wallboard element clips its own content, on either screen`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: width < 768 ? 812 : 900 })
      await login(page, ACCOUNTS.wallboard.username)
      await page.waitForURL('**/wallboard**')
      await expect(page.getByTestId('wallboard-total')).toBeVisible()
      for (const screenName of ['now', 'trend'] as const) {
        await expect(page.getByTestId('wallboard-tiles')).toHaveAttribute('data-screen', screenName)
        const clipped = await page.evaluate(() =>
          [...document.querySelectorAll('[data-testid^="wallboard-"]')]
            .filter((el) => !el.classList.contains('sr-only')) // visually-hidden live region is 1px by design
            .filter((el) => el.scrollWidth > el.clientWidth + 1)
            .map((el) => `${el.getAttribute('data-testid')} ${el.scrollWidth}>${el.clientWidth}`),
        )
        expect(clipped, `${screenName} @${width}: elements wider than their box`).toEqual([])
        // A figure must also sit inside its tile, even where the tile hides overflow.
        const escaped = await page.evaluate(() =>
          [...document.querySelectorAll('[data-testid^="wallboard-"]')]
            .filter((tile) => tile.querySelector(':scope > span.tabular-nums, :scope > span.font-bold'))
            .flatMap((tile) => {
              const t = tile.getBoundingClientRect()
              return [...tile.querySelectorAll('span.tabular-nums')]
                .filter((f) => {
                  const r = f.getBoundingClientRect()
                  return r.left < t.left - 1 || r.right > t.right + 1
                })
                .map((f) => `${tile.getAttribute('data-testid')}: ${f.textContent}`)
            }),
        )
        expect(escaped, `${screenName} @${width}: figure leaves its tile`).toEqual([])
        await shot(page, `66-wallboard-${screenName}-w${width}`)
        if (screenName === 'now') await page.getByTestId('wallboard-rotation-next').click()
      }
    })
  }

  test('Service Request @desktop: the Created/Closed chart really draws bars (not just a blank card)', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await openScope(page, '/service-requests')
    const card = page.getByTestId('created-closed-trend')
    await expect(card).toBeVisible()
    const canvas = card.locator('canvas')
    const box = await canvas.boundingBox()
    expect(box, 'canvas rendered').not.toBeNull()
    expect(box!.width).toBeGreaterThan(100)
    expect(box!.height).toBeGreaterThan(100)

    // Bars drawn: after the animation settles, the canvas holds a meaningful share of
    // non-transparent, non-grid pixels in colours other than the gridline grey.
    await expect
      .poll(
        () =>
          canvas.evaluate((el) => {
            const c = el as HTMLCanvasElement
            const data = c.getContext('2d')!.getImageData(0, 0, c.width, c.height).data
            let blue = 0
            for (let i = 0; i < data.length; i += 4) {
              // opaque, saturated blue-ish: the "Created" bar
              if (data[i + 3]! > 200 && data[i + 2]! > 180 && data[i]! < 80) blue++
            }
            return blue
          }),
        { message: 'no Created bar pixels on the canvas', timeout: 10_000 },
      )
      .toBeGreaterThan(200)

    // The accessible data table behind the chart agrees: it has rows with a non-zero count.
    const table = page.getByTestId('created-closed-trend-table')
    const rows = table.locator('tbody tr')
    expect(await rows.count()).toBeGreaterThan(0)
    const text = await table.innerText()
    expect(text).toMatch(/[1-9]/)
    await shot(page, '69-service-request-trend-desktop')
  })

  test('tablet: the incident table scroller is keyboard-focusable and its hint goes away at the end', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 768, height: 1024 })
    await openScope(page, '/ops')
    const scroller = page.getByTestId('incident-table-scroller')
    await scroller.scrollIntoViewIfNeeded()
    await expect(scroller).toHaveAttribute('tabindex', '0')
    await expect(scroller).toHaveAttribute('role', 'region')
    const overflows = await scroller.evaluate((el) => el.scrollWidth > el.clientWidth + 1)
    expect(overflows, 'the table needs to scroll sideways at 768px for this check to mean anything').toBe(true)
    await expect(page.getByTestId('incident-table-scroll-hint')).toBeVisible()
    await expect(page.getByTestId('incident-table-scroll-fade')).toBeVisible()

    // Reachable by keyboard, with a visible ring, and arrow keys scroll it.
    await expectFocusRing(page, scroller, 'incident table scroller')
    const before = await scroller.evaluate((el) => el.scrollLeft)
    await page.keyboard.press('ArrowRight')
    await expect.poll(() => scroller.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before)

    // To the end: hint and fade disappear.
    await scroller.evaluate((el) => {
      el.scrollLeft = el.scrollWidth
    })
    await expect(page.getByTestId('incident-table-scroll-hint')).toHaveCount(0)
    await expect(page.getByTestId('incident-table-scroll-fade')).toHaveCount(0)
    await noHorizontalScroll(page, 'incident table @768 (page)')

    // And back: the hint returns.
    await scroller.evaluate((el) => {
      el.scrollLeft = 0
    })
    await expect(page.getByTestId('incident-table-scroll-hint')).toBeVisible()
  })

  test('phone: matrix header cells do not clip their text', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await openScope(page, '/ops')
    const heatmap = page.getByTestId('impact-urgency-heatmap')
    await heatmap.scrollIntoViewIfNeeded()
    const clipped = await heatmap.evaluate((root) =>
      [...root.querySelectorAll('th, [role="columnheader"], [role="rowheader"]')]
        .filter((el) => el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1)
        .map((el) => `${(el.textContent ?? '').trim()} ${el.scrollWidth}x${el.scrollHeight} > ${el.clientWidth}x${el.clientHeight}`),
    )
    expect(clipped, 'matrix header cells clip their text').toEqual([])
    // An abbreviated/wrapped header still carries the full value.
    const unknown = heatmap.getByText(/Unknown/).first()
    if ((await unknown.count()) > 0) {
      const titled = await unknown.evaluate((el) => !!el.closest('[title]') || !!el.getAttribute('title'))
      expect(titled, 'Unknown header exposes its full value as a title').toBe(true)
    }
  })

  test('a machine set to dark: the wallboard still renders dark, legible and unclipped', async ({
    page,
  }) => {
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.setViewportSize({ width: 1440, height: 900 })
    await login(page, ACCOUNTS.wallboard.username)
    await page.waitForURL('**/wallboard**')
    const layout = page.getByTestId('wallboard-layout')
    await expect(layout).toHaveClass(/\bapp-dark\b/)
    await expect(page.getByTestId('wallboard-total')).toBeVisible()

    const probe = await page.evaluate(() => {
      const rgb = (value: string) => {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = 1
        const ctx = canvas.getContext('2d')!
        ctx.fillStyle = value
        ctx.fillRect(0, 0, 1, 1)
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data
        return [r, g, b] as number[]
      }
      const lum = ([r, g, b]: number[]) => {
        const c = [r, g, b].map((v) => {
          const s = v / 255
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
        })
        return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!
      }
      const layoutEl = document.querySelector('[data-testid="wallboard-layout"]')!
      const bg = lum(rgb(getComputedStyle(layoutEl).backgroundColor))
      const title = document.querySelector('[data-testid="wallboard-screen-title"]')!
      const fg = lum(rgb(getComputedStyle(title).color))
      const total = document.querySelector('[data-testid="wallboard-total"]')!
      const tfg = lum(rgb(getComputedStyle(total).color))
      return { bg, fg, tfg }
    })
    const ratio = (a: number, b: number) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
    expect(ratio(probe.bg, probe.fg), 'screen title contrast on dark').toBeGreaterThanOrEqual(4.5)
    expect(ratio(probe.bg, probe.tfg), 'total tile contrast on dark').toBeGreaterThanOrEqual(4.5)
    await noHorizontalScroll(page, 'wallboard dark')
    await shot(page, '67-wallboard-os-dark')
    await page.getByTestId('wallboard-rotation-next').click()
    await expect(page.getByTestId('wallboard-monthly')).toBeVisible()
    await shot(page, '68-wallboard-os-dark-screen-2')
  })
})
