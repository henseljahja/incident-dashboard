<script setup lang="ts">
import type { ChartConfiguration } from 'chart.js'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchForecast, type Forecast } from '@/apis/insights'
import BaseChart from '@/components/charts/BaseChart.vue'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InsightPanel from '@/components/insights/InsightPanel.vue'
import ScopeSwitch from '@/components/insights/ScopeSwitch.vue'
import { useInsight } from '@/composables/useInsight'
import { useInsightPage } from '@/composables/useInsightPage'
import AppLayout from '@/layout/AppLayout.vue'
import { formatCount, formatDecimal } from '@/utils/number'
import { orDash } from '@/utils/priority'

/**
 * Forecast & predictive insights: the next 7/14/30 days of ticket volume from a weekday-seasonal
 * level + linear-trend model, with a 95% band, a backtested error (MAPE) and the model's own
 * parameters on screen. The server ignores the date window — a forecast needs the last 180 days
 * whatever period another page is showing — but honours scope and every dimension filter.
 */
useInsightPage(null, null)
const { t, locale } = useI18n()

const HORIZONS = [7, 14, 30] as const
const horizon = ref<(typeof HORIZONS)[number]>(7)
const query = useInsight<Forecast>('forecast', (p) => fetchForecast(p, horizon.value), horizon)
const f = computed(() => query.data.value ?? null)
const dec = (v: number) => formatDecimal(v, locale.value)
const dec2 = (v: number) => formatDecimal(v, locale.value, { maximumFractionDigits: 2 })

/** The last 90 days of history are plotted; the model itself sees all 180. */
const PLOT_DAYS = 90

const config = computed<ChartConfiguration | null>(() => {
  if (!f.value) return null
  const history = f.value.history.slice(-PLOT_DAYS)
  const days = f.value.days
  const labels = [...history.map((h) => h.date), ...days.map((d) => d.date)]
  const pad = (n: number) => Array<number | null>(n).fill(null)
  const last = history[history.length - 1]?.actual ?? null
  return {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: t('insights.forecast.lower'),
          data: [...pad(history.length), ...days.map((d) => d.lower)],
          borderColor: 'transparent',
          backgroundColor: 'transparent',
          pointRadius: 0,
        },
        {
          label: t('insights.forecast.band'),
          data: [...pad(history.length), ...days.map((d) => d.upper)],
          borderColor: 'transparent',
          backgroundColor: 'rgba(235,104,52,0.18)',
          pointRadius: 0,
          fill: '-1',
        },
        {
          label: t('insights.forecast.actual'),
          data: [...history.map((h) => h.actual), ...pad(days.length)],
          borderColor: '#2a78d6',
          backgroundColor: 'rgba(42,120,214,0.12)',
          fill: true,
          borderWidth: 2,
          pointRadius: 0,
          tension: 0.25,
        },
        {
          label: t('insights.forecast.predicted'),
          data: [...pad(history.length - 1), last, ...days.map((d) => d.predicted)],
          borderColor: '#eb6834',
          borderDash: [6, 4],
          borderWidth: 2,
          pointRadius: 3,
          tension: 0.25,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      locale: locale.value,
      interaction: { mode: 'index', intersect: false },
      scales: {
        x: { ticks: { maxTicksLimit: 12, maxRotation: 0 }, grid: { display: false } },
        y: { beginAtZero: true, ticks: { precision: 0 } },
      },
      plugins: {
        legend: {
          position: 'bottom',
          labels: {
            usePointStyle: true,
            boxWidth: 8,
            filter: (item) => item.text !== t('insights.forecast.lower'),
          },
        },
      },
    },
  }
})

const seasonConfig = computed<ChartConfiguration | null>(() =>
  !f.value
    ? null
    : {
        type: 'bar',
        data: {
          labels: f.value.weekdayFactors.map((w) => w.weekday),
          datasets: [
            {
              label: t('insights.forecast.factor'),
              data: f.value.weekdayFactors.map((w) => w.factor),
              // Busy days (>1x) in the series blue, quiet days muted: the colour restates the
              // number on the axis, it does not replace it.
              backgroundColor: f.value.weekdayFactors.map((w) =>
                w.factor > 1 ? '#2a78d6' : '#b5b5b0',
              ),
              maxBarThickness: 40,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          locale: locale.value,
          scales: { y: { beginAtZero: true }, x: { grid: { display: false } } },
          plugins: { legend: { display: false } },
        },
      },
)

const accuracyTone = computed(() =>
  f.value?.accuracy === 'GOOD'
    ? 'text-[var(--chart-success-text)]'
    : f.value?.accuracy === 'POOR'
      ? 'text-[var(--chart-error-text)]'
      : 'text-surface-800',
)
const RISK_STYLE = {
  LOW: 'bg-[#e6f4ec] text-[#0f6b45]',
  MEDIUM: 'bg-[#fdf3d6] text-[#7a5200]',
  HIGH: 'bg-[#fde8e8] text-[#9b1c1c]',
} as const
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>

    <div class="flex flex-col gap-5" data-testid="forecast-view">
      <div class="flex flex-wrap items-end justify-between gap-3">
        <ScopeSwitch />
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-700" for="forecast-horizon">{{
            t('insights.forecast.horizon')
          }}</label>
          <select
            id="forecast-horizon"
            v-model.number="horizon"
            class="min-h-10 rounded-md border border-surface-300 bg-surface-0 px-2"
            data-testid="forecast-horizon"
          >
            <option v-for="h in HORIZONS" :key="h" :value="h">
              {{ t('insights.forecast.days', { n: h }) }}
            </option>
          </select>
        </div>
      </div>

      <p
        class="rounded-lg border-l-4 border-primary bg-surface-50 p-3 text-sm text-surface-700"
        data-testid="forecast-note"
      >
        {{ t('insights.forecast.note') }}
      </p>

      <p v-if="query.isError.value" class="text-sm text-[var(--chart-error-text)]" role="alert">
        {{ t('insights.state.error') }}
      </p>

      <template v-if="f">
        <div class="grid grid-cols-2 gap-3 lg:grid-cols-6" data-testid="forecast-tiles">
          <article class="rounded-xl border border-surface-200 bg-surface-0 p-4">
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.forecast.next7') }}</h3>
            <p class="text-3xl font-semibold tabular-nums" data-testid="forecast-next">
              {{ dec(f.nextTotal) }}
            </p>
            <p class="text-xs text-surface-600">
              {{ t('insights.forecast.vsCurrent', { n: dec(f.currentTotal) }) }}
            </p>
          </article>
          <article class="rounded-xl border border-surface-200 bg-surface-0 p-4">
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.forecast.change') }}</h3>
            <p class="text-3xl font-semibold tabular-nums" data-testid="forecast-change">
              {{ f.changePct === null ? '—' : `${f.changePct > 0 ? '+' : ''}${dec(f.changePct)}%` }}
            </p>
            <p class="text-xs text-surface-600">
              {{
                f.changePct === null
                  ? t('insights.forecast.noBaseline')
                  : f.changePct > 0
                    ? t('insights.forecast.rising')
                    : f.changePct < 0
                      ? t('insights.forecast.falling')
                      : t('insights.forecast.flat')
              }}
            </p>
          </article>
          <article class="rounded-xl border border-surface-200 bg-surface-0 p-4">
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.forecast.avgDay') }}</h3>
            <p class="text-3xl font-semibold tabular-nums">{{ dec2(f.avg7) }}</p>
            <p class="text-xs text-surface-600">
              {{ t('insights.forecast.avgSub', { d14: dec2(f.avg14), d30: dec2(f.avg30) }) }}
            </p>
          </article>
          <article class="rounded-xl border border-surface-200 bg-surface-0 p-4">
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.forecast.trend') }}</h3>
            <p class="text-3xl font-semibold tabular-nums">
              {{ f.trendPerDay > 0 ? '+' : ''
              }}{{ formatDecimal(f.trendPerDay, locale, { maximumFractionDigits: 3 })
              }}<span class="text-sm font-normal text-surface-600">/d</span>
            </p>
            <p class="text-xs text-surface-600">{{ t('insights.forecast.trendSub') }}</p>
          </article>
          <article class="rounded-xl border border-surface-200 bg-surface-0 p-4">
            <h3 class="text-xs uppercase text-surface-600">
              {{ t('insights.forecast.accuracy') }}
            </h3>
            <p class="text-3xl font-semibold" :class="accuracyTone" data-testid="forecast-accuracy">
              {{ t(`insights.forecast.acc.${f.accuracy}`) }}
            </p>
            <p class="text-xs text-surface-600">
              {{
                f.mapePct === null ? t('insights.forecast.noBacktest') : `MAPE ${dec(f.mapePct)}%`
              }}
            </p>
          </article>
          <article class="rounded-xl border border-surface-200 bg-surface-0 p-4">
            <h3 class="text-xs uppercase text-surface-600">{{ t('insights.forecast.busiest') }}</h3>
            <p class="text-3xl font-semibold">{{ f.busiestDay }}</p>
            <p class="text-xs text-surface-600">
              {{ t('insights.forecast.quietest', { day: f.quietestDay }) }}
            </p>
          </article>
        </div>

        <InsightPanel :title="t('insights.forecast.recommendations')">
          <ul class="flex flex-col gap-1 text-sm" data-testid="forecast-recs">
            <li v-for="r in f.recommendations" :key="r" :data-rec="r">
              ✓ {{ t(`insights.forecast.rec.${r}`) }}
            </li>
          </ul>
        </InsightPanel>

        <InsightPanel
          :title="t('insights.forecast.chartTitle')"
          :subtitle="t('insights.forecast.chartSub')"
        >
          <div class="h-72">
            <BaseChart :config="config" :label="t('insights.forecast.chartTitle')" />
          </div>
        </InsightPanel>

        <InsightPanel :title="t('insights.forecast.detail')">
          <div class="overflow-x-auto">
            <table class="insight-table w-full text-sm" data-testid="forecast-table">
              <thead class="text-left text-xs uppercase text-surface-600">
                <tr>
                  <th scope="col" class="py-1">{{ t('insights.forecast.date') }}</th>
                  <th scope="col">{{ t('insights.forecast.day') }}</th>
                  <th scope="col" class="text-right">{{ t('insights.forecast.predicted') }}</th>
                  <th scope="col" class="text-right">{{ t('insights.forecast.range') }}</th>
                  <th scope="col" class="text-right">{{ t('insights.forecast.seasonal') }}</th>
                  <th scope="col">{{ t('insights.forecast.risk') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="d in f.days"
                  :key="d.date"
                  class="border-t border-surface-100"
                  data-testid="forecast-row"
                  :data-date="d.date"
                >
                  <th scope="row" class="py-1.5 text-left font-medium">{{ d.date }}</th>
                  <td>{{ d.weekday }}</td>
                  <td class="text-right font-semibold tabular-nums">{{ dec(d.predicted) }}</td>
                  <td class="text-right tabular-nums text-surface-600">
                    {{ dec(d.lower) }} – {{ dec(d.upper) }}
                  </td>
                  <td class="text-right tabular-nums">{{ dec2(d.seasonalFactor) }}×</td>
                  <td>
                    <span
                      class="rounded-full px-2 py-0.5 text-xs font-medium"
                      :class="RISK_STYLE[d.risk]"
                      >{{ t(`insights.forecast.riskLevel.${d.risk}`) }}</span
                    >
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </InsightPanel>

        <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <InsightPanel :title="t('insights.forecast.quality')">
            <dl class="grid grid-cols-2 gap-y-1.5 text-sm" data-testid="forecast-quality">
              <dt class="text-surface-600">{{ t('insights.forecast.baseLevel') }}</dt>
              <dd class="text-right tabular-nums">{{ dec2(f.baseLevel) }}</dd>
              <dt class="text-surface-600">{{ t('insights.forecast.trendPerDay') }}</dt>
              <dd class="text-right tabular-nums">
                {{ formatDecimal(f.trendPerDay, locale, { maximumFractionDigits: 3 }) }}
              </dd>
              <dt class="text-surface-600">{{ t('insights.forecast.stdDev') }}</dt>
              <dd class="text-right tabular-nums">{{ dec2(f.stdDev) }}</dd>
              <dt class="text-surface-600">{{ t('insights.forecast.history') }}</dt>
              <dd class="text-right tabular-nums">
                {{ formatCount(f.historyPoints, locale) }} {{ t('insights.unit.days') }}
              </dd>
              <dt class="text-surface-600">MAPE</dt>
              <dd class="text-right tabular-nums">{{ orDash(f.mapePct, (v) => `${dec(v)}%`) }}</dd>
            </dl>
            <p class="text-xs text-surface-600">{{ t('insights.forecast.mapeNote') }}</p>
          </InsightPanel>
          <InsightPanel
            :title="t('insights.forecast.seasonality')"
            :subtitle="t('insights.forecast.seasonalitySub')"
          >
            <div class="h-48">
              <BaseChart :config="seasonConfig" :label="t('insights.forecast.seasonality')" />
            </div>
          </InsightPanel>
        </div>
      </template>
      <div
        v-else-if="query.isLoading.value"
        class="h-48 animate-pulse rounded-xl bg-surface-200"
      ></div>
    </div>
  </AppLayout>
</template>
