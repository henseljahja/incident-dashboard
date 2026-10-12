# Incident dashboard SPA — built with Vite, served by nginx.
#
# The runtime image contains only static assets plus nginx.conf. No Node, no source, no
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

# Redmine deep-link base, baked in at build time because Vite inlines `import.meta.env.*` into
# the bundle — there is no runtime knob for it. Left EMPTY by default, which the drawer renders
# as a disabled control explaining the deployment is unconfigured. It used to fall back to
# `https://redmine.example.com`, so the demo's only call to action pointed at a domain nobody
# owns and looked like it worked.
ARG VITE_REDMINE_BASE_URL=""
ENV VITE_REDMINE_BASE_URL=$VITE_REDMINE_BASE_URL

# `pnpm build` runs type-check in parallel with the bundle; keep it, a type error should fail the
# image rather than ship broken. VITE_API_BASE_URL is deliberately NOT set: client.ts defaults to
# `/api`, and nginx below proxies /api and /auth to the service on the compose network. Baking an
# absolute URL in here would hard-code one deployment's hostname into the bundle.
RUN pnpm build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY proxy_params_incident /etc/nginx/proxy_params_incident
EXPOSE 80
