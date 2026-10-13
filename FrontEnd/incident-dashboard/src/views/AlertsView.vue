<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchAlerts, type Alert } from '@/apis/insights'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InsightPanel from '@/components/insights/InsightPanel.vue'
import PriorityPill from '@/components/insights/PriorityPill.vue'
import ScopeSwitch from '@/components/insights/ScopeSwitch.vue'
import TicketLink from '@/components/insights/TicketLink.vue'
import { useInsight } from '@/composables/useInsight'
import { useInsightPage } from '@/composables/useInsightPage'
import AppLayout from '@/layout/AppLayout.vue'
import { formatDecimal } from '@/utils/number'

/**
 * Live alerts: rules evaluated over the OPEN tickets right now (the date window does not apply —
 * an alert is about the present). Highest severity first. Each alert says what the rule is, how
 * many tickets trip it and lists the ten worst by SLA used, linking to Redmine, where the work is
 * done. Refreshes every minute; the dashboard itself never changes a ticket.
 */
useInsightPage(null, null)
const { t, locale } = useI18n()

const query = useInsight<Alert[]>('alerts', fetchAlerts, null, true, 60_000)
const ORDER = { CRITICAL: 0, HIGH: 1, WARNING: 2, INFO: 3 } as const
const alerts = computed(() =>
  [...(query.data.value ?? [])].sort((a, b) => ORDER[a.severity] - ORDER[b.severity]),
)

const TONE = {
  CRITICAL: 'border-[#c0312f] bg-[#fde8e8] text-[#9b1c1c]',
  HIGH: 'border-[#eb6834] bg-[#fdebe0] text-[#9a3a12]',
  WARNING: 'border-[#eda100] bg-[#fdf3d6] text-[#7a5200]',
  INFO: 'border-[#6da7ec] bg-[#e1edfb] text-[#184f95]',
} as const
const ICON = { CRITICAL: '⛔', HIGH: '▲', WARNING: '⚠', INFO: 'ℹ' } as const
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>
    <div class="flex flex-col gap-4" data-testid="alerts-view">
      <ScopeSwitch />
      <p v-if="query.isError.value" class="text-sm text-[var(--chart-error-text)]" role="alert">
        {{ t('insights.state.error') }}
      </p>
      <div
        v-else-if="query.isLoading.value"
        class="h-24 animate-pulse rounded-xl bg-surface-200"
      ></div>
      <p
        v-else-if="alerts.length === 0"
        class="rounded-xl border border-surface-200 bg-surface-0 p-6 text-sm"
        data-testid="alerts-clear"
      >
        ✓ {{ t('insights.alerts.allClear') }}
      </p>

      <InsightPanel
        v-for="a in alerts"
        :key="a.code"
        :title="t(`insights.alerts.code.${a.code}.title`)"
        :subtitle="t(`insights.alerts.code.${a.code}.rule`)"
      >
        <template #actions>
          <span
            class="inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-semibold"
            :class="TONE[a.severity]"
            :data-severity="a.severity"
          >
            <span aria-hidden="true">{{ ICON[a.severity] }}</span
            >{{ t(`insights.alerts.severity.${a.severity}`) }}
            <span v-if="a.count > 0">· {{ a.count }}</span>
          </span>
        </template>
        <table
          v-if="a.tickets.length"
          class="insight-table w-full text-sm"
          :data-testid="`alert-${a.code}`"
        >
          <thead class="text-left text-xs uppercase text-surface-600">
            <tr>
              <th scope="col" class="py-1">{{ t('insights.col.ticket') }}</th>
              <th scope="col">{{ t('insights.col.subject') }}</th>
              <th scope="col">{{ t('insights.col.priority') }}</th>
              <th scope="col" class="text-right">{{ t('insights.col.slaUsed') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tk in a.tickets" :key="tk.noIncident" class="border-t border-surface-100">
              <th scope="row" class="py-1.5 text-left font-normal">
                <TicketLink :id="tk.noIncident" />
              </th>
              <td class="max-w-96 truncate" :title="tk.subject ?? ''">{{ tk.subject }}</td>
              <td><PriorityPill :priority="tk.priority" /></td>
              <td class="text-right tabular-nums">
                {{ tk.slaUsedPct === null ? '—' : `${formatDecimal(tk.slaUsedPct, locale)}%` }}
              </td>
            </tr>
          </tbody>
        </table>
      </InsightPanel>
    </div>
  </AppLayout>
</template>
