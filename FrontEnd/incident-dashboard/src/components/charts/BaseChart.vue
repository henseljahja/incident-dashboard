<script setup lang="ts">
import type { ChartConfiguration } from 'chart.js'
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'

import { Chart } from './registerChart'

/**
 * The one place a Chart.js instance is created, updated and destroyed. A chart component builds a
 * `ChartConfiguration` (computed from its query data) and passes it in; this component owns the
 * canvas lifecycle, so no chart leaks an instance on unmount or on a locale/data change.
 *
 * The canvas is `aria-hidden`: a chart is not accessible by itself. Every chart component renders
 * a real `<table>` of the same numbers next to it (the "table view"), and `label` names the figure
 * for assistive tech.
 */
const props = defineProps<{
  config: ChartConfiguration | null
  /** Short description of what the figure shows, announced with the table. */
  label: string
}>()

const canvas = ref<HTMLCanvasElement | null>(null)
const chart = shallowRef<Chart | null>(null)

function render() {
  chart.value?.destroy()
  chart.value = null
  if (!canvas.value || !props.config) return
  chart.value = new Chart(canvas.value, props.config)
}

watch(() => [props.config, canvas.value], render, { flush: 'post', immediate: true })
onBeforeUnmount(() => {
  chart.value?.destroy()
  chart.value = null
})
</script>

<template>
  <div class="relative h-full min-h-48 w-full" role="img" :aria-label="label">
    <canvas ref="canvas" aria-hidden="true"></canvas>
  </div>
</template>
