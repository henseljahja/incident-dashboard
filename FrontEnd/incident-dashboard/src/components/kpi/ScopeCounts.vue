<script setup lang="ts">
import { useQuery, VUE_QUERY_CLIENT, type QueryClient } from '@tanstack/vue-query'
import { computed, inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

import { fetchScopeCounts, type ScopeCount } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { queryClient as appQueryClient } from '@/queryClient'
import { SCOPES, type Scope } from '@/stores/filters'
import { formatCount } from '@/utils/number'

/**
 * The three ticket groups side by side: how many are open and how many of them have breached,
 * each tile a link to that group's menu. It answers "where is the pain?" before anyone picks a
 * menu, and on the ops pages it doubles as the way between the three of them.
 *
 * Numbers come from `GET /api/metrics/scope-counts`, which honors only the date window — so a
 * tile never changes because the operator filtered the table underneath it. The wallboard has its
 * own payload (`groupCounts` in `/api/metrics/wallboard`) and hands it in as `counts`; that turns
 * the request off, so the display makes one call, not two.
 *
 * A breach is shown with an icon AND words, never colour alone. On the wallboard role (no access
 * to the ops menus) the tiles are plain boxes, not links to pages the server would refuse.
 */
const props = withDefaults(
  defineProps<{
    /** The page the tiles sit on — gets `aria-current="page"`. */
    current?: Scope | null
    /** Date window for the request; only `dateFrom`/`dateTo` are sent. */
    params?: URLSearchParams
    /** Supplied by the wallboard; when present nothing is fetched. */
    counts?: ScopeCount[] | null
    /** False renders non-interactive tiles (wallboard role). */
    links?: boolean
    /** Dark surface (wallboard). */
    dark?: boolean
    /** Hides the ⓘ (`?tv=1`). */
    hideHint?: boolean
  }>(),
  {
    current: null,
    params: undefined,
    counts: undefined,
    links: true,
    dark: false,
    hideHint: false,
  },
)

const { t, locale } = useI18n()

/** Where each group lives. Mirrors the router; the menu ids are `incident.ops|problem|sr`. */
const ROUTES: Record<Scope, string> = {
  INCIDENT: '/ops',
  PROBLEM: '/problem',
  SERVICE_REQUEST: '/service-requests',
}

/** The same label the sidebar shows, so the tile and the menu cannot disagree. */
const LABEL_KEYS: Record<Scope, string> = {
  INCIDENT: 'nav.menu.ops',
  PROBLEM: 'nav.menu.problem',
  SERVICE_REQUEST: 'nav.menu.sr',
}

// Component specs mount without `VueQueryPlugin`; same fallback `InfoHint` uses.
const injectedClient = inject<QueryClient | null>(VUE_QUERY_CLIENT, null)

const fetchedExternally = computed(() => props.counts !== undefined)

const query = useQuery(
  {
    queryKey: computed(
      () =>
        [
          'metrics',
          'scope-counts',
          props.params?.get('dateFrom') ?? '',
          props.params?.get('dateTo') ?? '',
        ] as const,
    ),
    queryFn: () => fetchScopeCounts(props.params),
    enabled: computed(() => !fetchedExternally.value),
    // Keeps the figures while a date change refetches, rather than flashing three skeletons.
    placeholderData: (previous) => previous,
  },
  injectedClient ?? appQueryClient,
)

const rows = computed<ScopeCount[] | null>(() => {
  const source = fetchedExternally.value ? props.counts : query.data.value?.scopes
  return source ?? null
})

// External counts that have not arrived yet (the wallboard's first poll) are loading too.
const loading = computed(() => !rows.value && (fetchedExternally.value || query.isPending.value))
const failed = computed(() => !fetchedExternally.value && query.isError.value && !rows.value)

interface Tile {
  scope: Scope
  name: string
  route: string
  open: number
  breached: number
  total: number
}

/** Always the three groups in menu order, whatever order the payload arrived in. */
const tiles = computed<Tile[]>(() =>
  SCOPES.map((scope) => {
    const row = rows.value?.find((entry) => entry.scope === scope)
    return {
      scope,
      name: t(LABEL_KEYS[scope]),
      route: ROUTES[scope],
      open: row?.open ?? 0,
      breached: row?.breached ?? 0,
      total: row?.total ?? 0,
    }
  }),
)

function ariaLabel(tile: Tile): string {
  return t('scopeCounts.tileLabel', {
    name: tile.name,
    open: formatCount(tile.open, locale.value),
    breached: formatCount(tile.breached, locale.value),
  })
}

const surface = computed(() =>
  props.dark
    ? 'border-white/15 bg-white/5 text-white'
    : 'border-surface-200 bg-surface-0 text-surface-900 shadow-sm',
)
</script>

<template>
  <section aria-labelledby="scope-counts-heading" data-testid="scope-counts">
    <div class="mb-2 flex items-center gap-2">
      <h2
        id="scope-counts-heading"
        class="text-sm font-semibold"
        :class="dark ? 'text-white/70' : 'text-surface-700'"
      >
        {{ t('scopeCounts.heading') }}
      </h2>
      <InfoHint
        v-if="!hideHint"
        term="groupCounts"
        :class="dark ? 'text-white/70' : 'text-surface-600'"
      />
    </div>

    <div
      v-if="failed"
      class="flex flex-wrap items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 text-sm text-surface-700"
      role="alert"
      data-testid="scope-counts-error"
    >
      <i class="pi pi-exclamation-circle" aria-hidden="true" />
      <span>{{ t('scopeCounts.error') }}</span>
      <Button
        type="button"
        size="small"
        severity="secondary"
        variant="outlined"
        class="min-h-11"
        :label="t('common.retry')"
        @click="query.refetch()"
      />
    </div>

    <ul
      v-else
      class="grid grid-cols-1 gap-2 sm:grid-cols-3 sm:gap-3"
      data-testid="scope-counts-list"
    >
      <li v-for="tile in tiles" :key="tile.scope">
        <component
          :is="links ? RouterLink : 'div'"
          v-bind="links ? { to: tile.route, 'aria-label': ariaLabel(tile) } : {}"
          :aria-current="links && current === tile.scope ? 'page' : undefined"
          class="flex h-full min-h-14 flex-row flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border p-3 sm:min-h-16 sm:flex-col sm:items-stretch focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
          :class="[
            surface,
            links && current === tile.scope ? 'border-primary-600 ring-1 ring-primary-600' : '',
            links ? 'no-underline hover:bg-surface-50' : '',
          ]"
          :data-testid="`scope-tile-${tile.scope}`"
          :data-scope="tile.scope"
        >
          <span
            class="text-xs font-semibold sm:text-sm"
            :class="dark ? 'text-white/80' : 'text-surface-700'"
          >
            {{ tile.name }}
          </span>
          <Skeleton v-if="loading" height="2rem" width="4rem" data-testid="scope-skeleton" />
          <template v-else>
            <span class="flex flex-wrap items-baseline gap-x-2">
              <span class="text-3xl font-bold tabular-nums leading-none" data-testid="scope-open">{{
                formatCount(tile.open, locale)
              }}</span>
              <span class="text-sm" :class="dark ? 'text-white/70' : 'text-surface-600'">{{
                t('scopeCounts.open')
              }}</span>
            </span>
            <!-- Icon plus words: a breach never rides on colour alone. -->
            <span
              class="inline-flex items-center gap-1 text-sm font-medium"
              :class="
                tile.breached > 0
                  ? dark
                    ? 'text-[color-mix(in_srgb,var(--sla-breach)_45%,white)]'
                    : 'text-[var(--chart-error-text)]'
                  : dark
                    ? 'text-white/60'
                    : 'text-surface-600'
              "
              data-testid="scope-breached"
            >
              <i class="pi pi-exclamation-triangle text-xs" aria-hidden="true" />
              {{ t('scopeCounts.breached', { count: formatCount(tile.breached, locale) }) }}
            </span>
            <span
              v-if="rows && tile.total === 0"
              class="text-xs"
              :class="dark ? 'text-white/60' : 'text-surface-600'"
              data-testid="scope-none"
              >{{ t('scopeCounts.none') }}</span
            >
          </template>
        </component>
      </li>
    </ul>
  </section>
</template>
