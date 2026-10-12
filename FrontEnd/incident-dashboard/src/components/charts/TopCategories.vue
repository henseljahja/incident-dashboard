<script setup lang="ts">
import { useI18n } from 'vue-i18n'

import type { TopCategory } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { formatCount, formatSignedDelta } from '@/utils/number'

/**
 * Task 22. Top categories for the exec pulse, each carrying its own period-over-period
 * `deltaPct` straight off the wire (`TrendResponse.TopCategory`). `deltaPct` is `null`, never
 * `0`, when there is no baseline to compare against — rendered as "No prior period", never as a
 * fabricated 0% change.
 */
defineProps<{
  categories: TopCategory[]
}>()

const { t, locale } = useI18n()

/** One fixed decimal (`+8.5%`, `±0.0%`) so the column of percentages lines up. */
function deltaLabel(deltaPct: number | null): string {
  if (deltaPct === null) return t('common.noPriorPeriod')
  const digits = { minimumFractionDigits: 1, maximumFractionDigits: 1 }
  return `${formatSignedDelta(deltaPct, locale.value, digits)}%`
}
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="top-categories"
  >
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-sm font-semibold text-surface-700">{{ t('charts.topCategories.title') }}</h2>
      <InfoHint term="topCategories" class="text-surface-600" />
    </div>
    <div
      v-for="row in categories"
      :key="row.category"
      class="flex items-center justify-between gap-2 text-sm"
      data-testid="top-category-row"
      :data-category="row.category"
      :data-count="row.count"
      :data-delta-pct="row.deltaPct ?? ''"
    >
      <span class="text-surface-700">{{ row.category }}</span>
      <span class="tabular-nums">{{ formatCount(row.count, locale) }}</span>
      <span
        class="w-28 text-right tabular-nums"
        :class="{
          'text-[var(--chart-error-text)]': (row.deltaPct ?? 0) > 0,
          'text-[var(--chart-success-text)]': (row.deltaPct ?? 0) < 0,
          'text-surface-600': row.deltaPct === null || row.deltaPct === 0,
        }"
      >
        {{ deltaLabel(row.deltaPct) }}
      </span>
    </div>
    <div v-if="categories.length === 0" class="text-xs text-surface-600">
      {{ t('charts.topCategories.empty') }}
    </div>
  </div>
</template>
