import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import type { PermissionMenu } from '@/apis/auth'
import { useAuthStore } from '@/stores/auth'

import AppSidebar from '../AppSidebar.vue'

/**
 * The sidebar shipped with no test at all, which is how a nav entry pointing at a screen that
 * was never built ("User Management" -> `/admin/users` -> blank page) reached a running demo.
 * The nav is entirely server-driven, so these assertions are about faithfully rendering the
 * menu payload — NOT about hard-coding which menus exist.
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/ops', component: { template: '<div />' } },
    { path: '/problem', component: { template: '<div />' } },
    { path: '/service-requests', component: { template: '<div />' } },
    { path: '/exec', component: { template: '<div />' } },
    { path: '/wallboard', component: { template: '<div />' } },
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ],
})

function menu(overrides: Partial<PermissionMenu> = {}): PermissionMenu {
  return {
    menuId: 'incident.ops',
    label: 'Operations',
    route: '/ops',
    privileges: ['view'],
    ...overrides,
  }
}

function mountSidebar(
  menus: PermissionMenu[],
  props: Partial<{ hidden: boolean; scrim: boolean }> = {},
) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.menus = menus
  auth.displayName = 'Ops Lead'

  return mount(AppSidebar, {
    props: {
      hidden: false,
      scrim: false,
      closeSidebar: vi.fn<() => void>(),
      logout: vi.fn<() => void>(),
      ...props,
    },
    global: { plugins: [router, PrimeVue] },
  })
}

beforeEach(async () => {
  await router.push('/ops')
  await router.isReady()
})

describe('AppSidebar', () => {
  it('renders one nav entry per permitted menu, using the payload label and route', () => {
    const wrapper = mountSidebar([
      menu(),
      menu({ menuId: 'incident.exec', label: 'Executive', route: '/exec' }),
    ])

    const links = wrapper.findAll('nav a')
    expect(links).toHaveLength(2)
    // Translated through `nav.menu.ops`, not the payload's "Operations".
    expect(links[0]!.text()).toContain('Incident')
    expect(links[0]!.attributes('href')).toBe('/ops')
    expect(links[1]!.text()).toContain('Executive')
  })

  it('lists Incident, Problem and Service Request first and in that order, whatever the payload order', () => {
    const wrapper = mountSidebar([
      menu({ menuId: 'incident.exec', label: 'Executive', route: '/exec' }),
      menu({ menuId: 'incident.sr', label: 'SR', route: '/service-requests' }),
      menu({ menuId: 'incident.wallboard', label: 'Wallboard', route: '/wallboard' }),
      menu({ menuId: 'incident.problem', label: 'Problem', route: '/problem' }),
      menu(),
    ])

    const links = wrapper.findAll('nav a')
    expect(links.map((link) => link.text())).toEqual([
      'Incident',
      'Problem',
      'Service Request',
      'Wallboard',
      'Executive',
    ])
    expect(links.map((link) => link.attributes('href'))).toEqual([
      '/ops',
      '/problem',
      '/service-requests',
      '/wallboard',
      '/exec',
    ])
    // Each menu has its own glyph rather than the neutral fallback.
    expect(wrapper.findAll('nav a i').map((icon) => icon.classes().join(' '))).not.toContain(
      'pi pi-circle',
    )
  })

  it('marks only the current ticket group with aria-current', async () => {
    await router.push('/problem')
    const wrapper = mountSidebar([
      menu(),
      menu({ menuId: 'incident.problem', label: 'Problem', route: '/problem' }),
      menu({ menuId: 'incident.sr', label: 'SR', route: '/service-requests' }),
    ])

    expect(wrapper.findAll('nav a').map((link) => link.attributes('aria-current'))).toEqual([
      undefined,
      'page',
      undefined,
    ])
  })

  it('renders nothing for a caller with no menus rather than inventing a default nav', () => {
    // The permission payload is the sole authority on what exists; a fallback entry here would
    // show a VIEWER a link the server never granted.
    const wrapper = mountSidebar([])

    expect(wrapper.findAll('nav a')).toHaveLength(0)
  })

  it('marks the current route with aria-current', () => {
    const wrapper = mountSidebar([
      menu(),
      menu({ menuId: 'incident.exec', label: 'Executive', route: '/exec' }),
    ])

    const links = wrapper.findAll('nav a')
    expect(links[0]!.attributes('aria-current')).toBe('page')
    expect(links[1]!.attributes('aria-current')).toBeUndefined()
  })

  it('is inert while off-canvas, so Tab cannot walk into an invisible menu', () => {
    const wrapper = mountSidebar([menu()], { hidden: true })
    const aside = wrapper.get('aside')

    expect(aside.attributes('inert')).toBeDefined()
    expect(aside.attributes('aria-hidden')).toBe('true')
    expect(aside.classes()).toContain('-translate-x-full')
  })

  it('is focusable and on-screen while open', () => {
    const wrapper = mountSidebar([menu()], { hidden: false })
    const aside = wrapper.get('aside')

    expect(aside.attributes('inert')).toBeUndefined()
    expect(aside.attributes('aria-hidden')).toBeUndefined()
    expect(aside.classes()).toContain('translate-x-0')
  })

  it('renders the scrim only when asked, and dismisses on click', async () => {
    const closeSidebar = vi.fn<() => void>()
    setActivePinia(createPinia())
    useAuthStore().menus = [menu()]

    const wrapper = mount(AppSidebar, {
      props: { hidden: false, scrim: true, closeSidebar, logout: vi.fn<() => void>() },
      global: { plugins: [router, PrimeVue] },
    })

    const scrim = wrapper.get('[aria-hidden="true"].fixed.inset-0')
    await scrim.trigger('click')
    expect(closeSidebar).toHaveBeenCalled()
  })

  it('dismisses the overlay when a destination is chosen', async () => {
    const closeSidebar = vi.fn<() => void>()
    setActivePinia(createPinia())
    useAuthStore().menus = [menu({ menuId: 'incident.exec', label: 'Executive', route: '/exec' })]

    const wrapper = mount(AppSidebar, {
      props: { hidden: false, scrim: true, closeSidebar, logout: vi.fn<() => void>() },
      global: { plugins: [router, PrimeVue] },
    })

    await wrapper.get('nav a').trigger('click')
    // Otherwise a phone user lands on the new view with the nav still covering it.
    expect(closeSidebar).toHaveBeenCalled()
  })
})
