import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import type { AdminUser, AdminUserPage } from '@/apis/admin'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/apis/admin', async () => {
  const actual = await vi.importActual<typeof import('@/apis/admin')>('@/apis/admin')
  return {
    ...actual,
    fetchUsers: vi.fn<(search: string, page: number, size: number) => Promise<AdminUserPage>>(),
  }
})

import { fetchUsers } from '@/apis/admin'

import UserTableView from '../UserTableView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/admin/users', component: { template: '<div />' } },
    { path: '/admin/users/create', component: { template: '<div />' } },
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ],
})

function user(overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    username: 'ops.manager',
    name: 'Ops Manager',
    email: 'ops@example.test',
    roleId: 'OPS_MANAGER',
    roleName: 'Ops Manager',
    status: 'ACTIVE',
    lastLoginAt: null,
    createdAt: null,
    ...overrides,
  }
}

function page(content: AdminUser[]): AdminUserPage {
  return { content, page: 0, size: 10, totalElements: content.length, totalPages: 1 }
}

// jsdom has no matchMedia, and PrimeVue's Select (the rows-per-page control) calls it on
// mount to pick its touch/desktop presentation. Stubbed rather than stubbing the Select away,
// because the rows-per-page control is what one of these tests drives.
beforeEach(async () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
  vi.useRealTimers()
  vi.resetAllMocks()
  vi.mocked(fetchUsers).mockResolvedValue(page([user()]))
  await router.push('/admin/users')
  await router.isReady()
})

async function mountTable() {
  setActivePinia(createPinia())
  useAuthStore().menus = [
    {
      menuId: 'incident.admin',
      label: 'User Management',
      route: '/admin/users',
      privileges: ['view', 'manage'],
    },
  ]
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const wrapper = mount(UserTableView, {
    global: {
      plugins: [router, PrimeVue, [VueQueryPlugin, { queryClient }]],
      // AppLayout renders the sidebar, which reads matchMedia on mount; jsdom has none.
      stubs: { AppLayout: { template: '<div><slot /></div>' } },
    },
  })
  await flushPromises()
  return wrapper
}

/**
 * `getComponent(selector)` is typed as `WrapperLike`, which has no `props()` — a string
 * selector cannot tell vue-tsc which component it will land on, and a bare `VueWrapper` types
 * `props()` as `never` for the same reason. Narrowed to the one method these tests use, once,
 * rather than cast at each call site.
 */
function componentAt(wrapper: VueWrapper, selector: string): { props(name: string): unknown } {
  return wrapper.getComponent(selector) as unknown as { props(name: string): unknown }
}

describe('UserTableView', () => {
  it('sends the search term to the SERVER rather than filtering the current page', async () => {
    const wrapper = await mountTable()
    vi.mocked(fetchUsers).mockClear()

    await wrapper.get('[data-testid="user-search"]').setValue('findme')
    await wrapper.get('[data-testid="user-search-submit"]').trigger('click')
    await flushPromises()

    // The behaviour this pins is the one deliberate divergence from yob, whose Table.vue
    // filters `dataUser.content` in the browser. Client-side filtering silently searches only
    // the page on screen, so a match on page 3 returns nothing while page 1 is displayed.
    expect(fetchUsers).toHaveBeenCalledWith('findme', 0, 10)
  })

  it('returns to the first page when the page SIZE changes', async () => {
    const wrapper = await mountTable()
    const table = wrapper.findComponent({ name: 'DataTable' })

    // Land on a later page, then change how many rows fit on one.
    table.vm.$emit('page', { page: 4, rows: 10 })
    await flushPromises()
    vi.mocked(fetchUsers).mockClear()

    table.vm.$emit('page', { page: 4, rows: 100 })
    await flushPromises()

    // Staying on page 4 at 100-per-page asks for rows 400-500 of a set that now has one page,
    // and the table renders empty for no visible reason.
    // Indexed rather than `.at(-1)`: the production tsconfig's lib target predates ES2022,
    // and the Docker build type-checks these files even though a local dev run may not.
    const calls = vi.mocked(fetchUsers).mock.calls
    const lastCall = calls[calls.length - 1]
    expect(lastCall?.[1]).toBe(0)
    expect(lastCall?.[2]).toBe(100)
  })

  it('surfaces a load failure with a retry instead of an empty table', async () => {
    vi.mocked(fetchUsers).mockRejectedValue(new Error('Session expired.'))
    const wrapper = await mountTable()

    // An empty table and a failed request look identical to a reader. The Executive view
    // shipped with exactly this gap and presented a dead session as a permanent skeleton.
    const banner = wrapper.get('[data-testid="user-load-error"]')
    expect(banner.text()).toContain('Session expired.')
    expect(wrapper.find('[data-testid="user-table-empty"]').exists()).toBe(false)
  })

  /**
   * Two assertions, and the second is the one that matters.
   *
   * This spec used to assert `severity === 'danger'` — a value `Message` does not accept. It
   * passed (the prop is a string, nothing validates it) while the shipped pill rendered as bare
   * unstyled text, because no theme rule matches `p-message-danger`. The test was green and the
   * most urgent status on the screen was the only one with no visual marking.
   *
   * So: assert the weight is above a routine warning AND that the value is one the component
   * can actually paint. A future status that reaches for `danger`, `critical` or any other
   * Tag-flavoured word fails here instead of shipping invisible.
   */
  const MESSAGE_SEVERITIES = ['success', 'info', 'warn', 'error', 'secondary', 'contrast']

  it('renders a locked account at error weight, not as a routine warning', async () => {
    vi.mocked(fetchUsers).mockResolvedValue(
      page([user({ username: 'locked.user', status: 'LOCKED' })]),
    )
    const wrapper = await mountTable()

    const pill = componentAt(wrapper, '[data-testid="user-status-locked.user"]')
    expect(pill.props('severity')).toBe('error')
    expect(wrapper.text()).toContain('Locked')
  })

  it('gives every status a severity PrimeVue Message can actually paint', async () => {
    const statuses = ['ACTIVE', 'PENDING', 'INACTIVE', 'LOCKED'] as const
    vi.mocked(fetchUsers).mockResolvedValue(
      page(statuses.map((status) => user({ username: `${status.toLowerCase()}.user`, status }))),
    )
    const wrapper = await mountTable()

    for (const status of statuses) {
      const pill = componentAt(wrapper, `[data-testid="user-status-${status.toLowerCase()}.user"]`)
      // An unsupported value is not rejected by the component — it renders as unstyled text,
      // which is indistinguishable from "this row has no status" to the person reading it.
      expect(MESSAGE_SEVERITIES).toContain(pill.props('severity'))
    }
  })
})
