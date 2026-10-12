<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchMonthlyComparison } from '@/apis/insights'
import { fetchKpi } from '@/apis/metrics'
import { useFiltersStore } from '@/stores/filters'
import { formatCount, formatDecimal } from '@/utils/number'

import ForecastChart from './ForecastChart.vue'
import LabelDonut from './LabelDonut.vue'
import MonthlyComparison from './MonthlyComparison.vue'
import PeriodChips from './PeriodChips.vue'
import TopAging from './TopAging.vue'
import TopAssignees from './TopAssignees.vue'
import TopInstitusi from './TopInstitusi.vue'

/**
 * The RD 22524 analytics block: period chips, the five headline figures of the home mock-up
 * (total, MTTR, SLA compliance, breached, closure rate), then the summary, aging, category and
 * forecast widgets. Everything reads the BASE filter params, so it follows the same filters as
 * the KPI row and table without following their status/SLA facets.
 */
const { t, locale } = useI18n()
const filters = useFiltersStore()
const key = computed(() => filters.toBaseFilterParams().toString())

const { data: kpi } = useQuery({
  queryKey: computed(() => ['insights', 'kpi', key.value] as const),
  queryFn: () => fetchKpi(filters.toBaseFilterParams()),
})
const { data: months } = useQuery({
  queryKey: computed(() => ['insights', 'monthly', key.value] as const),
  queryFn: () => fetchMonthlyComparison(filters.toBaseFilterParams()),
})

const total = computed(() => (kpi.value ? kpi.value.open + kpi.value.closed : 0))

/** Share of closed tickets that were not breached; null when nothing has closed. */
const compliancePct = computed(() => {
  const rows = months.value ?? []
  const closed = rows.reduce((sum, r) => sum + r.closed, 0)
  const met = rows.reduce((sum, r) => sum + r.met, 0)
  return closed === 0 ? null : (met * 100) / closed
})

const closurePct = computed(() =>
  !kpi.value || total.value === 0 ? null : (kpi.value.closed * 100) / total.value,
)

const tiles = computed(() => [
  { id: 'total', value: formatCount(total.value, locale.value) },
  {
    id: 'mttr',
    value: kpi.value?.mttrDays == null ? '—' : formatDecimal(kpi.value.mttrDays, locale.value),
  },
  {
    id: 'compliance',
    value:
      compliancePct.value === null ? '—' : `${formatDecimal(compliancePct.value, locale.value)}%`,
  },
  { id: 'breached', value: formatCount(kpi.value?.breached ?? 0, locale.value) },
  {
    id: 'closure',
    value: closurePct.value === null ? '—' : `${formatDecimal(closurePct.value, locale.value)}%`,
  },
])
</script>

<template>
  <section
    class="flex flex-col gap-4"
    data-testid="insights-section"
    :aria-label="t('insights.title')"
  >
    <PeriodChips />
    <div class="grid grid-cols-2 gap-3 md:grid-cols-5">
      <div
        v-for="tile in tiles"
        :key="tile.id"
        class="rounded-lg border border-surface-200 bg-surface-0 p-3 shadow-sm"
        :data-testid="`insight-tile-${tile.id}`"
      >
        <div class="text-xs uppercase text-surface-600">{{ t(`insights.tile.${tile.id}`) }}</div>
        <div class="text-2xl font-semibold tabular-nums">{{ tile.value }}</div>
      </div>
    </div>
    <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <LabelDonut kind="priority" />
      <LabelDonut kind="source" />
      <TopInstitusi />
      <MonthlyComparison class="lg:col-span-2" />
      <TopAssignees />
      <TopAging class="lg:col-span-2" />
      <ForecastChart />
    </div>
  </section>
</template>
