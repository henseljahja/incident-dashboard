<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchAssignees, type AssigneeRow } from '@/apis/insights'
import { useInsight } from '@/composables/useInsight'
import { useFiltersStore } from '@/stores/filters'
import { formatCount, formatDecimal } from '@/utils/number'
import { orDash } from '@/utils/priority'

import InsightPanel from './InsightPanel.vue'
import PriorityCells from './PriorityCells.vue'

/**
 * Support-team workload: the ten assignees with the most tickets in the window, with their
 * priority mix, mean and range of resolution time (closed tickets only) and closure rate.
 * Clicking a name narrows the incident list to that person (the shared `assignedTo` filter).
 */
const emit = defineEmits<{ (e: 'filter', assignee: string): void }>()
const { t, locale } = useI18n()
const filters = useFiltersStore()
const query = useInsight<AssigneeRow[]>('assignees', fetchAssignees)
const rows = computed(() => query.data.value ?? [])
const dec = (v: number) => formatDecimal(v, locale.value)

function range(r: AssigneeRow): string {
  if (r.minResolutionDays === null || r.maxResolutionDays === null) return '—'
  return r.minResolutionDays === r.maxResolutionDays
    ? `${r.minResolutionDays}`
    : `${r.minResolutionDays}–${r.maxResolutionDays}`
}

function filterTo(r: AssigneeRow) {
  if (r.assignee === 'Unassigned') return
  filters.assignedTo = [r.assignee]
  filters.page = 0
  emit('filter', r.assignee)
}
</script>

<template>
  <InsightPanel
    :title="t('insights.assignees.title')"
    :subtitle="t('insights.assignees.subtitle')"
    :loading="query.isLoading.value"
    :error="query.isError.value"
    :empty="rows.length === 0"
    @retry="query.refetch()"
  >
    <div class="overflow-x-auto">
      <table class="insight-table w-full text-sm" data-testid="assignee-table">
        <thead class="text-left text-xs uppercase text-surface-600">
          <tr>
            <th scope="col" class="py-1">#</th>
            <th scope="col">{{ t('insights.col.assignee') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.total') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.closed') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.open') }}</th>
            <th scope="col">{{ t('insights.col.priority') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.avgResolution') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.range') }}</th>
            <th scope="col" class="min-w-36">{{ t('insights.col.closureRate') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in rows"
            :key="r.assignee"
            class="border-t border-surface-100"
            data-testid="assignee-row"
            :data-assignee="r.assignee"
          >
            <td class="py-1.5 text-surface-600">{{ r.rank }}</td>
            <th scope="row" class="text-left font-normal">
              <button
                v-if="r.assignee !== 'Unassigned'"
                type="button"
                class="min-h-8 text-left text-primary-800 underline-offset-2 hover:underline"
                @click="filterTo(r)"
              >
                {{ r.assignee }}
              </button>
              <span v-else class="italic text-surface-600">{{ t('insights.unassigned') }}</span>
            </th>
            <td class="text-right font-semibold tabular-nums">
              {{ formatCount(r.total, locale) }}
            </td>
            <td class="text-right tabular-nums">{{ formatCount(r.closed, locale) }}</td>
            <td class="text-right tabular-nums">{{ formatCount(r.open, locale) }}</td>
            <td><PriorityCells :counts="r.byPriority" /></td>
            <td class="text-right tabular-nums">{{ orDash(r.avgResolutionDays, dec) }}</td>
            <td class="text-right tabular-nums text-surface-600">{{ range(r) }}</td>
            <td>
              <div class="flex items-center gap-2">
                <div class="h-2 flex-1 rounded bg-surface-100" aria-hidden="true">
                  <div
                    class="h-2 rounded bg-primary"
                    :style="{ width: `${r.closureRatePct ?? 0}%` }"
                  ></div>
                </div>
                <span class="w-12 text-right text-xs tabular-nums">{{
                  orDash(r.closureRatePct, (v) => `${dec(v)}%`)
                }}</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </InsightPanel>
</template>
