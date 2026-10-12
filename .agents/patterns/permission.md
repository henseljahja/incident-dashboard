# permission

## Intent

Authorization is **server-side and fail-closed**. The Vue `v-can` directive hides UI; it does
nothing to a `curl`. Without server checks any authenticated account can pull the entire
production incident dataset regardless of role.

**Surfaces:** `@RequirePrivilege` · `PrivilegeInterceptor` · `WebConfig` · `V5__seed_rbac.sql`.

## Required Checks

- **The interceptor must actually be registered.** `WebConfig implements WebMvcConfigurer`,
  `addPathPatterns("/api/**")`. An unregistered interceptor is inert and reviews miss it.
- **Fail-closed:** a `/api/**` handler with no `@RequirePrivilege` throws. An undecorated
  endpoint is a bug, not a public endpoint.
- **`menu()` is an array**, satisfied by any listed menu. Routes are legitimately shared by
  three roles — `/api/metrics/{kpi,trend,top-categories}` and `/api/freshness` are reachable by
  ops, exec **and** wallboard. A single-valued annotation makes two of the three v1 views
  structurally unreachable by the roles that own them.
- **CORS preflight is exempt at both layers.** `OPTIONS` carries no `Authorization` and
  dispatches to Spring's `PreFlightHandler`, which is not a `HandlerMethod` and cannot carry an
  annotation — but Spring retains interceptors on the preflight chain. `AuthFilter`
  short-circuits `OPTIONS`; `PrivilegeInterceptor` returns `true` for non-`HandlerMethod`.
- **The privilege universe is `{view, export, manage}`**, enforced by a CHECK constraint. A
  typo'd privilege must fail at insert, not silently grant nothing.
- `/api/incidents/export` requires `export`, not `view` — bulk egress is its own privilege.
- Tier 2: a 403 test per role × route, including the wallboard's `/api/freshness` heartbeat.
