/**
 * SLA / severity banding for the incident heatmap.
 *
 * `slaBucket` maps an issue's elapsed age against its priority-derived SLA
 * budget into a small set of display bands. `SEVERITY_PALETTE` maps each
 * band to a colour token.
 *
 * DESIGN RULE: the severity palette must NEVER reuse the brand primary
 * colour (the PrimeVue Aura preset's default primary, emerald). Heatmap
 * cells communicate SLA risk, not brand identity — a later test asserts
 * no palette entry equals `BRAND_PRIMARY`.
 */

export type SlaBucket = 'Unknown' | 'Breached' | '80-100%' | '50-80%' | '0-50%'

/** PrimeVue Aura preset's default primary colour (emerald-500). Reference only — never assign this to a severity band. */
export const BRAND_PRIMARY = '#10b981'

export function slaBucket(elapsedDays: number | null, slaDays: number): SlaBucket {
  if (elapsedDays === null) {
    return 'Unknown'
  }
  if (elapsedDays > slaDays) {
    return 'Breached'
  }
  if (elapsedDays >= slaDays * 0.8) {
    return '80-100%'
  }
  if (elapsedDays >= slaDays * 0.5) {
    return '50-80%'
  }
  return '0-50%'
}

export const SEVERITY_PALETTE: Record<SlaBucket, string> = {
  Unknown: '#94a3b8', // slate-400
  Breached: '#ef4444', // red-500
  '80-100%': '#f97316', // orange-500
  '50-80%': '#f59e0b', // amber-500
  '0-50%': '#3b82f6', // blue-500
}
