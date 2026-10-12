import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import type { ScopeCount, ScopeCountsResponse } from '@/apis/metrics'
import { i18n } from '@/i18n'

vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return {
    ...actual,
    fetchScopeCounts: vi.fn<(params?: URLSearchParams) => Promise<ScopeCountsResponse>>(),
  }
})

import { fetchScopeCounts } from '@/apis/metrics'

import ScopeCounts from '../ScopeCounts.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/ops', component: { template: '<div />' } },
    { path: '/problem', component: { template: '<div />' } },
    { path: '/service-requests', component: { template: '<div />' } },
  ],
})

function entry(scope: ScopeCount['scope'], open: number, breached: number): ScopeCount {
  return { scope, open, closed: 4, breached, atRisk: 1, total: open + 4 }
}

const RESPONSE: ScopeCountsResponse = {
  scopes: [entry('INCIDENT', 12, 3), entry('PROBLEM', 5, 0), entry('SERVICE_REQUEST', 1234, 9)],
}

beforeEach(async () => {
  vi.resetAllMocks()
  vi.mocked(fetchScopeCounts).mockResolvedValue(RESPONSE)
  await router.push('/problem')
  await router.isReady()
})

async function mountCounts(props: Record<string, unknown> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const wrapper = mount(ScopeCounts, {
    props,
    global: { plugins: [router, PrimeVue, [VueQueryPlugin, { queryClient }]] },
  })
  await flushPromises()
  return wrapper
}

describe('ScopeCounts', () => {
  it('shows the three groups in menu order with open count and a breached badge', async () => {
    const wrapper = await mountCounts({ current: 'PROBLEM' })

    const tiles = wrapper.findAll('[data-scope]')
    expect(tiles.map((tile) => tile.attributes('data-scope'))).toEqual([
      'INCIDENT',
      'PROBLEM',
      'SERVICE_REQUEST',
    ])
    expect(tiles[0]!.get('[data-testid="scope-open"]').text()).toBe('12')
    // Grouped for the locale, like every other count.
    expect(tiles[2]!.get('[data-testid="scope-open"]').text()).toBe('1,234')
    // Words and an icon, not colour alone — and shown at zero too, so tiles stay the same shape.
    expect(tiles[0]!.get('[data-testid="scope-breached"]').text()).toBe('3 breached')
    expect(tiles[0]!.get('[data-testid="scope-breached"] i').classes()).toContain(
      'pi-exclamation-triangle',
    )
    expect(tiles[1]!.get('[data-testid="scope-breached"]').text()).toBe('0 breached')
  })

  it('links each tile to its menu and marks only the current page', async () => {
    const wrapper = await mountCounts({ current: 'PROBLEM' })

    const tiles = wrapper.findAll('[data-scope]')
    expect(tiles.map((tile) => tile.element.tagName)).toEqual(['A', 'A', 'A'])
    expect(tiles.map((tile) => tile.attributes('href'))).toEqual([
      '/ops',
      '/problem',
      '/service-requests',
    ])
    expect(tiles.map((tile) => tile.attributes('aria-current'))).toEqual([
      undefined,
      'page',
      undefined,
    ])
    expect(tiles[0]!.attributes('aria-label')).toBe('Incident: 12 open, 3 breached')
    // At least 44px to hit (3.5rem on a phone, 4rem from `sm`).
    for (const tile of tiles) expect(tile.classes()).toContain('min-h-14')
  })

  it('asks the server for the date window only', async () => {
    await mountCounts({
      params: new URLSearchParams('dateFrom=2026-01-01&dateTo=2026-02-01&tracker=X'),
    })

    const sent = vi.mocked(fetchScopeCounts).mock.calls[0]![0]!
    expect(sent.get('dateFrom')).toBe('2026-01-01')
    expect(sent.get('dateTo')).toBe('2026-02-01')
  })

  it('has an info hint for the group counts', async () => {
    const wrapper = await mountCounts()

    expect(wrapper.get('[data-testid="info-hint"]').attributes('data-term')).toBe('groupCounts')
  })

  it('renders plain boxes, not links, when told the viewer cannot open the menus', async () => {
    const wrapper = await mountCounts({ links: false, counts: RESPONSE.scopes })

    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(
      wrapper
        .findAll('[data-scope]')
        .every((tile) => tile.attributes('aria-current') === undefined),
    ).toBe(true)
    // Counts handed in means nothing is fetched.
    expect(fetchScopeCounts).not.toHaveBeenCalled()
    expect(wrapper.get('[data-scope="INCIDENT"] [data-testid="scope-open"]').text()).toBe('12')
  })

  it('shows skeletons while loading, never an empty card', async () => {
    vi.mocked(fetchScopeCounts).mockReturnValue(new Promise(() => {}))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(ScopeCounts, {
      global: { plugins: [router, PrimeVue, [VueQueryPlugin, { queryClient }]] },
    })
    await flushPromises()

    expect(wrapper.findAll('[data-testid="scope-skeleton"]')).toHaveLength(3)
    expect(wrapper.find('[data-testid="scope-open"]').exists()).toBe(false)
  })

  it('says so and offers a retry when the request fails', async () => {
    vi.mocked(fetchScopeCounts).mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountCounts()

    const error = wrapper.get('[data-testid="scope-counts-error"]')
    expect(error.attributes('role')).toBe('alert')
    expect(error.text()).toContain("Couldn't load the group counts")

    vi.mocked(fetchScopeCounts).mockResolvedValue(RESPONSE)
    await error.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="scope-counts-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-scope]')).toHaveLength(3)
  })

  it('marks a group with no tickets as empty rather than showing a bare zero', async () => {
    vi.mocked(fetchScopeCounts).mockResolvedValue({
      scopes: [
        { scope: 'INCIDENT', open: 0, closed: 0, breached: 0, atRisk: 0, total: 0 },
        entry('PROBLEM', 5, 0),
        entry('SERVICE_REQUEST', 1, 0),
      ],
    })
    const wrapper = await mountCounts()

    expect(wrapper.findAll('[data-testid="scope-none"]')).toHaveLength(1)
    expect(wrapper.get('[data-scope="INCIDENT"]').text()).toContain('None yet')
  })

  it('renders in Indonesian', async () => {
    i18n.global.locale.value = 'id'
    const wrapper = await mountCounts({ current: 'INCIDENT' })

    expect(wrapper.text()).toContain('Kelompok tiket')
    expect(wrapper.get('[data-scope="SERVICE_REQUEST"]').text()).toContain('Permintaan Layanan')
    expect(wrapper.get('[data-scope="INCIDENT"] [data-testid="scope-breached"]').text()).toBe(
      '3 melewati SLA',
    )
  })
})
