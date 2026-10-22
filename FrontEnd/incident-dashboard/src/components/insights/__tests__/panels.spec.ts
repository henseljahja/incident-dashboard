import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/apis/insights', async () => {
  const actual = await vi.importActual<typeof import('@/apis/insights')>('@/apis/insights')
  return {
    ...actual,
    fetchHome: vi.fn<(p: URLSearchParams) => Promise<unknown>>(),
    fetchMonthly: vi.fn<(p: URLSearchParams) => Promise<unknown>>(),
    fetchAssignees: vi.fn<(p: URLSearchParams, l?: number) => Promise<unknown>>(),
    fetchAging: vi.fn<(p: URLSearchParams, l?: number) => Promise<unknown>>(),
    fetchCategory: vi.fn<(p: URLSearchParams, l?: number) => Promise<unknown>>(),
  }
})

import * as api from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'

import AgingPanel from '../AgingPanel.vue'
import AssigneePanel from '../AssigneePanel.vue'
import CategoryPanel from '../CategoryPanel.vue'
import KpiStrip from '../KpiStrip.vue'
import MonthlyPanel from '../MonthlyPanel.vue'
import PriorityDonut from '../PriorityDonut.vue'
import { counts, mountInsight, unmountAll } from './helpers'

const HOME: api.Home = {
  total: 39,
  open: 10,
  closed: 29,
  mttrDays: 14.5,
  mttrMinDays: 9,
  mttrMaxDays: 22,
  onTime: 28,
  late: 1,
  compliancePct: 96.6,
  breachedOpen: 0,
  closurePct: 74.4,
  avgAgingDays: 15,
  byPriority: counts({ high: 4, medium: 10, low: 25 }),
  daily: [],
}

beforeEach(() => {
  vi.resetAllMocks()
})
afterEach(unmountAll)

describe('KpiStrip', () => {
  it('shows the five headline figures with their qualifiers', async () => {
    vi.mocked(api.fetchHome).mockResolvedValue(HOME)
    const w = await mountInsight(KpiStrip)

    const text = (id: string) => w.get(`[data-testid="kpi-${id}"]`).text()
    expect(text('total')).toContain('39')
    expect(text('total')).toContain('10 open')
    expect(text('mttr')).toContain('14.5')
    expect(text('mttr')).toContain('Min 9d')
    expect(text('compliance')).toContain('96.6')
    expect(text('compliance')).toContain('28 on time / 1 late')
    expect(text('breach')).toContain('0')
    expect(text('closure')).toContain('74.4')
    expect(text('closure')).toContain('Avg aging 15')
  })

  it('shows an em dash, not 0 or 100%, when nothing has closed', async () => {
    vi.mocked(api.fetchHome).mockResolvedValue({
      ...HOME,
      closed: 0,
      open: 39,
      mttrDays: null,
      mttrMinDays: null,
      mttrMaxDays: null,
      compliancePct: null,
      onTime: 0,
      late: 0,
      closurePct: 0,
    })
    const w = await mountInsight(KpiStrip)

    expect(w.get('[data-testid="kpi-mttr"] [data-testid="kpi-value"]').text()).toBe('—')
    expect(w.get('[data-testid="kpi-compliance"] [data-testid="kpi-value"]').text()).toBe('—')
  })

  it('shows no delta while the window is open (there is no honest previous period)', async () => {
    vi.mocked(api.fetchHome).mockResolvedValue(HOME)
    const w = await mountInsight(KpiStrip)

    expect(w.findAll('[data-testid="kpi-delta"]')).toHaveLength(0)
  })

  it('renders the delta (+30%) once a bounded window has a baseline', async () => {
    vi.mocked(api.fetchHome).mockImplementation(async (p) =>
      p.get('dateFrom') === '2026-08-14' ? { ...HOME, total: 30 } : HOME,
    )
    const { setActivePinia, createPinia } = await import('pinia')
    setActivePinia(createPinia())
    const filters = useFiltersStore()
    filters.dateFrom = '2026-09-13'
    filters.dateTo = '2026-10-12'
    const { QueryClient, VueQueryPlugin } = await import('@tanstack/vue-query')
    const { mount, flushPromises } = await import('@vue/test-utils')
    const { i18n } = await import('@/i18n')
    const w = mount(KpiStrip, {
      global: { plugins: [i18n, [VueQueryPlugin, { queryClient: new QueryClient() }]] },
    })
    await flushPromises()

    expect(w.get('[data-testid="kpi-total"] [data-testid="kpi-delta"]').text()).toContain('+30')
  })
})

describe('PriorityDonut', () => {
  it('lists every priority with its count and share', async () => {
    vi.mocked(api.fetchHome).mockResolvedValue(HOME)
    const w = await mountInsight(PriorityDonut)

    const rows = w.findAll('[data-testid="donut-row"]')
    expect(rows.map((r) => r.attributes('data-priority'))).toEqual([
      'critical',
      'high',
      'medium',
      'low',
    ])
    expect(w.get('[data-priority="low"]').text()).toContain('25')
    expect(w.get('[data-priority="low"]').text()).toContain('64.1%')
  })
})

describe('MonthlyPanel', () => {
  it('renders one row per month with the priority split and breach count', async () => {
    vi.mocked(api.fetchMonthly).mockResolvedValue([
      {
        period: '2026-04',
        total: 59,
        byPriority: counts({ medium: 3, low: 56 }),
        closed: 46,
        open: 12,
        avgResolutionDays: 19,
        avgOpenAgeDays: null,
        slaBreach: 1,
      },
    ])
    const w = await mountInsight(MonthlyPanel)

    const row = w.get('[data-testid="month-row"]')
    expect(row.attributes('data-period')).toBe('2026-04')
    expect(row.text()).toContain('59')
    expect(row.get('[data-priority="low"]').text()).toBe('56')
    expect(row.text()).toContain('19')
    expect(row.text()).toContain('—') // no open-age basis
  })
})

describe('AssigneePanel', () => {
  const ROW: api.AssigneeRow = {
    rank: 1,
    assignee: 'budi@x.id',
    total: 25,
    closed: 25,
    open: 0,
    byPriority: counts({ medium: 1, low: 24 }),
    avgResolutionDays: 14.3,
    minResolutionDays: 9,
    maxResolutionDays: 22,
    closureRatePct: 100,
  }

  it('shows rank, range and closure rate; clicking a name filters the list', async () => {
    vi.mocked(api.fetchAssignees).mockResolvedValue([ROW])
    const w = await mountInsight(AssigneePanel)

    const row = w.get('[data-testid="assignee-row"]')
    expect(row.text()).toContain('9–22')
    expect(row.text()).toContain('100%')
    await row.get('button').trigger('click')

    expect(useFiltersStore().assignedTo).toEqual(['budi@x.id'])
    expect(w.emitted('filter')).toEqual([['budi@x.id']])
  })

  it('does not offer to filter by the Unassigned bucket', async () => {
    vi.mocked(api.fetchAssignees).mockResolvedValue([{ ...ROW, assignee: 'Unassigned' }])
    const w = await mountInsight(AssigneePanel)

    expect(w.find('[data-testid="assignee-row"] button').exists()).toBe(false)
  })
})

describe('AgingPanel', () => {
  it('shows SLA used and marks overdue tickets', async () => {
    vi.mocked(api.fetchAging).mockResolvedValue([
      {
        noIncident: 21016,
        subject: 'Pop up error',
        priority: 'Low',
        status: 'Development',
        institusi: 'Merchant',
        assignedTo: null,
        agingDays: 7,
        slaDays: 60,
        slaUsedPct: 11.7,
      },
      {
        noIncident: 21017,
        subject: 'Late one',
        priority: 'High',
        status: 'Analysis',
        institusi: null,
        assignedTo: 'x',
        agingDays: 20,
        slaDays: 7,
        slaUsedPct: 285.7,
      },
    ])
    const w = await mountInsight(AgingPanel)

    const rows = w.findAll('[data-testid="aging-row"]')
    expect(rows[0]!.text()).toContain('11.7%')
    expect(rows[0]!.text()).toContain('Merchant')
    expect(rows[1]!.text()).toContain('285.7%')
    expect(rows[1]!.find('.font-semibold').exists()).toBe(true)
  })
})

describe('CategoryPanel', () => {
  it('shows external vs internal and the institusi ranking; clicking an institusi filters', async () => {
    vi.mocked(api.fetchCategory).mockResolvedValue({
      sources: [
        {
          source: 'External',
          total: 23,
          byPriority: counts({ low: 23 }),
          closed: 23,
          open: 0,
          avgAgingDays: 14,
        },
        {
          source: 'Internal',
          total: 16,
          byPriority: counts({ medium: 3, low: 13 }),
          closed: 6,
          open: 10,
          avgAgingDays: 16.4,
        },
      ],
      institusi: [
        { label: 'BNI', count: 11, sharePct: 28.2 },
        { label: 'OTHER', count: 5, sharePct: 12.8 },
      ],
      brands: [
        { label: 'MASTERCARD', count: 17, sharePct: 51.5 },
        { label: 'VISA', count: 10, sharePct: 30.3 },
      ],
    })
    const w = await mountInsight(CategoryPanel)

    const sources = w.findAll('[data-testid="source-row"]')
    expect(sources.map((s) => s.attributes('data-source'))).toEqual(['External', 'Internal'])
    expect(sources[1]!.text()).toContain('16.4')
    const brandRows = w.findAll('[data-testid="brand-row"]')
    expect(brandRows.map((r) => r.attributes('data-count'))).toEqual(['17', '10'])
    expect(brandRows[0]!.text()).toContain('MASTERCARD')
    const rows = w.findAll('[data-testid="institusi-row"]')
    expect(rows[0]!.text()).toContain('BNI')
    expect(rows[0]!.text()).toContain('28.2%')

    await rows[0]!.get('button').trigger('click')
    expect(useFiltersStore().institusi).toEqual(['BNI'])
  })
})

describe('widget states', () => {
  it('shows a retryable error rather than an empty or zero widget', async () => {
    vi.mocked(api.fetchMonthly).mockRejectedValue(new Error('boom'))
    const w = await mountInsight(MonthlyPanel)

    expect(w.find('[data-testid="panel-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="month-row"]').exists()).toBe(false)
  })

  it('shows the empty state when there is no data', async () => {
    vi.mocked(api.fetchMonthly).mockResolvedValue([])
    const w = await mountInsight(MonthlyPanel)

    expect(w.find('[data-testid="panel-empty"]').exists()).toBe(true)
  })
})
