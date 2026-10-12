import { useAuthStore } from '@/stores/auth'

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api'

/**
 * The auth endpoints are NOT under the `/api` tree.
 *
 * `AuthController` maps `/auth/token`, `/auth/refresh-token`, `/auth/logout`, `/auth/me` and
 * `/auth/change-password` at the ROOT, while every data endpoint (`IncidentController`,
 * `MetricsController`, `FreshnessController`) maps under `/api/**`. Prefixing the auth paths
 * with `API_BASE_URL` produces `/api/auth/token`, which the `AuthFilter` sees as a protected
 * path and refuses with "A bearer token is required." — i.e. login could never succeed.
 *
 * Derived from `API_BASE_URL` by dropping its trailing `/api` segment so that one
 * `VITE_API_BASE_URL` still configures both trees. Found by the Task 23 tier-3 walkthrough,
 * which is the first thing in the project to drive a real browser at a real backend; every
 * unit test mocks this boundary and so could not see it.
 */
export const AUTH_BASE_URL = API_BASE_URL.replace(/\/api\/?$/, '')

export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  body?: unknown
  /** Attach `Authorization: Bearer <accessToken>`. Default true. */
  auth?: boolean
  /** Overrides `API_BASE_URL`. Only `apis/auth.ts` uses it — see `AUTH_BASE_URL`. */
  baseUrl?: string
}

/**
 * Thin fetch wrapper — the SPA's one HTTP boundary.
 *
 * - Attaches `Authorization: Bearer <accessToken>` from the auth store's in-memory token
 *   (never `localStorage`; see `.agents/patterns/session.md`).
 * - Always sends `credentials: 'include'` so the `HttpOnly` refresh cookie set by
 *   `/auth/token` travels on `/auth/**` requests.
 * - This is STRICTLY READ-ONLY infrastructure: callers must not add mutating calls beyond
 *   the auth allowlist (`/auth/token`, `/auth/refresh-token`, `/auth/logout`,
 *   `/auth/change-password`) — see `.agents/patterns/api.md`.
 */
export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, baseUrl = API_BASE_URL } = options
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }

  if (auth) {
    const authStore = useAuthStore()
    if (authStore.accessToken) {
      headers.Authorization = `Bearer ${authStore.accessToken}`
    }
  }

  const isAuthRoute = baseUrl === AUTH_BASE_URL
  const send = () =>
    fetch(`${baseUrl}${path}`, {
      method,
      headers,
      credentials: 'include',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })

  let response = await send()

  // A 401 on an authenticated call usually means the 1h access token simply aged out, and the
  // HttpOnly refresh cookie is still good for its full 24h. Spend one silent refresh before
  // surfacing the failure.
  //
  // This is load-bearing for the WALLBOARD, which polls unattended on a screen for days with
  // nobody present to notice a login prompt. Without it, every call 401s after the first hour
  // and the view keeps rendering its last successful values — frozen numbers, and the stale
  // banner reads from that same last-successful payload, so it does not fire either. A wallboard
  // confidently displaying hour-old figures with no warning is precisely the failure the
  // freshness indicator exists to prevent.
  //
  // Exactly one attempt, and never on the auth routes themselves — retrying a failed
  // /auth/refresh-token through this path would recurse.
  if (response.status === 401 && auth && !isAuthRoute) {
    const authStore = useAuthStore()
    const recovered = await authStore.restoreSession().catch(() => false)
    if (recovered) {
      if (authStore.accessToken) {
        headers.Authorization = `Bearer ${authStore.accessToken}`
      }
      response = await send()
    }
  }

  if (!response.ok) {
    let message = response.statusText
    try {
      const payload = (await response.json()) as { message?: string }
      message = payload?.message ?? message
    } catch {
      // no JSON body — keep statusText
    }
    throw new ApiError(message, response.status)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}
