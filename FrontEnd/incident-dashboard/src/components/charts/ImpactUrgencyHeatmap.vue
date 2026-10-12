<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import type { MatrixCell } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { useScrollCue } from '@/composables/useScrollCue'
import { formatCount } from '@/utils/number'
import { BRAND_PRIMARY } from '@/utils/severity'

const props = withDefaults(
  defineProps<{
    cells: MatrixCell[]
    /**
     * The SPO matrix axes, in display order: impact (columns) Massive..Localized, urgency (rows)
     * Urgent..Low. Given, they are ALWAYS drawn in full (a combination with no tickets is a
     * quiet 0), and any label not in them (Unclassified, Unknown (n)) follows in a separated
     * neutral group. Absent (an older server), the axes are derived from the cells by severity.
     */
    impactOrder?: string[]
    urgencyOrder?: string[]
    /** Rows in the filtered set whose impact OR urgency is blank; 0 hides the link. */
    missingCount?: number
  }>(),
  { missingCount: 0, impactOrder: () => [], urgencyOrder: () => [] },
)

const emit = defineEmits<{
  (e: 'drill', payload: { impact: string; urgency: string; count: number }): void
  /** The "N missing" link — the page narrows the table to exactly those tickets. */
  (e: 'show-missing'): void
}>()

const { t, te, locale } = useI18n()

/**
 * Axis order, and it is the whole point of drawing a matrix rather than a list.
 *
 * An Impact x Urgency grid communicates by POSITION: the worst combination sits in one corner
 * and severity falls away along the diagonal, so a triage lead reads the shape before reading
 * a single number. That only works if both axes are ordered by severity.
 *
 * This used to return 0 for every real severity label and fall through to `localeCompare`,
 * which renders the axes **High, Low, Medium** — alphabetical. There is no corner in that
 * layout and no gradient; the chart looked like a heatmap and answered like a lookup table,
 * and every "where is the pain concentrated?" reading taken off it was wrong.
 *
 * `SEVERITY_ORDER` is the display order, worst first. A label the mart produces that is not in
 * it (an operator can add a Redmine priority at any time) sorts after every known severity but
 * before the two data-quality sentinels, so it is visible without displacing the gradient.
 */
const SEVERITY_ORDER = ['Critical', 'High', 'Medium', 'Low'] as const

/** Above every sentinel, below nothing else — an unrecognised label is data, not a defect. */
const UNRECOGNISED_RANK = 100

function axisRank(label: string): number {
  if (label === 'Unclassified') return 200
  if (label.startsWith('Unknown')) return 201
  const index = SEVERITY_ORDER.indexOf(label as (typeof SEVERITY_ORDER)[number])
  return index === -1 ? UNRECOGNISED_RANK : index
}

function uniqueAxis(values: string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const value of values) {
    if (!seen.has(value)) {
      seen.add(value)
      out.push(value)
    }
  }
  return out.sort((a, b) => {
    const rankDiff = axisRank(a) - axisRank(b)
    return rankDiff !== 0 ? rankDiff : a.localeCompare(b)
  })
}

// The grid scrolls inside its own box (the page never scrolls sideways); say so when it does.
const scroller = ref<HTMLElement | null>(null)
const { moreToRight, measure: measureScroll } = useScrollCue(scroller, () => props.cells)

/**
 * One axis: the SPO order first, in full, then whatever else the data holds. With no SPO order
 * the labels are sorted by severity as before.
 */
function axisFor(order: string[], labels: string[]): string[] {
  if (order.length === 0) return uniqueAxis(labels)
  return [...order, ...uniqueAxis(labels.filter((label) => !order.includes(label)))]
}

const impacts = computed(() =>
  axisFor(
    props.impactOrder,
    props.cells.map((cell) => cell.impact),
  ),
)
const urgencies = computed(() =>
  axisFor(
    props.urgencyOrder,
    props.cells.map((cell) => cell.urgency),
  ),
)

/** True from the first label that is not part of the SPO order — the neutral group starts here. */
const impactExtraFrom = computed(() =>
  props.impactOrder.length === 0 ? -1 : props.impactOrder.length,
)
const urgencyExtraFrom = computed(() =>
  props.urgencyOrder.length === 0 ? -1 : props.urgencyOrder.length,
)

/** The SPO palette is only drawn when the server sent the SPO axes. */
const spo = computed(() => props.impactOrder.length > 0 || props.urgencyOrder.length > 0)

/** The urgency row-header column; wide enough for "Unclassified" at text-xs without a mid-word break. */
const ROW_HEADER_REM = 5
/** An SPO column is at most this wide, and shrinks to share what the scroller has (phone). */
const SPO_COLUMN_REM = 4.5
/** A neutral-group column (Unclassified, Unknown (n)); these may scroll. */
const EXTRA_COLUMN_REM = 5

/**
 * The matrix as a grid: row headers, then the SPO columns, then the neutral group.
 *
 * The SPO columns are `minmax(0, 4.5rem)` and the whole grid is `min(100% + the neutral columns,
 * what the tracks need)` wide. On a phone that makes the SPO block exactly as wide as the
 * scroller — all four columns and the row header visible with nothing to scroll — while the
 * neutral columns sit past the right edge and are what scrolls. On a desktop the second term wins
 * and there is no phantom overflow. Fixed 56-64px cells did not shrink, which clipped the fourth
 * column at 375px.
 */
const gridStyle = computed(() => {
  const spoColumns = props.impactOrder.length
  const extraColumns = impacts.value.length - spoColumns
  const tracks = [
    `${ROW_HEADER_REM}rem`,
    spoColumns > 0 ? `repeat(${spoColumns}, minmax(0, ${SPO_COLUMN_REM}rem))` : '',
    extraColumns > 0 ? `repeat(${extraColumns}, ${EXTRA_COLUMN_REM}rem)` : '',
  ].filter(Boolean)
  const needed = ROW_HEADER_REM + spoColumns * SPO_COLUMN_REM + extraColumns * EXTRA_COLUMN_REM
  return {
    gridTemplateColumns: tracks.join(' '),
    width:
      spoColumns > 0
        ? `min(calc(100% + ${extraColumns * EXTRA_COLUMN_REM}rem), ${needed}rem)`
        : 'max-content',
  }
})

/**
 * SPO priority -> tint, from the semantic tokens in `main.css` (no new colours). Critical is the
 * darkest red, High the orange, Medium the amber, Low the green. Every tinted cell also carries
 * the priority WORD, and the legend names them, so the colour is never the only signal.
 */
const PRIORITY_TINT: Record<string, string> = {
  Critical: 'bg-[var(--chart-error-text)] text-white',
  High: 'bg-[var(--sla-risk)] text-surface-900',
  Medium: 'bg-[var(--sla-warn)] text-surface-900',
  Low: 'bg-[var(--sla-ok)] text-surface-900',
}
const PRIORITIES = ['Critical', 'High', 'Medium', 'Low'] as const

function priorityWord(priority: string): string {
  return te(`charts.matrix.priority.${priority}`)
    ? t(`charts.matrix.priority.${priority}`)
    : priority
}

/** Classes for a cell in SPO mode; `null` in legacy mode, which paints with the count ramp. */
function tintFor(cell: MatrixCell): string | null {
  if (!spo.value) return null
  if (cell.count === 0) return 'bg-surface-50 font-normal text-surface-400'
  const tint = cell.priority ? PRIORITY_TINT[cell.priority] : undefined
  return tint ?? 'bg-surface-100 text-surface-900'
}

function cellText(cell: MatrixCell): string | null {
  return spo.value && cell.count > 0 && cell.priority ? priorityWord(cell.priority) : null
}

function cellAria(cell: MatrixCell): string {
  const base = {
    impact: cell.impact,
    urgency: cell.urgency,
    count: formatCount(cell.count, locale.value),
  }
  return cell.count > 0 && cell.priority
    ? t('charts.matrix.cellLabelPriority', { ...base, priority: priorityWord(cell.priority) })
    : t('charts.matrix.cellLabel', base)
}

function coordinateKey(impact: string, urgency: string): string {
  return `${impact}::${urgency}`
}

const cellByCoordinate = computed(() => {
  const map = new Map<string, MatrixCell>()
  for (const cell of props.cells) {
    map.set(coordinateKey(cell.impact, cell.urgency), cell)
  }
  return map
})

function cellAt(impact: string, urgency: string): MatrixCell {
  return cellByCoordinate.value.get(coordinateKey(impact, urgency)) ?? { impact, urgency, count: 0 }
}

const maxCount = computed(() => props.cells.reduce((max, cell) => Math.max(max, cell.count), 0))

const total = computed(() => props.cells.reduce((sum, cell) => sum + cell.count, 0))

/**
 * A sequential, count-driven intensity ramp — deliberately blue-based, never the brand primary
 * (see `severity.ts`). The count is always rendered as text on the cell too, so severity never
 * depends on hue alone (colour-blind / print-safe).
 */
const RAMP = ['#eff6ff', '#bfdbfe', '#60a5fa', '#2563eb', '#1e3a8a']

/**
 * Ink per ramp step, and it must be derived from the ramp rather than fixed.
 *
 * The cell text used to be `text-surface-900` at every intensity, which inverted the chart's
 * own hierarchy: the ramp darkens as the count rises, so the single cell a responder most
 * needs to read — the busiest one — was the least legible. `#171717` on `#1e3a8a` measures
 * **1.73:1**. White on that same navy measures 10.36:1.
 *
 * The switch happens at index 3 (`#2563eb`), the first step too dark for near-black ink.
 */
const RAMP_INK = ['#171717', '#171717', '#171717', '#ffffff', '#ffffff']

if (
  import.meta.env.DEV &&
  RAMP.some((colour) => colour.toLowerCase() === BRAND_PRIMARY.toLowerCase())
) {
  throw new Error('ImpactUrgencyHeatmap ramp must never reuse the brand primary colour.')
}

function rampIndex(count: number): number {
  if (maxCount.value === 0 || count === 0) return 0
  const ratio = count / maxCount.value
  return Math.min(RAMP.length - 1, Math.floor(ratio * (RAMP.length - 1) + 0.0001))
}

function colourFor(count: number): string {
  return RAMP[rampIndex(count)]!
}

/** Paired with `colourFor` by construction, so the two can never drift out of contrast. */
function inkFor(count: number): string {
  return RAMP_INK[rampIndex(count)]!
}

/**
 * `Unknown (<id>)` is NOT a category — it is a deliberate data-quality signal. The mart
 * resolves an enum id with no `REF_CUSTOM_FIELD_ENUM` row to `Unknown (<id>)` rather than to
 * NULL precisely so a stale reference seed is visible in the report (see
 * `V1__config_tables.sql:112-128`). Rendering it identically to "Impact - Low" hid that
 * signal in plain sight: the one person who could act on it had no reason to notice.
 *
 * Do NOT hide these labels — being visible is the entire point. They just need to read as a
 * warning rather than as a classification.
 */
function isUnresolved(label: string): boolean {
  return label.startsWith('Unknown (')
}

const unresolvedTitle = computed(() => t('charts.matrix.unresolvedTitle'))

function onCellClick(cell: MatrixCell) {
  emit('drill', { impact: cell.impact, urgency: cell.urgency, count: cell.count })
}
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm"
    data-testid="impact-urgency-heatmap"
  >
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-sm font-semibold text-surface-700">{{ t('charts.matrix.title') }}</h2>
      <InfoHint term="matrix" class="text-surface-600" />
    </div>
    <!--
      An empty result set used to collapse this panel to a bare "Total: 0" inside an otherwise
      empty card, which reads as "failed to load" rather than "nothing matched". The table
      below already handles empties properly; this matches it.
    -->
    <div
      v-if="total === 0"
      class="flex flex-col items-center gap-2 py-8 text-center text-sm text-surface-600"
      data-testid="heatmap-empty"
    >
      <i class="pi pi-inbox text-2xl text-surface-300" aria-hidden="true" />
      {{ t('charts.empty') }}
    </div>

    <!--
      The grid gets its OWN horizontal scroll container so the page body never scrolls sideways.
 
      At four severity levels — Critical/High/Medium/Low, which is what the mart actually
      produces — the cells alone are 4 x 64px plus a row-label column, which exceeds a 390px
      phone viewport. Without this the whole document scrolled horizontally on mobile, and the
      thing that moved off-screen was the far column of the matrix: the corner a heatmap exists
      to put the worst cell in.
    -->
    <div v-else class="relative">
      <p
        v-if="moreToRight"
        class="mb-1 flex items-center justify-end gap-1 text-xs text-surface-600"
        data-testid="heatmap-scroll-hint"
      >
        {{ t('table.scrollHint') }}
        <i class="pi pi-arrow-right text-2xs" aria-hidden="true" />
      </p>
      <div
        ref="scroller"
        class="overflow-x-auto focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
        tabindex="0"
        role="region"
        :aria-label="t('charts.matrix.scrollRegion')"
        data-testid="heatmap-scroller"
        @scroll.passive="measureScroll"
      >
        <table class="grid text-sm" role="table" :style="gridStyle">
          <thead class="contents" role="rowgroup">
            <tr class="contents" role="row">
              <td role="presentation"></td>
              <th
                scope="colgroup"
                role="columnheader"
                style="grid-column: 2 / -1"
                class="p-1 text-left text-xs font-semibold text-surface-700"
                data-testid="heatmap-axis-impact"
              >
                {{ t('charts.matrix.axis.impact') }}
              </th>
            </tr>
            <tr class="contents" role="row">
              <td
                role="presentation"
                class="p-1 text-left align-bottom text-xs font-semibold text-surface-700"
                data-testid="heatmap-axis-urgency"
              >
                {{ t('charts.matrix.axis.urgency') }}
              </td>
              <th
                v-for="(impact, column) in impacts"
                :key="impact"
                class="min-w-0 px-0 py-1 text-left align-bottom text-2xs font-normal [overflow-wrap:break-word] hyphens-none sm:px-1 sm:text-xs"
                :class="[
                  isUnresolved(impact) ? 'text-amber-700 italic' : 'text-surface-600',

                  impactExtraFrom >= 0 && column === impactExtraFrom
                    ? 'border-l border-dashed border-surface-300'
                    : '',
                ]"
                :title="isUnresolved(impact) ? `${impact} — ${unresolvedTitle}` : impact"
                scope="col"
                role="columnheader"
              >
                <i
                  v-if="isUnresolved(impact)"
                  class="pi pi-exclamation-triangle mr-1 text-xs"
                  aria-hidden="true"
                />
                {{ impact }}
              </th>
            </tr>
          </thead>
          <tbody class="contents" role="rowgroup">
            <tr v-for="(urgency, row) in urgencies" :key="urgency" class="contents" role="row">
              <th
                class="min-w-0 p-0.5 text-left text-xs font-normal [overflow-wrap:break-word] hyphens-none"
                :class="[
                  isUnresolved(urgency) ? 'text-amber-700 italic' : 'text-surface-600',
                  urgencyExtraFrom >= 0 && row === urgencyExtraFrom
                    ? 'border-t border-dashed border-surface-300'
                    : '',
                ]"
                :title="isUnresolved(urgency) ? `${urgency} — ${unresolvedTitle}` : urgency"
                scope="row"
                role="rowheader"
              >
                <i
                  v-if="isUnresolved(urgency)"
                  class="pi pi-exclamation-triangle mr-1 text-xs"
                  aria-hidden="true"
                />
                {{ urgency }}
              </th>
              <td
                v-for="(impact, column) in impacts"
                :key="impact"
                role="cell"
                class="min-w-0 p-0.5 sm:p-1"
                :class="[
                  impactExtraFrom >= 0 && column === impactExtraFrom
                    ? 'border-l border-dashed border-surface-300'
                    : '',
                  urgencyExtraFrom >= 0 && row === urgencyExtraFrom
                    ? 'border-t border-dashed border-surface-300'
                    : '',
                ]"
              >
                <button
                  type="button"
                  data-testid="heatmap-cell"
                  :data-impact="impact"
                  :data-urgency="urgency"
                  :data-count="cellAt(impact, urgency).count"
                  :data-priority="cellAt(impact, urgency).priority ?? undefined"
                  :aria-label="cellAria(cellAt(impact, urgency))"
                  class="flex h-14 w-full min-w-0 flex-col items-center justify-center gap-0.5 rounded text-sm font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600 sm:h-12"
                  :class="tintFor(cellAt(impact, urgency))"
                  :style="
                    spo
                      ? undefined
                      : {
                          backgroundColor: colourFor(cellAt(impact, urgency).count),
                          color: inkFor(cellAt(impact, urgency).count),
                        }
                  "
                  @click="onCellClick(cellAt(impact, urgency))"
                >
                  <span
                    :class="
                      spo && cellAt(impact, urgency).count > 0
                        ? 'text-lg leading-none font-bold'
                        : ''
                    "
                    >{{ formatCount(cellAt(impact, urgency).count, locale) }}</span
                  >
                  <span
                    v-if="cellText(cellAt(impact, urgency))"
                    class="text-2xs leading-none font-medium"
                    data-testid="heatmap-cell-priority"
                    >{{ cellText(cellAt(impact, urgency)) }}</span
                  >
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <!--
        No edge fade here, unlike the incident table. On a phone the SPO block fills the scroller
        exactly, so the neutral columns start at the right edge and a fade would sit on the
        Localized column and wash it out. The text hint above says there is more.
      -->
    </div>
    <!--
      Rendered UNCONDITIONALLY, including when the count is zero.
      
      The empty state above replaced a bare "Total: 0" floating in an otherwise blank card, but
      hiding the count outright went a step too far: "no incidents matched" and "the panel
      failed to load" become indistinguishable again, just in the other direction. The message
      explains, the number confirms — an explicit `Total: 0` is a stated fact, and its absence
      is not.
    -->
    <!-- What the colours mean, in words. SPO mode only: legacy cells are tinted by count. -->
    <ul
      v-if="spo && total > 0"
      class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-surface-700"
      :aria-label="t('charts.matrix.legend')"
      data-testid="heatmap-legend"
    >
      <li v-for="priority in PRIORITIES" :key="priority" class="flex items-center gap-1">
        <span
          class="inline-block size-3 rounded"
          :class="PRIORITY_TINT[priority]"
          aria-hidden="true"
        />
        {{ priorityWord(priority) }}
      </li>
    </ul>
    <div class="text-xs text-surface-600" data-testid="heatmap-total">
      {{ t('common.total', { total: formatCount(total, locale) }) }}
    </div>
    <!--
      Tickets with a blank impact or urgency cannot be placed on the grid's severity axes. They
      still land in the Unclassified cells, but a count and a way to open exactly those tickets
      is what lets someone go and fix the data in Redmine. A real button (it acts on the page, it
      does not navigate), 44px tall, with its own ⓘ beside it.
    -->
    <div v-if="missingCount > 0" class="flex items-center gap-2" data-testid="heatmap-missing">
      <button
        type="button"
        class="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded text-sm font-medium text-primary-700 underline underline-offset-2 hover:text-primary-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
        data-testid="heatmap-missing-link"
        :title="t('charts.matrix.missingAction')"
        @click="emit('show-missing')"
      >
        <i class="pi pi-exclamation-circle text-xs" aria-hidden="true" />
        {{ t('charts.matrix.missing', { count: formatCount(missingCount, locale) }, missingCount) }}
      </button>
      <InfoHint term="matrixMissing" class="text-surface-600" />
    </div>
  </div>
</template>
