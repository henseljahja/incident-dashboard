<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { fetchFreshness, type FreshnessDto } from '@/apis/metrics'
import InfoHint from '@/components/common/InfoHint.vue'
import { formatDisplayDateTime } from '@/utils/date'
import { useStaleReason } from '@/utils/freshness'

const POLL_INTERVAL_MS = 30_000

const { t, locale } = useI18n()
const staleReasonLabel = useStaleReason()

const freshness = ref<FreshnessDto | null>(null)
const error = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | undefined

async function poll() {
  try {
    freshness.value = await fetchFreshness()
    error.value = null
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('common.freshness.error')
  }
}

onMounted(() => {
  void poll()
  timer = setInterval(() => void poll(), POLL_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

/** WIB, in the UI language — see `utils/date.ts`. `toLocaleString()` used the browser's zone and language. */
function formatTimestamp(value: string | null): string {
  return formatDisplayDateTime(value, locale.value) ?? t('common.never')
}
</script>

<template>
  <div data-testid="freshness-indicator" class="flex items-center gap-1">
    <div
      v-if="freshness && !freshness.stale"
      data-testid="freshness-live"
      class="flex items-center gap-2 text-sm text-surface-600"
    >
      <span class="inline-block h-2 w-2 rounded-full bg-emerald-500" aria-hidden="true"></span>
      <span>{{ t('common.freshness.live', { time: formatTimestamp(freshness.lastRunAt) }) }}</span>
    </div>
    <div
      v-else-if="freshness && freshness.stale"
      data-testid="freshness-stale-banner"
      role="alert"
      class="flex items-center gap-2 rounded border border-amber-400 bg-amber-50 px-3 py-2 text-sm text-amber-900"
    >
      <span class="inline-block h-2 w-2 rounded-full bg-amber-500" aria-hidden="true"></span>
      <span>
        {{ t('common.freshness.stale', { time: formatTimestamp(freshness.lastRunAt) }) }}
        <template v-if="freshness.staleReason"
          >({{ staleReasonLabel(freshness.staleReason) }})</template
        >.
      </span>
    </div>
    <div v-else-if="error" data-testid="freshness-error" role="alert" class="text-sm text-red-700">
      {{ error }}
    </div>
    <InfoHint term="freshness" class="text-surface-600" />
  </div>
</template>
