<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchTopInstitusi } from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'
import { formatCount } from '@/utils/number'

/** Category mock-up: the ten institusi raising the most tickets, as proportional bars. */
const { t, locale } = useI18n()
const filters = useFiltersStore()

const { data } = useQuery({
  queryKey: computed(
    () => ['insights', 'institusi', filters.toBaseFilterParams().toString()] as const,
  ),
  queryFn: () => fetchTopInstitusi(filters.toBaseFilterParams(), 10),
})
const rows = computed(() => data.value ?? [])
const max = computed(() => Math.max(1, ...rows.value.map((r) => r.count)))
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="top-institusi"
  >
    <h2 class="text-sm font-semibold text-surface-700">{{ t('insights.institusi.title') }}</h2>
    <div
      v-for="row in rows"
      :key="row.label"
      class="flex flex-col gap-1 text-sm"
      data-testid="institusi-row"
      :data-count="row.count"
    >
      <div class="flex justify-between gap-2">
        <span class="text-surface-700">{{ row.label }}</span>
        <span class="tabular-nums">{{ formatCount(row.count, locale) }}</span>
      </div>
      <div class="h-2 rounded bg-surface-100" aria-hidden="true">
        <div class="h-2 rounded bg-primary" :style="{ width: `${(row.count * 100) / max}%` }"></div>
      </div>
    </div>
    <p v-if="rows.length === 0" class="text-xs text-surface-600">{{ t('insights.empty') }}</p>
  </div>
</template>
