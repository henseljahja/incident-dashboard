import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { useFiltersStore } from '../filters'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('serialize / applyFromQueryString round-trip', () => {
  it('round-trips date filters', () => {
    const filters = useFiltersStore()
    filters.dateFrom = '2026-01-01'
    filters.dateTo = '2026-06-30'

    const restored = useFiltersStore()
    restored.applyFromQueryString(filters.serialize())

    expect(restored.dateFrom).toBe('2026-01-01')
    expect(restored.dateTo).toBe('2026-06-30')
  })

  it('round-trips multi-select filters (repeated query keys)', () => {
    const filters = useFiltersStore()
    filters.tracker = ['Incident', 'SR - Infra']
    filters.priority = ['High', 'Critical']

    const restored = useFiltersStore()
    restored.applyFromQueryString(filters.serialize())

    expect(restored.tracker).toEqual(['Incident', 'SR - Infra'])
    expect(restored.priority).toEqual(['High', 'Critical'])
  })

  it('round-trips text filters (sort/direction)', () => {
    const filters = useFiltersStore()
    filters.sort = 'AGING_NEW'
    filters.direction = 'desc'

    const restored = useFiltersStore()
    restored.applyFromQueryString(filters.serialize())

    expect(restored.sort).toBe('AGING_NEW')
    expect(restored.direction).toBe('desc')
  })

  it('round-trips a boolean filter and paging state', () => {
    const filters = useFiltersStore()
    filters.hasWorkaround = true
    filters.page = 3
    filters.size = 50

    const restored = useFiltersStore()
    restored.applyFromQueryString(filters.serialize())

    expect(restored.hasWorkaround).toBe(true)
    expect(restored.page).toBe(3)
    expect(restored.size).toBe(50)
  })

  it('round-trips a full multi-kind selection in one pass — a colleague pasting the URL back', () => {
    const filters = useFiltersStore()
    filters.dateFrom = '2026-01-01'
    filters.dateTo = '2026-06-30'
    filters.tracker = ['Incident']
    filters.stage = ['Open', 'Pending']
    filters.priority = ['Critical']
    filters.institusi = ['Bank A']
    filters.category = ['Network']
    filters.hasWorkaround = false
    filters.sort = 'SLA_RATIO'
    filters.direction = 'asc'
    filters.page = 2
    filters.size = 100

    const queryString = filters.serialize()
    const restored = useFiltersStore()
    restored.applyFromQueryString(queryString)

    expect(restored.dateFrom).toBe('2026-01-01')
    expect(restored.dateTo).toBe('2026-06-30')
    expect(restored.tracker).toEqual(['Incident'])
    expect(restored.stage).toEqual(['Open', 'Pending'])
    expect(restored.priority).toEqual(['Critical'])
    expect(restored.institusi).toEqual(['Bank A'])
    expect(restored.category).toEqual(['Network'])
    expect(restored.hasWorkaround).toBe(false)
    expect(restored.sort).toBe('SLA_RATIO')
    expect(restored.direction).toBe('asc')
    expect(restored.page).toBe(2)
    expect(restored.size).toBe(100)
  })
})

describe('applyFromQueryString — unknown keys', () => {
  it('ignores an unknown query key rather than throwing', () => {
    const filters = useFiltersStore()

    expect(() =>
      filters.applyFromQueryString('dateFrom=2026-01-01&mttd=should-be-ignored&bogus=1'),
    ).not.toThrow()

    expect(filters.dateFrom).toBe('2026-01-01')
  })
})

describe('toFilterParams — export payload shape', () => {
  it('excludes page and size — the export endpoint does not accept them', () => {
    const filters = useFiltersStore()
    filters.tracker = ['Incident']
    filters.page = 5
    filters.size = 100

    const params = filters.toFilterParams()

    expect(params.has('page')).toBe(false)
    expect(params.has('size')).toBe(false)
    expect(params.getAll('tracker')).toEqual(['Incident'])
  })
})

describe('status / sla facets (KPI tiles)', () => {
  it('round-trips both facets through the url', () => {
    const filters = useFiltersStore()
    filters.status = 'CLOSED'
    filters.sla = 'AT_RISK'

    const qs = filters.serialize()
    const restored = useFiltersStore()
    restored.reset()
    restored.applyFromQueryString(qs)

    expect(restored.status).toBe('CLOSED')
    expect(restored.sla).toBe('AT_RISK')
    expect(restored.toFilterParams().get('status')).toBe('CLOSED')
    expect(restored.toFilterParams().get('sla')).toBe('AT_RISK')
  })

  it('drops unknown facet values from the url', () => {
    const filters = useFiltersStore()

    expect(() =>
      filters.applyFromQueryString('status=PENDING&sla=breached&tracker=Incident'),
    ).not.toThrow()

    // Unknown (and wrong-case) values are dropped, not passed to the API as a 400.
    expect(filters.status).toBe(null)
    expect(filters.sla).toBe(null)
    expect(filters.tracker).toEqual(['Incident'])
    const params = filters.toFilterParams()
    expect(params.has('status')).toBe(false)
    expect(params.has('sla')).toBe(false)
    expect(filters.serialize()).not.toMatch(/status|sla/)
  })

  it('toggles a facet on, replaces it with its sibling, and clears it on a second click', () => {
    const filters = useFiltersStore()
    filters.page = 3

    filters.toggleSla('BREACHED')
    expect(filters.sla).toBe('BREACHED')
    expect(filters.page).toBe(0)

    filters.toggleSla('AT_RISK')
    expect(filters.sla).toBe('AT_RISK')

    filters.toggleStatus('OPEN')
    expect(filters.status).toBe('OPEN')
    expect(filters.sla).toBe('AT_RISK')

    filters.page = 2
    filters.toggleStatus('OPEN')
    expect(filters.status).toBe(null)
    expect(filters.page).toBe(0)
  })

  it('reset clears both facets', () => {
    const filters = useFiltersStore()
    filters.status = 'OPEN'
    filters.sla = 'BREACHED'

    filters.reset()

    expect(filters.status).toBe(null)
    expect(filters.sla).toBe(null)
  })

  it('leaves the facets out of the base params the charts read', () => {
    const filters = useFiltersStore()
    filters.tracker = ['Incident']
    filters.status = 'OPEN'
    filters.sla = 'BREACHED'

    const base = filters.toBaseFilterParams()

    expect(base.getAll('tracker')).toEqual(['Incident'])
    expect(base.has('status')).toBe(false)
    expect(base.has('sla')).toBe(false)
  })
})

/**
 * TM10 — the scope is a server-side param that the page owns. Anything the user can do to the
 * filters ("Clear all", a pasted or hand-edited URL) must leave it alone, or the Problem page
 * could quietly start listing incidents.
 */
describe('scope', () => {
  it('is sent on every filter-bearing request, table and charts alike', () => {
    const filters = useFiltersStore()
    filters.setScope('PROBLEM')
    filters.toggleSla('BREACHED')

    expect(filters.toFilterParams().get('scope')).toBe('PROBLEM')
    expect(filters.toBaseFilterParams().get('scope')).toBe('PROBLEM')
    expect(filters.toPageParams().get('scope')).toBe('PROBLEM')
    expect(filters.toPageParams().get('page')).toBe('0')
  })

  it('sends no scope until a page sets one', () => {
    expect(useFiltersStore().toFilterParams().has('scope')).toBe(false)
  })

  it('survives reset(), which is what "Clear all" calls', () => {
    const filters = useFiltersStore()
    filters.setScope('SERVICE_REQUEST')
    filters.tracker = ['SR - BAU']
    filters.matrixMissing = true

    filters.reset()

    expect(filters.scope).toBe('SERVICE_REQUEST')
    expect(filters.toFilterParams().get('scope')).toBe('SERVICE_REQUEST')
    expect(filters.tracker).toEqual([])
    expect(filters.matrixMissing).toBe(false)
  })

  it('cannot be overwritten or dropped by an edited URL', () => {
    const filters = useFiltersStore()
    filters.setScope('PROBLEM')

    filters.applyFromQueryString('scope=INCIDENT&priority=High')
    expect(filters.scope).toBe('PROBLEM')
    expect(filters.priority).toEqual(['High'])

    filters.applyFromQueryString('')
    expect(filters.scope).toBe('PROBLEM')
  })

  it('stays out of the address bar, because the path already says it', () => {
    const filters = useFiltersStore()
    filters.setScope('PROBLEM')
    filters.priority = ['High']

    expect(filters.serialize()).not.toContain('scope')
    expect(filters.serialize()).toContain('priority=High')
  })

  it('starts the new page clean when it changes, and does nothing when it does not', () => {
    const filters = useFiltersStore()
    filters.setScope('INCIDENT')
    filters.priority = ['Critical']
    filters.page = 3

    filters.setScope('INCIDENT')
    expect(filters.priority).toEqual(['Critical'])
    expect(filters.page).toBe(3)

    filters.setScope('PROBLEM')
    expect(filters.priority).toEqual([])
    expect(filters.page).toBe(0)
    expect(filters.scope).toBe('PROBLEM')
  })

  it('drops a tracker that does not belong to the page from a stale link', () => {
    const filters = useFiltersStore()
    filters.setScope('SERVICE_REQUEST')

    filters.applyFromQueryString('tracker=Incident&tracker=SR%20-%20BAU')

    expect(filters.tracker).toEqual(['SR - BAU'])
  })
})

describe('matrixMissing', () => {
  it('narrows the table and the tiles, but never the matrix it is counted from', () => {
    const filters = useFiltersStore()
    filters.matrixMissing = true

    expect(filters.toFilterParams().get('matrixMissing')).toBe('true')
    expect(filters.toPageParams().get('matrixMissing')).toBe('true')
    expect(filters.toBaseFilterParams().has('matrixMissing')).toBe(false)
  })

  it('round-trips through the URL', () => {
    const filters = useFiltersStore()
    filters.matrixMissing = true

    const url = filters.serialize()
    filters.reset()
    expect(filters.matrixMissing).toBe(false)

    filters.applyFromQueryString(url)
    expect(filters.matrixMissing).toBe(true)
  })
})

describe('search / source / assignedTo', () => {
  it('serialises, restores and resets the three list filters', () => {
    const filters = useFiltersStore()
    filters.search = '  vpn  '
    filters.source = ['External']
    filters.assignedTo = ['Budi', 'Siti']

    const params = filters.toFilterParams()
    expect(params.get('search')).toBe('vpn')
    expect(params.getAll('source')).toEqual(['External'])
    expect(params.getAll('assignedTo')).toEqual(['Budi', 'Siti'])

    const restored = useFiltersStore()
    restored.applyFromQueryString(filters.serialize())
    expect(restored.search).toBe('vpn')
    expect(restored.assignedTo).toEqual(['Budi', 'Siti'])

    restored.reset()
    expect(restored.search).toBe('')
    expect(restored.source).toEqual([])
    expect(restored.assignedTo).toEqual([])
  })

  it('omits a blank search from the request', () => {
    const filters = useFiltersStore()
    filters.search = '   '
    expect(filters.toFilterParams().has('search')).toBe(false)
  })
})

describe('setPeriod', () => {
  const today = new Date(2026, 9, 12) // 12 Oct 2026, local

  it('30d is an inclusive 30-day window ending today', () => {
    const filters = useFiltersStore()
    filters.page = 3
    filters.setPeriod('30d', today)
    expect(filters.dateFrom).toBe('2026-09-13')
    expect(filters.dateTo).toBe('2026-10-12')
    expect(filters.page).toBe(0)
  })

  it('6m goes back six calendar months and all clears both bounds', () => {
    const filters = useFiltersStore()
    filters.setPeriod('6m', today)
    expect(filters.dateFrom).toBe('2026-04-12')
    filters.setPeriod('all', today)
    expect(filters.dateFrom).toBeNull()
    expect(filters.dateTo).toBeNull()
  })
})
