import { apiRequest } from './client'

/**
 * `GET /api/metrics/definitions` — the live policy numbers behind every ⓘ.
 *
 * The WORDING of a definition lives in the i18n catalog (`definitions.*`); only the numbers
 * come from here, straight off the config tables. Hard-coding "Critical 1 · High 2" into the
 * translation files would be correct exactly until the DBA's first edit to `CFG_SLA_POLICY`.
 */

/** Stop-clock precedence, as enum names. Translated client-side via `definitions.closeBasis.*`. */
export type CloseBasis = 'CLOSE_DATE' | 'CLOSED_ON' | 'STATUS_CHANGE'

export interface SlaPolicyEntry {
  priorityId: number
  priorityName: string
  slaDays: number
  atRiskRemainingDays: number
}

export interface DefinitionsResponse {
  /** Ordered by `slaDays` ascending (Critical first). */
  slaPolicy: SlaPolicyEntry[]
  /** The fallback for a priority with no policy row — `DerivedFields`' constants. */
  unknownPriority: { slaDays: number; atRiskRemainingDays: number }
  /** Statuses Redmine leaves open but the business counts as closed (`IS_CLOSED_OVERRIDE = 1`). */
  closedByOverride: { statusId: number; statusName: string }[]
  closeRule: CloseBasis[]
  refreshIntervalMinutes: number
}

/** TanStack Query key — one cache entry for the whole page, shared by every InfoHint. */
export const DEFINITIONS_QUERY_KEY = ['definitions'] as const

/** Policy numbers change on a DBA edit, not per request: ten minutes is plenty fresh. */
export const DEFINITIONS_STALE_TIME_MS = 10 * 60_000

export function fetchDefinitions() {
  return apiRequest<DefinitionsResponse>('/metrics/definitions')
}

/** Every component that carries an ⓘ. Each has `definitions.<term>.{name,meaning,formula,source}`. */
export type DefinitionTerm =
  | 'open'
  | 'closed'
  | 'breached'
  | 'atRisk'
  | 'mttr'
  | 'mttrWorkaround'
  | 'mttrPermanent'
  | 'workaroundDebt'
  | 'trend'
  | 'topCategories'
  | 'matrix'
  | 'slaBuckets'
  | 'freshness'
  | 'aging'
  | 'slaColumn'
  | 'noRedmine'
  | 'slaCompliance'
  | 'groupCounts'
  | 'matrixMissing'
  | 'avgResolution'
