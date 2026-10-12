import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import { useLayout } from '../useLayout'

/**
 * These tests exist because of a shipped blocker, not for coverage.
 *
 * The redesign rendered the 256px rail at `translate-x-0` unconditionally while the content's
 * `ml-64` offset was `md`-gated, so on any viewport under 768px the nav sat directly on top of
 * the page: the logo covered the title, the menu covered the KPI cards, and nothing could be
 * dismissed. Nothing in CI looked at a narrow viewport, so it reached a running demo.
 */
async function setWidth(width: number) {
  window.innerWidth = width
  window.dispatchEvent(new Event('resize'))
  await nextTick()
}

describe('useLayout', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('hides the sidebar by default below the md breakpoint', async () => {
    await setWidth(375)
    const { sidebarHidden, scrimVisible } = useLayout()

    expect(sidebarHidden.value).toBe(true)
    expect(scrimVisible.value).toBe(false)
  })

  it('shows the sidebar by default at desktop widths', async () => {
    await setWidth(1280)
    const { sidebarHidden } = useLayout()

    expect(sidebarHidden.value).toBe(false)
  })

  it('opens the mobile sidebar as an overlay with a scrim', async () => {
    await setWidth(375)
    const { sidebarHidden, scrimVisible, toggleSidebar } = useLayout()

    toggleSidebar()
    await nextTick()

    expect(sidebarHidden.value).toBe(false)
    // The scrim is what makes it an overlay rather than a layer sitting on live content.
    expect(scrimVisible.value).toBe(true)
  })

  it('closes the mobile overlay on dismiss', async () => {
    await setWidth(375)
    const { sidebarHidden, toggleSidebar, closeSidebar } = useLayout()

    toggleSidebar()
    await nextTick()
    closeSidebar()
    await nextTick()

    expect(sidebarHidden.value).toBe(true)
  })

  it('never shows a scrim on desktop, where the rail displaces content instead', async () => {
    await setWidth(1280)
    const { scrimVisible, toggleSidebar } = useLayout()

    toggleSidebar()
    await nextTick()

    expect(scrimVisible.value).toBe(false)
  })

  it('drops the mobile overlay when the viewport grows past md', async () => {
    await setWidth(375)
    const { sidebarHidden, scrimVisible, toggleSidebar } = useLayout()
    toggleSidebar()
    await nextTick()
    expect(scrimVisible.value).toBe(true)

    await setWidth(1280)

    // Otherwise a rotate or resize strands a desktop layout under a stuck scrim.
    expect(scrimVisible.value).toBe(false)
    expect(sidebarHidden.value).toBe(false)
  })

  it('does not let the persisted desktop preference hide the mobile overlay', async () => {
    // A user who collapsed the rail on a laptop must still be able to open the nav on a phone;
    // the persisted flag is a DESKTOP preference and has no meaning at 375px.
    localStorage.setItem('sidebar', 'true')
    await setWidth(375)
    const { sidebarHidden, toggleSidebar } = useLayout()

    toggleSidebar()
    await nextTick()

    expect(sidebarHidden.value).toBe(false)
  })

  it('honours the persisted collapse preference on desktop', async () => {
    localStorage.setItem('sidebar', 'true')
    await setWidth(1280)
    const { sidebarHidden } = useLayout()

    expect(sidebarHidden.value).toBe(true)
  })
})
