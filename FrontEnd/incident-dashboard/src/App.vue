<script setup lang="ts">
import { watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

import { CHANGE_PASSWORD_ROUTE, LOGIN_ROUTE, useAuthStore } from '@/stores/auth'

/**
 * One job: when the session dies mid-use, leave the screen.
 *
 * `apiRequest` already spends one silent refresh on a 401, and `restoreSession()` clears the
 * store when that refresh fails — but nothing navigated afterwards. The result was a view stuck
 * on a dead session: TanStack Query kept surfacing errors nobody rendered, so the Executive
 * tiles sat on their skeletons indefinitely and the wallboard showed "Cannot reach the dashboard
 * service" forever. It looked like an endless load; it was an expired session with no exit.
 *
 * Watching the store rather than intercepting inside `apiRequest` keeps the HTTP layer free of a
 * router import — `router` already imports the auth store, and importing it back into
 * `apis/client` would close an import cycle through `apis/auth`.
 */
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

watch(
  () => auth.isAuthenticated,
  (isAuthenticated) => {
    // Only react to a session LOST after one existed. `initialized` is false during the
    // reload-time restore, when isAuthenticated is legitimately false for a moment, and the
    // router guard owns the redirect at that point.
    if (isAuthenticated || !auth.initialized) return
    if (route.path === LOGIN_ROUTE || route.path === CHANGE_PASSWORD_ROUTE) return

    void router.push({ path: LOGIN_ROUTE, query: { sessionExpired: '1' } })
  },
)
</script>

<template>
  <RouterView />
</template>
