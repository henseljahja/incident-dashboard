import { defineStore } from 'pinia'
import { en as primeEn } from 'primelocale/js/en.js'
import { id as primeId } from 'primelocale/js/id.js'
import type { PrimeVueConfiguration } from 'primevue/config'
import { getCurrentInstance, hasInjectionContext, ref } from 'vue'

import {
  DEFAULT_LOCALE,
  LOCALE_STORAGE_KEY,
  i18n,
  isAppLocale,
  readStoredLocale,
  type AppLocale,
} from '@/i18n'

/** PrimeVue's own labels (calendar months, paginator, aria) for each app locale. */
const PRIME_LOCALES: Record<AppLocale, NonNullable<PrimeVueConfiguration['locale']>> = {
  en: primeEn,
  id: primeId,
}

/**
 * PrimeVue's reactive config, when this store is first created inside a component that has it.
 *
 * Read off the app's global properties rather than through `usePrimeVue()`: `usePrimeVue`
 * throws — after Vue has already logged an injection warning — on an app mounted without
 * PrimeVue, which is how several component specs mount. At boot there is no component on the
 * stack, so `main.ts` hands `usePrimeVue()` over through `attachPrimeVue` instead.
 */
function resolvePrimeVue(): { config: PrimeVueConfiguration } | null {
  if (!hasInjectionContext()) return null
  const primevue = getCurrentInstance()?.appContext.config.globalProperties.$primevue as
    { config: PrimeVueConfiguration } | undefined
  return primevue ?? null
}

/**
 * The UI language — the one place it changes.
 *
 * Browser-local only (`localStorage['incident.locale']`), never a server column: a language
 * preference is not account data, and the wallboard has no account at all. Everything that has
 * to agree on the language is switched together here: the i18n catalog, `<html lang>` (so a
 * screen reader pronounces Indonesian as Indonesian) and PrimeVue's own calendar/paginator
 * labels.
 */
export const useLocaleStore = defineStore('locale', () => {
  let primevue = resolvePrimeVue()

  const locale = ref<AppLocale>(readStoredLocale())

  function apply(next: AppLocale) {
    i18n.global.locale.value = next
    if (typeof document !== 'undefined') {
      document.documentElement.lang = next
    }
    if (primevue) {
      primevue.config.locale = { ...primevue.config.locale, ...PRIME_LOCALES[next] }
    }
  }

  /**
   * Anything that is not `en`/`id` falls back to `en` rather than being written through — the
   * store is typed, but a value can still arrive untyped from a template or a devtools poke.
   *
   * The write is best-effort: when storage throws (private mode), the switch still works for
   * the rest of the session, in memory, with no error shown.
   */
  function setLocale(next: AppLocale) {
    const resolved = isAppLocale(next) ? next : DEFAULT_LOCALE
    locale.value = resolved
    try {
      globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, resolved)
    } catch {
      // Storage unavailable — the in-memory locale is still correct for this session.
    }
    apply(resolved)
  }

  /**
   * Boot-time hook for `main.ts`, which creates the store before any component exists and so
   * has to hand PrimeVue's config over explicitly.
   */
  function attachPrimeVue(instance: { config: PrimeVueConfiguration }) {
    primevue = instance
    apply(locale.value)
  }

  apply(locale.value)

  return { locale, setLocale, attachPrimeVue }
})
