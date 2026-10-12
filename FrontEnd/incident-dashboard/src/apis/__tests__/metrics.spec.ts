import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../client', () => ({ apiRequest: vi.fn<(path: string) => Promise<unknown>>() }))

import { apiRequest } from '../client'
import { fetchScopeCounts, fetchWallboard, normalizeMatrix } from '../metrics'

beforeEach(() => {
  vi.mocked(apiRequest).mockReset()
  vi.mocked(apiRequest).mockResolvedValue({})
})

describe('normalizeMatrix', () => {
  it('reads the object form with its missing count', () => {
    const cell = { impact: 'High', urgency: 'Low', count: 2 }
    expect(normalizeMatrix({ cells: [cell], missingCount: 5 })).toEqual({
      cells: [cell],
      missingCount: 5,
      impactOrder: [],
      urgencyOrder: [],
    })
  })

  it('treats the older bare array as "no missing count reported"', () => {
    const cell = { impact: 'High', urgency: 'Low', count: 2 }
    expect(normalizeMatrix([cell])).toEqual({
      cells: [cell],
      missingCount: 0,
      impactOrder: [],
      urgencyOrder: [],
    })
  })

  it('survives nothing at all', () => {
    const empty = { cells: [], missingCount: 0, impactOrder: [], urgencyOrder: [] }
    expect(normalizeMatrix(undefined)).toEqual(empty)
    expect(normalizeMatrix({})).toEqual(empty)
  })
})

describe('normalizeMatrix, SPO shape', () => {
  it('carries the priority on a cell and the SPO axis orders', () => {
    const raw = {
      cells: [
        { impact: 'Massive', urgency: 'Urgent', count: 3, priority: 'Critical' },
        { impact: 'Unclassified', urgency: 'Low', count: 1, priority: null },
      ],
      missingCount: 1,
      impactOrder: ['Massive', 'Large', 'Limited', 'Localized'],
      urgencyOrder: ['Urgent', 'High', 'Medium', 'Low'],
    }

    expect(normalizeMatrix(raw)).toEqual(raw)
  })

  it('still reads a response that has orders but a missing count', () => {
    const result = normalizeMatrix({ cells: [], impactOrder: ['Massive'] })

    expect(result.impactOrder).toEqual(['Massive'])
    expect(result.urgencyOrder).toEqual([])
    expect(result.missingCount).toBe(0)
  })
})

describe('fetchScopeCounts', () => {
  it('sends the date window and nothing else', async () => {
    await fetchScopeCounts(
      new URLSearchParams('dateFrom=2026-01-01&dateTo=2026-02-01&scope=PROBLEM&tracker=Incident'),
    )

    expect(apiRequest).toHaveBeenCalledWith(
      '/metrics/scope-counts?dateFrom=2026-01-01&dateTo=2026-02-01',
    )
  })

  it('sends a bare request when no window is set', async () => {
    await fetchScopeCounts()

    expect(apiRequest).toHaveBeenCalledWith('/metrics/scope-counts')
  })
})

describe('fetchWallboard', () => {
  it('takes no parameters: the server fixes scope and window', async () => {
    await fetchWallboard()

    expect(apiRequest).toHaveBeenCalledWith('/metrics/wallboard')
  })
})
