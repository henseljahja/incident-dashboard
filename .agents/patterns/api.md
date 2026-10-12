# api

## Intent

Every HTTP surface in this service is **read-only over the mart**. Redmine is the permanent
write side; this system never mutates incident data. The API exists to serve
`V_INCIDENT` and derived metrics to the Vue SPA, and to deep-link back to Redmine for
anything that would be a write.

**Surfaces:** `infrastructure/rest/**` (controllers + DTOs) · `domain/usecase/**`.

## Required Checks

- **`/api/**` is GET-only.** The write allowlist is exactly two routes, both under `/auth`:
  `POST /auth/token`, `POST /auth/refresh-token`, plus `POST /auth/change-password`. A new
  non-GET under `/api/**` is a design change, not an implementation detail — it needs a plan.
- **Every `/api/**` handler carries `@RequirePrivilege`.** `PrivilegeInterceptor` is
  fail-closed; an undecorated handler throws. See [permission](permission.md).
- **Bounded result sets.** `GET /api/incidents` bounds `size ≤ 200`. `GET /api/incidents/export`
  is the one bulk path and is capped separately at `incident.export.max-rows` (50,000), setting
  `truncated: true` when the cap bites. An unbounded read is a PII-egress bug, not a perf bug.
- **Report formatting happens at the DTO boundary, never in storage.** `INCIDENT_FACT` stores
  native Oracle types; `RPT_*` columns and DTO fields render the workbook's string formats.
  The 35-row report column contract in the plan is the authority.
- **DTO field names are asserted against the view's columns**, not re-typed by hand
  (`MartSchemaIT`). Renaming a view column must break a test.
- Errors use the shape in [error](error.md) — never a raw stack trace or SQL.
- Tier 1: MockMvc slice per controller. Tier 2: `AbstractOracleIT` against Testcontainers
  Oracle. Tier 3: Playwright walkthrough of the consuming view.

## The mocked-boundary blind spot (found by T023, 2026-08-23)

`AuthController` maps `/auth/**` at the ROOT; every data controller maps under `/api/**`. The
SPA client prefixed `API_BASE_URL` (`/api`) onto every call, so login posted to `/api/auth/token`
— which `AuthFilter` treats as protected and answers `401 A bearer token is required`.
**Login could never have succeeded in a browser.**

Nothing caught it before tier 3 ran, and the reason generalises: **every frontend unit test mocks
the HTTP client, so the base URL is exactly the thing they cannot see.** 72 green unit tests and
74 green backend ITs coexisted with a login page that could not log in. The backend was right, the
frontend was right in isolation, and the seam between them was wrong.

Rules:
- A route whose prefix differs from the app's default base URL needs an explicit, named base
  (`AUTH_BASE_URL`), not a mental note.
- Treat "all our tests mock this boundary" as a signal that a tier-3 walkthrough is load-bearing,
  not ceremony. This is what the deferred tier-3 obligation existed to catch.
- Keep `test:e2e` in the tier-3 command and keep the launcher opt-in via `E2E_STACK` so a normal
  `./mvnw test` still skips it.
