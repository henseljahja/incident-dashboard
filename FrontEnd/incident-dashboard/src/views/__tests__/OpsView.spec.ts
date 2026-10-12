import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import type { IncidentRow, PageResponse } from '@/apis/incidents'
import type {
  KpiResponse,
  MatrixWire,
  ScopeCountsResponse,
  SlaBucketDto,
  TrendPeriod,
} from '@/apis/metrics'
import { useAuthStore } from '@/stores/auth'
import { useFiltersStore } from '@/stores/filters'

vi.mock('@/apis/incidents', async () => {
  const actual = await vi.importActual<typeof import('@/apis/incidents')>('@/apis/incidents')
  return {
    ...actual,
    fetchIncidents: vi.fn<(params: URLSearchParams) => Promise<PageResponse<IncidentRow>>>(),
  }
})
vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return {
    ...actual,
    fetchKpi: vi.fn<(params: URLSearchParams) => Promise<KpiResponse>>(),
    fetchMatrix: vi.fn<(params: URLSearchParams) => Promise<MatrixWire>>(),
    fetchScopeCounts: vi.fn<(params?: URLSearchParams) => Promise<ScopeCountsResponse>>(),
    fetchSlaBuckets: vi.fn<(params: URLSearchParams) => Promise<SlaBucketDto[]>>(),
    fetchTrend: vi.fn<(params: URLSearchParams, granularity?: string) => Promise<TrendPeriod[]>>(),
  }
})

import { fetchIncidents } from '@/apis/incidents'
import {
  fetchKpi,
  fetchMatrix,
  fetchScopeCounts,
  fetchSlaBuckets,
  fetchTrend,
} from '@/apis/metrics'

import OpsView from '../OpsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/ops', component: { template: '<div />' }, meta: { scope: 'INCIDENT' } },
    { path: '/problem', component: { template: '<div />' }, meta: { scope: 'PROBLEM' } },
    {
      path: '/service-requests',
      component: { template: '<div />' },
      meta: { scope: 'SERVICE_REQUEST' },
    },
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ],
})

function row(overrides: Partial<IncidentRow> = {}): IncidentRow {
  return {
    NO_INCIDENT: 4021,
    SUBJECT: 'Payment gateway timeout',
    TRACKER: 'Incident',
    STATUS: 'Open',
    PRIORITY: 'High',
    ...overrides,
  }
}

// jsdom has no matchMedia, and PrimeVue's DatePicker (two of them, in the filter bar) calls it
// on mount to decide its touch/desktop presentation. Stubbed rather than stubbing the whole
// filter bar out, because a stubbed filter bar would not exercise the store writes these tests
// are about.
beforeEach(async () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
  vi.resetAllMocks()
  vi.mocked(fetchIncidents).mockResolvedValue({
    content: [row()],
    page: 0,
    size: 50,
    totalElements: 1,
  } as PageResponse<IncidentRow>)
  vi.mocked(fetchKpi).mockResolvedValue({
    windowFrom: null,
    windowTo: null,
    windowDays: 0,
    backlog: 1,
    open: 1,
    breached: 0,
    atRisk: 0,
    mttrWorkaroundDays: null,
    mttrPermanentDays: null,
    mttrGapDays: null,
    workaroundDebt: 0,
    closed: 0,
    previousPeriod: null,
  } as unknown as KpiResponse)
  vi.mocked(fetchMatrix).mockResolvedValue([])
  vi.mocked(fetchScopeCounts).mockResolvedValue({
    scopes: [
      { scope: 'INCIDENT', open: 1, closed: 0, breached: 0, atRisk: 0, total: 1 },
      { scope: 'PROBLEM', open: 2, closed: 0, breached: 0, atRisk: 0, total: 2 },
      { scope: 'SERVICE_REQUEST', open: 3, closed: 0, breached: 1, atRisk: 0, total: 3 },
    ],
  })
  Element.prototype.scrollIntoView = vi.fn<() => void>()
  vi.mocked(fetchSlaBuckets).mockResolvedValue([])
  vi.mocked(fetchTrend).mockResolvedValue([])

  await router.push('/ops')
  await router.isReady()
})

// Every mount listens to the one shared router. A wrapper left alive by an earlier test would
// react to the next test's navigation and fire requests into its mocks.
const mounted: { unmount: () => void }[] = []
afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

async function mountOps(path = '/ops') {
  await router.push(path)
  setActivePinia(createPinia())
  useAuthStore().menus = [
    { menuId: 'incident.ops', label: 'Operations', route: '/ops', privileges: ['view', 'export'] },
    { menuId: 'incident.problem', label: 'Problem', route: '/problem', privileges: ['view'] },
    { menuId: 'incident.sr', label: 'SR', route: '/service-requests', privileges: ['view'] },
  ]
  // A fresh QueryClient per mount, with retries off: a retrying client turns an assertion
  // failure into a timeout and hides which query actually broke.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const wrapper = mount(OpsView, {
    global: {
      plugins: [router, PrimeVue, [VueQueryPlugin, { queryClient }]],
      // chart.js binds a ResizeObserver on update and jsdom has none. Stubbed rather than
      // polyfilled: this file is about the drawer's lifecycle, and a real canvas renderer here
      // would only add a second thing that can fail for reasons unrelated to the assertion.
      stubs: { CreatedClosedTrend: true },
    },
  })
  await flushPromises()
  mounted.push(wrapper)
  return wrapper
}

/**
 * The drawer used to stay open across a filter change, showing an incident the table beneath it
 * no longer contained. Asserted here rather than in the browser: PrimeVue's Drawer unmounts on a
 * CSS transition end, and a throttled or background tab never fires it — so the rendered DOM is
 * not a reliable witness for this behaviour, but the component's own state is.
 */
describe('OpsView detail drawer', () => {
  it('closes when the filters change', async () => {
    const wrapper = await mountOps()
    const filters = useFiltersStore()

    await wrapper.get('[data-testid="incident-row-select"]').trigger('click')
    expect(wrapper.findComponent({ name: 'IncidentDetailDrawer' }).props('visible')).toBe(true)

    filters.hasWorkaround = true
    await flushPromises()

    expect(wrapper.findComponent({ name: 'IncidentDetailDrawer' }).props('visible')).toBe(false)
  })

  it('stays open while paging, which is navigation inside the same result set', async () => {
    const wrapper = await mountOps()
    const filters = useFiltersStore()

    await wrapper.get('[data-testid="incident-row-select"]').trigger('click')
    expect(wrapper.findComponent({ name: 'IncidentDetailDrawer' }).props('visible')).toBe(true)

    filters.page = 1
    await flushPromises()

    // Closing here would be gratuitous — the row the user opened is still the row they asked for.
    expect(wrapper.findComponent({ name: 'IncidentDetailDrawer' }).props('visible')).toBe(true)
  })
})

describe('OpsView definitions', () => {
  it('every component has an info hint', async () => {
    const wrapper = await mountOps()

    const terms = wrapper
      .findAll('[data-testid="info-hint"]')
      .map((hint) => hint.attributes('data-term'))

    // KPI tiles, the two charts rendered here, freshness, and the three explained table headers.
    // (CreatedClosedTrend is stubbed in this file for its canvas; its ⓘ is covered in
    // i18n-rendering.spec.ts.)
    expect(terms).toEqual(
      expect.arrayContaining([
        'open',
        'closed',
        'breached',
        'atRisk',
        'mttr',
        'workaroundDebt',
        'matrix',
        'slaBuckets',
        'freshness',
        'noRedmine',
        'slaColumn',
        'aging',
        'groupCounts',
      ]),
    )
    // Each ⓘ is its own button, never nested inside a tile's button.
    for (const hint of wrapper.findAll('[data-testid="info-hint"]')) {
      expect(hint.element.parentElement?.closest('button')).toBeNull()
    }
  })

  it('announces the table total politely once the page has loaded', async () => {
    const wrapper = await mountOps()

    const announcement = wrapper.get('[data-testid="table-total-announcement"]')
    expect(announcement.attributes('aria-live')).toBe('polite')
    expect(announcement.text()).toBe('1 incident matches the current filters')
  })

  it('charts ignore the tile facets while the table and tiles follow them', async () => {
    await mountOps()
    const filters = useFiltersStore()

    filters.toggleSla('BREACHED')
    await flushPromises()

    expect(vi.mocked(fetchKpi).mock.lastCall![0].get('sla')).toBe('BREACHED')
    expect(vi.mocked(fetchIncidents).mock.lastCall![0].get('sla')).toBe('BREACHED')
    for (const call of vi.mocked(fetchMatrix).mock.calls) expect(call[0].has('sla')).toBe(false)
    for (const call of vi.mocked(fetchSlaBuckets).mock.calls) expect(call[0].has('sla')).toBe(false)
  })
})

/**
 * TM09 / TM10 — one view, three ticket groups. The scope comes from the route, goes to the server
 * on every request, and is not something a filter action can undo.
 */
describe('OpsView scoped pages', () => {
  const lastParams = (mock: { mock: { lastCall?: unknown[] } }) =>
    (mock.mock.lastCall as [URLSearchParams])[0]

  it('Incident page: asks for scope=INCIDENT everywhere and shows the matrix', async () => {
    const wrapper = await mountOps('/ops')

    for (const mock of [fetchIncidents, fetchKpi, fetchMatrix]) {
      expect(lastParams(vi.mocked(mock)).get('scope')).toBe('INCIDENT')
    }
    expect(wrapper.find('[data-testid="impact-urgency-heatmap"]').exists()).toBe(true)
    // The tracker control is hidden: Incident has the one tracker.
    expect(wrapper.find('#filter-tracker').exists()).toBe(false)
  })

  it('names the group tiles at the top and marks the current page', async () => {
    const wrapper = await mountOps('/problem')

    const tiles = wrapper.findAll('[data-testid="scope-counts"] [data-scope]')
    expect(tiles.map((tile) => tile.attributes('aria-current'))).toEqual([
      undefined,
      'page',
      undefined,
    ])
    expect(tiles[2]!.get('[data-testid="scope-open"]').text()).toBe('3')
  })

  it('Problem page: scope=PROBLEM, no matrix, no matrix request, no tracker control', async () => {
    const wrapper = await mountOps('/problem')

    expect(lastParams(vi.mocked(fetchIncidents)).get('scope')).toBe('PROBLEM')
    expect(lastParams(vi.mocked(fetchKpi)).get('scope')).toBe('PROBLEM')
    expect(wrapper.find('[data-testid="impact-urgency-heatmap"]').exists()).toBe(false)
    expect(fetchMatrix).not.toHaveBeenCalled()
    expect(wrapper.find('#filter-tracker').exists()).toBe(false)
    // The page title is the menu's translated label.
    expect(wrapper.get('h1').text()).toBe('Problem')
  })

  it('Service Request page: scope=SERVICE_REQUEST, no matrix, the three SR trackers', async () => {
    const wrapper = await mountOps('/service-requests')

    expect(lastParams(vi.mocked(fetchIncidents)).get('scope')).toBe('SERVICE_REQUEST')
    expect(wrapper.find('[data-testid="impact-urgency-heatmap"]').exists()).toBe(false)
    expect(wrapper.get('h1').text()).toBe('Service Request')
    const options = wrapper.findComponent({ name: 'MultiSelect' }).props('options') as string[]
    expect(options).toEqual(['SR - Infra', 'SR - BAU', 'SR - Patch'])
  })

  it('keeps the table and the tiles on the same scope', async () => {
    await mountOps('/problem')

    expect(lastParams(vi.mocked(fetchKpi)).get('scope')).toBe(
      lastParams(vi.mocked(fetchIncidents)).get('scope'),
    )
  })

  it('does not carry filters from one page to the next', async () => {
    const wrapper = await mountOps('/ops')
    const filters = useFiltersStore()
    filters.priority = ['Critical']
    filters.page = 2
    await flushPromises()

    await router.push('/problem')
    await flushPromises()

    expect(filters.scope).toBe('PROBLEM')
    expect(filters.priority).toEqual([])
    expect(filters.page).toBe(0)
    expect(lastParams(vi.mocked(fetchIncidents)).get('scope')).toBe('PROBLEM')
    expect(lastParams(vi.mocked(fetchIncidents)).has('priority')).toBe(false)
    expect(wrapper.find('[data-testid="impact-urgency-heatmap"]').exists()).toBe(false)
  })

  it('"Clear all" cannot drop the scope', async () => {
    const wrapper = await mountOps('/problem')
    const filters = useFiltersStore()
    filters.priority = ['High']
    await flushPromises()

    await wrapper.get('[data-testid="filter-clear-all"]').trigger('click')
    await flushPromises()

    expect(filters.priority).toEqual([])
    expect(filters.scope).toBe('PROBLEM')
    expect(lastParams(vi.mocked(fetchIncidents)).get('scope')).toBe('PROBLEM')
    expect(lastParams(vi.mocked(fetchKpi)).get('scope')).toBe('PROBLEM')
  })

  it('an edited URL cannot switch the page to another scope', async () => {
    await router.push('/problem?scope=INCIDENT&priority=High')
    await mountOps('/problem?scope=INCIDENT&priority=High')

    const sent = lastParams(vi.mocked(fetchIncidents))
    expect(sent.get('scope')).toBe('PROBLEM')
    expect(sent.get('priority')).toBe('High')
    // And it does not write the scope back into the address bar.
    expect(router.currentRoute.value.query.scope).toBeUndefined()
  })

  describe('missing impact or urgency', () => {
    beforeEach(() => {
      vi.mocked(fetchMatrix).mockResolvedValue({
        cells: [{ impact: 'Unclassified', urgency: 'High', count: 4 }],
        missingCount: 4,
      })
    })

    it('shows the count on the Incident page and narrows the table to exactly those tickets', async () => {
      const wrapper = await mountOps('/ops')

      const link = wrapper.get('[data-testid="heatmap-missing-link"]')
      expect(link.text()).toBe('4 tickets missing impact or urgency')

      await link.trigger('click')
      await flushPromises()

      const filters = useFiltersStore()
      expect(filters.matrixMissing).toBe(true)
      expect(lastParams(vi.mocked(fetchIncidents)).get('matrixMissing')).toBe('true')
      // The matrix is the source of the count, so it is not narrowed by its own link.
      expect(lastParams(vi.mocked(fetchMatrix)).has('matrixMissing')).toBe(false)
      expect(wrapper.text()).toContain('Missing impact or urgency')
      expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    })

    it('lets the chip remove the narrowing', async () => {
      const wrapper = await mountOps('/ops')
      await wrapper.get('[data-testid="heatmap-missing-link"]').trigger('click')
      await flushPromises()

      await wrapper
        .get('button[aria-label="Remove filter Missing impact or urgency"]')
        .trigger('click')
      await flushPromises()

      expect(useFiltersStore().matrixMissing).toBe(false)
      expect(lastParams(vi.mocked(fetchIncidents)).has('matrixMissing')).toBe(false)
    })

    it('shows no link when nothing is missing', async () => {
      vi.mocked(fetchMatrix).mockResolvedValue({ cells: [], missingCount: 0 })
      const wrapper = await mountOps('/ops')

      expect(wrapper.find('[data-testid="heatmap-missing"]').exists()).toBe(false)
    })

    it('still understands the older bare-array matrix response', async () => {
      vi.mocked(fetchMatrix).mockResolvedValue([{ impact: 'High', urgency: 'High', count: 2 }])
      const wrapper = await mountOps('/ops')

      expect(wrapper.findAll('[data-testid="heatmap-cell"]')).toHaveLength(1)
      expect(wrapper.find('[data-testid="heatmap-missing"]').exists()).toBe(false)
    })
  })

  describe('states', () => {
    it('an empty scope shows the empty table, with the group tiles still there', async () => {
      vi.mocked(fetchIncidents).mockResolvedValue({
        content: [],
        page: 0,
        size: 25,
        totalElements: 0,
      } as unknown as PageResponse<IncidentRow>)
      vi.mocked(fetchScopeCounts).mockResolvedValue({
        scopes: [
          { scope: 'INCIDENT', open: 1, closed: 0, breached: 0, atRisk: 0, total: 1 },
          { scope: 'PROBLEM', open: 0, closed: 0, breached: 0, atRisk: 0, total: 0 },
          { scope: 'SERVICE_REQUEST', open: 3, closed: 0, breached: 1, atRisk: 0, total: 3 },
        ],
      })
      const wrapper = await mountOps('/problem')

      expect(wrapper.text()).toContain('No incidents match the current filters.')
      expect(wrapper.get('[data-scope="PROBLEM"] [data-testid="scope-none"]').text()).toBe(
        'None yet',
      )
      expect(wrapper.get('[data-testid="table-total-announcement"]').text()).toBe(
        '0 incidents match the current filters',
      )
    })

    it('a failed group-count request is reported on its own and does not take the page down', async () => {
      vi.mocked(fetchScopeCounts).mockRejectedValue(new Error('boom'))
      const wrapper = await mountOps('/problem')

      expect(wrapper.get('[data-testid="scope-counts-error"]').attributes('role')).toBe('alert')
      expect(wrapper.find('[data-testid="incident-row-select"]').exists()).toBe(true)
    })

    it('shows the loading skeleton for the group tiles while they are fetched', async () => {
      vi.mocked(fetchScopeCounts).mockReturnValue(new Promise(() => {}))
      const wrapper = await mountOps('/problem')

      expect(wrapper.findAll('[data-testid="scope-skeleton"]')).toHaveLength(3)
    })

    it('populated: the rows of the scope are in the table', async () => {
      const wrapper = await mountOps('/problem')

      expect(wrapper.findAll('[data-testid="incident-row-select"]')).toHaveLength(1)
    })
  })
})
