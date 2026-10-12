import { defineStore } from 'pinia'
import { ref } from 'vue'

import { formatDate } from '@/utils/date'

export const DEFAULT_PAGE_SIZE = 25

/** `GET /api/incidents?status=` — mirrors the backend `StatusFacet` enum exactly. */
export const STATUS_FACETS = ['OPEN', 'CLOSED'] as const
export type StatusFacet = (typeof STATUS_FACETS)[number]

/** `GET /api/incidents?sla=` — mirrors the backend `SlaFacet` enum exactly. */
export const SLA_FACETS = ['BREACHED', 'AT_RISK'] as const
export type SlaFacet = (typeof SLA_FACETS)[number]

/**
 * `GET /api/incidents?scope=` — the three ticket groups, mirroring the backend `SCOPE_GROUP`
 * values (`CFG_TRACKER_SCOPE`). Each group is its own menu and its own page.
 */
/** Period presets from the RD home mock-up: 30/60/90 days, 6 months, all time. */
export const PERIOD_PRESETS = ['30d', '60d', '90d', '6m', 'all'] as const
export type PeriodPreset = (typeof PERIOD_PRESETS)[number]

export const SCOPES = ['INCIDENT', 'PROBLEM', 'SERVICE_REQUEST'] as const
export type Scope = (typeof SCOPES)[number]

/**
 * The trackers each scope holds — DATA values, the exact strings the backend filters on, so they
 * are never translated. Mirrors the `CFG_TRACKER_SCOPE` seed (7 / 41 / 16, 32, 33).
 */
export const SCOPE_TRACKERS: Record<Scope, readonly string[]> = {
  INCIDENT: ['Incident'],
  PROBLEM: ['Problem'],
  SERVICE_REQUEST: ['SR - Infra', 'SR - BAU', 'SR - Patch'],
}

/**
 * A facet token from the URL, or `null`.
 *
 * The backend answers an unknown facet with 400 `invalid_filter`, so a bookmarked `?sla=NOPE`
 * forwarded as-is would turn a stale link into a broken page. Dropping it on hydrate means the
 * API is never asked, and the rest of the link's filters still apply.
 */
function facetFrom<T extends string>(allowed: readonly T[], raw: string | null): T | null {
  return raw !== null && (allowed as readonly string[]).includes(raw) ? (raw as T) : null
}

/**
 * The Ops filter bar's state — the query string of `GET /api/incidents` /
 * `GET /api/incidents/export` before it hits the wire. Field names mirror
 * `IncidentFilterRequest` exactly, so a filter added on the backend needs a matching key here,
 * not a re-derivation of what "matching" means.
 */
export const useFiltersStore = defineStore('filters', () => {
  const dateFrom = ref<string | null>(null)
  const dateTo = ref<string | null>(null)
  const tracker = ref<string[]>([])
  const stage = ref<string[]>([])
  const priority = ref<string[]>([])
  const institusi = ref<string[]>([])
  const category = ref<string[]>([])
  const hasWorkaround = ref<boolean | null>(null)
  /** Set by the Open/Closed KPI tiles. Single value: a ticket is one or the other. */
  const status = ref<StatusFacet | null>(null)
  /** Set by the Breached/At Risk KPI tiles. Single value, independent of `status`. */
  const sla = ref<SlaFacet | null>(null)
  /**
   * Narrows the list to the tickets that have no impact or urgency — what the matrix's "N
   * missing" link opens. Same population the matrix's `missingCount` counts.
   */
  const matrixMissing = ref(false)
  /**
   * Which ticket group this page shows. DELIBERATELY not part of the filter state the user can
   * edit: `reset()`, `applyFromQueryString()` and `serialize()` never touch it, so "Clear all" or
   * a hand-edited URL cannot turn the Problem page into the Incident page. Only `setScope()`
   * (called from the route's meta) moves it.
   */
  const scope = ref<Scope | null>(null)
  /** Free-text search over ticket number, subject, assignee and institusi. */
  const search = ref('')
  const source = ref<string[]>([])
  const assignedTo = ref<string[]>([])
  const sort = ref<string | null>(null)
  const direction = ref<string | null>(null)
  const page = ref(0)
  const size = ref(DEFAULT_PAGE_SIZE)

  /**
   * Filter-only params (`dateFrom`..`direction`) — `page`/`size` are DELIBERATELY excluded.
   * `GET /api/incidents/export` does not accept them at all (it always re-queries the whole
   * filtered set up to its own cap — see `IncidentController.export`); building the export
   * request from this method rather than from `serialize()` is what stops a client-side
   * regression from ever asking the server for "just this page".
   */
  function toFilterParams(): URLSearchParams {
    const params = new URLSearchParams()
    if (dateFrom.value) params.set('dateFrom', dateFrom.value)
    if (dateTo.value) params.set('dateTo', dateTo.value)
    for (const value of tracker.value) params.append('tracker', value)
    for (const value of stage.value) params.append('stage', value)
    for (const value of priority.value) params.append('priority', value)
    for (const value of institusi.value) params.append('institusi', value)
    for (const value of category.value) params.append('category', value)
    if (hasWorkaround.value !== null) params.set('hasWorkaround', String(hasWorkaround.value))
    if (status.value) params.set('status', status.value)
    if (sla.value) params.set('sla', sla.value)
    if (matrixMissing.value) params.set('matrixMissing', 'true')
    if (search.value.trim()) params.set('search', search.value.trim())
    for (const value of source.value) params.append('source', value)
    for (const value of assignedTo.value) params.append('assignedTo', value)
    if (scope.value) params.set('scope', scope.value)
    if (sort.value) params.set('sort', sort.value)
    if (direction.value) params.set('direction', direction.value)
    return params
  }

  /**
   * `toFilterParams()` WITHOUT the status/SLA facets — for the charts on the Ops page.
   *
   * Charts (heatmap, SLA buckets, trend) describe the period; the KPI tiles slice the TABLE. If
   * the charts followed the facets, clicking Breached would redraw the SLA-bucket chart as one
   * solid red bar and the heatmap as "breached tickets only" — true, and useless as context for
   * the drill the operator is in the middle of. The chip row makes the table's narrower scope
   * explicit instead. (Decisions Log, 2026-09-15, faceted tiles.)
   */
  function toBaseFilterParams(): URLSearchParams {
    const params = toFilterParams()
    params.delete('status')
    params.delete('sla')
    // The matrix is what the "missing" link is computed from; narrowing it by its own link would
    // shrink the number the link shows.
    params.delete('matrixMissing')
    return params
  }

  /** What the table request sends: every filter, `scope` included, plus paging. */
  function toPageParams(): URLSearchParams {
    const params = toFilterParams()
    params.set('page', String(page.value))
    params.set('size', String(size.value))
    return params
  }

  /**
   * The full query string for the URL bar — filter params plus paging, so a colleague can
   * paste the URL back and land on the same page of the same filtered set. `scope` is omitted:
   * the path (`/problem`) already carries it, and a `?scope=` in the bar would be a second,
   * editable copy of it.
   */
  function serialize(): string {
    const params = toFilterParams()
    params.delete('scope')
    params.set('page', String(page.value))
    params.set('size', String(size.value))
    return params.toString()
  }

  /**
   * Applies a query string (as read off `location.search` or pasted from a bookmark) onto the
   * store. An unknown key is IGNORED, not thrown on — a bookmarked URL from an older or newer
   * app version, or one carrying an unrelated tracking param, must not hard-fail the page.
   */
  function applyFromQueryString(queryString: string) {
    const params = new URLSearchParams(queryString)

    dateFrom.value = params.get('dateFrom')
    dateTo.value = params.get('dateTo')
    // A tracker outside this page's scope can only come from a stale or edited link; the server
    // would answer it with an empty page that looks like a bug.
    const allowed = scope.value ? SCOPE_TRACKERS[scope.value] : null
    tracker.value = params
      .getAll('tracker')
      .filter((name) => allowed === null || allowed.includes(name))
    matrixMissing.value = params.get('matrixMissing') === 'true'
    stage.value = params.getAll('stage')
    priority.value = params.getAll('priority')
    institusi.value = params.getAll('institusi')
    category.value = params.getAll('category')
    search.value = params.get('search') ?? ''
    source.value = params.getAll('source')
    assignedTo.value = params.getAll('assignedTo')

    const hasWorkaroundRaw = params.get('hasWorkaround')
    hasWorkaround.value = hasWorkaroundRaw === null ? null : hasWorkaroundRaw === 'true'

    status.value = facetFrom(STATUS_FACETS, params.get('status'))
    sla.value = facetFrom(SLA_FACETS, params.get('sla'))

    sort.value = params.get('sort')
    direction.value = params.get('direction')

    const pageRaw = params.get('page')
    page.value = pageRaw === null ? 0 : Number(pageRaw)

    const sizeRaw = params.get('size')
    size.value = sizeRaw === null ? DEFAULT_PAGE_SIZE : Number(sizeRaw)
  }

  function reset() {
    dateFrom.value = null
    dateTo.value = null
    tracker.value = []
    stage.value = []
    priority.value = []
    institusi.value = []
    category.value = []
    search.value = ''
    source.value = []
    assignedTo.value = []
    hasWorkaround.value = null
    status.value = null
    sla.value = null
    matrixMissing.value = false
    sort.value = null
    direction.value = null
    page.value = 0
    size.value = DEFAULT_PAGE_SIZE
  }

  /**
   * Switches the page to another ticket group. A new scope starts clean — filters from the
   * Incident page (a tracker, a priority) mean nothing on Problem and would otherwise leak across
   * the three menus. Setting the scope it already has is a no-op, so a re-render never wipes the
   * operator's filters.
   */
  function setScope(next: Scope | null) {
    if (scope.value === next) return
    reset()
    scope.value = next
  }

  /**
   * The tile click. The same value clears the facet (a second click on an active tile undoes
   * it); a different value replaces it. Back to page 0 either way — a narrower set can have
   * fewer pages, and staying on page 5 would render an empty table.
   */
  function toggleStatus(value: StatusFacet) {
    status.value = status.value === value ? null : value
    page.value = 0
  }

  function toggleSla(value: SlaFacet) {
    sla.value = sla.value === value ? null : value
    page.value = 0
  }

  /**
   * The home mock-up's period chips. A preset is just a `dateFrom`/`dateTo` pair, so every
   * surface that already reads the date window follows it; `all` clears both bounds. `today`
   * is injectable so the arithmetic is testable.
   */
  function setPeriod(preset: PeriodPreset, today: Date = new Date()) {
    if (preset === 'all') {
      dateFrom.value = null
      dateTo.value = null
    } else {
      const from = new Date(today.getFullYear(), today.getMonth(), today.getDate())
      if (preset === '6m') from.setMonth(from.getMonth() - 6)
      else from.setDate(from.getDate() - (Number.parseInt(preset, 10) - 1))
      dateFrom.value = formatDate(from)
      dateTo.value = formatDate(today)
    }
    page.value = 0
  }

  return {
    search,
    source,
    assignedTo,
    setPeriod,
    dateFrom,
    dateTo,
    tracker,
    stage,
    priority,
    institusi,
    category,
    hasWorkaround,
    status,
    sla,
    matrixMissing,
    scope,
    sort,
    direction,
    page,
    size,
    setScope,
    toggleStatus,
    toggleSla,
    toFilterParams,
    toPageParams,
    toBaseFilterParams,
    serialize,
    applyFromQueryString,
    reset,
  }
})
