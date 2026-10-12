import { useAuthStore } from '@/stores/auth'

import type { MenuId } from './can'

/**
 * UI-affordance layer only — see `can.ts`. The server (`PrivilegeInterceptor`) is the sole
 * authority on whether a request is allowed; this composable only decides whether to render
 * a control.
 */
export function usePermission() {
  const authStore = useAuthStore()

  const can = (menuId: MenuId, privilege?: string, condition?: boolean) => {
    if (!authStore.can(menuId, privilege)) return false
    if (condition === undefined) return true
    return condition
  }

  return { can }
}
