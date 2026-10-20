import { apiRequest } from './client'

/**
 * Wire types of `GET /api/insights/**` — mirror `InsightsResponse` on the backend field for
 * field. Percentages are 0-100; a figure with no basis (nothing closed, no history) is `null`,
 * never a fabricated zero — render it as an em dash.
 */
export interface PriorityCounts {
  critical: number
  high: number
  medium: number
  low: number
  other: number
}

export interface LabelCount {
  label: string
  count: number
}

export interface DailyPoint {
  date: string
  total: number
  byPriority: PriorityCounts
}

export interface Home {
  total: number
  open: number
  closed: number
  mttrDays: number | null
  mttrMinDays: number | null
  mttrMaxDays: number | null
  onTime: number
  late: number
  compliancePct: number | null
  breachedOpen: number
  closurePct: number | null
  avgAgingDays: number | null
  byPriority: PriorityCounts
  daily: DailyPoint[]
}

export interface MonthRow {
  period: string
  total: number
  byPriority: PriorityCounts
  closed: number
  open: number
  avgResolutionDays: number | null
  avgOpenAgeDays: number | null
  slaBreach: number
}

export interface AssigneeRow {
  rank: number
  assignee: string
  total: number
  closed: number
  open: number
  byPriority: PriorityCounts
  avgResolutionDays: number | null
  minResolutionDays: number | null
  maxResolutionDays: number | null
  closureRatePct: number | null
}

export interface SourceRow {
  source: string
  total: number
  byPriority: PriorityCounts
  closed: number
  open: number
  avgAgingDays: number | null
}

export interface InstitusiRow {
  label: string
  count: number
  sharePct: number | null
}

export interface Category {
  sources: SourceRow[]
  institusi: InstitusiRow[]
  brands: InstitusiRow[]
}

export interface AgingRow {
  noIncident: number
  subject: string | null
  priority: string | null
  status: string | null
  institusi: string | null
  assignedTo: string | null
  agingDays: number | null
  slaDays: number | null
  slaUsedPct: number | null
}

export type ForecastRisk = 'LOW' | 'MEDIUM' | 'HIGH'
export type ForecastAccuracy = 'GOOD' | 'FAIR' | 'POOR' | 'NA'

export interface ForecastDay {
  date: string
  weekday: string
  predicted: number
  lower: number
  upper: number
  seasonalFactor: number
  risk: ForecastRisk
}

export interface Forecast {
  horizonDays: number
  nextTotal: number
  currentTotal: number
  changePct: number | null
  avg7: number
  avg14: number
  avg30: number
  trendPerDay: number
  mapePct: number | null
  accuracy: ForecastAccuracy
  busiestDay: string
  quietestDay: string
  baseLevel: number
  stdDev: number
  historyPoints: number
  history: { date: string; actual: number }[]
  days: ForecastDay[]
  weekdayFactors: { weekday: string; factor: number }[]
  recommendations: ('STABLE' | 'SURGE' | 'DROP' | 'HIGH_RISK_DAY')[]
}

export interface RcaRow {
  noIncident: number
  subject: string | null
  status: string | null
  category: string | null
  assignedTo: string | null
  agingDays: number | null
  hasRootCause: boolean
  hasWorkaround: boolean
  solution: string | null
  closed: boolean
}

export interface Rca {
  total: number
  open: number
  withRootCause: number
  withWorkaround: number
  permanentSolution: number
  workaroundOnly: number
  rootCausePct: number | null
  workaroundPct: number | null
  permanentPct: number | null
  byCategory: LabelCount[]
  missingRootCause: RcaRow[]
}

export interface AlertTicket {
  noIncident: number
  subject: string | null
  priority: string | null
  slaUsedPct: number | null
}

export type AlertCode =
  | 'DATA_STALE'
  | 'BREACHED_OPEN'
  | 'AT_RISK'
  | 'CRITICAL_HIGH_OPEN'
  | 'UNASSIGNED_OPEN'
  | 'FORECAST_SURGE'

export interface Alert {
  code: AlertCode
  severity: 'CRITICAL' | 'HIGH' | 'WARNING' | 'INFO'
  count: number
  tickets: AlertTicket[]
}

export interface DailySummary {
  date: string
  created: number
  closed: number
  openNow: number
  breachedOpen: number
  byPriority: PriorityCounts
  topInstitusi: LabelCount[]
  topCategories: LabelCount[]
  attention: AlertTicket[]
  /** Present only when an AI provider is configured server-side; otherwise `null`. */
  narrative: string | null
  provider: string
}

export interface Pattern {
  institusi: string
  category: string
  count: number
  firstSeen: string
  lastSeen: string
  closedWithWorkaround: number
  permanentSolution: number
  keywords: string[]
  samples: AlertTicket[]
  recommendation: 'PERMANENT_FIX' | 'DOCUMENT_ROOT_CAUSE' | 'MONITOR'
}

export interface Patterns {
  patterns: Pattern[]
  narrative: string | null
  provider: string
}

export interface SearchHit {
  noIncident: number
  subject: string | null
  priority: string | null
  institusi: string | null
  relevance: number
}

export type QaMetric =
  'COMPLIANCE' | 'CLOSURE' | 'MTTR' | 'BREACHED_OPEN' | 'AVG_AGING' | 'OPEN' | 'CLOSED' | 'COUNT'

export interface Answer {
  understood: boolean
  metric: QaMetric | null
  scope: string | null
  value: number | null
  matched: number
  unit: string | null
}

function get<T>(path: string, params: URLSearchParams, extra: Record<string, string> = {}) {
  const merged = new URLSearchParams(params)
  // Aggregates describe the period, never a page: paging/sorting keys are dropped client-side
  // too, so a sorted table elsewhere never changes a widget's request.
  for (const key of ['page', 'size', 'sort', 'direction']) merged.delete(key)
  for (const [key, value] of Object.entries(extra)) merged.set(key, value)
  const query = merged.toString()
  return apiRequest<T>(`/insights/${path}${query ? `?${query}` : ''}`)
}

export const fetchHome = (p: URLSearchParams) => get<Home>('home', p)
export const fetchMonthly = (p: URLSearchParams) => get<MonthRow[]>('monthly', p)
export const fetchAssignees = (p: URLSearchParams, limit = 10) =>
  get<AssigneeRow[]>('assignees', p, { limit: String(limit) })
export const fetchCategory = (p: URLSearchParams, limit = 10) =>
  get<Category>('category', p, { limit: String(limit) })
export const fetchAging = (p: URLSearchParams, limit = 10) =>
  get<AgingRow[]>('aging', p, { limit: String(limit) })
export const fetchForecast = (p: URLSearchParams, horizonDays = 7) =>
  get<Forecast>('forecast', p, { horizonDays: String(horizonDays) })
export const fetchRca = (p: URLSearchParams, limit = 25) =>
  get<Rca>('rca', p, { limit: String(limit) })
export const fetchAlerts = (p: URLSearchParams) => get<Alert[]>('alerts', p)
export const fetchDailySummary = (p: URLSearchParams, date: string, lang: string) =>
  get<DailySummary>('ai/summary', p, { date, lang })
export const fetchPatterns = (p: URLSearchParams, lang: string) =>
  get<Patterns>('ai/patterns', p, { lang })
export const searchTickets = (p: URLSearchParams, q: string) =>
  get<SearchHit[]>('ai/search', p, { q })
export const askQuestion = (p: URLSearchParams, q: string) => get<Answer>('ai/ask', p, { q })
