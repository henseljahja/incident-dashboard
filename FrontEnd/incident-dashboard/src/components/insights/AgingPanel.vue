<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchAging, type AgingRow } from '@/apis/insights'
import { useInsight } from '@/composables/useInsight'
import { formatDecimal } from '@/utils/number'

import InsightPanel from './InsightPanel.vue'
import PriorityPill from './PriorityPill.vue'
import TicketLink from './TicketLink.vue'

/**
 * The ten longest-running OPEN tickets. "SLA used" is aging / SLA target: 100% means the budget
 * is exactly spent and anything above is overdue. The bar is capped at the panel width but the
 * number is not, so a ticket at 285% is visibly different from one at 101%.
 */
const { t, locale } = useI18n()
const query = useInsight<AgingRow[]>('aging', fetchAging)
const rows = computed(() => query.data.value ?? [])

function barClass(pct: number | null): string {
  if (pct === null) return 'bg-surface-300'
  return pct > 100
    ? 'bg-[var(--chart-error)]'
    : pct >= 80
      ? 'bg-[var(--chart-yellow)]'
      : 'bg-primary'
}
</script>

<template>
  <InsightPanel
    :title="t('insights.aging.title')"
    :subtitle="t('insights.aging.subtitle')"
    :loading="query.isLoading.value"
    :error="query.isError.value"
    :empty="rows.length === 0"
    @retry="query.refetch()"
  >
    <div class="overflow-x-auto">
      <table class="insight-table w-full text-sm" data-testid="aging-table">
        <thead class="text-left text-xs uppercase text-surface-600">
          <tr>
            <th scope="col" class="py-1">{{ t('insights.col.ticket') }}</th>
            <th scope="col">{{ t('insights.col.subject') }}</th>
            <th scope="col">{{ t('insights.col.priority') }}</th>
            <th scope="col">{{ t('insights.col.status') }}</th>
            <th scope="col">{{ t('insights.col.institusi') }}</th>
            <th scope="col">{{ t('insights.col.assignee') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.aging') }}</th>
            <th scope="col" class="text-right">{{ t('insights.col.slaTarget') }}</th>
            <th scope="col" class="min-w-40">{{ t('insights.col.slaUsed') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in rows"
            :key="r.noIncident"
            class="border-t border-surface-100"
            data-testid="aging-row"
            :data-incident="r.noIncident"
          >
            <th scope="row" class="py-1.5 text-left font-normal">
              <TicketLink :id="r.noIncident" />
            </th>
            <td class="max-w-72 truncate" :title="r.subject ?? ''">{{ r.subject }}</td>
            <td><PriorityPill :priority="r.priority" /></td>
            <td>{{ r.status }}</td>
            <td>{{ r.institusi ?? '—' }}</td>
            <td class="max-w-44 truncate">{{ r.assignedTo ?? '—' }}</td>
            <td class="text-right tabular-nums">{{ r.agingDays ?? '—' }}</td>
            <td class="text-right tabular-nums text-surface-600">{{ r.slaDays ?? '—' }}</td>
            <td>
              <div class="flex items-center gap-2">
                <div class="h-2 flex-1 rounded bg-surface-100" aria-hidden="true">
                  <div
                    class="h-2 rounded"
                    :class="barClass(r.slaUsedPct)"
                    :style="{ width: `${Math.min(r.slaUsedPct ?? 0, 100)}%` }"
                  ></div>
                </div>
                <span
                  class="w-16 text-right text-xs tabular-nums"
                  :class="
                    (r.slaUsedPct ?? 0) > 100 ? 'font-semibold text-[var(--chart-error-text)]' : ''
                  "
                >
                  {{ r.slaUsedPct === null ? '—' : `${formatDecimal(r.slaUsedPct, locale)}%` }}
                </span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </InsightPanel>
</template>
