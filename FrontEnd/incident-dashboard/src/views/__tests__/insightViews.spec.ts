import { flushPromises } from '@vue/test-utils'
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
    fetchForecast: vi.fn<(p: URLSearchParams, h?: number) => Promise<unknown>>(),
    fetchRca: vi.fn<(p: URLSearchParams, l?: number) => Promise<unknown>>(),
    fetchAlerts: vi.fn<(p: URLSearchParams) => Promise<unknown>>(),
    fetchDailySummary: vi.fn<(p: URLSearchParams, d: string, l: string) => Promise<unknown>>(),
    fetchPatterns: vi.fn<(p: URLSearchParams, l: string) => Promise<unknown>>(),
    searchTickets: vi.fn<(p: URLSearchParams, q: string) => Promise<unknown>>(),
    askQuestion: vi.fn<(p: URLSearchParams, q: string) => Promise<unknown>>(),
  }
})
vi.mock('@/layout/AppLayout.vue', () => ({
  default: { template: '<div><slot name="header-actions" /><slot /></div>' },
}))
vi.mock('@/components/common/FreshnessIndicator.vue', () => ({
  default: { template: '<span />' },
}))
vi.mock('@/apis/definitions', async () => {
  const actual = await vi.importActual<typeof import('@/apis/definitions')>('@/apis/definitions')
  return { ...actual, fetchDefinitions: vi.fn<() => Promise<unknown>>() }
})

import * as api from '@/apis/insights'
import { fetchDefinitions } from '@/apis/definitions'
import { counts, mountInsight, unmountAll } from '@/components/insights/__tests__/helpers'
import { useFiltersStore } from '@/stores/filters'

import AiInsightsView from '../AiInsightsLiveView.vue'
import AlertsView from '../AlertsView.vue'
import DashboardView from '../DashboardView.vue'
import ForecastView from '../ForecastView.vue'
import RcaView from '../RcaView.vue'
import SlaConfigView from '../SlaConfigView.vue'

beforeEach(() => {
  vi.resetAllMocks()
})
afterEach(unmountAll)

const stubs = { RouterLink: true }

describe('DashboardView', () => {
  beforeEach(() => {
    vi.mocked(api.fetchHome).mockResolvedValue({
      total: 1,
      open: 1,
      closed: 0,
      mttrDays: null,
      mttrMinDays: null,
      mttrMaxDays: null,
      onTime: 0,
      late: 0,
      compliancePct: null,
      breachedOpen: 0,
      closurePct: 0,
      avgAgingDays: null,
      byPriority: counts({ low: 1 }),
      daily: [],
    })
    vi.mocked(api.fetchMonthly).mockResolvedValue([])
    vi.mocked(api.fetchAssignees).mockResolvedValue([])
    vi.mocked(api.fetchAging).mockResolvedValue([])
    vi.mocked(api.fetchCategory).mockResolvedValue({ sources: [], institusi: [], brands: [] })
  })

  it('defaults to the Incident scope and a 30-day window, and exposes the four tabs', async () => {
    const w = await mountInsight(DashboardView, {
      global: { stubs, mocks: { $route: { meta: {} } } },
    })
    const filters = useFiltersStore()

    expect(filters.scope).toBe('INCIDENT')
    expect(filters.dateFrom).not.toBeNull()
    expect(filters.dateTo).not.toBeNull()
    expect(w.find('[data-testid="kpi-strip"]').exists()).toBe(true)
    // Without any menu grants the row-level Aging tab is withheld.
    expect(w.find('[data-testid="tab-overview"]').exists()).toBe(true)
    expect(w.find('[data-testid="tab-summary"]').exists()).toBe(true)
    expect(w.find('[data-testid="tab-category"]').exists()).toBe(true)
    expect(w.find('[data-testid="tab-aging"]').exists()).toBe(false)
  })

  it('switching tabs swaps the panel and keeps selection state on the tab', async () => {
    const w = await mountInsight(DashboardView, { global: { stubs } })

    expect(w.get('[data-testid="tab-overview"]').attributes('aria-selected')).toBe('true')
    await w.get('[data-testid="tab-summary"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="tab-summary"]').attributes('aria-selected')).toBe('true')
    expect(w.get('[data-testid="tab-overview"]').attributes('aria-selected')).toBe('false')
    expect(
      w.find('[data-testid="monthly-table"]').exists() ||
        w.find('[data-testid="panel-empty"]').exists(),
    ).toBe(true)
  })

  it('arrow keys move between tabs', async () => {
    const w = await mountInsight(DashboardView, { global: { stubs } })

    await w.get('[data-testid="tab-overview"]').trigger('keydown', { key: 'ArrowRight' })
    expect(w.get('[data-testid="tab-summary"]').attributes('aria-selected')).toBe('true')
  })
})

describe('ForecastView', () => {
  const FORECAST: api.Forecast = {
    horizonDays: 7,
    nextTotal: 19,
    currentTotal: 23,
    changePct: -18.3,
    avg7: 3.29,
    avg14: 2.79,
    avg30: 2.1,
    trendPerDay: 0.029,
    mapePct: 64,
    accuracy: 'POOR',
    busiestDay: 'Wed',
    quietestDay: 'Sun',
    baseLevel: 2.58,
    stdDev: 1.26,
    historyPoints: 111,
    history: [{ date: '2026-04-21', actual: 2 }],
    days: [
      {
        date: '2026-04-22',
        weekday: 'Wed',
        predicted: 4.5,
        lower: 2,
        upper: 7,
        seasonalFactor: 1.72,
        risk: 'LOW',
      },
    ],
    weekdayFactors: [
      { weekday: 'Sun', factor: 0.62 },
      { weekday: 'Wed', factor: 1.72 },
    ],
    recommendations: ['STABLE'],
  }

  it('shows the tiles, the per-day table, the model quality and the honest note', async () => {
    vi.mocked(api.fetchForecast).mockResolvedValue(FORECAST)
    const w = await mountInsight(ForecastView, { global: { stubs } })

    expect(w.get('[data-testid="forecast-next"]').text()).toBe('19')
    expect(w.get('[data-testid="forecast-change"]').text()).toBe('-18.3%')
    expect(w.get('[data-testid="forecast-accuracy"]').text()).toBe('Poor')
    expect(w.get('[data-testid="forecast-row"]').text()).toContain('1.72×')
    expect(w.get('[data-testid="forecast-quality"]').text()).toContain('111')
    expect(w.get('[data-testid="forecast-note"]').text()).toContain('not a promise')
    expect(w.find('[data-rec="STABLE"]').exists()).toBe(true)
  })

  it('refetches with the chosen horizon', async () => {
    vi.mocked(api.fetchForecast).mockResolvedValue(FORECAST)
    const w = await mountInsight(ForecastView, { global: { stubs } })

    await w.get('[data-testid="forecast-horizon"]').setValue(30)
    await new Promise((r) => setTimeout(r))
    expect(vi.mocked(api.fetchForecast).mock.calls.some((c) => c[1] === 30)).toBe(true)
  })

  it('does not invent a change percentage when there is no baseline', async () => {
    vi.mocked(api.fetchForecast).mockResolvedValue({
      ...FORECAST,
      changePct: null,
      mapePct: null,
      accuracy: 'NA',
    })
    const w = await mountInsight(ForecastView, { global: { stubs } })

    expect(w.get('[data-testid="forecast-change"]').text()).toBe('—')
    expect(w.get('[data-testid="forecast-accuracy"]').text()).toBe('n/a')
  })
})

describe('AlertsView', () => {
  it('orders alerts by severity and lists the worst tickets', async () => {
    vi.mocked(api.fetchAlerts).mockResolvedValue([
      { code: 'UNASSIGNED_OPEN', severity: 'INFO', count: 2, tickets: [] },
      {
        code: 'BREACHED_OPEN',
        severity: 'CRITICAL',
        count: 1,
        tickets: [{ noIncident: 77, subject: 'Down', priority: 'High', slaUsedPct: 171.4 }],
      },
    ])
    const w = await mountInsight(AlertsView, { global: { stubs } })

    const severities = w.findAll('[data-severity]').map((e) => e.attributes('data-severity'))
    expect(severities).toEqual(['CRITICAL', 'INFO'])
    expect(w.get('[data-testid="alert-BREACHED_OPEN"]').text()).toContain('171.4%')
  })

  it('says all clear when there are no alerts', async () => {
    vi.mocked(api.fetchAlerts).mockResolvedValue([])
    const w = await mountInsight(AlertsView, { global: { stubs } })

    expect(w.find('[data-testid="alerts-clear"]').exists()).toBe(true)
  })
})

describe('RcaView', () => {
  it('defaults to the Problem scope, shows coverage and the missing-root-cause worklist', async () => {
    vi.mocked(api.fetchRca).mockResolvedValue({
      total: 4,
      open: 2,
      withRootCause: 2,
      withWorkaround: 2,
      permanentSolution: 1,
      workaroundOnly: 2,
      rootCausePct: 50,
      workaroundPct: 50,
      permanentPct: 25,
      byCategory: [{ label: 'Network', count: 2 }],
      missingRootCause: [
        {
          noIncident: 3,
          subject: 'c',
          status: 'Open',
          category: 'Network',
          assignedTo: 'x',
          agingDays: 40,
          hasRootCause: false,
          hasWorkaround: false,
          solution: null,
          closed: false,
        },
      ],
    })
    const w = await mountInsight(RcaView, { global: { stubs } })

    expect(useFiltersStore().scope).toBe('PROBLEM')
    expect(w.get('[data-testid="rca-rootCause"]').text()).toContain('50')
    expect(w.get('[data-testid="rca-debt"]').text()).toContain('2')
    expect(w.findAll('[data-testid="rca-row"]')).toHaveLength(1)
  })
})

describe('SlaConfigView', () => {
  it('shows the live SLA ladder, the close rule and the refresh cadence', async () => {
    vi.mocked(fetchDefinitions).mockResolvedValue({
      slaPolicy: [{ priorityId: 1, priorityName: 'Critical', slaDays: 3, atRiskRemainingDays: 1 }],
      unknownPriority: { slaDays: 3, atRiskRemainingDays: 1 },
      closedByOverride: [{ statusId: 63, statusName: 'PTR' }],
      closeRule: ['CLOSE_DATE', 'CLOSED_ON', 'STATUS_CHANGE'],
      refreshIntervalMinutes: 2,
    })
    const w = await mountInsight(SlaConfigView, { global: { stubs } })

    expect(w.get('[data-testid="sla-row"]').text()).toContain('3')
    expect(w.findAll('[data-testid="sla-close-rule"] li')).toHaveLength(3)
    expect(w.get('[data-testid="sla-overrides"]').text()).toContain('PTR')
    expect(w.get('[data-testid="sla-refresh"]').text()).toContain('2 minutes')
  })
})

describe('AiInsightsView', () => {
  it('runs nothing on load', async () => {
    await mountInsight(AiInsightsView, { global: { stubs } })

    expect(api.fetchDailySummary).not.toHaveBeenCalled()
    expect(api.fetchPatterns).not.toHaveBeenCalled()
  })

  it('answers a question with the value and its basis', async () => {
    vi.mocked(api.askQuestion).mockResolvedValue({
      understood: true,
      metric: 'AVG_AGING',
      scope: 'BNI',
      value: 14,
      matched: 3,
      unit: 'd',
    })
    const w = await mountInsight(AiInsightsView, { global: { stubs } })

    await w.get('[data-testid="ai-question"]').setValue('berapa rata-rata aging BNI')
    await w.get('[data-testid="ai-ask"]').trigger('click')
    await new Promise((r) => setTimeout(r))

    expect(w.get('[data-testid="ai-answer-value"]').text()).toBe('14 days')
    expect(w.get('[data-testid="ai-answer"]').text()).toContain('BNI')
    expect(w.get('[data-testid="ai-answer"]').text()).toContain('3 tickets')
  })

  it('says it cannot answer rather than guessing', async () => {
    vi.mocked(api.askQuestion).mockResolvedValue({
      understood: false,
      metric: null,
      scope: null,
      value: null,
      matched: 0,
      unit: null,
    })
    const w = await mountInsight(AiInsightsView, { global: { stubs } })

    await w.get('[data-testid="ai-question"]').setValue('sky colour')
    await w.get('[data-testid="ai-ask"]').trigger('click')
    await new Promise((r) => setTimeout(r))

    expect(w.find('[data-testid="ai-answer-value"]').exists()).toBe(false)
    expect(w.get('[data-testid="ai-answer"]').text()).toContain('Try')
  })

  it('labels a locally computed summary as local, and shows a provider narrative when present', async () => {
    const base: api.DailySummary = {
      date: '2026-04-21',
      created: 2,
      closed: 1,
      openNow: 5,
      breachedOpen: 1,
      byPriority: counts({ low: 2 }),
      topInstitusi: [{ label: 'QRIS', count: 2 }],
      topCategories: [],
      attention: [],
      narrative: null,
      provider: 'local',
    }
    vi.mocked(api.fetchDailySummary).mockResolvedValue(base)
    const w = await mountInsight(AiInsightsView, { global: { stubs } })
    await w.get('[data-testid="ai-generate"]').trigger('submit')
    await new Promise((r) => setTimeout(r))
    expect(w.get('[data-testid="ai-provider"]').text()).toContain('locally')
    expect(w.find('[data-testid="ai-narrative"]').exists()).toBe(false)

    vi.mocked(api.fetchDailySummary).mockResolvedValue({
      ...base,
      narrative: '- 2 new tickets',
      provider: 'openai',
    })
    await w.get('[data-testid="ai-generate"]').trigger('submit')
    await new Promise((r) => setTimeout(r))
    expect(w.get('[data-testid="ai-narrative"]').text()).toContain('2 new tickets')
    expect(w.get('[data-testid="ai-provider"]').text()).toContain('openai')
  })

  it('search lists hits with relevance and reports no hit honestly', async () => {
    vi.mocked(api.searchTickets).mockResolvedValue([
      {
        noIncident: 20618,
        subject: 'QRIS decline',
        priority: 'Low',
        institusi: 'QRIS',
        relevance: 0.28,
      },
    ])
    const w = await mountInsight(AiInsightsView, { global: { stubs } })
    await w.get('[data-testid="ai-search"]').setValue('QRIS')
    await w.get('[data-testid="ai-search-go"]').trigger('click')
    await new Promise((r) => setTimeout(r))
    expect(w.get('[data-testid="ai-hit"]').text()).toContain('0.28')

    vi.mocked(api.searchTickets).mockResolvedValue([])
    await w.get('[data-testid="ai-search-go"]').trigger('click')
    await new Promise((r) => setTimeout(r))
    expect(w.find('[data-testid="ai-no-hits"]').exists()).toBe(true)
  })

  it('shows recurring patterns with their recommendation', async () => {
    vi.mocked(api.fetchPatterns).mockResolvedValue({
      patterns: [
        {
          institusi: 'BNI',
          category: 'Payment',
          count: 19,
          firstSeen: '2026-04-01',
          lastSeen: '2026-04-20',
          closedWithWorkaround: 12,
          permanentSolution: 0,
          keywords: ['timeout'],
          samples: [],
          recommendation: 'PERMANENT_FIX',
        },
      ],
      narrative: null,
      provider: 'local',
    })
    const w = await mountInsight(AiInsightsView, { global: { stubs } })
    await w.get('[data-testid="ai-analyze"]').trigger('click')
    await new Promise((r) => setTimeout(r))

    expect(w.get('[data-testid="ai-pattern"]').text()).toContain('BNI')
    expect(w.find('[data-rec="PERMANENT_FIX"]').exists()).toBe(true)
  })
})

describe('AiInsightsView placeholder', () => {
  it('shows the placeholder and calls no AI endpoint', async () => {
    const Placeholder = (await import('../AiInsightsView.vue')).default
    const w = await mountInsight(Placeholder, { global: { stubs } })

    expect(w.find('[data-testid="ai-placeholder"]').exists()).toBe(true)
    expect(api.fetchDailySummary).not.toHaveBeenCalled()
  })
})
