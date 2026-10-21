import { ACCOUNTS, login, shot, trackPageErrors } from './fixtures/seed'
import { expect, test } from './fixtures/test'

/**
 * "Explain this" on Forecast, Alerts and RCA, through the whole chain: SPA -> privilege check ->
 * narrator -> HTTP -> a stand-in model the e2e stack starts (`FakeOpenAiServer`). The stand-in
 * writes `task=<topic> lang=<language>`, so each assertion proves the right question was asked
 * in the right language, not merely that some text appeared. No key, no network, no real data.
 */
const PAGES = [
  { topic: 'forecast', path: '/forecast' },
  { topic: 'alerts', path: '/alerts' },
  { topic: 'rca', path: '/rca' },
] as const

test.describe('explain this', () => {
  for (const { topic, path } of PAGES) {
    test(`${topic}: nothing is sent until the button is pressed; then a labelled reading appears`, async ({
      page,
    }) => {
      const errors = trackPageErrors(page)
      const explainCalls: string[] = []
      page.on('request', (r) => {
        if (r.url().includes('/insights/ai/explain/')) explainCalls.push(r.url())
      })
      await login(page, ACCOUNTS.ops.username)
      await page.waitForURL('**/ops**')
      await page.goto(path)

      const card = page.getByTestId('explain-card')
      await expect(card).toBeVisible()
      await expect(card.getByRole('heading', { name: 'Explain this page' })).toBeVisible()
      await page.waitForLoadState('networkidle')
      expect(explainCalls, 'no model call on page load').toEqual([])

      await card.getByTestId('explain-button').click()

      const result = card.getByTestId('explain-result')
      await expect(result).toContainText(`task=explain-${topic}`)
      await expect(result).toContainText('lang=en')
      await expect(result.getByRole('listitem')).toHaveCount(2)
      await expect(card.getByTestId('explain-privacy')).toContainText('openai')
      await expect(card.getByTestId('explain-privacy')).toContainText('never ticket text')
      await expect(card.getByTestId('explain-button')).toHaveText(/Explain again/)
      expect(explainCalls).toHaveLength(1)
      await shot(page, `60-explain-${topic}`)
      expect(errors, 'the screen must not throw').toEqual([])
    })
  }

  test('the reading follows the UI language and goes stale when the filters change', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await page.goto('/forecast')
    const card = page.getByTestId('explain-card')

    await card.getByTestId('explain-button').click()
    await expect(card.getByTestId('explain-result')).toContainText('lang=en')
    await expect(card.getByTestId('explain-stale')).toHaveCount(0)

    // Changing what the page shows (the horizon) makes the earlier reading out of date.
    await page.getByTestId('forecast-horizon').selectOption('30')
    await expect(card.getByTestId('explain-stale')).toBeVisible()

    await page.getByTestId('language-switch').getByTestId('language-option-id').click()
    await expect(card.getByRole('heading', { name: 'Jelaskan halaman ini' })).toBeVisible()
    await card.getByTestId('explain-button').click()
    await expect(card.getByTestId('explain-result')).toContainText('lang=id')
    await expect(card.getByTestId('explain-stale')).toHaveCount(0)
    await shot(page, '61-explain-indonesian')
    await page.getByTestId('language-switch').getByTestId('language-option-en').click()
  })

  test('a failed request shows an alert, leaves the page working, and retry recovers', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await page.goto('/rca')
    const card = page.getByTestId('explain-card')

    // Fault injection on the one endpoint: the rest of the page keeps using the real API.
    await page.route('**/api/insights/ai/explain/rca**', (route) => route.abort())
    await card.getByTestId('explain-button').click()
    await expect(card.getByRole('alert')).toContainText('Could not reach the AI service')
    await expect(page.getByTestId('rca-view')).toBeVisible()
    await shot(page, '62-explain-error')

    await page.unroute('**/api/insights/ai/explain/rca**')
    await card.getByTestId('explain-retry').click()
    await expect(card.getByTestId('explain-result')).toContainText('task=explain-rca')
  })

  test('phone: the card fits at 375px, the button is thumb-sized, no sideways scroll', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await page.goto('/alerts')
    const card = page.getByTestId('explain-card')
    await expect(card).toBeVisible()
    await card.getByTestId('explain-button').click()
    await expect(card.getByTestId('explain-result')).toBeVisible()

    const box = await card.getByTestId('explain-button').boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow, 'no horizontal scroll').toBeLessThanOrEqual(0)
    await shot(page, '63-explain-phone')
  })

  test('the button is keyboard-operable, keeps focus, and the result is announced politely', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await page.goto('/alerts')
    const button = page.getByTestId('explain-button')
    await button.focus()
    await page.keyboard.press('Enter')

    const result = page.getByTestId('explain-result')
    await expect(result).toContainText('task=explain-alerts')
    // The announcement comes from a status region that existed before the text did, and focus
    // is handed back to the button rather than dropped to <body> when the call finishes.
    await expect(page.getByTestId('explain-status')).toHaveAttribute('role', 'status')
    await expect(page.getByTestId('explain-status')).toHaveText(/Explanation ready: 2 points/)
    await expect(button).toBeFocused()
  })
})
