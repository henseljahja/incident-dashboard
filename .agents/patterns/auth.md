# auth

## Intent

Authentication is **fully standalone**. It copies yob's `AuthFilter` *pattern* only — there is
no runtime call to yob, no shared user store, no SSO. Credentials live in this service's own
`APP_USER` table.

**Surfaces:** `domain/config/security/**` · `db/migration/{V4__auth.sql,V5__seed_rbac.sql,R__SeedAdmin.java}`.

## Required Checks

- **`spring-security-crypto` only — never `spring-boot-starter-security`.** The starter installs
  a filter chain that fights the hand-rolled `AuthFilter`. Consequence: `AccessDeniedException`
  is **ours** (`domain.config.error.exception`), because the starter's types are absent.
- **Tokens carry claims, and `validate` returns them.** `JwtService.validate(token, expectedTyp)
  → Claims`, not a bare username. A `String` return discards `typ` and `scope` and makes the
  forced-rotation gate unenforceable.
- **Refresh tokens are distinguishable.** `typ=refresh` + `jti`; `validate()` enforces the
  expected type. Without it a refresh token is just a long-lived access token.
- **Forced rotation must not lock the account out.** `MUST_CHANGE_PASSWORD` still issues a
  token, scoped `scope="change-password"`; every other route refuses it. Refusing the login
  outright strands the only bootstrap account.
- **Never log the request body.** yob's filter logs it; on `/auth/token` that is a plaintext
  password in the application log. Omit body logging, and assert the absence in a test.
- **`R__SeedAdmin` is repeatable and MUST override `getChecksum()`.** `BaseJavaMigration`
  returns `null` by default, so null-vs-null compares equal and the migration is skipped
  forever after its first run. Repeatable alone is insufficient.
- The bootstrap password comes from the environment, never from a committed file.
- Tier 2 `AuthFlowIT` covers: login · refresh · type confusion · forced rotation on every route.
