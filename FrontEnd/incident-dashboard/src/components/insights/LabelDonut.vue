<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ArcElement, Chart, DoughnutController, Legend, Tooltip } from 'chart.js'
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchByPriority, fetchBySource } from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'
import { formatCount } from '@/utils/number'

Chart.register(DoughnutController, ArcElement, Legend, Tooltip)

/**
 * One donut, two datasets: `priority` (home mock-up) and `source` (External vs Internal, the
 * category mock-up). Slice labels are DATA values and are never translated. Base filter params:
 * a donut describes the period, not the tile facets.
 */
const props = defineProps<{ kind: 'priority' | 'source' }>()

const { t, locale } = useI18n()
const filters = useFiltersStore()

const PRIORITY_COLORS: Record<string, string> = {
  Critical: '#dc2626',
  High: '#f97316',
  Medium: '#f59e0b',
  Low: '#16a34a',
}
const FALLBACK = ['#2563eb', '#7c3aed', '#0891b2', '#94a3b8', '#be185d', '#65a30d']

const { data } = useQuery({
  queryKey: computed(
    () => ['insights', props.kind, filters.toBaseFilterParams().toString()] as const,
  ),
  queryFn: () =>
    (props.kind === 'priority' ? fetchByPriority : fetchBySource)(filters.toBaseFilterParams()),
})

const rows = computed(() => data.value ?? [])
const total = computed(() => rows.value.reduce((sum, row) => sum + row.count, 0))

function color(label: string, index: number): string {
  return (
    (props.kind === 'priority' ? PRIORITY_COLORS[label] : undefined) ??
    FALLBACK[index % FALLBACK.length]!
  )
}

const canvasEl = ref<HTMLCanvasElement | null>(null)
const chart = shallowRef<Chart | null>(null)

function render() {
  if (!canvasEl.value) return
  const labels = rows.value.map((r) => r.label)
  const values = rows.value.map((r) => r.count)
  const colors = rows.value.map((r, i) => color(r.label, i))
  if (chart.value) {
    chart.value.data.labels = labels
    chart.value.data.datasets[0]!.data = values
    chart.value.data.datasets[0]!.backgroundColor = colors
    chart.value.update()
    return
  }
  chart.value = new Chart(canvasEl.value, {
    type: 'doughnut',
    data: { labels, datasets: [{ data: values, backgroundColor: colors }] },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      locale: locale.value,
      plugins: { legend: { position: 'bottom' } },
    },
  })
}

watch(rows, render, { flush: 'post' })
watch(canvasEl, render, { flush: 'post' })
onBeforeUnmount(() => {
  chart.value?.destroy()
  chart.value = null
})
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    :data-testid="`donut-${kind}`"
  >
    <h2 class="text-sm font-semibold text-surface-700">{{ t(`insights.donut.${kind}`) }}</h2>
    <div v-if="rows.length" class="relative h-56">
      <canvas ref="canvasEl" aria-hidden="true"></canvas>
    </div>
    <p v-else class="text-xs text-surface-600">{{ t('insights.empty') }}</p>
    <!-- Text equivalent of the canvas, for screen readers and tests. -->
    <ul class="sr-only">
      <li v-for="row in rows" :key="row.label" data-testid="donut-row">
        {{ row.label }}: {{ formatCount(row.count, locale) }} ({{
          total ? Math.round((row.count * 100) / total) : 0
        }}%)
      </li>
    </ul>
  </div>
</template>
