import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthTokenResponse, PermissionPayload } from '@/apis/auth'

vi.mock('@/apis/auth', () => ({
  login: vi.fn<(username: string, password: string) => Promise<AuthTokenResponse>>(),
  logout: vi.fn<() => Promise<void>>(),
  refreshToken: vi.fn<() => Promise<{ token: string }>>(),
  me: vi.fn<() => Promise<PermissionPayload>>(),
  changePassword: vi.fn<(currentPassword: string, newPassword: string) => Promise<void>>(),
}))

import * as authApi from '@/apis/auth'

import { CHANGE_PASSWORD_ROUTE, LOGIN_ROUTE, useAuthStore } from '../auth'

function tokenResponse(overrides: Partial<AuthTokenResponse> = {}): AuthTokenResponse {
  return {
    token: 'access-token',
    username: 'ops.manager',
    displayName: 'Ops Manager',
    defaultRoute: '/ops',
    menus: [
      { menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view', 'export'] },
    ],
    mustChangePassword: false,
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
})

describe('can', () => {
  it('grants when the menu carries the privilege', () => {
    const auth = useAuthStore()
    auth.menus = [
      { menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view', 'export'] },
    ]

    expect(auth.can('incident.ops', 'view')).toBe(true)
  })

  it('denies a privilege the role does not hold', () => {
    const auth = useAuthStore()
    auth.menus = [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }]

    expect(auth.can('incident.ops', 'export')).toBe(false)
  })

  it('denies unknown menus rather than defaulting open', () => {
    const auth = useAuthStore()
    auth.menus = [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }]

    expect(auth.can('incident.unknown', 'view')).toBe(false)
  })

  it('grants each ticket-group menu on its own, never by inheritance from incident.ops', () => {
    // Problem and Service Request are separate menus with their own grants; holding the Incident
    // menu says nothing about them, and a wallboard-only role holds none of the three.
    const auth = useAuthStore()
    auth.menus = [
      { menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view', 'export'] },
      { menuId: 'incident.problem', label: 'Problem', route: '/problem', privileges: ['view'] },
    ]

    expect(auth.can('incident.problem', 'view')).toBe(true)
    expect(auth.can('incident.problem', 'export')).toBe(false)
    expect(auth.can('incident.sr', 'view')).toBe(false)

    auth.menus = [
      {
        menuId: 'incident.wallboard',
        label: 'Wallboard',
        route: '/wallboard',
        privileges: ['view'],
      },
    ]
    expect(auth.can(['incident.ops', 'incident.problem', 'incident.sr'], 'view')).toBe(false)
  })

  it('denies everything against an empty permission set', () => {
    const auth = useAuthStore()
    auth.menus = []

    expect(auth.can('incident.ops', 'view')).toBe(false)
  })

  it('grants when ANY menu in an array of candidate menu ids matches — the shared-route case', () => {
    const auth = useAuthStore()
    auth.menus = [
      {
        menuId: 'incident.wallboard',
        label: 'Wallboard',
        route: '/wallboard',
        privileges: ['view'],
      },
    ]

    expect(auth.can(['incident.ops', 'incident.exec', 'incident.wallboard'], 'view')).toBe(true)
  })

  it('denies an array of candidate menu ids when none match', () => {
    const auth = useAuthStore()
    auth.menus = [
      {
        menuId: 'incident.wallboard',
        label: 'Wallboard',
        route: '/wallboard',
        privileges: ['view'],
      },
    ]

    expect(auth.can(['incident.ops', 'incident.exec'], 'view')).toBe(false)
  })
})

describe('resolveEntry — forced password rotation', () => {
  const targetRoutes = [
    '/ops',
    '/problem',
    '/service-requests',
    '/exec',
    '/wallboard',
    '/admin',
    '/',
    '/login',
  ]

  it.each(targetRoutes)(
    'routes %s to change-password, and NOWHERE else, while rotation is required',
    (target) => {
      const auth = useAuthStore()
      auth.accessToken = 'scoped-token'
      auth.mustChangePassword = true

      expect(auth.resolveEntry(target)).toBe(CHANGE_PASSWORD_ROUTE)
    },
  )

  it('does not redirect away from change-password itself while rotation is required', () => {
    const auth = useAuthStore()
    auth.accessToken = 'scoped-token'
    auth.mustChangePassword = true

    expect(auth.resolveEntry(CHANGE_PASSWORD_ROUTE)).toBeNull()
  })

  it('does not force change-password once rotation is no longer required', () => {
    const auth = useAuthStore()
    auth.accessToken = 'access-token'
    auth.mustChangePassword = false

    for (const target of targetRoutes.filter((r) => r !== '/login')) {
      expect(auth.resolveEntry(target)).toBeNull()
    }
  })

  it('sends an unauthenticated visitor to /login regardless of target', () => {
    const auth = useAuthStore()
    auth.accessToken = undefined

    for (const target of [
      '/ops',
      '/problem',
      '/service-requests',
      '/exec',
      '/wallboard',
      '/change-password',
    ]) {
      expect(auth.resolveEntry(target)).toBe(LOGIN_ROUTE)
    }
  })
})

describe('resolveEntry — DEFAULT_ROUTE landing, not a hardcoded path', () => {
  it('lands an ops role on its own DEFAULT_ROUTE after login', async () => {
    vi.mocked(authApi.login).mockResolvedValue(
      tokenResponse({
        defaultRoute: '/ops',
        menus: [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }],
      }),
    )
    const auth = useAuthStore()

    await auth.login('ops.manager', 'secret')

    expect(auth.defaultRoute).toBe('/ops')
    expect(auth.resolveEntry(LOGIN_ROUTE)).toBe('/ops')
  })

  it('lands an exec role on ITS OWN DEFAULT_ROUTE, distinct from the ops role — proving the value is not hardcoded', async () => {
    vi.mocked(authApi.login).mockResolvedValue(
      tokenResponse({
        defaultRoute: '/exec',
        menus: [{ menuId: 'incident.exec', label: 'Exec', route: '/exec', privileges: ['view'] }],
      }),
    )
    const auth = useAuthStore()

    await auth.login('exec.user', 'secret')

    expect(auth.defaultRoute).toBe('/exec')
    expect(auth.defaultRoute).not.toBe('/ops')
    expect(auth.resolveEntry(LOGIN_ROUTE)).toBe('/exec')
  })

  it('lands a wallboard role on a third, distinct DEFAULT_ROUTE', async () => {
    vi.mocked(authApi.login).mockResolvedValue(
      tokenResponse({
        defaultRoute: '/wallboard',
        menus: [
          {
            menuId: 'incident.wallboard',
            label: 'Wallboard',
            route: '/wallboard',
            privileges: ['view'],
          },
        ],
      }),
    )
    const auth = useAuthStore()

    await auth.login('wallboard.tv', 'secret')

    expect(auth.defaultRoute).toBe('/wallboard')
    expect(auth.resolveEntry(LOGIN_ROUTE)).toBe('/wallboard')
  })

  it('forces /change-password on login instead of DEFAULT_ROUTE when rotation is required', async () => {
    vi.mocked(authApi.login).mockResolvedValue(
      tokenResponse({ defaultRoute: '/ops', mustChangePassword: true }),
    )
    const auth = useAuthStore()

    await auth.login('bootstrap.admin', 'secret')

    expect(auth.resolveEntry('/ops')).toBe(CHANGE_PASSWORD_ROUTE)
  })
})

describe('login', () => {
  it('stores the access token in memory only (Pinia state)', async () => {
    vi.mocked(authApi.login).mockResolvedValue(tokenResponse({ token: 'abc123' }))
    const auth = useAuthStore()

    await auth.login('ops.manager', 'secret')

    expect(auth.accessToken).toBe('abc123')
    expect(auth.isAuthenticated).toBe(true)
  })
})

describe('logout', () => {
  it('clears the session even if the server call fails', async () => {
    vi.mocked(authApi.login).mockResolvedValue(tokenResponse())
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network error'))
    const auth = useAuthStore()
    await auth.login('ops.manager', 'secret')

    await expect(auth.logout()).rejects.toThrow('network error')

    expect(auth.accessToken).toBeUndefined()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.menus).toEqual([])
  })
})

describe('restoreSession', () => {
  it('rehydrates the session from the refresh cookie on reload', async () => {
    vi.mocked(authApi.refreshToken).mockResolvedValue({ token: 'refreshed-token' })
    const payload: PermissionPayload = {
      username: 'ops.manager',
      displayName: 'Ops Manager',
      defaultRoute: '/ops',
      menus: [{ menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view'] }],
      mustChangePassword: false,
    }
    vi.mocked(authApi.me).mockResolvedValue(payload)
    const auth = useAuthStore()

    const result = await auth.restoreSession()

    expect(result).toBe(true)
    expect(auth.accessToken).toBe('refreshed-token')
    expect(auth.defaultRoute).toBe('/ops')
    expect(auth.initialized).toBe(true)
  })

  it('clears the session when the refresh cookie is absent or expired', async () => {
    vi.mocked(authApi.refreshToken).mockRejectedValue(new Error('401'))
    const auth = useAuthStore()

    const result = await auth.restoreSession()

    expect(result).toBe(false)
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.initialized).toBe(true)
  })
})
