# session

## Intent

**There is no server-side session.** The service is stateless: every request is authorized from
its own bearer token. This is deliberate — the wallboard runs unattended for days and horizontal
restarts must not log anyone out.

**Surfaces:** `AuthFilter` · `JwtService` · the SPA's Pinia auth store.

## Required Checks

- No `HttpSession`, no session store, no sticky routing. State that looks session-shaped
  (filters, selected view) lives in the SPA, not the server.
- The only per-request server state is the `MDC` username and the `AuthenticatedUser` request
  attribute set by `AuthFilter` — both scoped to the request, both cleared after it.
- **Access-token lifetime is short; the refresh token is the long-lived credential**, carries
  `jti`, and is delivered as a cookie — which is why CORS sets
  `Access-Control-Allow-Credentials: true` and echoes the SPA origin by name. A wildcard origin
  is incompatible with credentials.
- A scoped token (`scope="change-password"`) is refused on every route but its own. The scope
  lives in the token, not in server memory.
- The wallboard must survive token expiry without a human: it re-authenticates or shows the
  stale banner — it never renders a silently frozen dashboard.
