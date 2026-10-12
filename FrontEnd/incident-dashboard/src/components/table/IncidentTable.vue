<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchExport, type IncidentRow } from '@/apis/incidents'
import InfoHint from '@/components/common/InfoHint.vue'
import { useScrollCue } from '@/composables/useScrollCue'
import { useFiltersStore } from '@/stores/filters'
import { downloadExportWorkbook } from '@/utils/export'
import vCan from '@/utils/can'
import { formatCount } from '@/utils/number'
import { redmineIssueId, redmineIssueUrl } from '@/utils/redmine'

const props = defineProps<{
  rows: IncidentRow[]
  loading?: boolean
  page: number
  size: number
  totalElements: number
}>()

const emit = defineEmits<{
  (e: 'update:page', page: number): void
  (e: 'select', row: IncidentRow): void
}>()

const { t, te, locale } = useI18n()
const filters = useFiltersStore()
const exporting = ref(false)

/**
 * Whether the wide grid has columns off the right edge. Status, Priority, SLA and Aging sit
 * there at tablet width with nothing to say so, so the grid shows an edge fade and a hint while
 * there is more to the right, and the scroll container is focusable so the keyboard can scroll it.
 */
const scroller = ref<HTMLElement | null>(null)
const { moreToRight, measure: measureScroll } = useScrollCue(scroller, () => props.rows)
const exportTruncated = ref(false)
const exportError = ref<string | null>(null)

/**
 * Priority and SLA render as Tags rather than bare text so a responder can scan the column
 * instead of reading it. The maps are explicit and total over the values the mart produces —
 * an unmapped value falls through to the neutral severity rather than throwing or rendering
 * an unstyled chip, because the mart's PRIORITY comes from Redmine's `enumerations` table and
 * an operator can add a new priority there at any time without telling anyone.
 */
const PRIORITY_SEVERITY: Record<string, string> = {
  Critical: 'danger',
  High: 'warn',
  Medium: 'info',
  Low: 'secondary',
}
const SLA_SEVERITY: Record<string, string> = {
  Breached: 'danger',
  '80-100%': 'warn',
  '50-80%': 'info',
  '0-50%': 'success',
}
const severityFor = (map: Record<string, string>, value: unknown) =>
  map[String(value ?? '')] ?? 'secondary'

/**
 * `SLA_BUCKET` is a mart enum, so it is the one table value mapped client-side
 * (`charts.bucket.*`). A band the catalog does not know renders as delivered — a new backend
 * band must stay visible, not become a raw key path.
 */
const BUCKET_KEYS: Record<string, string> = {
  Breached: 'breached',
  '80-100%': 'p80to100',
  '50-80%': 'p50to80',
  '0-50%': 'p0to50',
  Unknown: 'unknown',
}
function bucketLabel(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  const key = BUCKET_KEYS[String(value)]
  return key && te(`charts.bucket.${key}`) ? t(`charts.bucket.${key}`) : String(value)
}

function rowKey(row: IncidentRow): string {
  return String(row.NO_INCIDENT ?? row.no_incident ?? JSON.stringify(row))
}

/**
 * Aging rendered AGAINST its own SLA, because the bare number was not a judgement.
 *
 * "14" means one thing on a Low-priority ticket with a 60-day budget and something else
 * entirely on a Critical with three, and the column that carried the budget (`SLA`) was
 * sitting unread two columns away. `14 / 60` is the same two facts with the comparison
 * already made.
 *
 * Both halves come off `V_INCIDENT`: `AGING_NEW` is the single elapsed expression, `SLA` is
 * `SLA_DAYS` under the workbook's own header. `AGING_NEW` is NULL for the ~8% of rows whose
 * `close_date` is the empty string — that cohort gets an em dash rather than a fabricated 0,
 * which is the same NULL-vs-'' distinction the view goes out of its way to preserve.
 */
function isBlank(value: unknown): boolean {
  return value === null || value === undefined || value === ''
}

function figure(value: unknown): string {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? formatCount(numeric, locale.value) : String(value)
}

function agingLabel(row: IncidentRow): string {
  if (isBlank(row.AGING_NEW)) return '—'
  if (isBlank(row.SLA)) return figure(row.AGING_NEW)
  return `${figure(row.AGING_NEW)} / ${figure(row.SLA)}`
}

/** Spoken form of the same pair — "14 / 60" announces as two unrelated numbers. */
function agingDescription(row: IncidentRow): string {
  if (isBlank(row.AGING_NEW)) return t('table.agingUnknown')
  if (isBlank(row.SLA)) return t('table.agingDaysOld', { elapsed: figure(row.AGING_NEW) })
  return t('table.agingOfSla', { elapsed: figure(row.AGING_NEW), budget: figure(row.SLA) })
}

/**
 * Re-requests the FULL filtered set from the server — never a transform of `props.rows`, the
 * page currently on screen. `toFilterParams()` structurally excludes `page`/`size`, so this
 * cannot regress into "export the visible page" no matter what page the table is on. It DOES
 * carry the status/SLA facets: the export is the table, not the period.
 */
async function onExport() {
  exporting.value = true
  exportError.value = null
  try {
    const result = await fetchExport(filters.toFilterParams())
    exportTruncated.value = result.truncated
    downloadExportWorkbook(result)
  } catch (error) {
    exportError.value = error instanceof Error ? error.message : t('table.exportFailed')
  } finally {
    exporting.value = false
  }
}

const totalPages = () => (props.size > 0 ? Math.ceil(props.totalElements / props.size) : 0)

/** Any filter narrowing the set — sorting is not a filter, so it does not count. */
const hasActiveFilters = computed(() => {
  const params = filters.toFilterParams()
  params.delete('sort')
  params.delete('direction')
  return params.toString() !== ''
})
</script>

<template>
  <div class="flex flex-col gap-3">
    <Message
      v-if="exportTruncated"
      data-testid="export-truncated-banner"
      severity="warn"
      :closable="false"
      role="alert"
    >
      {{ t('table.exportTruncated') }}
    </Message>
    <Message v-if="exportError" severity="error" :closable="false" role="alert">
      {{ exportError }}
    </Message>

    <div class="flex items-center justify-between gap-2">
      <!--
        A real heading, not a styled span. The page's whole outline used to be the shell's
        single `<h1>` — every section title on it, this one included, was a div — so there was
        no structure to navigate the page by. See `AppLayout`'s `<main>` note.

        Not a live region any more: `OpsView` announces the total once the new result has
        landed, so a facet click is read out as one sentence rather than as a bare "(12)".
      -->
      <h2 class="text-sm font-semibold text-surface-700">
        {{ t('table.heading') }}
        <span class="ml-1 font-normal text-surface-600" data-testid="incident-count">
          ({{ figure(totalElements) }})
        </span>
      </h2>
      <Button
        v-can="[['incident.ops'], 'export']"
        type="button"
        data-testid="export-button"
        icon="pi pi-file-export"
        size="small"
        class="min-h-11"
        :label="exporting ? t('table.exporting') : t('table.export')"
        :loading="exporting"
        :disabled="exporting"
        @click="onExport"
      />
    </div>

    <!--
      MOBILE (below md): one card per incident.

      The desktop grid used to render as-is at 390px. It did not clip — `overflow-x-auto` saw
      to that — but `max-w-md` on the subject cell wrapped every subject to four to six lines,
      so a 25-row page measured 5535px tall against 2101px on desktop, with everything right of
      Status reachable only by swiping sideways INSIDE the table while the page scrolled
      vertically underneath it. This is the screen an on-call engineer opens on a phone.

      The card carries what a triage decision needs — id, tracker, status, subject, priority,
      SLA band and aging — and the drawer, which is unchanged, still holds the full record. The
      table below is untouched and still owns every width from md up.

      Distinct `data-testid`s from the table's: both trees are in the DOM at every width (only
      one is displayed), so sharing an id would make every `getByTestId` ambiguous rather than
      viewport-aware.

      The `#<no>` in the card header is plain text, not a link: the whole card is already a
      button that opens the drawer, and a link nested inside it would be invalid and
      unreachable. The drawer's header carries the Redmine link.
    -->
    <ul class="flex flex-col gap-2 md:hidden" data-testid="incident-card-list">
      <li v-for="row in rows" :key="rowKey(row)" data-testid="incident-card">
        <button
          type="button"
          data-testid="incident-card-select"
          class="flex w-full flex-col gap-2 rounded-lg border border-surface-200 bg-surface-0 p-3 text-left transition-colors hover:border-primary-400 hover:bg-primary-50/40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
          :aria-label="t('table.openIncident', { subject: row.SUBJECT })"
          @click="emit('select', row)"
        >
          <span class="flex w-full items-center gap-2 text-xs text-surface-600">
            <span class="font-medium tabular-nums" data-testid="incident-card-number"
              >#{{ row.NO_INCIDENT }}</span
            >
            <span aria-hidden="true">·</span>
            <span class="truncate">{{ row.TRACKER }}</span>
            <span aria-hidden="true">·</span>
            <span class="truncate">{{ row.STATUS }}</span>
            <i
              class="pi pi-chevron-right ml-auto shrink-0 text-xs text-surface-400"
              aria-hidden="true"
            />
          </span>

          <span class="line-clamp-2 font-medium text-surface-900">{{ row.SUBJECT }}</span>

          <span class="flex w-full flex-wrap items-center gap-2">
            <Tag
              :severity="severityFor(PRIORITY_SEVERITY, row.PRIORITY)"
              :value="String(row.PRIORITY ?? '—')"
            />
            <Tag
              :severity="severityFor(SLA_SEVERITY, row.SLA_BUCKET)"
              :value="bucketLabel(row.SLA_BUCKET)"
            />
            <Tag
              v-if="row.HAS_DATE_REJECT"
              severity="danger"
              :value="t('table.dateIssue')"
              class="text-xs"
              :title="t('table.dateIssueTitle')"
            />
            <span
              class="ml-auto text-xs tabular-nums text-surface-600"
              :aria-label="agingDescription(row)"
            >
              {{ t('table.daysSuffix', { value: agingLabel(row) }) }}
            </span>
          </span>
        </button>
      </li>
      <li
        v-if="!loading && rows.length === 0"
        class="flex flex-col items-center gap-2 rounded-lg border border-surface-200 px-3 py-10 text-center text-surface-600"
        data-testid="incident-card-empty"
      >
        <i class="pi pi-inbox block text-2xl text-surface-300" aria-hidden="true" />
        {{ t('table.empty') }}
        <Button
          v-if="hasActiveFilters"
          type="button"
          variant="link"
          size="small"
          class="min-h-11"
          :label="t('filters.clearAll')"
          @click="filters.reset()"
        />
      </li>
    </ul>

    <!-- DESKTOP (md and up): the full grid. -->
    <div class="relative hidden md:block">
      <p
        v-if="moreToRight"
        class="mb-1 flex items-center justify-end gap-1 text-xs text-surface-600"
        data-testid="incident-table-scroll-hint"
      >
        {{ t('table.scrollHint') }}
        <i class="pi pi-arrow-right text-2xs" aria-hidden="true" />
      </p>
      <div
        ref="scroller"
        class="overflow-x-auto rounded-lg border border-surface-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
        tabindex="0"
        role="region"
        :aria-label="t('table.scrollRegion')"
        data-testid="incident-table-scroller"
        @scroll.passive="measureScroll"
      >
        <table class="w-full border-collapse text-sm">
          <thead class="bg-surface-50">
            <tr class="text-left">
              <!--
              First, because it is the one identifier every conversation about a ticket starts
              from ("which one? #4021"), and the column the operator copies into Redmine. The
              header wording is the stakeholders' own ("No Redmine") in both languages.
            -->
              <th
                class="px-3 py-2 font-semibold whitespace-nowrap text-surface-700"
                data-testid="col-no-redmine"
              >
                <span class="inline-flex items-center gap-2">
                  {{ t('table.noRedmine') }}
                  <InfoHint term="noRedmine" />
                </span>
              </th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.subject') }}</th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.tracker') }}</th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.status') }}</th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.priority') }}</th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.institusi') }}</th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.source') }}</th>
              <th class="px-3 py-2 font-semibold text-surface-700">{{ t('table.assignedTo') }}</th>
              <th class="px-3 py-2 font-semibold whitespace-nowrap text-surface-700">
                <span class="inline-flex items-center gap-2">
                  {{ t('table.sla') }}
                  <InfoHint term="slaColumn" />
                </span>
              </th>
              <!--
              "Aging / SLA (days)": the elapsed count on its own was not a judgement — see
              `agingLabel`. The header names both halves so `14 / 60` needs no legend.
            -->
              <th class="px-3 py-2 text-right font-semibold whitespace-nowrap text-surface-700">
                <span class="inline-flex items-center gap-2">
                  {{ t('table.agingSla') }}
                  <InfoHint term="aging" />
                </span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="rowKey(row)"
              data-testid="incident-row"
              class="group border-t border-surface-200 transition-colors hover:bg-primary-50/40"
            >
              <!--
              The number links out; the subject button opens the drawer. Two separate controls
              in two separate cells, so neither is nested inside the other. With no
              `VITE_REDMINE_BASE_URL` the number is plain text — a dead-but-honest cell beats a
              link to a placeholder host.
            -->
              <td
                class="px-3 py-0 whitespace-nowrap tabular-nums"
                data-testid="incident-no-redmine"
              >
                <a
                  v-if="redmineIssueUrl(row.NO_INCIDENT)"
                  :href="redmineIssueUrl(row.NO_INCIDENT)!"
                  target="_blank"
                  rel="noopener noreferrer"
                  data-testid="incident-redmine-link"
                  class="inline-flex min-h-11 items-center gap-1 font-medium text-primary-800 underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
                  :aria-label="t('table.openInRedmine', { no: redmineIssueId(row.NO_INCIDENT) })"
                >
                  {{ redmineIssueId(row.NO_INCIDENT) }}
                  <i class="pi pi-external-link text-2xs" aria-hidden="true" />
                </a>
                <span
                  v-else
                  class="inline-flex min-h-11 items-center text-surface-700"
                  data-testid="incident-redmine-number"
                  >{{ redmineIssueId(row.NO_INCIDENT) ?? '—' }}</span
                >
              </td>
              <!--
              `py-0` on the cell with the padding moved INTO the button is what makes the hit
              target real. Previously the button wrapped only the subject text and measured
              20px tall — under the 24px AA minimum and far under the 44px comfortable target —
              so on a dense table the operator had to hit a thin strip of text while the rest
              of the cell was dead space.
            -->
              <td class="max-w-md min-w-64 px-3 py-0">
                <button
                  type="button"
                  data-testid="incident-row-select"
                  class="flex min-h-11 w-full cursor-pointer items-center gap-2 py-2 text-left font-medium text-surface-900 hover:text-primary-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
                  :aria-label="t('table.openIncident', { subject: row.SUBJECT })"
                  @click="emit('select', row)"
                >
                  <!--
                  `min-w-0` + `overflow-wrap: anywhere`, and both halves are load-bearing.

                  Redmine subjects carry unbroken machine tokens — one fixture reads
                  `DisbursementLMR3/QR_STATIC_PAYMENT_NEW/QRPayment06/BAC04V3S6U00`. A flex item
                  defaults to `min-width: auto`, so the span refuses to shrink below that token's
                  min-content width and `max-w-md` on the cell cannot win; the column blew out by
                  104px and took the whole PAGE into horizontal scroll with it.

                  `overflow-wrap: anywhere` rather than Tailwind's `break-words`
                  (`overflow-wrap: break-word`): only `anywhere` reduces the intrinsic min-content
                  size, which is the measurement the table's column algorithm actually reads.
                -->
                  <span class="min-w-0 [overflow-wrap:anywhere]">{{ row.SUBJECT }}</span>
                  <!--
                  The affordance, made visible before the pointer arrives.

                  Moving the handler onto this button was right — a `<tr>` with a bare click
                  handler is unreachable by keyboard — but nothing in the row's RESTING state
                  said it opened anything, so the drawer was a feature you had to already know
                  about. Dimmed at rest, solid on row hover or keyboard focus.
                -->
                  <i
                    class="pi pi-chevron-right ml-auto shrink-0 text-xs text-surface-300 transition-colors group-hover:text-primary-700 group-focus-within:text-primary-700"
                    aria-hidden="true"
                  />
                </button>
                <Tag
                  v-if="row.HAS_DATE_REJECT"
                  severity="danger"
                  :value="t('table.dateIssue')"
                  class="ml-1 align-middle text-xs"
                  :title="t('table.dateIssueTitle')"
                />
              </td>
              <td class="px-3 py-2 whitespace-nowrap text-surface-700">{{ row.TRACKER }}</td>
              <td class="px-3 py-2 whitespace-nowrap text-surface-700">{{ row.STATUS }}</td>
              <td class="px-3 py-2 whitespace-nowrap">
                <Tag
                  :severity="severityFor(PRIORITY_SEVERITY, row.PRIORITY)"
                  :value="String(row.PRIORITY ?? '—')"
                />
              </td>
              <td
                class="px-3 py-2 whitespace-nowrap text-surface-700"
                data-testid="incident-institusi"
              >
                {{ row.INSTITUSI ?? '—' }}
              </td>
              <td
                class="px-3 py-2 whitespace-nowrap text-surface-700"
                data-testid="incident-source"
              >
                {{ row.EXTERNAL_INTERNAL ?? '—' }}
              </td>
              <td
                class="max-w-48 truncate px-3 py-2 text-surface-700"
                data-testid="incident-assignee"
                :title="String(row.ASSIGNED_TO ?? '')"
              >
                {{ row.ASSIGNED_TO ?? '—' }}
              </td>
              <td class="px-3 py-2 whitespace-nowrap">
                <Tag
                  :severity="severityFor(SLA_SEVERITY, row.SLA_BUCKET)"
                  :value="bucketLabel(row.SLA_BUCKET)"
                />
              </td>
              <td
                class="px-3 py-2 text-right tabular-nums whitespace-nowrap text-surface-700"
                data-testid="incident-aging"
                :aria-label="agingDescription(row)"
              >
                {{ agingLabel(row) }}
              </td>
            </tr>
            <tr v-if="!loading && rows.length === 0">
              <!--
              `data-testid` because THREE components on this view share an empty-state sentence
              (the heatmap, the SLA buckets and this table), so addressing the table's copy by
              its text is a strict-mode ambiguity, not a locator.
            -->
              <td
                class="px-3 py-10 text-center text-surface-600"
                colspan="10"
                data-testid="incident-table-empty"
              >
                <i class="pi pi-inbox mb-2 block text-2xl text-surface-300" aria-hidden="true" />
                {{ t('table.empty') }}
                <!-- The way out of an empty result, right where the empty result is. -->
                <div v-if="hasActiveFilters" class="mt-2">
                  <Button
                    type="button"
                    variant="link"
                    size="small"
                    class="min-h-11"
                    data-testid="incident-table-clear-filters"
                    :label="t('filters.clearAll')"
                    @click="filters.reset()"
                  />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <!-- Edge fade: more columns to the right. Decorative; the hint above says it in words. -->
      <span
        v-if="moreToRight"
        class="pointer-events-none absolute inset-y-0 right-0 w-8 rounded-r-lg bg-gradient-to-l from-surface-300/70 to-transparent"
        aria-hidden="true"
        data-testid="incident-table-scroll-fade"
      />
    </div>

    <div class="flex items-center justify-between text-sm" aria-live="polite">
      <span class="text-surface-600">
        {{ t('table.page', { page: figure(page + 1), pages: figure(Math.max(totalPages(), 1)) }) }}
      </span>
      <div class="flex items-center gap-2">
        <Button
          type="button"
          icon="pi pi-chevron-left"
          :label="t('table.prev')"
          size="small"
          class="min-h-11"
          variant="outlined"
          severity="secondary"
          :disabled="page <= 0"
          @click="emit('update:page', page - 1)"
        />
        <Button
          type="button"
          icon="pi pi-chevron-right"
          icon-pos="right"
          :label="t('table.next')"
          size="small"
          class="min-h-11"
          variant="outlined"
          severity="secondary"
          :disabled="page + 1 >= totalPages()"
          @click="emit('update:page', page + 1)"
        />
      </div>
    </div>
  </div>
</template>
