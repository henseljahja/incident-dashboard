import { config } from '@vue/test-utils'
import type { App } from 'vue'
import { beforeEach } from 'vitest'

import { DEFAULT_LOCALE, LOCALE_STORAGE_KEY, i18n } from '@/i18n'

/**
 * Global vitest setup: every component mount gets the app's i18n instance, pinned to English.
 *
 * Pinned, not merely defaulted: the existing specs assert English copy ("Page not found",
 * "5 worse than the previous period."), and they keep proving that copy only if no earlier test
 * in the same worker can leave the shared instance on Indonesian. Specs that assert Indonesian
 * switch the locale explicitly, inside the test.
 *
 * The SAME instance the app uses, not a fresh one per mount, because `stores/locale.ts` drives
 * `i18n.global.locale` directly — a component on a different instance would never see the
 * switch the store spec is asserting.
 *
 * Installed through a thin wrapper that restores `app.unmount`. vue-i18n wraps it to call
 * `i18n.dispose()`, which stops the shared global scope; in the browser the app never unmounts,
 * but in a spec every `wrapper.unmount()` would otherwise freeze the catalog for every later
 * test in the file.
 */
const sharedI18n = {
  install(app: App) {
    const unmount = app.unmount
    void i18n.install(app)
    app.unmount = unmount
  },
}

config.global.plugins = [...(config.global.plugins ?? []), sharedI18n]

beforeEach(() => {
  i18n.global.locale.value = DEFAULT_LOCALE
  document.documentElement.lang = DEFAULT_LOCALE
  try {
    localStorage.removeItem(LOCALE_STORAGE_KEY)
  } catch {
    // A spec that stubbed storage to throw restores it itself.
  }
})
