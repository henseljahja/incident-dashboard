<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchKpi, type KpiResponse } from '@/apis/metrics'
import { useFiltersStore, type SlaFacet, type StatusFacet } from '@/stores/filters'

import KpiCard from './KpiCard.vue'

const emit = defineEmits<{
  (e: 'scroll-to-table'): void
}>()

const { t } = useI18n()
const filters = useFiltersStore()

// Reads the SAME filters store the table below reads (`toFilterParams`, excluding page/size —
// an aggregate is over the whole filtered population, never one page of it), facets included:
// the backend applies each tile's OTHER dimension, so the active tile equals the table total.
// A KPI row on a different filter than the table beneath it is a correctness bug, not a
// cosmetic one.
const queryKey = computed(() => ['metrics', 'kpi', filters.toFilterParams().toString()] as const)

const { data, isPending, isError } = useQuery({
  queryKey,
  queryFn: () => fetchKpi(filters.toFilterParams()),
  // A facet click re-keys the query. Keeping the previous figures until the new ones land stops
  // all seven tiles flashing to skeletons exactly when the operator is reading them.
  placeholderData: (previous) => previous,
})

const kpi = computed<KpiResponse | null>(() => data.value ?? null)

/** Skeletons on the first load only (DAC1) — never an empty card. */
const loading = computed(() => isPending.value && kpi.value === null)

const failed = computed(() => isError.value && kpi.value === null)

/**
 * The four status tiles ARE the table's status/SLA filter now.
 *
 * They used to only scroll to the table, because `IncidentFilter` had no breached/at-risk
 * field to honour. It has `status` and `sla` facets now. A second click clears the facet; the
 * chip row under the filter bar names what is applied and removes it. No scroll: the keyboard
 * path is tile → Enter → Tab to its ⓘ, and yanking the viewport to the table after every
 * toggle would break it, as well as the Breached-then-Open combination (AC3.2).
 */
function onStatus(value: StatusFacet) {
  filters.toggleStatus(value)
}

function onSla(value: SlaFacet) {
  filters.toggleSla(value)
}

/**
 * `workaroundDebt` has a direct filter-store equivalent: `hasWorkaround` is the same "has a
 * workaround value" predicate `IncidentQueryUseCase` applies. (MTTR is a facet tile too now: its
 * mean is over exactly the closed tickets, which is the Closed facet.)
 */
function onDebtSelect() {
  filters.hasWorkaround = true
  filters.page = 0
  emit('scroll-to-table')
}

function facetAction(pressed: boolean) {
  return pressed ? t('kpi.filterActive') : t('kpi.filterHint')
}
</script>

<template>
  <!--
    Seven tiles; seven columns only from 2xl. At `lg` the two MTTR labels wrap and a seventh
    column was pushed past the right edge of the card — clipped, not scrollable, so the last
    tile was simply unreachable on a 1280px screen. Stepping 4 -> 7 at the wider breakpoint keeps
    every card legible at every width.
  -->
  <section aria-labelledby="kpi-row-heading">
    <!--
      Visually hidden, structurally real. The tiles carry their own labels on screen, so a
      visible heading here would be redundant to a sighted reader — but without it the seven
      cards are loose buttons in the outline with nothing naming what they are collectively,
      and the page has no second-level structure to navigate by at all.
    -->
    <h2 id="kpi-row-heading" class="sr-only">{{ t('kpi.heading') }}</h2>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 2xl:grid-cols-6" data-testid="kpi-row">
      <!-- Backlog is gone (note 12): Open + Closed is the same population, split the way people ask. -->
      <KpiCard
        :label="t('kpi.open')"
        tone="info"
        icon="pi pi-folder-open"
        term="open"
        :value="kpi?.open ?? null"
        :previous-value="kpi?.previousPeriod?.open ?? null"
        :pressed="filters.status === 'OPEN'"
        :action-label="facetAction(filters.status === 'OPEN')"
        :loading="loading"
        :error="failed"
        data-kpi="open"
        @select="onStatus('OPEN')"
      />
      <KpiCard
        :label="t('kpi.closed')"
        tone="neutral"
        icon="pi pi-check-square"
        term="closed"
        :value="kpi?.closed ?? null"
        :previous-value="kpi?.previousPeriod?.closed ?? null"
        :pressed="filters.status === 'CLOSED'"
        :action-label="facetAction(filters.status === 'CLOSED')"
        :loading="loading"
        :error="failed"
        data-kpi="closed"
        @select="onStatus('CLOSED')"
      />
      <KpiCard
        :label="t('kpi.breached')"
        tone="danger"
        icon="pi pi-exclamation-triangle"
        term="breached"
        :value="kpi?.breached ?? null"
        :previous-value="kpi?.previousPeriod?.breached ?? null"
        :pressed="filters.sla === 'BREACHED'"
        :action-label="facetAction(filters.sla === 'BREACHED')"
        :loading="loading"
        :error="failed"
        data-kpi="breached"
        @select="onSla('BREACHED')"
      />
      <KpiCard
        :label="t('kpi.atRisk')"
        tone="risk"
        icon="pi pi-clock"
        term="atRisk"
        :value="kpi?.atRisk ?? null"
        :previous-value="kpi?.previousPeriod?.atRisk ?? null"
        :pressed="filters.sla === 'AT_RISK'"
        :action-label="facetAction(filters.sla === 'AT_RISK')"
        :loading="loading"
        :error="failed"
        data-kpi="at-risk"
        @select="onSla('AT_RISK')"
      />
      <!--
        ONE MTTR, and it is a facet. Workaround and permanent used to be two tiles that only
        scrolled to the table, so both listed the same unfiltered rows (stakeholder feedback,
        8 Oct). The mean is now taken over the closed tickets, and clicking the tile applies the
        Closed facet so the table is exactly the set the number comes from.
      -->
      <KpiCard
        :label="t('kpi.mttr')"
        :sublabel="t('kpi.mttrHint')"
        tone="neutral"
        icon="pi pi-stopwatch"
        term="mttr"
        :value="kpi?.mttrDays ?? null"
        :previous-value="kpi?.previousPeriod?.mttrDays ?? null"
        :pressed="filters.status === 'CLOSED'"
        :action-label="facetAction(filters.status === 'CLOSED')"
        :loading="loading"
        :error="failed"
        data-kpi="mttr"
        @select="onStatus('CLOSED')"
      />
      <KpiCard
        :label="t('kpi.workaroundDebt')"
        tone="warn"
        icon="pi pi-flag"
        term="workaroundDebt"
        :value="kpi?.workaroundDebt ?? null"
        :previous-value="kpi?.previousPeriod?.workaroundDebt ?? null"
        :action-label="t('kpi.filterWorkaround')"
        :loading="loading"
        :error="failed"
        data-testid="kpi-workaround-debt"
        @select="onDebtSelect"
      />
    </div>
  </section>
</template>
