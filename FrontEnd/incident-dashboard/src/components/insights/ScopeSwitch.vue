<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'

import { SCOPES, useFiltersStore, type Scope } from '@/stores/filters'

/**
 * Which ticket group the page describes. "All" is the null scope; switching resets the filters
 * (the store's `setScope` contract) so a Problem-only filter never leaks onto Incident.
 */
const { t } = useI18n()
const filters = useFiltersStore()
const { scope } = storeToRefs(filters)

const options: { value: Scope | null; label: string }[] = [
  { value: null, label: 'all' },
  ...SCOPES.map((s) => ({ value: s as Scope | null, label: s })),
]
</script>

<template>
  <div
    class="inline-flex rounded-lg border border-surface-300 bg-surface-0 p-0.5"
    role="group"
    :aria-label="t('insights.scope.label')"
  >
    <button
      v-for="option in options"
      :key="String(option.value)"
      type="button"
      class="min-h-10 rounded-md px-3 text-sm"
      :class="scope === option.value ? 'bg-primary text-primary-contrast' : 'text-surface-700'"
      :aria-pressed="scope === option.value"
      :data-testid="`scope-${option.label}`"
      @click="filters.setScope(option.value)"
    >
      {{ t(`insights.scope.${option.label}`) }}
    </button>
  </div>
</template>
