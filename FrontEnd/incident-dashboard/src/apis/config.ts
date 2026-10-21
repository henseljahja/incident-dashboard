import { apiRequest } from './client'

/**
 * `/api/config` — the adjustable parameters (SLA ladder, alert rules, scalar knobs).
 *
 * A second write surface beside `apis/admin.ts`, with the same justification: it writes OUR OWN
 * `CFG_*` tables, never the Redmine mirror. The server authorises every call (`incident.params`
 * view to read, manage to write) and records the editor; `can()` only decides what to render.
 */
export type Severity = 'INFO' | 'WARNING' | 'HIGH' | 'CRITICAL'
export const SEVERITIES: Severity[] = ['INFO', 'WARNING', 'HIGH', 'CRITICAL']

export interface ConfigParam {
  key: string
  type: 'INT' | 'DECIMAL' | 'TEXT'
  value: string
  defaultValue: string
  min: number | null
  max: number | null
  updatedAt: string | null
  updatedBy: string | null
}

export interface AlertRule {
  code: string
  enabled: boolean
  severity: Severity
  minCount: number
  threshold: number | null
  defaultEnabled: boolean
  defaultSeverity: Severity
  defaultMinCount: number
  defaultThreshold: number | null
  updatedAt: string | null
  updatedBy: string | null
}

export interface SlaItem {
  priorityId: number
  priorityName: string
  slaDays: number
  atRiskRemainingDays: number
}

export interface ConfigChange {
  at: string | null
  by: string
  target: string
  oldValue: string | null
  newValue: string | null
}

export interface ConfigSnapshot {
  slaPolicy: SlaItem[]
  alertRules: AlertRule[]
  params: ConfigParam[]
  recentChanges: ConfigChange[]
}

export interface AlertRuleUpdate {
  enabled?: boolean
  severity?: Severity
  minCount?: number
  threshold?: number | null
}

export const CONFIG_QUERY_KEY = ['config'] as const

export const fetchConfig = () => apiRequest<ConfigSnapshot>('/config')

export const saveSlaPolicy = (items: Omit<SlaItem, 'priorityName'>[]) =>
  apiRequest<ConfigSnapshot>('/config/sla-policy', { method: 'PUT', body: { items } })

export const saveAlertRule = (code: string, body: AlertRuleUpdate) =>
  apiRequest<AlertRule>(`/config/alert-rules/${encodeURIComponent(code)}`, {
    method: 'PUT',
    body,
  })

export const saveParam = (key: string, value: string) =>
  apiRequest<ConfigParam>(`/config/params/${encodeURIComponent(key)}`, {
    method: 'PUT',
    body: { value },
  })
