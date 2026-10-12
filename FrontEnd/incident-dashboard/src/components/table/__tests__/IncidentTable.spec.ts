import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExportResult, IncidentRow } from '@/apis/incidents'
import { useAuthStore } from '@/stores/auth'
import { useFiltersStore } from '@/stores/filters'

vi.mock('@/apis/incidents', async () => {
  const actual = await vi.importActual<typeof import('@/apis/incidents')>('@/apis/incidents')
  return {
    ...actual,
    fetchExport: vi.fn<(params: URLSearchParams) => Promise<ExportResult>>(),
  }
})
vi.mock('@/utils/export', () => ({
  downloadExportWorkbook: vi.fn<(result: ExportResult, filename?: string) => void>(),
}))

import { fetchExport } from '@/apis/incidents'
import { downloadExportWorkbook } from '@/utils/export'

import IncidentTable from '../IncidentTable.vue'

function makeRow(overrides: Partial<IncidentRow> = {}): IncidentRow {
  return {
    NO_INCIDENT: 1,
    SUBJECT: 'Payment gateway timeout',
    TRACKER: 'Incident',
    STATUS: 'Open',
    PRIORITY: 'High',
    ...overrides,
  }
}

function exportResult(overrides: Partial<ExportResult> = {}): ExportResult {
  return {
    exportId: 'exp-1',
    rowCount: 1,
    truncated: false,
    maxRows: 50000,
    content: [makeRow()],
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
  const auth = useAuthStore()
  auth.menus = [
    { menuId: 'incident.ops', label: 'Ops', route: '/ops', privileges: ['view', 'export'] },
  ]
})

describe('export requests the full filtered set, not the current page', () => {
  it('issues an export request that carries no page/size params, even mid-way through a large paged result', async () => {
    vi.mocked(fetchExport).mockResolvedValue(exportResult())
    const filters = useFiltersStore()
    // Page 1 of many, non-default size — proves the exclusion is structural, not incidental.
    filters.page = 1
    filters.size = 25
    filters.tracker = ['Incident']

    const wrapper = mount(IncidentTable, {
      props: { rows: [makeRow()], loading: false, page: 1, size: 25, totalElements: 400 },
    })

    await wrapper.find('[data-testid="export-button"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect(fetchExport).toHaveBeenCalledTimes(1)
    const paramsArg = vi.mocked(fetchExport).mock.calls[0]![0]
    expect(paramsArg.has('page')).toBe(false)
    expect(paramsArg.has('size')).toBe(false)
    expect(paramsArg.getAll('tracker')).toEqual(['Incident'])
    expect(downloadExportWorkbook).toHaveBeenCalledWith(exportResult())
  })

  it('shows a visible banner when the export came back truncated', async () => {
    vi.mocked(fetchExport).mockResolvedValue(exportResult({ truncated: true }))
    const wrapper = mount(IncidentTable, {
      props: { rows: [makeRow()], loading: false, page: 0, size: 25, totalElements: 1 },
    })

    await wrapper.find('[data-testid="export-button"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toMatch(/truncat/i)
  })
})

describe('incident rows are reachable and activatable by keyboard', () => {
  it('emits the same select payload from a keyboard activation as from a click', async () => {
    const row = makeRow()
    const wrapper = mount(IncidentTable, {
      props: { rows: [row], loading: false, page: 0, size: 25, totalElements: 1 },
    })

    const activator = wrapper.find('[data-testid="incident-row-select"]')
    expect(activator.exists()).toBe(true)
    // A real, natively-focusable control — not a div/tr relying on a synthetic keydown handler.
    expect(activator.element.tagName.toLowerCase()).toBe('button')

    await activator.trigger('click')

    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')![0]).toEqual([row])
  })

  it('gives the activator an accessible name that identifies the row', () => {
    const wrapper = mount(IncidentTable, {
      props: {
        rows: [makeRow({ SUBJECT: 'Payment gateway timeout' })],
        loading: false,
        page: 0,
        size: 25,
        totalElements: 1,
      },
    })

    const activator = wrapper.find('[data-testid="incident-row-select"]')
    const accessibleName = activator.attributes('aria-label') ?? activator.text()
    expect(accessibleName).toContain('Payment gateway timeout')
  })
})

describe('renders a subject containing markup as literal text', () => {
  it('escapes an XSS-shaped subject instead of executing it', () => {
    const wrapper = mount(IncidentTable, {
      props: {
        rows: [makeRow({ SUBJECT: '<img src=x onerror=alert(1)>' })],
        loading: false,
        page: 0,
        size: 25,
        totalElements: 1,
      },
    })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
  })
})

/**
 * The phone presentation, and the reason it exists.
 *
 * The desktop grid used to render as-is at 390px: a 25-row page measured 5535px tall against
 * 2101px on desktop, subjects wrapped to four-to-six lines, and everything right of Status was
 * reachable only by swiping sideways inside the table. Both trees are always in the DOM (CSS
 * decides which one is displayed), so these assert on the card tree by its own test ids.
 */
describe('mobile presentation', () => {
  function mountTable(rows: IncidentRow[]) {
    return mount(IncidentTable, {
      props: { rows, page: 0, size: 25, totalElements: rows.length },
    })
  }

  it('renders one card per incident alongside the table, each opening the same drawer', async () => {
    const rows = [makeRow({ NO_INCIDENT: 11 }), makeRow({ NO_INCIDENT: 12, SUBJECT: 'VPN drop' })]
    const wrapper = mountTable(rows)

    const cards = wrapper.findAll('[data-testid="incident-card"]')
    expect(cards).toHaveLength(2)

    await wrapper.findAll('[data-testid="incident-card-select"]')[1]!.trigger('click')
    expect(wrapper.emitted('select')![0]![0]).toMatchObject({ NO_INCIDENT: 12 })
  })

  it('keeps the card tree hidden from md up and the table hidden below it', () => {
    const wrapper = mountTable([makeRow()])

    // The switch is a class contract, not a JS branch — assert the classes, since jsdom has no
    // layout and cannot tell us which one a real viewport would paint.
    expect(wrapper.get('[data-testid="incident-card-list"]').classes()).toContain('md:hidden')
    // The grid sits in a scroll container, inside the wrapper that carries the breakpoint.
    const table = wrapper.get('table').element.parentElement!.parentElement!
    expect(table.className).toContain('hidden')
    expect(table.className).toContain('md:block')
  })

  it('makes the wide grid a labelled, keyboard-focusable scroll region', () => {
    const scroller = mountTable([makeRow()]).get('[data-testid="incident-table-scroller"]')

    // Without tabindex a keyboard user cannot scroll to the columns that sit off-screen.
    expect(scroller.attributes('tabindex')).toBe('0')
    expect(scroller.attributes('role')).toBe('region')
    expect(scroller.attributes('aria-label')).toBe(
      'Incident table. Scroll sideways for more columns.',
    )
    expect(scroller.classes()).toContain('overflow-x-auto')
  })

  it('shows the scroll hint and edge fade only while columns are off the right edge', async () => {
    const wrapper = mountTable([makeRow()])
    expect(wrapper.find('[data-testid="incident-table-scroll-hint"]').exists()).toBe(false)

    const el = wrapper.get('[data-testid="incident-table-scroller"]').element as HTMLElement
    Object.defineProperty(el, 'clientWidth', { configurable: true, value: 500 })
    Object.defineProperty(el, 'scrollWidth', { configurable: true, value: 900 })
    await wrapper.get('[data-testid="incident-table-scroller"]').trigger('scroll')

    expect(wrapper.get('[data-testid="incident-table-scroll-hint"]').text()).toContain(
      'Scroll for more columns',
    )
    expect(wrapper.find('[data-testid="incident-table-scroll-fade"]').exists()).toBe(true)

    // Scrolled to the end: the cue goes away.
    el.scrollLeft = 400
    await wrapper.get('[data-testid="incident-table-scroller"]').trigger('scroll')
    expect(wrapper.find('[data-testid="incident-table-scroll-hint"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="incident-table-scroll-fade"]').exists()).toBe(false)
  })

  it('lets an unbroken subject token wrap instead of blowing the column out', () => {
    // Regression: a real Redmine subject carrying a long machine token widened the subject
    // column by 104px and put the entire PAGE into horizontal scroll. Caught in a live
    // 1440x900 walkthrough, not by any test, because jsdom has no layout — so what is pinned
    // here is the class contract that makes the browser shrink it.
    //
    // Both classes matter. Without `min-w-0` a flex item keeps `min-width: auto` and refuses
    // to shrink below min-content; without `overflow-wrap: anywhere` the min-content width
    // itself never comes down (`break-word` would NOT be enough — it wraps without shrinking
    // the intrinsic measurement the column algorithm reads).
    const wrapper = mountTable([
      makeRow({ SUBJECT: 'DisbursementLMR3/QR_STATIC_PAYMENT_NEW/QRPayment06/BAC04V3S6U00' }),
    ])

    const subject = wrapper
      .get('[data-testid="incident-row-select"]')
      .findAll('span')
      .find((span) => span.text().includes('DisbursementLMR3'))!
    expect(subject.classes()).toContain('min-w-0')
    expect(subject.classes()).toContain('[overflow-wrap:anywhere]')
  })

  it('gives the card tree its own test ids so no locator is ambiguous across viewports', () => {
    const wrapper = mountTable([makeRow()])

    // Both trees are in the DOM at every width. Sharing ids would make every getByTestId in the
    // e2e suite resolve two elements and fail strict mode.
    expect(wrapper.findAll('[data-testid="incident-row-select"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="incident-card-select"]')).toHaveLength(1)
  })
})

/**
 * "14" is not a judgement. It means one thing against a 60-day Low SLA and something else
 * against a 3-day Critical, and the budget was sitting unread in the `SLA` column.
 */
describe('aging is rendered against its own SLA budget', () => {
  function agingText(row: Partial<IncidentRow>) {
    const wrapper = mount(IncidentTable, {
      props: { rows: [makeRow(row)], page: 0, size: 25, totalElements: 1 },
    })
    return wrapper.get('[data-testid="incident-aging"]').text().trim()
  }

  it('prints elapsed over budget when both are present', () => {
    expect(agingText({ AGING_NEW: 14, SLA: 60 })).toBe('14 / 60')
  })

  it('falls back to the bare elapsed count when the budget is missing', () => {
    expect(agingText({ AGING_NEW: 14, SLA: null })).toBe('14')
  })

  /**
   * `AGING_NEW` is NULL for the ~8% of rows whose `close_date` is the empty string. That cohort
   * gets an em dash, never a fabricated 0 — the view preserves the NULL-vs-'' distinction
   * deliberately and the table must not collapse it.
   */
  it('shows an em dash rather than inventing a zero when elapsed is unknown', () => {
    expect(agingText({ AGING_NEW: null, SLA: 60 })).toBe('—')
  })

  it('describes the pair in words for assistive technology', () => {
    const wrapper = mount(IncidentTable, {
      props: { rows: [makeRow({ AGING_NEW: 14, SLA: 60 })], page: 0, size: 25, totalElements: 1 },
    })
    // "14 / 60" announces as two unrelated numbers without this.
    expect(wrapper.get('[data-testid="incident-aging"]').attributes('aria-label')).toBe(
      '14 days of a 60 day SLA',
    )
  })
})

/**
 * The ticket number is the first thing an operator says out loud or pastes into Redmine, and
 * the table had no column for it (stakeholder notes, US5).
 */
describe('No Redmine column', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  function mountRows(rows: IncidentRow[]) {
    return mount(IncidentTable, {
      props: { rows, page: 0, size: 25, totalElements: rows.length },
    })
  }

  it('no redmine column links to redmine', () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', 'https://redmine.invalid/')
    const wrapper = mountRows([makeRow({ NO_INCIDENT: 4021 })])

    const headers = wrapper.findAll('thead th')
    expect(headers[0]!.attributes('data-testid')).toBe('col-no-redmine')
    expect(headers[0]!.text()).toContain('No Redmine')

    const cells = wrapper.findAll('tbody tr')[0]!.findAll('td')
    expect(cells[0]!.attributes('data-testid')).toBe('incident-no-redmine')
    const link = cells[0]!.get('[data-testid="incident-redmine-link"]')
    expect(link.element.tagName).toBe('A')
    // Trailing slash on the base is normalised away, not doubled.
    expect(link.attributes('href')).toBe('https://redmine.invalid/issues/4021')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(link.text()).toBe('4021')
    expect(link.attributes('aria-label')).toBe('Open #4021 in Redmine')
  })

  it('plain number without base url', () => {
    vi.stubEnv('VITE_REDMINE_BASE_URL', '')
    const wrapper = mountRows([makeRow({ NO_INCIDENT: 4021 })])

    const cell = wrapper.get('[data-testid="incident-no-redmine"]')
    expect(cell.find('a').exists()).toBe(false)
    expect(cell.get('[data-testid="incident-redmine-number"]').text()).toBe('4021')
    expect(wrapper.find('[data-testid="incident-redmine-link"]').exists()).toBe(false)

    // Mobile card header carries the number too (AC5.2) — as text, inside the card's button.
    expect(wrapper.get('[data-testid="incident-card-number"]').text()).toBe('#4021')
  })

  it('shows the SLA, Aging and No Redmine header definitions', () => {
    const wrapper = mountRows([makeRow()])

    const terms = wrapper
      .findAll('thead [data-testid="info-hint"]')
      .map((hint) => hint.attributes('data-term'))
    expect(terms).toEqual(expect.arrayContaining(['noRedmine', 'slaColumn', 'aging']))
  })

  it('offers Clear filters in the empty state when filters are active', async () => {
    const filters = useFiltersStore()
    filters.sla = 'BREACHED'
    const wrapper = mountRows([])

    expect(wrapper.text()).toContain('No incidents match these filters')
    const clear = wrapper.get('[data-testid="incident-table-clear-filters"]')
    expect(clear.text()).toBe('Clear filters')
    await clear.trigger('click')
    expect(filters.sla).toBe(null)
  })
})
