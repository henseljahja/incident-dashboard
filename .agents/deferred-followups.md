# Deferred follow-ups — mti/incident-dashboard

## 2026-08-20-incident-dashboard — T007 — REF_CUSTOM_FIELD_ENUM cannot be fully seeded — 20260821

**Reason:** The plan assumed the delivered workbook could seed `REF_CUSTOM_FIELD_ENUM`, because
"the labels are literally in the report we are reproducing". The labels are — **the enum value
ids are not.** `custom_values.value` stores the id, so labels alone cannot key a row.

`scripts/extract-ref-tables.py` recovers real ids by joining
`journal_details` (records the numeric enum id per field) -> `journals` (journal -> issue) ->
the workbook label for that issue, latest journal wins. The journal dumps cover only 100
issues, so only **16 of 234** (field, id) -> label pairs are recoverable:

| field | resolved | unresolved |
|---|---|---|
| 41 type_of_incident | 3 | 23 |
| 77 category_incident | 4 | 120 |
| 93 impact_severity | 2 | 2 |
| 94 urgency_severity | 2 | 2 |
| 101 institusi | 4 | 70 |
| 349 external_internal | 1 | 1 |

218 labels are written with an empty `enum_value_id` to
`target/ref-seed/ref_custom_field_enum_unresolved.csv` for a DBA to fill.

**Impact if not closed:** every unresolved id renders as `Unknown (<id>)`. That is loud
degradation by design, not data loss — but with 120 of 124 `category_incident` values
unresolved, the ops view's category breakdown would be almost entirely `Unknown (...)` and
useless. **This gates showing the category and institusi charts to stakeholders**, not the build.

**Named follow-up:** one of, in preference order —
1. Ask the sync team to stream `custom_field_enumerations` after all (then flip
   `incident.label.enum-resolver=mirror`; the code path exists and fails fast if absent).
2. Get a one-off `SELECT id, custom_field_id, name FROM custom_field_enumerations` export.
3. Get a wider `journal_details`/`journals` export than the 100-issue sample.

**Trigger to close:** before the ops view is shown to stakeholders. Re-run
`scripts/extract-ref-tables.py` and confirm `ref_custom_field_enum_unresolved.csv` is empty.
**Status:** open

---

## 2026-08-20-incident-dashboard — T007 — status 108 `Re-CCB` may be in scope — 20260821

**Reason:** Ground truth treats ids 75-108 as out-of-scope (HR-recruitment and procurement
statuses on other trackers), and 108 is in that band. But `Re-CCB` has `position` 11, placing
it mid-incident-workflow between `CCB` (10) and `RTM` (13) — the out-of-scope statuses occupy
positions 42-74. It looks like an incident status added late, not a procurement one.

**Impact:** it currently surfaces as `Unmapped`. Loud degradation, never dropped from an
aggregate — so this is a labelling question, not a correctness bug.

**Named follow-up:** confirm with the Redmine admins which tracker uses `Re-CCB`. If it is used
by trackers 7/16/32/33/41, add it to `CFG_STATUS_STAGE_MAP` as stage `In Progress` and bump the
expected row count from 40 to 41 in `ConfigTablesIT`.
**Trigger to close:** first run against real (non-fixture) data — check
`SELECT DISTINCT STATUS_ID FROM INCIDENT_FACT WHERE STATUS_STAGE = 'Unmapped'`.
**Status:** open

---

## 2026-08-20-incident-dashboard — T007 — `Developer_assigned` has no id column — 20260821

**Reason:** The workbook renders 38 distinct developer names but never the user id, so those
cannot key a `REF_USER` row. Reported rather than guessed — minting surrogate ids would join
to nothing.

**Impact:** `DEVELOPER_ASSIGNED` (SC027) resolves from journal prop_key 126, which DOES carry
the numeric user id, so the mart path is unaffected. This only limits pre-seeding REF_USER
with developer firstnames ahead of the first ETL run.

**Named follow-up:** same export as the enum gap above (a real `users` pull).
**Trigger to close:** first run against real data.
**Status:** open

---

## 2026-08-20-incident-dashboard — tooling — verify-wave tier2 boundary regex misses this repo — 20260821

**Reason:** `verify-wave.sh` selects tier2 on an incremental wave only when the delta matches
`^migrations/|sqlc\.yaml` — repo-root-anchored and Go/payops-shaped. This project's migrations
live at `BackEnd/incident-service/src/main/resources/db/migration/`, so **the boundary never
fires here**. Phase 1's first gate recorded `tier2: -1 (SKIPPED)` while still reporting PASS —
exactly the failure mode the script's own comment warns about: "the gate looks satisfied and is
not."

**Worked around, not fixed:** every phase gate in this plan is now run with the sanctioned
`--force-tier2` opt-in, so tier2 genuinely executes and the evidence bundle records rc=0.

**Named follow-up:** either (a) widen the regex to `(^|/)(migrations|db/migration)/|sqlc\.yaml`
in `~/.agents/straventa/verify-wave/verify-wave.sh`, or (b) make the pattern configurable per
sub-project via an AGENTS.md key. (a) is a one-line change but touches shared estate tooling
used by every repo, so it is not being made unilaterally mid-run.
**Trigger to close:** next time verify-wave is edited, or before another non-Go repo joins the
pipeline.
**Status:** open

---

## 5. Paging `/api/incidents` reconstructs the full dataset with no `EXPORT_AUDIT` row

**Raised by:** security persona, validation quorum, 2026-08-23
**Status:** open

`/api/incidents/export` is airtight — cap and audit row are written in one `@Transactional`
method, `IncidentQueryUseCase.rows` is package-private with a single caller, and
`IncidentQueryIT.everyExportWritesAnAuditRow` asserts exactly one row. But a `VIEWER` holding
only `incident.ops:view` can script `GET /api/incidents?size=200&page=0..N` and reconstruct the
identical dataset with **zero** audit rows. There is no rate limit anywhere in the service.

So the `export` privilege is a bulk/convenience boundary, not a confidentiality one. A departing
employee with view-only access pulls 50k customer and staff rows in 250 requests, and the
incident is exactly as unreconstructable as the one `EXPORT_AUDIT` exists to prevent.

**Trigger:** before the first non-pilot rollout, or immediately if the user base widens beyond
the initial ops team.
**Options:** a lightweight audit row (or per-principal cumulative row counter with a WARN
threshold) from `IncidentQueryUseCase.page`, or a rows-served-per-principal-per-hour cap.

---

## 6. Redmine's per-issue `is_private` ACL is dropped at the mirror boundary

**Raised by:** security persona, validation quorum, 2026-08-23
**Status:** open — **needs a business decision, not just code**

`issues.is_private` is never selected in `incident-delta.sql`, never carried into
`INCIDENT_FACT`, and never filtered. An incident marked private in Redmine — visible there only
to members with "view private issues" — is surfaced to every `incident.ops:view` holder and is
bulk-exportable.

This faithfully matches the source report query, so it may well be an accepted business
decision. It is not recorded as one anywhere, which is the actual problem: nobody can tell
whether it was decided or overlooked.

**Trigger:** before rollout beyond the pilot ops team. Ask the incident-management owner whether
private issues are in scope. If yes, record the decision here and close. If no, carry
`IS_PRIVATE` into the fact table and either exclude it from `V_INCIDENT` or gate it behind a
distinct privilege.

---

## 7. `spring.autoconfigure.exclude` disables Flyway unconditionally

**Raised by:** security persona, validation quorum, 2026-08-23
**Status:** open

`application.yml` excludes `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration` and
`FlywayAutoConfiguration`, and no profile re-enables them — only `AbstractOracleIT` blanks the
property for tests. Deployed as-is, V4/V5 and `R__SeedAdmin` never run, so the auth schema and
the fail-fast bootstrap-admin seed have **never executed outside Testcontainers**.

Not exploitable (every DB bean is `@Lazy`, so requests fail closed with a 500), but the auth
migration path is untested in the shape it will actually ship.

**Trigger:** first real deployment. Add a `prod` profile that clears
`spring.autoconfigure.exclude`, and gate the exclusion behind the scaffold/test profile.

---

## 8. Spring Boot 3.1.5 is an EOL branch

**Raised by:** security persona, validation quorum, 2026-08-23
**Status:** open

Pinned parent 3.1.5 (Nov 2023) resolves Tomcat 10.1.15 and Spring Framework 6.0.13, carrying an
unpatched backlog including CVE-2023-46589 (trailer-header request smuggling). The persona found
no code path in this repo that triggers the Spring-web CVEs — no `UriComponentsBuilder` on user
input, no `RouterFunctions` static resources — so this is version exposure, not a demonstrated
exploit, and the smuggling case depends on a fronting proxy we do not have a manifest for.

**Trigger:** before production exposure. Bump to a supported 3.5.x line, re-run tier 2, and take
the jjwt 0.11.5 deprecation cleanup in the same pass.

---

## 9. Smaller quorum findings, batched

**Raised by:** validation quorum, 2026-08-23 · **Status:** open

- `/auth/refresh-token` does not rotate the `jti`; a stolen refresh cookie stays valid its full
  24h with no reuse detection. Mitigated by `HttpOnly; Secure; SameSite=Lax; Path=/auth`.
- `LoginUseCase.changePassword` verifies the current password with no attempt counter, unlike
  `authenticate`. Route the failure through `registerFailure`.
- `RequirePrivilege` is `@Target(METHOD)` but `PrivilegeInterceptor` also probes `getBeanType()`
  — dead branch that reads as if class-level annotation were supported.
- `application-localdocker.yml` defaults the DB password to `changeme`; drop the default so a
  misconfigured deploy fails loudly.
- `AuthFilter`'s `ErrorMapping.write` bypasses the dispatcher, so filter-level 401/403s carry no
  CORS header and the SPA sees an opaque network error instead of the message.
- `xlsx` is a non-registry CDN tarball — version is correct (past the 0.19.3 and 0.20.2 fixes)
  but invisible to `pnpm audit`; needs a manual SheetJS advisory trigger.
- `MartSchemaIT` half-2 and `KpiRow.spec.ts` value assertions are weak (substring/whole-text
  matches). Scope them per-card / assert `repository.factColumns()`.
- `MirrorEnumLabelResolver` is never activated in any test; its fail-fast constructor has never
  executed.
- `IncidentFilterBar`'s tracker/stage/priority option lists are hand-transcribed from
  `V2__seed_config.sql` — the only backend-owned list in the system retyped by hand.
