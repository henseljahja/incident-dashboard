<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchMonthlyComparison } from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'
import { formatCount, formatDecimal } from '@/utils/number'

/** Summary & Assignment mock-up: month-by-month created / closed / breached / MTTR / SLA met. */
const { t, locale } = useI18n()
const filters = useFiltersStore()

const { data } = useQuery({
  queryKey: computed(
    () => ['insights', 'monthly', filters.toBaseFilterParams().toString()] as const,
  ),
  queryFn: () => fetchMonthlyComparison(filters.toBaseFilterParams()),
})
const rows = computed(() => data.value ?? [])

/** Closed-and-not-breached over closed; `null` (shown as a dash) when nothing closed. */
function compliance(met: number, closed: number): string {
  return closed === 0 ? '—' : `${formatDecimal((met * 100) / closed, locale.value)}%`
}
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="monthly-comparison"
  >
    <h2 class="text-sm font-semibold text-surface-700">{{ t('insights.monthly.title') }}</h2>
    <div class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead class="text-left text-xs uppercase text-surface-600">
          <tr>
            <th scope="col" class="py-1">{{ t('insights.monthly.month') }}</th>
            <th scope="col" class="text-right">{{ t('insights.monthly.created') }}</th>
            <th scope="col" class="text-right">{{ t('insights.monthly.closed') }}</th>
            <th scope="col" class="text-right">{{ t('insights.monthly.breached') }}</th>
            <th scope="col" class="text-right">{{ t('insights.monthly.mttr') }}</th>
            <th scope="col" class="text-right">{{ t('insights.monthly.compliance') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.period"
            class="border-t border-surface-100"
            data-testid="month-row"
            :data-period="row.period"
          >
            <th scope="row" class="py-1 text-left font-normal">{{ row.period }}</th>
            <td class="text-right tabular-nums">{{ formatCount(row.created, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(row.closed, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(row.breached, locale) }}</td>
            <td class="text-right tabular-nums">
              {{ row.mttrDays === null ? '—' : formatDecimal(row.mttrDays, locale) }}
            </td>
            <td class="text-right tabular-nums">{{ compliance(row.met, row.closed) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="rows.length === 0" class="text-xs text-surface-600">{{ t('insights.empty') }}</p>
  </div>
</template>
