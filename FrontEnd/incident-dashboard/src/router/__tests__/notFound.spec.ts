import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import router from '../index'
import { useAuthStore } from '@/stores/auth'

/**
 * Before the catch-all existed, an unrecognised path rendered a blank white page: no message,
 * no navigation, and no console error to diagnose it with. The original example was
 * `/admin/users`, seeded into the menu payload with no matching screen, so the sidebar itself
 * led straight into that dead end.
 *
 * That specific path is now a real screen, so it moved from the catch-all test to the
 * menu-route test below — where it pins the OTHER half of the same invariant: every route the
 * server advertises in `menus[]` must resolve to a view. Deleting the assertion along with the
 * bug would have left that half unguarded.
 */
beforeEach(() => {
  setActivePinia(createPinia())
})

function authenticate() {
  const auth = useAuthStore()
  auth.accessToken = 'test-token'
  auth.initialized = true
  auth.defaultRoute = '/ops'
  auth.menus = [
    { menuId: 'incident.ops', label: 'Operations', route: '/ops', privileges: ['view'] },
  ]
}

describe('router catch-all', () => {
  it('resolves an unknown path to the not-found route', () => {
    const resolved = router.resolve('/totally-made-up-route')

    expect(resolved.name).toBe('not-found')
    expect(resolved.matched).not.toHaveLength(0)
  })

  it('resolves a nested unknown path, not just a top-level one', () => {
    // The catch-all's `:pathMatch(.*)*` has to span segments. A single-segment pattern would
    // pass the test above and still render a blank page for anything deeper — which is the
    // shape most mistyped URLs actually take now that /admin/users/:username exists.
    expect(router.resolve('/admin/users/someone/nope/deeper').name).toBe('not-found')
  })

  it('resolves every route the server can advertise in menus[] to a real view', () => {
    // The menu table is the source of truth for the sidebar (V5/V8__user_admin.sql), and a
    // MENU_ID whose ROUTE has no entry here is exactly the bug V6 deleted a menu row over.
    // `UserAdminIT.menuAndGrantsRestored` asserts the seeded ROUTE end; this is the SPA end.
    for (const route of [
      '/ops',
      '/problem',
      '/service-requests',
      '/exec',
      '/wallboard',
      '/admin/users',
    ]) {
      expect(router.resolve(route).name, `${route} must not fall through to not-found`).not.toBe(
        'not-found',
      )
    }
  })

  it('matches /admin/users/create as its own route, not as a username', () => {
    // `/admin/users/:username` is declared after `/create` on purpose; reversing them makes
    // the create screen unreachable and loads an account literally named "create".
    expect(router.resolve('/admin/users/create').name).toBe('admin-user-create')
    expect(router.resolve('/admin/users/ops.manager').name).toBe('admin-user-detail')
    expect(router.resolve('/admin/users/ops.manager/edit').name).toBe('admin-user-edit')
  })

  it('still resolves every real route to its own view', () => {
    expect(router.resolve('/ops').name).toBe('ops')
    expect(router.resolve('/problem').name).toBe('problem')
    expect(router.resolve('/service-requests').name).toBe('service-requests')
    expect(router.resolve('/exec').name).toBe('exec')
    expect(router.resolve('/wallboard').name).toBe('wallboard')
    expect(router.resolve('/login').name).toBe('login')
    expect(router.resolve('/change-password').name).toBe('change-password')
  })

  it('gives each ticket-group page its scope in route meta, on one shared view', () => {
    // OpsView reads `meta.scope`; a route without it would silently show the unscoped list.
    const scopes = ['/ops', '/problem', '/service-requests'].map((path) => {
      const resolved = router.resolve(path)
      return [resolved.meta.scope, resolved.matched[0]!.components?.default]
    })
    expect(scopes.map(([scope]) => scope)).toEqual(['INCIDENT', 'PROBLEM', 'SERVICE_REQUEST'])
    // One component, three scopes — a fix to the table lands on all three pages.
    expect(new Set(scopes.map(([, component]) => String(component))).size).toBe(1)
  })

  it('keeps the catch-all as the last route', () => {
    const routes = router.getRoutes()
    expect(routes[routes.length - 1]!.name).toBe('not-found')
  })

  it('sends an unauthenticated visitor to login rather than to not-found', async () => {
    // Order matters: the auth guard must win, or a bad URL would leak the existence of the
    // 404 view to anyone unauthenticated.
    const auth = useAuthStore()
    auth.initialized = true

    await router.push('/totally-made-up-route')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('lands an authenticated visitor on the not-found view', async () => {
    authenticate()

    await router.push('/totally-made-up-route')
    expect(router.currentRoute.value.name).toBe('not-found')
  })
})
