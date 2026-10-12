<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { DefinitionTerm } from '@/apis/definitions'
import type { KpiResponse } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { formatDecimal } from '@/utils/number'

/**
 * Task 22. Plots the two computable mean-times — time-to-workaround (resolution date) and
 * time-to-permanent (close date) — on the SAME chart, so the gap between them (the workaround
 * debt) is visible at a glance. There is deliberately no third series: Redmine records no
 * detection timestamp, so a "time-to-detect" figure would be fabricated (`MetricsContractTest`
 * enforces this server-side; see `KpiResponse`'s javadoc).
 *
 * Built as plain DOM bars, matching `SlaBucketBars`/`ImpactUrgencyHeatmap` — not `chart.js`
 * canvas like `CreatedClosedTrend`. This module has no `canvas` polyfill in the unit-test
 * environment, and every value is also rendered as text, so legibility never depends on hue
 * alone (light or dark).
 */
const props = defineProps<{
  kpi: KpiResponse | null
}>()

const { t, locale } = useI18n()

interface Series {
  key: 'workaround' | 'permanent'
  /** Each bar is its own metric with its own definition, so each carries its own ⓘ. */
  term: DefinitionTerm
  label: string
  current: number | null
  previous: number | null
}

const series = computed<Series[]>(() => [
  {
    key: 'workaround',
    term: 'mttrWorkaround',
    label: t('charts.mttr.workaround'),
    current: props.kpi?.mttrWorkaroundDays ?? null,
    previous: props.kpi?.previousPeriod?.mttrWorkaroundDays ?? null,
  },
  {
    key: 'permanent',
    term: 'mttrPermanent',
    label: t('charts.mttr.permanent'),
    current: props.kpi?.mttrPermanentDays ?? null,
    previous: props.kpi?.previousPeriod?.mttrPermanentDays ?? null,
  },
])

const maxValue = computed(() =>
  series.value.reduce((max, s) => Math.max(max, s.current ?? 0, s.previous ?? 0), 0),
)

function widthFor(value: number | null): string {
  if (value === null || maxValue.value === 0) return '0%'
  return `${Math.round((value / maxValue.value) * 100)}%`
}

const gapDays = computed(() => props.kpi?.mttrGapDays ?? null)
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="mttr-comparison"
  >
    <h2 class="text-sm font-semibold text-surface-700">{{ t('charts.mttr.title') }}</h2>
    <div v-for="s in series" :key="s.key" class="flex items-center gap-2 text-sm">
      <span class="flex w-52 shrink-0 items-center gap-2 text-surface-600">
        {{ s.label }}
        <InfoHint :term="s.term" />
      </span>
      <div class="h-3 flex-1 rounded bg-surface-100">
        <div
          class="h-3 rounded"
          :class="s.key === 'workaround' ? 'bg-[var(--chart-teal)]' : 'bg-[var(--chart-blue)]'"
          :style="{ width: widthFor(s.current) }"
        />
      </div>
      <span
        class="w-16 text-right tabular-nums"
        :data-testid="`mttr-bar-${s.key}`"
        :data-value="s.current ?? ''"
      >
        {{
          s.current !== null
            ? t('common.daysShort', { value: formatDecimal(s.current, locale) })
            : '—'
        }}
      </span>
    </div>
    <div class="text-xs text-surface-600" data-testid="mttr-gap">
      {{ t('charts.mttr.gap') }}
      {{ gapDays !== null ? t('common.days', { value: formatDecimal(gapDays, locale) }) : '—' }}
    </div>
  </div>
</template>
