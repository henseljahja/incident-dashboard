import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { IncidentRow } from '@/apis/incidents'
import { i18n } from '@/i18n'

import IncidentDetailDrawer from '../IncidentDetailDrawer.vue'

/** Invented ticket. */
function makeRow(overrides: Partial<IncidentRow> = {}): IncidentRow {
  return {
    NO_INCIDENT: 5310,
    SUBJECT: 'Settlement file arrives late',
    TRACKER: 'Incident',
    STATUS: 'Open',
    PRIORITY: 'High',
    SLA_BUCKET: 'Breached',
    ...overrides,
  }
}

let wrapper: VueWrapper | null = null

async function mountDrawer(row: IncidentRow) {
  wrapper = mount(IncidentDetailDrawer, {
    props: { visible: true, row },
    attachTo: document.body,
    global: { plugins: [PrimeVue] },
  })
  await flushPromises()
  return wrapper
}

/** The Drawer teleports to `body`. */
const inBody = <T extends Element = HTMLElement>(testId: string) =>
  document.body.querySelector<T>(`[data-testid="${testId}"]`)

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllEnvs()
})

describe('IncidentDetailDrawer', () => {
  it('header links ticket', async () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', 'https://redmine.invalid')
    await mountDrawer(makeRow())

    const link = inBody<HTMLAnchorElement>('drawer-redmine-number')
    expect(link).not.toBeNull()
    expect(link!.textContent?.trim()).toBe('#5310')
    expect(link!.getAttribute('href')).toBe('https://redmine.invalid/issues/5310')
    expect(link!.getAttribute('target')).toBe('_blank')
    expect(link!.getAttribute('rel')).toBe('noopener noreferrer')
    expect(link!.getAttribute('aria-label')).toBe('Open #5310 in Redmine')
    // The body CTA points at the same ticket.
    expect(inBody<HTMLAnchorElement>('open-in-redmine')!.getAttribute('href')).toBe(
      'https://redmine.invalid/issues/5310',
    )
  })

  it('shows a plain number and explains the missing base url', async () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', '')
    await mountDrawer(makeRow())

    expect(inBody('drawer-redmine-number')).toBeNull()
    expect(inBody('drawer-number')!.textContent?.trim()).toBe('#5310')
    expect(inBody('open-in-redmine')).toBeNull()
    expect(inBody('open-in-redmine-unconfigured')!.textContent).toContain('VITE_REDMINE_BASE_URL')
  })

  it('translates labels and the SLA bucket enum, but not the data values', async () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', '')
    i18n.global.locale.value = 'id'
    await mountDrawer(makeRow())

    const text = document.body.textContent ?? ''
    expect(text).toContain('Detail insiden')
    expect(text).toContain('Prioritas')
    expect(text).toContain('Melewati SLA')
    // Status and tracker names are Redmine data, shown as Redmine has them.
    expect(text).toContain('Open')
    expect(text).toContain('Incident')
    expect(inBody('open-in-redmine-unconfigured')!.textContent).toContain('belum mengatur')
  })
})
