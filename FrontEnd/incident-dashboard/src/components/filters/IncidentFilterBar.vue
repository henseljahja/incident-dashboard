<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatDate, parseDate } from '@/utils/date'

import { SCOPE_TRACKERS, useFiltersStore } from '@/stores/filters'

/**
 * Static option lists mirrored from the backend seed config (`CFG_TRACKER_SCOPE`,
 * `CFG_STATUS_STAGE_MAP`, `CFG_SLA_POLICY`) rather than fetched, since this task does not add
 * a metadata endpoint. A later task introducing one should replace these with a query.
 *
 * These are DATA values — the exact strings the backend filters on — so they are never
 * translated; only the controls around them are.
 */
const ALL_TRACKER_OPTIONS = ['Incident', 'Problem', 'SR - Infra', 'SR - BAU', 'SR - Patch']
const STAGE_OPTIONS = ['New', 'Triage', 'In Progress', 'Blocked', 'Resolved', 'Closed', 'Cancelled']
const PRIORITY_OPTIONS = ['Low', 'Medium', 'High', 'Critical']

const { t } = useI18n()

const HAS_WORKAROUND_OPTIONS = computed<{ label: string; value: boolean | null }[]>(() => [
  { label: t('common.any'), value: null },
  { label: t('common.yes'), value: true },
  { label: t('common.no'), value: false },
])

const filters = useFiltersStore()
const {
  dateFrom,
  dateTo,
  tracker,
  stage,
  priority,
  institusi,
  category,
  hasWorkaround,
  status,
  sla,
  matrixMissing,
  scope,
  search,
  source,
  assignedTo,
} = storeToRefs(filters)

/** Data values of the External/Internal custom field (cf 349), so never translated. */
const SOURCE_OPTIONS = ['External', 'Internal']

/** The search box commits on Enter or blur, so each keystroke is not a request. */
const searchDraft = ref(search.value)
watch(search, (value) => {
  searchDraft.value = value
})
function commitSearch() {
  if (searchDraft.value.trim() === search.value) return
  search.value = searchDraft.value.trim()
  resetPage()
}

/**
 * Only the page's own trackers: the Service Request page offers its three, Incident and Problem
 * hold one each. With a single option the control would be a one-item picker that can only
 * narrow to what the page already shows, so it is hidden instead.
 */
const trackerOptions = computed<readonly string[]>(() =>
  scope.value ? SCOPE_TRACKERS[scope.value] : ALL_TRACKER_OPTIONS,
)
const showTracker = computed(() => trackerOptions.value.length > 1)

function toDate(value: string | null): Date | null {
  return parseDate(value)
}

function fromDate(value: Date | Date[] | null): string | null {
  if (!value || Array.isArray(value)) return null
  // Local calendar components, never toISOString() — see src/utils/date.ts.
  return formatDate(value)
}

function resetPage() {
  filters.page = 0
}

function clearAll() {
  filters.reset()
}

/**
 * Three filters inline, five behind a disclosure.
 *
 * All eight used to sit permanently above the table, nearly all empty on arrival — roughly a
 * third of the first screenful spent on controls nobody had touched, pushing the incident rows
 * (the working surface) below the fold. Date range and tracker are what an operator actually
 * reaches for first; the rest are narrowing tools used once a shape is already visible.
 *
 * The count on the toggle is the load-bearing part: collapsing controls is only safe if the
 * state they hold stays legible, so the badge says how many HIDDEN filters are active and the
 * chip row below spells out every active filter regardless of which panel owns it.
 */
const advancedOpen = ref(false)

const advancedActiveCount = computed(
  () =>
    stage.value.length +
    priority.value.length +
    institusi.value.length +
    category.value.length +
    source.value.length +
    assignedTo.value.length +
    (hasWorkaround.value === null ? 0 : 1),
)

/**
 * A pasted or bookmarked URL must not hide its own filters.
 *
 * `OpsView` restores filter state from the query string on mount, which lands AFTER this
 * component is created. Without this the panel stayed shut over live values — the chips would
 * still name them, but "Priority: High" with no visible Priority control reads as a filter you
 * cannot get at. Opens once, on the first arrival of hidden state; never forces it shut again,
 * because that would fight a user who deliberately collapsed it.
 */
watch(
  advancedActiveCount,
  (count) => {
    if (count > 0) advancedOpen.value = true
  },
  { immediate: true },
)

interface ActiveFilter {
  /** Stable across renders so `:key` does not thrash while a list is being edited. */
  key: string
  label: string
  remove: () => void
}

function removeFrom(list: { value: string[] }, item: string) {
  list.value = list.value.filter((value) => value !== item)
  resetPage()
}

/**
 * Every active filter, named in full, as a removable chip.
 *
 * This is what replaces "read the eight controls and work out which ones are set". It also
 * covers the collapsed panel: a filter is never invisible just because its control is.
 */
const activeFilters = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []

  // The KPI tiles' facets come first: they are the most recent, most deliberate narrowing, and
  // the chip is the only place on screen that says the table is no longer the whole period.
  if (status.value) {
    chips.push({
      key: `status:${status.value}`,
      label: t(`filters.chip.status.${status.value}`),
      remove: () => filters.toggleStatus(status.value!),
    })
  }
  if (sla.value) {
    chips.push({
      key: `sla:${sla.value}`,
      label: t(`filters.chip.sla.${sla.value}`),
      remove: () => filters.toggleSla(sla.value!),
    })
  }

  if (search.value) {
    chips.push({
      key: `search:${search.value}`,
      label: t('filters.chip.field', { name: t('filters.search'), value: search.value }),
      remove: () => {
        search.value = ''
        resetPage()
      },
    })
  }

  // Set by the matrix's "N missing" link. A chip, so the narrowing is named and removable.
  if (matrixMissing.value) {
    chips.push({
      key: 'matrixMissing',
      label: t('filters.chip.matrixMissing'),
      remove: () => {
        matrixMissing.value = false
        resetPage()
      },
    })
  }

  if (dateFrom.value) {
    chips.push({
      key: `dateFrom:${dateFrom.value}`,
      label: t('filters.chip.from', { value: dateFrom.value }),
      remove: () => {
        dateFrom.value = null
        resetPage()
      },
    })
  }
  if (dateTo.value) {
    chips.push({
      key: `dateTo:${dateTo.value}`,
      label: t('filters.chip.to', { value: dateTo.value }),
      remove: () => {
        dateTo.value = null
        resetPage()
      },
    })
  }

  const lists: { key: string; name: string; ref: { value: string[] } }[] = [
    { key: 'tracker', name: t('filters.tracker'), ref: tracker },
    { key: 'stage', name: t('filters.stage'), ref: stage },
    { key: 'priority', name: t('filters.priority'), ref: priority },
    { key: 'institusi', name: t('filters.institusi'), ref: institusi },
    { key: 'category', name: t('filters.category'), ref: category },
    { key: 'source', name: t('filters.source'), ref: source },
    { key: 'assignedTo', name: t('filters.assignedTo'), ref: assignedTo },
  ]
  for (const { key, name, ref: list } of lists) {
    for (const value of list.value) {
      chips.push({
        key: `${key}:${value}`,
        label: t('filters.chip.field', { name, value }),
        remove: () => removeFrom(list, value),
      })
    }
  }

  if (hasWorkaround.value !== null) {
    chips.push({
      key: `hasWorkaround:${hasWorkaround.value}`,
      label: t('filters.chip.hasWorkaround', {
        value: hasWorkaround.value ? t('common.yes') : t('common.no'),
      }),
      remove: () => {
        hasWorkaround.value = null
        resetPage()
      },
    })
  }

  return chips
})
</script>

<template>
  <div
    class="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-50 p-4"
    role="search"
    :aria-label="t('filters.region')"
  >
    <div class="flex flex-wrap items-end gap-3">
      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-date-from">{{
          t('filters.from')
        }}</label>
        <DatePicker
          :model-value="toDate(dateFrom)"
          date-format="yy-mm-dd"
          show-icon
          input-id="filter-date-from"
          input-class="min-h-11"
          :pt="{ dropdown: { class: 'min-h-11 min-w-11' } }"
          @update:model-value="
            (v: unknown) => {
              dateFrom = fromDate(v as Date | Date[] | null)
              resetPage()
            }
          "
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-date-to">{{
          t('filters.to')
        }}</label>
        <DatePicker
          :model-value="toDate(dateTo)"
          date-format="yy-mm-dd"
          show-icon
          input-id="filter-date-to"
          input-class="min-h-11"
          :pt="{ dropdown: { class: 'min-h-11 min-w-11' } }"
          @update:model-value="
            (v: unknown) => {
              dateTo = fromDate(v as Date | Date[] | null)
              resetPage()
            }
          "
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-search">{{
          t('filters.search')
        }}</label>
        <InputText
          id="filter-search"
          v-model="searchDraft"
          type="search"
          class="min-h-11 min-w-56"
          :placeholder="t('filters.searchPlaceholder')"
          data-testid="filter-search"
          @keydown.enter="commitSearch"
          @blur="commitSearch"
        />
      </div>

      <div v-if="showTracker" class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-tracker">{{
          t('filters.tracker')
        }}</label>
        <MultiSelect
          input-id="filter-tracker"
          v-model="tracker"
          :options="[...trackerOptions]"
          :placeholder="t('filters.anyTracker')"
          display="chip"
          class="min-w-48"
          @update:model-value="resetPage"
        />
      </div>

      <!--
        Real Buttons, not hand-rolled elements. "Clear filters" used to be a bare `<button>` with
        ad-hoc border/padding classes, so it sat at a different weight and radius from every
        other control in the product while doing the same kind of job.
      -->
      <div class="ml-auto flex items-end gap-2">
        <Button
          type="button"
          data-testid="filter-advanced-toggle"
          severity="secondary"
          variant="outlined"
          size="small"
          class="min-h-11"
          :icon="advancedOpen ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
          icon-pos="right"
          :label="advancedOpen ? t('filters.fewerFilters') : t('filters.moreFilters')"
          :badge="advancedActiveCount > 0 ? String(advancedActiveCount) : undefined"
          :aria-expanded="advancedOpen"
          aria-controls="filter-advanced-panel"
          @click="advancedOpen = !advancedOpen"
        />
        <Button
          type="button"
          data-testid="filter-clear-all"
          severity="secondary"
          variant="outlined"
          size="small"
          class="min-h-11"
          icon="pi pi-filter-slash"
          :label="t('filters.clearAll')"
          :disabled="activeFilters.length === 0"
          @click="clearAll"
        />
      </div>
    </div>

    <div
      v-if="advancedOpen"
      id="filter-advanced-panel"
      data-testid="filter-advanced-panel"
      class="flex flex-wrap items-end gap-3 border-t border-surface-200 pt-3"
    >
      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-stage">{{
          t('filters.stage')
        }}</label>
        <MultiSelect
          input-id="filter-stage"
          v-model="stage"
          :options="STAGE_OPTIONS"
          :placeholder="t('filters.anyStage')"
          display="chip"
          class="min-w-48"
          @update:model-value="resetPage"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-priority">{{
          t('filters.priority')
        }}</label>
        <MultiSelect
          input-id="filter-priority"
          v-model="priority"
          :options="PRIORITY_OPTIONS"
          :placeholder="t('filters.anyPriority')"
          display="chip"
          class="min-w-48"
          @update:model-value="resetPage"
        />
      </div>

      <!--
        The commit rule is stated in a hint that STAYS, not in a placeholder that leaves.
 
        "Type and press Enter" lived in the placeholder, which disappears on the first keystroke
        — exactly when the reader needs it — so the one non-obvious interaction on this bar was
        documented only while it was not yet relevant. `aria-describedby` puts the same sentence
        in the accessible description rather than leaving it as nearby text a screen reader
        never associates with the field.
      -->
      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-institusi">{{
          t('filters.institusi')
        }}</label>
        <InputChips
          input-id="filter-institusi"
          v-model="institusi"
          aria-describedby="filter-institusi-hint"
          class="min-w-48"
          @update:model-value="resetPage"
        />
        <small id="filter-institusi-hint" class="text-xs text-surface-600">
          {{ t('filters.institusiHint') }}
        </small>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-category">{{
          t('filters.category')
        }}</label>
        <InputChips
          input-id="filter-category"
          v-model="category"
          aria-describedby="filter-category-hint"
          class="min-w-48"
          @update:model-value="resetPage"
        />
        <small id="filter-category-hint" class="text-xs text-surface-600">
          {{ t('filters.categoryHint') }}
        </small>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-source">{{
          t('filters.source')
        }}</label>
        <MultiSelect
          input-id="filter-source"
          v-model="source"
          :options="SOURCE_OPTIONS"
          :placeholder="t('filters.anySource')"
          display="chip"
          class="min-w-48"
          @update:model-value="resetPage"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs uppercase text-surface-600" for="filter-assigned-to">{{
          t('filters.assignedTo')
        }}</label>
        <InputChips
          input-id="filter-assigned-to"
          v-model="assignedTo"
          aria-describedby="filter-assigned-to-hint"
          class="min-w-48"
          @update:model-value="resetPage"
        />
        <small id="filter-assigned-to-hint" class="text-xs text-surface-600">
          {{ t('filters.assignedToHint') }}
        </small>
      </div>

      <div class="flex flex-col gap-1">
        <!--
          This one control is labelled with `aria-labelledby`, not `<label for>`, and the
          difference is not cosmetic. PrimeVue's Select renders its `input-id` onto a
          `<span role="combobox" tabindex="0">` rather than an `<input>` — so `<label for>`
          pointed at a non-form element and did nothing: no accessible name, and clicking the
          label did not focus the control. The other seven filters use MultiSelect/DatePicker/
          InputChips, whose `input-id` really does land on an `<input>`, so they are correct
          as-is. Verified against the rendered DOM, not assumed.
        -->
        <span id="filter-has-workaround-label" class="text-xs uppercase text-surface-600">
          {{ t('filters.hasWorkaround') }}
        </span>
        <Select
          input-id="filter-has-workaround"
          aria-labelledby="filter-has-workaround-label"
          v-model="hasWorkaround"
          :options="HAS_WORKAROUND_OPTIONS"
          option-label="label"
          option-value="value"
          class="min-w-32"
          @update:model-value="resetPage"
        />
      </div>
    </div>

    <!--
      What is actually filtering, in words, removable one at a time.
 
      Reading filter state used to mean inspecting eight controls for a non-empty value. Each
      chip names its field AND its value, so the set reads as a sentence, and each removes only
      itself — narrowing by five values and wanting to drop one of them no longer means
      re-picking the other four.
    -->
    <div
      v-if="activeFilters.length > 0"
      class="flex flex-wrap items-center gap-2 border-t border-surface-200 pt-3"
      data-testid="active-filters"
    >
      <span class="text-xs uppercase text-surface-600">{{ t('filters.active') }}</span>
      <!--
        The pill stays 24px tall; the `after:` layer extends its HIT area to 44px vertically, so
        a thumb can remove a chip without the row growing to fit a full-height target.
      -->
      <button
        v-for="chip in activeFilters"
        :key="chip.key"
        type="button"
        data-testid="active-filter-chip"
        :data-chip="chip.key"
        class="relative flex min-h-6 cursor-pointer items-center gap-1.5 rounded-full border border-primary-200 bg-primary-50 py-1 pr-2 pl-3 text-xs text-primary-800 transition-colors after:absolute after:-inset-y-2.5 after:inset-x-0 hover:border-primary-400 hover:bg-primary-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600 motion-reduce:transition-none"
        :aria-label="t('filters.removeChip', { label: chip.label })"
        @click="chip.remove()"
      >
        {{ chip.label }}
        <i class="pi pi-times text-2xs" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>
