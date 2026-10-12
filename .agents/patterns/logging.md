# logging

## Intent

The application log is a place production customer names, staff names and incident
descriptions can escape to. Logging here is designed around that, not around debuggability.

**Surfaces:** `AuthFilter` · `GlobalExceptionHandler` · the refresh/reconcile scheduler ·
`EXPORT_AUDIT`.

## Required Checks

- **Never log request or response bodies.** yob's filter shape logs the request body; ported
  as-is that writes a plaintext password on `/auth/token`. Omit it (preferred — this service
  has almost no POSTs) or mask `password`, and assert the captured log output in a test.
- **Never log incident rows.** Log counts, ids and durations — not `SUBJECT`, `INSTITUSI`,
  `ASSIGNED_TO`, `WORKAROUND` or `ROOTCAUSE`.
- **Bulk egress is audited, not logged.** Every `/api/incidents/export` writes an
  `EXPORT_AUDIT(EXPORT_ID, USERNAME, REQUESTED_AT, FILTER_JSON, ROW_COUNT, TRUNCATED)` row.
  An untraceable bulk pull of this dataset is the incident nobody can reconstruct afterwards.
- The refresh job logs run id, rows merged, rows rejected and watermark movement — enough to
  diagnose a stalled mirror without reproducing its contents.
- Errors log the correlation id that the 500 body returns, so a user report maps to one line.
- **A credential-carrying DTO MUST override `toString()`.** "We log no bodies" is not enough:
  Spring MVC itself logs them at TRACE on `org.springframework.web` —
  `AbstractMessageConverterMethodArgumentResolver` ("Read ... to [...]") and
  `InvocableHandlerMethod` ("Arguments: [...]") both call `toString()`, and a Java `record`
  generates one that prints every component. Turning TRACE on to debug a failing login is
  exactly when this fires. `LoginRequest` and `ChangePasswordRequest` therefore mask their
  credential fields in `toString()` (found by `AuthFlowIT.loginLogsContainNoPassword`,
  2026-08-21). Masking at the DTO covers every framework call site, present and future;
  suppressing one logger name does not.
