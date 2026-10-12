# Coverage Map — incident dashboard

Target: http://localhost:8090 (demo compose stack), account `admin` / ADMIN role.
The stack is read-only over an Oracle mirror; Redmine is the write side. The ONLY
write surfaces in the whole product are login and change-password.

## Routes declared in src/router/index.ts
| Path | Component | In sidebar? |
|---|---|---|
| `/` | redirect -> `/ops` | — |
| `/ops` | OpsView | yes |
| `/login` | LoginView | no (unauthenticated) |
| `/change-password` | ChangePasswordView | no |
| `/wallboard` | WallboardView | yes |
| `/exec` | ExecView | yes |
| `/admin/users` | **NOT DECLARED** | **yes — see Bug 1** |

## Forms (the complete set)
1. Login — username, password
2. Change password — current, new, confirm
3. Ops filter bar — from, to, tracker, stage, priority, institusi, category, hasWorkaround

## Settings surfaces
None. There is no Settings/Config/Preferences nav item anywhere in the product,
so §4.5's settings census has no surface to apply to. Recorded rather than
skipped silently.

## Auth / permission boundaries
- unauthenticated -> any protected route
- `mustChangePassword` session -> restricted to change-password scope
- role-scoped menus (ADMIN sees 4, VIEWER fewer)
- expired access token -> silent refresh-and-retry (client.ts)

## State boundaries
- empty result set (filters that match nothing)
- single row
- full page (25 rows) + pagination
- stale mirror (freshness banner)
- service unreachable (wallboard failure banner)

## Known automated-test gaps
- No e2e covers `/admin/users` (route does not exist).
- No test asserts the sidebar renders (AppSidebar is new as of 6dc7e52).
- Playwright suite predates the design system change entirely.
