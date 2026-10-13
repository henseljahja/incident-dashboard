<script setup lang="ts">
import type { ChartConfiguration } from 'chart.js'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchMonthly, type MonthRow } from '@/apis/insights'
import BaseChart from '@/components/charts/BaseChart.vue'
import { useInsight } from '@/composables/useInsight'
import { formatCount, formatDecimal } from '@/utils/number'
import { PRIORITY_COLOR, PRIORITY_KEYS, orDash } from '@/utils/priority'

import InsightPanel from './InsightPanel.vue'
import PriorityCells from './PriorityCells.vue'

/**
 * Month-by-month comparison by CREATION month: how many tickets arrived (stacked by priority),
 * how many are closed / still open, the mean time to resolve the closed ones, the mean age of the
 * still-open ones, and how many breached SLA. The chart and the table are the same rows.
 */
const { t, locale } = useI18n()
const query = useInsight<MonthRow[]>('monthly', fetchMonthly)
const rows = computed(() => query.data.value ?? [])
const dec = (v: number) => formatDecimal(v, locale.value)

const keys = computed(() =>
  PRIORITY_KEYS.filter((k) => k !== 'other' || rows.value.some((r) => r.byPriority.other > 0)),
)

const config = computed<ChartConfiguration | null>(() =>
  rows.value.length === 0
    ? null
    : {
        type: 'bar',
        data: {
          labels: rows.value.map((r) => r.period),
          datasets: keys.value.map((key) => ({
            label: t(`insights.priority.${key}`),
            data: rows.value.map((r) => r.byPriority[key]),
            backgroundColor: PRIORITY_COLOR[key],
            borderColor: '#ffffff',
            borderWidth: 1,
            maxBarThickness: 64,
            stack: 'prio',
          })),
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          locale: locale.value,
          scales: {
            x: { stacked: true, grid: { display: false } },
            y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } },
          },
          plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8 } } },
        },
      },
)
</script>

<template>
  <InsightPanel
    :title="t('insights.monthly.title')"
    :subtitle="t('insights.monthly.subtitle')"
    :loading="query.isLoading.value"
    :error="query.isError.value"
    :empty="rows.length === 0"
    @retry="query.refetch()"
  >
    <div class="h-56"><BaseChart :config="config" :label="t('insights.monthly.title')" /></div>
    <div class="overflow-x-auto">
      <table class="insight-table w-full text-sm" data-testid="monthly-table">
        <thead class="text-left text-xs uppercase text-surface-600">
          <tr>
            <th scope="col" class="py-1">{{ t('insights.monthly.month') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.total') }}</th>
            <th scope="col">{{ t('insights.col.priority') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.closed') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.open') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.avgResolution') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.avgOpenAge') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.slaBreach') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in rows"
            :key="r.period"
            class="border-t border-surface-100"
            data-testid="month-row"
            :data-period="r.period"
          >
            <th scope="row" class="py-1.5 text-left font-medium">{{ r.period }}</th>
            <td class="text-right tabular-nums">{{ formatCount(r.total, locale) }}</td>
            <td><PriorityCells :counts="r.byPriority" /></td>
            <td class="text-right tabular-nums">{{ formatCount(r.closed, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(r.open, locale) }}</td>
            <td class="text-right tabular-nums">{{ orDash(r.avgResolutionDays, dec) }}</td>
            <td class="text-right tabular-nums">{{ orDash(r.avgOpenAgeDays, dec) }}</td>
            <td
              class="text-right tabular-nums"
              :class="r.slaBreach > 0 ? 'font-semibold text-[var(--chart-error-text)]' : ''"
            >
              {{ formatCount(r.slaBreach, locale) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </InsightPanel>
</template>
