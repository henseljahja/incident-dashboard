<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

import {
  askQuestion,
  fetchDailySummary,
  fetchPatterns,
  searchTickets,
  type Answer,
  type DailySummary,
  type Patterns,
  type SearchHit,
} from '@/apis/insights'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InsightPanel from '@/components/insights/InsightPanel.vue'
import PriorityCells from '@/components/insights/PriorityCells.vue'
import PriorityPill from '@/components/insights/PriorityPill.vue'
import ScopeSwitch from '@/components/insights/ScopeSwitch.vue'
import TicketLink from '@/components/insights/TicketLink.vue'
import { useInsightPage } from '@/composables/useInsightPage'
import AppLayout from '@/layout/AppLayout.vue'
import { useFiltersStore } from '@/stores/filters'
import { formatDate } from '@/utils/date'
import { formatDecimal } from '@/utils/number'

/**
 * AI insights, in two layers.
 *
 * **Computed locally, always:** the daily summary figures, the Q&A answer, ticket search and the
 * recurring-pattern analysis are produced by the dashboard server from the same rows as every
 * other widget — no ticket text leaves the system. Search is lexical (TF-IDF) relevance, and Q&A
 * answers a closed set of questions and says so when it cannot answer; neither pretends to be
 * more than it is.
 *
 * **Optional prose:** if an operator has configured an AI provider server-side, the summary and
 * pattern cards also show a short written briefing and name the provider. By default none is
 * configured and the cards say "computed locally".
 *
 * Each card is run on demand (a button), not on page load, so nothing is computed — or sent
 * anywhere — until someone asks.
 */
useInsightPage('INCIDENT', null)
const { t, locale } = useI18n()
const filters = useFiltersStore()

interface Run<T> {
  data: T | null
  loading: boolean
  error: boolean
}
function run<T>(): Run<T> {
  return { data: null, loading: false, error: false }
}

async function exec<T>(state: Run<T>, job: () => Promise<T>) {
  state.loading = true
  state.error = false
  try {
    state.data = await job()
  } catch {
    state.error = true
  } finally {
    state.loading = false
  }
}

const lang = () => (locale.value === 'id' ? 'id' : 'en')

const summaryDate = ref(formatDate(new Date()))
const summary = ref(run<DailySummary>())
const generate = () =>
  exec(summary.value, () =>
    fetchDailySummary(filters.toBaseFilterParams(), summaryDate.value, lang()),
  )

const question = ref('')
const answer = ref(run<Answer>())
const ask = () =>
  question.value.trim() &&
  exec(answer.value, () => askQuestion(filters.toBaseFilterParams(), question.value.trim()))

const query = ref('')
const hits = ref(run<SearchHit[]>())
const search = () =>
  query.value.trim() &&
  exec(hits.value, () => searchTickets(filters.toBaseFilterParams(), query.value.trim()))

const patterns = ref(run<Patterns>())
const analyze = () =>
  exec(patterns.value, () => fetchPatterns(filters.toBaseFilterParams(), lang()))

function answerValue(a: Answer): string {
  if (a.value === null) return '—'
  const v = formatDecimal(a.value, locale.value)
  return a.unit === '%' ? `${v}%` : a.unit === 'd' ? `${v} ${t('insights.unit.days')}` : v
}

const EXAMPLES = ['q1', 'q2', 'q3'] as const
</script>

<template>
  <AppLayout>
    <template #header-actions><FreshnessIndicator /></template>
    <div class="flex flex-col gap-4" data-testid="ai-view">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <ScopeSwitch />
        <p class="max-w-2xl text-xs text-surface-600" data-testid="ai-privacy">
          {{ t('insights.ai.privacy') }}
        </p>
      </div>

      <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <!-- Daily summary -->
        <InsightPanel
          :title="t('insights.ai.summary.title')"
          :subtitle="t('insights.ai.summary.sub')"
          :loading="summary.loading"
          :error="summary.error"
          @retry="generate"
        >
          <form class="flex items-end gap-2" @submit.prevent="generate">
            <div class="flex flex-col gap-1">
              <label class="text-xs uppercase text-surface-600" for="ai-date">{{
                t('insights.ai.date')
              }}</label>
              <input
                id="ai-date"
                v-model="summaryDate"
                type="date"
                class="min-h-10 rounded-md border border-surface-300 px-2"
              />
            </div>
            <button
              type="submit"
              class="min-h-10 rounded-md bg-primary px-4 text-sm text-primary-contrast"
              data-testid="ai-generate"
            >
              {{ t('insights.ai.generate') }}
            </button>
          </form>
          <div v-if="summary.data" class="flex flex-col gap-3 text-sm" data-testid="ai-summary">
            <p class="text-xs text-surface-600" data-testid="ai-provider">
              {{
                summary.data.provider === 'local'
                  ? t('insights.ai.local')
                  : t('insights.ai.provider', { name: summary.data.provider })
              }}
            </p>
            <p
              v-if="summary.data.narrative"
              class="whitespace-pre-line rounded-lg bg-surface-50 p-3"
              data-testid="ai-narrative"
            >
              {{ summary.data.narrative }}
            </p>
            <dl class="grid grid-cols-4 gap-2 text-center">
              <div>
                <dt class="text-xs text-surface-600">{{ t('insights.ai.created') }}</dt>
                <dd class="text-xl font-semibold tabular-nums" data-testid="ai-created">
                  {{ summary.data.created }}
                </dd>
              </div>
              <div>
                <dt class="text-xs text-surface-600">{{ t('insights.ai.closedToday') }}</dt>
                <dd class="text-xl font-semibold tabular-nums">{{ summary.data.closed }}</dd>
              </div>
              <div>
                <dt class="text-xs text-surface-600">{{ t('insights.ai.openNow') }}</dt>
                <dd class="text-xl font-semibold tabular-nums">{{ summary.data.openNow }}</dd>
              </div>
              <div>
                <dt class="text-xs text-surface-600">{{ t('insights.ai.breached') }}</dt>
                <dd class="text-xl font-semibold tabular-nums text-[var(--chart-error-text)]">
                  {{ summary.data.breachedOpen }}
                </dd>
              </div>
            </dl>
            <div class="flex items-center gap-2">
              <span class="text-xs text-surface-600">{{ t('insights.col.priority') }}</span
              ><PriorityCells :counts="summary.data.byPriority" />
            </div>
            <div v-if="summary.data.topInstitusi.length">
              <span class="text-xs text-surface-600">{{ t('insights.institusi.title') }}:</span>
              {{ summary.data.topInstitusi.map((i) => `${i.label} (${i.count})`).join(', ') }}
            </div>
            <div v-if="summary.data.topCategories.length">
              <span class="text-xs text-surface-600">{{ t('insights.col.category') }}:</span>
              {{ summary.data.topCategories.map((i) => `${i.label} (${i.count})`).join(', ') }}
            </div>
            <div v-if="summary.data.attention.length">
              <h3 class="text-xs font-semibold uppercase text-surface-600">
                {{ t('insights.ai.attention') }}
              </h3>
              <ul>
                <li v-for="a in summary.data.attention" :key="a.noIncident" class="flex gap-2">
                  <TicketLink :id="a.noIncident" /><span class="truncate">{{ a.subject }}</span
                  ><PriorityPill :priority="a.priority" />
                </li>
              </ul>
            </div>
          </div>
        </InsightPanel>

        <!-- Q&A -->
        <InsightPanel
          :title="t('insights.ai.qa.title')"
          :subtitle="t('insights.ai.qa.sub')"
          :loading="answer.loading"
          :error="answer.error"
          @retry="ask"
        >
          <form class="flex gap-2" @submit.prevent="ask">
            <label class="sr-only" for="ai-question">{{ t('insights.ai.qa.title') }}</label>
            <input
              id="ai-question"
              v-model="question"
              type="text"
              maxlength="200"
              :placeholder="t('insights.ai.qa.placeholder')"
              class="min-h-10 flex-1 rounded-md border border-surface-300 px-3"
              data-testid="ai-question"
            />
            <button
              type="submit"
              class="min-h-10 rounded-md bg-primary px-4 text-sm text-primary-contrast"
              data-testid="ai-ask"
            >
              {{ t('insights.ai.ask') }}
            </button>
          </form>
          <div
            v-if="answer.data"
            class="rounded-lg bg-surface-50 p-3 text-sm"
            data-testid="ai-answer"
            aria-live="polite"
          >
            <template v-if="answer.data.understood && answer.data.metric">
              <p class="text-surface-600">
                {{ t(`insights.ai.metric.${answer.data.metric}`)
                }}<span v-if="answer.data.scope"> — {{ answer.data.scope }}</span>
              </p>
              <p class="text-2xl font-semibold tabular-nums" data-testid="ai-answer-value">
                {{ answerValue(answer.data) }}
              </p>
              <p class="text-xs text-surface-600">
                {{ t('insights.ai.basedOn', { n: answer.data.matched }) }}
              </p>
            </template>
            <template v-else>
              <p>{{ t('insights.ai.qa.notUnderstood') }}</p>
              <ul class="mt-1 list-disc pl-5 text-surface-600">
                <li v-for="e in EXAMPLES" :key="e">{{ t(`insights.ai.qa.${e}`) }}</li>
              </ul>
            </template>
          </div>
        </InsightPanel>

        <!-- Search -->
        <InsightPanel
          :title="t('insights.ai.search.title')"
          :subtitle="t('insights.ai.search.sub')"
          :loading="hits.loading"
          :error="hits.error"
          @retry="search"
        >
          <form class="flex gap-2" @submit.prevent="search">
            <label class="sr-only" for="ai-search">{{ t('insights.ai.search.title') }}</label>
            <input
              id="ai-search"
              v-model="query"
              type="search"
              maxlength="100"
              :placeholder="t('insights.ai.search.placeholder')"
              class="min-h-10 flex-1 rounded-md border border-surface-300 px-3"
              data-testid="ai-search"
            />
            <button
              type="submit"
              class="min-h-10 rounded-md bg-primary px-4 text-sm text-primary-contrast"
              data-testid="ai-search-go"
            >
              {{ t('insights.ai.searchGo') }}
            </button>
          </form>
          <p
            v-if="hits.data && hits.data.length === 0"
            class="text-sm text-surface-600"
            data-testid="ai-no-hits"
          >
            {{ t('insights.ai.search.none') }}
          </p>
          <ol v-if="hits.data?.length" class="flex flex-col gap-2" data-testid="ai-hits">
            <li
              v-for="h in hits.data"
              :key="h.noIncident"
              class="flex items-start gap-2 text-sm"
              data-testid="ai-hit"
            >
              <TicketLink :id="h.noIncident" />
              <span class="flex-1"
                >{{ h.subject
                }}<span class="block text-xs text-surface-600">{{ h.institusi }}</span></span
              >
              <PriorityPill :priority="h.priority" />
              <span
                class="w-14 text-right text-xs tabular-nums text-surface-600"
                :title="t('insights.ai.relevance')"
                >{{ h.relevance.toFixed(2) }}</span
              >
            </li>
          </ol>
        </InsightPanel>

        <!-- Patterns -->
        <InsightPanel
          :title="t('insights.ai.patterns.title')"
          :subtitle="t('insights.ai.patterns.sub')"
          :loading="patterns.loading"
          :error="patterns.error"
          @retry="analyze"
        >
          <button
            type="button"
            class="min-h-10 self-start rounded-md bg-primary px-4 text-sm text-primary-contrast"
            data-testid="ai-analyze"
            @click="analyze"
          >
            {{ t('insights.ai.analyze') }}
          </button>
          <div v-if="patterns.data" class="flex flex-col gap-3" data-testid="ai-patterns">
            <p
              v-if="patterns.data.narrative"
              class="whitespace-pre-line rounded-lg bg-surface-50 p-3 text-sm"
            >
              {{ patterns.data.narrative }}
            </p>
            <p v-if="patterns.data.patterns.length === 0" class="text-sm text-surface-600">
              {{ t('insights.ai.patterns.none') }}
            </p>
            <article
              v-for="p in patterns.data.patterns"
              :key="`${p.institusi}|${p.category}`"
              class="rounded-lg border border-surface-200 p-3 text-sm"
              data-testid="ai-pattern"
            >
              <h3 class="font-semibold">
                {{ p.institusi }} · {{ p.category }}
                <span class="ml-1 rounded-full bg-surface-100 px-2 text-xs">{{ p.count }}×</span>
              </h3>
              <p class="text-xs text-surface-600">
                {{ p.firstSeen }} → {{ p.lastSeen }} ·
                {{
                  t('insights.ai.patterns.workaroundOf', {
                    w: p.closedWithWorkaround,
                    perm: p.permanentSolution,
                  })
                }}
              </p>
              <p v-if="p.keywords.length" class="mt-1 text-xs">
                {{ t('insights.ai.patterns.keywords') }}: {{ p.keywords.join(', ') }}
              </p>
              <p class="mt-1 font-medium" :data-rec="p.recommendation">
                → {{ t(`insights.ai.patterns.rec.${p.recommendation}`) }}
              </p>
              <ul class="mt-1 text-xs">
                <li v-for="s in p.samples" :key="s.noIncident" class="flex gap-2">
                  <TicketLink :id="s.noIncident" /><span class="truncate">{{ s.subject }}</span>
                </li>
              </ul>
            </article>
          </div>
        </InsightPanel>
      </div>
    </div>
  </AppLayout>
</template>
