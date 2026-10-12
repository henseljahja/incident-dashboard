import type { Directive } from 'vue'

import { usePermission } from './usePermission'

/**
 * A menu id, or several — the backend's `@RequirePrivilege(menu = {...})` lets a single
 * route be reachable by more than one menu (e.g. `/api/metrics/kpi` is shared by ops, exec
 * and the wallboard). `can()` treats an array as "granted if the caller holds the privilege
 * on ANY listed menu" to mirror that server-side array semantics — never assume a route maps
 * to exactly one menu.
 */
export type MenuId = string | string[]

export type CanTuple = [menuId: MenuId, privilege?: string]

// RBAC only, optionally combined with a runtime (ABAC) condition
export type CanRule = CanTuple | [CanTuple, condition: boolean]

// any / all
export type CanValue = CanRule | CanRule[]

export type CanDirective = Directive<HTMLElement, CanValue>

function isMenuIdArray(x: unknown): x is string[] {
  return Array.isArray(x) && x.every((item) => typeof item === 'string')
}

/**
 * A `CanTuple` is `[menuId, privilege?]` where `menuId` is a string OR an array of strings
 * (see `MenuId`). Disambiguating it from a list of rules — both are plain arrays — relies on
 * `privilege` never being an array itself: a `CanTuple`'s second slot is always a string or
 * absent, never another tuple.
 */
function isCanTuple(x: unknown): x is CanTuple {
  if (!Array.isArray(x) || x.length > 2) return false
  const [menuId, privilege] = x
  const menuIdOk = typeof menuId === 'string' || isMenuIdArray(menuId)
  const privilegeOk = privilege === undefined || typeof privilege === 'string'
  return menuIdOk && privilegeOk
}

/** `[CanTuple, condition]` — the ABAC-annotated form of a rule. */
function isCanRuleWithCondition(x: unknown): x is [CanTuple, boolean] {
  return Array.isArray(x) && x.length === 2 && isCanTuple(x[0]) && typeof x[1] === 'boolean'
}

function isCanRule(x: unknown): x is CanRule {
  return isCanTuple(x) || isCanRuleWithCondition(x)
}

function normalizeRule(rule: CanRule): [CanTuple, boolean?] {
  if (isCanTuple(rule)) {
    return [rule]
  }
  return rule as [CanTuple, boolean]
}

export function normalizeRules(value: CanValue): [CanTuple, boolean?][] {
  if (isCanRule(value)) {
    return [normalizeRule(value)]
  }
  return (value as CanRule[]).map(normalizeRule)
}

declare module 'vue' {
  export interface ComponentCustomProperties {
    // prefix with v (v-can)
    vCan: CanDirective
  }
}

/**
 * v-can — UI-affordance directive (RBAC + ABAC).
 *
 * SECURITY NOTE: this directive is NOT a security boundary. It only hides or removes an
 * element from the DOM; it does nothing to a direct API call. Every `/api/**` route is
 * authorized server-side (`PrivilegeInterceptor`, fail-closed) — `v-can` merely keeps a
 * control a user cannot use out of their way. Never rely on it to protect data.
 *
 * ─────────────────────────────────────────────
 * BASIC USAGE
 * ─────────────────────────────────────────────
 *
 * Single permission (RBAC only):
 *
 * <button v-can="['incident.ops', 'export']">
 *   Export
 * </button>
 *
 * A route shared by several menus (array menuId, granted if ANY matches):
 *
 * <div v-can="[['incident.ops', 'incident.exec', 'incident.wallboard'], 'view']">
 *   ...
 * </div>
 *
 * ─────────────────────────────────────────────
 * OR / AND LOGIC, NEGATION, ABAC
 * ─────────────────────────────────────────────
 *
 * <button v-can:any="[['incident.ops', 'export'], ['incident.ops', 'manage']]">Any</button>
 * <button v-can:all="[['incident.ops', 'view'], ['incident.ops', 'export']]">All</button>
 * <button v-can.not="['incident.ops', 'export']">Visible when NOT allowed</button>
 * <button v-can="[['incident.ops', 'export'], someRuntimeCondition]">ABAC</button>
 */
export default {
  mounted(el, binding) {
    const { can } = usePermission()
    const rules = normalizeRules(binding.value)

    const mode = binding.arg === 'all' ? 'all' : 'any'
    let allowed =
      mode === 'all'
        ? rules.every(([[menuId, privilege], condition]) => {
            return can(menuId, privilege, condition)
          })
        : rules.some(([[menuId, privilege], condition]) => {
            return can(menuId, privilege, condition)
          })

    const isNot = binding.modifiers.not === true
    if (isNot) {
      allowed = !allowed
    }

    if (!allowed) {
      el.remove()
    }
  },
} satisfies CanDirective
