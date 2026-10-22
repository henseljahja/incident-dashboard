/**
 * Deployment settings that must be changeable WITHOUT rebuilding the bundle.
 *
 * Vite inlines `import.meta.env.VITE_*` at build time, which would tie one image to one
 * environment. Instead the page loads `/config.js` before the app, which sets
 * `window.__APP_CONFIG__`; in a container that file is generated from environment variables at
 * start-up. Order of precedence for each key: runtime config, then the build-time `VITE_*`
 * variable (what `pnpm dev` and the e2e stack use), then the default.
 */
export interface AppConfig {
  /** Backend base, e.g. `/api` (same origin, the default) or `https://api.example.com/api`. */
  apiBaseUrl?: string
  /** Redmine origin for ticket deep links; empty disables the links. */
  redmineBaseUrl?: string
  /** `'true'` turns on the live AI Insights page instead of the placeholder. */
  aiInsightsLive?: string
}

declare global {
  interface Window {
    __APP_CONFIG__?: AppConfig
  }
}

const BUILD_TIME: Record<keyof AppConfig, string | undefined> = {
  get apiBaseUrl() {
    return import.meta.env.VITE_API_BASE_URL as string | undefined
  },
  get redmineBaseUrl() {
    return import.meta.env.VITE_REDMINE_BASE_URL as string | undefined
  },
  get aiInsightsLive() {
    return import.meta.env.VITE_AI_INSIGHTS_LIVE as string | undefined
  },
}

/** Read at call time (not import time) so tests and late-loaded config are honoured. */
export function appConfig(key: keyof AppConfig): string | undefined {
  const runtime = typeof window === 'undefined' ? undefined : window.__APP_CONFIG__?.[key]
  if (typeof runtime === 'string' && runtime.trim() !== '') return runtime.trim()
  const buildTime = BUILD_TIME[key]
  return buildTime === undefined ? undefined : buildTime
}
