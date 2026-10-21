<script setup lang="ts">
import { useMutation } from '@tanstack/vue-query'
import Button from 'primevue/button'
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchExplanation, type ExplainTopic } from '@/apis/insights'
import { useAiStatus } from '@/composables/useAiStatus'
import { useFiltersStore } from '@/stores/filters'

/**
 * "Explain this" for a page of figures (Forecast, Alerts, RCA). One click = one model call; nothing
 * fires on load. Renders nothing at all unless the server has a real narrator, so with AI off the
 * page is unchanged. The text appears in a polite live region so a screen reader announces it, and
 * a reading that no longer matches the filters on screen is marked as such rather than left to
 * pass for current.
 */
const props = defineProps<{
  topic: ExplainTopic
  /** Forecast horizon in days; part of what the reading is about. */
  horizonDays?: number
}>()

const { t, locale } = useI18n()
const filters = useFiltersStore()
const { narrating } = useAiStatus()

const lang = () => (locale.value === 'id' ? 'id' : 'en')
const headingId = useId()
const button = ref<{ $el: HTMLElement } | null>(null)
const mutation = useMutation({
  mutationFn: () =>
    fetchExplanation(props.topic, filters.toBaseFilterParams(), lang(), props.horizonDays),
})

// The button is disabled while the call runs, which drops keyboard focus to <body>. Once it is
// enabled again, hand focus back -- but only if it was dropped, never taking it from wherever
// the user has since moved.
watch(
  () => mutation.isPending.value,
  (pending, wasPending) => {
    if (pending || !wasPending) return
    const active = document.activeElement
    if (active === document.body || active === null) button.value?.$el.focus()
  },
  { flush: 'post' },
)

/** The inputs the current reading was written for; a change makes it stale. */
const inputKey = computed(
  () => `${filters.toBaseFilterParams().toString()}|${props.horizonDays ?? ''}|${lang()}`,
)
const writtenKey = ref('')
function run() {
  if (mutation.isPending.value) return
  writtenKey.value = inputKey.value
  mutation.mutate()
}
const stale = computed(() => mutation.isSuccess.value && writtenKey.value !== inputKey.value)
watch(
  () => props.topic,
  () => mutation.reset(),
)

/** Bullets from the model's text: strips list markers, drops empty lines. */
const bullets = computed(() => {
  const text = mutation.data.value?.narrative
  if (!text) return []
  return text
    .split('\n')
    .map((l) => l.replace(/^\s*(?:[-*•]|\d+[.)])\s*/, '').trim())
    .filter(Boolean)
})
const unavailable = computed(() => mutation.isSuccess.value && bullets.value.length === 0)
/** Read out by the always-present status region; empty when there is nothing new to say. */
const statusText = computed(() => {
  if (mutation.isPending.value) return t('insights.explain.working')
  if (mutation.isError.value) return t('insights.explain.error')
  if (unavailable.value) return t('insights.explain.unavailable')
  if (bullets.value.length) return t('insights.explain.ready', { n: bullets.value.length })
  return ''
})
const provider = computed(() => mutation.data.value?.provider ?? '')
</script>

<template>
  <section
    v-if="narrating"
    class="flex flex-col gap-3 rounded-xl border border-surface-200 border-l-4 border-l-primary bg-surface-0 p-4 shadow-sm"
    :aria-labelledby="headingId"
    data-testid="explain-card"
  >
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex min-w-0 items-start gap-2.5">
        <span class="pi pi-sparkles mt-0.5 text-primary" aria-hidden="true"></span>
        <div class="min-w-0">
          <h2 :id="headingId" class="text-sm font-semibold text-surface-800">
            {{ t('insights.explain.title') }}
          </h2>
          <p class="text-xs text-surface-600">{{ t('insights.explain.hint') }}</p>
        </div>
      </div>
      <Button
        ref="button"
        :label="
          mutation.isPending.value
            ? t('insights.explain.working')
            : mutation.isSuccess.value
              ? t('insights.explain.again')
              : t('insights.explain.action')
        "
        icon="pi pi-sparkles"
        size="small"
        :outlined="mutation.isSuccess.value"
        :loading="mutation.isPending.value"
        class="min-h-11 min-w-36"
        data-testid="explain-button"
        @click="run"
      />
    </div>

    <!-- Present from first render so assistive tech registers it before text arrives. -->
    <p class="sr-only" role="status" data-testid="explain-status">{{ statusText }}</p>

    <div v-if="mutation.isPending.value" class="flex flex-col gap-2" data-testid="explain-loading">
      <div class="h-4 w-3/4 motion-safe:animate-pulse rounded bg-surface-200"></div>
      <div class="h-4 w-full motion-safe:animate-pulse rounded bg-surface-200"></div>
      <div class="h-4 w-2/3 motion-safe:animate-pulse rounded bg-surface-200"></div>
    </div>

    <div
      v-else-if="mutation.isError.value || unavailable"
      class="flex flex-wrap items-center gap-3 text-sm"
      data-testid="explain-error"
    >
      <span
        class="text-[var(--chart-error-text)]"
        :role="mutation.isError.value ? 'alert' : 'status'"
      >
        {{
          mutation.isError.value ? t('insights.explain.error') : t('insights.explain.unavailable')
        }}
      </span>
      <Button
        :label="t('insights.explain.retry')"
        size="small"
        severity="secondary"
        outlined
        class="min-h-11"
        data-testid="explain-retry"
        @click="run"
      />
    </div>

    <div v-else-if="bullets.length" data-testid="explain-result">
      <ul class="flex list-disc flex-col gap-1.5 break-words pl-5 text-sm text-surface-800">
        <li v-for="(b, i) in bullets" :key="i">{{ b }}</li>
      </ul>
      <p v-if="stale" class="mt-2 text-xs font-medium text-[#7a5200]" data-testid="explain-stale">
        {{ t('insights.explain.stale') }}
      </p>
      <p class="mt-2 text-xs text-surface-600" data-testid="explain-privacy">
        {{ t('insights.explain.privacy', { provider }) }}
      </p>
    </div>
  </section>
</template>
