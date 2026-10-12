<script setup lang="ts">
import type { ChartConfiguration } from 'chart.js'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchHome, type Home } from '@/apis/insights'
import BaseChart from '@/components/charts/BaseChart.vue'
import { useInsight } from '@/composables/useInsight'
import { formatCount } from '@/utils/number'
import { PRIORITY_COLOR, PRIORITY_KEYS, priorityTotal } from '@/utils/priority'

import InsightPanel from './InsightPanel.vue'

/**
 * Share of tickets per priority. A donut is honest here because there are at most five parts
 * of one whole; every slice is also a legend row with its count and percentage, so the figure
 * does not depend on reading colour.
 */
const { t, locale } = useI18n()
const query = useInsight<Home>('home', fetchHome)
const counts = computed(() => query.data.value?.byPriority ?? null)
const total = computed(() => (counts.value ? priorityTotal(counts.value) : 0))

const rows = computed(() =>
  counts.value
    ? PRIORITY_KEYS.filter((k) => k !== 'other' || counts.value!.other > 0).map((key) => ({
        key,
        count: counts.value![key],
        pct: total.value ? (counts.value![key] * 100) / total.value : 0,
      }))
    : [],
)

const config = computed<ChartConfiguration | null>(() =>
  total.value === 0
    ? null
    : {
        type: 'doughnut',
        data: {
          labels: rows.value.map((r) => t(`insights.priority.${r.key}`)),
          datasets: [
            {
              data: rows.value.map((r) => r.count),
              backgroundColor: rows.value.map((r) => PRIORITY_COLOR[r.key]),
              borderColor: '#ffffff',
              borderWidth: 2,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          cutout: '62%',
          locale: locale.value,
          plugins: { legend: { display: false } },
        },
      },
)
</script>

<template>
  <InsightPanel
    :title="t('insights.priorityDonut.title')"
    :loading="query.isLoading.value"
    :error="query.isError.value"
    :empty="total === 0"
    @retry="query.refetch()"
  >
    <div class="flex flex-wrap items-center gap-4" data-testid="priority-donut">
      <div class="h-40 w-40 shrink-0">
        <BaseChart :config="config" :label="t('insights.priorityDonut.title')" />
      </div>
      <ul class="flex min-w-40 flex-1 flex-col gap-1.5 text-sm">
        <li
          v-for="r in rows"
          :key="r.key"
          class="flex items-center gap-2"
          data-testid="donut-row"
          :data-priority="r.key"
        >
          <span
            class="h-3 w-3 rounded-sm"
            :style="{ backgroundColor: PRIORITY_COLOR[r.key] }"
            aria-hidden="true"
          ></span>
          <span class="flex-1 text-surface-700">{{ t(`insights.priority.${r.key}`) }}</span>
          <span class="font-medium tabular-nums">{{ formatCount(r.count, locale) }}</span>
          <span class="w-12 text-right text-xs tabular-nums text-surface-600"
            >{{ r.pct.toFixed(1) }}%</span
          >
        </li>
      </ul>
    </div>
  </InsightPanel>
</template>
