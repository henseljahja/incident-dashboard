import * as XLSX from 'xlsx'

import type { ExportResult } from '@/apis/incidents'

/**
 * Turns a server `ExportResult` into a downloaded workbook.
 *
 * INVARIANT: this is the ONLY thing this module does. It never re-derives rows from what is
 * currently on screen — the workbook is built exclusively from `result.content`, which is the
 * server's own re-query of the full filtered set (see `IncidentExportUseCase`). Building it
 * from table state instead would silently export "whatever page happens to be rendered" and
 * bypass both the `export` privilege check and the `EXPORT_AUDIT` row that only the real
 * server call produces.
 */
export function downloadExportWorkbook(result: ExportResult, filename?: string) {
  const rows = result.content.map((row) => row)
  const worksheet = XLSX.utils.json_to_sheet(rows)
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, worksheet, 'Incidents')
  XLSX.writeFile(workbook, filename ?? `incidents-${result.exportId}.xlsx`)
}
