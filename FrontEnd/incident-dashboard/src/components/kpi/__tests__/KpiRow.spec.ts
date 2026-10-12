import { VueQueryPlugin, QueryClient } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { KpiResponse } from '@/apis/metrics'
import { useFiltersStore } from '@/stores/filters'

vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return {
    ...actual,
    fetchKpi: vi.fn<(params: URLSearchParams) => Promise<KpiResponse>>(),
  }
})

import { fetchKpi } from '@/apis/metrics'

import IncidentFilterBar from '@/components/filters/IncidentFilterBar.vue'

import KpiRow from '../KpiRow.vue'

function kpiResponse(overrides: Partial<KpiResponse> = {}): KpiResponse {
  return {
    windowFrom: '2026-07-01',
    windowTo: '2026-08-01',
    windowDays: 31,
    backlog: 12,
    open: 8,
    closed: 21,
    breached: 3,
    atRisk: 2,
    mttrDays: 6.4,
    mttrWorkaroundDays: 4.2,
    mttrPermanentDays: 9.5,
    mttrGapDays: 5.3,
    workaroundDebt: 6,
    previousPeriod: null,
    ...overrides,
  }
}

function mountKpiRow() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return mount(KpiRow, {
    global: {
      plugins: [[VueQueryPlugin, { queryClient }]],
    },
  })
}

/** The tile for one KPI, by its `data-kpi` key. */
const tile = (key: string) => `[data-kpi="${key}"]`

beforeEach(() => {
  setActivePinia(createPinia())
  // jsdom has no matchMedia; the filter bar's DatePicker binds a responsive listener on mount.
  window.matchMedia =
    window.matchMedia ??
    ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }))
  vi.resetAllMocks()
})

describe('KpiRow', () => {
  it('renders every KPI figure from the response, with no MTTD anywhere', async () => {
    vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())

    const wrapper = mountKpiRow()
    await flushPromises()

    // Backlog was replaced by Closed (stakeholder notes, AC4.1); this used to assert '12'.
    expect(wrapper.text()).toContain('21') // closed
    expect(wrapper.text()).toContain('8') // open
    expect(wrapper.text()).toContain('3') // breached
    expect(wrapper.text()).toContain('6') // workaround debt
    expect(wrapper.text().toUpperCase()).not.toContain('MTTD')
  })

  it('reads the same filters store the table reads, excluding page/size from the aggregate query', async () => {
    vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
    const filters = useFiltersStore()
    filters.tracker = ['Incident']
    filters.page = 3

    mountKpiRow()
    await flushPromises()

    expect(fetchKpi).toHaveBeenCalledTimes(1)
    const paramsArg = vi.mocked(fetchKpi).mock.calls[0]![0]
    expect(paramsArg.getAll('tracker')).toEqual(['Incident'])
    expect(paramsArg.has('page')).toBe(false)
    expect(paramsArg.has('size')).toBe(false)
  })

  it('clicking the Workaround Debt card applies the matching hasWorkaround filter and scrolls to the table', async () => {
    vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
    const filters = useFiltersStore()
    filters.page = 2

    const wrapper = mountKpiRow()
    await flushPromises()

    await wrapper.get('[data-testid="kpi-workaround-debt"]').trigger('click')

    expect(filters.hasWorkaround).toBe(true)
    expect(filters.page).toBe(0)
    expect(wrapper.emitted('scroll-to-table')).toBeTruthy()
  })

  it('renders skeleton per tile while loading', async () => {
    vi.mocked(fetchKpi).mockReturnValue(new Promise(() => {}))

    const wrapper = mountKpiRow()
    await flushPromises()

    const tiles = wrapper.findAll('[data-testid="kpi-tile"]')
    expect(tiles).toHaveLength(6)
    for (const t of tiles) {
      expect(t.find('[data-testid="kpi-skeleton"]').exists()).toBe(true)
      expect(t.find('[data-testid="kpi-value"]').exists()).toBe(false)
    }
  })

  it('closed tile, no backlog', async () => {
    vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())

    const wrapper = mountKpiRow()
    await flushPromises()

    expect(wrapper.get(tile('closed')).text()).toContain('21')
    expect(wrapper.text()).not.toContain('Backlog')
    // The fixture still carries the deprecated `backlog: 12`; it must not be rendered anywhere.
    const values = wrapper.findAll('[data-testid="kpi-value"]').map((v) => v.text())
    expect(values).not.toContain('12')
    expect(wrapper.findAll('[data-testid="kpi-tile"]')).toHaveLength(6)
  })

  /**
   * Workaround and permanent MTTR were two tiles that only scrolled, so both listed the same
   * unfiltered rows. There is one MTTR tile now, and it is the Closed facet.
   */
  describe('the single MTTR tile', () => {
    it('replaces the two split tiles and shows the mean over closed tickets', async () => {
      vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
      const wrapper = mountKpiRow()
      await flushPromises()

      expect(wrapper.find(tile('mttr-workaround')).exists()).toBe(false)
      expect(wrapper.find(tile('mttr-permanent')).exists()).toBe(false)
      const mttr = wrapper.get(tile('mttr'))
      expect(mttr.text()).toContain('6.4')
      expect(mttr.text()).toContain('MTTR')
      expect(mttr.get('[data-testid="kpi-sublabel"]').text()).toBe('mean days, closed tickets')
      expect(
        wrapper.findAll('[data-testid="info-hint"]').map((hint) => hint.attributes('data-term')),
      ).toContain('mttr')
    })

    it('toggles the Closed facet, is aria-pressed, and shows a removable chip', async () => {
      vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const Page = defineComponent(() => () => h('div', [h(KpiRow), h(IncidentFilterBar)]))
      const wrapper = mount(Page, {
        attachTo: document.body,
        global: { plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]] },
      })
      await flushPromises()
      const filters = useFiltersStore()
      filters.page = 3

      expect(wrapper.get(tile('mttr')).attributes('aria-pressed')).toBe('false')
      await wrapper.get(tile('mttr')).trigger('click')
      await flushPromises()

      expect(filters.status).toBe('CLOSED')
      expect(filters.page).toBe(0)
      expect(wrapper.get(tile('mttr')).attributes('aria-pressed')).toBe('true')
      // Same facet as the Closed tile, so both read as pressed and agree.
      expect(wrapper.get(tile('closed')).attributes('aria-pressed')).toBe('true')
      expect(wrapper.get('[data-chip]').text()).toContain('Status: Closed')
      expect(vi.mocked(fetchKpi).mock.lastCall![0].get('status')).toBe('CLOSED')

      await wrapper.get(tile('mttr')).trigger('click')
      await flushPromises()
      expect(filters.status).toBe(null)
      expect(wrapper.get(tile('mttr')).attributes('aria-pressed')).toBe('false')
      expect(wrapper.find('[data-chip]').exists()).toBe(false)

      wrapper.unmount()
    })

    it('shows a dash, not 0, when nothing has closed', async () => {
      vi.mocked(fetchKpi).mockResolvedValue(kpiResponse({ mttrDays: null }))
      const wrapper = mountKpiRow()
      await flushPromises()

      expect(wrapper.get(tile('mttr')).get('[data-testid="kpi-value"]').text()).toBe('—')
    })
  })

  it('toggles sla facet and chip', async () => {
    vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const Page = defineComponent(() => () => h('div', [h(KpiRow), h(IncidentFilterBar)]))

    const wrapper = mount(Page, {
      attachTo: document.body,
      global: { plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]] },
    })
    await flushPromises()
    const filters = useFiltersStore()
    filters.page = 4

    const breached = () => wrapper.get(tile('breached'))
    expect(breached().attributes('aria-pressed')).toBe('false')
    expect(wrapper.find('[data-chip]').exists()).toBe(false)

    await breached().trigger('click')
    await flushPromises()

    expect(filters.sla).toBe('BREACHED')
    expect(filters.page).toBe(0)
    expect(breached().attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="kpi-pressed-icon"]').exists()).toBe(true)
    const chip = wrapper.get('[data-chip]')
    expect(chip.text()).toContain('SLA: Breached')
    // The query follows the facet, so the tile count and the table total agree.
    expect(vi.mocked(fetchKpi).mock.lastCall![0].get('sla')).toBe('BREACHED')

    // Open combines with Breached rather than replacing it (AC3.2).
    await wrapper.get(tile('open')).trigger('click')
    expect(filters.status).toBe('OPEN')
    expect(filters.sla).toBe('BREACHED')

    // Second click clears.
    await breached().trigger('click')
    await flushPromises()
    expect(filters.sla).toBe(null)
    expect(breached().attributes('aria-pressed')).toBe('false')
    expect(wrapper.text()).not.toContain('SLA: Breached')
    expect(wrapper.text()).toContain('Status: Open')

    wrapper.unmount()
  })

  it('error state disables tile filtering', async () => {
    vi.mocked(fetchKpi).mockRejectedValue(new Error('boom'))
    const filters = useFiltersStore()

    const wrapper = mountKpiRow()
    await flushPromises()

    const breached = wrapper.get(tile('breached'))
    expect(breached.element.tagName).toBe('DIV')
    expect(breached.attributes('aria-pressed')).toBeUndefined()
    expect(breached.get('[data-testid="kpi-value"]').text()).toBe('—')
    expect(breached.get('[data-testid="kpi-error"]').text()).toBe("Couldn't load this number")
    expect(wrapper.findAll('[data-testid="kpi-tile"] button[data-testid="kpi-card"]')).toHaveLength(
      0,
    )

    await breached.trigger('click')
    expect(filters.sla).toBe(null)
    expect(wrapper.emitted('scroll-to-table')).toBeFalsy()
  })
})
