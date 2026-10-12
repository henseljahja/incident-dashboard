<script setup lang="ts">
import { useQuery, VUE_QUERY_CLIENT, type QueryClient } from '@tanstack/vue-query'
import Popover from 'primevue/popover'
import { computed, inject, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import {
  DEFINITIONS_QUERY_KEY,
  DEFINITIONS_STALE_TIME_MS,
  fetchDefinitions,
  type CloseBasis,
  type DefinitionTerm,
} from '@/apis/definitions'
import { queryClient as appQueryClient } from '@/queryClient'
import { formatCount } from '@/utils/number'

/**
 * The ⓘ on every tile, chart card and explained table header: what the number shows, how it is
 * calculated, and where the rule comes from.
 *
 * Wording is i18n (`definitions.<term>.*`); thresholds are live from
 * `GET /api/metrics/definitions`, so "Critical 1 · High 2" follows `CFG_SLA_POLICY` instead of
 * drifting from it. The meaning and formula never wait on that request and never fail with it:
 * while it loads the numbers read `…`, and if it fails they read
 * `definitions.numbersUnavailable`, which points at the source document instead.
 *
 * A separate `<button>`, never nested inside a tile's own button — nested interactive content
 * is invalid, and a screen reader cannot reach the inner control at all.
 */
const props = defineProps<{
  term: DefinitionTerm
}>()

const { t, locale } = useI18n()

const trigger = ref<HTMLButtonElement | null>(null)
const popover = ref<InstanceType<typeof Popover> | null>(null)
const panelId = useId()
const open = ref(false)

/**
 * Fetched on first open, not on mount. A dashboard page carries a dozen of these and most are
 * never opened; one lazy request (shared by the `['definitions']` cache key) is enough.
 */
const requested = ref(false)

/**
 * The query client provided to this app, or the app singleton. Component specs mount charts and
 * tables without `VueQueryPlugin`, and `useQuery` throws outright with no client — an ⓘ must
 * never be the reason a card cannot render.
 */
const injectedClient = inject<QueryClient | null>(VUE_QUERY_CLIENT, null)

const definitions = useQuery(
  {
    queryKey: DEFINITIONS_QUERY_KEY,
    queryFn: fetchDefinitions,
    staleTime: DEFINITIONS_STALE_TIME_MS,
    enabled: requested,
  },
  injectedClient ?? appQueryClient,
)

const numbersState = computed<'loading' | 'ready' | 'error'>(() => {
  if (definitions.data.value) return 'ready'
  if (definitions.isError.value) return 'error'
  return 'loading'
})

/** The value for a numeric placeholder in the current state. */
function numeric(render: () => string): string {
  if (numbersState.value === 'ready') return render()
  if (numbersState.value === 'error') return t('definitions.numbersUnavailable')
  return '…'
}

const name = computed(() => t(`definitions.${props.term}.name`))

const DEFAULT_CLOSE_RULE: CloseBasis[] = ['CLOSE_DATE', 'CLOSED_ON', 'STATUS_CHANGE']

const formulaParams = computed(() => {
  const data = definitions.data.value
  const policy = data?.slaPolicy ?? []
  return {
    cushions: numeric(() =>
      t('definitions.dayList', {
        list: policy
          .map((row) =>
            t('definitions.policyItem', {
              name: row.priorityName,
              days: formatCount(row.atRiskRemainingDays, locale.value),
            }),
          )
          .join(' · '),
      }),
    ),
    statuses: numeric(() => {
      const names = (data?.closedByOverride ?? []).map((row) => row.statusName)
      return names.length === 0
        ? t('definitions.closed.redmineOnly')
        : t('definitions.closed.statusList', {
            // A plain comma list: `Intl.ListFormat` is ES2021 and this build targets an older lib,
            // and a status name is data, so it is not worth a polyfill to add "and".
            names: names.join(', '),
          })
    }),
    minutes: numeric(() => formatCount(data?.refreshIntervalMinutes ?? 0, locale.value)),
    // The precedence is an enum, not a threshold, so it keeps the documented default while the
    // request is in flight or failed rather than blanking a sentence that does not depend on it.
    rule: (data?.closeRule ?? DEFAULT_CLOSE_RULE)
      .map((basis) => t(`definitions.closeBasis.${basis}`))
      .join(' → '),
  }
})

/** The terms whose rule is "age against SLA days" also list the SLA days themselves. */
const SLA_DAY_TERMS = new Set<DefinitionTerm>([
  'breached',
  'atRisk',
  'slaBuckets',
  'slaColumn',
  'aging',
])

const slaDaysLine = computed(() => {
  if (!SLA_DAY_TERMS.has(props.term)) return null
  const data = definitions.data.value
  return t('definitions.slaDays', {
    list: numeric(() =>
      t('definitions.dayList', {
        list: [
          ...(data?.slaPolicy ?? []).map((row) =>
            t('definitions.policyItem', {
              name: row.priorityName,
              days: formatCount(row.slaDays, locale.value),
            }),
          ),
          t('definitions.policyItem', {
            name: t('definitions.unknownPriority'),
            days: formatCount(data?.unknownPriority.slaDays ?? 0, locale.value),
          }),
        ].join(' · '),
      }),
    ),
  })
})

function toggle(event: Event) {
  requested.value = true
  if (open.value) {
    close({ returnFocus: false })
    return
  }
  popover.value?.show(event, trigger.value ?? undefined)
  open.value = true
}

function close({ returnFocus }: { returnFocus: boolean }) {
  popover.value?.hide()
  open.value = false
  if (returnFocus) trigger.value?.focus()
}

/**
 * Esc closes AND puts focus back on the ⓘ. PrimeVue's Popover closes on Esc but only restores
 * focus when the key is pressed inside its content, so a keyboard user who opened it and pressed
 * Esc straight away was left with focus on nothing in particular.
 */
function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) {
    close({ returnFocus: true })
  }
}

watch(open, (isOpen) => {
  if (isOpen) document.addEventListener('keydown', onDocumentKeydown)
  else document.removeEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => document.removeEventListener('keydown', onDocumentKeydown))
</script>

<template>
  <span class="inline-flex shrink-0 align-middle">
    <!--
      44px hit area, 16px of visual footprint: the negative margin gives back the space the
      target adds, so a card header does not grow to fit a full-size button.
    -->
    <button
      ref="trigger"
      type="button"
      data-testid="info-hint"
      :data-term="term"
      :aria-label="t('definitions.about', { name })"
      aria-haspopup="dialog"
      :aria-expanded="open"
      :aria-controls="open ? panelId : undefined"
      class="-m-3 inline-flex size-11 cursor-pointer items-center justify-center rounded-full text-current hover:text-primary-700 focus-visible:outline-2 focus-visible:-outline-offset-8 focus-visible:outline-primary-600"
      @click.stop="toggle"
    >
      <i class="pi pi-info-circle text-sm" aria-hidden="true" />
    </button>
    <Popover ref="popover" :id="panelId" :aria-label="name" @hide="open = false">
      <div
        class="flex max-w-xs flex-col gap-2 text-sm text-surface-700"
        data-testid="info-hint-panel"
        :data-term="term"
      >
        <p class="font-semibold text-surface-900">{{ name }}</p>
        <dl class="flex flex-col gap-2">
          <div>
            <dt class="text-xs font-medium tracking-wide text-surface-600 uppercase">
              {{ t('definitions.meaning') }}
            </dt>
            <dd data-testid="info-hint-meaning">{{ t(`definitions.${term}.meaning`) }}</dd>
          </div>
          <div>
            <dt class="text-xs font-medium tracking-wide text-surface-600 uppercase">
              {{ t('definitions.formula') }}
            </dt>
            <dd data-testid="info-hint-formula">
              {{ t(`definitions.${term}.formula`, formulaParams) }}
            </dd>
            <dd v-if="slaDaysLine" class="mt-1" data-testid="info-hint-sla-days">
              {{ slaDaysLine }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium tracking-wide text-surface-600 uppercase">
              {{ t('definitions.source') }}
            </dt>
            <dd data-testid="info-hint-source">{{ t(`definitions.${term}.source`) }}</dd>
            <dd v-if="slaDaysLine" class="mt-1">{{ t('definitions.sources.spo') }}</dd>
          </div>
        </dl>
      </div>
    </Popover>
  </span>
</template>
