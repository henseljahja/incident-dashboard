import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { MatrixCell } from '@/apis/metrics'
import { i18n } from '@/i18n'
import { BRAND_PRIMARY } from '@/utils/severity'

import ImpactUrgencyHeatmap from '../ImpactUrgencyHeatmap.vue'

function cells(overrides: MatrixCell[] = []): MatrixCell[] {
  return [
    { impact: 'Critical', urgency: 'High', count: 5 },
    { impact: 'High', urgency: 'Medium', count: 3 },
    { impact: 'Unclassified', urgency: 'Unclassified', count: 2 },
    { impact: 'Medium', urgency: 'Unknown (99)', count: 1 },
    ...overrides,
  ]
}

describe('ImpactUrgencyHeatmap', () => {
  it('renders Unclassified and Unknown as real cells', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells() } })

    expect(wrapper.text()).toContain('Unclassified')
    expect(wrapper.text()).toContain('Unknown (99)')
  })

  it('accounts for every incident in the totals — no unclassified row is dropped', () => {
    const data = cells()
    const reportedTotal = data.reduce((sum, cell) => sum + cell.count, 0)

    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: data } })

    const renderedCells = wrapper.findAll('[data-testid="heatmap-cell"]')
    const renderedTotal = renderedCells.reduce((sum, cell) => {
      const count = Number(cell.attributes('data-count'))
      return sum + count
    }, 0)

    expect(renderedTotal).toBe(reportedTotal)
    // The grand total must also be visible on screen, not just internally summed.
    expect(wrapper.get('[data-testid="heatmap-total"]').text()).toContain(String(reportedTotal))
  })

  it('emits a drill-through with the clicked cell coordinates', async () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells() } })

    const target = wrapper
      .findAll('[data-testid="heatmap-cell"]')
      .find(
        (cell) =>
          cell.attributes('data-impact') === 'Critical' &&
          cell.attributes('data-urgency') === 'High',
      )
    expect(target).toBeTruthy()

    await target!.trigger('click')

    expect(wrapper.emitted('drill')).toBeTruthy()
    expect(wrapper.emitted('drill')![0]).toEqual([
      { impact: 'Critical', urgency: 'High', count: 5 },
    ])
  })

  /**
   * The regression this locks: `axisRank` used to return 0 for every real severity label, so
   * both axes fell through to `localeCompare` and rendered **High, Low, Medium**. A matrix
   * communicates by position — worst in one corner, severity falling along the diagonal — and
   * an alphabetical axis has no corner and no diagonal. Asserting the rendered order, not the
   * comparator, so a future rewrite of the sort cannot pass while the chart lies.
   */
  it('orders both axes by severity, worst first — never alphabetically', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, {
      props: {
        cells: [
          { impact: 'Low', urgency: 'Low', count: 1 },
          { impact: 'High', urgency: 'High', count: 2 },
          { impact: 'Medium', urgency: 'Medium', count: 3 },
          { impact: 'Critical', urgency: 'Critical', count: 4 },
        ],
      },
    })

    expect(columnAxis(wrapper)).toEqual(['Critical', 'High', 'Medium', 'Low'])
    expect(rowAxis(wrapper)).toEqual(['Critical', 'High', 'Medium', 'Low'])
  })

  it('sorts the data-quality sentinels last, behind every real severity', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, {
      props: {
        cells: [
          { impact: 'Unclassified', urgency: 'Low', count: 1 },
          { impact: 'Low', urgency: 'Low', count: 1 },
          { impact: 'Unknown (99)', urgency: 'Low', count: 1 },
          { impact: 'Critical', urgency: 'Low', count: 1 },
        ],
      },
    })

    expect(columnAxis(wrapper)).toEqual(['Critical', 'Low', 'Unclassified', 'Unknown (99)'])
  })

  /**
   * An operator can add a priority to Redmine's `enumerations` table at any time. A label the
   * display order does not know about must stay visible and must not be mistaken for a
   * data-quality sentinel — it is real data, just newer than this constant.
   */
  it('places an unrecognised severity after the known ones but before the sentinels', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, {
      props: {
        cells: [
          { impact: 'Unclassified', urgency: 'Low', count: 1 },
          { impact: 'Blocker', urgency: 'Low', count: 1 },
          { impact: 'Critical', urgency: 'Low', count: 1 },
        ],
      },
    })

    expect(columnAxis(wrapper)).toEqual(['Critical', 'Blocker', 'Unclassified'])
  })

  it('never paints a cell with the brand primary colour', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells() } })

    const renderedCells = wrapper.findAll('[data-testid="heatmap-cell"]')
    expect(renderedCells.length).toBeGreaterThan(0)
    for (const cell of renderedCells) {
      const backgroundColor = (cell.element as HTMLElement).style.backgroundColor
      expect(backgroundColor.toLowerCase()).not.toBe(hexToRgb(BRAND_PRIMARY))
    }
  })
})

function hexToRgb(hex: string): string {
  const value = hex.replace('#', '')
  const r = parseInt(value.slice(0, 2), 16)
  const g = parseInt(value.slice(2, 4), 16)
  const b = parseInt(value.slice(4, 6), 16)
  return `rgb(${r}, ${g}, ${b})`
}

function columnAxis(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper
    .findAll('thead th[scope="col"]')
    .map((th) => th.text().trim())
    .filter((label) => label.length > 0)
}

function rowAxis(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAll('tbody th[scope="row"]').map((th) => th.text().trim())
}

describe('ImpactUrgencyHeatmap missing impact/urgency link', () => {
  it('shows the count as a button and emits show-missing when pressed', async () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells(), missingCount: 7 } })

    const link = wrapper.get('[data-testid="heatmap-missing-link"]')
    expect(link.element.tagName).toBe('BUTTON')
    expect(link.text()).toBe('7 tickets missing impact or urgency')
    // It has its own ⓘ, not nested inside the button.
    const hint = wrapper.get('[data-testid="heatmap-missing"] [data-testid="info-hint"]')
    expect(hint.attributes('data-term')).toBe('matrixMissing')
    expect(hint.element.closest('button[data-testid="heatmap-missing-link"]')).toBeNull()

    await link.trigger('click')
    expect(wrapper.emitted('show-missing')).toHaveLength(1)
  })

  it('uses the singular for one ticket', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells(), missingCount: 1 } })

    expect(wrapper.get('[data-testid="heatmap-missing-link"]').text()).toBe(
      '1 ticket missing impact or urgency',
    )
  })

  it('renders no link when nothing is missing', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells(), missingCount: 0 } })

    expect(wrapper.find('[data-testid="heatmap-missing"]').exists()).toBe(false)
  })

  it('is 44px tall and says it in Indonesian', async () => {
    i18n.global.locale.value = 'id'
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells(), missingCount: 2 } })

    const link = wrapper.get('[data-testid="heatmap-missing-link"]')
    expect(link.classes()).toContain('min-h-11')
    expect(link.text()).toBe('2 tiket dampak atau urgensinya kosong')
  })
})

describe('ImpactUrgencyHeatmap scroll cue', () => {
  it('is a labelled, focusable scroll region that shows a hint and fade only while it overflows', async () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells() } })
    const scroller = wrapper.get('[data-testid="heatmap-scroller"]')

    expect(scroller.attributes('tabindex')).toBe('0')
    expect(scroller.attributes('role')).toBe('region')
    expect(scroller.attributes('aria-label')).toContain('Scroll sideways')
    expect(wrapper.find('[data-testid="heatmap-scroll-hint"]').exists()).toBe(false)

    const el = scroller.element as HTMLElement
    Object.defineProperty(el, 'clientWidth', { configurable: true, value: 300 })
    Object.defineProperty(el, 'scrollWidth', { configurable: true, value: 500 })
    await scroller.trigger('scroll')
    expect(wrapper.find('[data-testid="heatmap-scroll-hint"]').exists()).toBe(true)
    // No edge fade: it would sit on the last SPO column when that column fills the scroller.
    expect(wrapper.find('[data-testid="heatmap-scroll-fade"]').exists()).toBe(false)

    el.scrollLeft = 200
    await scroller.trigger('scroll')
    expect(wrapper.find('[data-testid="heatmap-scroll-hint"]').exists()).toBe(false)
  })
})

/** The SPO matrix: fixed axes, priority tint plus word, unknown labels last, legend. */
describe('ImpactUrgencyHeatmap SPO matrix', () => {
  const IMPACT = ['Massive', 'Large', 'Limited', 'Localized']
  const URGENCY = ['Urgent', 'High', 'Medium', 'Low']

  function spoCells(): MatrixCell[] {
    return [
      { impact: 'Massive', urgency: 'Urgent', count: 3, priority: 'Critical' },
      { impact: 'Large', urgency: 'High', count: 2, priority: 'High' },
      { impact: 'Limited', urgency: 'Medium', count: 5, priority: 'Medium' },
      { impact: 'Localized', urgency: 'Low', count: 7, priority: 'Low' },
      { impact: 'Unclassified', urgency: 'Unclassified', count: 4, priority: null },
      { impact: 'Unknown (9)', urgency: 'Medium', count: 1, priority: null },
    ]
  }

  function mountSpo(cellList: MatrixCell[] = spoCells()) {
    return mount(ImpactUrgencyHeatmap, {
      props: { cells: cellList, impactOrder: IMPACT, urgencyOrder: URGENCY },
    })
  }

  const cell = (wrapper: ReturnType<typeof mount>, impact: string, urgency: string) =>
    wrapper.get(`[data-testid="heatmap-cell"][data-impact="${impact}"][data-urgency="${urgency}"]`)

  it('draws the SPO axes in the server order, and the unclassified labels after them', () => {
    const wrapper = mountSpo()

    expect(columnAxis(wrapper)).toEqual([...IMPACT, 'Unclassified', 'Unknown (9)'])
    expect(rowAxis(wrapper)).toEqual([...URGENCY, 'Unclassified'])
  })

  it('draws every SPO combination, with a quiet 0 where there are no tickets', () => {
    const wrapper = mountSpo([
      { impact: 'Massive', urgency: 'Urgent', count: 3, priority: 'Critical' },
    ])

    expect(columnAxis(wrapper)).toEqual(IMPACT)
    expect(wrapper.findAll('[data-testid="heatmap-cell"]')).toHaveLength(16)
    const empty = cell(wrapper, 'Large', 'Low')
    expect(empty.text()).toBe('0')
    expect(empty.classes()).toContain('text-surface-400')
    // No priority word on an empty cell.
    expect(empty.find('[data-testid="heatmap-cell-priority"]').exists()).toBe(false)
  })

  it('tints each cell by its priority and names the priority, so colour is not the only signal', () => {
    const wrapper = mountSpo()

    const expected: [string, string, string, string][] = [
      ['Massive', 'Urgent', 'Critical', '--chart-error-text'],
      ['Large', 'High', 'High', '--sla-risk'],
      ['Limited', 'Medium', 'Medium', '--sla-warn'],
      ['Localized', 'Low', 'Low', '--sla-ok'],
    ]
    for (const [impact, urgency, word, token] of expected) {
      const c = cell(wrapper, impact, urgency)
      expect(c.attributes('data-priority')).toBe(word)
      expect(c.get('[data-testid="heatmap-cell-priority"]').text()).toBe(word)
      expect(c.classes().join(' ')).toContain(token)
      expect(c.attributes('aria-label')).toContain(`priority ${word}`)
    }
    // The count is the prominent number.
    expect(cell(wrapper, 'Massive', 'Urgent').get('span').classes()).toContain('text-lg')
  })

  it('keeps Unclassified and Unknown neutral: no priority tint, no priority word', () => {
    const wrapper = mountSpo()

    for (const [impact, urgency] of [
      ['Unclassified', 'Unclassified'],
      ['Unknown (9)', 'Medium'],
    ] as const) {
      const c = cell(wrapper, impact, urgency)
      expect(c.classes()).toContain('bg-surface-100')
      expect(c.find('[data-testid="heatmap-cell-priority"]').exists()).toBe(false)
    }
  })

  it('separates the neutral group from the SPO grid', () => {
    const wrapper = mountSpo()

    const headers = wrapper.findAll('thead th[scope="col"]')
    expect(headers[3]!.classes()).not.toContain('border-l')
    expect(headers[4]!.classes()).toContain('border-l')
    expect(wrapper.findAll('tbody th[scope="row"]')[4]!.classes()).toContain('border-t')
  })

  it('shows the axis titles and a legend of the four priorities', () => {
    const wrapper = mountSpo()

    expect(wrapper.get('[data-testid="heatmap-axis-impact"]').text()).toBe('Impact')
    expect(wrapper.get('[data-testid="heatmap-axis-urgency"]').text()).toBe('Urgency')
    expect(wrapper.findAll('[data-testid="heatmap-legend"] li').map((li) => li.text())).toEqual([
      'Critical',
      'High',
      'Medium',
      'Low',
    ])
  })

  it('says it in Indonesian', () => {
    i18n.global.locale.value = 'id'
    const wrapper = mountSpo()

    expect(wrapper.get('[data-testid="heatmap-axis-impact"]').text()).toBe('Dampak')
    expect(wrapper.get('[data-testid="heatmap-axis-urgency"]').text()).toBe('Urgensi')
    expect(wrapper.findAll('[data-testid="heatmap-legend"] li').map((li) => li.text())).toEqual([
      'Kritis',
      'Tinggi',
      'Sedang',
      'Rendah',
    ])
  })

  it('keeps the drill-through payload unchanged, also for an empty cell', async () => {
    const wrapper = mountSpo()

    await cell(wrapper, 'Massive', 'Urgent').trigger('click')
    await cell(wrapper, 'Large', 'Low').trigger('click')

    expect(wrapper.emitted('drill')).toEqual([
      [{ impact: 'Massive', urgency: 'Urgent', count: 3 }],
      [{ impact: 'Large', urgency: 'Low', count: 0 }],
    ])
  })

  it('has no legend and no SPO tint on an older server that sends no axes', () => {
    const wrapper = mount(ImpactUrgencyHeatmap, { props: { cells: cells() } })

    expect(wrapper.find('[data-testid="heatmap-legend"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="heatmap-cell-priority"]').exists()).toBe(false)
  })

  it('keeps the matrix scroll cue, and sizes the grid so the four SPO columns share the scroller', () => {
    const wrapper = mountSpo()

    expect(wrapper.find('[data-testid="heatmap-scroller"]').exists()).toBe(true)
    // Cells flex into their track instead of holding a fixed width that overflowed at 375px.
    const classes = cell(wrapper, 'Massive', 'Urgent').classes()
    expect(classes).toContain('w-full')
    expect(classes).toContain('min-w-0')
    expect(classes.some((c) => /^(sm:)?w-(14|16)$/.test(c))).toBe(false)

    // Row header, four shrinkable SPO tracks, then the two neutral columns that may scroll.
    const style = wrapper.get('table').attributes('style')
    expect(style).toContain(
      'grid-template-columns: 5rem repeat(4, minmax(0, 4.5rem)) repeat(2, 5rem)',
    )
    // The width (`min(100% + neutral columns, tracks needed)`) uses CSS `min()`, which jsdom's style
    // parser drops, so it is checked in the browser (e2e/spo-matrix.spec.ts), not here.
  })

  it('declares no neutral columns when every label is an SPO one', () => {
    const wrapper = mountSpo([
      { impact: 'Massive', urgency: 'Urgent', count: 1, priority: 'Critical' },
    ])

    expect(wrapper.get('table').attributes('style')).toContain(
      'grid-template-columns: 5rem repeat(4, minmax(0, 4.5rem));',
    )
  })

  it('keeps table semantics for assistive tech though it is laid out as a grid', () => {
    const wrapper = mountSpo()

    expect(wrapper.get('table').attributes('role')).toBe('table')
    expect(wrapper.findAll('thead th[scope="col"]')[0]!.attributes('role')).toBe('columnheader')
    expect(wrapper.findAll('tbody th[scope="row"]')[0]!.attributes('role')).toBe('rowheader')
    expect(wrapper.findAll('tbody tr')[0]!.attributes('role')).toBe('row')
  })
})
