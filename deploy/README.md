# Demo stack — run the dashboard on one machine

Brings up Oracle 23 Free, the incident service, and the SPA behind nginx, so a human can log
in and click around. **This is a demo/evaluation stack, not a production manifest and not the
test stack** — see the header of [`docker-compose.yml`](docker-compose.yml) for how it differs
from both.

## Prerequisites

- Docker with ~6 GB available to it. Oracle Free is the heavy part.
- The **fixture CSVs** at `BackEnd/incident-service/src/test/resources/fixtures/`. Six of the
  nine are production-derived and therefore gitignored, so a fresh clone will not have them —
  get them from an existing working copy. `seed-demo.sh` checks and tells you if they are
  missing.
- A JDK for the seeding step (it runs through `./mvnw`).

## Run it

```bash
cd deploy
cp .env.example .env      # then change the values
./start-demo.sh
```

Takes about four minutes cold, most of it Oracle uncompressing its data files.

### Use `start-demo.sh` — `docker compose up -d` on its own will not work

Seeding has to happen on **both sides** of the service's Flyway run, so no single compose
invocation can express it:

```
mirror DDL  ->  service starts (Flyway)  ->  fixture rows
```

Get it wrong in either direction and you get `ORA-00942`, just on a different table:

- **all at once** — `V3__mart.sql` cannot create `V_INCIDENT` over mirror tables that do not exist yet
- **all up front** — `ref_custom_field_enum` and `ref_user` target `REF_` tables that `V1__config_tables.sql` has not created yet

Tier 2 sequences the same three steps the same way; `AbstractOracleIT` calls the ordering
load-bearing. A side effect worth knowing: creating the mirror first leaves a non-empty schema,
so the service's Flyway is baselined at version 0 in the compose file. Version 0 and not
Flyway's default of 1, or `V1__config_tables.sql` would be silently skipped forever.

The seeder loads the **mirror only**. The service's own scheduled ETL then builds
`INCIDENT_FACT` from it on the next tick — the real path, not a bypass. The demo interval is 30
seconds, so give it about that long before the dashboard shows figures.

To re-seed a stack that is already up: `./seed-demo.sh data`.

## Credentials

Open **http://localhost:8090**

| | |
|---|---|
| Username | `admin` |
| Password | whatever you set as `INCIDENT_ADMIN_PASSWORD` in `deploy/.env` |

`.env.example` ships `AdminDemo#2026!`. **Minimum 12 characters** — the seeding migration
refuses a weaker one and the failure aborts application startup, so a short value here shows
up as "the service won't boot" rather than as a warning.

**First login forces a password change.** The bootstrap admin is seeded with
`MUST_CHANGE_PASSWORD=Y` and the session is restricted to the change-password scope until you
complete the form. That is intended, not a bug.

The account has the `ADMIN` role, which reaches all three views: Ops, Wallboard and Exec.

Verified end to end against this stack on 2026-08-23: login through nginx returns a token with
`mustChangePassword: true`, that token is correctly refused (403) on `/api/incidents`, the
change-password form clears the flag, and the dashboard then serves 95 incidents from the
fixture set.

### There are no other accounts

The `ops.manager` / `exec.user` / `viewer` / … logins in `V7__seed_test_users.sql` are on the
**test classpath only**. They are never packaged into the jar and do not exist in this stack.
Do not hand them out as demo credentials.

## Serving over plain HTTP costs you the session — read this before deploying

`docker-compose.yml` sets `INCIDENT_JWT_COOKIE_SECURE=false`, and that line is doing real work.

The refresh cookie normally carries `Secure`. A browser **discards** a `Secure` cookie that
arrives over plain HTTP — with one exception, `localhost`, which every browser treats as a
trustworthy origin. So a stack tested at `http://localhost:8090` behaves perfectly and the same
stack at `http://<ip>:8090` is broken: login still returns 200 with a token, the cookie is
silently dropped, and every `/auth/refresh-token` answers 401. The session dies on the first
page reload, and the silent refresh-on-401 can never recover it. Nothing in any response says
"cookie rejected" — it just looks like the user was logged out for no reason. The wallboard, an
unattended screen, ends up showing a login form.

Turning `Secure` off puts a 24-hour refresh credential in cleartext on the network. It is
defensible on an isolated internal host and nowhere else. **The fix is TLS**: put a certificate
in front of nginx, delete the `INCIDENT_JWT_COOKIE_SECURE` line, and the default (`true`) is
correct again.

Found on the 172.16.54.213 demo stack, 2026-08-26. `AuthFlowIT` asserts the `true` default so
it cannot silently invert.

## The Executive view is empty with the shipped fixtures — this is not a bug

`/exec` hard-scopes to the **current calendar month**. Every fixture issue is from July 2026 or
earlier, so the tiles read `—` and the charts are blank. The same query over July returns
95 / 5 / 2 / 38. Ops and Wallboard have no such window and show data normally. If you are
demoing to stakeholders, show Ops, or re-seed with issues dated inside the current month.

## Ports

| Port | What | Why not the obvious one |
|---|---|---|
| 8090 | Dashboard (nginx) | |
| 8081 | Service, direct | |
| 1523 | Oracle | 1521 is the conventional default and 1522 belongs to the dev stack in `BackEnd/local-infra/` — 1523 lets all three run at once |

## Data sensitivity

The fixtures are production-derived: real customer names, staff names and incident
descriptions. They are read from your working copy at seed time and are deliberately **not**
baked into any image, so the images stay safe to move around — the running database does not.
Treat the machine accordingly, and do not point this stack at anything shared.

## Teardown

```bash
docker compose down          # keep the data
docker compose down -v       # also drop the Oracle volume, forcing a fresh seed
```

## Kubernetes

Production-style manifests (kustomize: `base` + `staging` / `production` overlays, Ingress,
NetworkPolicy, PDB) live in [`k8s/`](k8s/README.md), including the full environment-variable
reference for both images.
