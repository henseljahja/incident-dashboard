<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { PERIOD_PRESETS, useFiltersStore, type PeriodPreset } from '@/stores/filters'
import { formatDate } from '@/utils/date'

/** The home mock-up's period chips. A chip is active when the store's window equals its preset. */
const { t } = useI18n()
const filters = useFiltersStore()

const active = computed<PeriodPreset | null>(() => {
  if (!filters.dateFrom && !filters.dateTo) return 'all'
  const today = new Date()
  for (const preset of PERIOD_PRESETS) {
    if (preset === 'all') continue
    const probe = new Date(today)
    const from = new Date(probe.getFullYear(), probe.getMonth(), probe.getDate())
    if (preset === '6m') from.setMonth(from.getMonth() - 6)
    else from.setDate(from.getDate() - (Number.parseInt(preset, 10) - 1))
    if (filters.dateFrom === formatDate(from) && filters.dateTo === formatDate(today)) return preset
  }
  return null
})
</script>

<template>
  <div class="flex flex-wrap gap-2" role="group" :aria-label="t('insights.period.label')">
    <button
      v-for="preset in PERIOD_PRESETS"
      :key="preset"
      type="button"
      class="min-h-11 rounded-full border px-4 text-sm"
      :class="
        active === preset
          ? 'border-primary bg-primary text-primary-contrast'
          : 'border-surface-300 bg-surface-0 text-surface-700'
      "
      :aria-pressed="active === preset"
      :data-testid="`period-${preset}`"
      @click="filters.setPeriod(preset)"
    >
      {{ t(`insights.period.${preset}`) }}
    </button>
  </div>
</template>
