# Incident dashboard SPA — built with Vite, served by nginx.
#
# The runtime image contains only static assets plus the nginx template. No Node, no source, no
# node_modules, and — importantly — none of the production-derived fixture CSVs.

# node:24 — package.json declares engines.node "^22.18.0 || >=24.12.0", and the pinned pnpm
# needs node:sqlite, which does not exist before Node 22. On node:20 the install dies with
# ERR_UNKNOWN_BUILTIN_MODULE before it reads a single dependency.
FROM node:24-alpine AS build
WORKDIR /build

RUN corepack enable && corepack prepare pnpm@10.33.0 --activate

# Lockfile layer first so a source edit does not reinstall every dependency.
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .

# No VITE_* build args: deployment settings (API base, Redmine origin, AI page) are RUNTIME
# environment variables, rendered into /config.js when the container starts (see
# docker-entrypoint.d/40-runtime-config.sh and src/utils/runtimeConfig.ts). One image, any
# environment. VITE_API_BASE_URL stays unset here so the bundle defaults to the same-origin `/api`.
# `pnpm build` runs type-check in parallel with the bundle; keep it, a type error should fail the
# image rather than ship broken. VITE_API_BASE_URL is deliberately NOT set: client.ts defaults to
# `/api`, and nginx below proxies /api and /auth to the service on the compose network. Baking an
# absolute URL in here would hard-code one deployment's hostname into the bundle.
RUN pnpm build

# nginx-unprivileged: runs as uid 101 and listens on 8080, so the pod can set runAsNonRoot and
# drop every capability. The templates directory is rendered with envsubst on start.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY proxy_params_incident /etc/nginx/proxy_params_incident
COPY --chmod=0755 docker-entrypoint.d/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
ENV BACKEND_UPSTREAM=http://incident-service:8080 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
EXPOSE 8080
