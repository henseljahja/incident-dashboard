/**
 * Tier-3 shared fixture layer: accounts, credentials-from-environment, screenshot helper and
 * the global preflight that refuses to let the suite run against a stack that is not there.
 *
 * NOTHING here stubs, mocks or intercepts the API. Every helper drives the real SPA against a
 * real backend. A tier-3 suite that can pass without the backend is worse than no suite at
 * all, because the final wave reads it as coverage — hence `globalSetup` below, which fails
 * loudly and early rather than letting specs "pass" against a dead stack.
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'

import type { Page } from '@playwright/test'

/** Where step captures land. Gitignored — they render real incident subjects and customer names. */
export const SCREENSHOT_DIR = path.join('e2e', 'screenshots')

/** The backend origin the SPA talks to. Must match `incident.cors.allowed-origins`' peer. */
export const API_BASE_URL = process.env.E2E_API_BASE_URL ?? 'http://localhost:8081/api'

/**
 * The auth endpoints are served at the ROOT, not under `/api` — `AuthController` maps
 * `/auth/**` while every data controller maps `/api/**`. Mirrors `AUTH_BASE_URL` in
 * `src/apis/client.ts`; specs that wait on a login/refresh response must use this base.
 */
export const AUTH_BASE_URL = API_BASE_URL.replace(/\/api\/?$/, '')

/** The SPA origin. 5175 is not arbitrary: it is the ONLY origin in `incident.cors.allowed-origins`. */
export const APP_BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5175'

/**
 * Where `IncidentDetailDrawer` builds its deep links from. Kept in sync with the
 * `VITE_REDMINE_BASE_URL` the dev server is started with (see `playwright.config.ts`).
 */
export const REDMINE_BASE_URL =
  process.env.E2E_REDMINE_BASE_URL ?? 'https://redmine.example.com'

export interface Account {
  username: string
  /**
   * Where the app is expected to put this account with NO route chosen by the test — the
   * role's `DEFAULT_ROUTE` from `V5__seed_rbac.sql`, except for `rotate.me`, whose
   * `MUST_CHANGE_PASSWORD = 'Y'` outranks it and pins the session to `/change-password`.
   */
  landingRoute: string
}

/**
 * The `V7__seed_test_users.sql` accounts this suite drives. `V7` is TEST-CLASSPATH ONLY, so
 * these usernames exist on the isolated stack and nowhere else.
 */
export const ACCOUNTS = {
  // ADMIN, and the only seeded account holding `incident.admin:manage` — the whole
  // user-management surface is unreachable by every other role, by design.
  admin: { username: 'admin.test', landingRoute: '/ops' },
  ops: { username: 'ops.manager', landingRoute: '/ops' },
  exec: { username: 'exec.user', landingRoute: '/exec' },
  wallboard: { username: 'wallboard.user', landingRoute: '/wallboard' },
  rotate: { username: 'rotate.me', landingRoute: '/change-password' },
} as const satisfies Record<string, Account>

/**
 * Credentials come from the environment, never from this repository.
 *
 * `V7` gives every account a DIFFERENT password, so a single `E2E_PASSWORD` cannot address
 * them all. Resolution order per account:
 *
 *   1. `E2E_PASSWORD_<USERNAME>` with non-alphanumerics folded to `_` and upper-cased
 *      (`ops.manager` -> `E2E_PASSWORD_OPS_MANAGER`), then
 *   2. `E2E_PASSWORD` as a shared fallback for stacks seeded with one password.
 *
 * Missing -> throw. Never a default, never a literal in a spec.
 */
export function envKeyFor(username: string): string {
  return `E2E_PASSWORD_${username.toUpperCase().replaceAll(/[^A-Z0-9]/g, '_')}`
}

export function passwordFor(username: string): string {
  const value = process.env[envKeyFor(username)] ?? process.env.E2E_PASSWORD
  if (!value) {
    throw new Error(
      `No password in the environment for "${username}". Set ${envKeyFor(username)} ` +
        `(or E2E_PASSWORD for all accounts). See playwright.config.ts for the full recipe.`,
    )
  }
  return value
}

/**
 * A capture per key step. Named with a zero-padded ordinal so the directory reads as a
 * walkthrough in file order rather than in whatever order the runner finished.
 */
export async function shot(page: Page, name: string): Promise<void> {
  await page.addStyleTag({ content: '#__vue-devtools-container__{display:none!important}' })
  // chart.js animates bars in; a capture mid-animation looks like an empty card. Wait until the
  // canvas pixels stop changing (no-op when the page has no chart).
  await page
    .waitForFunction(() => {
      const w = window as unknown as { __shotPrev?: string }
      const canvases = [...document.querySelectorAll('canvas')]
      if (canvases.length === 0) return true
      const now = canvases.map((c) => c.toDataURL()).join('|')
      const stable = w.__shotPrev === now
      w.__shotPrev = now
      return stable
    }, undefined, { polling: 250, timeout: 5000 })
    .catch(() => undefined)
  mkdirSync(SCREENSHOT_DIR, { recursive: true })
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true })
}

/**
 * Drives the REAL login form — never a token injected into the store. `/login` is reached by
 * navigating to it directly; where the app goes AFTERWARDS is left entirely to RBAC, which is
 * what the landing assertions in `login.spec.ts` and `ops-happy-path.spec.ts` are checking.
 */
export async function login(page: Page, username: string, password?: string): Promise<void> {
  await page.goto('/login')
  // `InputText`'s root IS the `<input>`, so `id`/`autocomplete` land on it.
  await page.locator('input#username').fill(username)
  // `Password`'s root is a WRAPPER div, and PrimeVue binds fallthrough attrs (`id`,
  // `autocomplete`) to that wrapper — the real `<input>` inherits neither. So the field is
  // located structurally. (Side effect worth a design-review ticket, not a change here:
  // `<label for="password">` in LoginView therefore points at a div, not at the input.)
  await page
    .locator('.p-password input')
    .first()
    .fill(password ?? passwordFor(username))
  await page.getByRole('button', { name: 'Sign in' }).click()
}

/** Fails a spec if the page threw an uncaught error — an "empty state" must not be a stack trace. */
export function trackPageErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

/**
 * Playwright `globalSetup`. Two jobs, both fail-loud:
 *
 *  1. every account this suite logs in as has a password in the environment;
 *  2. the backend is actually up AND actually seeded — proved by performing a real login
 *     against `POST /auth/token` before a single browser starts.
 *
 * If either fails the whole run aborts with reproduction instructions. It never degrades to
 * a stubbed or skipped run.
 */
export default async function globalSetup(): Promise<void> {
  const missing = Object.values(ACCOUNTS)
    .map((account) => account.username)
    .filter((username) => !process.env[envKeyFor(username)] && !process.env.E2E_PASSWORD)

  if (missing.length > 0) {
    throw new Error(
      'TIER-3 PREFLIGHT FAILED — no credentials in the environment.\n' +
        missing.map((username) => `  missing ${envKeyFor(username)}  (for ${username})`).join('\n') +
        '\nSet them (or a single E2E_PASSWORD) and re-run. See playwright.config.ts.',
    )
  }

  const probe = ACCOUNTS.ops
  let status: number
  try {
    const response = await fetch(`${AUTH_BASE_URL}/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: probe.username,
        password: passwordFor(probe.username),
      }),
    })
    status = response.status
  } catch (cause) {
    throw new Error(
      `TIER-3 PREFLIGHT FAILED — no backend at ${API_BASE_URL}.\n` +
        'Bring the isolated stack up first (see the "Running this suite" block in ' +
        'playwright.config.ts). This suite does NOT stub the API, so there is nothing ' +
        `meaningful to run without it.\n  cause: ${String(cause)}`,
    )
  }

  if (status !== 200) {
    throw new Error(
      `TIER-3 PREFLIGHT FAILED — ${AUTH_BASE_URL}/auth/token answered ${status} for ` +
        `"${probe.username}". The backend is up but either the V7 test users are not seeded ` +
        'or the password in the environment is wrong.',
    )
  }
}
