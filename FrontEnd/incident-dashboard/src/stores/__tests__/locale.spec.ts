import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { LOCALE_STORAGE_KEY, i18n } from '@/i18n'

import { useLocaleStore } from '../locale'

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useLocaleStore', () => {
  it('restores a stored supported locale on creation', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'id')

    const store = useLocaleStore()

    expect(store.locale).toBe('id')
    expect(i18n.global.locale.value).toBe('id')
    expect(document.documentElement.lang).toBe('id')
  })

  it('falls back to en for an unsupported stored value', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr')

    const store = useLocaleStore()

    expect(store.locale).toBe('en')
    expect(i18n.global.locale.value).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('falls back to en when reading storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })

    expect(() => useLocaleStore()).not.toThrow()
    expect(useLocaleStore().locale).toBe('en')
  })

  it('still switches for the session when writing storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError')
    })
    const store = useLocaleStore()

    expect(() => store.setLocale('id')).not.toThrow()

    expect(store.locale).toBe('id')
    expect(i18n.global.locale.value).toBe('id')
    expect(document.documentElement.lang).toBe('id')
  })

  it('persists a switch and rejects an unsupported value', () => {
    const store = useLocaleStore()

    store.setLocale('id')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('id')

    // An untyped value arriving from a template or devtools.
    store.setLocale('fr' as never)
    expect(store.locale).toBe('en')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
    expect(i18n.global.locale.value).toBe('en')
  })
})
