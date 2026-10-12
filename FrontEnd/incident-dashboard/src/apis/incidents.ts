import { apiRequest } from './client'

/**
 * One row of `V_INCIDENT`, on the wire. `IncidentRowDto` is a catalog-driven, column-keyed
 * map (see the backend DTO) — there is no fixed field list here on purpose, and re-typing it
 * by hand is exactly how a view column silently stops reaching this table.
 */
export type IncidentRow = Record<string, unknown>

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ExportResult {
  exportId: string
  rowCount: number
  /** The server-side cap bit — the UI must raise a visible banner when this is true. */
  truncated: boolean
  maxRows: number
  content: IncidentRow[]
}

/**
 * `GET /api/incidents` — one page of the filtered set. `params` should come from
 * `useFiltersStore().serialize()`-shaped paging, i.e. it MAY carry `page`/`size`; the server
 * clamps `size` to `IncidentFilter.MAX_PAGE_SIZE` (200) regardless of what is asked for.
 *
 * `status` (`OPEN|CLOSED`) and `sla` (`BREACHED|AT_RISK`) travel in the same params. Any other
 * value is a 400 `invalid_filter`, which is why the store drops unknown tokens on hydrate
 * rather than forwarding them.
 */
export function fetchIncidents(params: URLSearchParams) {
  const query = params.toString()
  return apiRequest<PageResponse<IncidentRow>>(`/incidents${query ? `?${query}` : ''}`)
}

/**
 * `GET /api/incidents/export` — the one bulk-egress path, gated server-side on the `export`
 * privilege. `params` MUST come from `useFiltersStore().toFilterParams()`, never from
 * `serialize()`: the export endpoint does not accept `page`/`size` and always re-queries the
 * whole filtered set up to its own cap (see `IncidentController.export`). Sending a
 * page/size-scoped request here would silently export the wrong data.
 */
export function fetchExport(params: URLSearchParams) {
  const query = params.toString()
  return apiRequest<ExportResult>(`/incidents/export${query ? `?${query}` : ''}`)
}
