<script setup lang="ts">
import type { ChartConfiguration } from 'chart.js'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchHome, type Home } from '@/apis/insights'
import BaseChart from '@/components/charts/BaseChart.vue'
import { useInsight } from '@/composables/useInsight'
import { formatCount } from '@/utils/number'
import { PRIORITY_COLOR, PRIORITY_KEYS } from '@/utils/priority'

import InsightPanel from './InsightPanel.vue'

/**
 * Daily created tickets, stacked by priority, so the height of a day is its volume and the
 * colour split is its severity mix. Days with no ticket are absent from the data and therefore
 * from the axis; the sr-only table below is the same numbers for a screen reader.
 */
const { t, locale } = useI18n()
const query = useInsight<Home>('home', fetchHome)
const daily = computed(() => query.data.value?.daily ?? [])

const keys = computed(() =>
  PRIORITY_KEYS.filter((k) => k !== 'other' || daily.value.some((d) => d.byPriority.other > 0)),
)

const config = computed<ChartConfiguration | null>(() => {
  if (daily.value.length === 0) return null
  return {
    type: 'bar',
    data: {
      labels: daily.value.map((d) => d.date.slice(5)),
      datasets: keys.value.map((key) => ({
        label: t(`insights.priority.${key}`),
        data: daily.value.map((d) => d.byPriority[key]),
        backgroundColor: PRIORITY_COLOR[key],
        borderColor: '#ffffff',
        borderWidth: 1,
        maxBarThickness: 28,
        stack: 'prio',
      })),
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      locale: locale.value,
      interaction: { mode: 'index', intersect: false },
      scales: {
        x: { stacked: true, grid: { display: false } },
        y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } },
      },
      plugins: {
        legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8 } },
        tooltip: {
          callbacks: {
            footer: (items) =>
              t('insights.trend.total', {
                n: formatCount(
                  items.reduce((sum, i) => sum + (i.parsed.y ?? 0), 0),
                  locale.value,
                ),
              }),
          },
        },
      },
    },
  }
})
</script>

<template>
  <InsightPanel
    :title="t('insights.trend.title')"
    :subtitle="t('insights.trend.subtitle')"
    :loading="query.isLoading.value"
    :error="query.isError.value"
    :empty="daily.length === 0"
    @retry="query.refetch()"
  >
    <div class="h-64" data-testid="daily-trend">
      <BaseChart :config="config" :label="t('insights.trend.title')" />
    </div>
    <div class="sr-only">
      <table>
        <caption>
          {{
            t('insights.trend.title')
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ t('insights.trend.date') }}</th>
            <th v-for="k in keys" :key="k" scope="col">{{ t(`insights.priority.${k}`) }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in daily" :key="d.date">
            <th scope="row">{{ d.date }}</th>
            <td v-for="k in keys" :key="k">{{ d.byPriority[k] }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </InsightPanel>
</template>
