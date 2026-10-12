<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { PERIOD_PRESETS, useFiltersStore, type PeriodPreset } from '@/stores/filters'
import { formatDate, parseDate } from '@/utils/date'

import ScopeSwitch from './ScopeSwitch.vue'

/**
 * Period chips (30/60/90 days, 6 months, all) plus a custom range, over the shared date window.
 * A chip is "active" only when the store's window equals the window that chip would set today,
 * so picking a custom range deselects every chip instead of leaving a stale one lit.
 */
const { t } = useI18n()
const filters = useFiltersStore()
const { dateFrom, dateTo } = storeToRefs(filters)

const active = computed<PeriodPreset | null>(() => {
  if (!dateFrom.value && !dateTo.value) return 'all'
  const today = new Date()
  for (const preset of PERIOD_PRESETS) {
    if (preset === 'all') continue
    const probe = new Date(today.getFullYear(), today.getMonth(), today.getDate())
    if (preset === '6m') probe.setMonth(probe.getMonth() - 6)
    else probe.setDate(probe.getDate() - (Number.parseInt(preset, 10) - 1))
    if (dateFrom.value === formatDate(probe) && dateTo.value === formatDate(today)) return preset
  }
  return null
})

function pick(which: 'from' | 'to', value: Date | Date[] | null | undefined) {
  const formatted = value && !Array.isArray(value) ? formatDate(value) : null
  if (which === 'from') dateFrom.value = formatted
  else dateTo.value = formatted
  filters.page = 0
}
</script>

<template>
  <div class="flex flex-wrap items-end gap-x-4 gap-y-3" data-testid="period-bar">
    <ScopeSwitch />
    <div class="flex flex-wrap gap-2" role="group" :aria-label="t('insights.period.label')">
      <button
        v-for="preset in PERIOD_PRESETS"
        :key="preset"
        type="button"
        class="min-h-10 rounded-full border px-4 text-sm"
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
    <div class="flex items-end gap-2">
      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="period-from">{{
          t('filters.from')
        }}</label>
        <DatePicker
          :model-value="parseDate(dateFrom)"
          date-format="yy-mm-dd"
          input-id="period-from"
          input-class="min-h-10 w-32"
          @update:model-value="(v: unknown) => pick('from', v as Date | null)"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="period-to">{{
          t('filters.to')
        }}</label>
        <DatePicker
          :model-value="parseDate(dateTo)"
          date-format="yy-mm-dd"
          input-id="period-to"
          input-class="min-h-10 w-32"
          @update:model-value="(v: unknown) => pick('to', v as Date | null)"
        />
      </div>
    </div>
  </div>
</template>
