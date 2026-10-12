import { createI18n } from 'vue-i18n'

import en from './locales/en.json'
import id from './locales/id.json'

/**
 * The UI's language runtime. Chrome copy only — see `.agents/patterns/i18n.md`.
 *
 * Composition mode (`legacy: false`) so components call `useI18n()` and the locale is a plain
 * ref the locale store can drive. `fallbackLocale: 'en'` means a key missing from `id.json`
 * renders English rather than the raw key path; the parity spec is what stops that from ever
 * shipping, and the dev-only warnings are what make it visible before the spec runs.
 */
export const SUPPORTED_LOCALES = ['en', 'id'] as const

export type AppLocale = (typeof SUPPORTED_LOCALES)[number]

export const DEFAULT_LOCALE: AppLocale = 'en'

/** The one persisted preference in the SPA. Browser-local by design: no server state. */
export const LOCALE_STORAGE_KEY = 'incident.locale'

export function isAppLocale(value: unknown): value is AppLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value)
}

/**
 * The stored locale, or `en`.
 *
 * `localStorage` throws outright in some private-browsing modes and sandboxed webviews, and a
 * value written by some other build (`fr`, `en-US`, garbage) must not boot the app into a
 * locale it has no catalog for. Both collapse to the default, silently — a language preference
 * is never worth an error on screen.
 */
export function readStoredLocale(): AppLocale {
  try {
    const stored = globalThis.localStorage?.getItem(LOCALE_STORAGE_KEY)
    return isAppLocale(stored) ? stored : DEFAULT_LOCALE
  } catch {
    return DEFAULT_LOCALE
  }
}

export const i18n = createI18n({
  legacy: false,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: { en, id },
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV,
})
