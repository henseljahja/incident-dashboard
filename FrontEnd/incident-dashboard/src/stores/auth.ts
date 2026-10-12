import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/apis/auth'
import type { AuthTokenResponse, PermissionMenu, PermissionPayload } from '@/apis/auth'
import type { MenuId } from '@/utils/can'

export const LOGIN_ROUTE = '/login'
export const CHANGE_PASSWORD_ROUTE = '/change-password'

export const useAuthStore = defineStore('auth', () => {
  // Access token lives in memory ONLY — never `localStorage`/`sessionStorage`. The refresh
  // token is an `HttpOnly` cookie the browser manages; this store never sees its value.
  // See .agents/patterns/session.md.
  const accessToken = ref<string | undefined>(undefined)
  const username = ref<string | undefined>(undefined)
  const displayName = ref<string | undefined>(undefined)
  const defaultRoute = ref<string | undefined>(undefined)
  const menus = ref<PermissionMenu[]>([])
  const mustChangePassword = ref(false)
  /** True once the initial reload-time session restore (via the refresh cookie) has settled. */
  const initialized = ref(false)

  const isAuthenticated = computed(() => !!accessToken.value)

  function applyPermissionPayload(payload: PermissionPayload) {
    username.value = payload.username
    displayName.value = payload.displayName
    defaultRoute.value = payload.defaultRoute
    menus.value = payload.menus
    mustChangePassword.value = payload.mustChangePassword
  }

  function applySession(session: AuthTokenResponse) {
    accessToken.value = session.token
    applyPermissionPayload(session)
  }

  /**
   * Drops the client-side session WITHOUT calling `/auth/logout`.
   *
   * Used after a password change, where the server has already revoked every refresh token and
   * cleared the cookie. Calling `logout()` there would fire a request with a
   * `change-password`-scoped token against an already-dead session — noise that can only fail.
   */
  function clearSessionLocally() {
    clearSession()
  }

  function clearSession() {
    accessToken.value = undefined
    username.value = undefined
    displayName.value = undefined
    defaultRoute.value = undefined
    menus.value = []
    mustChangePassword.value = false
  }

  /**
   * UI-affordance layer ONLY — see `.agents/patterns/permission.md` and `src/utils/can.ts`.
   * This is NOT a security boundary: the server (`PrivilegeInterceptor`) is the sole
   * authority on whether a request is allowed. `can()` only decides whether to render a
   * control. `menuId` may be a single id or an array, mirroring the backend
   * `@RequirePrivilege(menu = {...})` array semantics for routes shared by several menus —
   * granted if the caller holds the privilege on ANY listed menu. Deny by default, including
   * for unknown menus and an empty permission set.
   */
  function can(menuId: MenuId, privilege?: string): boolean {
    const ids = Array.isArray(menuId) ? menuId : [menuId]
    return ids.some((id) => {
      const menu = menus.value.find((m) => m.menuId === id)
      if (!menu) return false
      if (!privilege) return true
      return menu.privileges.includes(privilege)
    })
  }

  async function login(username: string, password: string) {
    const session = await authApi.login(username, password)
    applySession(session)
    initialized.value = true
    return session
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      clearSession()
    }
  }

  /**
   * Re-establishes the session from the `HttpOnly` refresh cookie after a page reload — the
   * access token itself is never persisted, so this is the only way it comes back.
   */
  async function restoreSession(): Promise<boolean> {
    try {
      const { token } = await authApi.refreshToken()
      accessToken.value = token
      const payload = await authApi.me()
      applyPermissionPayload(payload)
      return true
    } catch {
      clearSession()
      return false
    } finally {
      initialized.value = true
    }
  }

  /**
   * Where the router should actually send `targetPath`, given the current session. Returns
   * `null` when navigation may proceed unchanged.
   *
   * `mustChangePassword` wins over every other consideration: the server refuses a
   * `scope="change-password"` token on every route but its own
   * (`.agents/patterns/session.md`), so the client mirrors that lock — landing anywhere else
   * while the flag is set is a dead end at the API layer regardless of which route was
   * requested.
   */
  function resolveEntry(targetPath: string): string | null {
    if (!isAuthenticated.value) {
      return targetPath === LOGIN_ROUTE ? null : LOGIN_ROUTE
    }

    if (mustChangePassword.value) {
      return targetPath === CHANGE_PASSWORD_ROUTE ? null : CHANGE_PASSWORD_ROUTE
    }

    if (targetPath === LOGIN_ROUTE) {
      // RBAC decides the landing view — the role's own DEFAULT_ROUTE, never a fixed path.
      return defaultRoute.value ?? null
    }

    return null
  }

  return {
    accessToken,
    username,
    displayName,
    defaultRoute,
    menus,
    mustChangePassword,
    initialized,
    isAuthenticated,
    can,
    login,
    logout,
    restoreSession,
    resolveEntry,
    clearSessionLocally,
  }
})
