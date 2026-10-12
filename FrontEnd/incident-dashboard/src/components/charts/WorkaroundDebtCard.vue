<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { KpiResponse } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { formatCount, formatSignedDelta } from '@/utils/number'

/**
 * Task 22. The workaround-debt headline figure: incidents that HAVE a workaround (resolution
 * date) but have NOT been permanently closed. Always paired with its period-over-period delta —
 * a bare count invites reading noise as trend, which is exactly what this view exists to avoid.
 */
const props = defineProps<{
  kpi: KpiResponse | null
}>()

const { t, locale } = useI18n()

const value = computed(() => props.kpi?.workaroundDebt ?? null)
const previousValue = computed(() => props.kpi?.previousPeriod?.workaroundDebt ?? null)

const delta = computed(() => {
  if (value.value === null || previousValue.value === null) return null
  return value.value - previousValue.value
})

const deltaLabel = computed(() => {
  if (delta.value === null) return t('common.noPriorPeriod')
  return formatSignedDelta(delta.value, locale.value, { maximumFractionDigits: 0 })
})
</script>

<template>
  <div
    class="relative flex flex-col items-start gap-1 overflow-hidden rounded-lg border border-surface-200 bg-surface-0 p-4 pl-5 shadow-sm"
    data-testid="workaround-debt-card"
  >
    <span class="absolute inset-y-0 left-0 w-1.5 bg-[var(--sla-warn)]" aria-hidden="true" />
    <span class="flex w-full items-center justify-between gap-2">
      <span class="text-xs font-medium tracking-wide text-surface-600 uppercase">
        {{ t('kpi.workaroundDebt') }}
      </span>
      <InfoHint term="workaroundDebt" class="text-surface-600" />
    </span>
    <span class="text-3xl font-semibold text-surface-900" data-testid="workaround-debt-value">
      {{ value === null ? '—' : formatCount(value, locale) }}
    </span>
    <span
      class="text-sm"
      data-testid="workaround-debt-delta"
      :class="{
        'text-[var(--chart-error-text)]': (delta ?? 0) > 0,
        'text-[var(--chart-success-text)]': (delta ?? 0) < 0,
        'text-surface-600': delta === null || delta === 0,
      }"
    >
      {{ deltaLabel }}
    </span>
  </div>
</template>
