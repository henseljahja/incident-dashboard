/**
 * The `test` every tier-3 spec imports: Playwright's own, with the UI language pinned.
 *
 * The SPA restores `localStorage['incident.locale']` on boot, and every existing spec asserts
 * English copy ("Sign in", "Operations", "Open in Redmine"). A fresh browser context has no
 * stored preference and would render English today — but "today" is a default, not a promise,
 * so the suite seeds `en` explicitly (TM035).
 *
 * Seeded ONLY when absent: `locale.spec.ts` switches to Indonesian and reloads, and an init
 * script that overwrote the value on every navigation would make that persistence test
 * fail for the wrong reason.
 *
 * A separate module from `seed.ts` because that file is also Playwright's `globalSetup`, which
 * runs outside the test runner.
 */
import { test as base } from '@playwright/test'

/** Mirrors `LOCALE_STORAGE_KEY` in `src/i18n/index.ts`. */
export const LOCALE_STORAGE_KEY = 'incident.locale'

export const test = base.extend({
  page: async ({ page }, use) => {
    await page.addInitScript(
      ([key, locale]) => {
        try {
          if (window.localStorage.getItem(key) === null) window.localStorage.setItem(key, locale)
        } catch {
          // about:blank and other opaque origins have no storage; nothing to seed there.
        }
      },
      [LOCALE_STORAGE_KEY, 'en'] as const,
    )
    // The Vue devtools floating pill (dev server only) sits over tiles and pollutes captures.
    await page.addInitScript(() => {
      const hide = () => {
        const style = document.createElement('style')
        style.textContent = '#__vue-devtools-container__{display:none!important}'
        document.head.appendChild(style)
      }
      if (document.head) hide()
      else document.addEventListener('DOMContentLoaded', hide, { once: true })
    })
    await use(page)
  },
})

export { expect } from '@playwright/test'
