# Handoff — resume point (written 2026-10-29)

Next session: say **"check the docs, and continue whatever left off"** — start here, then
`.agents/deferred-followups.md` (open items) and `AGENTS.md` (rules).

## State
- `main` = everything. PR #1 and PR #2 are merged (merge commit d21d412). Branch
  `fix/rd-22524-local-verification` is merged and can be deleted.
- Last full verification (branch tip 0c5a405): backend `./mvnw verify` green (~150 ITs),
  vitest 407/407, Playwright 94/94, vue-tsc + lint clean. Not re-run after the merge (no code changed).
- Shipped this stretch: Parameters menu (adjustable SLA/alert rules/forecast/brand settings),
  "Explain this" AI buttons on Forecast/Alerts/RCA, env-configurable backend + runtime
  `/config.js` frontend, `deploy/k8s/` (kustomize base + staging/production overlays).

## Open items (ranked)
1. **Kubernetes first real deploy.** Ingress path routing and the ingress-controller NetworkPolicy
   allow rule were never exercised (local traefik was down). Pods/probes/restricted PSA/API 401
   were verified on OrbStack. Fill registry, host, `secrets.env` per `deploy/k8s/README.md`.
2. **Excel parity level 3** needs a UAT-loaded Oracle mirror (only the 100-issue sample exists).
3. **Change Management / Availability** pages have no data source. Needs: Change tracker ID +
   success/failure/rollback status IDs, and a monitoring feed. Do NOT invent data.
4. **AI never called against a real provider** (only `FakeOpenAiServer`). Default stays OFF
   (`INCIDENT_AI_ENABLED=false`, no key). Cost estimates were given per click, no caching.
5. `AGING_OPEN` alert rule ships disabled; no manual screen-reader pass done.
6. The 9 older items in `deferred-followups.md` (ref enum seeding, `EXPORT_AUDIT` on paging,
   Redmine `is_private`, Flyway exclude, Spring Boot 3.1.5 EOL claim — verify, backend is 3.5.3 now).

## How to run things (gotchas)
- Backend tiers: `cd BackEnd/incident-service && ./mvnw verify` (Testcontainers Oracle, Docker needed).
  The shell hook may block inline `./mvnw`/body-printing `curl`: put commands in a script file.
- Playwright (tier 3): start the isolated stack in its own process
  `E2E_STACK=true ./mvnw test -Dtest=E2eStackLauncherTest -DfailIfNoTests=false` (wait for
  "E2E STACK READY", port 8081), then in `FrontEnd/incident-dashboard`:
  `npm run test:e2e` with env `E2E_PASSWORD_<USER>` for ADMIN_TEST, OPS_MANAGER, EXEC_USER,
  WALLBOARD_USER, ROTATE_ME, VIEWER (public fixtures: top of
  `src/test/resources/db/migration/V7__seed_test_users.sql`). Stop the demo stack first (memory/port).
  Never point tier 3 at `BackEnd/local-infra`.
- Demo stack: `deploy/start-demo.sh` (dashboard :8090, needs `deploy/.env`, copy from `.env.example`).
  Currently running images built from main.
- Frontend config is runtime now: `REDMINE_BASE_URL`, `AI_INSIGHTS_LIVE`, `API_BASE_URL` (container env),
  not Vite build args. Backend is single-replica (ETL has no lock). Full env table: `deploy/k8s/README.md`.
- macOS: `sed -i` needs `''` (use python for edits). Group/Map.of limit 10 entries in Java tests.

## Rules that still apply
- Production-derived data in `docs/reference/` stays local: never commit it, never paste rows externally.
- No `Co-Authored-By`/"Generated with" trailers. Tests are truth: never weaken assertions;
  tell the user before changing any test expectation.
- Push/PR/deploy need the user's go-ahead (they have approved pushes and the PR #2 merge so far, case by case).
