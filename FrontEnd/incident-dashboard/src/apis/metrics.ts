import type { Scope } from '@/stores/filters'

import { apiRequest } from './client'

/**
 * `GET /api/metrics/kpi` response — mirrors `KpiResponse` on the wire exactly (field-for-field,
 * see the backend record). `previousPeriod` is `null`, never a fabricated zero window, whenever
 * the request left a bound open — see `KpiResponse`'s javadoc.
 *
 * There is deliberately no detection-latency ("MTTD") field here: Redmine records when a ticket
 * was raised, never when the incident started, so no such number is ever computed or displayed.
 */
export interface KpiResponse {
  windowFrom: string | null
  windowTo: string | null
  windowDays: number | null
  /**
   * @deprecated The whole filtered population ignoring the status/SLA facets — kept on the wire
   * for one release so an older bundle keeps working. No tile renders it; with no facets,
   * `open + closed === backlog`.
   */
  backlog: number
  /** `IS_CLOSED = 0`, with the active `sla` facet applied (never the `status` facet). */
  open: number
  /** `IS_CLOSED = 1` (Redmine-closed, or a status the business treats as closed), with the `sla` facet applied. */
  closed: number
  /** `SLA_BREACHED = 1`, with the active `status` facet applied (never the `sla` facet). */
  breached: number
  /** `SLA_AT_RISK = 1` — open, not breached, within the per-priority cushion — with the `status` facet applied. */
  atRisk: number
  /**
   * Mean days from creation to close, over the CLOSED tickets of the filtered set — exactly the
   * rows the Closed facet lists, so the tile and the table can be reconciled.
   */
  mttrDays: number | null
  /** @deprecated Split MTTR; Exec still draws it, the Ops tiles no longer read it. */
  mttrWorkaroundDays: number | null
  /** @deprecated See `mttrWorkaroundDays`. */
  mttrPermanentDays: number | null
  /** @deprecated See `mttrWorkaroundDays`. */
  mttrGapDays: number | null
  workaroundDebt: number
  previousPeriod: KpiResponse | null
}

/**
 * One cell of `GET /api/metrics/matrix` — mirrors `MatrixResponse`. `impact`/`urgency` are
 * `Unclassified` (no custom value at all) or `Unknown (<id>)` (an enum id with no
 * `REF_CUSTOM_FIELD_ENUM` row) as REAL string values, never `null` — a chart or a `Map` keyed on
 * these strings must not treat either as "no row here".
 */
export interface MatrixCell {
  impact: string
  urgency: string
  count: number
  /**
   * The SPO priority of this impact x urgency pair: `Critical`, `High`, `Medium` or `Low`; `null`
   * for the Unclassified / Unknown (n) labels, which have no SPO position. Absent on older servers.
   */
  priority?: string | null
}

/**
 * `GET /api/metrics/matrix` response. `missingCount` is the number of rows in the filtered set
 * whose impact OR urgency is null/blank — the same N that `matrixMissing=true` narrows the table
 * to. The bare-array shape the endpoint used to return is still accepted by `normalizeMatrix`.
 */
export interface MatrixResponse {
  cells: MatrixCell[]
  missingCount: number
  /** SPO impact axis (columns), worst first: Massive, Large, Limited, Localized. Empty if absent. */
  impactOrder: string[]
  /** SPO urgency axis (rows), worst first: Urgent, High, Medium, Low. Empty if absent. */
  urgencyOrder: string[]
}

/** Either wire shape of `GET /api/metrics/matrix`: the legacy bare array or the object form. */
export type MatrixWire =
  | MatrixCell[]
  | {
      cells?: MatrixCell[]
      missingCount?: number
      impactOrder?: string[]
      urgencyOrder?: string[]
    }

/** One shape for the component, whichever the server sent. A bare array means no missing count. */
export function normalizeMatrix(raw: MatrixWire | null | undefined): MatrixResponse {
  if (!raw) return { cells: [], missingCount: 0, impactOrder: [], urgencyOrder: [] }
  if (Array.isArray(raw)) return { cells: raw, missingCount: 0, impactOrder: [], urgencyOrder: [] }
  return {
    cells: raw.cells ?? [],
    missingCount: raw.missingCount ?? 0,
    impactOrder: raw.impactOrder ?? [],
    urgencyOrder: raw.urgencyOrder ?? [],
  }
}

/** One band of `GET /api/metrics/sla-buckets` — mirrors `MatrixResponse.SlaBucket`. */
export interface SlaBucketDto {
  bucket: string
  count: number
}

/** One period of `GET /api/metrics/trend` — mirrors `TrendResponse`. */
export interface TrendPeriod {
  period: string
  created: number
  closed: number
  backlog: number
}

/** One row of `GET /api/metrics/top-categories` — mirrors `TrendResponse.TopCategory`. */
export interface TopCategory {
  category: string
  count: number
  deltaPct: number | null
}

/**
 * `GET /api/freshness` — mirrors `FreshnessUseCase.Freshness`. Both timestamps and `ageSeconds`
 * are `null` on a cold start (ETL never run); `stale` is `true` in that case too, per the
 * backend contract — a cold start is not "fresh by omission".
 */
export interface FreshnessDto {
  lastRunAt: string | null
  lastSourceTs: string | null
  ageSeconds: number | null
  stale: boolean
  staleReason: string | null
}

/**
 * `GET /api/metrics/kpi` — `params` should come from `useFiltersStore().toFilterParams()`, the
 * same filter-only params `/api/incidents` uses, INCLUDING the `status`/`sla` facets: each tile
 * applies the other dimension's facet, so the active tile always equals the table total.
 * Paging/sorting keys, if present, are ignored server-side (aggregates have no page).
 */
export function fetchKpi(params: URLSearchParams) {
  const query = params.toString()
  return apiRequest<KpiResponse>(`/metrics/kpi${query ? `?${query}` : ''}`)
}

/** `GET /api/metrics/matrix` — the impact/urgency heatmap's source data. */
export function fetchMatrix(params: URLSearchParams) {
  const query = params.toString()
  return apiRequest<MatrixWire>(`/metrics/matrix${query ? `?${query}` : ''}`)
}

/** `GET /api/metrics/sla-buckets`. */
export function fetchSlaBuckets(params: URLSearchParams) {
  const query = params.toString()
  return apiRequest<SlaBucketDto[]>(`/metrics/sla-buckets${query ? `?${query}` : ''}`)
}

/**
 * `GET /api/metrics/trend`. `granularity` is appended on top of the filter params when given;
 * omitted, the backend applies its own default.
 */
export function fetchTrend(params: URLSearchParams, granularity?: string) {
  const merged = new URLSearchParams(params)
  if (granularity) merged.set('granularity', granularity)
  const query = merged.toString()
  return apiRequest<TrendPeriod[]>(`/metrics/trend${query ? `?${query}` : ''}`)
}

/** `GET /api/metrics/top-categories`. */
export function fetchTopCategories(params: URLSearchParams, limit?: number) {
  const merged = new URLSearchParams(params)
  if (limit) merged.set('limit', String(limit))
  const query = merged.toString()
  return apiRequest<TopCategory[]>(`/metrics/top-categories${query ? `?${query}` : ''}`)
}

/** One entry of `GET /api/metrics/scope-counts`. Invariant: `open + closed === total`. */
export interface ScopeCount {
  scope: Scope
  open: number
  closed: number
  breached: number
  atRisk: number
  total: number
}

export interface ScopeCountsResponse {
  scopes: ScopeCount[]
}

/**
 * `GET /api/metrics/scope-counts`. Honors `dateFrom`/`dateTo` only — the server ignores `scope`
 * and `tracker`, so a tile never changes with the page it sits on.
 */
export function fetchScopeCounts(params: URLSearchParams = new URLSearchParams()) {
  const merged = new URLSearchParams()
  const dateFrom = params.get('dateFrom')
  const dateTo = params.get('dateTo')
  if (dateFrom) merged.set('dateFrom', dateFrom)
  if (dateTo) merged.set('dateTo', dateTo)
  const query = merged.toString()
  return apiRequest<ScopeCountsResponse>(`/metrics/scope-counts${query ? `?${query}` : ''}`)
}

export interface WallboardCriticalRow {
  noIncident: number
  /** `null` for roles that are not shown ticket text (TV, exec). */
  subject: string | null
  status: string
  priority: string
  elapsedDays: number
  slaDays: number
}

/**
 * `GET /api/metrics/wallboard` — mirrors the backend payload. Scope is fixed to INCIDENT on the
 * server and no parameter is honored. `slaCompliance.percent` and `avgResolutionDays` are `null`
 * when nothing has closed in the period.
 */
export interface WallboardResponse {
  generatedAt: string
  year: number
  total: number
  open: number
  closed: number
  breached: number
  atRisk: number
  slaCompliance: { met: number; closedInPeriod: number; percent: number | null }
  byStage: { stage: string; count: number }[]
  byPriority: { priority: string; count: number }[]
  criticalHighOpen: WallboardCriticalRow[]
  topCategories: { category: string; count: number }[]
  monthly: { month: string; created: number; closed: number }[]
  avgResolutionDays: number | null
  groupCounts: ScopeCount[]
}

export function fetchWallboard() {
  return apiRequest<WallboardResponse>('/metrics/wallboard')
}

/**
 * `GET /api/freshness` — the one staleness reading in the system. `FreshnessIndicator` polls
 * this every 30s; nothing else computes staleness for itself.
 */
export function fetchFreshness() {
  return apiRequest<FreshnessDto>('/freshness')
}

export { fetchDefinitions, type DefinitionsResponse } from './definitions'
