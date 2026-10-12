import PrimeVue from 'primevue/config'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import NotFoundView from '../NotFoundView.vue'

// `replace`, not `push`: the view deliberately REPLACES history so the dead URL does not sit
// in the back stack waiting to 404 again on the next Back press.
const replace = vi.fn<(to: string) => void>()
vi.mock('vue-router', () => ({ useRouter: () => ({ replace }) }))

// The view reads the auth store to decide where "back to the dashboard" goes, so it needs a
// live Pinia — same setup as ExecView.spec.ts and OpsView.spec.ts.
beforeEach(() => {
  setActivePinia(createPinia())
  replace.mockClear()
})

function mountView() {
  return mount(NotFoundView, { global: { plugins: [PrimeVue] } })
}

describe('NotFoundView', () => {
  /**
   * This route renders OUTSIDE AppLayout — no sidebar, no shell — so unlike /ops or /exec
   * nothing else on the page contributes a landmark. It shipped as a bare `<div>`, which left
   * a screen-reader user on this page with no region to jump to at all. Caught by a live
   * landmark sweep, not by any unit test, because the old markup was perfectly valid HTML.
   *
   * Pinned as the element name rather than a snapshot so restyling the page cannot quietly
   * regress the semantics.
   */
  it('is a <main> landmark, since no layout wraps this route', () => {
    const wrapper = mountView()

    expect(wrapper.element.tagName).toBe('MAIN')
    expect(wrapper.get('[data-testid="not-found"]').element.tagName).toBe('MAIN')
  })

  it('names the failure in a single h1 so the page has a heading to land on', () => {
    const wrapper = mountView()

    const headings = wrapper.findAll('h1')
    expect(headings).toHaveLength(1)
    expect(headings[0]!.text()).toBe('Page not found')
  })

  it('offers a way out rather than stranding the visitor', async () => {
    const wrapper = mountView()

    await wrapper.get('button').trigger('click')
    expect(replace).toHaveBeenCalled()
  })
})
