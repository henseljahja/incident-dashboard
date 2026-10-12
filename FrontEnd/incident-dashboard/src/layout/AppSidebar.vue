<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

import { useMenuLabel } from '@/i18n/menu'
import { useAuthStore } from '@/stores/auth'

/**
 * Navigation rail, ported from yob's `AppSidebar.vue`.
 *
 * The data was already there and simply never rendered: `/auth/token` returns a `menus[]`
 * array where every entry carries `menuId`, `label` and `route`. The old 28-line shell threw
 * all of it away and showed a single title, so a user with three views had no way to reach
 * two of them except by typing the URL.
 *
 * Because the backend only returns menus the caller may see, this list is permission-shaped
 * for free — a VIEWER simply gets fewer entries. That is an affordance, NOT a security
 * boundary; the server remains the sole authority (see `.agents/patterns/permission.md`).
 */
const { hidden, scrim, closeSidebar, logout } = defineProps<{
  /** True when the rail is off-canvas — collapsed on desktop, or closed on mobile. */
  hidden: boolean
  /** True only for the mobile overlay; the desktop rail displaces content instead. */
  scrim: boolean
  closeSidebar: () => void
  logout: () => void
}>()

const authStore = useAuthStore()
const route = useRoute()
const { t } = useI18n()
const menuLabel = useMenuLabel()

/**
 * Display order is pinned here rather than taken from the payload, for the same reason yob
 * pins its own: the backend orders by seeding order, which is an implementation detail and
 * would reshuffle the nav the day someone adds a role. The three ticket groups first because they
 * are the working surface (Incident, Problem, Service Request); Wallboard next because it is the shared screen; Exec after; administration last.
 */
const MENU_ORDER = [
  'incident.ops',
  'incident.problem',
  'incident.sr',
  'incident.wallboard',
  'incident.exec',
  'incident.admin',
]

/** Unlisted entries sort after every listed one, keeping their relative order. */
const rank = (list: string[], key: string) => {
  const i = list.indexOf(key)
  return i === -1 ? list.length : i
}

/** Per-menu icon. Falls back to a neutral glyph so a new backend menu still renders. */
const MENU_ICONS: Record<string, string> = {
  'incident.ops': 'pi pi-inbox',
  'incident.problem': 'pi pi-search',
  'incident.sr': 'pi pi-ticket',
  'incident.wallboard': 'pi pi-desktop',
  'incident.exec': 'pi pi-chart-line',
  'incident.admin': 'pi pi-users',
}
const iconFor = (menuId: string) => MENU_ICONS[menuId] ?? 'pi pi-circle'

const items = computed(() =>
  [...authStore.menus]
    .filter((m) => !!m.route)
    .sort((a, b) => rank(MENU_ORDER, a.menuId) - rank(MENU_ORDER, b.menuId)),
)

/** A route belongs to the current page when it matches exactly or as a prefix segment. */
const isActive = (r?: string) => !!r && (route.path === r || route.path.startsWith(`${r}/`))

const displayName = computed(() => authStore.displayName ?? '')

/**
 * The second line of the identity chip.
 *
 * It used to read "Signed in" — a line that told a reader nothing they could not see from the
 * fact that the app was open, while the line above it truncated mid-word ("Bootstrap A…"). The
 * username is short, unique, and the string an admin actually recognises, so it earns the space
 * the placeholder was occupying.
 */
const username = computed(() => authStore.username ?? '')

/**
 * On mobile the rail covers the page, so choosing a destination has to dismiss it — otherwise
 * the user arrives at the new view with the nav still on top of it. On desktop `closeSidebar`
 * is a no-op, so this is safe to call unconditionally.
 */
function onNavigate(event: MouseEvent, navigate: (e: MouseEvent) => void) {
  navigate(event)
  closeSidebar()
}
</script>

<template>
  <!--
    `inert` when hidden is load-bearing, not decoration: an off-canvas rail is still in the DOM,
    so without it every nav link stays keyboard-focusable and a Tab from the header walks into
    a menu nobody can see.
  -->
  <aside
    :inert="hidden || undefined"
    :aria-hidden="hidden ? 'true' : undefined"
    :class="[
      'flex flex-col gap-3 fixed inset-y-0 left-0 z-50 h-screen w-64 bg-surface-100 p-3',
      'transform transition-transform duration-400 ease-[cubic-bezier(0.05,0.74,0.2,0.99)]',
      hidden ? '-translate-x-full' : 'translate-x-0',
    ]"
  >
    <div class="flex items-center gap-2 px-1 pt-1">
      <RouterLink to="/" :aria-label="t('nav.home')">
        <img src="/yokke.svg" width="86" alt="Yokke" />
      </RouterLink>
    </div>
    <div class="px-1 text-xs font-semibold tracking-wide text-surface-600 uppercase">
      {{ t('common.appName') }}
    </div>

    <nav
      class="flex flex-1 flex-col gap-1 overflow-y-auto scrollbar-hide"
      :aria-label="t('nav.main')"
    >
      <RouterLink
        v-for="item in items"
        :key="item.menuId"
        v-slot="{ href, navigate }"
        :to="item.route ?? ''"
        custom
      >
        <a
          v-ripple
          :href="href"
          :aria-current="isActive(item.route) ? 'page' : undefined"
          :class="[
            'flex min-h-11 items-center gap-2 rounded-xl px-3 py-2 text-sm transition-colors',
            isActive(item.route)
              ? 'border border-primary-600 bg-primary-50 font-semibold text-primary-800'
              : 'text-surface-700 hover:bg-surface-200/60',
          ]"
          @click="onNavigate($event, navigate)"
        >
          <i :class="iconFor(item.menuId)" aria-hidden="true" />
          <span>{{ menuLabel(item) }}</span>
        </a>
      </RouterLink>
    </nav>

    <div class="mt-auto flex w-full items-center justify-between gap-2 rounded-lg bg-surface-0 p-3">
      <Avatar icon="pi pi-user" class="min-h-11 min-w-11 shrink-0 bg-primary-50 text-primary-800" />

      <div class="min-w-0 flex-1">
        <!--
          `line-clamp-2`, not `truncate`. A single clamped line cut the one label that says who
          is signed in down to "Bootstrap A…"; two lines fit the great majority of real display
          names outright, and the title attribute still carries the whole string for the rest.
        -->
        <div class="line-clamp-2 text-sm font-bold" :title="displayName">{{ displayName }}</div>
        <div v-if="username" class="truncate text-xs text-surface-600" :title="username">
          {{ username }}
        </div>
      </div>

      <Button
        icon="pi pi-sign-out"
        variant="text"
        severity="contrast"
        rounded
        :aria-label="t('nav.logout')"
        class="min-h-11 min-w-11 shrink-0"
        @click="logout"
      />
    </div>
  </aside>

  <div
    v-if="scrim"
    class="fixed inset-0 z-40 bg-black/40 transition-opacity"
    aria-hidden="true"
    @click="closeSidebar"
  />
</template>
