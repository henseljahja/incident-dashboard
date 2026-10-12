import { describe, expect, it } from 'vitest'
import { formatDate, parseDate } from '../date'

/**
 * Regression tests for the filter-bound off-by-one found by the staff-engineer
 * quorum on 2026-08-23.
 *
 * `IncidentFilterBar` formatted a picked date with `toISOString().slice(0, 10)`.
 * PrimeVue's `DatePicker` emits LOCAL midnight, `toISOString` renders UTC, so in
 * Asia/Jakarta (UTC+7) picking 2 August sent `2026-08-01` to the API. Every
 * surface on the Ops page reads the same store, so all of them shifted together
 * and stayed mutually consistent while being uniformly wrong.
 *
 * These tests are worthless unless they run at a NON-ZERO UTC offset — under
 * `TZ=UTC` the broken implementation passes. `vitest.config.ts` pins
 * `TZ=Asia/Jakarta` for exactly this reason; the guard below fails loudly if that
 * pinning is ever removed, rather than letting these silently stop testing
 * anything.
 */
describe('filter-bound date conversion', () => {
  it('runs at a non-zero UTC offset, or these tests prove nothing', () => {
    expect(new Date(2026, 7, 2).getTimezoneOffset()).not.toBe(0)
  })

  it('formats the LOCAL calendar date, not the UTC instant', () => {
    // Local midnight on 2 August. The old toISOString() path yielded 2026-08-01.
    expect(formatDate(new Date(2026, 7, 2))).toBe('2026-08-02')
  })

  it('formats a local time late enough to cross the UTC date line', () => {
    // 23:30 local on 2 Aug is 16:30Z the same day at +07:00, but any offset
    // greater than +00:30 would push a naive UTC render onto the 3rd.
    expect(formatDate(new Date(2026, 7, 2, 23, 30))).toBe('2026-08-02')
  })

  it('parses to local midnight so the round trip is lossless', () => {
    const parsed = parseDate('2026-08-02')
    expect(parsed).not.toBeNull()
    expect(parsed!.getFullYear()).toBe(2026)
    expect(parsed!.getMonth()).toBe(7)
    expect(parsed!.getDate()).toBe(2)
  })

  it('round-trips every bound unchanged', () => {
    for (const value of ['2026-01-01', '2026-08-02', '2026-12-31', '2024-02-29']) {
      expect(formatDate(parseDate(value)!)).toBe(value)
    }
  })

  it('pads single-digit months and days', () => {
    expect(formatDate(new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  it('treats an absent or malformed bound as no bound', () => {
    expect(parseDate(null)).toBeNull()
    expect(parseDate('')).toBeNull()
    expect(parseDate('02-08-2026')).toBeNull()
    expect(parseDate('not a date')).toBeNull()
  })
})

describe('display formatting follows the UI locale, in Asia/Jakarta', () => {
  it('formats a date in English and Indonesian', async () => {
    const { formatDisplayDate } = await import('../date')
    // 2026-08-16T20:00Z is already 17 August in Jakarta (UTC+7).
    expect(formatDisplayDate('2026-08-16T20:00:00Z', 'en')).toBe('Aug 17, 2026')
    expect(formatDisplayDate('2026-08-16T20:00:00Z', 'id')).toBe('17 Agu 2026')
  })

  it('formats a timestamp on a 24-hour clock', async () => {
    const { formatDisplayDateTime } = await import('../date')
    expect(formatDisplayDateTime('2026-08-17T06:05:00Z', 'id')).toContain('13.05')
    expect(formatDisplayDateTime('2026-08-17T06:05:00Z', 'en')).toContain('13:05')
  })

  it('returns null for an empty value and the raw text for an unparseable one', async () => {
    const { formatDisplayDate, formatDisplayDateTime } = await import('../date')
    expect(formatDisplayDate(null, 'en')).toBe(null)
    expect(formatDisplayDateTime('', 'id')).toBe(null)
    expect(formatDisplayDateTime('not a date', 'id')).toBe('not a date')
  })
})
