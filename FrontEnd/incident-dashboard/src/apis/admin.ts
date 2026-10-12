import { apiRequest } from './client'

/**
 * `/api/admin/**` — account administration.
 *
 * <p>Shapes ported from yob backoffice's `apis/users.ts` so the screens port with their query
 * handling intact. Two differences, both because this service is not yob:
 *
 * - No `tenantId`/`region`/`area`/`branch`: there is no tenancy here, and carrying empty
 *   strings for fields nothing populates would put four permanently blank columns on a screen.
 * - No bulk-upload calls. yob onboards member banks a spreadsheet at a time; this deployment
 *   has a handful of named operators, and the endpoints do not exist server-side.
 *
 * THE ONE WRITE SURFACE. `apis/client.ts` documents the SPA as strictly read-only, and that
 * remains true of INCIDENT data — Redmine is still the system of record and nothing here
 * touches the mirror. What this module writes is OUR OWN `APP_USER*` tables, which no other
 * system owns; the read-only rule was never about them.
 */

/**
 * Derived server-side from `IS_ACTIVE`/`MUST_CHANGE_PASSWORD`/`LOCKED_UNTIL` rather than
 * stored — see `UserAdminUseCase.deriveStatus`. `PENDING` is yob's display rule for an account
 * that exists but has never signed in. There is no `TERMINATED`: nothing deletes accounts
 * here, and deactivation is reversible.
 */
export type UserStatus = 'PENDING' | 'ACTIVE' | 'INACTIVE' | 'LOCKED'

export interface AdminUser {
  username: string
  name: string | null
  email: string | null
  roleId: string | null
  roleName: string | null
  status: UserStatus
  lastLoginAt: string | null
  createdAt: string | null
}

export interface AdminUserPage {
  content: AdminUser[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AssignableRole {
  roleId: string
  name: string
  defaultRoute: string
  priority: number
}

/**
 * The one-time password, returned by create and reset.
 *
 * It exists nowhere else — the row stores only a bcrypt hash — so whatever renders this must
 * say so and must not offer to fetch it again. There is no endpoint that could.
 */
export interface CreatedUser {
  username: string
  temporaryPassword: string
}

export interface UserUpsertRequest {
  username?: string
  name: string
  email?: string
  roleId: string
  active?: boolean
}

export function fetchUsers(search: string, page: number, size: number) {
  const query = new URLSearchParams({
    search: search ?? '',
    page: String(page),
    size: String(size),
  })
  return apiRequest<AdminUserPage>(`/admin/users?${query}`)
}

export function fetchUser(username: string) {
  return apiRequest<AdminUser>(`/admin/users/${encodeURIComponent(username)}`)
}

export function fetchRoles() {
  return apiRequest<AssignableRole[]>('/admin/roles')
}

export function createUser(body: UserUpsertRequest) {
  return apiRequest<CreatedUser>('/admin/users', { method: 'POST', body })
}

export function updateUser(username: string, body: UserUpsertRequest) {
  return apiRequest<AdminUser>(`/admin/users/${encodeURIComponent(username)}`, {
    method: 'PUT',
    body,
  })
}

export function resetPassword(username: string) {
  return apiRequest<CreatedUser>(`/admin/users/${encodeURIComponent(username)}/reset-password`, {
    method: 'POST',
  })
}
