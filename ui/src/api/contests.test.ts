import { describe, test, expect, vi, beforeEach } from 'vitest'
import { listContests, createContest, updateContest } from './contests'
import type { Contest } from './contests'

const mockContest: Contest = { id: 1, name: 'WLM', year: 2024, country: 'Ukraine' }

function mockFetch(body: unknown, ok = true, status = 200) {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok,
    status,
    json: () => Promise.resolve(body)
  })
}

beforeEach(() => { vi.restoreAllMocks() })

describe('listContests', () => {
  test('calls GET /api/contests and returns JSON', async () => {
    mockFetch([mockContest])
    const result = await listContests()
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/contests', undefined)
    expect(result).toEqual([mockContest])
  })

  test('rejects on non-2xx response', async () => {
    mockFetch({}, false, 500)
    await expect(listContests()).rejects.toThrow('HTTP 500')
  })
})

describe('createContest', () => {
  test('calls POST /api/contests with JSON body and returns created contest', async () => {
    mockFetch(mockContest)
    const input = { name: 'WLM', year: 2024, country: 'Ukraine' }
    const result = await createContest(input)
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/contests', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    }))
    expect(result).toEqual(mockContest)
  })

  test('rejects on non-2xx response', async () => {
    mockFetch({}, false, 400)
    await expect(createContest({ name: 'x', year: 2024, country: 'UA' })).rejects.toThrow('HTTP 400')
  })
})

describe('updateContest', () => {
  test('calls PUT /api/contests with JSON body', async () => {
    mockFetch(null)
    await updateContest(mockContest)
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/contests', expect.objectContaining({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(mockContest)
    }))
  })

  test('rejects on non-2xx response', async () => {
    mockFetch({}, false, 500)
    await expect(updateContest(mockContest)).rejects.toThrow('HTTP 500')
  })
})
