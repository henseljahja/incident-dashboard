import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import KpiCard from '../KpiCard.vue'

/**
 * The card used to render a bare arrow: "95 ▲". Up by one, or up by forty? An arrow with no
 * number tells a responder that something moved and then sends them elsewhere to find out how
 * much — the exact trip the tile exists to save. The Executive view already printed its
 * magnitudes; the operator's own KPI row did not.
 */
describe('KpiCard trend', () => {
  function trendOf(props: Record<string, unknown>) {
    const wrapper = mount(KpiCard, { props: { label: 'Backlog', ...props } as never })
    const trend = wrapper.find('[data-testid="kpi-trend"]')
    return trend.exists() ? trend : null
  }

  it('prints how far a rise went, not just that it rose', () => {
    const trend = trendOf({ value: 95, previousValue: 83 })!
    expect(trend.attributes('data-trend')).toBe('up')
    expect(trend.text()).toContain('12')
  })

  it('prints the magnitude of a fall too', () => {
    const trend = trendOf({ value: 83, previousValue: 95 })!
    expect(trend.attributes('data-trend')).toBe('down')
    // The sign is carried by the arrow and the colour; the number is the size, never negative.
    expect(trend.text()).toContain('12')
    expect(trend.text()).not.toContain('-')
  })

  /** The two MTTR cards arrive pre-rounded as e.g. `4.2`, so a whole-number format would lie. */
  it('keeps one decimal for fractional metrics and none for counts', () => {
    expect(trendOf({ value: '4.2', previousValue: 9.5 })!.text()).toContain('5.3')
    expect(trendOf({ value: 10, previousValue: 4 })!.text()).toContain('6')
  })

  it('shows a flat marker with no magnitude when nothing changed', () => {
    const trend = trendOf({ value: 95, previousValue: 95 })!
    expect(trend.attributes('data-trend')).toBe('flat')
    expect(trend.attributes('data-delta')).toBeUndefined()
  })

  it('renders no trend at all when there is no prior period', () => {
    // `previousPeriod` is null for an unbounded window — a fabricated 0 delta would read as
    // "nothing changed", which is a different claim from "there is nothing to compare with".
    expect(trendOf({ value: 95, previousValue: null })).toBeNull()
  })

  /**
   * "▲12" announces as punctuation or as nothing at all depending on the screen reader, so the
   * movement has to live in the accessible name. Up is bad on every card here, so the direction
   * is named as worse/better rather than up/down — a listener has no colour to infer it from.
   */
  it('states the movement in words in the accessible name', () => {
    const wrapper = mount(KpiCard, {
      props: {
        label: 'Breached',
        value: 9,
        previousValue: 4,
        actionLabel: 'Scroll to the incident table',
      },
    })

    const label = wrapper.get('[data-testid="kpi-card"]').attributes('aria-label')!
    expect(label).toContain('Breached: 9.')
    expect(label).toContain('5 worse than the previous period.')
    expect(label).toContain('Scroll to the incident table.')
  })
})
