import { formatDate, parseDate } from './date'

/**
 * The window immediately before `[from, to]`, equal in length (inclusive of both ends), as
 * `YYYY-MM-DD`; `null` when either bound is open — there is no honest "previous period" for
 * "all time". Calendar arithmetic is local (see `date.ts`), never `toISOString()`.
 */
export function previousWindow(
  from: string | null,
  to: string | null,
): { from: string; to: string } | null {
  const start = parseDate(from)
  const end = parseDate(to)
  if (!start || !end || end < start) return null
  const days = Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1
  const prevEnd = new Date(start.getFullYear(), start.getMonth(), start.getDate() - 1)
  const prevStart = new Date(
    prevEnd.getFullYear(),
    prevEnd.getMonth(),
    prevEnd.getDate() - (days - 1),
  )
  return { from: formatDate(prevStart), to: formatDate(prevEnd) }
}

/**
 * Change of `current` against `previous` as a percentage of `previous`; `null` when there is no
 * baseline (previous is 0 or either side is missing) — "no baseline" is not "unchanged".
 */
export function deltaPct(current: number | null, previous: number | null): number | null {
  if (current === null || previous === null || previous === 0) return null
  return ((current - previous) * 100) / Math.abs(previous)
}
