import { defineConfig, devices } from '@playwright/test'

/**
 * READ-ONLY checks against an already-deployed stack — the demo host, not the isolated test stack.
 *
 * The main config (`playwright.config.ts`) owns the isolated stack and its fixtures, which
 * TRUNCATE and reseed tables; none of that may ever point at a shared host. This one starts no
 * server, seeds nothing and changes nothing: it clicks, reads and asserts.
 *
 * It never types a password either. The first run opens a visible login window
 * (`e2e-deployed/login.setup.ts`); sign in and it saves the session and closes by itself:
 *
 *     pnpm exec playwright test -c playwright.deployed.config.ts
 *     DEPLOYED_FORCE_LOGIN=1 pnpm exec playwright test -c playwright.deployed.config.ts   # session expired
 *
 * `.auth/` is gitignored: the file holds a live refresh cookie. The spec runs in ONE browser
 * context and writes the session back at the end, so a rotated refresh cookie is not lost
 * between runs.
 */
export const DEPLOYED_BASE_URL = process.env.DEPLOYED_BASE_URL ?? 'http://172.16.54.213:8090'
export const DEPLOYED_AUTH_STATE = process.env.DEPLOYED_AUTH_STATE ?? '.auth/deployed.json'

export default defineConfig({
  testDir: './e2e-deployed',
  globalSetup: './e2e-deployed/login.setup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    baseURL: DEPLOYED_BASE_URL,
    viewport: { width: 1440, height: 900 },
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
})
