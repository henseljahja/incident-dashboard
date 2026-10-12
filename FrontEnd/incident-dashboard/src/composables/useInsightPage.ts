import { onMounted } from 'vue'

import { useFiltersStore, type PeriodPreset, type Scope } from '@/stores/filters'

/**
 * What every insight page does on arrival: pick the ticket group it is about and, if the shared
 * date window is still open, a sensible default period. A window the user already chose on
 * another page is kept — moving between Dashboard and Forecast must not silently change the
 * period under their feet. `setScope` is a no-op for the scope the page already has.
 */
export function useInsightPage(scope: Scope | null, period: PeriodPreset | null = '30d') {
  const filters = useFiltersStore()
  filters.setScope(scope)
  onMounted(() => {
    if (period && !filters.dateFrom && !filters.dateTo) filters.setPeriod(period)
  })
}
