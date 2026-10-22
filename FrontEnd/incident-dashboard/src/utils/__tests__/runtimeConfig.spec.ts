import { afterEach, describe, expect, it, vi } from 'vitest'

import { appConfig } from '../runtimeConfig'
import { redmineBaseUrl } from '../redmine'

afterEach(() => {
  delete window.__APP_CONFIG__
  vi.unstubAllEnvs()
})

describe('runtime config', () => {
  it('falls back to the build-time variable, then to undefined', () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', 'https://build.example')
    expect(appConfig('redmineBaseUrl')).toBe('https://build.example')
    vi.stubEnv('VITE_REDMINE_BASE_URL', '')
    expect(appConfig('redmineBaseUrl')).toBe('')
  })

  it('runtime config wins over the build-time value', () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', 'https://build.example')
    window.__APP_CONFIG__ = { redmineBaseUrl: 'https://runtime.example' }
    expect(appConfig('redmineBaseUrl')).toBe('https://runtime.example')
  })

  it('an empty or blank runtime value does not mask the build-time one', () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', 'https://build.example')
    window.__APP_CONFIG__ = { redmineBaseUrl: '   ' }
    expect(appConfig('redmineBaseUrl')).toBe('https://build.example')
  })

  it('feeds the Redmine link builder, trimming trailing slashes', () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', '')
    window.__APP_CONFIG__ = { redmineBaseUrl: 'https://redmine.example/' }
    expect(redmineBaseUrl()).toBe('https://redmine.example')
    delete window.__APP_CONFIG__
    expect(redmineBaseUrl()).toBeNull()
  })

  it('carries the AI page switch and the API base', () => {
    window.__APP_CONFIG__ = { aiInsightsLive: 'true', apiBaseUrl: 'https://api.example/api' }
    expect(appConfig('aiInsightsLive')).toBe('true')
    expect(appConfig('apiBaseUrl')).toBe('https://api.example/api')
  })
})
