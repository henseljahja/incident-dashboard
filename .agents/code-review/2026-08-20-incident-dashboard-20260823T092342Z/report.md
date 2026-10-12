# Code Review Report — 2026-08-20-incident-dashboard

**Run:** `2026-08-20-incident-dashboard-20260823T092342Z` · **Branch:** `tdd/2026-08-20-incident-dashboard`
**Layers run:** 1 + 2 (Layer 3 skipped — `--with-spec-check` not set)
**HEAD at verdict:** `05519356be84adedd1793667579926382cd98565`

---

## Layer 1 — Deterministic scan

| | Before | After fixes |
|---|---|---|
| CRITICAL | 4 | **0** |
| HIGH | 36 | 2 (both suppressed, see below) |
| MODERATE | 26 | 0 |
| LOW | 11 | 0 |

**Secrets.** gitleaks over full history: **0 findings**. No `.env` or keystore added. The
credential-shaped literals in the diff are test-fixture passwords on the test classpath
(`V7__seed_test_users.sql` and the auth ITs); they are never packaged and the accounts they
seed do not exist in any deployment.

**SAST.** semgrep: 1 WARNING, analysed and suppressed — `spring-sqli` at
`MetricsUseCase.java:242` cannot interpolate caller text, because `format` is a key into a
fixed 3-entry map that throws on an unknown key.

**Dependencies — this is where the review actually bit.** 77 advisories, 4 CRITICAL, all
tracing to one cause: Spring Boot 3.1.x is out of OSS support, so it stopped receiving
managed-dependency bumps and the transitive CVEs piled up behind a single version number.
The CRITICALs were all `tomcat-embed-core@10.1.15` — including a digest authenticator that
authenticates any unknown user, and an HTTP/2 path that does not validate request headers.

Fixed in `0551935` by bumping the parent to 3.5.3 (plus POI 5.4.0 and a new
`flyway-database-oracle` module, which Flyway 10 requires after splitting per-database
support out of `flyway-core`). **No source change was needed.** Re-verified on the new
parent: BUILD SUCCESS, 44 unit + 74 integration against the Testcontainers Oracle.

The 2 survivors are `xlsx@0.20.3`, suppressed with reasons: both advisories carry
`introduced: 0` and **no `fixed` event**, because the npm `xlsx` package was abandoned at
0.18.5 when SheetJS moved distribution to its own CDN — so OSV matches every version
forever. We install the official CDN tarball at 0.20.3, which contains both fixes, and only
ever call `XLSX.writeFile`; both advisories live in the parser, which this codebase never
invokes. Re-check if workbook import is ever added.

## Layer 2 — AI quality analysis

3 BLOCKER, 5 MAJOR — **all fixed and re-verified**. Full detail in `layer2.log`.

The three blockers are worth naming because each was invisible to a green test suite:

1. **Flyway was disabled unconditionally.** `spring.autoconfigure.exclude` turned off the
   DataSource and Flyway in the base config document with nothing re-enabling them.
   Testcontainers ITs build their own DataSource, so 74 integration tests passed while the
   shipping configuration would boot with no database and never run the auth migrations.
2. **Every Ops date filter was off by one day.** `toISOString()` on a local-midnight
   `Date`. Invisible because every surface reads the same filter store, so all the numbers
   shifted together and stayed mutually consistent while being uniformly wrong.
3. **Change-password was a dead end.** The seeded admin is forced to change its password
   before it can do anything else, and the route rendered a static placeholder with no
   form. The backend endpoint was complete and tested; only the UI was a stub.

Two findings are recorded rather than fixed, because they need a decision and not code:
the `export` privilege is a bulk/convenience boundary rather than a confidentiality one
(paging reconstructs the same data with no audit row), and Redmine's `is_private` ACL is
dropped at the mirror. Both are in `.agents/deferred-followups.md` (#5, #6).

## Layer 3 — Spec alignment

Skipped (opt-in). Covered from another direction during the TDD run: 35/35 scope-census
items verified in the final wave, and a 3-persona validation quorum reviewed the
implementation against the plan.

---

## Verdict: ✅ PASS

No CRITICAL or HIGH issues remain outstanding. Every blocker and major found by either
layer is fixed and re-verified; the two suppressed findings are documented with the
evidence for the suppression, and the two deferred findings are tracked follow-ups that
need a business answer rather than a patch.
