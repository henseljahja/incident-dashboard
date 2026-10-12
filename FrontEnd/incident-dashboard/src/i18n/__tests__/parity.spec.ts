import { afterEach, describe, expect, it, vi } from 'vitest'

import type { DefinitionTerm } from '@/apis/definitions'

import { i18n } from '..'
import en from '../locales/en.json'
import id from '../locales/id.json'

type Catalog = { [key: string]: string | Catalog }

/** Every leaf key, dotted: `kpi.exec.thisMonth`. */
function leafKeys(catalog: Catalog, prefix = ''): string[] {
  return Object.entries(catalog).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return typeof value === 'string' ? [path] : leafKeys(value, path)
  })
}

function leafAt(catalog: Catalog, path: string): string | undefined {
  const value = path.split('.').reduce<string | Catalog | undefined>((node, part) => {
    return node && typeof node === 'object' ? node[part] : undefined
  }, catalog)
  return typeof value === 'string' ? value : undefined
}

const enKeys = leafKeys(en as Catalog).sort()
const idKeys = leafKeys(id as Catalog).sort()

afterEach(() => {
  vi.restoreAllMocks()
})

describe('locale catalogs', () => {
  it('en and id have exactly the same keys', () => {
    expect(idKeys.filter((key) => !enKeys.includes(key))).toEqual([])
    expect(enKeys.filter((key) => !idKeys.includes(key))).toEqual([])
  })

  it('has no empty message in either language', () => {
    const empty = enKeys.filter((key) => !leafAt(en as Catalog, key) || !leafAt(id as Catalog, key))
    expect(empty).toEqual([])
  })

  it('keeps the same placeholders in both languages', () => {
    const placeholders = (message: string | undefined) =>
      [...(message ?? '').matchAll(/\{(\w+)\}/g)]
        .map((match) => match[1])
        .sort()
        .join(',')
    const mismatched = enKeys.filter(
      (key) =>
        placeholders(leafAt(id as Catalog, key)) !== placeholders(leafAt(en as Catalog, key)),
    )
    expect(mismatched).toEqual([])
  })

  it('compiles every message in both languages without a syntax warning', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const unrendered = (['en', 'id'] as const).flatMap((locale) =>
      enKeys.filter((key) => typeof i18n.global.t(key, {}, { locale }) !== 'string'),
    )
    expect(unrendered).toEqual([])
    expect(warn).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()
  })

  it('defines name, meaning, formula and source for every InfoHint term', () => {
    const terms: DefinitionTerm[] = [
      'open',
      'closed',
      'breached',
      'atRisk',
      'mttr',
      'mttrWorkaround',
      'mttrPermanent',
      'workaroundDebt',
      'trend',
      'topCategories',
      'matrix',
      'slaBuckets',
      'freshness',
      'aging',
      'slaColumn',
      'noRedmine',
      'slaCompliance',
      'groupCounts',
      'matrixMissing',
      'avgResolution',
    ]
    const missing = terms.flatMap((term) =>
      ['name', 'meaning', 'formula', 'source']
        .map((part) => `definitions.${term}.${part}`)
        .filter((key) => !enKeys.includes(key)),
    )
    expect(missing).toEqual([])
  })

  /**
   * A key a component asks for but the catalog lacks renders as the raw key path on screen —
   * in both languages, silently. Scans every static `t('…')` in the source.
   */
  it('contains every static key the source asks for', () => {
    const sources = import.meta.glob<string>(['/src/**/*.{vue,ts}', '!/src/**/__tests__/**'], {
      query: '?raw',
      import: 'default',
      eager: true,
    })
    // Guards the guard: a glob that matched nothing would pass this test vacuously.
    expect(Object.keys(sources).length).toBeGreaterThan(30)
    const missing = new Set<string>()
    for (const [file, text] of Object.entries(sources)) {
      for (const match of text.matchAll(/\bt\(\s*'([a-zA-Z]+\.[\w.]+)'/g)) {
        if (!enKeys.includes(match[1]!)) missing.add(`${match[1]} (${file})`)
      }
      for (const match of text.matchAll(/keypath="([\w.]+)"/g)) {
        if (!enKeys.includes(match[1]!)) missing.add(`${match[1]} (${file})`)
      }
    }
    expect([...missing]).toEqual([])
  })
})
