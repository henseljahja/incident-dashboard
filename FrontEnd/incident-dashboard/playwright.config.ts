import { defineConfig, devices } from '@playwright/test'

/**
 * Tier-3 walkthrough driver (Task 23). Discharges the tier-3 obligation deferred from
 * Phases 3 (auth) and 4 (Excel parity).
 *
 * ============================================================================
 * RUNNING THIS SUITE — read this before `pnpm test:e2e`
 * ============================================================================
 *
 * There is NO `docker-compose.test.yml` in this repository, by design: the isolated stack is
 * `OracleTestContainer` (see `AGENTS.md` -> `isolated_stack_file`), a Testcontainers-driven
 * `gvenzl/oracle-free:23-slim` that the JUnit tier-2 suite owns. It boots in ~95s cold, has no
 * host-port contract, and its schema is created imperatively by `AbstractOracleIT` +
 * `MirrorFixtureLoader` — so Playwright cannot start it with a `webServer` line, and the local
 * DEV stack (`BackEnd/local-infra/docker-compose.yml`, port 1522) must NEVER be substituted:
 * the fixtures TRUNCATE tables.
 *
 * `webServer` below therefore starts exactly the half Playwright can own — the Vite dev server
 * — and `globalSetup` (`e2e/fixtures/seed.ts`) hard-fails the run if the other half is absent.
 * Nothing in this suite stubs the API; there is no "green without a backend" mode.
 *
 * The backend half, brought up by hand in another terminal:
 *
 *   1. Start a backend on an Oracle carrying the V1..V5 migrations, the TEST-classpath
 *      `V7__seed_test_users.sql`, the `MirrorFixtureLoader` fixtures and a completed
 *      `MartRefreshUseCase.refresh()`. It must listen on `E2E_API_BASE_URL`'s port (8081 by
 *      default) and it must be configured with:
 *
 *          incident.cors.allowed-origins = http://localhost:5175
 *
 *      That value is the reason `PORT` below is 5175 and not Vite's default 5173: it is the
 *      only origin the backend will return `Access-Control-Allow-Credentials: true` for, and
 *      without that header the `HttpOnly` refresh cookie can never be set, so
 *      `/auth/refresh-token` — and every reload-time session restore — cannot work.
 *      localhost:5175 -> localhost:8081 is cross-ORIGIN but same-SITE (ports are not part of a
 *      site), so a `SameSite=Lax` refresh cookie still travels.
 *
 *   2. Export the credentials. They are NEVER hardcoded here or in a spec:
 *
 *          export E2E_PASSWORD_OPS_MANAGER=...      # ops.manager
 *          export E2E_PASSWORD_EXEC_USER=...        # exec.user
 *          export E2E_PASSWORD_WALLBOARD_USER=...   # wallboard.user
 *          export E2E_PASSWORD_ROTATE_ME=...        # rotate.me
 *
 *      (or a single `E2E_PASSWORD` if the stack was seeded with one shared password.)
 *      The values are the public test fixtures documented at the top of
 *      `V7__seed_test_users.sql`; they are read from the environment so that this repository
 *      never carries a password literal, not because they are secret.
 *
 *   3. `pnpm test:e2e`.
 *
 * Overridable environment:
 *   E2E_BASE_URL          SPA origin           default http://localhost:5175
 *   E2E_API_BASE_URL      backend API root     default http://localhost:8081/api
 *   E2E_REDMINE_BASE_URL  deep-link base       default https://redmine.example.com
 *
 * Screenshots land in `e2e/screenshots/`, which is gitignored ALONGSIDE `test-results/` and
 * `playwright-report/`: every capture renders real incident subjects, customer names and staff
 * names off the seeded mirror. Commit the specs; never commit the evidence.
 */

const APP_BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5175'
const API_BASE_URL = process.env.E2E_API_BASE_URL ?? 'http://localhost:8081/api'
const REDMINE_BASE_URL = process.env.E2E_REDMINE_BASE_URL ?? 'https://redmine.example.com'

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results',
  globalSetup: './e2e/fixtures/seed.ts',

  /**
   * ONE worker, and no retries. Every spec shares a single backend over a single seeded
   * Oracle; parallel workers would race the same mart, and a retry that "fixes" a failure
   * against shared mutable state is a false green, which is the one outcome tier 3 must not
   * produce.
   */
  workers: 1,
  fullyParallel: false,
  retries: 0,
  forbidOnly: !!process.env.CI,

  // The backend is a cold JVM over Oracle; first-query latency is real, not a hang.
  timeout: 60_000,
  expect: { timeout: 15_000 },

  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],

  use: {
    baseURL: APP_BASE_URL,
    trace: 'retain-on-failure',
    video: 'off',
    // Deterministic viewport so the walkthrough captures are comparable run to run, and so the
    // heatmap/table drill-through assertions reason about a known layout.
    viewport: { width: 1440, height: 900 },
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: {
    // `--port 5175 --strictPort`: a silent fallback to 5176 would put the SPA on an origin the
    // backend's CORS allowlist does not contain, and every spec would fail on a refresh-cookie
    // error rather than on the port mismatch that actually caused it.
    command: 'pnpm vite --port 5175 --strictPort',
    url: APP_BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      // The SPA's `apiRequest` defaults to a same-origin `/api`; there is no Vite proxy, so the
      // backend origin has to be injected here.
      VITE_API_BASE_URL: API_BASE_URL,
      VITE_REDMINE_BASE_URL: REDMINE_BASE_URL,
    },
  },
})
