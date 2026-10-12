<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatDate } from '@/utils/date'

import type { DefinitionTerm } from '@/apis/definitions'
import { fetchKpi, fetchTopCategories, type KpiResponse, type TopCategory } from '@/apis/metrics'
import MttrComparison from '@/components/charts/MttrComparison.vue'
import InfoHint from '@/components/common/InfoHint.vue'
import AppLayout from '@/layout/AppLayout.vue'
import TopCategories from '@/components/charts/TopCategories.vue'
import WorkaroundDebtCard from '@/components/charts/WorkaroundDebtCard.vue'
import { formatCount, formatSignedDelta } from '@/utils/number'

/**
 * Task 22. The exec pulse — a monthly summary, not a live operator surface.
 *
 * Deliberately does NOT use `useFiltersStore`: there is no filter bar here (see `WallboardView`
 * for the same reasoning), and the window this view asks for is fixed to the current calendar
 * month rather than whatever an operator last typed into the Ops filter bar.
 *
 * Deliberately does NOT auto-refresh on an interval the way `WallboardView` does — a monthly
 * pulse read once per visit is the point; polling it every 60s would encourage reading
 * within-month noise as trend, which is exactly what the monthly window exists to prevent.
 *
 * Deliberately does NOT call `GET /api/metrics/trend`: nothing on this view plots a
 * created/closed series, and the two mean-times it does plot
 * (`mttrWorkaroundDays`/`mttrPermanentDays`) come off `KpiResponse`, not `TrendResponse`.
 *
 * Strictly read-only: every figure here is a number or a delta, never an edit affordance.
 * Follow-up on an incident is a Redmine deep-link, not a form on this page.
 */

const { t, locale } = useI18n()

const kpi = ref<KpiResponse | null>(null)
const topCategories = ref<TopCategory[]>([])

/**
 * Load state, because `void load()` swallowed every failure.
 *
 * This view has no TanStack Query and no retry: a rejected `load()` left `kpi` null forever
 * with nothing rendered to say so, so an expired session or a 403 presented as tiles that
 * simply never finished loading. There was no error path at all — not a silent one, none.
 */
const loading = ref(true)
const loadError = ref<string | null>(null)

function monthlyWindowParams(now: Date = new Date()): URLSearchParams {
  const from = new Date(now.getFullYear(), now.getMonth(), 1)
  const params = new URLSearchParams()
  params.set('dateFrom', formatDate(from))
  params.set('dateTo', formatDate(now))
  return params
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const params = monthlyWindowParams()
    const [kpiResult, topCategoriesResult] = await Promise.all([
      fetchKpi(params),
      fetchTopCategories(params),
    ])
    kpi.value = kpiResult
    topCategories.value = topCategoriesResult
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : t('errors.execLoad')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

interface Delta {
  value: number | null
  label: string
}

function computeDelta(
  current: number | null | undefined,
  previous: number | null | undefined,
): Delta {
  if (current === null || current === undefined || previous === null || previous === undefined) {
    return { value: null, label: t('common.noPriorPeriod') }
  }
  const diff = current - previous
  return {
    value: diff,
    label: formatSignedDelta(diff, locale.value, { maximumFractionDigits: 0 }),
  }
}

interface HeadlineTile {
  key: string
  label: string
  term: DefinitionTerm
  value: number | null
  delta: Delta
}

/**
 * Open / Closed / Breached / At Risk — the same four the Ops row and the wallboard show, so a
 * figure quoted from one screen can be found on the others. Backlog is gone everywhere (note 12).
 *
 * NOT clickable: this is a summary page with no table to filter, and a tile that looks like the
 * Ops tile but does nothing when pressed is worse than a tile that is plainly a figure.
 */
const headlineTiles = computed<HeadlineTile[]>(() => [
  {
    key: 'open',
    label: t('kpi.open'),
    term: 'open',
    value: kpi.value?.open ?? null,
    delta: computeDelta(kpi.value?.open, kpi.value?.previousPeriod?.open),
  },
  {
    key: 'closed',
    label: t('kpi.closed'),
    term: 'closed',
    value: kpi.value?.closed ?? null,
    delta: computeDelta(kpi.value?.closed, kpi.value?.previousPeriod?.closed),
  },
  {
    key: 'breached',
    label: t('kpi.breached'),
    term: 'breached',
    value: kpi.value?.breached ?? null,
    delta: computeDelta(kpi.value?.breached, kpi.value?.previousPeriod?.breached),
  },
  {
    key: 'at-risk',
    label: t('kpi.atRisk'),
    term: 'atRisk',
    value: kpi.value?.atRisk ?? null,
    delta: computeDelta(kpi.value?.atRisk, kpi.value?.previousPeriod?.atRisk),
  },
])
</script>

<template>
  <AppLayout>
    <template #header-actions>
      <span class="text-sm text-surface-600">{{ t('kpi.exec.thisMonth') }}</span>
    </template>

    <div class="flex flex-col gap-6" data-testid="exec-view">
      <!--
        A failure has to be visible and recoverable. Without this the view rendered its tiles
        against a null payload and simply never finished — indistinguishable from a slow load,
        with nothing to click and nothing to read.
      -->
      <Message
        v-if="loadError"
        severity="error"
        :closable="false"
        role="alert"
        data-testid="exec-load-error"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>{{ loadError }}</span>
          <Button :label="t('common.retry')" icon="pi pi-refresh" size="small" @click="load" />
        </div>
      </Message>

      <div
        v-else-if="loading"
        class="flex items-center gap-2 text-sm text-surface-600"
        data-testid="exec-loading"
        aria-live="polite"
      >
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        {{ t('kpi.exec.loading') }}
      </div>

      <section aria-labelledby="exec-headline-heading" class="flex flex-col gap-2">
        <div class="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="exec-headline-heading" class="text-sm font-semibold text-surface-700">
            {{ t('kpi.exec.headline') }}
          </h2>
          <!--
            The comparison is named ONCE here instead of on all five tiles.
 
            Every delta used to end in "vs. previous period", which repeated the same six words
            five times in a 4-column grid and pushed the number — the part being read — into a
            wrapped second line. The window is a property of the section, not of each figure.
          -->
          <span class="text-xs text-surface-600">{{ t('kpi.exec.compared') }}</span>
        </div>
        <div class="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="exec-headline-tiles">
          <div
            v-for="tile in headlineTiles"
            :key="tile.key"
            class="flex flex-col items-start gap-1 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
            :data-testid="`exec-tile-${tile.key}`"
          >
            <span class="flex w-full items-center justify-between gap-2">
              <span class="text-xs font-medium tracking-wide text-surface-600 uppercase">
                {{ tile.label }}
              </span>
              <InfoHint :term="tile.term" class="text-surface-600" />
            </span>
            <span
              class="text-3xl font-semibold text-surface-900 tabular-nums"
              :data-testid="`exec-tile-${tile.key}-value`"
            >
              {{ tile.value === null ? '—' : formatCount(tile.value, locale) }}
            </span>
            <!--
            Colour encodes DIRECTION, not magnitude, and up is bad here: every headline tile
            counts incidents or days-to-resolve, so a rise is a regression. Kept as an explicit
            map rather than a generic "positive = green" helper, because inverting that on a
            future tile that measures something good would be a one-character mistake.
          -->
            <span
              class="text-sm"
              :data-testid="`exec-tile-${tile.key}-delta`"
              :class="{
                'text-[var(--chart-error-text)]': (tile.delta.value ?? 0) > 0,
                'text-[var(--chart-success-text)]': (tile.delta.value ?? 0) < 0,
                'text-surface-600': tile.delta.value === null || tile.delta.value === 0,
              }"
            >
              {{ tile.delta.label }}
            </span>
          </div>

          <WorkaroundDebtCard :kpi="kpi" />
        </div>
      </section>

      <MttrComparison :kpi="kpi" />

      <TopCategories :categories="topCategories" />
    </div>
  </AppLayout>
</template>
