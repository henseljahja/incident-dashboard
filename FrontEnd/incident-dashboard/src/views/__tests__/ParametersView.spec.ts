import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/apis/client'

vi.mock('@/apis/config', async () => {
  const actual = await vi.importActual<typeof import('@/apis/config')>('@/apis/config')
  return {
    ...actual,
    fetchConfig: vi.fn<() => Promise<unknown>>(),
    saveSlaPolicy: vi.fn<(items: unknown[]) => Promise<unknown>>(),
    saveAlertRule: vi.fn<(code: string, body: unknown) => Promise<unknown>>(),
    saveParam: vi.fn<(key: string, value: string) => Promise<unknown>>(),
  }
})
vi.mock('@/layout/AppLayout.vue', () => ({
  default: { template: '<div><slot name="header-actions" /><slot /></div>' },
}))
vi.mock('@/components/common/FreshnessIndicator.vue', () => ({
  default: { template: '<span />' },
}))

import * as api from '@/apis/config'
import { mountInsight, unmountAll } from '@/components/insights/__tests__/helpers'
import { useAuthStore } from '@/stores/auth'

import ParametersView from '../ParametersView.vue'

const SNAPSHOT: api.ConfigSnapshot = {
  slaPolicy: [
    { priorityId: 1, priorityName: 'Critical', slaDays: 3, atRiskRemainingDays: 1 },
    { priorityId: 2, priorityName: 'Low', slaDays: 60, atRiskRemainingDays: 7 },
  ],
  alertRules: [
    {
      code: 'AGING_OPEN',
      enabled: false,
      severity: 'WARNING',
      minCount: 1,
      threshold: 30,
      defaultEnabled: false,
      defaultSeverity: 'WARNING',
      defaultMinCount: 1,
      defaultThreshold: 30,
      updatedAt: null,
      updatedBy: null,
    },
    {
      code: 'AT_RISK',
      enabled: true,
      severity: 'HIGH',
      minCount: 2,
      threshold: null,
      defaultEnabled: true,
      defaultSeverity: 'WARNING',
      defaultMinCount: 1,
      defaultThreshold: null,
      updatedAt: null,
      updatedBy: null,
    },
  ],
  params: [
    {
      key: 'forecast.default_horizon_days',
      type: 'INT',
      value: '7',
      defaultValue: '7',
      min: 1,
      max: 30,
      updatedAt: null,
      updatedBy: null,
    },
  ],
  recentChanges: [{ at: null, by: 'admin.test', target: 'param:x', oldValue: '1', newValue: '2' }],
}

async function mountAs(privileges: string[]) {
  const w = await mountInsight(ParametersView)
  useAuthStore().menus = [
    { menuId: 'incident.params', label: 'Parameters', route: '/parameters', privileges },
  ] as never
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.fetchConfig).mockResolvedValue(SNAPSHOT)
})
afterEach(unmountAll)

describe('ParametersView', () => {
  it('shows every section and the recent changes', async () => {
    const w = await mountAs(['view', 'manage'])

    expect(w.findAll('[data-testid="params-sla-row"]')).toHaveLength(2)
    expect(w.find('[data-testid="rule-AGING_OPEN"]').exists()).toBe(true)
    expect(w.find('[data-testid="param-forecast.default_horizon_days"]').exists()).toBe(true)
    expect(w.findAll('[data-testid="params-change-row"]')).toHaveLength(1)
  })

  it('is read-only without the manage privilege: inputs disabled, no save buttons', async () => {
    const w = await mountAs(['view'])

    expect(w.find('[data-testid="params-readonly"]').exists()).toBe(true)
    expect(w.findAll('input, select').every((el) => el.attributes('disabled') !== undefined)).toBe(
      true,
    )
    expect(w.find('[data-testid="sla-save"]').exists()).toBe(false)
    expect(w.find('[data-testid="rule-save"]').exists()).toBe(false)
    expect(w.find('[data-testid="param-save"]').exists()).toBe(false)
  })

  it('saves the SLA ladder with the edited numbers', async () => {
    vi.mocked(api.saveSlaPolicy).mockResolvedValue(SNAPSHOT)
    const w = await mountAs(['view', 'manage'])

    await w.findAll('[data-testid="sla-days"]')[1]!.setValue(45)
    await w.get('[data-testid="sla-save"]').trigger('click')
    await flushPromises()

    expect(api.saveSlaPolicy).toHaveBeenCalledWith([
      { priorityId: 1, slaDays: 3, atRiskRemainingDays: 1 },
      { priorityId: 2, slaDays: 45, atRiskRemainingDays: 7 },
    ])
    expect(w.get('[data-testid="sla-notice"]').text()).toContain('Saved')
  })

  it('saves one alert rule; a rule without a threshold sends none', async () => {
    vi.mocked(api.saveAlertRule).mockResolvedValue(SNAPSHOT.alertRules[1]!)
    const w = await mountAs(['view', 'manage'])

    const row = w.get('[data-testid="rule-AT_RISK"]')
    await row.get('[data-testid="rule-min"]').setValue(3)
    await row.get('[data-testid="rule-severity"]').setValue('CRITICAL')
    await row.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()

    expect(api.saveAlertRule).toHaveBeenCalledWith('AT_RISK', {
      enabled: true,
      severity: 'CRITICAL',
      minCount: 3,
    })
  })

  it('reset restores the rule defaults in the form without saving', async () => {
    const w = await mountAs(['view', 'manage'])

    const row = w.get('[data-testid="rule-AT_RISK"]')
    await row.get('[data-testid="rule-reset"]').trigger('click')

    expect((row.get('[data-testid="rule-severity"]').element as HTMLSelectElement).value).toBe(
      'WARNING',
    )
    expect((row.get('[data-testid="rule-min"]').element as HTMLInputElement).value).toBe('1')
    expect(api.saveAlertRule).not.toHaveBeenCalled()
  })

  it('shows the server validation message when a param is rejected', async () => {
    vi.mocked(api.saveParam).mockRejectedValue(new ApiError('Allowed range is 1 to 30.', 400))
    const w = await mountAs(['view', 'manage'])

    const row = w.get('[data-testid="param-forecast.default_horizon_days"]')
    await row.get('[data-testid="param-input"]').setValue('99')
    await row.get('[data-testid="param-save"]').trigger('click')
    await flushPromises()

    expect(api.saveParam).toHaveBeenCalledWith('forecast.default_horizon_days', '99')
    const notice = row.get('[data-testid="param-notice"]')
    expect(notice.text()).toContain('Allowed range is 1 to 30.')
    expect(notice.attributes('role')).toBe('alert')
  })

  it('shows an error line when the parameters cannot be loaded', async () => {
    vi.mocked(api.fetchConfig).mockRejectedValue(new ApiError('boom', 500))
    const w = await mountAs(['view'])

    expect(w.get('[role="alert"]').text()).toContain('Could not load')
  })
})
