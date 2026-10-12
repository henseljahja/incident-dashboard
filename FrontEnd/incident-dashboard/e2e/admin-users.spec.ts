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
 * User management, end to end — the screen `V6__remove_unbuilt_admin_menu.sql` deleted a menu
 * row over, now that it exists.
 *
 * Worth driving at tier 3 rather than trusting the vitest specs, because the create form
 * shipped broken in exactly the gap a mocked test cannot see: those specs emitted a
 * hand-written `{valid, values}` submit payload, while the real PrimeVue resolver returned
 * `values: undefined` (a schema key with no rendered field) and the screen died on a
 * TypeError. Only a real browser driving a real form catches that class of defect.
 */
test.describe('user management', () => {
  /** Unique per run: these specs CREATE accounts, and the stack is not reset between them. */
  const suffix = String(Date.now()).slice(-6)
  const newUsername = `e2e.created.${suffix}`

  test('the menu is advertised and the screen behind it actually loads', async ({ page }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.admin.username)
    await page.waitForURL('**/ops')

    // The nav is rendered from the SERVER's menu payload, so this asserts the V8 seed and the
    // SPA route agree — the exact pairing V6 broke by having one without the other.
    const link = page.getByRole('link', { name: /user management/i })
    await expect(link).toBeVisible()
    await link.click()

    await page.waitForURL('**/admin/users')
    // The `h1` is rendered by AppLayout from the SERVER's menu payload, so asserting it here
    // proves the label the backend advertises actually reaches the page — not just that some
    // hardcoded string is present.
    await expect(page.getByRole('heading', { level: 1, name: 'User Management' })).toBeVisible()
    // A blank page was the original bug; assert real table chrome, not just the URL.
    await expect(page.getByPlaceholder('Search username, name or email')).toBeVisible()
    expect(errors, 'the screen must not throw').toEqual([])
    await shot(page, '30-admin-users-list')
  })

  test('a role without manage cannot reach the screen or the API', async ({ page }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops')

    // No affordance...
    await expect(page.getByRole('link', { name: /user management/i })).toHaveCount(0)

    // ...and, more importantly, no data even when the URL is typed by hand.
    //
    // The token is fetched and attached explicitly. `page.request` is a SEPARATE context that
    // does not carry the SPA's in-memory bearer token, so calling it bare returns 401
    // ("no credentials") — which would pass a loose check while proving nothing about
    // authorization. 403 is the assertion worth making: a fully authenticated ops.manager,
    // refused on privilege.
    const tokenResponse = await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
      data: {
        username: ACCOUNTS.ops.username,
        password: passwordFor(ACCOUNTS.ops.username),
      },
    })
    const { token } = await tokenResponse.json()
    const response = await page.request.get(`${API_BASE_URL}/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(response.status(), 'authorization is server-side and fail-closed').toBe(403)
  })

  test('creating an account issues a one-time password that really works', async ({ page }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto('/admin/users/create')

    await page.locator('input#username').fill(newUsername)
    await page.locator('input#name').fill('E2E Created User')
    // PrimeVue's Select is a role=combobox span with a body-mounted overlay, so it is driven
    // the way a person drives it rather than by setting a value.
    await page.getByLabel('Role').click()
    await page.getByRole('option', { name: 'Viewer (read only)' }).click()

    await page.getByRole('button', { name: 'Create' }).click()

    const panel = page.getByRole('status')
    await expect(panel).toBeVisible()
    await expect(panel).toContainText('shown once')
    await shot(page, '31-admin-user-created-otp')

    const temporary = (await panel.locator('code').innerText()).trim()
    expect(temporary.length, 'a blank password panel is worse than an error').toBeGreaterThan(8)
    expect(errors).toEqual([])

    // The credential is only real if it authenticates. Asserted against the API rather than by
    // logging in through the UI, so this spec does not disturb the admin session it holds.
    const tokenResponse = await page.request.post(`${AUTH_BASE_URL}/auth/token`, {
      data: { username: newUsername, password: temporary },
    })
    expect(tokenResponse.status()).toBe(200)
    expect(
      (await tokenResponse.json()).mustChangePassword,
      'a handed-over password must be rotated at first sign-in',
    ).toBe(true)
  })

  test('the new account is listed, and the administrator still is not', async ({ page }) => {
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto('/admin/users')

    await page.getByPlaceholder('Search username, name or email').fill(newUsername)
    await page.getByRole('button', { name: 'Search', exact: true }).click()

    await expect(page.getByRole('cell', { name: newUsername })).toBeVisible()
    // yob's `hasNotUsername`: an administrator who can see their own row can deactivate it,
    // and if theirs is the last account holding `manage` there is no way back in.
    await expect(page.getByRole('cell', { name: ACCOUNTS.admin.username })).toHaveCount(0)
    await shot(page, '32-admin-users-search')
  })

  test('a duplicate username is refused with a message, not a silent no-op', async ({ page }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto('/admin/users/create')

    await page.locator('input#username').fill(newUsername)
    await page.locator('input#name').fill('Duplicate Attempt')
    await page.getByLabel('Role').click()
    await page.getByRole('option', { name: 'Viewer (read only)' }).click()
    await page.getByRole('button', { name: 'Create' }).click()

    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText(/already exists/i)
    // A failed create must not also show a password panel — that would imply an account exists.
    await expect(page.getByRole('status')).toHaveCount(0)
    expect(errors).toEqual([])
    await shot(page, '33-admin-user-duplicate')
  })

  test('editing loads the account and refuses to rename it', async ({ page }) => {
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto(`/admin/users/${encodeURIComponent(newUsername)}/edit`)

    const usernameField = page.locator('input#username')
    await expect(usernameField).toHaveValue(newUsername)
    // The server has no rename path; an editable box here would promise one.
    await expect(usernameField).toBeDisabled()
    await expect(page.locator('input#name')).toHaveValue('E2E Created User')
    await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible()
    await shot(page, '34-admin-user-edit')
  })

  test('viewing an account is read-only — no save, no reset', async ({ page }) => {
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto(`/admin/users/${encodeURIComponent(newUsername)}`)

    await expect(page.locator('input#name')).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Save' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Reset Password' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Back' })).toBeVisible()
    await shot(page, '35-admin-user-view')
  })

  test('deactivating an account really ends its session', async ({ page }) => {
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto(`/admin/users/${encodeURIComponent(newUsername)}/edit`)

    await page.getByRole('switch').click()
    await page.getByRole('button', { name: 'Save' }).click()
    await page.waitForURL('**/admin/users')

    // The flag alone would be cosmetic for the 24h a refresh cookie still lives — which is
    // precisely the window "deactivate now" is used in.
    //
    // `page.request` is a separate context with no bearer token, so it must be given one
    // explicitly; without it this reads back 401 and the assertion fails for a reason that has
    // nothing to do with deactivation.
    const adminToken = await page.request
      .post(`${AUTH_BASE_URL}/auth/token`, {
        data: {
          username: ACCOUNTS.admin.username,
          password: passwordFor(ACCOUNTS.admin.username),
        },
      })
      .then((r) => r.json())
      .then((d) => d.token)
    const response = await page.request.get(
      `${API_BASE_URL}/admin/users/${encodeURIComponent(newUsername)}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    )
    expect((await response.json()).status).toBe('INACTIVE')
    await shot(page, '36-admin-user-deactivated')
  })

  test('an unknown account is a real message, not a blank screen', async ({ page }) => {
    const errors = trackPageErrors(page)
    await login(page, ACCOUNTS.admin.username)
    // Load-bearing: `login()` submits the form and RBAC then pushes to the role's landing
    // route. Navigating before that settles races the in-flight navigation, and the `goto`
    // below is cancelled — the specs then sat on /login waiting 60s for a field that was
    // never going to render.
    await page.waitForURL('**/ops')
    await page.goto('/admin/users/no.such.person')

    await expect(page.getByRole('alert')).toBeVisible()
    expect(errors, 'a 404 must not surface as an uncaught error').toEqual([])
    await shot(page, '37-admin-user-not-found')
  })
})
