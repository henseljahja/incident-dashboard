/**
 * Locale-aware number rendering. The one place a figure becomes display text.
 *
 * `toFixed(1)` printed `2.5` to an Indonesian reader, for whom that is two thousand five hundred
 * — the decimal separator is a comma (`2,5`) and the dot groups thousands. `Intl.NumberFormat`
 * with the ACTIVE locale gets both right, and is also what puts the `1,234` grouping on counts.
 *
 * Display only. Never feed the output back into arithmetic or a query string.
 */

/** A count: grouped, no fraction. */
export function formatCount(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value)
}

/**
 * A duration or ratio: at most `maximumFractionDigits` decimals (one by default), so whole
 * numbers stay whole (`14`) and fractions keep their tenth (`4.2` / `4,2`).
 */
export function formatDecimal(
  value: number,
  locale: string,
  options: { maximumFractionDigits?: number; minimumFractionDigits?: number } = {},
): string {
  const { maximumFractionDigits = 1, minimumFractionDigits = 0 } = options
  return new Intl.NumberFormat(locale, { maximumFractionDigits, minimumFractionDigits }).format(
    value,
  )
}

/**
 * A period-over-period movement with its sign: `+5`, `-2`, and `±0` for no change.
 *
 * `±0` rather than Intl's bare `0`: an unsigned zero next to a column of signed deltas reads as
 * "no comparison", which is a different fact from "compared, and unchanged".
 */
export function formatSignedDelta(
  value: number,
  locale: string,
  options: { maximumFractionDigits?: number; minimumFractionDigits?: number } = {},
): string {
  const { maximumFractionDigits = 1, minimumFractionDigits = 0 } = options
  if (value === 0) {
    return `±${formatDecimal(0, locale, { maximumFractionDigits, minimumFractionDigits })}`
  }
  return new Intl.NumberFormat(locale, {
    maximumFractionDigits,
    minimumFractionDigits,
    signDisplay: 'always',
  }).format(value)
}

/** A whole-number percentage (`42` -> `42%`), with the locale's own spacing and sign placement. */
export function formatPercent(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 }).format(
    value / 100,
  )
}
