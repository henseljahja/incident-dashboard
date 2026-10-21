import { expect, test } from './fixtures/test'

import {
  ACCOUNTS,
  API_BASE_URL,
  AUTH_BASE_URL,
  login,
  passwordFor,
  shot,
  trackPageErrors,
} from './fixtures/seed'

/**
 * The Parameters menu end to end: ADMIN edits and the change really lands in the server (a
 * reload shows it, the change log records it, the Alerts page obeys it); OPS_MANAGER sees the same
 * page read-only and the API refuses its writes. The stack is shared and not reset between specs,
 * so every edit is put back before the test ends.
 */
test.describe('parameters', () => {
  test('ADMIN edits an alert rule and a parameter; both persist and are logged', async ({
    page,
  }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.admin.username)
    await page.waitForURL('**/ops')
    await page.getByRole('link', { name: 'Parameters' }).click()
    await page.waitForURL('**/parameters')
    await expect(page.getByRole('heading', { level: 1, name: 'Parameters' })).toBeVisible()
    await expect(page.getByTestId('params-readonly')).toHaveCount(0)

    // Scalar parameter: the server's validation message is shown, then a valid value is saved.
    const horizon = page.getByTestId('param-forecast.default_horizon_days')
    await horizon.getByTestId('param-input').fill('99')
    await horizon.getByTestId('param-save').click()
    await expect(horizon.getByTestId('param-notice')).toContainText('Allowed range')
    await horizon.getByTestId('param-input').fill('14')
    await horizon.getByTestId('param-save').click()
    await expect(horizon.getByTestId('param-notice')).toContainText('Saved')

    // Alert rule: enable the (shipped-disabled) aging rule.
    const aging = page.getByTestId('rule-AGING_OPEN')
    await aging.getByTestId('rule-enabled').check()
    await aging.getByTestId('rule-save').click()
    await expect(page.getByTestId('rule-notice').first()).toContainText('Saved')
    await shot(page, '40-parameters-admin')

    // Persisted: a fresh load shows the saved values and both edits in the change log.
    await page.reload()
    await expect(
      page.getByTestId('param-forecast.default_horizon_days').getByTestId('param-input'),
    ).toHaveValue('14')
    await expect(page.getByTestId('rule-AGING_OPEN').getByTestId('rule-enabled')).toBeChecked()
    await expect(page.getByTestId('params-changes')).toContainText(
      'param:forecast.default_horizon_days',
    )
    await expect(page.getByTestId('params-changes')).toContainText('alert:AGING_OPEN')

    // The Forecast page now defaults to the configured horizon.
    await page.goto('/forecast')
    await expect(page.getByTestId('forecast-horizon')).toHaveValue('14')

    // Put everything back to the defaults.
    await page.goto('/parameters')
    const back = page.getByTestId('param-forecast.default_horizon_days')
    await back.getByTestId('param-reset').click()
    await back.getByTestId('param-save').click()
    await expect(back.getByTestId('param-notice')).toContainText('Saved')
    const rule = page.getByTestId('rule-AGING_OPEN')
    await rule.getByTestId('rule-reset').click()
    await rule.getByTestId('rule-save').click()
    await expect(page.getByTestId('rule-notice').first()).toContainText('Saved')
    expect(errors, 'the screen must not throw').toEqual([])
  })

  test('OPS_MANAGER sees the page read-only and the API refuses its writes', async ({ page }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops')
    await page.getByRole('link', { name: 'Parameters' }).click()
    await page.waitForURL('**/parameters')

    await expect(page.getByTestId('params-readonly')).toBeVisible()
    await expect(page.getByTestId('sla-save')).toHaveCount(0)
    await expect(page.getByTestId('rule-save')).toHaveCount(0)
    await expect(page.getByTestId('param-save')).toHaveCount(0)
    await expect(page.getByTestId('sla-days').first()).toBeDisabled()
    await shot(page, '41-parameters-readonly')

    const tokenResponse = await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
      data: { username: ACCOUNTS.ops.username, password: passwordFor(ACCOUNTS.ops.username) },
    })
    const { token } = await tokenResponse.json()
    const response = await page.request.put(
      `${API_BASE_URL}/config/params/forecast.default_horizon_days`,
      { headers: { Authorization: `Bearer ${token}` }, data: { value: '30' } },
    )
    expect(response.status(), 'writes need incident.params:manage, fail-closed').toBe(403)
  })
})
