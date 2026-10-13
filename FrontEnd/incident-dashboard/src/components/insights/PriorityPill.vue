<script setup lang="ts">
import { computed } from 'vue'

import { PRIORITY_PILL, priorityKey } from '@/utils/priority'

/** A priority as a coloured word. The word is always present, so colour never carries it alone. */
const props = defineProps<{ priority: string | null | undefined }>()
const style = computed(() => PRIORITY_PILL[priorityKey(props.priority)])
</script>

<template>
  <span
    class="inline-block rounded-full px-2 py-0.5 text-xs font-medium"
    :style="{ backgroundColor: style.bg, color: style.fg }"
    data-testid="priority-pill"
  >
    {{ priority || '—' }}
  </span>
</template>
