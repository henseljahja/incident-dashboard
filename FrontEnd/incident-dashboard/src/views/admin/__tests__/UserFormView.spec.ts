import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import type { AdminUser, AssignableRole, CreatedUser } from '@/apis/admin'

vi.mock('@/apis/admin', async () => {
  const actual = await vi.importActual<typeof import('@/apis/admin')>('@/apis/admin')
  return {
    ...actual,
    fetchRoles: vi.fn<() => Promise<AssignableRole[]>>(),
    fetchUser: vi.fn<(username: string) => Promise<AdminUser>>(),
    createUser: vi.fn<(body: unknown) => Promise<CreatedUser>>(),
    updateUser: vi.fn<(username: string, body: unknown) => Promise<AdminUser>>(),
    resetPassword: vi.fn<(username: string) => Promise<CreatedUser>>(),
  }
})

import { createUser, fetchRoles, fetchUser, resetPassword, updateUser } from '@/apis/admin'

import UserFormView from '../UserFormView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/admin/users', component: { template: '<div />' } },
    { path: '/admin/users/create', component: { template: '<div />' } },
    { path: '/admin/users/:username', component: { template: '<div />' } },
    { path: '/admin/users/:username/edit', component: { template: '<div />' } },
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ],
})

const ROLES: AssignableRole[] = [
  { roleId: 'ADMIN', name: 'Administrator', defaultRoute: '/ops', priority: 10 },
  { roleId: 'VIEWER', name: 'Viewer (read only)', defaultRoute: '/ops', priority: 50 },
]

const EXISTING: AdminUser = {
  username: 'ops.manager',
  name: 'Ops Manager',
  email: 'ops@example.test',
  roleId: 'VIEWER',
  roleName: 'Viewer (read only)',
  status: 'ACTIVE',
  lastLoginAt: '2026-08-01T10:00:00Z',
  createdAt: '2026-07-01T10:00:00Z',
}

beforeEach(() => {
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
  vi.resetAllMocks()
  vi.mocked(fetchRoles).mockResolvedValue(ROLES)
  vi.mocked(fetchUser).mockResolvedValue(EXISTING)
})

async function mountForm(mode: 'create' | 'view' | 'edit', path: string) {
  setActivePinia(createPinia())
  await router.push(path)
  await router.isReady()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const wrapper = mount(UserFormView, {
    props: { mode },
    global: {
      plugins: [router, PrimeVue, [VueQueryPlugin, { queryClient }]],
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

describe('UserFormView', () => {
  it('shows the one-time password and REPLACES the form, so it cannot be scrolled past', async () => {
    vi.mocked(createUser).mockResolvedValue({
      username: 'new.user',
      temporaryPassword: 'Rk7#mQp2Xb4wZn9t',
    })
    const wrapper = await mountForm('create', '/admin/users/create')

    await wrapper.getComponent({ name: 'Form' }).vm.$emit('submit', {
      valid: true,
      values: { username: 'new.user', name: 'New User', email: '', roleId: 'VIEWER' },
    })
    await flushPromises()

    const banner = wrapper.get('[data-testid="user-temp-password"]')
    expect(banner.text()).toContain('Rk7#mQp2Xb4wZn9t')
    // The server stores only a bcrypt hash, so there is no endpoint that could show this
    // again. The form must go away rather than invite a second submit that would 409.
    expect(banner.text()).toContain('cannot be shown again')
    expect(wrapper.findComponent({ name: 'Form' }).exists()).toBe(false)
  })

  it('does not navigate away while the one-time password is on screen', async () => {
    vi.mocked(createUser).mockResolvedValue({
      username: 'new.user',
      temporaryPassword: 'Rk7#mQp2Xb4wZn9t',
    })
    const wrapper = await mountForm('create', '/admin/users/create')

    await wrapper.getComponent({ name: 'Form' }).vm.$emit('submit', {
      valid: true,
      values: { username: 'new.user', name: 'New User', email: '', roleId: 'VIEWER' },
    })
    await flushPromises()

    // Redirecting to the list on success — the obvious thing to do, and what the edit path
    // does — would destroy the only copy of the credential before anyone read it.
    expect(router.currentRoute.value.path).toBe('/admin/users/create')
    await wrapper.get('[data-testid="user-temp-password-done"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/users')
  })

  it('never submits an invalid form, whatever the submit button state', async () => {
    const wrapper = await mountForm('create', '/admin/users/create')

    await wrapper.getComponent({ name: 'Form' }).vm.$emit('submit', {
      valid: false,
      values: { username: 'x', name: '', email: 'nope', roleId: '' },
    })
    await flushPromises()

    // The button is also disabled, but that is an affordance. This asserts the handler itself
    // refuses, so a keyboard submit or a stale disabled state cannot get through.
    expect(createUser).not.toHaveBeenCalled()
  })

  it('surfaces a server conflict instead of failing silently', async () => {
    vi.mocked(createUser).mockRejectedValue(new Error('A user with that username already exists.'))
    const wrapper = await mountForm('create', '/admin/users/create')

    await wrapper.getComponent({ name: 'Form' }).vm.$emit('submit', {
      valid: true,
      values: { username: 'dup.user', name: 'Dup', email: '', roleId: 'VIEWER' },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="user-form-error"]').text()).toContain('already exists')
    expect(wrapper.find('[data-testid="user-temp-password"]').exists()).toBe(false)
  })

  it('leaves every control disabled in view mode', async () => {
    const wrapper = await mountForm('view', '/admin/users/ops.manager')

    expect(componentAt(wrapper, '[data-testid="field-name"]').props('disabled')).toBe(true)
    expect(componentAt(wrapper, '[data-testid="field-role"]').props('disabled')).toBe(true)
    expect(componentAt(wrapper, '[data-testid="field-active"]').props('disabled')).toBe(true)
    // No Save, and no Reset Password — viewing an account must not be a write surface.
    expect(wrapper.find('[data-testid="user-form-submit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="user-reset-password"]').exists()).toBe(false)
  })

  it('keeps username disabled on edit — the server has no rename path', async () => {
    const wrapper = await mountForm('edit', '/admin/users/ops.manager/edit')

    expect(componentAt(wrapper, '[data-testid="field-username"]').props('disabled')).toBe(true)
    expect(componentAt(wrapper, '[data-testid="field-name"]').props('disabled')).toBe(false)
  })

  it('sends the edited values, and the active flag, on save', async () => {
    vi.mocked(updateUser).mockResolvedValue(EXISTING)
    const wrapper = await mountForm('edit', '/admin/users/ops.manager/edit')

    await wrapper.getComponent({ name: 'Form' }).vm.$emit('submit', {
      valid: true,
      values: { name: 'Renamed', email: 'r@example.test', roleId: 'ADMIN', active: false },
    })
    await flushPromises()

    expect(updateUser).toHaveBeenCalledWith('ops.manager', {
      name: 'Renamed',
      email: 'r@example.test',
      roleId: 'ADMIN',
      active: false,
    })
    expect(router.currentRoute.value.path).toBe('/admin/users')
  })

  it('shows a reset password the same way a created one is shown', async () => {
    vi.mocked(resetPassword).mockResolvedValue({
      username: 'ops.manager',
      temporaryPassword: 'Ts4$vLm8Yq2rBd6k',
    })
    const wrapper = await mountForm('edit', '/admin/users/ops.manager/edit')

    await wrapper.get('[data-testid="user-reset-password"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="user-temp-password"]').text()).toContain('Ts4$vLm8Yq2rBd6k')
    expect(resetPassword).toHaveBeenCalledWith('ops.manager')
  })
})

/**
 * These drive the REAL `Form` component and its REAL zod resolver, rather than emitting a
 * hand-written `submit` payload.
 *
 * The distinction is the whole point. Every other test in this file fabricates
 * `{valid, values}`, and that is exactly how the create screen shipped broken: the schema
 * carried an `active` key that the create form does not render, so zod failed against
 * `undefined`, filed the error under a field with no state — leaving `valid` TRUE — and
 * returned `values: undefined`. The submit handler then read `values.username` off nothing and
 * the screen showed "Cannot read properties of undefined (reading 'username')" with no
 * validation message to explain it. A fabricated payload can never catch that, because it IS
 * the assumption under test.
 */
describe('UserFormView resolver, exercised for real', () => {
  it('produces values for a valid create submission, with valid true', async () => {
    vi.mocked(createUser).mockResolvedValue({
      username: 'real.user',
      temporaryPassword: 'Zq3#nWt7Kc5vHm1s',
    })
    const wrapper = await mountForm('create', '/admin/users/create')

    await wrapper.get('[data-testid="field-username"]').setValue('real.user')
    await wrapper.get('[data-testid="field-name"]').setValue('Real User')
    await wrapper.get('[data-testid="field-email"]').setValue('')
    // Through the Form's own `setValues` rather than the Select: PrimeVue's Select renders a
    // `role=combobox` span whose overlay is appended to body, and a bare `$emit` on the
    // component does not travel through FormField's binding. `setValues` writes real form
    // state, so the resolver still runs against it — which is the part under test.
    const formVm = wrapper.getComponent({ name: 'Form' }).vm as unknown as {
      setValues: (values: Record<string, unknown>) => void
    }
    formVm.setValues({ roleId: 'VIEWER' })
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // If the schema and the rendered fields disagree, this call never happens and the screen
    // shows a TypeError instead.
    expect(createUser).toHaveBeenCalledWith({
      username: 'real.user',
      name: 'Real User',
      email: '',
      roleId: 'VIEWER',
    })
  })

  it('blocks a genuinely invalid create submission at the resolver', async () => {
    const wrapper = await mountForm('create', '/admin/users/create')

    await wrapper.get('[data-testid="field-username"]').setValue('ab')
    await wrapper.get('[data-testid="field-name"]').setValue('')
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(createUser).not.toHaveBeenCalled()
    // And the reason has to be legible, not just an inert button.
    expect(wrapper.text()).toContain('at least 3 characters')
  })
})
