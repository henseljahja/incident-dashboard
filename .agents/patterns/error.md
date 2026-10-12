# error

## Intent

One handler owns the whole error surface, and no error response ever leaks the database.
This data is production-derived; a stack trace or a SQL fragment in a 500 body is a
disclosure, not a debugging aid.

**Surfaces:** `domain/config/error/{ErrorMapping,GlobalExceptionHandler}.java` and
`domain/config/error/exception/**`.

## Required Checks

- **`GlobalExceptionHandler` is owned by a named task**, never left unassigned in the file
  structure — an unowned handler is how 500s ship.
- The mapping is fixed: `AccessDeniedException` → **403** · JWT exceptions → **401** ·
  validation failures → **400** · everything else → **500**.
- **A 500 body carries a correlation id and nothing else** — no stack trace, no SQL, no
  column names, no row values.
- `AccessDeniedException` is **ours** (`domain.config.error.exception`) because
  `spring-security-crypto` ships no exception types and the security starter is deliberately
  off the classpath. Do not import a Spring Security type for it.
- A fail-closed authorization miss must surface as 403, not 500 — a 500 here reads as an
  outage and hides a real permission bug.
- Tier 2 asserts the body shape of a 403 and a 500, including the *absence* of stack/SQL.
