import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'

import { useAuthStore } from '@/stores/auth'

import vCan, { normalizeRules } from '../can'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('normalizeRules', () => {
  it('normalizes a bare tuple to a single rule', () => {
    expect(normalizeRules(['incident.ops', 'view'])).toEqual([[['incident.ops', 'view']]])
  })

  it('normalizes a single-rule list with an ABAC condition', () => {
    expect(normalizeRules([[['incident.ops', 'view'], true]])).toEqual([
      [['incident.ops', 'view'], true],
    ])
  })

  it('normalizes a list of rules', () => {
    expect(
      normalizeRules([
        ['incident.ops', 'view'],
        ['incident.ops', 'export'],
      ]),
    ).toEqual([[['incident.ops', 'view']], [['incident.ops', 'export']]])
  })
})

// The directive target is nested inside a wrapper element rather than mounted as the
// component root — `el.remove()` detaches the element from its parent, and Test Utils'
// `find()` checks the *mounted* root element's own identity first; a removed root element
// would still self-match, hiding a real bug. Nesting makes `find()` fall through to a real
// `querySelector`, which correctly reflects the DOM removal.
function mountWithDirective(template: string) {
  const Component = defineComponent({
    directives: { can: vCan },
    template: `<div>${template}</div>`,
  })
  return mount(Component)
}

describe('v-can directive', () => {
  it('grants when the menu carries the privilege', () => {
    const auth = useAuthStore()
    auth.menus = [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }]

    const wrapper = mountWithDirective(`<button v-can="['incident.ops', 'view']">Export</button>`)

    expect(wrapper.find('button').exists()).toBe(true)
  })

  it('denies a privilege the role does not hold', () => {
    const auth = useAuthStore()
    auth.menus = [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }]

    const wrapper = mountWithDirective(`<button v-can="['incident.ops', 'export']">Export</button>`)

    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('denies unknown menus rather than defaulting open', () => {
    const auth = useAuthStore()
    auth.menus = []

    const wrapper = mountWithDirective(
      `<button v-can="['incident.unknown', 'view']">Secret</button>`,
    )

    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('denies against an empty permission set', () => {
    const auth = useAuthStore()
    auth.menus = []

    const wrapper = mountWithDirective(`<button v-can="['incident.ops', 'view']">Anything</button>`)

    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('grants when the caller holds the privilege on ANY of several candidate menus', () => {
    const auth = useAuthStore()
    auth.menus = [
      {
        menuId: 'incident.wallboard',
        label: 'Wallboard',
        route: '/wallboard',
        privileges: ['view'],
      },
    ]

    const wrapper = mountWithDirective(
      `<div class="target" v-can="[['incident.ops', 'incident.exec', 'incident.wallboard'], 'view']">Metrics</div>`,
    )

    expect(wrapper.find('.target').exists()).toBe(true)
  })

  it('denies an array of candidate menus when none match', () => {
    const auth = useAuthStore()
    auth.menus = [
      {
        menuId: 'incident.wallboard',
        label: 'Wallboard',
        route: '/wallboard',
        privileges: ['view'],
      },
    ]

    const wrapper = mountWithDirective(
      `<div class="target" v-can="[['incident.ops', 'incident.exec'], 'view']">Metrics</div>`,
    )

    expect(wrapper.find('.target').exists()).toBe(false)
  })

  it('supports .not to invert the result', () => {
    const auth = useAuthStore()
    auth.menus = [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }]

    const wrapper = mountWithDirective(
      `<button v-can.not="['incident.ops', 'export']">Read-only banner</button>`,
    )

    expect(wrapper.find('button').exists()).toBe(true)
  })
})
