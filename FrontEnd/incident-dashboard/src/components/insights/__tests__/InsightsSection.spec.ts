import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/i18n'
import { useFiltersStore } from '@/stores/filters'

vi.mock('@/apis/insights', () => ({
  fetchByPriority: vi.fn<(p: URLSearchParams) => Promise<unknown>>(),
  fetchBySource: vi.fn<(p: URLSearchParams) => Promise<unknown>>(),
  fetchTopInstitusi: vi.fn<(p: URLSearchParams, limit?: number) => Promise<unknown>>(),
  fetchTopAssignees: vi.fn<(p: URLSearchParams, limit?: number) => Promise<unknown>>(),
  fetchMonthlyComparison: vi.fn<(p: URLSearchParams) => Promise<unknown>>(),
  fetchTopAging: vi.fn<(p: URLSearchParams, limit?: number) => Promise<unknown>>(),
  fetchForecast: vi.fn<(p: URLSearchParams, horizon?: number) => Promise<unknown>>(),
}))
vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return { ...actual, fetchKpi: vi.fn<(p: URLSearchParams) => Promise<unknown>>() }
})
// jsdom has no canvas; the chart wrappers are covered by their text equivalents.
vi.mock('chart.js', () => {
  class FakeChart {
    static register() {}
    data = { labels: [], datasets: [{}] }
    options = {}
    update() {}
    destroy() {}
  }
  const stub = class {}
  return {
    Chart: FakeChart,
    ArcElement: stub,
    DoughnutController: stub,
    Legend: stub,
    Tooltip: stub,
    CategoryScale: stub,
    LinearScale: stub,
    LineController: stub,
    LineElement: stub,
    PointElement: stub,
  }
})

import * as insights from '@/apis/insights'
import { fetchKpi, type KpiResponse } from '@/apis/metrics'

import InsightsSection from '../InsightsSection.vue'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
  vi.mocked(fetchKpi).mockResolvedValue({
    open: 30,
    closed: 70,
    breached: 12,
    mttrDays: 4.5,
  } as KpiResponse)
  vi.mocked(insights.fetchMonthlyComparison).mockResolvedValue([
    { period: '2026-08', created: 40, closed: 30, breached: 5, met: 24, mttrDays: 4 },
    { period: '2026-09', created: 60, closed: 40, breached: 7, met: 36, mttrDays: 5 },
  ])
  vi.mocked(insights.fetchByPriority).mockResolvedValue([
    { label: 'High', count: 30 },
    { label: 'Low', count: 70 },
  ])
  vi.mocked(insights.fetchBySource).mockResolvedValue([{ label: 'External', count: 10 }])
  vi.mocked(insights.fetchTopInstitusi).mockResolvedValue([{ label: 'Bank A', count: 9 }])
  vi.mocked(insights.fetchTopAssignees).mockResolvedValue([
    { assignee: 'Budi', total: 10, open: 2, closed: 8, breached: 1, closureRatePct: 80 },
  ])
  vi.mocked(insights.fetchTopAging).mockResolvedValue([
    {
      noIncident: 101,
      subject: 'VPN down',
      priority: 'High',
      assignedTo: 'Budi',
      status: 'In Progress',
      agingDays: 20,
      slaDays: 7,
      slaUsedPct: 285.71,
    },
  ])
  vi.mocked(insights.fetchForecast).mockResolvedValue({
    method: 'linear-regression',
    historyDays: 60,
    horizonDays: 14,
    slopePerDay: 0.12,
    points: [{ date: '2026-10-12', actual: 3, forecast: null, lower: null, upper: null }],
  })
})

async function mountSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const wrapper = mount(InsightsSection, {
    global: { plugins: [PrimeVue, i18n, [VueQueryPlugin, { queryClient }]] },
  })
  await flushPromises()
  return wrapper
}

describe('InsightsSection', () => {
  it('derives the headline tiles from KPI and monthly data', async () => {
    const wrapper = await mountSection()

    expect(wrapper.get('[data-testid="insight-tile-total"]').text()).toContain('100')
    expect(wrapper.get('[data-testid="insight-tile-breached"]').text()).toContain('12')
    // closure = 70 / 100
    expect(wrapper.get('[data-testid="insight-tile-closure"]').text()).toContain('70')
    // compliance = (24 + 36) / (30 + 40) = 85.7%
    expect(wrapper.get('[data-testid="insight-tile-compliance"]').text()).toContain('85')
  })

  it('renders the assignee, aging, institusi and monthly widgets', async () => {
    const wrapper = await mountSection()

    expect(wrapper.get('[data-testid="assignee-row"]').attributes('data-assignee')).toBe('Budi')
    expect(wrapper.get('[data-testid="aging-row"]').text()).toContain('VPN down')
    expect(wrapper.get('[data-testid="institusi-row"]').text()).toContain('Bank A')
    expect(wrapper.findAll('[data-testid="month-row"]')).toHaveLength(2)
    expect(wrapper.get('[data-testid="forecast-note"]').text()).toContain('60')
  })

  it('a period chip rewrites the shared date window', async () => {
    const wrapper = await mountSection()
    const filters = useFiltersStore()

    await wrapper.get('[data-testid="period-90d"]').trigger('click')
    expect(filters.dateFrom).not.toBeNull()
    expect(filters.dateTo).not.toBeNull()

    await wrapper.get('[data-testid="period-all"]').trigger('click')
    expect(filters.dateFrom).toBeNull()
  })

  it('sends the base filter params to the widgets', async () => {
    const filters = useFiltersStore()
    filters.status = 'OPEN'
    filters.priority = ['High']
    await mountSection()

    const params = vi.mocked(insights.fetchTopAssignees).mock.calls[0]![0]
    expect(params.getAll('priority')).toEqual(['High'])
    expect(params.has('status')).toBe(false)
  })
})
