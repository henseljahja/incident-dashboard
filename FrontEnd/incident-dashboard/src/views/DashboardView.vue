<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import AgingPanel from '@/components/insights/AgingPanel.vue'
import AssigneePanel from '@/components/insights/AssigneePanel.vue'
import CategoryPanel from '@/components/insights/CategoryPanel.vue'
import DailyTrendPanel from '@/components/insights/DailyTrendPanel.vue'
import KpiStrip from '@/components/insights/KpiStrip.vue'
import MonthlyPanel from '@/components/insights/MonthlyPanel.vue'
import PeriodBar from '@/components/insights/PeriodBar.vue'
import PriorityDonut from '@/components/insights/PriorityDonut.vue'
import { useInsightPage } from '@/composables/useInsightPage'
import AppLayout from '@/layout/AppLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { useFiltersStore } from '@/stores/filters'

/**
 * RD 22524 dashboard: the home overview (KPIs, daily trend, priority mix) and three focused tabs —
 * Summary & Assignment, Aging, Category. Everything reads the shared filter store, so the period,
 * scope and any filter set on the Incidents page carry over, and clicking an assignee or an
 * institusi narrows the incident list (the "Open in list" link carries the filters there).
 *
 * Row-level panels (aging carries ticket subjects) only render for roles that may read the
 * incident list; the server enforces the same rule, this just avoids a 403 panel.
 */
useInsightPage('INCIDENT')

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const filters = useFiltersStore()

const TABS = ['overview', 'summary', 'aging', 'category'] as const
type Tab = (typeof TABS)[number]
const tab = ref<Tab>('overview')

const canSeeRows = computed(() =>
  ['incident.ops', 'incident.problem', 'incident.sr'].some((menu) => auth.can(menu, 'view')),
)
const tabs = computed(() => TABS.filter((id) => id !== 'aging' || canSeeRows.value))

function onKey(event: KeyboardEvent, index: number) {
  const last = tabs.value.length - 1
  const next =
    event.key === 'ArrowRight'
      ? index === last
        ? 0
        : index + 1
      : event.key === 'ArrowLeft'
        ? index === 0
          ? last
          : index - 1
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? last
            : null
  if (next === null) return
  event.preventDefault()
  tab.value = tabs.value[next]!
  document.getElementById(`dash-tab-${tab.value}`)?.focus()
}

function openInList() {
  void router.push({
    path:
      filters.scope === 'PROBLEM'
        ? '/problem'
        : filters.scope === 'SERVICE_REQUEST'
          ? '/service-requests'
          : '/ops',
    query: Object.fromEntries(new URLSearchParams(filters.serialize())),
  })
}
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>

    <div class="flex flex-col gap-5">
      <PeriodBar />
      <KpiStrip />

      <div
        role="tablist"
        :aria-label="t('insights.dashboard.tabs')"
        class="flex flex-wrap gap-1 border-b border-surface-200"
      >
        <button
          v-for="(id, index) in tabs"
          :id="`dash-tab-${id}`"
          :key="id"
          type="button"
          role="tab"
          class="min-h-11 border-b-2 px-4 text-sm"
          :class="
            tab === id
              ? 'border-primary-600 font-semibold text-primary-800'
              : 'border-transparent text-surface-700'
          "
          :aria-selected="tab === id"
          :aria-controls="`dash-panel-${id}`"
          :tabindex="tab === id ? 0 : -1"
          :data-testid="`tab-${id}`"
          @click="tab = id"
          @keydown="onKey($event, index)"
        >
          {{ t(`insights.dashboard.tab.${id}`) }}
        </button>
        <button
          type="button"
          class="ml-auto min-h-11 px-3 text-sm text-primary-800 hover:underline"
          data-testid="open-in-list"
          @click="openInList"
        >
          {{ t('insights.dashboard.openInList') }} →
        </button>
      </div>

      <div
        v-if="tab === 'overview'"
        id="dash-panel-overview"
        role="tabpanel"
        aria-labelledby="dash-tab-overview"
        class="grid grid-cols-1 gap-4 xl:grid-cols-3"
      >
        <DailyTrendPanel class="xl:col-span-2" />
        <PriorityDonut />
      </div>
      <div
        v-else-if="tab === 'summary'"
        id="dash-panel-summary"
        role="tabpanel"
        aria-labelledby="dash-tab-summary"
        class="flex flex-col gap-4"
      >
        <MonthlyPanel />
        <AssigneePanel />
      </div>
      <div
        v-else-if="tab === 'aging'"
        id="dash-panel-aging"
        role="tabpanel"
        aria-labelledby="dash-tab-aging"
      >
        <AgingPanel />
      </div>
      <div v-else id="dash-panel-category" role="tabpanel" aria-labelledby="dash-tab-category">
        <CategoryPanel />
      </div>
    </div>
  </AppLayout>
</template>
