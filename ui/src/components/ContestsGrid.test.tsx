import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ContestsGrid, { FilterChips } from './ContestsGrid'
import * as api from '../api/contests'
import type { Contest } from '../api/contests'

vi.mock('../api/contests')

const contest1: Contest = { id: 1, name: 'WLM Ukraine', year: 2024, country: 'Ukraine' }
const contest2: Contest = { id: 2, name: 'WLE Poland', year: 2024, country: 'Poland' }

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.listContests).mockResolvedValue([contest1, contest2])
  vi.mocked(api.createContest).mockResolvedValue({ ...contest1, id: 3 })
  vi.mocked(api.updateContest).mockResolvedValue(undefined)
})

test('renders contest rows after loading', async () => {
  render(<ContestsGrid />)
  expect(await screen.findByText('WLM Ukraine')).toBeInTheDocument()
  expect(screen.getByText('WLE Poland')).toBeInTheDocument()
})

test('calls listContests on mount', async () => {
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')
  expect(api.listContests).toHaveBeenCalledTimes(1)
})

test('shows error banner when listContests rejects', async () => {
  vi.mocked(api.listContests).mockRejectedValue(new Error('network'))
  render(<ContestsGrid />)
  expect(await screen.findByText(/failed to load/i)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
})

test('retry button re-fetches contests', async () => {
  vi.mocked(api.listContests)
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValueOnce([contest1])
  render(<ContestsGrid />)
  await screen.findByText(/failed to load/i)
  fireEvent.click(screen.getByRole('button', { name: /retry/i }))
  expect(await screen.findByText('WLM Ukraine')).toBeInTheDocument()
})

test('"Add contest" button is present', async () => {
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')
  expect(screen.getByRole('button', { name: /add contest/i })).toBeInTheDocument()
})

test('clicking "Add contest" adds an editable new row', async () => {
  const user = userEvent.setup()
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')
  await user.click(screen.getByRole('button', { name: /add contest/i }))
  // New row in edit mode — name field is an input
  const inputs = screen.getAllByRole('textbox')
  expect(inputs.length).toBeGreaterThan(0)
})

test('saving a new row calls createContest and re-fetches', async () => {
  const user = userEvent.setup()
  vi.mocked(api.listContests)
    .mockResolvedValueOnce([contest1])
    .mockResolvedValueOnce([contest1, { id: 3, name: 'New Contest', year: 2025, country: 'Germany' }])
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')

  await user.click(screen.getByRole('button', { name: /add contest/i }))

  // Fill in required fields
  const inputs = screen.getAllByRole('textbox')
  await user.clear(inputs[0])
  await user.type(inputs[0], 'New Contest')
  // year field (number input)
  const yearInput = screen.getByDisplayValue(new Date().getFullYear().toString())
  await user.clear(yearInput)
  await user.type(yearInput, '2025')
  // country
  const countryInput = inputs[1]
  await user.clear(countryInput)
  await user.type(countryInput, 'Germany')

  await user.click(screen.getByRole('menuitem', { name: /save/i, hidden: true }))

  await waitFor(() => expect(api.createContest).toHaveBeenCalledWith(
    expect.objectContaining({ name: 'New Contest', year: 2025, country: 'Germany' })
  ))
  expect(api.listContests).toHaveBeenCalledTimes(2)
})

test('clicking Edit on a row enters edit mode', async () => {
  const user = userEvent.setup()
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')

  const editButtons = screen.getAllByRole('menuitem', { name: /edit/i, hidden: true })
  await user.click(editButtons[0])

  // Row is now in edit mode — inputs appear
  await waitFor(() => {
    expect(screen.getAllByRole('textbox').length).toBeGreaterThan(0)
  })
})

test('saving an edited row calls updateContest and re-fetches', async () => {
  const user = userEvent.setup()
  vi.mocked(api.listContests)
    .mockResolvedValueOnce([contest1])
    .mockResolvedValueOnce([{ ...contest1, name: 'WLM Ukraine Updated' }])
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')

  const editButtons = screen.getAllByRole('menuitem', { name: /edit/i, hidden: true })
  await user.click(editButtons[0])

  const nameInput = await screen.findByDisplayValue('WLM Ukraine')
  await user.clear(nameInput)
  await user.type(nameInput, 'WLM Ukraine Updated')

  await user.click(screen.getByRole('menuitem', { name: /save/i, hidden: true }))

  await waitFor(() => expect(api.updateContest).toHaveBeenCalledWith(
    expect.objectContaining({ id: 1, name: 'WLM Ukraine Updated' })
  ))
  expect(api.listContests).toHaveBeenCalledTimes(2)
})

test('cancelling a new row removes it without calling createContest', async () => {
  const user = userEvent.setup()
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')

  await user.click(screen.getByRole('button', { name: /add contest/i }))
  expect(screen.getAllByRole('textbox').length).toBeGreaterThan(0)

  await user.click(screen.getByRole('menuitem', { name: /cancel/i, hidden: true }))

  await waitFor(() => expect(screen.queryAllByRole('textbox').length).toBe(0))
  expect(api.createContest).not.toHaveBeenCalled()
})

test('cancelling an edit restores original values', async () => {
  const user = userEvent.setup()
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')

  const editButtons = screen.getAllByRole('menuitem', { name: /edit/i, hidden: true })
  await user.click(editButtons[0])

  const nameInput = await screen.findByDisplayValue('WLM Ukraine')
  await user.clear(nameInput)
  await user.type(nameInput, 'Changed Name')

  await user.click(screen.getByRole('menuitem', { name: /cancel/i, hidden: true }))

  await waitFor(() => expect(screen.getByText('WLM Ukraine')).toBeInTheDocument())
  expect(api.updateContest).not.toHaveBeenCalled()
})

test('saving with blank name shows error and does not call API', async () => {
  const user = userEvent.setup()
  render(<ContestsGrid />)
  await screen.findByText('WLM Ukraine')

  const editButtons = screen.getAllByRole('menuitem', { name: /edit/i, hidden: true })
  await user.click(editButtons[0])

  const nameInput = await screen.findByDisplayValue('WLM Ukraine')
  await user.clear(nameInput)

  await user.click(screen.getByRole('menuitem', { name: /save/i, hidden: true }))

  await waitFor(() => expect(screen.getByText(/name.*required|required.*name/i)).toBeInTheDocument())
  expect(api.updateContest).not.toHaveBeenCalled()
})

describe('FilterChips', () => {
  const noFilters = { name: null, year: null, country: null }

  test('renders nothing when all filters are null', () => {
    render(<FilterChips filters={noFilters} onRemove={() => {}} onClearAll={() => {}} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  test('renders a chip for each active filter', () => {
    render(
      <FilterChips
        filters={{ name: null, year: '2023', country: 'Ukraine' }}
        onRemove={() => {}}
        onClearAll={() => {}}
      />
    )
    expect(screen.getByText('Year: 2023')).toBeInTheDocument()
    expect(screen.getByText('Country: Ukraine')).toBeInTheDocument()
    expect(screen.queryByText(/Name:/)).not.toBeInTheDocument()
  })

  test('calls onRemove with the correct key when chip delete is clicked', async () => {
    const user = userEvent.setup()
    const onRemove = vi.fn()
    render(
      <FilterChips
        filters={{ name: null, year: '2023', country: null }}
        onRemove={onRemove}
        onClearAll={() => {}}
      />
    )
    await user.click(screen.getByTestId('remove-filter-year'))
    expect(onRemove).toHaveBeenCalledWith('year')
  })

  test('shows Clear all button when 2 or more filters are active', () => {
    render(
      <FilterChips
        filters={{ name: null, year: '2023', country: 'Ukraine' }}
        onRemove={() => {}}
        onClearAll={() => {}}
      />
    )
    expect(screen.getByRole('button', { name: /clear all/i })).toBeInTheDocument()
  })

  test('does not show Clear all when only 1 filter is active', () => {
    render(
      <FilterChips
        filters={{ name: null, year: '2023', country: null }}
        onRemove={() => {}}
        onClearAll={() => {}}
      />
    )
    expect(screen.queryByRole('button', { name: /clear all/i })).not.toBeInTheDocument()
  })

  test('calls onClearAll when Clear all is clicked', async () => {
    const user = userEvent.setup()
    const onClearAll = vi.fn()
    render(
      <FilterChips
        filters={{ name: 'WLM Ukraine', year: '2023', country: null }}
        onRemove={() => {}}
        onClearAll={onClearAll}
      />
    )
    await user.click(screen.getByRole('button', { name: /clear all/i }))
    expect(onClearAll).toHaveBeenCalled()
  })
})
