# Kubernetes deployment

Kustomize layout:

```
k8s/
  base/                 Deployments, Services, ConfigMaps, Ingress, NetworkPolicy, PDB
  overlays/staging/     namespace, host, image tags, replicas, CORS origin
  overlays/production/  same, 2 dashboard replicas
```

Neither image bakes in any environment: all configuration is environment variables.

## Deploy

1. Push both images (built from `BackEnd/incident-service` and `FrontEnd/incident-dashboard`) and set
   the registry/tag in `overlays/<env>/kustomization.yaml` (`images:`).
2. Set the real host in the overlay's Ingress patch and its `INCIDENT_CORS_ALLOWED_ORIGINS` (the public
   origin, `https://...`, never `*`).
3. Create the secrets file (git-ignored):

   ```bash
   cp overlays/production/secrets.env.example overlays/production/secrets.env
   $EDITOR overlays/production/secrets.env
   ```

   Prefer External Secrets / Sealed Secrets? Delete the `secretGenerator` block and provide a Secret
   named `incident-service-secrets` with the same keys.
4. Review, then apply:

   ```bash
   kustomize build overlays/production | kubeconform -strict -summary
   kubectl apply -k overlays/production
   ```

The Ingress ships for `ingress-nginx` (`ingressClassName: nginx`, timeout/body-size annotations). For
another controller, change the class and annotations in `base/ingress.yaml`. Routing is path-based on
one host: `/api` and `/auth` go to the backend, `/` to the dashboard, so the browser stays same-origin.
TLS comes from the `incident-dashboard-tls` secret (uncomment the cert-manager annotation to issue it).

## Constraints that shape the manifests

- **Backend is one replica, `strategy: Recreate`.** The ETL `@Scheduled` jobs have no distributed lock.
  If you ever scale out, set `INCIDENT_ETL_SCHEDULING_ENABLED=false` on the extras.
- **TLS is required in production.** `INCIDENT_JWT_COOKIE_SECURE=true` (the base default) makes the
  refresh cookie `Secure`; served over plain HTTP, login succeeds but the browser drops the cookie.
- **CORS must equal the public origin** (`INCIDENT_CORS_ALLOWED_ORIGINS`).
- **Oracle is external.** Put the JDBC URL in the Secret. First boot runs Flyway (the startup probe allows
  6 minutes). If the mirror tables already exist in the application schema, also set
  `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and `SPRING_FLYWAY_BASELINE_VERSION=0`.
- **Mirror schema separator**: `INCIDENT_MIRROR_SCHEMA` carries its own trailing `.` or `_`
  (`REDMINE_MIRROR.` for a schema, `REDMINE_MIRROR_` for a table prefix), and
  `SPRING_FLYWAY_PLACEHOLDERS_MIRROR` must match it.
- Pods run non-root, read-only root filesystem, all capabilities dropped, seccomp `RuntimeDefault`;
  the namespaces enforce the `restricted` Pod Security level. Writable paths are `emptyDir`s.
- NetworkPolicy denies all ingress except the ingress controller (namespace `ingress-nginx`; edit if
  yours differs). Egress is open because Oracle/Redmine/AI endpoints are site-specific.

## Environment variables

### Backend (`incident-service-config` ConfigMap, `incident-service-secrets` Secret)

| Variable | Default | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | none | **Secret.** `jdbc:oracle:thin:@//host:1521/SERVICE` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | none | **Secret** |
| `INCIDENT_JWT_SECRET` | none | **Secret**, at least 32 bytes |
| `INCIDENT_ADMIN_PASSWORD` | none | **Secret**, at least 12 chars, used on first boot only |
| `INCIDENT_AI_API_KEY` | empty | **Secret**, only when AI is enabled |
| `INCIDENT_CORS_ALLOWED_ORIGINS` | `http://localhost:5175` | Public origin(s), comma separated |
| `INCIDENT_JWT_COOKIE_SECURE` | `true` in base | `false` only for plain-HTTP testing |
| `INCIDENT_MIRROR_SCHEMA` | `REDMINE_MIRROR.` | Includes trailing separator |
| `SPRING_FLYWAY_PLACEHOLDERS_MIRROR` | (follows mirror) | Must equal `INCIDENT_MIRROR_SCHEMA` |
| `SPRING_FLYWAY_ENABLED` | `true` | |
| `INCIDENT_ETL_SCHEDULING_ENABLED` | `true` | Disable on any extra replica |
| `INCIDENT_ETL_REFRESH_INTERVAL_MS` | `120000` | Incremental refresh cadence |
| `INCIDENT_ETL_RECONCILE_CRON` | `0 15 * * * *` | Spring 6-field cron |
| `INCIDENT_ETL_CHUNK_SIZE` | `500` | |
| `INCIDENT_ETL_WATERMARK_OVERLAP_SECONDS` | `300` | |
| `INCIDENT_ETL_MIRROR_LAG_THRESHOLD_SECONDS` | `3600` | Mirror-lag warning threshold |
| `INCIDENT_EXPORT_MAX_ROWS` | `50000` | |
| `INCIDENT_AI_ENABLED` | `false` | |
| `INCIDENT_AI_PROVIDER` | `none` | |
| `INCIDENT_AI_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible endpoint |
| `INCIDENT_AI_MODEL` | `gpt-4o-mini` | |
| `INCIDENT_AI_SEND_SUBJECTS` | `false` | Send incident subjects to the provider |
| `INCIDENT_AI_TIMEOUT_SECONDS` | `20` | |
| `SERVER_PORT` | `8080` | |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | Honours `X-Forwarded-*` from the Ingress |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | `30s` | Graceful shutdown |
| `TZ` | `Asia/Jakarta` | Also set in the image |

Other Spring properties can be set the same way (`SPRING_...` / `INCIDENT_...` relaxed binding).

### Dashboard (`incident-dashboard-config` ConfigMap)

| Variable | Default | Notes |
|---|---|---|
| `API_BASE_URL` | `/api` | Keep `/api` behind the path-routing Ingress |
| `REDMINE_BASE_URL` | empty | Enables "Open in Redmine" links |
| `AI_INSIGHTS_LIVE` | `false` | Shows AI routes (backend AI must be on too) |
| `BACKEND_UPSTREAM` | `http://incident-service:8080` | Only for the docker-compose same-origin proxy |

These are rendered into `/config.js` at container start; change the ConfigMap and restart the
Deployment (`kubectl rollout restart deploy/incident-dashboard`) to apply.

## Verification status

Both overlays pass `kustomize build | kubeconform -strict`. The `base` was applied to a local OrbStack
cluster (Pod Security `restricted`, read-only root, real Oracle): both pods became Ready, `/health`
returned 200, `/api/...` returned 401 unauthenticated, the dashboard served `/`, `/ops` and `/config.js`.
Ingress routing and the ingress-controller NetworkPolicy allow rule were **not** exercised end to end
(the local cluster's controller was not running), so test them on first deploy.
