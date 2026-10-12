<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchSlaBuckets } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { useFiltersStore } from '@/stores/filters'
import { formatCount } from '@/utils/number'
import { SEVERITY_PALETTE, type SlaBucket as SlaBand } from '@/utils/severity'

const { t, te, locale } = useI18n()
const filters = useFiltersStore()

// Same filters store as the table below — an SLA distribution on a different filter than the
// rows it is supposed to summarise is a correctness bug. The BASE params, without the KPI tiles'
// status/SLA facets: clicking Breached must not redraw this as one solid red bar.
const queryKey = computed(
  () => ['metrics', 'sla-buckets', filters.toBaseFilterParams().toString()] as const,
)

const { data } = useQuery({
  queryKey,
  queryFn: () => fetchSlaBuckets(filters.toBaseFilterParams()),
})

const buckets = computed(() => data.value ?? [])
const maxCount = computed(() => buckets.value.reduce((max, b) => Math.max(max, b.count), 0))
const total = computed(() => buckets.value.reduce((sum, b) => sum + b.count, 0))

/**
 * `SEVERITY_PALETTE` is the one place a band becomes a colour (see `severity.ts`) — reusing it
 * here, rather than inventing a second ramp, is what keeps a "Breached" bar and a "Breached"
 * table cell agreeing on what red means. An unrecognised band name (future backend addition)
 * falls back to the palette's own `Unknown` entry rather than silently picking no colour.
 */
function colourFor(bucket: string): string {
  return SEVERITY_PALETTE[bucket as SlaBand] ?? SEVERITY_PALETTE.Unknown
}

/**
 * Band names are a mart enum, so they are translated client-side (`charts.bucket.*`); a band the
 * catalog does not know is shown as delivered rather than as a raw key path.
 */
const BUCKET_KEYS: Record<string, string> = {
  Breached: 'breached',
  '80-100%': 'p80to100',
  '50-80%': 'p50to80',
  '0-50%': 'p0to50',
  Unknown: 'unknown',
}
function bucketLabel(bucket: string): string {
  const key = BUCKET_KEYS[bucket]
  return key && te(`charts.bucket.${key}`) ? t(`charts.bucket.${key}`) : bucket
}

function widthFor(count: number): string {
  if (maxCount.value === 0) return '0%'
  return `${Math.round((count / maxCount.value) * 100)}%`
}
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="sla-bucket-bars"
  >
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-sm font-semibold text-surface-700">{{ t('charts.slaBuckets.title') }}</h2>
      <InfoHint term="slaBuckets" class="text-surface-600" />
    </div>

    <!--
      Without this the panel rendered as a blank rectangle with "Total: 0" under it whenever a
      filter matched nothing — indistinguishable from a failed load. Matches the table's and
      the heatmap's empty state.
    -->
    <div
      v-if="total === 0"
      class="flex flex-col items-center gap-2 py-8 text-center text-sm text-surface-600"
      data-testid="sla-buckets-empty"
    >
      <i class="pi pi-inbox text-2xl text-surface-300" aria-hidden="true" />
      {{ t('charts.empty') }}
    </div>

    <div v-for="bucket in buckets" :key="bucket.bucket" class="flex items-center gap-2 text-sm">
      <span class="w-28 shrink-0 text-surface-600">{{ bucketLabel(bucket.bucket) }}</span>
      <div class="h-3 flex-1 rounded bg-surface-100">
        <div
          class="h-3 rounded"
          :style="{ width: widthFor(bucket.count), backgroundColor: colourFor(bucket.bucket) }"
        />
      </div>
      <span class="w-10 text-right tabular-nums">{{ formatCount(bucket.count, locale) }}</span>
    </div>
    <div v-if="total > 0" class="text-xs text-surface-600">
      {{ t('common.total', { total: formatCount(total, locale) }) }}
    </div>
  </div>
</template>
