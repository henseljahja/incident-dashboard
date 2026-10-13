<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchHome, type Home } from '@/apis/insights'
import { useInsight } from '@/composables/useInsight'
import { useFiltersStore } from '@/stores/filters'
import { formatCount, formatDecimal } from '@/utils/number'
import { deltaPct, previousWindow } from '@/utils/period'
import { orDash } from '@/utils/priority'

/**
 * The five headline figures: total, MTTR, SLA compliance, open breaches, closure rate — each with
 * the qualifier that makes it checkable (open/closed, min-max, on-time/late, threshold, avg
 * aging) and, when the window is bounded, its change against the equal-length window before it.
 *
 * "Better" is per metric: a lower MTTR or breach count is good, a higher compliance or closure
 * rate is good, and a change in raw volume is neutral. The arrow's colour follows that, and the
 * arrow is always accompanied by the signed percentage, so colour is never the only signal.
 */
const { t, locale } = useI18n()
const filters = useFiltersStore()

const current = useInsight<Home>('home', fetchHome)

const prevWindow = computed(() => previousWindow(filters.dateFrom, filters.dateTo))
const previous = useQuery({
  queryKey: computed(
    () =>
      ['insights', 'home-prev', filters.toBaseFilterParams().toString(), prevWindow.value] as const,
  ),
  queryFn: () => {
    const params = filters.toBaseFilterParams()
    params.set('dateFrom', prevWindow.value!.from)
    params.set('dateTo', prevWindow.value!.to)
    return fetchHome(params)
  },
  enabled: computed(() => prevWindow.value !== null),
})

type Direction = 'up-good' | 'down-good' | 'neutral'
interface Tile {
  id: string
  value: string
  unit?: string
  sub: string
  delta: number | null
  direction: Direction
  tone: 'default' | 'danger'
}

const tiles = computed<Tile[]>(() => {
  const h = current.data.value
  const p = previous.data.value ?? null
  if (!h) return []
  const dec = (v: number) => formatDecimal(v, locale.value)
  return [
    {
      id: 'total',
      value: formatCount(h.total, locale.value),
      sub: t('insights.kpi.totalSub', {
        open: formatCount(h.open, locale.value),
        closed: formatCount(h.closed, locale.value),
      }),
      delta: deltaPct(h.total, p?.total ?? null),
      direction: 'neutral',
      tone: 'default',
    },
    {
      id: 'mttr',
      value: orDash(h.mttrDays, dec),
      unit: h.mttrDays === null ? undefined : t('insights.unit.days'),
      sub:
        h.mttrMinDays === null || h.mttrMaxDays === null
          ? t('insights.kpi.noClosed')
          : t('insights.kpi.mttrSub', { min: h.mttrMinDays, max: h.mttrMaxDays }),
      delta: deltaPct(h.mttrDays, p?.mttrDays ?? null),
      direction: 'down-good',
      tone: 'default',
    },
    {
      id: 'compliance',
      value: orDash(h.compliancePct, dec),
      unit: h.compliancePct === null ? undefined : '%',
      sub:
        h.closed === 0
          ? t('insights.kpi.noClosed')
          : t('insights.kpi.complianceSub', { onTime: h.onTime, late: h.late }),
      delta: deltaPct(h.compliancePct, p?.compliancePct ?? null),
      direction: 'up-good',
      tone: 'default',
    },
    {
      id: 'breach',
      value: formatCount(h.breachedOpen, locale.value),
      sub: t('insights.kpi.breachSub'),
      delta: deltaPct(h.breachedOpen, p?.breachedOpen ?? null),
      direction: 'down-good',
      tone: h.breachedOpen > 0 ? 'danger' : 'default',
    },
    {
      id: 'closure',
      value: orDash(h.closurePct, dec),
      unit: h.closurePct === null ? undefined : '%',
      sub:
        h.avgAgingDays === null
          ? t('insights.kpi.noAging')
          : t('insights.kpi.closureSub', { days: dec(h.avgAgingDays) }),
      delta: deltaPct(h.closurePct, p?.closurePct ?? null),
      direction: 'up-good',
      tone: 'default',
    },
  ]
})

function deltaClass(tile: Tile): string {
  if (tile.delta === null || tile.delta === 0 || tile.direction === 'neutral') {
    return 'text-surface-600'
  }
  const good = tile.direction === 'up-good' ? tile.delta > 0 : tile.delta < 0
  return good ? 'text-[var(--chart-success-text)]' : 'text-[var(--chart-error-text)]'
}

function deltaText(delta: number): string {
  const sign = delta > 0 ? '▲ +' : delta < 0 ? '▼ ' : '● '
  return `${sign}${formatDecimal(delta, locale.value, { maximumFractionDigits: 1 })}%`
}
</script>

<template>
  <div
    class="grid grid-cols-2 gap-3 lg:grid-cols-5"
    data-testid="kpi-strip"
    :aria-busy="current.isLoading.value"
  >
    <template v-if="current.isLoading.value && tiles.length === 0">
      <div v-for="n in 5" :key="n" class="h-28 animate-pulse rounded-xl bg-surface-200"></div>
    </template>
    <p
      v-else-if="current.isError.value"
      class="col-span-full text-sm text-[var(--chart-error-text)]"
      role="alert"
    >
      {{ t('insights.state.error') }}
    </p>
    <article
      v-for="tile in tiles"
      :key="tile.id"
      class="flex flex-col gap-1 rounded-xl border bg-surface-0 p-4 shadow-sm"
      :class="tile.tone === 'danger' ? 'border-[var(--chart-error)]' : 'border-surface-200'"
      :data-testid="`kpi-${tile.id}`"
    >
      <h3 class="text-xs font-medium uppercase tracking-wide text-surface-600">
        {{ t(`insights.kpi.${tile.id}`) }}
      </h3>
      <p class="flex items-baseline gap-1">
        <span
          class="text-3xl font-semibold tabular-nums text-surface-900"
          data-testid="kpi-value"
          >{{ tile.value }}</span
        >
        <span v-if="tile.unit" class="text-sm text-surface-600">{{ tile.unit }}</span>
      </p>
      <p class="text-xs text-surface-600">{{ tile.sub }}</p>
      <p
        v-if="tile.delta !== null"
        class="text-xs font-medium tabular-nums"
        :class="deltaClass(tile)"
        data-testid="kpi-delta"
      >
        {{ deltaText(tile.delta) }}
        <span class="font-normal text-surface-600">{{ t('insights.kpi.vsPrev') }}</span>
      </p>
    </article>
  </div>
</template>
