import { useStorage, useWindowSize } from '@vueuse/core'
import { computed, ref, watch } from 'vue'

/**
 * Breakpoint + sidebar state, ported from yob's `src/layout/useLayout.ts` so both consoles
 * collapse at the same widths and remember the sidebar the same way.
 *
 * `isSidebarActive` is TRUE when the sidebar is COLLAPSED — the sense is inverted, and it is
 * inverted in yob too. Kept as-is rather than "fixed", because the persisted `sidebar` key in
 * localStorage would flip meaning for anyone who already has one stored.
 *
 * DESKTOP vs MOBILE are two different states, and conflating them is what broke every screen
 * under 768px: the rail was rendered in-flow at `translate-x-0` while the content's `ml-64`
 * offset was `md`-gated, so below the breakpoint the nav sat directly on top of the page. The
 * two states are now separate:
 *
 * - `isSidebarActive` (persisted) is the DESKTOP collapse preference. It has no effect on
 *   mobile, where a 256px rail can never sit beside a 375px page.
 * - `mobileSidebarOpen` (NOT persisted) is the mobile overlay. It starts closed on every load,
 *   because a nav that covers the whole screen is not a preference worth remembering.
 *
 * `sidebarHidden` is the single value the shell should render from. Reading either raw flag
 * directly is what reintroduces the bug.
 */
export function useLayout() {
  const { width, height } = useWindowSize()

  const breakpoints = {
    '2xs': 320,
    xs: 480,
    sm: 640,
    md: 768,
    lg: 1024,
    xl: 1280,
    '2xl': 1536,
  }

  const base = computed(() => width.value < breakpoints['2xs'])
  const xxs = computed(() => width.value >= breakpoints['2xs'])
  const xs = computed(() => width.value >= breakpoints.xs)
  const sm = computed(() => width.value >= breakpoints.sm)
  const md = computed(() => width.value >= breakpoints.md)
  const lg = computed(() => width.value >= breakpoints.lg)
  const xl = computed(() => width.value >= breakpoints.xl)
  const xxl = computed(() => width.value >= breakpoints['2xl'])

  /** Desktop collapse preference. `true` = collapsed. Persisted; see the note above. */
  const isSidebarActive = useStorage('sidebar', false)

  /** Mobile overlay state. Deliberately session-only. */
  const mobileSidebarOpen = ref(false)

  /** The one value the shell renders from. */
  const sidebarHidden = computed(() =>
    md.value ? isSidebarActive.value : !mobileSidebarOpen.value,
  )

  /** The scrim exists only for the mobile overlay — on desktop the rail displaces content. */
  const scrimVisible = computed(() => !md.value && mobileSidebarOpen.value)

  /**
   * Crossing up into desktop closes the overlay, so a user who opens the nav on a phone and
   * then rotates or resizes does not land on a desktop layout with a stuck scrim over it.
   */
  watch(md, (isDesktop) => {
    if (isDesktop) mobileSidebarOpen.value = false
  })

  function toggleSidebar() {
    if (md.value) isSidebarActive.value = !isSidebarActive.value
    else mobileSidebarOpen.value = !mobileSidebarOpen.value
  }

  /**
   * Dismiss. On mobile this closes the overlay; on desktop it is a no-op, because the only
   * things that call it (the scrim, a nav click) exist only while the overlay is open. It
   * deliberately does NOT write the desktop preference — navigating should not silently
   * change how the app looks the next time it is opened on a laptop.
   */
  function closeSidebar() {
    mobileSidebarOpen.value = false
  }

  return {
    isSidebarActive,
    mobileSidebarOpen,
    sidebarHidden,
    scrimVisible,
    toggleSidebar,
    closeSidebar,
    width,
    height,
    base,
    xxs,
    xs,
    sm,
    md,
    lg,
    xl,
    xxl,
  }
}
