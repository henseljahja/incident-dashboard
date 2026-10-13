import { useQuery } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'

import { useFiltersStore } from '@/stores/filters'

/**
 * One insight widget = one query over the page's BASE filter params (the shared store minus the
 * KPI tiles' status/sla facets), keyed on those params so any filter change refetches and a
 * repeat request is served from cache. `extra` carries widget-local inputs (a limit, a horizon).
 */
export function useInsight<T>(
  name: string,
  fetcher: (params: URLSearchParams) => Promise<T>,
  extra: MaybeRefOrGetter<unknown> = null,
  enabled: MaybeRefOrGetter<boolean> = true,
  refetchInterval?: number,
) {
  const filters = useFiltersStore()
  const params = computed(() => filters.toBaseFilterParams())
  return useQuery({
    queryKey: computed(() => ['insights', name, params.value.toString(), toValue(extra)] as const),
    queryFn: () => fetcher(params.value),
    enabled: computed(() => toValue(enabled)),
    placeholderData: (previous) => previous,
    refetchInterval,
  })
}
