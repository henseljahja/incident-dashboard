<script setup lang="ts">
import { useI18n } from 'vue-i18n'

/**
 * The card every dashboard widget sits in, with the four states a data widget really has —
 * loading, failed, empty, ready — so none of them is an empty white rectangle. A failed widget
 * says so and offers a retry; it never renders a stale zero as if it were data.
 */
defineProps<{
  title: string
  /** One line under the title: how to read the figure, or the window it covers. */
  subtitle?: string
  loading?: boolean
  error?: boolean
  empty?: boolean
}>()

defineEmits<{ (e: 'retry'): void }>()

const { t } = useI18n()
</script>

<template>
  <section
    class="flex min-w-0 flex-col gap-3 rounded-xl border border-surface-200 bg-surface-0 p-4 shadow-sm"
    :aria-busy="loading ? 'true' : 'false'"
  >
    <header class="flex flex-wrap items-start justify-between gap-2">
      <div class="min-w-0">
        <h2 class="text-sm font-semibold text-surface-800">{{ title }}</h2>
        <p v-if="subtitle" class="text-xs text-surface-600">{{ subtitle }}</p>
      </div>
      <slot name="actions" />
    </header>

    <div v-if="loading" class="flex flex-col gap-2" data-testid="panel-loading">
      <div class="h-4 w-2/3 animate-pulse rounded bg-surface-200"></div>
      <div class="h-4 w-full animate-pulse rounded bg-surface-200"></div>
      <div class="h-4 w-5/6 animate-pulse rounded bg-surface-200"></div>
    </div>
    <div
      v-else-if="error"
      class="flex items-center gap-3 text-sm"
      role="alert"
      data-testid="panel-error"
    >
      <span class="text-[var(--chart-error-text)]">{{ t('insights.state.error') }}</span>
      <button
        type="button"
        class="min-h-11 rounded border border-surface-300 px-3"
        @click="$emit('retry')"
      >
        {{ t('insights.state.retry') }}
      </button>
    </div>
    <p v-else-if="empty" class="text-sm text-surface-600" data-testid="panel-empty">
      {{ t('insights.state.empty') }}
    </p>
    <slot v-else />
  </section>
</template>
