<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import { fetchUsers, type AdminUser, type UserStatus } from '@/apis/admin'
import AppLayout from '@/layout/AppLayout.vue'
import { formatDisplayDateTime } from '@/utils/date'
import { titleCase } from '@/utils/text'

/**
 * User management — ported from yob backoffice's `routes/UserManagement/Table.vue`.
 *
 * <p>Kept from yob because they are the proven parts: the single debounced search box with
 * explicit Search/Clear buttons, `rows-per-page` living in `#paginatorstart` beside the export
 * control, status rendered as a pill `Message` rather than coloured text, and the frozen
 * right-hand Action column with View + Edit.
 *
 * <p><b>Server-side search, not yob's client-side `filteredItems`.</b> yob asks the server for
 * a page and THEN filters that page in the browser, so a filter matching a row on page 3 finds
 * nothing while page 1 is displayed — it reads as "search is broken" and is the one part of
 * that screen worth not copying. `search` goes to the server, which is also what makes
 * `totalElements` honest.
 */
const router = useRouter()
const queryClient = useQueryClient()
const { t, te, locale } = useI18n()

const rowsPerPageOptions = [10, 25, 50, 100]

const searchInput = ref('')
/** What has actually been submitted — separate from `searchInput` so typing does not re-query. */
const appliedSearch = ref('')
const page = ref(0)
const rows = ref(10)

let debounceTimer: ReturnType<typeof setTimeout> | undefined

function applySearch() {
  appliedSearch.value = searchInput.value.trim()
  page.value = 0
}

function onSearchInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(applySearch, 500)
}

function onSearchClear() {
  searchInput.value = ''
  applySearch()
}

// Changing page SIZE has to reset to the first page. Without this, sitting on page 9 of 10 at
// 10-per-page and switching to 100-per-page asks the server for rows 900-1000 of a set that now
// has one page, and the table renders empty.
watch(rows, () => {
  page.value = 0
})

const { isPending, isError, error, data } = useQuery({
  queryKey: computed(() => ['admin/users', appliedSearch.value, page.value, rows.value]),
  queryFn: () => fetchUsers(appliedSearch.value, page.value, rows.value),
  placeholderData: (previous) => previous,
})

const items = computed<AdminUser[]>(() => data.value?.content ?? [])
const totalRecords = computed(() => data.value?.totalElements ?? 0)

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
}

function viewUser(user: AdminUser) {
  router.push(`/admin/users/${encodeURIComponent(user.username)}`)
}

function editUser(user: AdminUser) {
  router.push(`/admin/users/${encodeURIComponent(user.username)}/edit`)
}

function refresh() {
  void queryClient.invalidateQueries({ queryKey: ['admin/users'] })
}

/**
 * yob's `statusSeverity`, with `LOCKED` added and `TERMINATED` dropped.
 *
 * `LOCKED` sits at the highest weight: it means five failed logins locked the account out,
 * which is either someone who cannot get in or someone guessing at it. Both want attention now,
 * and `warn` would sit at the same weight as a routine never-signed-in PENDING.
 *
 * <p><b>It must be `error`, not `danger`.</b> `danger` is `Tag`'s and `Button`'s vocabulary;
 * `Message` accepts only `success | info | warn | error | secondary | contrast`. Passing
 * `danger` is not rejected — it lands as a `p-message-danger` class that no theme rule matches,
 * so the pill loses its background, its border and its radius and renders as bare text. The
 * effect was that the ONE status an admin scans this column to find was the only one with no
 * visual marking at all, while Active/Pending/Inactive all kept their pills. The severity type
 * below is what keeps a future status from re-introducing an unstyled value silently.
 */
type MessageSeverity = 'success' | 'info' | 'warn' | 'error' | 'secondary' | 'contrast'

function statusSeverity(status: UserStatus): MessageSeverity {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'ACTIVE':
      return 'success'
    case 'INACTIVE':
      return 'secondary'
    case 'LOCKED':
      return 'error'
    default:
      return 'secondary'
  }
}

/**
 * The account status is a server enum with a fixed set of values, so — unlike tracker or status
 * NAMES from Redmine — it is chrome, and is translated. An enum value this build does not know
 * yet falls back to its title-cased form rather than to a raw catalog key.
 */
function statusLabel(status: string) {
  const key = `admin.status.${status}`
  return te(key) ? t(key) : titleCase(status)
}

function formatTimestamp(value: string | null) {
  return formatDisplayDateTime(value, locale.value) ?? '—'
}
</script>

<template>
  <AppLayout>
    <div class="flex flex-col gap-4" data-testid="user-management">
      <!--
        No title here. `AppLayout` renders the `<h1>` from the server's menu payload, so the
        nav label and the page title cannot disagree — and a second "User Management" in the
        body made the phrase appear three times on one screen while adding no heading a screen
        reader could navigate by (it was a `div`). Actions only.
      -->
      <div class="flex h-10.5 flex-wrap items-center justify-end gap-4">
        <div class="flex gap-3">
          <Button
            type="button"
            severity="secondary"
            variant="outlined"
            icon="pi pi-refresh"
            :label="t('admin.refresh')"
            class="rounded-xl"
            data-testid="user-refresh"
            @click="refresh"
          />
          <Button
            type="button"
            severity="primary"
            icon="pi pi-plus"
            icon-pos="right"
            :label="t('admin.create')"
            class="rounded-xl"
            data-testid="user-create-link"
            @click="router.push('/admin/users/create')"
          />
        </div>
      </div>

      <div class="flex flex-wrap gap-3 rounded-xl bg-surface-100 p-4">
        <InputText
          id="user-search"
          v-model="searchInput"
          :placeholder="t('admin.searchPlaceholder')"
          :aria-label="t('admin.searchPlaceholder')"
          class="min-w-0 flex-1 rounded-xl"
          data-testid="user-search"
          @input="onSearchInput"
          @keyup.enter="applySearch"
        />
        <Button
          type="button"
          severity="secondary"
          variant="outlined"
          :label="t('admin.clear')"
          class="rounded-xl"
          data-testid="user-search-clear"
          @click="onSearchClear"
        />
        <!--
          Search is the primary action of this block and now carries primary weight. It used to
          be a filled `secondary` sitting beside an outlined `secondary` Clear — same hue, same
          size, so the pair read as two equal options rather than "do the thing" and "undo it".
        -->
        <Button
          type="button"
          severity="primary"
          icon="pi pi-search"
          :label="t('admin.search')"
          class="rounded-xl"
          data-testid="user-search-submit"
          @click="applySearch"
        />
      </div>

      <!--
        A failed load must be visible and recoverable. The Executive view shipped without this
        and presented an expired session as tiles that never finished loading; the same query
        failure here would render an empty table that looks like "no users exist".
      -->
      <Message
        v-if="isError"
        severity="error"
        :closable="false"
        role="alert"
        data-testid="user-load-error"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>{{ error?.message ?? t('admin.loadError') }}</span>
          <Button :label="t('common.retry')" icon="pi pi-refresh" size="small" @click="refresh" />
        </div>
      </Message>

      <!--
        `v-else`: a failed load must NOT also render the table. Without it the screen showed the
        error banner and, directly beneath it, "No users found." — which is not true and is the
        more believable of the two, so the reader concludes the account list is empty rather
        than that their session died.
      -->
      <DataTable
        v-else
        :value="items"
        :total-records="totalRecords"
        :loading="isPending"
        lazy
        paginator
        paginator-template="FirstPageLink PrevPageLink PageLinks NextPageLink LastPageLink"
        :first="page * rows"
        :rows="rows"
        :rows-per-page-options="rowsPerPageOptions"
        data-testid="user-table"
        :pt="{ root: 'p-4 rounded-xl border border-surface-200' }"
        @page="onPage"
      >
        <template #empty>
          <div class="py-6 text-center text-surface-600" data-testid="user-table-empty">
            {{ t('admin.empty') }}
          </div>
        </template>

        <template #paginatorstart>
          <div class="flex items-center gap-2">
            <span class="text-sm">{{ t('admin.rowsPerPage') }}</span>
            <Select
              v-model="rows"
              :options="rowsPerPageOptions"
              class="rounded-xl"
              :aria-label="t('admin.rowsPerPage')"
            />
          </div>
        </template>

        <Column :header="t('admin.col.no')">
          <template #body="{ index }">
            {{ page * rows + index + 1 }}
          </template>
        </Column>

        <Column field="username" :header="t('admin.col.username')" />

        <Column field="name" :header="t('admin.col.name')">
          <template #body="{ data: row }">{{ row.name ?? '—' }}</template>
        </Column>

        <Column field="email" :header="t('admin.col.email')">
          <template #body="{ data: row }">{{ row.email ?? '—' }}</template>
        </Column>

        <Column field="roleName" :header="t('admin.col.role')">
          <template #body="{ data: row }">{{ row.roleName ?? '—' }}</template>
        </Column>

        <Column field="lastLoginAt" :header="t('admin.col.lastSignIn')">
          <template #body="{ data: row }">{{ formatTimestamp(row.lastLoginAt) }}</template>
        </Column>

        <Column field="status" :header="t('admin.col.status')">
          <template #body="{ data: row }">
            <div class="flex justify-center">
              <Message
                :severity="statusSeverity(row.status)"
                :pt="{ root: 'w-fit outline-0 rounded-full', content: 'px-2 py-1.5' }"
                :data-testid="`user-status-${row.username}`"
              >
                {{ statusLabel(row.status) }}
              </Message>
            </div>
          </template>
        </Column>

        <Column :header="t('admin.col.action')" frozen align-frozen="right">
          <template #body="{ data: row }">
            <div class="flex gap-3">
              <Button
                type="button"
                severity="secondary"
                variant="outlined"
                :label="t('admin.view')"
                class="rounded-xl"
                :data-testid="`user-view-${row.username}`"
                @click="viewUser(row)"
              />
              <Button
                type="button"
                severity="secondary"
                :label="t('admin.edit')"
                class="rounded-xl"
                :data-testid="`user-edit-${row.username}`"
                @click="editUser(row)"
              />
            </div>
          </template>
        </Column>
      </DataTable>
    </div>
  </AppLayout>
</template>
