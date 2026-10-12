/**
 * Redmine deep links — the ONE action this read-only product offers.
 *
 * Links are built from `VITE_REDMINE_BASE_URL` plus the numeric issue id only, never from a URL
 * field carried in row data: a row field is user/Redmine-authored content and must never be
 * trusted as a navigation target.
 *
 * Read at CALL time rather than captured at module load, so the table, the mobile card and the
 * drawer all resolve the same value and a spec can exercise the unconfigured deployment without
 * re-importing the component.
 */

/**
 * The configured base, or `null` when the deployment has none.
 *
 * Trailing slashes are stripped, because the value is configured by a human and
 * `https://mtiredmine.yokke.co.id/` is at least as natural to type as the bare origin. Without
 * this, the deep link becomes `https://…co.id//issues/4021` — which most servers tolerate and
 * some redirect or 404, for no reason the operator could ever guess from the config.
 *
 * There is deliberately NO placeholder fallback. A `?? 'https://redmine.example.com'` used to
 * sit in the drawer, and the demo compose file did not set the variable — so the single call to
 * action silently linked to a domain nobody owns. Callers render plain text instead.
 */
export function redmineBaseUrl(): string | null {
  const configured = (import.meta.env.VITE_REDMINE_BASE_URL as string | undefined)
    ?.trim()
    .replace(/\/+$/, '')
  return configured ? configured : null
}

/** The issue's Redmine URL, or `null` when either the id or the base URL is missing. */
export function redmineIssueUrl(issueId: unknown): string | null {
  const base = redmineBaseUrl()
  const id = redmineIssueId(issueId)
  return base === null || id === null ? null : `${base}/issues/${encodeURIComponent(id)}`
}

/** `NO_INCIDENT` as a display string, or `null` when the row carries no usable id. */
export function redmineIssueId(value: unknown): string | null {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  if (typeof value === 'string' && value.trim() !== '') return value.trim()
  return null
}
