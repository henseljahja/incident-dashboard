<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import { fetchIncidents, type IncidentRow } from '@/apis/incidents'
import CreatedClosedTrend from '@/components/charts/CreatedClosedTrend.vue'
import ImpactUrgencyHeatmap from '@/components/charts/ImpactUrgencyHeatmap.vue'
import SlaBucketBars from '@/components/charts/SlaBucketBars.vue'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import IncidentFilterBar from '@/components/filters/IncidentFilterBar.vue'
import InsightsSection from '@/components/insights/InsightsSection.vue'
import KpiRow from '@/components/kpi/KpiRow.vue'
import ScopeCounts from '@/components/kpi/ScopeCounts.vue'
import IncidentDetailDrawer from '@/components/table/IncidentDetailDrawer.vue'
import IncidentTable from '@/components/table/IncidentTable.vue'
import AppLayout from '@/layout/AppLayout.vue'
import { fetchMatrix, normalizeMatrix, type MatrixCell } from '@/apis/metrics'
import { useFiltersStore, type Scope } from '@/stores/filters'
import { formatCount } from '@/utils/number'

const { t, locale } = useI18n()
const filters = useFiltersStore()
const route = useRoute()
const router = useRouter()
const tableAnchor = ref<HTMLElement | null>(null)

/**
 * One view, three pages: `/ops`, `/problem` and `/service-requests` differ only in the route's
 * `meta.scope`. The scope goes into the filters store BEFORE the first query is built (this runs
 * during setup, `onMounted` below runs after), and again whenever the route changes while this
 * instance is reused — vue-router keeps the component alive between two routes that share it.
 * `setScope` starts the new page with clean filters, so nothing from Incident leaks into Problem.
 * A route with no scope (a bare `/ops` mount) is the Incident page.
 */
const scope = computed<Scope>(() => route.meta.scope ?? 'INCIDENT')
watch(scope, (next) => filters.setScope(next), { immediate: true })

/** Impact x urgency only exists for incidents; Problem and SR tickets do not carry it (D8). */
const showMatrix = computed(() => scope.value === 'INCIDENT')

/**
 * The RD 22524 analytics block is opt-in: it adds seven aggregate queries, which an operator who
 * only wants the list should not pay for on every page load.
 */
const insightsOpen = ref(false)

function scrollToTable() {
  tableAnchor.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// The heatmap's drill-through applies the clicked cell's impact/urgency as a category filter
// stand-in isn't possible (IncidentFilter has no impact/urgency field) — instead it narrows the
// visible table by scrolling the operator to it after the cell click, matching KpiRow's
// "clickable → scroll to the table" convention. See MatrixResponse's javadoc for why
// Unclassified/Unknown cells are real, clickable cells too.
function onHeatmapDrill() {
  scrollToTable()
}

// Base params — the heatmap describes the period, not the tile facets (see `toBaseFilterParams`).
const matrixQueryKey = computed(
  () => ['metrics', 'matrix', filters.toBaseFilterParams().toString()] as const,
)
const { data: matrixData } = useQuery({
  queryKey: matrixQueryKey,
  queryFn: () => fetchMatrix(filters.toBaseFilterParams()),
  enabled: showMatrix,
})
const matrix = computed(() => normalizeMatrix(matrixData.value))
const matrixCells = computed<MatrixCell[]>(() => matrix.value.cells)

/** The "N missing" link: narrow the table to exactly those tickets (a removable chip names it). */
function onShowMissing() {
  filters.matrixMissing = true
  filters.page = 0
  scrollToTable()
}

// Restore filter state from the URL once, on mount — a bookmarked/shared link reproduces the
// same filtered page. Unknown query keys are ignored; see `filters.applyFromQueryString`.
onMounted(() => {
  const queryString = new URLSearchParams(route.query as Record<string, string>).toString()
  if (queryString) {
    filters.applyFromQueryString(queryString)
  }
})

// Keeps the address bar pasteable: any filter/page change is reflected into the URL.
watch(
  () => filters.serialize(),
  (queryString) => {
    void router.replace({
      path: route.path,
      query: Object.fromEntries(new URLSearchParams(queryString)),
    })
  },
)

// `toPageParams`, not `serialize`: the request carries the scope, the address bar does not.
const queryKey = computed(() => ['incidents', filters.toPageParams().toString()] as const)

const { data, isFetching } = useQuery({
  queryKey,
  queryFn: () => fetchIncidents(filters.toPageParams()),
})

const selectedRow = ref<IncidentRow | null>(null)
const drawerVisible = ref(false)

/**
 * Changing the filters closes the detail drawer.
 *
 * Leaving it open showed a row that the table beneath it no longer contained — the data was
 * not wrong (it is the incident the user asked for) but the screen contradicted itself, and
 * there was no cue that the open row had fallen out of the result set.
 *
 * Keyed on the filter fields only, NOT `serialize()`, which also carries `page`: paging is
 * navigation within the same result set, and closing the drawer on it would be gratuitous.
 */
watch(
  () => filters.toFilterParams().toString(),
  () => {
    drawerVisible.value = false
  },
)

function onSelect(row: IncidentRow) {
  selectedRow.value = row
  drawerVisible.value = true
}

function onPageChange(page: number) {
  filters.page = page
}

/**
 * The table total, announced once the new result has LANDED.
 *
 * A facet click changes the table without moving focus — focus stays on the tile, which is the
 * point of the keyboard path — so a screen-reader user otherwise hears "pressed" and nothing
 * about what the table now holds. Empty while fetching, so the region never reads out the
 * previous total as if it were the new one.
 */
const totalAnnouncement = computed(() =>
  isFetching.value || !data.value
    ? ''
    : // Plural form picked on the raw total; the figure itself is formatted for the locale.
      t(
        'table.totalAnnouncement',
        { count: formatCount(data.value.totalElements, locale.value) },
        data.value.totalElements,
      ),
)
</script>

<template>
  <AppLayout>
    <template #header-actions>
      <FreshnessIndicator />
    </template>

    <div class="flex flex-col gap-6">
      <ScopeCounts :current="scope" :params="filters.toBaseFilterParams()" />
      <KpiRow @scroll-to-table="scrollToTable" />
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <!--
          The matrix gets two of three columns: the mart yields up to six impact columns
          (four severities, Unclassified, Unknown) at 64px each, which a one-third card clipped
          behind its own scroll container. The trend is a time series and reads best full width.
        -->
        <ImpactUrgencyHeatmap
          v-if="showMatrix"
          class="lg:col-span-2"
          :cells="matrixCells"
          :missing-count="matrix.missingCount"
          :impact-order="matrix.impactOrder"
          :urgency-order="matrix.urgencyOrder"
          @drill="onHeatmapDrill"
          @show-missing="onShowMissing"
        />
        <SlaBucketBars :class="showMatrix ? '' : 'lg:col-span-3'" />
        <CreatedClosedTrend class="lg:col-span-3" />
      </div>
      <div class="flex flex-col gap-3">
        <button
          type="button"
          class="min-h-11 self-start rounded border border-surface-300 bg-surface-0 px-4 text-sm"
          data-testid="insights-toggle"
          :aria-expanded="insightsOpen"
          @click="insightsOpen = !insightsOpen"
        >
          {{ insightsOpen ? t('insights.hide') : t('insights.show') }}
        </button>
        <InsightsSection v-if="insightsOpen" />
      </div>
      <IncidentFilterBar />
      <p class="sr-only" aria-live="polite" data-testid="table-total-announcement">
        {{ totalAnnouncement }}
      </p>
      <div ref="tableAnchor">
        <IncidentTable
          :rows="data?.content ?? []"
          :loading="isFetching"
          :page="filters.page"
          :size="data?.size ?? filters.size"
          :total-elements="data?.totalElements ?? 0"
          @update:page="onPageChange"
          @select="onSelect"
        />
      </div>
      <IncidentDetailDrawer v-model:visible="drawerVisible" :row="selectedRow" />
    </div>
  </AppLayout>
</template>
