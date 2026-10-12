import { describe, expect, it } from 'vitest'

import { deltaPct, previousWindow } from '../period'
import { priorityKey, priorityTotal } from '../priority'

describe('previousWindow', () => {
  it('is the equal-length window ending the day before', () => {
    // 30 days inclusive: 13 Sep .. 12 Oct  ->  14 Aug .. 12 Sep
    expect(previousWindow('2026-09-13', '2026-10-12')).toEqual({
      from: '2026-08-14',
      to: '2026-09-12',
    })
  })

  it('a one-day window compares against the single day before', () => {
    expect(previousWindow('2026-03-01', '2026-03-01')).toEqual({
      from: '2026-02-28',
      to: '2026-02-28',
    })
  })

  it('crosses a year boundary', () => {
    expect(previousWindow('2026-01-01', '2026-01-31')).toEqual({
      from: '2025-12-01',
      to: '2025-12-31',
    })
  })

  it('has no previous period for an open or inverted window', () => {
    expect(previousWindow(null, '2026-01-31')).toBeNull()
    expect(previousWindow('2026-01-01', null)).toBeNull()
    expect(previousWindow('2026-02-01', '2026-01-01')).toBeNull()
  })
})

describe('deltaPct', () => {
  it('is relative to the previous value', () => {
    expect(deltaPct(15, 10)).toBe(50)
    expect(deltaPct(5, 10)).toBe(-50)
    expect(deltaPct(10, 10)).toBe(0)
  })

  it('is null, not 0 or Infinity, without a baseline', () => {
    expect(deltaPct(5, 0)).toBeNull()
    expect(deltaPct(null, 5)).toBeNull()
    expect(deltaPct(5, null)).toBeNull()
  })
})

describe('priority helpers', () => {
  it('maps names case-insensitively and everything else to other', () => {
    expect(priorityKey('High')).toBe('high')
    expect(priorityKey(' CRITICAL ')).toBe('critical')
    expect(priorityKey('Urgent!')).toBe('other')
    expect(priorityKey(null)).toBe('other')
  })

  it('sums every bucket', () => {
    expect(priorityTotal({ critical: 1, high: 2, medium: 3, low: 4, other: 5 })).toBe(15)
  })
})
