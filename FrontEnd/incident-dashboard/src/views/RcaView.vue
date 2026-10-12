<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchRca, type Rca } from '@/apis/insights'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InsightPanel from '@/components/insights/InsightPanel.vue'
import PeriodBar from '@/components/insights/PeriodBar.vue'
import TicketLink from '@/components/insights/TicketLink.vue'
import { useInsight } from '@/composables/useInsight'
import { useInsightPage } from '@/composables/useInsightPage'
import AppLayout from '@/layout/AppLayout.vue'
import { formatCount, formatDecimal } from '@/utils/number'
import { orDash } from '@/utils/priority'

/**
 * Problem management & root-cause analysis. How completely problem tickets are worked up:
 *
 * - **Root cause documented** — the Redmine rootcause field is non-empty.
 * - **Workaround** — the workaround field is non-empty.
 * - **Permanent solution** — the report's SOLUTION classification includes a permanent fix.
 * - **Workaround debt** — has a workaround but no permanent solution: the problems that will
 *   come back.
 *
 * Below, the OPEN problems with no root cause yet, oldest first — the Problem manager's worklist.
 * Defaults to the Problem scope and all time; both can be changed with the bar.
 */
useInsightPage('PROBLEM', null)
const { t, locale } = useI18n()
const query = useInsight<Rca>('rca', fetchRca)
const r = computed(() => query.data.value ?? null)
const dec = (v: number) => formatDecimal(v, locale.value)

const coverage = computed(() =>
  r.value
    ? [
        { id: 'rootCause', pct: r.value.rootCausePct, n: r.value.withRootCause },
        { id: 'workaround', pct: r.value.workaroundPct, n: r.value.withWorkaround },
        { id: 'permanent', pct: r.value.permanentPct, n: r.value.permanentSolution },
      ]
    : [],
)
const maxCategory = computed(() => Math.max(1, ...(r.value?.byCategory ?? []).map((c) => c.count)))
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>
    <div class="flex flex-col gap-5" data-testid="rca-view">
      <PeriodBar />
      <p v-if="query.isError.value" class="text-sm text-[var(--chart-error-text)]" role="alert">
        {{ t('insights.state.error') }}
      </p>
      <div
        v-else-if="query.isLoading.value"
        class="h-40 animate-pulse rounded-xl bg-surface-200"
      ></div>

      <template v-if="r">
        <div class="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <article
            class="rounded-xl border border-surface-200 bg-surface-0 p-4"
            data-testid="rca-total"
          >
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.rca.total') }}</h3>
            <p class="text-3xl font-semibold tabular-nums">{{ formatCount(r.total, locale) }}</p>
            <p class="text-xs text-surface-600">
              {{ t('insights.rca.openSub', { n: formatCount(r.open, locale) }) }}
            </p>
          </article>
          <article
            v-for="c in coverage"
            :key="c.id"
            class="rounded-xl border border-surface-200 bg-surface-0 p-4"
            :data-testid="`rca-${c.id}`"
          >
            <h3 class="text-xs uppercase text-surface-600">{{ t(`insights.rca.${c.id}`) }}</h3>
            <p class="text-3xl font-semibold tabular-nums">
              {{ orDash(c.pct, (v) => `${dec(v)}%`) }}
            </p>
            <p class="text-xs text-surface-600">
              {{
                t('insights.rca.ofTotal', {
                  n: formatCount(c.n, locale),
                  total: formatCount(r.total, locale),
                })
              }}
            </p>
          </article>
          <article
            class="rounded-xl border border-[var(--chart-yellow)] bg-surface-0 p-4"
            data-testid="rca-debt"
          >
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.rca.debt') }}</h3>
            <p class="text-3xl font-semibold tabular-nums">
              {{ formatCount(r.workaroundOnly, locale) }}
            </p>
            <p class="text-xs text-surface-600">{{ t('insights.rca.debtSub') }}</p>
          </article>
        </div>

        <div class="grid grid-cols-1 gap-4 xl:grid-cols-3">
          <InsightPanel :title="t('insights.rca.byCategory')" :empty="r.byCategory.length === 0">
            <ol class="flex flex-col gap-2" data-testid="rca-categories">
              <li v-for="c in r.byCategory" :key="c.label">
                <div class="flex justify-between text-sm">
                  <span>{{ c.label }}</span
                  ><span class="font-semibold tabular-nums">{{
                    formatCount(c.count, locale)
                  }}</span>
                </div>
                <div class="h-2 rounded bg-surface-100" aria-hidden="true">
                  <div
                    class="h-2 rounded bg-primary"
                    :style="{ width: `${(c.count * 100) / maxCategory}%` }"
                  ></div>
                </div>
              </li>
            </ol>
          </InsightPanel>

          <InsightPanel
            class="xl:col-span-2"
            :title="t('insights.rca.worklist')"
            :subtitle="t('insights.rca.worklistSub')"
            :empty="r.missingRootCause.length === 0"
          >
            <div class="overflow-x-auto">
              <table class="insight-table w-full text-sm" data-testid="rca-worklist">
                <thead class="text-left text-xs uppercase text-surface-600">
                  <tr>
                    <th scope="col" class="py-1">{{ t('insights.col.ticket') }}</th>
                    <th scope="col">{{ t('insights.col.subject') }}</th>
                    <th scope="col">{{ t('insights.col.status') }}</th>
                    <th scope="col">{{ t('insights.col.category') }}</th>
                    <th scope="col">{{ t('insights.col.assignee') }}</th>
                    <th scope="col" class="text-right">{{ t('insights.col.aging') }}</th>
                    <th scope="col">{{ t('insights.rca.workaround') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in r.missingRootCause"
                    :key="row.noIncident"
                    class="border-t border-surface-100"
                    data-testid="rca-row"
                  >
                    <th scope="row" class="py-1.5 text-left font-normal">
                      <TicketLink :id="row.noIncident" />
                    </th>
                    <td class="max-w-72 truncate" :title="row.subject ?? ''">{{ row.subject }}</td>
                    <td>{{ row.status }}</td>
                    <td>{{ row.category ?? '—' }}</td>
                    <td class="max-w-40 truncate">{{ row.assignedTo ?? '—' }}</td>
                    <td class="text-right tabular-nums">{{ row.agingDays ?? '—' }}</td>
                    <td>{{ row.hasWorkaround ? t('common.yes') : t('common.no') }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </InsightPanel>
        </div>
      </template>
    </div>
  </AppLayout>
</template>
