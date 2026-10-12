<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchTopAssignees } from '@/apis/insights'
import { useFiltersStore } from '@/stores/filters'
import { formatCount, formatDecimal } from '@/utils/number'

/** Summary & Assignment mock-up: the ten busiest assignees with their closure rate. */
const { t, locale } = useI18n()
const filters = useFiltersStore()

const { data } = useQuery({
  queryKey: computed(
    () => ['insights', 'assignees', filters.toBaseFilterParams().toString()] as const,
  ),
  queryFn: () => fetchTopAssignees(filters.toBaseFilterParams(), 10),
})
const rows = computed(() => data.value ?? [])
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="top-assignees"
  >
    <h2 class="text-sm font-semibold text-surface-700">{{ t('insights.assignees.title') }}</h2>
    <div class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead class="text-left text-xs uppercase text-surface-600">
          <tr>
            <th scope="col" class="py-1">{{ t('insights.assignees.assignee') }}</th>
            <th scope="col" class="text-right">{{ t('insights.assignees.total') }}</th>
            <th scope="col" class="text-right">{{ t('insights.assignees.open') }}</th>
            <th scope="col" class="text-right">{{ t('insights.assignees.closed') }}</th>
            <th scope="col" class="text-right">{{ t('insights.assignees.breached') }}</th>
            <th scope="col" class="text-right">{{ t('insights.assignees.closureRate') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.assignee"
            class="border-t border-surface-100"
            data-testid="assignee-row"
            :data-assignee="row.assignee"
          >
            <th scope="row" class="py-1 text-left font-normal">{{ row.assignee }}</th>
            <td class="text-right tabular-nums">{{ formatCount(row.total, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(row.open, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(row.closed, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(row.breached, locale) }}</td>
            <td class="text-right tabular-nums">
              {{
                row.closureRatePct === null ? '—' : `${formatDecimal(row.closureRatePct, locale)}%`
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="rows.length === 0" class="text-xs text-surface-600">{{ t('insights.empty') }}</p>
  </div>
</template>
