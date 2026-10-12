import { ACCOUNTS, login, shot } from './fixtures/seed'
import { LOCALE_STORAGE_KEY, expect, test } from './fixtures/test'

/**
 * TM034 — the language switch is a browser-local preference that survives a reload (US7, DAC4).
 *
 * The shared fixture seeds `en` only when nothing is stored, so the Indonesian choice made here
 * is what the reload has to restore.
 */
test.describe('UI language', () => {
  test('switching to Indonesian re-renders without a reload and persists across one', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
    await expect(page.locator('[data-kpi="at-risk"]')).toContainText('At risk')

    await page.getByTestId('language-switch').getByTestId('language-option-id').click()

    // No reload: same document, new copy.
    await expect(page.locator('html')).toHaveAttribute('lang', 'id')
    await expect(page.locator('[data-kpi="at-risk"]')).toContainText('Berisiko')
    expect(await page.evaluate((key) => localStorage.getItem(key), LOCALE_STORAGE_KEY)).toBe('id')

    await page.reload()

    await expect(page.locator('html')).toHaveAttribute('lang', 'id')
    await expect(page.getByRole('heading', { level: 1, name: 'Insiden' })).toBeVisible()
    await expect(page.locator('[data-kpi="at-risk"]')).toContainText('Berisiko')
    await shot(page, '50-ops-indonesian')

    // And back, so the preference is not a one-way door.
    await page.getByTestId('language-switch').getByTestId('language-option-en').click()
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
    await expect(page.getByRole('heading', { level: 1, name: 'Incident' })).toBeVisible()
  })
})
