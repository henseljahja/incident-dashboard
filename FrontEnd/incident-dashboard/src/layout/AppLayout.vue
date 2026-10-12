<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import { useMenuLabel } from '@/i18n/menu'
import { useAuthStore } from '@/stores/auth'

import AppSidebar from './AppSidebar.vue'
import { useLayout } from './useLayout'

/**
 * App shell, ported from yob's `AppLayout.vue`.
 *
 * What it replaces: a 28-line placeholder that rendered a bare white bar with the app name
 * and a Log out button, then dropped the page straight onto the body. No nav, no page
 * container, no elevation — which is most of why the dashboard read as a prototype.
 *
 * The structure is yob's: a fixed 64-wide rail, and the page content inside a Card on a
 * `surface-100` ground so it reads as an elevated sheet rather than loose elements.
 *
 * Deliberately NOT ported from yob: the idle-logout timer, fingerprint drift check and
 * network-status banner. Those belong to a console that writes money-moving records. This
 * dashboard is strictly read-only, so importing them would be ceremony that implies a
 * threat model this app does not have.
 */
const { sidebarHidden, scrimVisible, closeSidebar, toggleSidebar, md } = useLayout()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()
const menuLabel = useMenuLabel()

/**
 * Page title comes from the menu payload, so the nav and the header cannot disagree — both go
 * through the same `menuLabel`, so they cannot disagree in Indonesian either.
 */
const title = computed(() => {
  const match = auth.menus.find(
    (m) => m.route && (route.path === m.route || route.path.startsWith(`${m.route}/`)),
  )
  return match ? menuLabel(match) : ''
})

// Covers the navigations AppSidebar's own click handler cannot see: browser back/forward,
// and any programmatic push. Without it a phone user can end up on a new view with the
// overlay still covering it.
watch(
  () => route.path,
  () => closeSidebar(),
)

/**
 * The skip link's target. `tabindex="-1"` on `<main>` is what makes the jump actually move
 * focus: without it the browser scrolls the region into view but leaves focus on the link, so
 * the next Tab walks straight back into the nav the user just asked to skip.
 */
const CONTENT_ID = 'main-content'

function focusContent() {
  document.getElementById(CONTENT_ID)?.focus()
}

async function onLogout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="min-h-screen bg-surface-100">
    <!--
      First focusable thing in the document, invisible until it has focus. `sr-only` alone would
      make it unreachable-looking for a sighted keyboard user, so `focus:not-sr-only` brings it
      back on screen the moment Tab lands on it.
    -->
    <a
      :href="`#${CONTENT_ID}`"
      data-testid="skip-to-content"
      class="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-100 focus:rounded-lg focus:bg-surface-0 focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-surface-900 focus:shadow-lg focus:outline-2 focus:outline-offset-2 focus:outline-primary-600"
      @click="focusContent"
    >
      {{ t('nav.skipToContent') }}
    </a>

    <AppSidebar
      :hidden="sidebarHidden"
      :scrim="scrimVisible"
      :closeSidebar="closeSidebar"
      :logout="onLogout"
    />

    <!--
      The content offset and the rail's visibility are now driven by the SAME state. They used
      to disagree: the rail rendered at `translate-x-0` unconditionally while `ml-64` was
      `md`-gated, so below 768px the nav sat directly on top of the page and no screen was
      usable on a phone. `sidebarHidden` is false only when the rail is actually displacing
      content, which is only ever true on desktop.
    -->
    <div
      class="flex min-h-screen flex-col p-3"
      :class="!sidebarHidden && md ? 'ml-64 py-3 pr-3 pl-0' : ''"
    >
      <Card
        :pt="{
          root: 'rounded-lg flex-1',
          body: 'p-0 flex flex-col flex-1',
          content: 'flex flex-col flex-1',
        }"
      >
        <template #header>
          <div class="flex flex-wrap items-center gap-2 border-b border-surface-200 px-4 py-3">
            <Button
              icon="pi pi-bars"
              variant="text"
              severity="contrast"
              rounded
              class="min-h-11 min-w-11"
              :aria-label="t('nav.toggle')"
              @click="toggleSidebar"
            />
            <h1 class="text-xl font-semibold text-surface-900">{{ title }}</h1>
            <!--
              Page-level controls (freshness, export, refresh) render here rather than inside
              the page body, so every view puts them in the same place. Previously each view
              printed its own <h1> next to them, which duplicated the title the shell now owns.
            -->
            <div class="ml-auto flex flex-wrap items-center justify-end gap-2">
              <slot name="header-actions" />
              <LanguageSwitch />
            </div>
          </div>
        </template>

        <template #content>
          <!--
            `<main>` exists so there is something to skip TO.
 
            The shell used to expose only `aside` and `nav` as landmarks, and every view's entire
            heading outline was the single `<h1>` above — card titles were styled divs. With no
            main region, no heading tree and no skip link, a keyboard or screen-reader user had
            no mechanism at all to get past the nav rail to the data, which is WCAG 2.2 2.4.1
            (Bypass Blocks, Level A). The skip link at the top of the document is the other half.
          -->
          <main :id="CONTENT_ID" class="flex flex-1 flex-col gap-4 p-4" tabindex="-1">
            <slot />
          </main>
        </template>
      </Card>
    </div>

    <Toast />
  </div>
</template>
