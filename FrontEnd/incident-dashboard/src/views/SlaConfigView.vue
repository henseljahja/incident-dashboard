<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { useI18n } from 'vue-i18n'

import {
  DEFINITIONS_QUERY_KEY,
  DEFINITIONS_STALE_TIME_MS,
  fetchDefinitions,
} from '@/apis/definitions'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InsightPanel from '@/components/insights/InsightPanel.vue'
import PriorityPill from '@/components/insights/PriorityPill.vue'
import AppLayout from '@/layout/AppLayout.vue'

/**
 * The SLA policy the numbers on every other page were computed with, read straight from the
 * config tables: the per-priority budget and at-risk cushion, the fallback for a priority with no
 * policy, the statuses the business counts as closed, where the clock stops, and how often the
 * mart refreshes. It is a window onto the config, not an editor: the policy is changed in
 * `CFG_SLA_POLICY` (which re-derives the mart on the next refresh) so a threshold can never be
 * edited from a screen whose job is to display it.
 */
const { t } = useI18n()
const query = useQuery({
  queryKey: DEFINITIONS_QUERY_KEY,
  queryFn: fetchDefinitions,
  staleTime: DEFINITIONS_STALE_TIME_MS,
})
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>
    <div class="flex flex-col gap-4" data-testid="sla-config-view">
      <p class="rounded-lg border-l-4 border-primary bg-surface-50 p-3 text-sm text-surface-700">
        {{ t('insights.sla.note') }}
      </p>
      <p v-if="query.isError.value" class="text-sm text-[var(--chart-error-text)]" role="alert">
        {{ t('insights.state.error') }}
      </p>
      <template v-if="query.data.value">
        <InsightPanel :title="t('insights.sla.ladder')" :subtitle="t('insights.sla.ladderSub')">
          <table class="insight-table w-full text-sm" data-testid="sla-ladder">
            <thead class="text-left text-xs uppercase text-surface-600">
              <tr>
                <th scope="col" class="py-1">{{ t('insights.col.priority') }}</th>
                <th scope="col" class="text-right">{{ t('insights.sla.days') }}</th>
                <th scope="col" class="text-right">{{ t('insights.sla.cushion') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="p in query.data.value.slaPolicy"
                :key="p.priorityId"
                class="border-t border-surface-100"
                data-testid="sla-row"
              >
                <th scope="row" class="py-1.5 text-left font-normal">
                  <PriorityPill :priority="p.priorityName" />
                </th>
                <td class="text-right tabular-nums">{{ p.slaDays }}</td>
                <td class="text-right tabular-nums">{{ p.atRiskRemainingDays }}</td>
              </tr>
              <tr class="border-t border-surface-100 text-surface-600">
                <th scope="row" class="py-1.5 text-left font-normal italic">
                  {{ t('insights.sla.unknownPriority') }}
                </th>
                <td class="text-right tabular-nums">
                  {{ query.data.value.unknownPriority.slaDays }}
                </td>
                <td class="text-right tabular-nums">
                  {{ query.data.value.unknownPriority.atRiskRemainingDays }}
                </td>
              </tr>
            </tbody>
          </table>
        </InsightPanel>

        <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <InsightPanel
            :title="t('insights.sla.closeRule')"
            :subtitle="t('insights.sla.closeRuleSub')"
          >
            <ol class="list-decimal pl-5 text-sm" data-testid="sla-close-rule">
              <li v-for="rule in query.data.value.closeRule" :key="rule">
                {{ t(`definitions.closeBasis.${rule}`) }}
              </li>
            </ol>
          </InsightPanel>
          <InsightPanel
            :title="t('insights.sla.closedByOverride')"
            :subtitle="t('insights.sla.closedByOverrideSub')"
            :empty="query.data.value.closedByOverride.length === 0"
          >
            <ul class="text-sm" data-testid="sla-overrides">
              <li v-for="s in query.data.value.closedByOverride" :key="s.statusId">
                {{ s.statusName }}
              </li>
            </ul>
          </InsightPanel>
        </div>
        <p class="text-sm text-surface-700" data-testid="sla-refresh">
          {{ t('insights.sla.refresh', { n: query.data.value.refreshIntervalMinutes }) }}
        </p>
      </template>
      <div
        v-else-if="query.isLoading.value"
        class="h-40 animate-pulse rounded-xl bg-surface-200"
      ></div>
    </div>
  </AppLayout>
</template>
