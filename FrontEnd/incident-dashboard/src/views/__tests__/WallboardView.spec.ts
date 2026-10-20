import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { FreshnessDto, WallboardResponse } from '@/apis/metrics'
import { ROTATION_INTERVAL_KEY } from '@/composables/useRotation'

vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return {
    ...actual,
    fetchWallboard: vi.fn<() => Promise<WallboardResponse>>(),
    fetchFreshness: vi.fn<() => Promise<FreshnessDto>>(),
  }
})

import { fetchFreshness, fetchWallboard } from '@/apis/metrics'

import WallboardView from '../WallboardView.vue'

function wallboardResponse(overrides: Partial<WallboardResponse> = {}): WallboardResponse {
  return {
    generatedAt: '2026-10-07T09:00:00Z',
    year: 2026,
    total: 33,
    open: 8,
    closed: 25,
    breached: 3,
    atRisk: 2,
    slaCompliance: { met: 20, closedInPeriod: 25, percent: 80 },
    byStage: [
      { stage: 'New', count: 3 },
      { stage: 'In Progress', count: 5 },
    ],
    byPriority: [
      { priority: 'Critical', count: 2 },
      { priority: 'High', count: 4 },
    ],
    criticalHighOpen: [
      {
        noIncident: 4021,
        subject: 'Payment gateway timeout',
        status: 'In Progress',
        priority: 'Critical',
        elapsedDays: 2.5,
        slaDays: 3,
      },
    ],
    topCategories: [
      { category: 'Network', count: 9 },
      { category: 'Application', count: 4 },
    ],
    monthly: [
      { month: '2026-01', created: 6, closed: 4 },
      { month: '2026-02', created: 0, closed: 0 },
    ],
    avgResolutionDays: 4.2,
    groupCounts: [
      { scope: 'INCIDENT', open: 8, closed: 25, breached: 3, atRisk: 2, total: 33 },
      { scope: 'PROBLEM', open: 2, closed: 1, breached: 0, atRisk: 0, total: 3 },
      { scope: 'SERVICE_REQUEST', open: 40, closed: 60, breached: 7, atRisk: 1, total: 100 },
    ],
    ...overrides,
  }
}

function freshnessResponse(overrides: Partial<FreshnessDto> = {}): FreshnessDto {
  return {
    lastRunAt: '2026-08-23T10:00:00Z',
    lastSourceTs: '2026-08-23T09:55:00Z',
    ageSeconds: 300,
    stale: false,
    staleReason: null,
    ...overrides,
  }
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  // The layout's language switch writes through the locale store.
  setActivePinia(createPinia())
  window.history.replaceState({}, '', '/wallboard')
  localStorage.removeItem(ROTATION_INTERVAL_KEY)
  setHidden(false)
  vi.resetAllMocks()
  vi.mocked(fetchWallboard).mockResolvedValue(wallboardResponse())
  vi.mocked(fetchFreshness).mockResolvedValue(freshnessResponse())
})

afterEach(() => {
  vi.useRealTimers()
  localStorage.removeItem(ROTATION_INTERVAL_KEY)
})

describe('WallboardView', () => {
  it('forces dark mode regardless of user preference', async () => {
    const wrapper = mount(WallboardView)
    await flushPromises()

    expect(wrapper.find('.app-dark').exists()).toBe(true)
  })

  /**
   * The inventory is exact — every button is accounted for by name and count — so an accidental
   * operator control still fails here. Two escape hatches (fullscreen, back), the two language
   * options, the two rotation buttons, and one ⓘ per component on screen 1: the group counts,
   * Breached, At risk, Open and SLA compliance.
   */
  it('renders only the escape hatches, the language switch, the rotation controls and the hints, no app nav, and no MTTD anywhere', async () => {
    const wrapper = mount(WallboardView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('Log out')
    expect(wrapper.text()).not.toContain('Incident Ops')
    expect(wrapper.find('header').exists()).toBe(false)
    expect(wrapper.find('nav').exists()).toBe(false)

    expect(wrapper.findAll('button')).toHaveLength(11)
    expect(wrapper.find('[data-testid="wallboard-fullscreen-toggle"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wallboard-exit"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="language-switch"]').findAll('button')).toHaveLength(2)
    expect(wrapper.find('[data-testid="wallboard-rotation-toggle"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="wallboard-rotation-next"]').exists()).toBe(true)
    expect(
      wrapper.findAll('[data-testid="info-hint"]').map((hint) => hint.attributes('data-term')),
    ).toEqual(['groupCounts', 'breached', 'atRisk', 'open', 'slaCompliance'])

    // The group tiles are plain boxes: this role cannot open the ops menus.
    expect(wrapper.findAll('a')).toHaveLength(0)

    expect(wrapper.text().toUpperCase()).not.toContain('MTTD')
  })

  it('keeps the chrome reachable by keyboard rather than unmounting it when idle', async () => {
    // Removing the controls from the DOM when the pointer goes still would strand anyone
    // navigating without a mouse — they would have no way off this screen at all.
    const wrapper = mount(WallboardView)
    await flushPromises()

    expect(wrapper.find('[data-testid="wallboard-chrome"]').exists()).toBe(true)
  })

  it('reads only the wallboard endpoint, which has no parameters and fixes the scope to Incident', async () => {
    mount(WallboardView)
    await flushPromises()

    expect(fetchWallboard).toHaveBeenCalledTimes(1)
    // Nothing a client could send would change which tickets it shows.
    expect(vi.mocked(fetchWallboard).mock.calls[0]).toEqual([])
  })

  it('auto-refreshes on an interval and cleans the timer up on unmount', async () => {
    vi.useFakeTimers()

    const wrapper = mount(WallboardView)
    await flushPromises()

    expect(fetchWallboard).toHaveBeenCalledTimes(1)
    expect(fetchFreshness).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()

    expect(fetchWallboard).toHaveBeenCalledTimes(2)
    expect(fetchFreshness).toHaveBeenCalledTimes(2)

    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(120_000)
    await flushPromises()

    // Timer must be cleared on unmount — call counts stay frozen at 2, not creeping to 3/4.
    expect(fetchWallboard).toHaveBeenCalledTimes(2)
    expect(fetchFreshness).toHaveBeenCalledTimes(2)
  })

  it('shows a prominent stale warning when freshness reports stale', async () => {
    vi.mocked(fetchFreshness).mockResolvedValue(
      freshnessResponse({ stale: true, staleReason: 'ETL job has not run' }),
    )

    const wrapper = mount(WallboardView)
    await flushPromises()

    const warning = wrapper.get('[data-testid="wallboard-stale-warning"]')
    expect(warning.attributes('role')).toBe('alert')
    expect(warning.text().toUpperCase()).toContain('STALE')
  })

  it('does not show the stale warning when freshness is fresh', async () => {
    const wrapper = mount(WallboardView)
    await flushPromises()

    expect(wrapper.find('[data-testid="wallboard-stale-warning"]').exists()).toBe(false)
  })

  /**
   * Regression guards for the unattended-freeze defect (Layer 2 code review, 2026-08-23).
   *
   * The access token lives 1h; the wallboard polls every 60s for days with nobody watching.
   * Before the fix, `refresh()` had no catch, so a failed poll left the previous payload on
   * screen AND left `freshness.stale` false — it was computed from that older, then-healthy
   * response. The wall showed hour-old numbers with no warning, which is exactly the failure
   * the freshness indicator exists to prevent.
   */
  it('raises the stale banner when a refresh fails, instead of freezing silently', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchWallboard).mockResolvedValue(wallboardResponse({ breached: 42 }))
    vi.mocked(fetchFreshness).mockResolvedValue(freshnessResponse({ stale: false }))

    const wrapper = mount(WallboardView)
    await flushPromises()
    expect(wrapper.find('[data-testid="wallboard-stale-warning"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="wallboard-breached"]').text()).toContain('42')

    // The screen rotates on the same 60 s beat as the poll; hold it on screen 1, where Breached is.
    await wrapper.get('[data-testid="wallboard-rotation-toggle"]').trigger('click')

    // Every later poll fails, as it would once the access token ages out.
    vi.mocked(fetchWallboard).mockRejectedValue(new Error('401'))
    vi.mocked(fetchFreshness).mockRejectedValue(new Error('401'))
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()

    const banner = wrapper.find('[data-testid="wallboard-stale-warning"]')
    expect(banner.exists()).toBe(true)
    // Names the real cause: unreachable service, not a lagging mirror.
    expect(banner.text()).toContain('Cannot reach the dashboard service')
    // Last good value deliberately stays visible — a blank wallboard is worse than a labelled one.
    expect(wrapper.get('[data-testid="wallboard-breached"]').text()).toContain('42')
  })

  it('clears the failure banner once a later poll succeeds', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchWallboard).mockRejectedValue(new Error('boom'))
    vi.mocked(fetchFreshness).mockRejectedValue(new Error('boom'))

    const wrapper = mount(WallboardView)
    await flushPromises()
    expect(wrapper.find('[data-testid="wallboard-stale-warning"]').exists()).toBe(true)

    // The screen rotates on the same 60 s beat as the poll; hold it on screen 1, where Breached is.
    await wrapper.get('[data-testid="wallboard-rotation-toggle"]').trigger('click')

    vi.mocked(fetchWallboard).mockResolvedValue(wallboardResponse({ breached: 7 }))
    vi.mocked(fetchFreshness).mockResolvedValue(freshnessResponse({ stale: false }))
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()

    expect(wrapper.find('[data-testid="wallboard-stale-warning"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="wallboard-breached"]').text()).toContain('7')
  })

  describe('screen 1, Now', () => {
    it('shows the group counts, the headline figures, the breakdowns and the Critical/High list', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Now')

      const groups = wrapper.get('[data-testid="wallboard-group-counts"]')
      expect(groups.get('[data-scope="INCIDENT"] [data-testid="scope-open"]').text()).toBe('8')
      expect(groups.get('[data-scope="PROBLEM"] [data-testid="scope-open"]').text()).toBe('2')
      expect(
        groups.get('[data-scope="SERVICE_REQUEST"] [data-testid="scope-breached"]').text(),
      ).toBe('7 breached')

      expect(wrapper.get('[data-testid="wallboard-breached"]').text()).toContain('3')
      expect(wrapper.get('[data-testid="wallboard-at-risk"]').text()).toContain('2')
      expect(wrapper.get('[data-testid="wallboard-total"]').text()).toContain('33')
      expect(wrapper.get('[data-testid="wallboard-open"]').text()).toContain('8')
      const compliance = wrapper.get('[data-testid="wallboard-sla-compliance"]').text()
      expect(compliance).toContain('80%')
      expect(compliance).toContain('20 of 25 closed tickets met their SLA')

      expect(
        wrapper
          .findAll('[data-testid="wallboard-stage-row"]')
          .map((r) => r.attributes('data-stage')),
      ).toEqual(['New', 'In Progress'])
      expect(
        wrapper
          .findAll('[data-testid="wallboard-priority-row"]')
          .map((r) => r.attributes('data-priority')),
      ).toEqual(['Critical', 'High'])

      const critical = wrapper.get('[data-testid="wallboard-critical-row"]')
      expect(critical.text()).toContain('#4021')
      expect(critical.text()).toContain('Payment gateway timeout')
      expect(critical.text()).toContain('2.5 of 3 days')
    })

    it('shows number, priority and age only when the subject is withheld, with no "null"', async () => {
      vi.mocked(fetchWallboard).mockResolvedValue(
        wallboardResponse({
          criticalHighOpen: [
            {
              noIncident: 4022,
              subject: null,
              status: 'In Progress',
              priority: 'High',
              elapsedDays: 5,
              slaDays: 7,
            },
          ],
        }),
      )
      const wrapper = mount(WallboardView)
      await flushPromises()

      const row = wrapper.get('[data-testid="wallboard-critical-row"]')
      expect(row.text()).toContain('#4022')
      expect(row.text()).toContain('High')
      expect(row.text()).toContain('5 of 7 days')
      expect(row.text()).not.toContain('null')
      expect(row.text()).not.toContain('In Progress')
      // Number, priority, age: three cells and no empty one.
      expect(row.findAll('span')).toHaveLength(3)
    })

    it('says so when no Critical or High incident is open, instead of an empty box', async () => {
      vi.mocked(fetchWallboard).mockResolvedValue(wallboardResponse({ criticalHighOpen: [] }))
      const wrapper = mount(WallboardView)
      await flushPromises()

      expect(wrapper.get('[data-testid="wallboard-critical-high-empty"]').text()).toBe(
        'No Critical or High incidents are open',
      )
    })

    it('shows a dash-free "nothing closed" note when no ticket has closed, not a fake 0%', async () => {
      vi.mocked(fetchWallboard).mockResolvedValue(
        wallboardResponse({ slaCompliance: { met: 0, closedInPeriod: 0, percent: null } }),
      )
      const wrapper = mount(WallboardView)
      await flushPromises()

      const tile = wrapper.get('[data-testid="wallboard-sla-compliance"]')
      expect(tile.text()).toContain('Nothing closed yet this year')
      expect(tile.text()).not.toContain('0%')
      expect(tile.text()).toContain('·')
    })

    it('keeps the urgent pair, the context row and the lists in that order', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      // A wallboard answers "how bad is it" first: Breached and At risk come before the context
      // figures, which come before the breakdowns.
      const html = wrapper.html()
      const at = (id: string) => html.indexOf(`data-testid="${id}"`)
      expect(at('wallboard-breached')).toBeGreaterThan(at('wallboard-group-counts'))
      expect(at('wallboard-total')).toBeGreaterThan(at('wallboard-at-risk'))
      expect(at('wallboard-by-stage')).toBeGreaterThan(at('wallboard-sla-compliance'))
      expect(at('wallboard-critical-high')).toBeGreaterThan(at('wallboard-by-priority'))
    })

    it('still renders a dimmed placeholder rather than a number before the first poll lands', async () => {
      // `—` at this size renders as a wide white bar that reads as a broken tile. Every tile has
      // to degrade the same way.
      vi.mocked(fetchWallboard).mockRejectedValue(new Error('cold start'))
      vi.mocked(fetchFreshness).mockRejectedValue(new Error('cold start'))

      const wrapper = mount(WallboardView)
      await flushPromises()

      for (const id of ['breached', 'at-risk', 'total', 'open', 'sla-compliance']) {
        expect(wrapper.get(`[data-testid="wallboard-${id}"]`).text()).toContain('·')
      }
    })

    it('shows a loading note and skeleton group tiles while the first poll is in flight', async () => {
      vi.mocked(fetchWallboard).mockReturnValue(new Promise(() => {}))
      const wrapper = mount(WallboardView)
      await flushPromises()

      expect(wrapper.get('[data-testid="wallboard-loading"]').text()).toContain('Loading')
      expect(wrapper.findAll('[data-testid="scope-skeleton"]')).toHaveLength(3)
    })
  })

  describe('screen 2, Trend', () => {
    async function onTrend() {
      const wrapper = mount(WallboardView)
      await flushPromises()
      await wrapper.get('[data-testid="wallboard-rotation-next"]').trigger('click')
      return wrapper
    }

    it('shows the top categories, the monthly figures, resolved, open and average resolution', async () => {
      const wrapper = await onTrend()

      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Trend')
      expect(
        wrapper
          .findAll('[data-testid="wallboard-category-row"]')
          .map((r) => r.attributes('data-category')),
      ).toEqual(['Network', 'Application'])
      const months = wrapper.findAll('[data-testid="wallboard-month-row"]')
      expect(months.map((m) => m.attributes('data-month'))).toEqual(['2026-01', '2026-02'])
      expect(months[0]!.attributes('data-created')).toBe('6')
      expect(months[0]!.attributes('data-closed')).toBe('4')
      // A zero month is still a row, and its text carries both numbers.
      expect(months[1]!.text()).toContain('0 · 0')

      expect(wrapper.get('[data-testid="wallboard-resolved"]').text()).toContain('25')
      expect(wrapper.get('[data-testid="wallboard-open"]').text()).toContain('8')
      expect(wrapper.get('[data-testid="wallboard-avg-resolution"]').text()).toContain('4.2 days')
      // Backlog and the old Closed tile are gone.
      expect(wrapper.find('[data-testid="wallboard-closed"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('Backlog')
      // Group counts belong to screen 1.
      expect(wrapper.find('[data-testid="wallboard-group-counts"]').exists()).toBe(false)
    })

    it('has an info hint on each figure and chart', async () => {
      const wrapper = await onTrend()

      expect(
        wrapper.findAll('[data-testid="info-hint"]').map((hint) => hint.attributes('data-term')),
      ).toEqual(['closed', 'open', 'avgResolution', 'topCategories', 'trend'])
    })

    it('says so when there are no categories yet', async () => {
      vi.mocked(fetchWallboard).mockResolvedValue(wallboardResponse({ topCategories: [] }))
      const wrapper = await onTrend()

      expect(wrapper.get('[data-testid="wallboard-top-categories"]').text()).toContain(
        'No categories this year',
      )
    })

    it('shows a placeholder, not "0 days", when nothing has closed', async () => {
      vi.mocked(fetchWallboard).mockResolvedValue(wallboardResponse({ avgResolutionDays: null }))
      const wrapper = await onTrend()

      expect(wrapper.get('[data-testid="wallboard-avg-resolution"]').text()).toContain('·')
      expect(wrapper.get('[data-testid="wallboard-avg-resolution"]').text()).not.toContain('days')
    })
  })

  describe('?tv=1', () => {
    it('hides the hints and the rotation controls but keeps the countdown and the figures', async () => {
      window.history.replaceState({}, '', '/wallboard?tv=1')

      const wrapper = mount(WallboardView)
      await flushPromises()

      expect(wrapper.findAll('[data-testid="info-hint"]')).toHaveLength(0)
      expect(wrapper.find('[data-testid="wallboard-rotation-controls"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="wallboard-rotation-toggle"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="wallboard-rotation-interval"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="wallboard-rotation-status"]').text()).toContain(
        'Screen 1 of 2',
      )
      expect(wrapper.get('[data-testid="wallboard-open"]').text()).toContain('8')
    })

    it('still rotates', async () => {
      window.history.replaceState({}, '', '/wallboard?tv=1')
      vi.useFakeTimers()

      const wrapper = mount(WallboardView)
      await flushPromises()
      await vi.advanceTimersByTimeAsync(60_000)

      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Trend')
    })
  })

  /** TM11, through the screen. The timing rules themselves are in `useRotation.spec.ts`. */
  describe('rotation', () => {
    it('starts on screen 1 of 2 with a one minute countdown', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      expect(wrapper.get('[data-testid="wallboard-rotation-status"]').text()).toBe(
        'Screen 1 of 2 · next in 60 s',
      )
      expect(
        (wrapper.get('[data-testid="wallboard-rotation-interval"]').element as HTMLSelectElement)
          .value,
      ).toBe('60000')
    })

    it('counts down, then switches screen', async () => {
      vi.useFakeTimers()
      const wrapper = mount(WallboardView)
      await flushPromises()

      await vi.advanceTimersByTimeAsync(18_000)
      expect(wrapper.get('[data-testid="wallboard-rotation-status"]').text()).toBe(
        'Screen 1 of 2 · next in 42 s',
      )
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Now')

      await vi.advanceTimersByTimeAsync(42_000)
      expect(wrapper.get('[data-testid="wallboard-rotation-status"]').text()).toBe(
        'Screen 2 of 2 · next in 60 s',
      )
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Trend')
    })

    it('offers 30 s, 1 min, 2 min and 5 min and remembers the choice', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      const select = wrapper.get('[data-testid="wallboard-rotation-interval"]')
      expect(select.findAll('option').map((o) => o.text())).toEqual([
        '30 s',
        '1 min',
        '2 min',
        '5 min',
      ])

      await select.setValue('120000')

      expect(localStorage.getItem(ROTATION_INTERVAL_KEY)).toBe('120000')
      expect(wrapper.get('[data-testid="wallboard-rotation-status"]').text()).toBe(
        'Screen 1 of 2 · next in 120 s',
      )
      wrapper.unmount()

      const again = mount(WallboardView)
      await flushPromises()
      expect(
        (again.get('[data-testid="wallboard-rotation-interval"]').element as HTMLSelectElement)
          .value,
      ).toBe('120000')
    })

    it('pauses and resumes, and says so', async () => {
      vi.useFakeTimers()
      const wrapper = mount(WallboardView)
      await flushPromises()
      const toggle = wrapper.get('[data-testid="wallboard-rotation-toggle"]')

      await toggle.trigger('click')
      expect(toggle.attributes('aria-pressed')).toBe('true')
      expect(toggle.text()).toBe('Resume rotation')
      await vi.advanceTimersByTimeAsync(300_000)
      expect(wrapper.get('[data-testid="wallboard-rotation-status"]').text()).toBe(
        'Screen 1 of 2 · paused',
      )
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Now')

      await toggle.trigger('click')
      expect(toggle.text()).toBe('Pause rotation')
      await vi.advanceTimersByTimeAsync(60_000)
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Trend')
    })

    it('moves to the next screen on demand', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      await wrapper.get('[data-testid="wallboard-rotation-next"]').trigger('click')
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Trend')
      await wrapper.get('[data-testid="wallboard-rotation-next"]').trigger('click')
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Now')
    })

    it('stops rotating while the tab is hidden', async () => {
      vi.useFakeTimers()
      const wrapper = mount(WallboardView)
      await flushPromises()

      setHidden(true)
      await vi.advanceTimersByTimeAsync(600_000)
      await flushPromises()
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Now')

      setHidden(false)
      await vi.advanceTimersByTimeAsync(60_000)
      expect(wrapper.get('[data-testid="wallboard-screen-title"]').text()).toBe('Trend')
      // Advancing 10 minutes of fake time runs ~20 data refreshes through the mocked API; on a
      // loaded machine that can exceed vitest's 5s default. The assertions are unchanged.
    }, 30_000)

    /**
     * The countdown ticks every second; a live region that followed it would read out a number a
     * second for as long as the display is on. The region is polite, and it changes only when the
     * screen does.
     */
    it('announces the screen change once, not every second', async () => {
      vi.useFakeTimers()
      const wrapper = mount(WallboardView)
      await flushPromises()
      const live = wrapper.get('[data-testid="wallboard-rotation-live"]')

      expect(live.attributes('aria-live')).toBe('polite')
      expect(live.text()).toBe('')

      const seen = new Set<string>()
      for (let second = 0; second < 61; second++) {
        await vi.advanceTimersByTimeAsync(1_000)
        seen.add(live.text())
      }

      expect([...seen].filter(Boolean)).toEqual(['Screen 2 of 2 · next in 60 s'])
    })

    it('draws the progress bar without animation for people who ask for reduced motion', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      const bar = wrapper.get('[data-testid="wallboard-rotation-progress"] > div')
      expect(bar.classes()).toContain('motion-reduce:transition-none')
    })

    it('grows the progress bar as the interval is used up', async () => {
      vi.useFakeTimers()
      const wrapper = mount(WallboardView)
      await flushPromises()

      await vi.advanceTimersByTimeAsync(30_000)

      expect(
        wrapper.get('[data-testid="wallboard-rotation-progress"] > div').attributes('style'),
      ).toContain('width: 50%')
    })

    it('gives the select and both buttons a 44px target', async () => {
      const wrapper = mount(WallboardView)
      await flushPromises()

      for (const id of ['interval', 'toggle', 'next']) {
        expect(wrapper.get(`[data-testid="wallboard-rotation-${id}"]`).classes()).toContain(
          'min-h-11',
        )
      }
    })
  })

  /**
   * This screen had NO h1 at all — its outline was empty — so it failed WCAG 2.4.1 more
   * completely than any other view. `sr-only` on purpose: a visible title would spend vertical
   * space the numbers need, and the existing spec pins `header`/`nav` as absent because their
   * presence is what would make this read as an operator console rather than a display.
   */
  it('has a heading in the outline without putting one on the wall', async () => {
    const wrapper = mount(WallboardView)
    await flushPromises()

    const h1 = wrapper.get('h1')
    expect(h1.text()).toBe('Incident wallboard')
    expect(h1.classes()).toContain('sr-only')
    expect(wrapper.find('header').exists()).toBe(false)
  })
})
