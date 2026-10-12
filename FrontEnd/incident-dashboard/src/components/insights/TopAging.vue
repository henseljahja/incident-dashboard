<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchTopAging } from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'
import { formatDecimal } from '@/utils/number'

/** Aging mock-up: the ten longest-running OPEN tickets and how much of their SLA is used. */
const { t, locale } = useI18n()
const filters = useFiltersStore()

const { data } = useQuery({
  queryKey: computed(() => ['insights', 'aging', filters.toBaseFilterParams().toString()] as const),
  queryFn: () => fetchTopAging(filters.toBaseFilterParams(), 10),
})
const rows = computed(() => data.value ?? [])

const emit = defineEmits<{ select: [noIncident: number] }>()
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="top-aging"
  >
    <h2 class="text-sm font-semibold text-surface-700">{{ t('insights.aging.title') }}</h2>
    <div class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead class="text-left text-xs uppercase text-surface-600">
          <tr>
            <th scope="col" class="py-1">{{ t('insights.aging.ticket') }}</th>
            <th scope="col">{{ t('insights.aging.priority') }}</th>
            <th scope="col">{{ t('insights.aging.assignee') }}</th>
            <th scope="col" class="text-right">{{ t('insights.aging.days') }}</th>
            <th scope="col" class="text-right">{{ t('insights.aging.slaUsed') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.noIncident"
            class="cursor-pointer border-t border-surface-100"
            data-testid="aging-row"
            :data-incident="row.noIncident"
            @click="emit('select', row.noIncident)"
          >
            <th scope="row" class="py-1 text-left font-normal">
              #{{ row.noIncident }} <span class="text-surface-600">{{ row.subject }}</span>
            </th>
            <td>{{ row.priority ?? '—' }}</td>
            <td>{{ row.assignedTo ?? '—' }}</td>
            <td class="text-right tabular-nums">{{ row.agingDays ?? '—' }}</td>
            <td
              class="text-right tabular-nums"
              :class="(row.slaUsedPct ?? 0) > 100 ? 'text-[var(--chart-error-text)]' : ''"
            >
              {{ row.slaUsedPct === null ? '—' : `${formatDecimal(row.slaUsedPct, locale)}%` }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="rows.length === 0" class="text-xs text-surface-600">{{ t('insights.empty') }}</p>
  </div>
</template>
