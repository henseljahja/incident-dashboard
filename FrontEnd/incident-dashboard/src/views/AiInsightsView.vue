<script setup lang="ts">
import { useI18n } from 'vue-i18n'

import AppLayout from '@/layout/AppLayout.vue'

/**
 * Placeholder for AI Insights. The AI provider and its data-handling terms are not decided yet
 * (RD 22524: "to be confirmed"), so the route shows what is pending instead of a working page.
 *
 * The built page (local summary, Q&A, search, patterns) is kept as `AiInsightsLiveView` and is
 * switched on with `VITE_AI_INSIGHTS_LIVE=true` at build time — see `router/index.ts`.
 */
const { t } = useI18n()
</script>

<template>
  <AppLayout>
    <section
      class="mx-auto flex max-w-xl flex-col items-center gap-3 rounded border border-dashed border-surface-300 bg-surface-0 px-6 py-12 text-center"
      data-testid="ai-placeholder"
    >
      <h2 class="text-lg font-semibold text-surface-900">
        {{ t('insights.ai.placeholder.title') }}
      </h2>
      <p class="text-sm text-surface-700">{{ t('insights.ai.placeholder.body') }}</p>
    </section>
  </AppLayout>
</template>
