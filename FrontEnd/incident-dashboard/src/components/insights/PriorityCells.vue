<script setup lang="ts">
import { useI18n } from 'vue-i18n'

import type { PriorityCounts } from '@/apis/insights'
import { PRIORITY_KEYS, PRIORITY_PILL } from '@/utils/priority'
import { formatCount } from '@/utils/number'

/**
 * The per-priority split used by every summary table (Critical / High / Medium / Low, plus
 * Other only when something falls outside the ladder, so the cells always sum to the row total).
 * A zero renders as a quiet "0", not as an empty cell.
 */
const props = defineProps<{ counts: PriorityCounts }>()
const { t, locale } = useI18n()

function visible(): (typeof PRIORITY_KEYS)[number][] {
  return PRIORITY_KEYS.filter((key) => key !== 'other' || props.counts.other > 0)
}
</script>

<template>
  <span class="inline-flex gap-1" data-testid="priority-cells">
    <span
      v-for="key in visible()"
      :key="key"
      class="min-w-7 rounded px-1.5 py-0.5 text-center text-xs tabular-nums"
      :style="
        counts[key] > 0
          ? { backgroundColor: PRIORITY_PILL[key].bg, color: PRIORITY_PILL[key].fg }
          : {}
      "
      :class="counts[key] > 0 ? 'font-semibold' : 'text-surface-500'"
      :title="t(`insights.priority.${key}`)"
      :data-priority="key"
    >
      {{ formatCount(counts[key], locale) }}
    </span>
  </span>
</template>
