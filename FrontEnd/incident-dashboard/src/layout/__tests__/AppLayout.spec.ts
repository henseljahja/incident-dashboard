import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

import AppLayout from '../AppLayout.vue'

let wrapper: ReturnType<typeof mount> | null = null

// These mount into the real document so focus can be asserted. Without an explicit unmount the
// layouts accumulate in `document.body` and `getElementById('main-content')` resolves to a
// PREVIOUS test's element — the skip link then appears broken when it is the fixture that is.
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/ops', component: { template: '<div />' } },
  ],
})

beforeEach(async () => {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.menus = [
    { menuId: 'incident.ops', label: 'Operations', route: '/ops', privileges: ['view'] },
  ]
  await router.push('/ops')
  await router.isReady()
})

function mountLayout() {
  wrapper = mount(AppLayout, {
    attachTo: document.body,
    slots: { default: '<p>page body</p>' },
    global: { plugins: [router, PrimeVue, ToastService] },
  })
  return wrapper
}

/**
 * Every screen used to expose only `aside` and `nav` as landmarks, and the whole heading
 * outline was the single `<h1>` below — card titles were styled divs. With no main region, no
 * heading tree and no skip link, a keyboard or screen-reader user had no mechanism at all to
 * get past the nav rail to the data. That is WCAG 2.2 2.4.1 (Bypass Blocks, Level A).
 */
describe('AppLayout landmarks', () => {
  it('wraps the page body in a main landmark', () => {
    const wrapper = mountLayout()

    const main = wrapper.get('main')
    expect(main.text()).toContain('page body')
    expect(main.attributes('id')).toBe('main-content')
  })

  it('offers a skip link as the first focusable thing in the document', () => {
    const wrapper = mountLayout()

    const skip = wrapper.get('[data-testid="skip-to-content"]')
    expect(skip.attributes('href')).toBe('#main-content')

    // Invisible until focused, but present in the DOM and reachable by Tab — `display: none`
    // would take it out of the tab order and make it decorative.
    expect(skip.classes()).toContain('sr-only')
    expect(skip.classes()).toContain('focus:not-sr-only')
  })

  /**
   * Without `tabindex="-1"` the browser scrolls `<main>` into view but leaves focus on the
   * link, so the next Tab walks straight back into the nav the user just asked to skip.
   */
  it('makes the main region programmatically focusable so the skip actually moves focus', async () => {
    const wrapper = mountLayout()
    expect(wrapper.get('main').attributes('tabindex')).toBe('-1')

    await wrapper.get('[data-testid="skip-to-content"]').trigger('click')
    expect(document.activeElement).toBe(wrapper.get('main').element)
  })

  it('renders exactly one h1, taken from the server menu payload', () => {
    const wrapper = mountLayout()

    const h1s = wrapper.findAll('h1')
    expect(h1s).toHaveLength(1)
    // The server's label is "Operations"; the page title goes through the same translated menu
    // label as the sidebar, and `nav.menu.ops` is "Incident" now that Problem and Service Request
    // sit beside it.
    expect(h1s[0]!.text()).toBe('Incident')
  })
})
