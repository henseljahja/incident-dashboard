import { apiRequest } from './client'

/** `GET /api/metrics/by-priority`, `by-source`, `top-institusi` — mirrors `InsightsResponse.LabelCount`. */
export interface LabelCount {
  label: string
  count: number
}

/** `GET /api/metrics/top-assignees` — mirrors `InsightsResponse.Assignee`. */
export interface AssigneeRow {
  assignee: string
  total: number
  open: number
  closed: number
  breached: number
  closureRatePct: number | null
}

/** `GET /api/metrics/monthly-comparison` — mirrors `InsightsResponse.Month`. `met` = closed and not breached. */
export interface MonthRow {
  period: string
  created: number
  closed: number
  breached: number
  met: number
  mttrDays: number | null
}

/** `GET /api/metrics/top-aging` — mirrors `InsightsResponse.AgingRow`. */
export interface AgingRow {
  noIncident: number
  subject: string | null
  priority: string | null
  assignedTo: string | null
  status: string | null
  agingDays: number | null
  slaDays: number | null
  slaUsedPct: number | null
}

export interface ForecastPoint {
  date: string
  actual: number | null
  forecast: number | null
  lower: number | null
  upper: number | null
}

/** `GET /api/metrics/forecast`. `method` names the model so the UI never overclaims. */
export interface Forecast {
  method: string
  historyDays: number
  horizonDays: number
  slopePerDay: number | null
  points: ForecastPoint[]
}

function get<T>(path: string, params: URLSearchParams, extra: Record<string, string> = {}) {
  const merged = new URLSearchParams(params)
  for (const [key, value] of Object.entries(extra)) merged.set(key, value)
  const query = merged.toString()
  return apiRequest<T>(`/metrics/${path}${query ? `?${query}` : ''}`)
}

export const fetchByPriority = (params: URLSearchParams) => get<LabelCount[]>('by-priority', params)
export const fetchBySource = (params: URLSearchParams) => get<LabelCount[]>('by-source', params)
export const fetchTopInstitusi = (params: URLSearchParams, limit = 10) =>
  get<LabelCount[]>('top-institusi', params, { limit: String(limit) })
export const fetchTopAssignees = (params: URLSearchParams, limit = 10) =>
  get<AssigneeRow[]>('top-assignees', params, { limit: String(limit) })
export const fetchMonthlyComparison = (params: URLSearchParams) =>
  get<MonthRow[]>('monthly-comparison', params)
export const fetchTopAging = (params: URLSearchParams, limit = 10) =>
  get<AgingRow[]>('top-aging', params, { limit: String(limit) })
export const fetchForecast = (params: URLSearchParams, horizonDays = 14) =>
  get<Forecast>('forecast', params, { horizonDays: String(horizonDays) })
