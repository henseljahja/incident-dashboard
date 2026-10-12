<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import type { DefinitionTerm } from '@/apis/definitions'
import {
  fetchFreshness,
  fetchWallboard,
  type FreshnessDto,
  type WallboardResponse,
} from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import ScopeCounts from '@/components/kpi/ScopeCounts.vue'
import { ROTATION_INTERVALS_MS, useRotation } from '@/composables/useRotation'
import WallboardLayout from '@/layout/WallboardLayout.vue'
import { useStaleReason } from '@/utils/freshness'
import { formatCount, formatDecimal, formatPercent } from '@/utils/number'

/**
 * Unattended TV display. Two screens that rotate: "Now" (where things stand) and "Trend" (how the
 * year is going). Deliberately does NOT use `useFiltersStore` — there is no filter bar and no
 * logged-in operator to hold filter state.
 *
 * Everything comes from ONE endpoint, `GET /api/metrics/wallboard`, whose scope is fixed to
 * Incident on the server: the `incident.wallboard` role has no row-level access, and "Incident
 * only" must not be something a client can change. The group tiles (`ScopeCounts`) take that same
 * payload's `groupCounts` and are plain boxes, not links — this role cannot open the ops menus.
 *
 * Deliberately does NOT reuse `KpiRow`/`KpiCard` (filter-store-coupled, small "click to filter"
 * cards — dead affordances here) or the matrix (its source is ops-only server-side).
 */

const REFRESH_INTERVAL_MS = 60_000
const SCREENS = ['now', 'trend'] as const

const { t, locale } = useI18n()
const staleReasonLabel = useStaleReason()

/**
 * `?tv=1` — the wall-mounted display. The ⓘ buttons and the rotation controls are for a person at
 * a laptop; on a TV nobody can press them, and small circles in the corners of the tiles are
 * clutter viewed from across a room. Read once from the address the screen was opened with: a TV
 * does not navigate, and this view is also mounted without a router in its specs.
 */
const tvMode = new URLSearchParams(globalThis.location?.search ?? '').get('tv') === '1'

const rotation = useRotation(SCREENS.length)
const screen = computed(() => SCREENS[rotation.index.value]!)

const data = ref<WallboardResponse | null>(null)
const freshness = ref<FreshnessDto | null>(null)
let timer: ReturnType<typeof setInterval> | undefined

/**
 * True once a poll has failed, cleared by the next success.
 *
 * An unattended display must never present stale figures as current. If the refresh fails, the
 * last successful payload is still on screen and `freshness.stale` still reads false — it was
 * computed from that older, then-healthy response — so without this flag the wallboard shows
 * hour-old numbers with no warning at all. Nobody is standing there to notice.
 */
const refreshFailed = ref(false)

async function refresh() {
  try {
    const [wallboard, freshnessResult] = await Promise.all([fetchWallboard(), fetchFreshness()])
    data.value = wallboard
    freshness.value = freshnessResult
    refreshFailed.value = false
  } catch {
    // Deliberately keep the last good values on screen — a blank wallboard is worse than a
    // clearly-labelled stale one. The banner below carries the warning.
    refreshFailed.value = true
  }
}

onMounted(() => {
  void refresh()
  timer = setInterval(() => void refresh(), REFRESH_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

const loading = computed(() => data.value === null && !refreshFailed.value)

/** A figure, or the dimmed dot: `—` at this size reads as a broken tile, not as "no number yet". */
const count = (value: number | null | undefined) =>
  value === null || value === undefined ? null : formatCount(value, locale.value)

interface Headline {
  key: string
  term?: DefinitionTerm
  label: string
  value: string | null
  detail?: string
}

/** The urgent pair, sized as the headline: a wallboard answers "how bad is it" first. */
const urgent = computed<Headline[]>(() => [
  {
    key: 'breached',
    term: 'breached',
    label: t('wallboard.breached'),
    value: count(data.value?.breached),
  },
  {
    key: 'at-risk',
    term: 'atRisk',
    label: t('wallboard.atRisk'),
    value: count(data.value?.atRisk),
  },
])

const compliance = computed(() => data.value?.slaCompliance ?? null)

/** Context: the whole year, how much is still open, and how well closed tickets kept their SLA. */
const context = computed<Headline[]>(() => [
  { key: 'total', label: t('wallboard.total'), value: count(data.value?.total) },
  { key: 'open', term: 'open', label: t('wallboard.open'), value: count(data.value?.open) },
  {
    key: 'sla-compliance',
    term: 'slaCompliance',
    label: t('wallboard.slaCompliance'),
    value:
      compliance.value?.percent === null || compliance.value === null
        ? null
        : formatPercent(compliance.value.percent, locale.value),
    detail: compliance.value
      ? compliance.value.percent === null
        ? t('wallboard.slaComplianceNone')
        : t('wallboard.slaComplianceDetail', {
            met: formatCount(compliance.value.met, locale.value),
            closed: formatCount(compliance.value.closedInPeriod, locale.value),
          })
      : undefined,
  },
])

/** The trend screen's three figures. */
const yearTiles = computed<Headline[]>(() => [
  {
    key: 'resolved',
    term: 'closed',
    label: t('wallboard.resolved'),
    value: count(data.value?.closed),
  },
  { key: 'open', term: 'open', label: t('wallboard.open'), value: count(data.value?.open) },
  {
    key: 'avg-resolution',
    term: 'avgResolution',
    label: t('wallboard.avgResolution'),
    value:
      data.value?.avgResolutionDays === null || data.value === null
        ? null
        : t('wallboard.avgResolutionValue', {
            value: formatDecimal(data.value.avgResolutionDays, locale.value),
          }),
  },
])

/** A bar's width as a share of the largest value in its list; never zero-width for a real count. */
function share(value: number, max: number): string {
  if (max <= 0 || value <= 0) return '0%'
  return `${Math.max(4, Math.round((value / max) * 100))}%`
}

const stageMax = computed(() => Math.max(0, ...(data.value?.byStage ?? []).map((r) => r.count)))
const priorityMax = computed(() =>
  Math.max(0, ...(data.value?.byPriority ?? []).map((r) => r.count)),
)
const categoryMax = computed(() =>
  Math.max(0, ...(data.value?.topCategories ?? []).map((r) => r.count)),
)
const monthlyMax = computed(() =>
  Math.max(0, ...(data.value?.monthly ?? []).flatMap((m) => [m.created, m.closed])),
)

/** `2026-03` -> "Mar" / "Mar" in the active locale. A label, never fed back into arithmetic. */
function monthLabel(month: string): string {
  const [year, number] = month.split('-').map(Number)
  if (!year || !number) return month
  return new Intl.DateTimeFormat(locale.value, { month: 'short' }).format(
    new Date(year, number - 1, 1),
  )
}

const screenLabel = computed(() =>
  screen.value === 'now' ? t('wallboard.screenNow') : t('wallboard.screenTrend'),
)

/** The visible countdown, ticking every second. */
const statusText = computed(() =>
  rotation.paused.value
    ? t('wallboard.statusPaused', { current: rotation.index.value + 1, total: SCREENS.length })
    : t('wallboard.status', {
        current: rotation.index.value + 1,
        total: SCREENS.length,
        seconds: rotation.remainingSeconds.value,
      }),
)

/** What the live region says: the same sentence, but only when the screen or pause state changes. */
const liveText = computed(() => {
  const a = rotation.announcement.value
  if (!a) return ''
  return a.paused
    ? t('wallboard.statusPaused', { current: a.current, total: a.total })
    : t('wallboard.status', { current: a.current, total: a.total, seconds: a.seconds })
})

function onIntervalChange(event: Event) {
  rotation.setIntervalMs(Number((event.target as HTMLSelectElement).value))
}

const labelClass = 'text-white/70'
</script>

<template>
  <WallboardLayout>
    <!--
      The wallboard's `h1`, and it is `sr-only` on purpose.

      A visible title is the wrong fix for an empty outline: the design's job is to be read from
      across a room, and a header band would spend vertical space the numbers need. Structurally
      real, visually absent.

      NOT wrapped in `<header>`: the spec pins `header`/`nav` as absent, because their presence
      is what would make this read as an operator console rather than a display.
    -->
    <h1 class="sr-only">{{ t('wallboard.heading') }}</h1>

    <div
      v-if="freshness?.stale || refreshFailed"
      data-testid="wallboard-stale-warning"
      role="alert"
      class="flex flex-col items-center justify-center gap-2 bg-[var(--sla-breach)] px-6 py-8 text-center"
    >
      <span class="text-4xl font-extrabold uppercase tracking-widest sm:text-6xl">{{
        t('wallboard.staleTitle')
      }}</span>
      <span v-if="refreshFailed" class="text-xl font-normal sm:text-2xl">
        {{ t('wallboard.unreachable') }}
      </span>
      <span v-else class="text-xl font-normal sm:text-2xl">
        {{ t('wallboard.stale')
        }}<template v-if="freshness?.staleReason">
          — {{ staleReasonLabel(freshness.staleReason) }}</template
        >.
      </span>
    </div>

    <main
      class="flex flex-1 flex-col gap-6 p-4 sm:p-8"
      data-testid="wallboard-tiles"
      :data-screen="screen"
    >
      <div class="flex flex-wrap items-baseline gap-3">
        <h2
          class="text-xl font-semibold uppercase tracking-widest text-white/70"
          data-testid="wallboard-screen-title"
        >
          {{ screenLabel }}
        </h2>
        <span v-if="data" class="text-sm text-white/60">{{
          t('wallboard.year', { year: data.year })
        }}</span>
        <span v-if="loading" class="text-sm text-white/60" data-testid="wallboard-loading">{{
          t('wallboard.loading')
        }}</span>
      </div>

      <!-- Screen 1: where things stand right now. -->
      <template v-if="screen === 'now'">
        <ScopeCounts
          dark
          :links="false"
          :counts="data?.groupCounts ?? null"
          :hide-hint="tvMode"
          data-testid="wallboard-group-counts"
        />

        <div class="grid grid-cols-1 gap-6 sm:grid-cols-2">
          <div
            v-for="tile in urgent"
            :key="tile.key"
            class="@container relative flex min-w-0 flex-col items-center justify-center rounded-lg border p-4 sm:p-6"
            :class="
              tile.key === 'breached'
                ? 'border-[var(--sla-breach)]/40 bg-[var(--sla-breach)]/15'
                : 'border-[var(--sla-risk)]/40 bg-[var(--sla-risk)]/15'
            "
            :data-testid="`wallboard-${tile.key}`"
          >
            <span class="flex flex-wrap items-center justify-center gap-x-2">
              <span
                class="text-center text-2xl tracking-widest uppercase sm:text-3xl"
                :class="
                  tile.key === 'breached'
                    ? 'text-[color-mix(in_srgb,var(--sla-breach)_45%,white)]'
                    : 'text-[color-mix(in_srgb,var(--sla-risk)_45%,white)]'
                "
                >{{ tile.label }}</span
              >
              <span v-if="!tvMode && tile.term" class="text-white/70"
                ><InfoHint :term="tile.term" /></span
            ></span>
            <span
              v-if="tile.value !== null"
              class="max-w-full text-[clamp(3rem,min(16vh,22cqw),12rem)] font-bold leading-none tabular-nums"
              >{{ tile.value }}</span
            >
            <span
              v-else
              class="max-w-full text-[clamp(3rem,min(16vh,22cqw),12rem)] font-bold leading-none text-white/25"
              :aria-label="t('common.noDataYet')"
              >·</span
            >
          </div>
        </div>

        <div class="grid grid-cols-1 gap-6 sm:grid-cols-3">
          <div
            v-for="tile in context"
            :key="tile.key"
            class="@container relative flex min-w-0 flex-col items-center justify-center gap-1 rounded-lg border p-4"
            :class="
              tile.key === 'open'
                ? 'border-primary-400/30 bg-primary-400/10'
                : 'border-white/15 bg-white/5'
            "
            :data-testid="`wallboard-${tile.key}`"
          >
            <span class="flex flex-wrap items-center justify-center gap-x-2">
              <span
                class="text-center text-sm tracking-wide uppercase lg:text-lg lg:tracking-wider"
                :class="tile.key === 'open' ? 'text-primary-300' : 'text-white/60'"
                >{{ tile.label }}</span
              >
              <span v-if="!tvMode && tile.term" class="text-white/70"
                ><InfoHint :term="tile.term" /></span
            ></span>
            <span
              v-if="tile.value !== null"
              class="max-w-full text-[clamp(2rem,min(9vh,22cqw),6rem)] font-bold leading-none tabular-nums"
              >{{ tile.value }}</span
            >
            <span
              v-else
              class="max-w-full text-[clamp(2rem,min(9vh,22cqw),6rem)] font-bold leading-none text-white/25"
              :aria-label="t('common.noDataYet')"
              >·</span
            >
            <span v-if="tile.detail" class="text-center text-sm text-white/70">{{
              tile.detail
            }}</span>
          </div>
        </div>

        <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <section
            class="flex flex-col gap-3 rounded-lg border border-white/15 bg-white/5 p-4"
            data-testid="wallboard-by-stage"
          >
            <h3 class="text-lg font-semibold" :class="labelClass">{{ t('wallboard.byStage') }}</h3>
            <p v-if="!data" class="text-sm text-white/60">{{ t('wallboard.noData') }}</p>
            <ul v-else class="flex flex-col gap-2">
              <li
                v-for="row in data.byStage"
                :key="row.stage"
                class="grid grid-cols-[8rem_1fr_3rem] items-center gap-3 text-base"
                data-testid="wallboard-stage-row"
                :data-stage="row.stage"
                :data-count="row.count"
              >
                <span class="truncate">{{ row.stage }}</span>
                <span class="h-3 rounded bg-white/10" aria-hidden="true">
                  <span
                    class="block h-3 rounded bg-[var(--chart-blue)]"
                    :style="{ width: share(row.count, stageMax) }"
                  />
                </span>
                <span class="text-right tabular-nums">{{ formatCount(row.count, locale) }}</span>
              </li>
            </ul>
          </section>

          <section
            class="flex flex-col gap-3 rounded-lg border border-white/15 bg-white/5 p-4"
            data-testid="wallboard-by-priority"
          >
            <h3 class="text-lg font-semibold" :class="labelClass">
              {{ t('wallboard.byPriority') }}
            </h3>
            <p v-if="!data" class="text-sm text-white/60">{{ t('wallboard.noData') }}</p>
            <ul v-else class="flex flex-col gap-2">
              <li
                v-for="row in data.byPriority"
                :key="row.priority"
                class="grid grid-cols-[8rem_1fr_3rem] items-center gap-3 text-base"
                data-testid="wallboard-priority-row"
                :data-priority="row.priority"
                :data-count="row.count"
              >
                <span class="truncate">{{ row.priority }}</span>
                <span class="h-3 rounded bg-white/10" aria-hidden="true">
                  <span
                    class="block h-3 rounded bg-[var(--chart-purple)]"
                    :style="{ width: share(row.count, priorityMax) }"
                  />
                </span>
                <span class="text-right tabular-nums">{{ formatCount(row.count, locale) }}</span>
              </li>
            </ul>
          </section>
        </div>

        <section
          class="flex flex-col gap-3 rounded-lg border border-white/15 bg-white/5 p-4"
          data-testid="wallboard-critical-high"
        >
          <h3 class="text-lg font-semibold" :class="labelClass">
            {{ t('wallboard.criticalHigh') }}
          </h3>
          <p v-if="!data" class="text-sm text-white/60">{{ t('wallboard.noData') }}</p>
          <p
            v-else-if="data.criticalHighOpen.length === 0"
            class="text-base text-white/70"
            data-testid="wallboard-critical-high-empty"
          >
            {{ t('wallboard.criticalHighEmpty') }}
          </p>
          <ol v-else class="flex flex-col divide-y divide-white/10">
            <li
              v-for="row in data.criticalHighOpen"
              :key="row.noIncident"
              class="flex flex-col gap-1 py-2 sm:flex-row sm:items-center sm:gap-4"
              data-testid="wallboard-critical-row"
              :data-incident="row.noIncident"
            >
              <!-- No subject (TV and exec roles): number, priority and age only. No blank column. -->
              <span
                class="font-semibold tabular-nums"
                :class="{ 'sm:flex-1': row.subject === null }"
                >#{{ row.noIncident }}</span
              >
              <span
                v-if="row.subject !== null"
                class="min-w-0 flex-1 truncate"
                :title="row.subject"
                >{{ row.subject }}</span
              >
              <span v-if="row.subject !== null" class="text-sm text-white/70">{{
                row.status
              }}</span>
              <span class="text-sm font-semibold">{{ row.priority }}</span>
              <span class="text-sm tabular-nums text-white/70">{{
                t('wallboard.criticalHighAge', {
                  elapsed: formatDecimal(row.elapsedDays, locale),
                  sla: formatDecimal(row.slaDays, locale),
                })
              }}</span>
            </li>
          </ol>
        </section>
      </template>

      <!-- Screen 2: how the year is going. -->
      <template v-else>
        <div class="grid grid-cols-1 gap-6 sm:grid-cols-3">
          <div
            v-for="tile in yearTiles"
            :key="tile.key"
            class="@container relative flex min-w-0 flex-col items-center justify-center gap-1 rounded-lg border border-white/15 bg-white/5 p-4 sm:p-6"
            :data-testid="`wallboard-${tile.key}`"
          >
            <span class="flex flex-wrap items-center justify-center gap-x-2"
              ><span
                class="text-center text-sm tracking-wide uppercase text-white/60 lg:text-lg lg:tracking-wider"
                >{{ tile.label }}</span
              ><span v-if="!tvMode && tile.term" class="text-white/70"
                ><InfoHint :term="tile.term" /></span
            ></span>
            <span
              v-if="tile.value !== null"
              class="max-w-full text-center text-[clamp(2rem,min(8vh,20cqw),5rem)] font-bold leading-none tabular-nums"
              >{{ tile.value }}</span
            >
            <span
              v-else
              class="max-w-full text-center text-[clamp(2rem,min(8vh,20cqw),5rem)] font-bold leading-none text-white/25"
              :aria-label="t('common.noDataYet')"
              >·</span
            >
          </div>
        </div>

        <div class="grid grid-cols-1 items-start gap-6 lg:grid-cols-2">
          <section
            class="relative flex flex-col gap-3 rounded-lg border border-white/15 bg-white/5 p-4"
            data-testid="wallboard-top-categories"
          >
            <div class="flex items-center justify-between gap-2">
              <h3 class="text-lg font-semibold" :class="labelClass">
                {{ t('wallboard.topCategories') }}
              </h3>
              <span v-if="!tvMode" class="text-white/70"><InfoHint term="topCategories" /></span>
            </div>
            <p v-if="!data" class="text-sm text-white/60">{{ t('wallboard.noData') }}</p>
            <p v-else-if="data.topCategories.length === 0" class="text-base text-white/70">
              {{ t('wallboard.topCategoriesEmpty') }}
            </p>
            <ul v-else class="flex flex-col gap-2">
              <li
                v-for="row in data.topCategories"
                :key="row.category"
                class="grid grid-cols-[minmax(6rem,12rem)_1fr_3rem] items-center gap-3 text-base"
                data-testid="wallboard-category-row"
                :data-category="row.category"
                :data-count="row.count"
              >
                <span class="truncate" :title="row.category">{{ row.category }}</span>
                <span class="h-3 rounded bg-white/10" aria-hidden="true">
                  <span
                    class="block h-3 rounded bg-[var(--chart-teal)]"
                    :style="{ width: share(row.count, categoryMax) }"
                  />
                </span>
                <span class="text-right tabular-nums">{{ formatCount(row.count, locale) }}</span>
              </li>
            </ul>
          </section>

          <section
            class="flex flex-col gap-3 rounded-lg border border-white/15 bg-white/5 p-4"
            data-testid="wallboard-monthly"
          >
            <div class="flex items-center justify-between gap-2">
              <h3 class="text-lg font-semibold" :class="labelClass">
                {{ t('wallboard.monthly') }}
              </h3>
              <span v-if="!tvMode" class="text-white/70"><InfoHint term="trend" /></span>
            </div>
            <!-- The key is words and swatches, so created vs closed never rides on colour alone. -->
            <p class="flex gap-4 text-sm text-white/70">
              <span class="inline-flex items-center gap-1"
                ><span class="inline-block size-3 rounded bg-[var(--chart-blue)]" />{{
                  t('wallboard.created')
                }}</span
              >
              <span class="inline-flex items-center gap-1"
                ><span class="inline-block size-3 rounded bg-[var(--chart-success)]" />{{
                  t('wallboard.resolved')
                }}</span
              >
            </p>
            <p v-if="!data" class="text-sm text-white/60">{{ t('wallboard.noData') }}</p>
            <ul v-else class="flex flex-col gap-2">
              <li
                v-for="row in data.monthly"
                :key="row.month"
                class="grid grid-cols-[3rem_1fr_6rem] items-center gap-3 text-sm"
                data-testid="wallboard-month-row"
                :data-month="row.month"
                :data-created="row.created"
                :data-closed="row.closed"
              >
                <span class="text-white/70">{{ monthLabel(row.month) }}</span>
                <span class="flex flex-col gap-1" aria-hidden="true">
                  <span
                    class="block h-2 rounded bg-[var(--chart-blue)]"
                    :style="{ width: share(row.created, monthlyMax) }"
                  />
                  <span
                    class="block h-2 rounded bg-[var(--chart-success)]"
                    :style="{ width: share(row.closed, monthlyMax) }"
                  />
                </span>
                <span class="text-right tabular-nums"
                  >{{ formatCount(row.created, locale) }} ·
                  {{ formatCount(row.closed, locale) }}</span
                >
              </li>
            </ul>
          </section>
        </div>
      </template>
    </main>

    <!--
      Rotation status and controls.

      The countdown is the VISIBLE text and ticks every second, so it is hidden from assistive
      technology; the live region beside it is `sr-only`, polite, and only changes when the screen
      or the pause state changes — a screen reader announces "Screen 2 of 2" once per rotation,
      not once per second. The progress bar does not animate under `prefers-reduced-motion`.
    -->
    <div
      class="flex flex-col gap-2 border-t border-white/10 px-4 py-3 sm:px-8"
      data-testid="wallboard-rotation"
    >
      <div
        class="h-1 w-full overflow-hidden rounded bg-white/10"
        role="presentation"
        data-testid="wallboard-rotation-progress"
      >
        <div
          class="h-1 bg-primary-400 transition-[width] duration-1000 ease-linear motion-reduce:transition-none"
          :style="{ width: `${Math.round(rotation.progress.value * 100)}%` }"
        />
      </div>
      <div class="flex flex-wrap items-center gap-3">
        <span
          class="text-sm text-white/80"
          aria-hidden="true"
          data-testid="wallboard-rotation-status"
          >{{ statusText }}</span
        >
        <p
          class="sr-only"
          aria-live="polite"
          aria-atomic="true"
          data-testid="wallboard-rotation-live"
        >
          {{ liveText }}
        </p>
        <div
          v-if="!tvMode"
          class="ml-auto flex flex-wrap items-center gap-2"
          role="group"
          :aria-label="t('wallboard.controls')"
          data-testid="wallboard-rotation-controls"
        >
          <label class="text-sm text-white/80" for="wallboard-rotation-interval">{{
            t('wallboard.interval')
          }}</label>
          <select
            id="wallboard-rotation-interval"
            class="min-h-11 rounded border border-white/30 bg-surface-900 px-3 text-sm text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-400"
            data-testid="wallboard-rotation-interval"
            :value="rotation.intervalMs.value"
            @change="onIntervalChange"
          >
            <option v-for="ms in ROTATION_INTERVALS_MS" :key="ms" :value="ms">
              {{ t(`wallboard.intervalOption.${ms}`) }}
            </option>
          </select>
          <Button
            type="button"
            :icon="rotation.paused.value ? 'pi pi-play' : 'pi pi-pause'"
            :label="rotation.paused.value ? t('wallboard.resume') : t('wallboard.pause')"
            severity="secondary"
            size="small"
            class="min-h-11"
            data-testid="wallboard-rotation-toggle"
            :aria-pressed="rotation.paused.value"
            @click="rotation.toggle()"
          />
          <Button
            type="button"
            icon="pi pi-step-forward"
            :label="t('wallboard.next')"
            severity="secondary"
            size="small"
            class="min-h-11"
            data-testid="wallboard-rotation-next"
            @click="rotation.next()"
          />
        </div>
      </div>
    </div>
  </WallboardLayout>
</template>
