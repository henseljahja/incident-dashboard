/**
 * Calendar-date <-> `YYYY-MM-DD` conversion for filter bounds.
 *
 * <h2>Why this file exists</h2>
 *
 * A filter bound is a CALENDAR DATE, not an instant. "From 2 August" means the
 * second of August wherever the operator is sitting — it does not shift because
 * the server is in UTC.
 *
 * `Date.prototype.toISOString()` renders the UTC instant. PrimeVue's `DatePicker`
 * emits a `Date` at LOCAL midnight. Compose the two and every bound moves back a
 * day for any positive UTC offset:
 *
 * ```
 * TZ=Asia/Jakarta (UTC+7)
 * picked            2026-08-02T00:00:00+07:00   (operator clicked 2 August)
 * .toISOString()    2026-08-01T17:00:00Z
 * .slice(0, 10)     "2026-08-01"                <-- off by one, silently
 * ```
 *
 * That shipped in `IncidentFilterBar` and was caught by the staff-engineer
 * quorum on 2026-08-23. It was invisible in use because every surface on the Ops
 * page — KPI row, heatmap, SLA buckets, trend, table and export — reads the same
 * filter store, so all of them shifted together and stayed mutually consistent
 * while being uniformly wrong. It also poisoned `EXPORT_AUDIT.FILTER_JSON`,
 * recording a window the operator never asked for.
 *
 * The backend goes out of its way to avoid exactly this off-by-one: the query
 * predicate uses a half-open `< dateTo + 1 day` bound so the final day is never
 * dropped. Formatting the bound wrong on the client reintroduces the bug
 * upstream of that fix.
 *
 * <b>Use these two functions for every filter bound. Never `toISOString()` on a
 * picked date.</b> (Timestamps are a different matter — an instant genuinely is
 * UTC and `toISOString` is right for those.)
 */

/** `YYYY-MM-DD` from a `Date`'s LOCAL calendar components. */
export function formatDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * A `Date` at LOCAL midnight from `YYYY-MM-DD`.
 *
 * Not `new Date(value)`: the one-argument string form parses a bare date as UTC
 * midnight, which renders as the PREVIOUS day for any negative UTC offset and
 * makes the round trip through {@link formatDate} lossy. The numeric constructor
 * is local by definition, so `parseDate(formatDate(d))` is the identity on the
 * calendar date for every timezone.
 */
export function parseDate(value: string | null): Date | null {
  if (!value) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return null
  const [, year, month, day] = match
  return new Date(Number(year), Number(month) - 1, Number(day))
}

/**
 * <h2>Display formatting — a different job from the two functions above</h2>
 *
 * `formatDate`/`parseDate` produce QUERY values and must never depend on the reader's language.
 * The functions below produce TEXT for a human, in the active UI locale, and must never be fed
 * back into a filter.
 *
 * Displayed instants are pinned to WIB (`Asia/Jakarta`), 24-hour. The mirror, the ETL and every
 * stakeholder are on WIB; a laptop on UTC rendering "last mirrored 03:12" for a 10:12 refresh
 * would read as a stalled pipeline. `toLocaleString()` with no arguments did exactly that — and
 * also picked the BROWSER's language rather than the app's.
 */
export const DISPLAY_TIME_ZONE = 'Asia/Jakarta'

/** `15 Sep 2026` (en) / `15 Sep 2026` (id — Intl supplies the Indonesian month abbreviations). */
export function formatDisplayDate(value: string | Date | null, locale: string): string | null {
  const parsed = toInstant(value)
  if (!parsed) return typeof value === 'string' && value ? value : null
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: DISPLAY_TIME_ZONE,
  }).format(parsed)
}

/**
 * `15 Sep 2026, 10:12` — for timestamps (last mirrored, last sign-in). `null` when there is no
 * value, so the caller chooses the words for "never"; an unparseable string is returned as-is
 * rather than rendered as "Invalid Date".
 */
export function formatDisplayDateTime(value: string | Date | null, locale: string): string | null {
  const parsed = toInstant(value)
  if (!parsed) return typeof value === 'string' && value ? value : null
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: DISPLAY_TIME_ZONE,
  }).format(parsed)
}

function toInstant(value: string | Date | null): Date | null {
  if (value === null || value === '') return null
  const parsed = value instanceof Date ? value : new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}
