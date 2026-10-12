# Stack-by-subdirectory map — mti/incident-dashboard

Checked-file data backing the "resolve $(pwd) to a row before dispatching any agent"
rule (`~/Project/CLAUDE.md`) and `/straventa-tdd` Step 0.5. Add a row before extending
the pipeline to a new sub-project; do not let agents guess an unmapped directory.

| Subdirectory | Stack | Tier-1 command | Domain expert | Tier-fail fixer |
|---|---|---|---|---|
| `BackEnd/incident-service` | Spring Boot 3.1.5 / Java 17 (Maven) | `./mvnw -q test` (tier2: `./mvnw -q verify`) | mti-monorepo-expert | spring-boot-fixer |
| `BackEnd/local-infra` | docker-compose only (`gvenzl/oracle-free:23-slim`, port 1522), no app code | n/a | mti-monorepo-expert | n/a |
| `FrontEnd/incident-dashboard` | Vue 3 + TypeScript (Vite, PrimeVue 4 Aura, Tailwind 4) | `pnpm type-check && pnpm lint && pnpm test --run` | mti-monorepo-expert | vue-frontend-fixer |
| `docs/reference` | Redmine SQL exports + source report query + target xlsx. Reference data, no code. | n/a | claude | n/a |
| `.` (workspace root) | Agent-workspace artifacts only (`.agents/**`) — no application code | n/a | claude | n/a |

Conventions are copied from `mti/yob` deliberately: same Spring layering
(`domain/{model,usecase,config}` + `infrastructure/{rest,datasource}`), package root
`id.co.yokke.incidentdashboard`, same PrimeVue Aura preset and lint/format setup. Same
team, same muscle memory, and it keeps a future fold-into-yob cheap.

This file and the workspace-root `AGENTS.md` `test_commands` block are edited together.
