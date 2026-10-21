<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import Button from 'primevue/button'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { ApiError } from '@/apis/client'
import {
  CONFIG_QUERY_KEY,
  SEVERITIES,
  fetchConfig,
  saveAlertRule,
  saveParam,
  saveSlaPolicy,
  type AlertRule,
  type ConfigParam,
  type ConfigSnapshot,
  type SlaItem,
} from '@/apis/config'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InsightPanel from '@/components/insights/InsightPanel.vue'
import AppLayout from '@/layout/AppLayout.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * The Parameters menu: every rule the dashboard applies that a person (not a developer) should be
 * able to tune. Anyone with `incident.params:view` sees the values; only `manage` (ADMIN) gets
 * editable fields, and the server enforces that independently -- hiding the inputs is a courtesy.
 * Each section saves on its own and shows the server's validation message verbatim.
 */
const { t, te } = useI18n()
const auth = useAuthStore()
const queryClient = useQueryClient()
const canEdit = computed(() => auth.can('incident.params', 'manage'))

const query = useQuery({ queryKey: CONFIG_QUERY_KEY, queryFn: fetchConfig })

// Editable copies (arrays, so v-model binds to a row object), rebuilt on each server snapshot.
type RuleRow = AlertRule & { thresholdText: string }
type ParamRow = ConfigParam & { text: string }
const slaRows = ref<SlaItem[]>([])
const ruleRows = ref<RuleRow[]>([])
const paramRows = ref<ParamRow[]>([])

function load(s: ConfigSnapshot) {
  slaRows.value = s.slaPolicy.map((i) => ({ ...i }))
  ruleRows.value = s.alertRules.map((r) => ({
    ...r,
    thresholdText: r.threshold === null ? '' : String(r.threshold),
  }))
  paramRows.value = s.params.map((p) => ({ ...p, text: p.value }))
}
watch(
  () => query.data.value,
  (s) => s && load(s),
  { immediate: true },
)

const notice = reactive<Record<string, { ok: boolean; text: string } | undefined>>({})
function fail(section: string, e: unknown) {
  const message = e instanceof ApiError || e instanceof Error ? e.message : String(e)
  notice[section] = { ok: false, text: t('params.saveError', { message }) }
}
function done(section: string) {
  notice[section] = { ok: true, text: t('params.saved') }
  void queryClient.invalidateQueries({ queryKey: CONFIG_QUERY_KEY })
  // Pages derived from these settings must not keep serving figures computed under the old ones.
  void queryClient.invalidateQueries({ queryKey: ['insights'] })
}

const slaMutation = useMutation({
  mutationFn: () =>
    saveSlaPolicy(
      slaRows.value.map((i) => ({
        priorityId: i.priorityId,
        slaDays: Number(i.slaDays),
        atRiskRemainingDays: Number(i.atRiskRemainingDays),
      })),
    ),
  onSuccess: () => done('sla'),
  onError: (e) => fail('sla', e),
})

const savingRule = ref<string | null>(null)
const ruleMutation = useMutation({
  mutationFn: (r: RuleRow) =>
    saveAlertRule(r.code, {
      enabled: r.enabled,
      severity: r.severity,
      minCount: Number(r.minCount),
      ...(r.defaultThreshold !== null
        ? { threshold: r.thresholdText === '' ? null : Number(r.thresholdText) }
        : {}),
    }),
  onMutate: (r) => {
    savingRule.value = r.code
  },
  onSuccess: (_d, r) => done(`rule:${r.code}`),
  onError: (e, r) => fail(`rule:${r.code}`, e),
  onSettled: () => {
    savingRule.value = null
  },
})

function resetRule(r: RuleRow) {
  r.enabled = r.defaultEnabled
  r.severity = r.defaultSeverity
  r.minCount = r.defaultMinCount
  r.thresholdText = r.defaultThreshold === null ? '' : String(r.defaultThreshold)
}

const paramMutation = useMutation({
  mutationFn: (p: ParamRow) => saveParam(p.key, String(p.text)),
  onSuccess: (_d, p) => done(`param:${p.key}`),
  onError: (e, p) => fail(`param:${p.key}`, e),
})

// vue-i18n reads dots as nesting, so the catalog keys use underscores.
const paramLabel = (p: ConfigParam) => {
  const key = `params.param.${p.key.replace(/\./g, '_')}`
  return te(key) ? t(key) : p.key
}
const ruleName = (code: string) =>
  te(`params.alertName.${code}`) ? t(`params.alertName.${code}`) : code
const rangeOf = (p: ConfigParam) =>
  p.min !== null && p.max !== null ? t('params.range', { min: p.min, max: p.max }) : ''
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '')
const inputClass =
  'min-h-10 rounded-md border border-surface-300 bg-surface-0 px-2 tabular-nums disabled:bg-surface-100 disabled:text-surface-600'
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>
    <div class="flex flex-col gap-4" data-testid="parameters-view">
      <p class="rounded-lg border-l-4 border-primary bg-surface-50 p-3 text-sm text-surface-700">
        {{ t('params.note') }}
      </p>
      <p v-if="!canEdit" class="text-sm text-surface-700" data-testid="params-readonly">
        {{ t('params.readOnly') }}
      </p>
      <p v-if="query.isError.value" class="text-sm text-[var(--chart-error-text)]" role="alert">
        {{ t('params.error') }}
      </p>

      <template v-if="query.data.value">
        <InsightPanel :title="t('params.sla.title')" :subtitle="t('params.sla.sub')">
          <table class="insight-table w-full text-sm" data-testid="params-sla">
            <thead class="text-left text-xs uppercase text-surface-600">
              <tr>
                <th scope="col" class="py-1">{{ t('insights.col.priority') }}</th>
                <th scope="col" class="text-right">{{ t('params.sla.days') }}</th>
                <th scope="col" class="text-right">{{ t('params.sla.cushion') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="p in slaRows"
                :key="p.priorityId"
                class="border-t border-surface-100"
                data-testid="params-sla-row"
              >
                <th scope="row" class="py-1.5 text-left font-normal">{{ p.priorityName }}</th>
                <td class="text-right">
                  <input
                    v-model.number="p.slaDays"
                    type="number"
                    min="1"
                    max="365"
                    :disabled="!canEdit"
                    :aria-label="`${p.priorityName} ${t('params.sla.days')}`"
                    :class="[inputClass, 'w-24 text-right']"
                    data-testid="sla-days"
                  />
                </td>
                <td class="text-right">
                  <input
                    v-model.number="p.atRiskRemainingDays"
                    type="number"
                    min="0"
                    :disabled="!canEdit"
                    :aria-label="`${p.priorityName} ${t('params.sla.cushion')}`"
                    :class="[inputClass, 'w-24 text-right']"
                    data-testid="sla-cushion"
                  />
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="canEdit" class="mt-3 flex items-center gap-3">
            <Button
              :label="slaMutation.isPending.value ? t('params.saving') : t('params.save')"
              :disabled="slaMutation.isPending.value"
              data-testid="sla-save"
              @click="slaMutation.mutate()"
            />
            <span
              v-if="notice.sla"
              :class="notice.sla?.ok ? 'text-sm' : 'text-sm text-[var(--chart-error-text)]'"
              :role="notice.sla?.ok ? 'status' : 'alert'"
              data-testid="sla-notice"
              >{{ notice.sla?.text }}</span
            >
          </div>
        </InsightPanel>

        <InsightPanel :title="t('params.alerts.title')" :subtitle="t('params.alerts.sub')">
          <table class="insight-table w-full text-sm" data-testid="params-rules">
            <thead class="text-left text-xs uppercase text-surface-600">
              <tr>
                <th scope="col" class="py-1">{{ t('params.alerts.rule') }}</th>
                <th scope="col">{{ t('params.alerts.enabled') }}</th>
                <th scope="col">{{ t('params.alerts.severity') }}</th>
                <th scope="col">{{ t('params.alerts.minCount') }}</th>
                <th scope="col">{{ t('params.alerts.threshold') }}</th>
                <th v-if="canEdit" scope="col"></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="r in ruleRows" :key="r.code">
                <tr class="border-t border-surface-100" :data-testid="`rule-${r.code}`">
                  <th scope="row" class="py-1.5 text-left font-normal">{{ ruleName(r.code) }}</th>
                  <td>
                    <input
                      v-model="r.enabled"
                      type="checkbox"
                      :disabled="!canEdit"
                      :aria-label="`${ruleName(r.code)} ${t('params.alerts.enabled')}`"
                      data-testid="rule-enabled"
                    />
                  </td>
                  <td>
                    <select
                      v-model="r.severity"
                      :disabled="!canEdit"
                      :aria-label="`${ruleName(r.code)} ${t('params.alerts.severity')}`"
                      :class="inputClass"
                      data-testid="rule-severity"
                    >
                      <option v-for="s in SEVERITIES" :key="s" :value="s">
                        {{ t(`params.severity.${s}`) }}
                      </option>
                    </select>
                  </td>
                  <td>
                    <input
                      v-model.number="r.minCount"
                      type="number"
                      min="1"
                      :disabled="!canEdit"
                      :aria-label="`${ruleName(r.code)} ${t('params.alerts.minCount')}`"
                      :class="[inputClass, 'w-20']"
                      data-testid="rule-min"
                    />
                  </td>
                  <td>
                    <input
                      v-if="r.defaultThreshold !== null"
                      v-model="r.thresholdText"
                      type="number"
                      min="0"
                      :disabled="!canEdit"
                      :aria-label="`${ruleName(r.code)} ${t('params.alerts.threshold')}`"
                      :title="
                        te(`params.alerts.thresholdHint.${r.code}`)
                          ? t(`params.alerts.thresholdHint.${r.code}`)
                          : ''
                      "
                      :class="[inputClass, 'w-24']"
                      data-testid="rule-threshold"
                    />
                  </td>
                  <td v-if="canEdit" class="whitespace-nowrap py-1">
                    <Button
                      :label="savingRule === r.code ? t('params.saving') : t('params.save')"
                      size="small"
                      :disabled="savingRule === r.code"
                      data-testid="rule-save"
                      @click="ruleMutation.mutate(r)"
                    />
                    <Button
                      :label="t('params.resetDefault')"
                      size="small"
                      severity="secondary"
                      text
                      data-testid="rule-reset"
                      @click="resetRule(r)"
                    />
                  </td>
                </tr>
                <tr v-if="notice[`rule:${r.code}`]">
                  <td colspan="6" class="pb-1 text-xs">
                    <span
                      :class="notice[`rule:${r.code}`]?.ok ? '' : 'text-[var(--chart-error-text)]'"
                      :role="notice[`rule:${r.code}`]?.ok ? 'status' : 'alert'"
                      data-testid="rule-notice"
                      >{{ ruleName(r.code) }}: {{ notice[`rule:${r.code}`]?.text }}</span
                    >
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </InsightPanel>

        <InsightPanel :title="t('params.general.title')" :subtitle="t('params.general.sub')">
          <ul class="flex flex-col gap-3" data-testid="params-general">
            <li
              v-for="p in paramRows"
              :key="p.key"
              class="flex flex-wrap items-center gap-3"
              :data-testid="`param-${p.key}`"
            >
              <label :for="`p-${p.key}`" class="min-w-64 text-sm">{{ paramLabel(p) }}</label>
              <input
                :id="`p-${p.key}`"
                v-model="p.text"
                :type="p.type === 'TEXT' ? 'text' : 'number'"
                :step="p.type === 'DECIMAL' ? 'any' : '1'"
                :min="p.min ?? undefined"
                :max="p.max ?? undefined"
                :disabled="!canEdit"
                :class="[inputClass, 'w-28']"
                data-testid="param-input"
              />
              <span class="text-xs text-surface-600">
                {{ rangeOf(p) }} · {{ t('params.default', { value: p.defaultValue }) }}
              </span>
              <template v-if="canEdit">
                <Button
                  :label="t('params.save')"
                  size="small"
                  data-testid="param-save"
                  @click="paramMutation.mutate(p)"
                />
                <Button
                  :label="t('params.resetDefault')"
                  size="small"
                  severity="secondary"
                  text
                  data-testid="param-reset"
                  @click="p.text = p.defaultValue"
                />
              </template>
              <span
                v-if="notice[`param:${p.key}`]"
                :class="
                  notice[`param:${p.key}`]?.ok
                    ? 'text-xs'
                    : 'text-xs text-[var(--chart-error-text)]'
                "
                :role="notice[`param:${p.key}`]?.ok ? 'status' : 'alert'"
                data-testid="param-notice"
                >{{ notice[`param:${p.key}`]?.text }}</span
              >
            </li>
          </ul>
        </InsightPanel>

        <InsightPanel
          :title="t('params.changes.title')"
          :empty="query.data.value.recentChanges.length === 0"
        >
          <table class="insight-table w-full text-sm" data-testid="params-changes">
            <thead class="text-left text-xs uppercase text-surface-600">
              <tr>
                <th scope="col" class="py-1">{{ t('params.changes.when') }}</th>
                <th scope="col">{{ t('params.changes.who') }}</th>
                <th scope="col">{{ t('params.changes.what') }}</th>
                <th scope="col">{{ t('params.changes.from') }}</th>
                <th scope="col">{{ t('params.changes.to') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(c, i) in query.data.value.recentChanges"
                :key="i"
                class="border-t border-surface-100"
                data-testid="params-change-row"
              >
                <td class="py-1.5">{{ when(c.at) }}</td>
                <td>{{ c.by }}</td>
                <td>{{ c.target }}</td>
                <td>{{ c.oldValue }}</td>
                <td>{{ c.newValue }}</td>
              </tr>
            </tbody>
          </table>
        </InsightPanel>
      </template>
      <div
        v-else-if="query.isLoading.value"
        class="h-40 animate-pulse rounded-xl bg-surface-200"
      ></div>
    </div>
  </AppLayout>
</template>
