import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import type { KpiResponse, TopCategory } from '@/apis/metrics'

vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return {
    ...actual,
    fetchKpi: vi.fn<(params: URLSearchParams) => Promise<KpiResponse>>(),
    fetchTopCategories: vi.fn<(params: URLSearchParams) => Promise<TopCategory[]>>(),
  }
})

import { fetchKpi, fetchTopCategories } from '@/apis/metrics'

import ExecView from '../ExecView.vue'

/**
 * ExecView renders inside AppLayout as of 2026-08-23, so mounting it now pulls in the shell:
 * the sidebar reads the auth store and every nav entry is a RouterLink. Pinia and a router
 * are therefore mount prerequisites, not conveniences.
 *
 * Deliberately a REAL router over stubbed RouterLink: the shell derives the page title by
 * matching `route.path` against the menu payload, so a stub would quietly make the header
 * untestable while still letting these assertions pass.
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/exec', component: { template: '<div />' } },
  ],
})

function mountExecView() {
  setActivePinia(createPinia())
  return mount(ExecView, { global: { plugins: [router, PrimeVue] } })
}

function kpiResponse(overrides: Partial<KpiResponse> = {}): KpiResponse {
  return {
    windowFrom: '2026-06-01',
    windowTo: '2026-06-15',
    windowDays: 15,
    backlog: 40,
    open: 25,
    closed: 30,
    breached: 6,
    atRisk: 4,
    mttrDays: 2,
    mttrWorkaroundDays: 4.2,
    mttrPermanentDays: 9.8,
    mttrGapDays: 5.6,
    workaroundDebt: 11,
    previousPeriod: {
      windowFrom: '2026-05-17',
      windowTo: '2026-05-31',
      windowDays: 15,
      backlog: 35,
      open: 22,
      closed: 26,
      breached: 8,
      atRisk: 5,
      mttrDays: 3,
      mttrWorkaroundDays: 5.1,
      mttrPermanentDays: 10.4,
      mttrGapDays: 5.3,
      workaroundDebt: 9,
      previousPeriod: null,
    },
    ...overrides,
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
  vi.mocked(fetchTopCategories).mockResolvedValue([
    { category: 'Network', count: 12, deltaPct: 8.5 },
    { category: 'Database', count: 7, deltaPct: -3.2 },
  ])
})

afterEach(() => {
  vi.useRealTimers()
})

describe('ExecView', () => {
  it('plots time-to-workaround against time-to-permanent', async () => {
    const wrapper = mountExecView()
    await flushPromises()

    const workaroundBar = wrapper.get('[data-testid="mttr-bar-workaround"]')
    const permanentBar = wrapper.get('[data-testid="mttr-bar-permanent"]')

    expect(workaroundBar.attributes('data-value')).toBe('4.2')
    expect(permanentBar.attributes('data-value')).toBe('9.8')
    expect(workaroundBar.text()).toContain('4.2')
    expect(permanentBar.text()).toContain('9.8')
  })

  it('surfaces workaround debt as a headline figure', async () => {
    const wrapper = mountExecView()
    await flushPromises()

    const card = wrapper.get('[data-testid="workaround-debt-card"]')
    expect(card.get('[data-testid="workaround-debt-value"]').text()).toBe('11')
    // 11 (current) vs. 9 (previous) — a rising debt, not just a bare count.
    expect(card.get('[data-testid="workaround-debt-delta"]').text()).toContain('+2')
  })

  it('defaults to a monthly window, not live', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 5, 15)) // 2026-06-15

    const wrapper = mountExecView()
    await flushPromises()

    expect(fetchKpi).toHaveBeenCalledTimes(1)
    const params = vi.mocked(fetchKpi).mock.calls[0]![0]
    expect(params.get('dateFrom')).toBe('2026-06-01')
    expect(params.get('dateTo')).toBe('2026-06-15')

    const topCategoriesParams = vi.mocked(fetchTopCategories).mock.calls[0]![0]
    expect(topCategoriesParams.get('dateFrom')).toBe('2026-06-01')
    expect(topCategoriesParams.get('dateTo')).toBe('2026-06-15')

    // Not live: unlike WallboardView, nothing re-fetches on an interval.
    await vi.advanceTimersByTimeAsync(5 * 60_000)
    await flushPromises()
    expect(fetchKpi).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('shows period-over-period deltas on every figure', async () => {
    const wrapper = mountExecView()
    await flushPromises()

    // closed 30 vs 26 => +4, open 25 vs 22 => +3, breached 6 vs 8 => -2, atRisk 4 vs 5 => -1
    // (Backlog 40 vs 35 => +5 was asserted here until the Backlog tile was replaced by Closed.)
    expect(wrapper.get('[data-testid="exec-tile-closed-delta"]').text()).toContain('+4')
    expect(wrapper.get('[data-testid="exec-tile-open-delta"]').text()).toContain('+3')
    expect(wrapper.get('[data-testid="exec-tile-breached-delta"]').text()).toContain('-2')
    expect(wrapper.get('[data-testid="exec-tile-at-risk-delta"]').text()).toContain('-1')
    expect(wrapper.get('[data-testid="workaround-debt-delta"]').text()).toContain('+2')

    // No headline figure is a bare number with no comparison.
    const deltas = wrapper.findAll('[data-testid$="-delta"]')
    expect(deltas.length).toBeGreaterThanOrEqual(5)
    for (const delta of deltas) {
      expect(delta.text().trim().length).toBeGreaterThan(0)
      expect(delta.text()).not.toBe('No prior period')
    }
  })

  it('shows a Closed tile and no Backlog tile, and the summary tiles are not controls', async () => {
    const wrapper = mountExecView()
    await flushPromises()

    expect(wrapper.get('[data-testid="exec-tile-closed-value"]').text()).toBe('30')
    expect(wrapper.find('[data-testid="exec-tile-backlog"]').exists()).toBe(false)
    const tiles = wrapper.get('[data-testid="exec-headline-tiles"]')
    expect(tiles.text()).not.toContain('Backlog')
    // Exec is a summary page: the only buttons in the tiles are ⓘ hints — one per headline tile
    // plus the Workaround Debt card that shares the grid.
    const buttons = tiles.findAll('button')
    expect(buttons).toHaveLength(5)
    for (const button of buttons) expect(button.attributes('data-testid')).toBe('info-hint')
    expect(tiles.find('[aria-pressed]').exists()).toBe(false)
  })

  it('never renders a fabricated detection-latency figure', async () => {
    const wrapper = mountExecView()
    await flushPromises()

    expect(wrapper.text().toUpperCase()).not.toContain('MTTD')
    expect(wrapper.html().toUpperCase()).not.toContain('MTTD')
  })
})

/**
 * The endless-load regression.
 *
 * `load()` was fired as `void load()` with no try/catch, so a rejected request — an expired
 * session, a 403, a network blip — was swallowed entirely. `kpi` stayed null, the tiles kept
 * rendering their empty state, and the view presented as "loading forever" with nothing to
 * read and nothing to click. There was no error path at all.
 */
describe('ExecView load failures', () => {
  it('surfaces an error with a retry instead of loading forever', async () => {
    vi.mocked(fetchKpi).mockRejectedValue(new Error('Session expired'))
    vi.mocked(fetchTopCategories).mockRejectedValue(new Error('Session expired'))

    const wrapper = mountExecView()
    await flushPromises()

    const banner = wrapper.find('[data-testid="exec-load-error"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('Session expired')
    // Recoverable without a page reload.
    expect(banner.text()).toContain('Retry')
    // And it must not still claim to be loading.
    expect(wrapper.find('[data-testid="exec-loading"]').exists()).toBe(false)
  })

  it('retries the request when the retry control is used', async () => {
    vi.mocked(fetchKpi).mockRejectedValueOnce(new Error('Session expired'))
    vi.mocked(fetchTopCategories).mockRejectedValueOnce(new Error('Session expired'))

    const wrapper = mountExecView()
    await flushPromises()
    expect(wrapper.find('[data-testid="exec-load-error"]').exists()).toBe(true)

    vi.mocked(fetchKpi).mockResolvedValue(kpiResponse())
    vi.mocked(fetchTopCategories).mockResolvedValue([])

    await wrapper.find('[data-testid="exec-load-error"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="exec-load-error"]').exists()).toBe(false)
  })
})
