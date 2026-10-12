import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { DefinitionsResponse } from '@/apis/definitions'
import { i18n } from '@/i18n'

vi.mock('@/apis/definitions', async () => {
  const actual = await vi.importActual<typeof import('@/apis/definitions')>('@/apis/definitions')
  return { ...actual, fetchDefinitions: vi.fn<() => Promise<DefinitionsResponse>>() }
})

import { fetchDefinitions } from '@/apis/definitions'

import InfoHint from '../InfoHint.vue'

/** Invented policy, shaped like the definitions route. Matches the plan's documented cushions. */
function definitions(): DefinitionsResponse {
  return {
    slaPolicy: [
      { priorityId: 4, priorityName: 'Critical', slaDays: 3, atRiskRemainingDays: 1 },
      { priorityId: 3, priorityName: 'High', slaDays: 7, atRiskRemainingDays: 2 },
      { priorityId: 2, priorityName: 'Medium', slaDays: 14, atRiskRemainingDays: 3 },
      { priorityId: 1, priorityName: 'Low', slaDays: 60, atRiskRemainingDays: 7 },
    ],
    unknownPriority: { slaDays: 3, atRiskRemainingDays: 1 },
    closedByOverride: [
      { statusId: 901, statusName: 'Closed Pending Review' },
      { statusId: 902, statusName: 'Closed With Waiver' },
    ],
    closeRule: ['CLOSE_DATE', 'CLOSED_ON', 'STATUS_CHANGE'],
    refreshIntervalMinutes: 2,
  }
}

let wrapper: VueWrapper | null = null

function mountHint(term: InstanceType<typeof InfoHint>['$props']['term']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  wrapper = mount(InfoHint, {
    props: { term },
    attachTo: document.body,
    global: { plugins: [PrimeVue, [VueQueryPlugin, { queryClient }]] },
  })
  return wrapper
}

const panel = () => document.body.querySelector<HTMLElement>('[data-testid="info-hint-panel"]')
const inPanel = (testId: string) =>
  panel()?.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.textContent?.trim()

beforeEach(() => {
  vi.resetAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('InfoHint', () => {
  it('is a separate button named after its term, and fetches nothing until opened', () => {
    const hint = mountHint('atRisk').get('[data-testid="info-hint"]')

    expect(hint.element.tagName).toBe('BUTTON')
    expect(hint.attributes('type')).toBe('button')
    expect(hint.attributes('aria-label')).toBe('About At risk')
    expect(hint.attributes('aria-expanded')).toBe('false')
    expect(fetchDefinitions).not.toHaveBeenCalled()
    expect(panel()).toBeNull()
  })

  it('shows meaning, formula and source with live cushions once definitions load', async () => {
    vi.mocked(fetchDefinitions).mockResolvedValue(definitions())
    const w = mountHint('atRisk')

    await w.get('[data-testid="info-hint"]').trigger('click')
    await flushPromises()

    expect(fetchDefinitions).toHaveBeenCalledTimes(1)
    expect(w.get('[data-testid="info-hint"]').attributes('aria-expanded')).toBe('true')
    expect(inPanel('info-hint-meaning')).toBe('Open tickets close to breaching their SLA')
    expect(inPanel('info-hint-formula')).toContain('Critical 1 · High 2 · Medium 3 · Low 7 days')
    expect(inPanel('info-hint-sla-days')).toBe(
      'SLA days: Critical 3 · High 7 · Medium 14 · Low 60 · No priority 3 days',
    )
    expect(inPanel('info-hint-source')).toContain('SLA policy')
    expect(panel()?.textContent).toContain('SPO IT Service Management 2023, p.14')
  })

  it('names the closed-status override list from the route', async () => {
    vi.mocked(fetchDefinitions).mockResolvedValue(definitions())
    const w = mountHint('closed')

    await w.get('[data-testid="info-hint"]').trigger('click')
    await flushPromises()

    expect(inPanel('info-hint-formula')).toBe(
      'Count where status is closed: Redmine closed statuses, plus Closed Pending Review, Closed With Waiver',
    )
  })

  it('keeps meaning and formula when the definitions route fails, and says the numbers are unavailable', async () => {
    vi.mocked(fetchDefinitions).mockRejectedValue(new Error('500'))
    const w = mountHint('atRisk')

    await w.get('[data-testid="info-hint"]').trigger('click')
    await flushPromises()

    expect(inPanel('info-hint-meaning')).toBe('Open tickets close to breaching their SLA')
    const formula = inPanel('info-hint-formula')
    expect(formula).toContain('Open, not breached, and days left ≤ cushion.')
    expect(formula).toContain("Current thresholds couldn't be loaded; see SPO ITSM 2023 p.14")
    expect(formula).not.toContain('Critical 1')
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    vi.mocked(fetchDefinitions).mockResolvedValue(definitions())
    const w = mountHint('breached')
    const trigger = w.get('[data-testid="info-hint"]')

    ;(trigger.element as HTMLButtonElement).focus()
    await trigger.trigger('click')
    await flushPromises()
    expect(panel()).not.toBeNull()
    ;(document.body as HTMLElement).focus()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()

    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
  })

  it('renders its copy in Indonesian', async () => {
    vi.mocked(fetchDefinitions).mockResolvedValue(definitions())
    i18n.global.locale.value = 'id'
    const w = mountHint('atRisk')

    expect(w.get('[data-testid="info-hint"]').attributes('aria-label')).toBe('Tentang Berisiko')
    await w.get('[data-testid="info-hint"]').trigger('click')
    await flushPromises()

    expect(inPanel('info-hint-meaning')).toBe('Tiket terbuka yang hampir melewati SLA')
    expect(inPanel('info-hint-formula')).toContain('Critical 1 · High 2 · Medium 3 · Low 7 hari')
  })
})
