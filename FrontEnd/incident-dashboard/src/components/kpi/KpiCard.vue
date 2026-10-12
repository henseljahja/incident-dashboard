<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { DefinitionTerm } from '@/apis/definitions'
import InfoHint from '@/components/common/InfoHint.vue'
import { formatCount, formatDecimal } from '@/utils/number'

const props = withDefaults(
  defineProps<{
    label: string
    /**
     * The RAW figure, never pre-formatted text. The card formats it for the active locale; a
     * formatted string (`4,2` in Indonesian) would make `Number(value)` NaN and silently drop
     * the trend arrow. `null` renders the em dash.
     */
    value: number | string | null
    /** Previous-period value for the same metric, for the period-over-period arrow. `null`/`undefined` hides it — see `KpiResponse.previousPeriod`, which is null for an unbounded window. */
    previousValue?: number | null
    /**
     * Severity accent. Purely presentational — it colours the left rule and the icon so a
     * responder can find "Breached" without reading seven labels. It does NOT change the value,
     * and an absent tone renders the neutral card, so a new KPI added without one still looks
     * deliberate rather than broken.
     */
    tone?: 'neutral' | 'info' | 'warn' | 'risk' | 'danger'
    /** PrimeIcons class, e.g. `pi pi-inbox`. */
    icon?: string
    /**
     * What activating the card does, e.g. "Scroll to the incident table".
     *
     * Every card is a `<button>`, but nothing said so: a screen reader announced
     * "BACKLOG 95, button" with no hint of the outcome. Worse, one card behaves differently
     * from the other six — Workaround Debt also applies a filter — with no distinction of any
     * kind. Naming the action makes both problems visible instead of implied.
     */
    actionLabel?: string
    /**
     * Facet state for a toggle tile (Open / Closed / Breached / At Risk). `undefined` means the
     * card is not a toggle at all and carries no `aria-pressed` — an MTTR tile announcing
     * "not pressed" would claim a state it cannot have.
     */
    pressed?: boolean
    /** `false` renders a plain, non-interactive card (Exec summary, error state). */
    clickable?: boolean
    /** Renders a Skeleton in place of the figure — never an empty card (DAC1). */
    loading?: boolean
    /** The figure failed to load: `—` plus `kpi.error`, and the card stops being a control. */
    error?: boolean
    /** A short qualifier under the figure ("mean days, closed tickets"). */
    sublabel?: string
    /** The ⓘ definition for this figure. */
    term?: DefinitionTerm
  }>(),
  { clickable: true, pressed: undefined },
)

const emit = defineEmits<{
  (e: 'select'): void
}>()

/**
 * Fallthrough attributes (a caller's `data-testid`) land on the TILE control, not on the wrapper:
 * the tile is what a caller clicks and what a spec addresses, exactly as when the whole card was
 * one `<button>`.
 */
defineOptions({ inheritAttrs: false })

const { t, locale } = useI18n()

const interactive = computed(() => props.clickable && !props.error && !props.loading)

const numericValue = computed<number | null>(() => {
  if (props.value === null || props.value === '') return null
  const current = Number(props.value)
  return Number.isNaN(current) ? null : current
})

/**
 * Whole numbers stay whole (incident counts); anything with a fraction keeps one decimal (the
 * two MTTR cards, which arrive pre-rounded as `4.2`). `Intl`, so an Indonesian reader sees
 * `4,2` and `1.234` rather than a decimal point that means "thousands" to them.
 */
function formatFigure(value: number): string {
  return Number.isInteger(value)
    ? formatCount(value, locale.value)
    : formatDecimal(value, locale.value)
}

const displayValue = computed(() => {
  if (props.error) return '—'
  if (numericValue.value !== null) return formatFigure(numericValue.value)
  return props.value === null || props.value === '' ? '—' : String(props.value)
})

const trend = computed(() => {
  if (props.previousValue === null || props.previousValue === undefined) return null
  if (props.error || numericValue.value === null) return null
  if (numericValue.value > props.previousValue) return 'up'
  if (numericValue.value < props.previousValue) return 'down'
  return 'flat'
})

/**
 * How MUCH it moved, not just which way.
 *
 * The card used to render a bare arrow: "95 ▲". Up by one, or up by forty? An arrow with no
 * number tells a responder that something changed and then sends them elsewhere to find out how
 * much — which is precisely the trip the tile exists to save. The Executive view already
 * printed its magnitudes; the operator's own KPI row did not.
 *
 * Formatted off the SAME numeric value the trend is derived from, so the glyph and the figure
 * can never disagree.
 */
const delta = computed<number | null>(() => {
  if (trend.value === null || numericValue.value === null) return null
  return numericValue.value - (props.previousValue ?? 0)
})

const deltaLabel = computed(() => {
  const value = delta.value
  if (value === null || value === 0) return ''
  // Rounded to the tenth first: 9.5 - 4.2 is 5.299999… in floating point, which is not an integer.
  const magnitude = Math.round(Math.abs(value) * 10) / 10
  return formatFigure(magnitude)
})

/**
 * Spoken, not glyphed. "▲12" announces as punctuation or as nothing at all depending on the
 * screen reader, so the accessible name carries the movement in words. Up is bad on every card
 * here — see the note on the template — so the direction is named as worse/better, not as
 * up/down, which would leave the listener to infer the polarity a sighted reader gets from the
 * colour.
 */
const trendDescription = computed(() => {
  if (!trend.value || delta.value === null) return ''
  if (trend.value === 'flat') return t('kpi.trendUnchanged')
  return trend.value === 'up'
    ? t('kpi.trendWorse', { delta: deltaLabel.value })
    : t('kpi.trendBetter', { delta: deltaLabel.value })
})

const accessibleName = computed(() => {
  const parts = [t('kpi.cardLabel', { label: props.label, value: displayValue.value })]
  if (props.error) parts.push(t('kpi.error'))
  if (trendDescription.value) parts.push(trendDescription.value)
  if (interactive.value && props.actionLabel) parts.push(`${props.actionLabel}.`)
  return parts.join(' ')
})

/**
 * The accent rule lives on a `::before`-style span rather than a border colour so the tone
 * map stays in one place. Values come from the SLA ramp in main.css, not the categorical
 * chart palette — severity is a scale, not a set of unrelated categories.
 */
const TONE_RULE: Record<string, string> = {
  neutral: 'bg-surface-300',
  info: 'bg-primary-500',
  warn: 'bg-[var(--sla-warn)]',
  risk: 'bg-[var(--sla-risk)]',
  danger: 'bg-[var(--sla-breach)]',
}
const TONE_ICON: Record<string, string> = {
  neutral: 'text-surface-400',
  info: 'text-primary-600',
  warn: 'text-[var(--sla-warn)]',
  risk: 'text-[var(--sla-risk)]',
  danger: 'text-[var(--sla-breach)]',
}

const tone = computed(() => props.tone ?? 'neutral')

function onActivate() {
  if (interactive.value) emit('select')
}
</script>

<template>
  <!--
    The card is a wrapper, the tile is the BUTTON inside it, and the ⓘ is the button's sibling
    — never its child. Nested interactive content is invalid HTML and unreachable to a screen
    reader. The tile button stretches over the whole card (`absolute inset-0` on its ::after)
    so the click target is still the full card, and the ⓘ sits above that layer in the header.

    Active facet: ring in the primary token AND a check glyph AND `aria-pressed` — the state is
    never carried by colour alone (WCAG 1.4.1).
  -->
  <div
    class="group relative flex min-h-11 flex-col items-start gap-1 overflow-hidden rounded-lg border bg-surface-0 p-4 pl-5 text-left shadow-sm transition-colors motion-reduce:transition-none"
    :class="[
      pressed ? 'border-transparent ring-2 ring-[var(--p-primary-color)]' : 'border-surface-200',
      interactive ? 'hover:border-primary-400 hover:bg-surface-50' : '',
    ]"
    data-testid="kpi-tile"
    :data-pressed="pressed === undefined ? undefined : String(pressed)"
  >
    <!-- Severity rule. aria-hidden: the tone repeats what the label already says. -->
    <span class="absolute inset-y-0 left-0 w-1.5" :class="TONE_RULE[tone]" aria-hidden="true" />

    <component
      :is="interactive ? 'button' : 'div'"
      :type="interactive ? 'button' : undefined"
      data-testid="kpi-card"
      :aria-label="interactive ? accessibleName : undefined"
      :aria-pressed="interactive && pressed !== undefined ? String(pressed) : undefined"
      :title="interactive ? actionLabel : undefined"
      class="flex w-full flex-col items-start gap-1 text-left focus-visible:outline-none"
      :class="
        interactive
          ? 'cursor-pointer after:absolute after:inset-0 after:rounded-lg focus-visible:after:outline-2 focus-visible:after:outline-offset-2 focus-visible:after:outline-primary-600'
          : ''
      "
      v-bind="$attrs"
      @click="onActivate"
    >
      <!-- The check LEADS the label, so a pressed tile never pushes it under the icon cluster. -->
      <span class="flex w-full items-center gap-1.5 pr-8 xl:max-2xl:pr-16">
        <i
          v-if="pressed"
          class="pi pi-check-circle text-xs text-primary-700"
          data-testid="kpi-pressed-icon"
          aria-hidden="true"
        />
        <span
          :title="label"
          class="text-xs font-medium tracking-normal text-surface-600 uppercase [overflow-wrap:normal] xl:max-2xl:tracking-wide"
        >
          {{ label }}
        </span>
      </span>

      <span v-if="loading" class="flex h-9 w-20 items-center">
        <Skeleton height="1.75rem" width="4rem" data-testid="kpi-skeleton" />
      </span>
      <span v-else class="flex items-baseline gap-2">
        <span class="text-3xl font-semibold text-surface-900 tabular-nums" data-testid="kpi-value">
          {{ displayValue }}
        </span>
        <!--
          Visible affordance to match the announced one — otherwise only AT users learn the
          card is actionable. Hidden from AT because `aria-label` already carries it.
        -->
        <i
          v-if="interactive && actionLabel"
          class="pi pi-arrow-right ml-1 text-xs text-surface-400 opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 motion-reduce:transition-none"
          aria-hidden="true"
        />
        <!--
          Direction AND magnitude, and up is bad: every card here counts incidents or days, so a
          rise is a regression. The arrow glyph carries the same information as the colour, so
          this stays readable without colour vision. `aria-hidden` because the card's own
          `aria-label` says the same thing in words.
        -->
        <span
          v-if="trend"
          data-testid="kpi-trend"
          :data-trend="trend"
          :data-delta="deltaLabel || undefined"
          class="text-sm font-medium tabular-nums"
          aria-hidden="true"
          :class="{
            'text-[var(--chart-error-text)]': trend === 'up',
            'text-[var(--chart-success-text)]': trend === 'down',
            'text-surface-600': trend === 'flat',
          }"
        >
          <template v-if="trend === 'up'">▲{{ deltaLabel }}</template>
          <template v-else-if="trend === 'down'">▼{{ deltaLabel }}</template>
          <template v-else>—</template>
        </span>
      </span>

      <span v-if="sublabel && !error" class="text-xs text-surface-600" data-testid="kpi-sublabel">{{
        sublabel
      }}</span>

      <span v-if="error" class="text-xs text-surface-600" data-testid="kpi-error">
        {{ t('kpi.error') }}
      </span>
    </component>

    <!-- Above the stretched tile layer, so the ⓘ stays its own target. -->
    <span
      class="absolute top-2 right-1 z-10 flex items-center gap-1 text-surface-600 xl:max-2xl:right-2"
    >
      <!--
        Decorative (the tone rule and label already say it), so it yields on narrow tiles: at
        360px a two-up tile cannot hold label + tone icon + ⓘ without the label running under it.
      -->
      <!-- A wrapper carries the breakpoint: primeicons' unlayered `.pi { display }` beats utilities. -->
      <span
        v-if="icon"
        class="mr-1 hidden xl:max-2xl:inline-flex"
        data-testid="kpi-tone-icon"
        aria-hidden="true"
      >
        <i :class="[icon, TONE_ICON[tone]]" />
      </span>
      <InfoHint v-if="term" :term="term" />
    </span>
  </div>
</template>
