import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type MountingOptions } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import type { Component } from 'vue'

import type { PriorityCounts } from '@/apis/insights'
import { i18n } from '@/i18n'

export const counts = (p: Partial<PriorityCounts> = {}): PriorityCounts => ({
  critical: 0,
  high: 0,
  medium: 0,
  low: 0,
  other: 0,
  ...p,
})

// jsdom has no matchMedia; PrimeVue's DatePicker binds a responsive listener with it on mount.
window.matchMedia =
  window.matchMedia ??
  ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }))

const mounted: { unmount: () => void }[] = []

/** Unmounts everything `mountInsight` attached to the document; call from `afterEach`. */
export function unmountAll() {
  while (mounted.length) mounted.pop()!.unmount()
}

/** Mounts with the plugins every insight widget needs: Pinia, i18n, PrimeVue, a fresh query cache. */
export async function mountInsight(component: Component, options: MountingOptions<unknown> = {}) {
  setActivePinia(createPinia())
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const wrapper = mount(component, {
    attachTo: document.body,
    ...options,
    global: {
      plugins: [i18n, PrimeVue, [VueQueryPlugin, { queryClient }]],
      stubs: {
        BaseChart: { template: '<div data-testid="chart" />' },
        AppLayout: { template: '<div><slot name="header-actions" /><slot /></div>' },
        FreshnessIndicator: true,
        ...(options.global?.stubs as object),
      },
      ...options.global,
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
