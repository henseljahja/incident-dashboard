import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import type { Scope } from '@/stores/filters'

declare module 'vue-router' {
  interface RouteMeta {
    /** Which ticket group an ops page shows — `OpsView` hands it to the filters store. */
    scope?: Scope
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/ops',
    },
    {
      path: '/ops',
      name: 'ops',
      component: () => import('../views/OpsView.vue'),
      meta: { scope: 'INCIDENT' },
    },
    /**
     * Problem and Service Request are the SAME view with a different scope — one screen, three
     * ticket groups, so a fix to the table or the tiles lands on all three.
     */
    {
      path: '/problem',
      name: 'problem',
      component: () => import('../views/OpsView.vue'),
      meta: { scope: 'PROBLEM' },
    },
    {
      path: '/service-requests',
      name: 'service-requests',
      component: () => import('../views/OpsView.vue'),
      meta: { scope: 'SERVICE_REQUEST' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('../views/ChangePasswordView.vue'),
    },
    {
      path: '/wallboard',
      name: 'wallboard',
      component: () => import('../views/WallboardView.vue'),
    },
    {
      path: '/exec',
      name: 'exec',
      component: () => import('../views/ExecView.vue'),
    },
    /**
     * User management. The route half of the pair `V6__remove_unbuilt_admin_menu.sql` insisted
     * on: it deleted the `incident.admin` menu precisely because `/admin/users` was seeded into
     * the nav with no screen behind it, and its comment made restoring the menu conditional on
     * restoring the route at the same time. `V8__user_admin.sql` re-adds the menu; these
     * entries are the other half. `UserAdminIT.menuAndGrantsRestored` asserts the path below
     * still matches the seeded `ROUTE`.
     *
     * Not guarded here beyond the session check — the sidebar renders from the server's menu
     * payload, and every `/api/admin/**` handler requires `incident.admin:manage` fail-closed.
     * A client-side role guard would be a second, weaker copy of that rule.
     */
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('../views/admin/UserTableView.vue'),
    },
    {
      path: '/admin/users/create',
      name: 'admin-user-create',
      component: () => import('../views/admin/UserFormView.vue'),
      props: { mode: 'create' },
    },
    /**
     * Declared AFTER `/create` — vue-router matches in order, and a `:username` param would
     * otherwise swallow `/admin/users/create` and try to load an account named "create".
     */
    {
      path: '/admin/users/:username',
      name: 'admin-user-detail',
      component: () => import('../views/admin/UserFormView.vue'),
      props: { mode: 'view' },
    },
    {
      path: '/admin/users/:username/edit',
      name: 'admin-user-edit',
      component: () => import('../views/admin/UserFormView.vue'),
      props: { mode: 'edit' },
    },
    /**
     * Catch-all, and it must stay LAST — vue-router matches in declaration order, so a
     * catch-all declared earlier would swallow every route below it.
     *
     * Without this, an unknown path rendered a blank white page with no message and no console
     * error. The original example was `/admin/users`, seeded into the menu payload with no
     * screen behind it; that route now exists (above), but the catch-all stays — the next
     * mistyped URL or removed screen needs it just as much.
     */
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()

  if (!authStore.initialized) {
    await authStore.restoreSession()
  }

  const redirect = authStore.resolveEntry(to.path)
  if (redirect !== null && redirect !== to.path) {
    return redirect
  }

  return true
})

export default router
