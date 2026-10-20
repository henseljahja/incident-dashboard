<script setup lang="ts">
import type { ChartConfiguration } from 'chart.js'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchCategory, type Category } from '@/apis/insights'
import BaseChart from '@/components/charts/BaseChart.vue'
import { useInsight } from '@/composables/useInsight'
import { useFiltersStore } from '@/stores/filters'
import { formatCount, formatDecimal } from '@/utils/number'
import { orDash } from '@/utils/priority'

import InsightPanel from './InsightPanel.vue'
import PriorityCells from './PriorityCells.vue'

/**
 * Where tickets come from: External vs Internal (a table with the priority mix, closed/open and
 * mean aging, and a pie of the same totals) beside the ten institusi raising the most tickets.
 * Clicking an institusi narrows the incident list to it.
 */
const { t, locale } = useI18n()
const filters = useFiltersStore()
const query = useInsight<Category>('category', fetchCategory)
const sources = computed(() => query.data.value?.sources ?? [])
const institusi = computed(() => query.data.value?.institusi ?? [])
const brands = computed(() => query.data.value?.brands ?? [])
const brandMax = computed(() => Math.max(1, ...brands.value.map((b) => b.count)))
const max = computed(() => Math.max(1, ...institusi.value.map((i) => i.count)))
const dec = (v: number) => formatDecimal(v, locale.value)

// Fixed hue per source, by entity (not by rank): External = blue, Internal = violet.
const SOURCE_COLOR: Record<string, string> = {
  external: '#2a78d6',
  internal: '#6250d6',
}
const FALLBACK = ['#1baf7a', '#9a9a96', '#e87ba4']
function colorFor(source: string, index: number): string {
  return SOURCE_COLOR[source.toLowerCase()] ?? FALLBACK[index % FALLBACK.length]!
}

const config = computed<ChartConfiguration | null>(() =>
  sources.value.length === 0
    ? null
    : {
        type: 'doughnut',
        data: {
          labels: sources.value.map((s) => s.source),
          datasets: [
            {
              data: sources.value.map((s) => s.total),
              backgroundColor: sources.value.map((s, i) => colorFor(s.source, i)),
              borderColor: '#ffffff',
              borderWidth: 2,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          cutout: '55%',
          locale: locale.value,
          plugins: { legend: { display: false } },
        },
      },
)

function filterInstitusi(label: string) {
  if (label === 'Unclassified') return
  filters.institusi = [label]
  filters.page = 0
}
</script>

<template>
  <div class="grid grid-cols-1 gap-4 xl:grid-cols-5">
    <InsightPanel
      class="xl:col-span-3"
      :title="t('insights.source.title')"
      :loading="query.isLoading.value"
      :error="query.isError.value"
      :empty="sources.length === 0"
      @retry="query.refetch()"
    >
      <div class="flex flex-wrap items-center gap-4">
        <div class="h-44 w-44 shrink-0">
          <BaseChart :config="config" :label="t('insights.source.title')" />
        </div>
        <div class="min-w-0 flex-1 overflow-x-auto">
          <table class="insight-table w-full text-sm" data-testid="source-table">
            <thead class="text-left text-xs uppercase text-surface-600">
              <tr>
                <th scope="col" class="py-1">{{ t('insights.col.source') }}</th>
                <th scope="col" class="text-right">{{ t('insights.col.total') }}</th>
                <th scope="col">{{ t('insights.col.priority') }}</th>
                <th scope="col" class="text-right">{{ t('insights.col.closed') }}</th>
                <th scope="col" class="text-right">{{ t('insights.col.open') }}</th>
                <th scope="col" class="text-right">{{ t('insights.col.avgAging') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(s, i) in sources"
                :key="s.source"
                class="border-t border-surface-100"
                data-testid="source-row"
                :data-source="s.source"
              >
                <th scope="row" class="py-1.5 text-left font-medium">
                  <span
                    class="mr-1.5 inline-block h-3 w-3 rounded-sm align-middle"
                    :style="{ backgroundColor: colorFor(s.source, i) }"
                    aria-hidden="true"
                  ></span
                  >{{ s.source }}
                </th>
                <td class="text-right font-semibold tabular-nums">
                  {{ formatCount(s.total, locale) }}
                </td>
                <td><PriorityCells :counts="s.byPriority" /></td>
                <td class="text-right tabular-nums">{{ formatCount(s.closed, locale) }}</td>
                <td class="text-right tabular-nums">{{ formatCount(s.open, locale) }}</td>
                <td class="text-right tabular-nums">{{ orDash(s.avgAgingDays, dec) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </InsightPanel>

    <InsightPanel
      class="xl:col-span-2"
      :title="t('insights.institusi.title')"
      :loading="query.isLoading.value"
      :error="query.isError.value"
      :empty="institusi.length === 0"
      @retry="query.refetch()"
    >
      <ol class="flex flex-col gap-2" data-testid="institusi-list">
        <li
          v-for="row in institusi"
          :key="row.label"
          data-testid="institusi-row"
          :data-count="row.count"
        >
          <button
            type="button"
            class="flex w-full min-h-9 flex-col gap-1 text-left"
            @click="filterInstitusi(row.label)"
          >
            <span class="flex items-baseline justify-between gap-2 text-sm">
              <span class="text-surface-800">{{ row.label }}</span>
              <span class="tabular-nums">
                <span class="font-semibold">{{ formatCount(row.count, locale) }}</span>
                <span class="ml-2 text-xs text-surface-600">{{
                  orDash(row.sharePct, (v) => `${dec(v)}%`)
                }}</span>
              </span>
            </span>
            <span class="h-2 rounded bg-surface-100" aria-hidden="true">
              <span
                class="block h-2 rounded bg-primary"
                :style="{ width: `${(row.count * 100) / max}%` }"
              ></span>
            </span>
          </button>
        </li>
      </ol>
    </InsightPanel>

    <InsightPanel
      class="xl:col-span-5"
      :title="t('insights.brands.title')"
      :loading="query.isLoading.value"
      :error="query.isError.value"
      :empty="brands.length === 0"
      @retry="query.refetch()"
    >
      <ol class="grid grid-cols-1 gap-2 md:grid-cols-2" data-testid="brand-list">
        <li v-for="row in brands" :key="row.label" data-testid="brand-row" :data-count="row.count">
          <span class="flex items-baseline justify-between gap-2 text-sm">
            <span class="text-surface-800">{{ row.label }}</span>
            <span class="tabular-nums">
              <span class="font-semibold">{{ formatCount(row.count, locale) }}</span>
              <span class="ml-2 text-xs text-surface-600">{{
                orDash(row.sharePct, (v) => `${dec(v)}%`)
              }}</span>
            </span>
          </span>
          <span class="mt-1 block h-2 rounded bg-surface-100" aria-hidden="true">
            <span
              class="block h-2 rounded bg-primary"
              :style="{ width: `${(row.count * 100) / brandMax}%` }"
            ></span>
          </span>
        </li>
      </ol>
    </InsightPanel>
  </div>
</template>
