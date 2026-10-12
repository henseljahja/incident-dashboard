import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/apis/auth', () => ({
  changePassword: vi.fn<(currentPassword: string, newPassword: string) => Promise<void>>(),
}))

const push = vi.fn<(to: string) => Promise<void>>()
vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRouter: () => ({ push }),
  }
})

import * as authApi from '@/apis/auth'
import { ApiError } from '@/apis/client'
import { useAuthStore } from '@/stores/auth'

import ChangePasswordView from '../ChangePasswordView.vue'

const globalPlugins = { plugins: [PrimeVue] }

beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
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

afterEach(() => {
  vi.restoreAllMocks()
})

async function fillAndSubmit(
  wrapper: ReturnType<typeof mount>,
  current: string,
  next: string,
  confirm: string,
) {
  const currentInput = wrapper.get('#current-password')
  const newInput = wrapper.get('#new-password')
  const confirmInput = wrapper.get('#confirm-password')

  await currentInput.setValue(current)
  await newInput.setValue(next)
  await confirmInput.setValue(confirm)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('ChangePasswordView', () => {
  it('posts the current and new password to the API', async () => {
    vi.mocked(authApi.changePassword).mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.defaultRoute = '/ops'

    const wrapper = mount(ChangePasswordView, { attachTo: document.body, global: globalPlugins })

    await fillAndSubmit(wrapper, 'oldPass123', 'newPass456', 'newPass456')

    expect(authApi.changePassword).toHaveBeenCalledTimes(1)
    expect(authApi.changePassword).toHaveBeenCalledWith('oldPass123', 'newPass456')

    wrapper.unmount()
  })

  it('rejects a mismatched confirmation client-side without calling the API', async () => {
    const auth = useAuthStore()
    auth.defaultRoute = '/ops'

    const wrapper = mount(ChangePasswordView, { attachTo: document.body, global: globalPlugins })

    await fillAndSubmit(wrapper, 'oldPass123', 'newPass456', 'somethingElse')

    expect(authApi.changePassword).not.toHaveBeenCalled()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text().length).toBeGreaterThan(0)
    expect(push).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('renders a visible, announced error on a server rejection and does not navigate away', async () => {
    vi.mocked(authApi.changePassword).mockRejectedValue(
      new ApiError('Current password is incorrect', 400),
    )
    const auth = useAuthStore()
    auth.defaultRoute = '/ops'

    const wrapper = mount(ChangePasswordView, { attachTo: document.body, global: globalPlugins })

    await fillAndSubmit(wrapper, 'wrongPass', 'newPass456', 'newPass456')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Current password is incorrect')
    expect(push).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  /**
   * These two tests previously asserted a redirect to the role's DEFAULT_ROUTE. That was the
   * bug, not the contract: `POST /auth/change-password` revokes every outstanding refresh
   * token and clears the refresh cookie, so landing on the dashboard put the user there with a
   * `change-password`-scoped token that AuthFilter refuses on every /api/** route, and no way
   * to recover. The ops view came up with empty KPIs and the wallboard showed "Cannot reach the
   * dashboard service" — on every first login, since the bootstrap admin is seeded with
   * MUST_CHANGE_PASSWORD=Y.
   *
   * The route out is sign-in with the new password, which is also the honest end to a rotation.
   */
  it('sends the user back to sign in after a rotation, because the server revoked the session', async () => {
    vi.mocked(authApi.changePassword).mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.defaultRoute = '/exec'
    auth.accessToken = 'change-password-scoped-token'

    const wrapper = mount(ChangePasswordView, { attachTo: document.body, global: globalPlugins })
    await fillAndSubmit(wrapper, 'oldPass123', 'newPass456', 'newPass456')

    expect(push).toHaveBeenCalledWith({ path: '/login', query: { passwordChanged: '1' } })
    // The dead token must not survive the redirect.
    expect(auth.accessToken).toBeUndefined()

    wrapper.unmount()
  })

  it('never routes to the dashboard on success, whatever the role default route is', async () => {
    vi.mocked(authApi.changePassword).mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.defaultRoute = '/wallboard'

    const wrapper = mount(ChangePasswordView, { attachTo: document.body, global: globalPlugins })
    await fillAndSubmit(wrapper, 'oldPass123', 'newPass456', 'newPass456')

    // The exact regression: a push to defaultRoute here strands the user in a dead session.
    expect(push).not.toHaveBeenCalledWith('/wallboard')
    expect(push).toHaveBeenCalledWith({ path: '/login', query: { passwordChanged: '1' } })

    wrapper.unmount()
  })

  it('associates every label with a real form control, not a wrapper div', () => {
    const auth = useAuthStore()
    auth.defaultRoute = '/ops'

    const wrapper = mount(ChangePasswordView, { attachTo: document.body, global: globalPlugins })

    const labels = wrapper.findAll('label')
    expect(labels.length).toBeGreaterThan(0)

    for (const label of labels) {
      const forAttr = label.attributes('for')
      expect(forAttr, `label "${label.text()}" is missing a for attribute`).toBeTruthy()

      const target = document.getElementById(forAttr!)
      expect(
        target,
        `label "${label.text()}" for="${forAttr}" has no matching element`,
      ).not.toBeNull()

      const tag = target!.tagName.toLowerCase()
      expect(
        tag === 'input',
        `label "${label.text()}" for="${forAttr}" resolves to a <${tag}>, not a real focusable form control`,
      ).toBe(true)
    }

    wrapper.unmount()
  })
})
