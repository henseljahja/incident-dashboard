import type { PriorityCounts } from '@/apis/insights'

/**
 * Priority is an ORDERED severity, so it gets an ordered warm-to-cool ramp rather than four
 * unrelated hues: Critical reads as the most urgent at a glance, Low recedes. The hex values are
 * the validated reference palette's red / orange / yellow / blue-300, defined once here so every
 * chart, pill and table uses the same four colours for the same four words.
 *
 * Colour is never the only carrier: every use pairs it with the word (legend, pill label,
 * column header) — see the table fallback in each chart.
 */
export const PRIORITY_KEYS = ['critical', 'high', 'medium', 'low', 'other'] as const
export type PriorityKey = (typeof PRIORITY_KEYS)[number]

export const PRIORITY_COLOR: Record<PriorityKey, string> = {
  critical: '#c0312f',
  high: '#eb6834',
  medium: '#eda100',
  low: '#6da7ec',
  other: '#9a9a96',
}

/** Text colours that clear 4.5:1 on their own tinted pill background. */
export const PRIORITY_PILL: Record<PriorityKey, { bg: string; fg: string }> = {
  critical: { bg: '#fde8e8', fg: '#9b1c1c' },
  high: { bg: '#fdebe0', fg: '#9a3a12' },
  medium: { bg: '#fdf3d6', fg: '#7a5200' },
  low: { bg: '#e1edfb', fg: '#184f95' },
  other: { bg: '#ececea', fg: '#454542' },
}

/** `'High'` -> `'high'`; anything outside the ladder -> `'other'`. */
export function priorityKey(name: string | null | undefined): PriorityKey {
  const key = (name ?? '').trim().toLowerCase()
  return (PRIORITY_KEYS as readonly string[]).includes(key) && key !== 'other'
    ? (key as PriorityKey)
    : 'other'
}

export function priorityTotal(counts: PriorityCounts): number {
  return counts.critical + counts.high + counts.medium + counts.low + counts.other
}

/** Mean-style figure `14.5`; the em dash for "no basis". */
export function orDash(value: number | null | undefined, format: (v: number) => string): string {
  return value === null || value === undefined ? '—' : format(value)
}
