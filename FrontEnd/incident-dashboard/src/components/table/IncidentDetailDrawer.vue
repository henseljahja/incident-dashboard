<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { IncidentRow } from '@/apis/incidents'
import { redmineIssueId, redmineIssueUrl } from '@/utils/redmine'

const props = defineProps<{
  visible: boolean
  row: IncidentRow | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
}>()

const { t, te } = useI18n()

/** The Redmine issue number, as shown. See `utils/redmine.ts` for why links never come from row data. */
const issueId = computed(() => redmineIssueId(props.row?.NO_INCIDENT))

/**
 * When `VITE_REDMINE_BASE_URL` is unset the link is replaced by an explanation rather than
 * pointed at a placeholder host.
 *
 * There used to be a `?? 'https://redmine.example.com'` fallback here, and the demo compose
 * file did not set the variable — so the single call to action in a strictly read-only product
 * silently linked to a domain nobody owns. A dead-but-honest control is better than a live
 * link to the wrong place: this way a misconfigured deployment is visible at a glance instead
 * of looking like it works until someone clicks it.
 */
const redmineUrl = computed(() => redmineIssueUrl(props.row?.NO_INCIDENT))

/** `SLA_BUCKET` is the one mart enum in this drawer; see `IncidentTable.bucketLabel`. */
const BUCKET_KEYS: Record<string, string> = {
  Breached: 'breached',
  '80-100%': 'p80to100',
  '50-80%': 'p50to80',
  '0-50%': 'p0to50',
  Unknown: 'unknown',
}
const bucketLabel = computed(() => {
  const value = props.row?.SLA_BUCKET
  if (value === null || value === undefined || value === '') return value
  const key = BUCKET_KEYS[String(value)]
  return key && te(`charts.bucket.${key}`) ? t(`charts.bucket.${key}`) : String(value)
})

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <Drawer
    :visible="visible"
    position="right"
    class="!w-full md:!w-[28rem]"
    :aria-label="t('drawer.title')"
    @update:visible="close"
  >
    <!--
      The ticket number IS the header, and it is the Redmine link (AC5.4): the first thing an
      operator does with a ticket is say its number or open it in Redmine, and both now happen
      in the same place. Plain `#<no>` when the deployment has no base URL.
    -->
    <template #header>
      <span class="flex flex-col">
        <span class="text-xs text-surface-600 uppercase">{{ t('drawer.title') }}</span>
        <a
          v-if="redmineUrl"
          :href="redmineUrl"
          target="_blank"
          rel="noopener noreferrer"
          data-testid="drawer-redmine-number"
          class="inline-flex min-h-11 items-center gap-1.5 text-lg font-semibold text-primary-800 tabular-nums underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
          :aria-label="t('table.openInRedmine', { no: issueId })"
          >#{{ issueId }}<i class="pi pi-external-link text-xs" aria-hidden="true"
        /></a>
        <span
          v-else-if="issueId !== null"
          class="text-lg font-semibold tabular-nums"
          data-testid="drawer-number"
          >#{{ issueId }}</span
        >
      </span>
    </template>

    <div v-if="row" class="flex flex-col gap-3 text-sm">
      <!-- Strictly read-only: no edit/save affordance anywhere in this drawer. Any action
           that would mutate the incident deep-links to Redmine, the permanent write side. -->
      <div>
        <div class="text-xs uppercase text-surface-600">{{ t('drawer.subject') }}</div>
        <div class="font-medium">{{ row.SUBJECT }}</div>
        <span
          v-if="row.HAS_DATE_REJECT"
          class="mt-1 inline-block rounded bg-red-100 px-1 text-xs text-red-800"
          :title="t('table.dateIssueTitle')"
        >
          {{ t('table.dateIssue') }}
        </span>
      </div>

      <div class="grid grid-cols-2 gap-2">
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.tracker') }}</div>
          <div>{{ row.TRACKER }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.status') }}</div>
          <div>{{ row.STATUS }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.priority') }}</div>
          <div>{{ row.PRIORITY }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.stage') }}</div>
          <div>{{ row.STATUS_STAGE }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.slaBucket') }}</div>
          <div>{{ bucketLabel }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.aging') }}</div>
          <div>{{ row.AGING_NEW }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.institusi') }}</div>
          <div>{{ row.INSTITUSI }}</div>
        </div>
        <div>
          <div class="text-xs uppercase text-surface-600">{{ t('drawer.category') }}</div>
          <div>{{ row.CATEGORY_INCIDENT }}</div>
        </div>
      </div>

      <div v-if="row.WORKAROUND">
        <div class="text-xs uppercase text-surface-600">{{ t('drawer.workaround') }}</div>
        <div class="whitespace-pre-wrap">{{ row.WORKAROUND }}</div>
      </div>
      <div v-if="row.ROOTCAUSE">
        <div class="text-xs uppercase text-surface-600">{{ t('drawer.rootCause') }}</div>
        <div class="whitespace-pre-wrap">{{ row.ROOTCAUSE }}</div>
      </div>

      <!--
        The product's only call to action, so it uses the primary Button like every other
        primary action. It was `bg-surface-800` — the one near-black control in a cyan UI,
        which read as a secondary/cancel affordance.
      -->
      <a
        v-if="redmineUrl"
        v-ripple
        :href="redmineUrl"
        target="_blank"
        rel="noopener noreferrer"
        data-testid="open-in-redmine"
        class="mt-2 inline-flex min-h-11 items-center justify-center gap-2 rounded-md bg-primary px-3 py-1.5 text-center font-medium text-primary-contrast transition-colors hover:bg-primary-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-600"
      >
        <i class="pi pi-external-link" aria-hidden="true" />
        {{ t('drawer.openInRedmine') }}
      </a>
      <div
        v-else-if="issueId !== null"
        data-testid="open-in-redmine-unconfigured"
        class="mt-2 rounded-md border border-surface-300 bg-surface-100 px-3 py-2 text-xs text-surface-600"
      >
        <i class="pi pi-info-circle mr-1" aria-hidden="true" />
        <i18n-t keypath="drawer.redmineUnconfigured" tag="span" scope="global">
          <template #name><code>VITE_REDMINE_BASE_URL</code></template>
        </i18n-t>
      </div>
    </div>
  </Drawer>
</template>
