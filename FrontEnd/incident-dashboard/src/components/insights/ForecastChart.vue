<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import {
  CategoryScale,
  Chart,
  Legend,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js'
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchForecast } from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'
import { formatDecimal } from '@/utils/number'

Chart.register(
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Legend,
  Tooltip,
)

/**
 * Forecast mock-up: daily created-ticket counts for the last 60 days and a straight-line
 * projection with a 95% band. It is a trend extrapolation, not a model of seasonality, and the
 * card says so (`insights.forecast.note`). The server ignores the date window on purpose.
 */
const { t, locale } = useI18n()
const filters = useFiltersStore()
const horizon = ref(14)

const { data } = useQuery({
  queryKey: computed(
    () => ['insights', 'forecast', filters.toBaseFilterParams().toString(), horizon.value] as const,
  ),
  queryFn: () => fetchForecast(filters.toBaseFilterParams(), horizon.value),
})

const points = computed(() => data.value?.points ?? [])
const canvasEl = ref<HTMLCanvasElement | null>(null)
const chart = shallowRef<Chart | null>(null)

function render() {
  if (!canvasEl.value) return
  const labels = points.value.map((p) => p.date)
  const datasets = [
    {
      label: t('insights.forecast.actual'),
      data: points.value.map((p) => p.actual),
      borderColor: '#2563eb',
      pointRadius: 0,
    },
    {
      label: t('insights.forecast.forecast'),
      data: points.value.map((p) => p.forecast),
      borderColor: '#f59e0b',
      borderDash: [6, 4],
      pointRadius: 0,
    },
    {
      label: t('insights.forecast.upper'),
      data: points.value.map((p) => p.upper),
      borderColor: '#cbd5e1',
      pointRadius: 0,
    },
    {
      label: t('insights.forecast.lower'),
      data: points.value.map((p) => p.lower),
      borderColor: '#cbd5e1',
      pointRadius: 0,
    },
  ]
  if (chart.value) {
    chart.value.data.labels = labels
    chart.value.data.datasets = datasets
    chart.value.update()
    return
  }
  chart.value = new Chart(canvasEl.value, {
    type: 'line',
    data: { labels, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      locale: locale.value,
      spanGaps: false,
      scales: { y: { beginAtZero: true } },
    },
  })
}

watch(points, render, { flush: 'post' })
watch(canvasEl, render, { flush: 'post' })
onBeforeUnmount(() => {
  chart.value?.destroy()
  chart.value = null
})

const slopeLabel = computed(() =>
  data.value?.slopePerDay == null
    ? null
    : formatDecimal(data.value.slopePerDay, locale.value, { maximumFractionDigits: 2 }),
)
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="forecast"
  >
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-sm font-semibold text-surface-700">{{ t('insights.forecast.title') }}</h2>
      <select
        v-model.number="horizon"
        class="min-h-11 rounded border border-surface-300 bg-surface-0 px-2 text-sm"
        :aria-label="t('insights.forecast.horizon')"
        data-testid="forecast-horizon"
      >
        <option :value="7">7</option>
        <option :value="14">14</option>
        <option :value="30">30</option>
      </select>
    </div>
    <div v-if="points.length" class="relative h-56">
      <canvas ref="canvasEl" aria-hidden="true"></canvas>
    </div>
    <p v-else class="text-xs text-surface-600">{{ t('insights.empty') }}</p>
    <p class="text-xs text-surface-600" data-testid="forecast-note">
      {{ t('insights.forecast.note') }}
      <span v-if="slopeLabel !== null">{{
        t('insights.forecast.slope', { value: slopeLabel })
      }}</span>
    </p>
  </div>
</template>
