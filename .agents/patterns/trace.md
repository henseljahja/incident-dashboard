# trace

## Intent

Two questions must be answerable at any moment without a database session: *who made this
request* and *how stale is what they are looking at*.

**Surfaces:** `AuthFilter` (MDC) · `GlobalExceptionHandler` (correlation id) ·
`ETL_WATERMARK` + `GET /api/freshness`.

## Required Checks

- `AuthFilter` puts the username in `MDC` on every authenticated request; it is cleared when
  the request ends.
- Every 500 response body carries a correlation id that also appears in exactly one log line.
- **`/api/freshness` is the single source of staleness** — `{lastRunAt, lastSourceTs,
  ageSeconds, stale}`. No view computes staleness independently; a second implementation is
  how two screens disagree about whether the data is live.
- `/api/freshness` is privilege-annotated per the Task 14 route map and **must be reachable by
  the wallboard**. Fail-closed would otherwise 500 every heartbeat, and the stale banner — the
  entire point of an unattended TV — never renders.
- The reconcile job reports drift as a number, and `reportsZeroDriftOnAHealthyMart` asserts it.
