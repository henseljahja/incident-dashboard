import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue, { type PrimeVueConfiguration } from 'primevue/config'
import { defineComponent, h } from 'vue'
import { useI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it } from 'vitest'

import { LOCALE_STORAGE_KEY } from '@/i18n'

import LanguageSwitch from '../LanguageSwitch.vue'

beforeEach(() => {
  setActivePinia(createPinia())
})

/** A tile label (chrome) next to a category name (data), the way the dashboard shows both. */
const Host = defineComponent(() => {
  const { t } = useI18n()
  return () =>
    h('div', [
      h(LanguageSwitch),
      h('span', { 'data-testid': 'chrome' }, t('kpi.atRisk')),
      h('span', { 'data-testid': 'data' }, 'Unclassified'),
    ])
})

describe('LanguageSwitch', () => {
  it('re-renders chrome in Indonesian without a reload, leaving data values alone', async () => {
    const wrapper = mount(Host, { global: { plugins: [PrimeVue] } })
    const primevue = (wrapper.vm as unknown as { $primevue: { config: PrimeVueConfiguration } })
      .$primevue

    expect(wrapper.get('[data-testid="chrome"]').text()).toBe('At risk')
    expect(primevue.config.locale?.dayNames?.[0]).toBe('Sunday')

    await wrapper.get('[data-testid="language-option-id"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="chrome"]').text()).toBe('Berisiko')
    expect(wrapper.get('[data-testid="data"]').text()).toBe('Unclassified')
    expect(document.documentElement.lang).toBe('id')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('id')
    // PrimeVue's own labels (calendar, paginator) switch with it.
    expect(primevue.config.locale?.dayNames?.[0]).toBe('Minggu')

    await wrapper.get('[data-testid="language-option-en"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="chrome"]').text()).toBe('At risk')
    expect(document.documentElement.lang).toBe('en')
    expect(primevue.config.locale?.dayNames?.[0]).toBe('Sunday')
  })

  it('is a labelled group whose options name each language in that language', () => {
    const wrapper = mount(LanguageSwitch, { global: { plugins: [PrimeVue] } })

    const group = wrapper.get('[role="group"]')
    const labelId = group.attributes('aria-labelledby')
    expect(labelId).toBeTruthy()
    expect(wrapper.get(`[id="${labelId}"]`).text()).toBe('Language')
    expect(wrapper.find('[lang="id"]').text()).toBe('Bahasa Indonesia')
    expect(group.findAll('button')).toHaveLength(2)
  })

  it('does not let the selection be cleared by clicking the active option', async () => {
    const wrapper = mount(LanguageSwitch, { global: { plugins: [PrimeVue] } })

    await wrapper.get('[data-testid="language-option-en"]').trigger('click')
    await flushPromises()

    expect(document.documentElement.lang).toBe('en')
    expect(wrapper.get('[data-testid="language-option-en"]').text()).toBe('EN')
  })
})
