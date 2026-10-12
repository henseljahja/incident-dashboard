<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import {
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  Legend,
  LinearScale,
  Tooltip,
} from 'chart.js'
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchTrend } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { useFiltersStore } from '@/stores/filters'
import { formatCount } from '@/utils/number'

Chart.register(BarController, BarElement, CategoryScale, LinearScale, Legend, Tooltip)

const { t, locale } = useI18n()
const filters = useFiltersStore()

// Same filters store as the table below — the trend must never disagree with what is on screen.
// The BASE params, without the KPI tiles' status/SLA facets: a chart describes the period, and
// the tiles slice the table (Decisions Log, faceted tiles).
const queryKey = computed(
  () => ['metrics', 'trend', filters.toBaseFilterParams().toString()] as const,
)

const { data } = useQuery({
  queryKey,
  queryFn: () => fetchTrend(filters.toBaseFilterParams()),
})

const periods = computed(() => data.value ?? [])

const canvasEl = ref<HTMLCanvasElement | null>(null)
const chart = shallowRef<Chart | null>(null)

function render() {
  if (!canvasEl.value) return

  const labels = periods.value.map((p) => p.period)
  const created = periods.value.map((p) => p.created)
  const closed = periods.value.map((p) => p.closed)

  if (chart.value) {
    chart.value.data.labels = labels
    chart.value.data.datasets[0]!.data = created
    chart.value.data.datasets[1]!.data = closed
    // Legend text follows the UI language without rebuilding the chart.
    chart.value.data.datasets[0]!.label = t('charts.trend.created')
    chart.value.data.datasets[1]!.label = t('charts.trend.closed')
    chart.value.update()
    return
  }

  chart.value = new Chart(canvasEl.value, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        {
          type: 'bar',
          label: t('charts.trend.created'),
          data: created,
          backgroundColor: '#2563eb',
          maxBarThickness: 48,
        },
        {
          type: 'bar',
          label: t('charts.trend.closed'),
          data: closed,
          backgroundColor: '#94a3b8',
          maxBarThickness: 48,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      locale: locale.value,
      scales: {
        y: { beginAtZero: true },
      },
    },
  })
}

watch(periods, render, { flush: 'post' })
watch(canvasEl, render, { flush: 'post' })
watch(locale, () => {
  if (chart.value) chart.value.options.locale = locale.value
  render()
})

onBeforeUnmount(() => {
  chart.value?.destroy()
  chart.value = null
})
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="created-closed-trend"
  >
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-sm font-semibold text-surface-700">{{ t('charts.trend.title') }}</h2>
      <InfoHint term="trend" class="text-surface-600" />
    </div>
    <div class="relative h-56">
      <canvas ref="canvasEl" aria-hidden="true"></canvas>
    </div>
    <!--
      `sr-only` sits on a WRAPPER, not on the <table>.

      Tailwind's `sr-only` pins width/height to 1px, but the table layout algorithm treats a
      declared width as a MINIMUM and expands to fit its content regardless — so the "hidden"
      table stayed 329px wide, reached 1px past the viewport, and put the whole page into
      horizontal scroll. A block-level wrapper obeys the 1px + overflow:hidden that sr-only
      actually depends on; the table inside is then clipped for real.
    -->
    <div class="sr-only">
      <table data-testid="created-closed-trend-table">
        <caption>
          {{
            t('charts.trend.caption')
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ t('charts.trend.period') }}</th>
            <th scope="col">{{ t('charts.trend.created') }}</th>
            <th scope="col">{{ t('charts.trend.closed') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="period in periods" :key="period.period">
            <th scope="row">{{ period.period }}</th>
            <td>{{ formatCount(period.created, locale) }}</td>
            <td>{{ formatCount(period.closed, locale) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
