import { describe, expect, it } from 'vitest'

import { BRAND_PRIMARY, SEVERITY_PALETTE, slaBucket } from '../severity'

describe('slaBucket', () => {
  it('returns Unknown for a null elapsed duration', () => {
    expect(slaBucket(null, 7)).toBe('Unknown')
  })

  it('returns Breached when elapsed exceeds the SLA budget', () => {
    expect(slaBucket(8, 7)).toBe('Breached') // slaDays + 1
  })

  it('returns Breached at the boundary + 1 for a larger SLA', () => {
    expect(slaBucket(15, 14)).toBe('Breached')
  })

  it('returns 80-100% exactly at the SLA boundary (elapsed === slaDays)', () => {
    expect(slaBucket(7, 7)).toBe('80-100%')
  })

  it('returns 80-100% exactly at the 0.8 boundary', () => {
    expect(slaBucket(8, 10)).toBe('80-100%') // 10 * 0.8 = 8
  })

  it('returns 50-80% just below the 0.8 boundary', () => {
    expect(slaBucket(7, 10)).toBe('50-80%')
  })

  it('returns 50-80% exactly at the 0.5 boundary', () => {
    expect(slaBucket(5, 10)).toBe('50-80%') // 10 * 0.5 = 5
  })

  it('returns 0-50% just below the 0.5 boundary', () => {
    expect(slaBucket(4, 10)).toBe('0-50%')
  })

  it('returns 0-50% for zero elapsed days', () => {
    expect(slaBucket(0, 10)).toBe('0-50%')
  })
})

describe('SEVERITY_PALETTE', () => {
  it('never uses the brand primary colour for any band', () => {
    for (const [, colour] of Object.entries(SEVERITY_PALETTE)) {
      expect(colour.toLowerCase()).not.toBe(BRAND_PRIMARY.toLowerCase())
    }
  })

  it('defines a colour for every SlaBucket band', () => {
    expect(Object.keys(SEVERITY_PALETTE).sort()).toEqual(
      ['Unknown', 'Breached', '80-100%', '50-80%', '0-50%'].sort(),
    )
  })
})
