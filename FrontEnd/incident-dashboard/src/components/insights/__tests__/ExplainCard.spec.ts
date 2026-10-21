import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/apis/insights', async () => {
  const actual = await vi.importActual<typeof import('@/apis/insights')>('@/apis/insights')
  return {
    ...actual,
    fetchAiStatus: vi.fn<() => Promise<unknown>>(),
    fetchExplanation: vi.fn<(...a: unknown[]) => Promise<unknown>>(),
  }
})

import * as api from '@/apis/insights'
import { i18n } from '@/i18n'
import { useFiltersStore } from '@/stores/filters'

import ExplainCard from '../ExplainCard.vue'
import { mountInsight, unmountAll } from './helpers'

const LIVE: api.AiStatus = { enabled: true, narrating: true, provider: 'openai' }

beforeEach(() => {
  vi.resetAllMocks()
  i18n.global.locale.value = 'en'
  vi.mocked(api.fetchAiStatus).mockResolvedValue(LIVE)
})
afterEach(unmountAll)

const mountCard = (
  props: { topic: api.ExplainTopic; horizonDays?: number } = { topic: 'forecast' },
) => mountInsight(ExplainCard, { props })

describe('ExplainCard', () => {
  it('renders nothing while the server has no real narrator', async () => {
    vi.mocked(api.fetchAiStatus).mockResolvedValue({
      enabled: false,
      narrating: false,
      provider: 'local',
    })
    const w = await mountCard()

    expect(w.find('[data-testid="explain-card"]').exists()).toBe(false)
  })

  it('renders nothing when the status call fails (no button that cannot work)', async () => {
    vi.mocked(api.fetchAiStatus).mockRejectedValue(new Error('down'))
    const w = await mountCard()

    expect(w.find('[data-testid="explain-card"]').exists()).toBe(false)
  })

  it('makes no model call until the button is pressed', async () => {
    const w = await mountCard()

    expect(w.find('[data-testid="explain-button"]').exists()).toBe(true)
    expect(api.fetchExplanation).not.toHaveBeenCalled()
  })

  it('explains: sends topic, language and horizon; shows bullets without list markers', async () => {
    vi.mocked(api.fetchExplanation).mockResolvedValue({
      topic: 'forecast',
      narrative: '- Volume is rising.\n* Watch Mondays.\n1. Staff up.\n\n',
      provider: 'openai',
    })
    const w = await mountCard({ topic: 'forecast', horizonDays: 14 })

    await w.get('[data-testid="explain-button"]').trigger('click')
    await flushPromises()

    expect(api.fetchExplanation).toHaveBeenCalledWith(
      'forecast',
      expect.any(URLSearchParams),
      'en',
      14,
    )
    const items = w.findAll('[data-testid="explain-result"] li').map((li) => li.text())
    expect(items).toEqual(['Volume is rising.', 'Watch Mondays.', 'Staff up.'])
    expect(w.get('[data-testid="explain-privacy"]').text()).toContain('openai')
    expect(w.get('[data-testid="explain-status"]').text()).toBe('Explanation ready: 3 points.')
    expect(w.get('[data-testid="explain-button"]').text()).toContain('Explain again')
  })

  it('asks in Indonesian when the UI is Indonesian', async () => {
    i18n.global.locale.value = 'id'
    vi.mocked(api.fetchExplanation).mockResolvedValue({
      topic: 'rca',
      narrative: '- ok',
      provider: 'openai',
    })
    const w = await mountCard({ topic: 'rca' })

    await w.get('[data-testid="explain-button"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(api.fetchExplanation).mock.calls[0]?.[2]).toBe('id')
    expect(w.get('[data-testid="explain-button"]').text()).toContain('Jelaskan lagi')
  })

  it('a null narrative is "unavailable", never invented text, and retry calls again', async () => {
    vi.mocked(api.fetchExplanation).mockResolvedValue({
      topic: 'alerts',
      narrative: null,
      provider: 'openai',
    })
    const w = await mountCard({ topic: 'alerts' })

    await w.get('[data-testid="explain-button"]').trigger('click')
    await flushPromises()
    const message = w.get('[data-testid="explain-error"] span')
    // Not an outage, so a polite status rather than an assertive alert.
    expect(message.attributes('role')).toBe('status')
    expect(message.text()).toContain('did not return an explanation')
    expect(w.find('[data-testid="explain-result"]').exists()).toBe(false)

    await w.get('[data-testid="explain-retry"]').trigger('click')
    await flushPromises()
    expect(api.fetchExplanation).toHaveBeenCalledTimes(2)
  })

  it('a failed request shows the error and keeps the page usable', async () => {
    vi.mocked(api.fetchExplanation).mockRejectedValue(new Error('500'))
    const w = await mountCard({ topic: 'alerts' })

    await w.get('[data-testid="explain-button"]').trigger('click')
    await flushPromises()

    expect(w.get('[data-testid="explain-error"] [role="alert"]').text()).toContain(
      'Could not reach',
    )
    expect(w.get('[data-testid="explain-status"]').text()).toContain('Could not reach')
    expect(w.get('[data-testid="explain-button"]').attributes('disabled')).toBeUndefined()
  })

  it('marks a reading stale when the filters change after it was written', async () => {
    vi.mocked(api.fetchExplanation).mockResolvedValue({
      topic: 'alerts',
      narrative: '- fine',
      provider: 'openai',
    })
    const w = await mountCard({ topic: 'alerts' })
    await w.get('[data-testid="explain-button"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="explain-stale"]').exists()).toBe(false)

    useFiltersStore().setScope('PROBLEM')
    await flushPromises()

    expect(w.get('[data-testid="explain-stale"]').text()).toContain('Filters changed')
  })
})
