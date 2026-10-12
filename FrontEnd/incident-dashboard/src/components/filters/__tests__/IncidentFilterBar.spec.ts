import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { useFiltersStore } from '@/stores/filters'

import IncidentFilterBar from '../IncidentFilterBar.vue'

let wrapper: VueWrapper | null = null

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

beforeEach(() => {
  setActivePinia(createPinia())
  // jsdom does not implement matchMedia; PrimeVue's DatePicker uses it to bind a responsive
  // listener on mount. This is a jsdom environment gap, not something under test here.
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
})

const globalPlugins = { plugins: [PrimeVue] }

/**
 * PrimeVue 4 renders `id` on the outer wrapper `<div>`, not on the real focusable input, and
 * only forwards `input-id` to the control itself. A `<label for="x">` pointing at a wrapper is
 * unlabelled to a screen reader even though it "looks" associated in the DOM. These assertions
 * fail against the old `id="..."` markup because `document.getElementById(forTarget)` resolves
 * to a `<div>`, which is not a form control.
 */
/**
 * Five of the eight filters now live behind a disclosure, so every structural assertion below
 * has to open it first. A spec that checked only the three inline controls would silently lose
 * most of its coverage the moment the panel was collapsed — which is exactly the regression
 * "collapse the filter bar" could have introduced.
 */
async function mountExpanded(): Promise<VueWrapper> {
  const w = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })
  await w.get('[data-testid="filter-advanced-toggle"]').trigger('click')
  return w
}

describe('IncidentFilterBar label association', () => {
  it('associates every label with a real form control, not a wrapper div', async () => {
    wrapper = await mountExpanded()

    const labels = wrapper.findAll('label')
    expect(labels.length).toBeGreaterThan(0)

    // Real native tags, or a PrimeVue custom-widget trigger that is itself focusable and
    // carries an interactive ARIA role (PrimeVue's Select renders its trigger as a
    // `<span role="combobox" tabindex="0">`, never a native `<select>`). Either is a legitimate
    // label target; the outer wrapper `<div>` PrimeVue puts `id` on by default is neither.
    const nativeControls = new Set(['input', 'select', 'textarea', 'button'])
    const interactiveRoles = new Set(['combobox', 'textbox', 'listbox'])

    for (const label of labels) {
      const forAttr = label.attributes('for')
      expect(forAttr, `label "${label.text()}" is missing a for attribute`).toBeTruthy()

      const target = document.getElementById(forAttr!)
      expect(
        target,
        `label "${label.text()}" for="${forAttr}" has no matching element`,
      ).not.toBeNull()

      const tag = target!.tagName.toLowerCase()
      const role = target!.getAttribute('role')
      const isNativeControl = nativeControls.has(tag)
      const isFocusableWidget =
        target!.hasAttribute('tabindex') && role !== null && interactiveRoles.has(role)

      expect(
        isNativeControl || isFocusableWidget,
        `label "${label.text()}" for="${forAttr}" resolves to a <${tag}> with role="${role}", not a real focusable form control`,
      ).toBe(true)
    }
  })

  it('produces no duplicate ids across the two date pickers', async () => {
    wrapper = await mountExpanded()

    const allIds = Array.from(document.body.querySelectorAll('[id]')).map((el) => el.id)
    const seen = new Set<string>()
    const duplicates = new Set<string>()
    for (const id of allIds) {
      if (seen.has(id)) duplicates.add(id)
      seen.add(id)
    }

    expect(Array.from(duplicates)).toEqual([])
  })

  it('keeps all eight filters reachable once the disclosure is open', async () => {
    wrapper = await mountExpanded()

    // Collapsing controls is only acceptable if they are still reachable. Named explicitly so
    // a filter dropped during a layout change fails here rather than going quietly missing.
    for (const id of [
      'filter-date-from',
      'filter-date-to',
      'filter-tracker',
      'filter-stage',
      'filter-priority',
      'filter-institusi',
      'filter-category',
      'filter-has-workaround',
    ]) {
      expect(document.getElementById(id), `filter control #${id} is missing`).not.toBeNull()
    }
  })
})

describe('IncidentFilterBar active state', () => {
  it('starts collapsed with no chips when nothing is filtered', () => {
    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })

    expect(wrapper.find('[data-testid="filter-advanced-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="active-filters"]').exists()).toBe(false)
    // Nothing to clear — the control must not invite a no-op.
    expect(wrapper.get('[data-testid="filter-clear-all"]').attributes('disabled')).toBeDefined()
  })

  /**
   * The regression this guards: `OpsView` restores filter state from the query string AFTER
   * this component mounts. If the disclosure stayed shut over restored values, a shared or
   * bookmarked link would apply filters whose controls were nowhere on screen.
   */
  it('opens itself when a hidden filter is already active, as from a pasted URL', async () => {
    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })
    expect(wrapper.find('[data-testid="filter-advanced-panel"]').exists()).toBe(false)

    useFiltersStore().priority = ['High']
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="filter-advanced-panel"]').exists()).toBe(true)
  })

  it('names every active filter as its own removable chip', async () => {
    const filters = useFiltersStore()
    filters.dateFrom = '2026-07-01'
    filters.tracker = ['Incident', 'Problem']
    filters.priority = ['High']
    filters.hasWorkaround = true

    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })
    await wrapper.vm.$nextTick()

    const chips = wrapper.findAll('[data-testid="active-filter-chip"]')
    const labels = chips.map((chip) => chip.text().trim())
    expect(labels).toEqual([
      'From 2026-07-01',
      'Tracker: Incident',
      'Tracker: Problem',
      'Priority: High',
      'Has workaround: Yes',
    ])
  })

  it('removes only the chip that was clicked, leaving its siblings alone', async () => {
    const filters = useFiltersStore()
    filters.tracker = ['Incident', 'Problem', 'SR - Infra']
    filters.page = 4

    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })
    await wrapper.vm.$nextTick()

    const problem = wrapper
      .findAll('[data-testid="active-filter-chip"]')
      .find((chip) => chip.text().includes('Problem'))
    expect(problem).toBeTruthy()
    await problem!.trigger('click')

    // Narrowing by three and dropping one must not mean re-picking the other two.
    expect(filters.tracker).toEqual(['Incident', 'SR - Infra'])
    // A narrower set can have fewer pages; staying on page 5 would render an empty table.
    expect(filters.page).toBe(0)
  })
})

describe('IncidentFilterBar facet chips (KPI tiles)', () => {
  it('names each active tile facet as a removable chip, ahead of the other filters', async () => {
    const filters = useFiltersStore()
    filters.status = 'OPEN'
    filters.sla = 'AT_RISK'
    filters.tracker = ['Incident']

    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })
    await wrapper.vm.$nextTick()

    const labels = wrapper
      .findAll('[data-testid="active-filter-chip"]')
      .map((chip) => chip.text().trim())
    expect(labels).toEqual(['Status: Open', 'SLA: At risk', 'Tracker: Incident'])

    const sla = wrapper
      .findAll('[data-testid="active-filter-chip"]')
      .find((chip) => chip.text().includes('SLA: At risk'))!
    expect(sla.attributes('aria-label')).toBe('Remove filter SLA: At risk')
    await sla.trigger('click')

    expect(filters.sla).toBe(null)
    expect(filters.status).toBe('OPEN')
  })

  it('clears the facets along with every other filter', async () => {
    const filters = useFiltersStore()
    filters.status = 'CLOSED'
    filters.sla = 'BREACHED'

    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })
    await wrapper.vm.$nextTick()

    const clear = wrapper.findAll('button').find((button) => button.text() === 'Clear filters')
    expect(clear).toBeTruthy()
    await clear!.trigger('click')

    expect(filters.status).toBe(null)
    expect(filters.sla).toBe(null)
    expect(wrapper.find('[data-testid="active-filter-chip"]').exists()).toBe(false)
  })
})

describe('IncidentFilterBar scope', () => {
  function trackerControl(w: VueWrapper) {
    return w.find('#filter-tracker')
  }

  it('offers every tracker when no page has set a scope', () => {
    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })

    expect(trackerControl(wrapper).exists()).toBe(true)
  })

  it('offers the three SR trackers on the Service Request page', () => {
    useFiltersStore().setScope('SERVICE_REQUEST')
    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })

    expect(trackerControl(wrapper).exists()).toBe(true)
    const options = wrapper.findComponent({ name: 'MultiSelect' }).props('options') as string[]
    expect(options).toEqual(['SR - Infra', 'SR - BAU', 'SR - Patch'])
  })

  it.each(['INCIDENT', 'PROBLEM'] as const)(
    'hides the tracker control on the %s page, which has one tracker',
    (scope) => {
      useFiltersStore().setScope(scope)
      wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })

      expect(trackerControl(wrapper).exists()).toBe(false)
      expect(wrapper.find('label[for="filter-tracker"]').exists()).toBe(false)
    },
  )

  it('names the matrix narrowing as a removable chip, and "Clear all" removes it but not the scope', async () => {
    const filters = useFiltersStore()
    filters.setScope('INCIDENT')
    filters.matrixMissing = true
    wrapper = mount(IncidentFilterBar, { attachTo: document.body, global: globalPlugins })

    expect(wrapper.text()).toContain('Missing impact or urgency')

    await wrapper.get('[data-testid="filter-clear-all"]').trigger('click')

    expect(filters.matrixMissing).toBe(false)
    expect(filters.scope).toBe('INCIDENT')
    expect(wrapper.text()).not.toContain('Missing impact or urgency')
  })
})
