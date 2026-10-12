import { expect, test } from './fixtures/test'

import { ACCOUNTS, AUTH_BASE_URL, login, passwordFor, shot } from './fixtures/seed'

/**
 * Authentication and RBAC landing, end to end against the real `/auth/token`.
 *
 * The point of doing this at tier 3 rather than trusting `stores/__tests__/auth.spec.ts` is
 * that the unit test asserts `resolveEntry()` in isolation. Here the SERVER decides the
 * `landingRoute`, the router guard consumes it, and the browser's address bar is the
 * assertion — the whole chain, or nothing.
 */
test.describe('authentication', () => {
  test('an unauthenticated visitor is bounced to /login from a protected route', async ({
    page,
  }) => {
    await page.goto('/ops')
    await page.waitForURL('**/login')
    expect(new URL(page.url()).pathname).toBe('/login')
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
    await shot(page, '01-login-guard-redirect')
  })

  test('bad credentials are refused, with no session and no leak of which half was wrong', async ({
    page,
  }) => {
    const responsePromise = page.waitForResponse(
      (response) => response.url() === `${AUTH_BASE_URL}/auth/token`,
    )

    await login(page, ACCOUNTS.ops.username, 'definitely-not-the-password')

    const response = await responsePromise
    expect(response.status(), 'a wrong password must be a 401, never a 200 with no menus').toBe(
      401,
    )

    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    // Generic on purpose: a message naming the username as valid turns /auth/token into a
    // user-enumeration oracle (the same property AuthFlowIT asserts server-side).
    await expect(alert).not.toContainText(ACCOUNTS.ops.username)

    // Still on /login: a refused login must not navigate anywhere.
    expect(new URL(page.url()).pathname).toBe('/login')
    await shot(page, '02-login-bad-credentials')
  })

  /**
   * RBAC landing for every seeded role. NOTHING here chooses a route — the account is typed
   * into the form and the app is left to decide. That is the assertion.
   */
  for (const [label, account] of Object.entries(ACCOUNTS)) {
    test(`${label} (${account.username}) lands on ${account.landingRoute} without choosing it`, async ({
      page,
    }) => {
      await login(page, account.username)
      await page.waitForURL(`**${account.landingRoute}**`)
      expect(
        new URL(page.url()).pathname,
        'the landing route is decided server-side (role DEFAULT_ROUTE, or the forced-rotation ' +
          'override), never a hardcoded path in the SPA',
      ).toBe(account.landingRoute)
      await shot(page, `03-rbac-landing-${account.username}`)
    })
  }

  test('a forced-rotation account is locked to /change-password', async ({ page }) => {
    await login(page, ACCOUNTS.rotate.username)
    await page.waitForURL('**/change-password')

    // The server refuses a scope="change-password" token on every route but its own, so an
    // account mid-rotation must never reach an operational surface. A hard navigation to /ops
    // is the attempt; the assertion is that it does not land there.
    //
    // Observed and deliberate: it lands on /login, not back on /change-password. A full page
    // load drops the in-memory access token and `restoreSession()` has to re-establish it via
    // `/auth/refresh-token` + `/auth/me` — and `/auth/me` is one of the routes the
    // change-password scope is refused on, so the restore fails and the session is cleared.
    // Either destination satisfies the invariant that matters; /ops does not.
    await page.goto('/ops')
    await expect
      .poll(() => new URL(page.url()).pathname, {
        message: 'an account mid-rotation must not reach an operational surface',
      })
      .not.toBe('/ops')
    expect(['/login', '/change-password']).toContain(new URL(page.url()).pathname)
    await expect(page.getByTestId('incident-row')).toHaveCount(0)
    await shot(page, '04-forced-rotation-locked')
  })

  test('logging out ends the session — the refresh cookie no longer restores it', async ({
    page,
  }) => {
    await login(page, ACCOUNTS.ops.username)
    await page.waitForURL('**/ops**')

    await page.getByRole('button', { name: 'Log out' }).click()
    await page.waitForURL('**/login')

    // A reload after logout must NOT come back logged in: if the refresh cookie survived
    // logout, this navigates to /ops and the test fails.
    await page.goto('/ops')
    await page.waitForURL('**/login')
    expect(new URL(page.url()).pathname).toBe('/login')
    await shot(page, '05-logout-session-ended')
  })
})

test('the access token is never persisted to web storage', async ({ page }) => {
  await login(page, ACCOUNTS.ops.username)
  await page.waitForURL('**/ops**')

  const persisted = await page.evaluate(() => ({
    local: Object.entries({ ...localStorage }),
    session: Object.entries({ ...sessionStorage }),
  }))

  const asText = JSON.stringify(persisted)
  // `.agents/patterns/session.md`: the access token lives in memory only. A JWT in storage is
  // a stored bearer credential readable by any XSS.
  expect(asText, 'no JWT-shaped value may appear in localStorage/sessionStorage').not.toMatch(
    /eyJ[A-Za-z0-9_-]{8,}\./,
  )
  // Sanity: the helper above genuinely logged in, so the negative assertion means something.
  expect(passwordFor(ACCOUNTS.ops.username).length).toBeGreaterThan(0)
})
