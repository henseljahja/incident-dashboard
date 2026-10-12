import { AUTH_BASE_URL, apiRequest } from './client'

/** A menu granted to the caller, with the privileges held on it (`view` | `export` | `manage`). */
/**
 * One entry of the caller's permitted menu set.
 *
 * `label` and `route` were MISSING from this interface until 2026-08-23, even though
 * `/auth/token` has always returned them (they come from `APP_MENU.LABEL` / `.ROUTE`, seeded
 * by `V5__seed_rbac.sql`). The omission was not cosmetic: with the nav data invisible to
 * TypeScript, the app shell had nothing to build a sidebar from, so it shipped with a bare
 * title bar and no navigation at all — a user with three permitted views could only reach two
 * of them by typing the URL.
 *
 * Keep this in step with `PermissionMenuDto` on the backend.
 */
export interface PermissionMenu {
  menuId: string
  /** Human-readable nav label, e.g. "Operations". */
  label: string
  /** SPA path this menu points at, e.g. "/ops". */
  route: string
  privileges: string[]
}

/** The permission payload shape shared by `/auth/token` and `GET /auth/me`. */
export interface PermissionPayload {
  /**
   * The account name, as sent by `AuthController.AuthPayload`. It was already on the wire and
   * simply not declared here — the sidebar had nothing but `displayName` to identify the
   * session with, so a long display name truncated to "Bootstrap A…" and the one string that
   * would have disambiguated it was being discarded on arrival.
   */
  username: string
  displayName: string
  defaultRoute: string
  menus: PermissionMenu[]
  mustChangePassword: boolean
}

export interface AuthTokenResponse extends PermissionPayload {
  token: string
}

export function login(username: string, password: string) {
  return apiRequest<AuthTokenResponse>('/auth/token', {
    method: 'POST',
    body: { username, password },
    auth: false,
    baseUrl: AUTH_BASE_URL,
  })
}

/** Reads the `HttpOnly` refresh cookie; never called with an `Authorization` header. */
export function refreshToken() {
  return apiRequest<{ token: string }>('/auth/refresh-token', {
    method: 'POST',
    auth: false,
    baseUrl: AUTH_BASE_URL,
  })
}

export function logout() {
  return apiRequest<void>('/auth/logout', { method: 'POST', baseUrl: AUTH_BASE_URL })
}

export function me() {
  return apiRequest<PermissionPayload>('/auth/me', { baseUrl: AUTH_BASE_URL })
}

export function changePassword(currentPassword: string, newPassword: string) {
  return apiRequest<void>('/auth/change-password', {
    method: 'POST',
    body: { currentPassword, newPassword },
    baseUrl: AUTH_BASE_URL,
  })
}
