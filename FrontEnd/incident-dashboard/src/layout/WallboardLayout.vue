<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import LanguageSwitch from '@/components/common/LanguageSwitch.vue'

/**
 * Chrome for the unattended wallboard — deliberately minimal, but no longer absent.
 *
 * The original had NO chrome at all: no header, no nav, no way back. That is right for the
 * case it was designed for (a TV in an ops room, nobody logged in, nobody clicking) and wrong
 * for every other way people actually reach this screen — a colleague opening /wallboard on a
 * laptop had no affordance to leave it and had to edit the URL.
 *
 * So the choice is made explicit instead of implied: the bar below is small, dim, and
 * auto-hides once the pointer is still, and REAL fullscreen is now an opt-in button rather
 * than something the layout fakes by covering the viewport. On the TV nobody moves a mouse, so
 * the bar disappears within seconds and the display looks exactly as it did before.
 *
 * `app-dark` is PrimeVue's dark-mode selector (`main.ts`'s `darkModeSelector: '.app-dark'`) —
 * applying it here forces dark mode for this subtree regardless of the viewer's OS preference,
 * since on a wallboard there is no user session to read a preference from.
 */

const { t } = useI18n()

/** How long the pointer must be still before the chrome fades out. */
const IDLE_HIDE_MS = 3_000

const chromeVisible = ref(true)
const isFullscreen = ref(false)
let idleTimer: ReturnType<typeof setTimeout> | undefined

function showChromeThenIdle() {
  chromeVisible.value = true
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    chromeVisible.value = false
  }, IDLE_HIDE_MS)
}

/**
 * Uses the real Fullscreen API rather than just hiding chrome, so the browser's own UI goes
 * away too — which is the thing that actually matters on a wall-mounted display, and the thing
 * a page can never achieve on its own.
 */
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await document.documentElement.requestFullscreen()
    }
  } catch {
    // Fullscreen is refused when not triggered by a user gesture, and is unavailable outright
    // in some embedded webviews. Swallowed on purpose: the wallboard still works at normal
    // size, and an error toast on a screen nobody is watching helps nobody.
  }
}

function syncFullscreenState() {
  isFullscreen.value = document.fullscreenElement !== null
}

onMounted(() => {
  showChromeThenIdle()
  window.addEventListener('mousemove', showChromeThenIdle)
  window.addEventListener('keydown', showChromeThenIdle)
  document.addEventListener('fullscreenchange', syncFullscreenState)
})

onBeforeUnmount(() => {
  if (idleTimer) clearTimeout(idleTimer)
  window.removeEventListener('mousemove', showChromeThenIdle)
  window.removeEventListener('keydown', showChromeThenIdle)
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  // Leaving the view in fullscreen would trap the rest of the app in it.
  if (document.fullscreenElement) void document.exitFullscreen()
})
</script>

<template>
  <div
    class="app-dark relative flex min-h-screen w-full flex-col bg-surface-950 text-white"
    data-testid="wallboard-layout"
  >
    <!--
      Fades rather than unmounts: a control that is removed from the DOM cannot be reached by
      keyboard, and someone navigating this screen without a mouse would have no way to leave.
      `pointer-events-none` while hidden keeps it from swallowing clicks on the tiles beneath.

      A row in the page flow, NOT an absolutely positioned layer. Floating over the top-right
      corner put the language switch and the two round buttons on top of whatever the page drew
      there — at 375px that was the "STALE DATA" heading. The row keeps its height while faded,
      so nothing below it jumps when it appears or disappears.
    -->
    <div
      class="flex min-h-14 shrink-0 items-center justify-end gap-2 px-3 py-1 transition-opacity duration-500 motion-reduce:transition-none"
      :class="chromeVisible ? 'opacity-100' : 'pointer-events-none opacity-0'"
      data-testid="wallboard-chrome"
    >
      <LanguageSwitch />
      <Button
        type="button"
        :icon="isFullscreen ? 'pi pi-window-minimize' : 'pi pi-window-maximize'"
        :aria-label="isFullscreen ? t('wallboard.fullscreenExit') : t('wallboard.fullscreenEnter')"
        :title="isFullscreen ? t('wallboard.fullscreenExit') : t('wallboard.fullscreenEnter')"
        data-testid="wallboard-fullscreen-toggle"
        rounded
        severity="secondary"
        class="min-h-11 min-w-11"
        @click="toggleFullscreen"
      />
      <Button
        type="button"
        icon="pi pi-arrow-left"
        :aria-label="t('wallboard.back')"
        :title="t('wallboard.back')"
        data-testid="wallboard-exit"
        rounded
        severity="secondary"
        class="min-h-11 min-w-11"
        @click="$router.push('/ops')"
      />
    </div>

    <slot />
  </div>
</template>
