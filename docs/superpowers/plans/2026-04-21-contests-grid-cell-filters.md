# ContestsGrid Cell Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Name, Year, and Country cells in ContestsGrid clickable to filter rows, with removable filter chips above the grid and URL query-string persistence.

**Architecture:** Filter state lives in `ContestsGrid` as `Record<FilterKey, string | null>`, initialised from `window.location.search` on mount. A `filteredRows` derived value feeds the DataGrid. A `FilterChips` sub-component (named export from the same file) renders active filters as MUI Chips. A `useEffect` syncs filter state to the URL via `history.replaceState`.

**Tech Stack:** React 19, MUI v9 (`Chip`, `Box`, `Button`), MUI X DataGrid v9, Vitest + React Testing Library + `@testing-library/user-event`

---

## File Structure

Both tasks touch the same two files. No new files needed.

- **Modify:** `ui/src/components/ContestsGrid.tsx`
  - Add: `FilterKey`, `Filters` types
  - Add: `FILTER_LABELS` constant
  - Add: exported `FilterChips` component
  - Add: `filters` state (initialised from URL), `filteredRows` derived value
  - Add: URL sync `useEffect`
  - Add: `makeFilterCell` helper
  - Update: `name`, `year`, `country` column defs to use `renderCell`
  - Update: JSX — render `FilterChips` above the `DataGrid`
  - Update: pass `filteredRows` (not `rows`) to `DataGrid`

- **Modify:** `ui/src/components/ContestsGrid.test.tsx`
  - Update import to include `FilterChips` named export
  - Add: `describe('FilterChips')` unit tests
  - Add: URL persistence tests
  - Add: click-to-filter tests
  - Add: chip removal / clear-all integration tests

---

### Task 1: FilterChips component

Write and test the `FilterChips` sub-component in isolation before wiring it into `ContestsGrid`.

**Files:**
- Modify: `ui/src/components/ContestsGrid.tsx`
- Modify: `ui/src/components/ContestsGrid.test.tsx`

---

- [ ] **Step 1: Write failing FilterChips tests**

Add to `ui/src/components/ContestsGrid.test.tsx`. At the top, update the import and add the describe block. The named export `FilterChips` doesn't exist yet — the test file will fail to compile until Step 3.

Change the existing import line:
```tsx
import ContestsGrid from './ContestsGrid'
```
to:
```tsx
import ContestsGrid, { FilterChips } from './ContestsGrid'
```

Then add this describe block after the existing `beforeEach` block:

```tsx
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
```

---

- [ ] **Step 2: Run tests — expect compile failure**

```bash
cd ui && npm test
```

Expected: TypeScript compile error — `FilterChips` is not exported from `./ContestsGrid`.

---

- [ ] **Step 3: Implement FilterChips in ContestsGrid.tsx**

In `ui/src/components/ContestsGrid.tsx`, add after the existing imports and before the `Toolbar` function:

```tsx
// Add Chip to the MUI import:
import { Button, Alert, Box, Chip } from '@mui/material'
```

Then add these type definitions and the component just before the existing `Toolbar` function:

```tsx
export type FilterKey = 'name' | 'year' | 'country'
export type Filters = Record<FilterKey, string | null>

const FILTER_LABELS: Record<FilterKey, string> = {
  name: 'Name',
  year: 'Year',
  country: 'Country',
}

const FILTER_KEYS: FilterKey[] = ['name', 'year', 'country']

interface FilterChipsProps {
  filters: Filters
  onRemove: (key: FilterKey) => void
  onClearAll: () => void
}

export function FilterChips({ filters, onRemove, onClearAll }: FilterChipsProps) {
  const active = FILTER_KEYS.filter(k => filters[k] !== null)
  if (active.length === 0) return null
  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, px: 1, py: 0.5, bgcolor: 'grey.50' }}>
      <Box component="span" sx={{ fontSize: 12, color: 'text.secondary', fontWeight: 500 }}>Filters:</Box>
      {active.map(key => (
        <Chip
          key={key}
          label={`${FILTER_LABELS[key]}: ${filters[key]}`}
          onDelete={() => onRemove(key)}
          slotProps={{ deleteIcon: { 'data-testid': `remove-filter-${key}` } as React.HTMLAttributes<SVGSVGElement> }}
          size="small"
          color="primary"
        />
      ))}
      {active.length >= 2 && (
        <Button size="small" onClick={onClearAll} sx={{ fontSize: 11, minWidth: 0, px: 1 }}>
          Clear all
        </Button>
      )}
    </Box>
  )
}
```

Also add the `React` import (needed for the `slotProps` cast) — check the top of the file. The existing imports use named imports from React (`useState`, `useEffect`, `useCallback`). Add `import React from 'react'` or change to `import React, { useState, useEffect, useCallback } from 'react'`.

---

- [ ] **Step 4: Run tests — expect FilterChips tests to pass**

```bash
cd ui && npm test
```

Expected: all `describe('FilterChips')` tests pass. Existing tests still pass.

---

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/ContestsGrid.tsx ui/src/components/ContestsGrid.test.tsx
git commit -m "feat: add FilterChips component for ContestsGrid"
```

---

### Task 2: Filter state, filteredRows, URL sync

Add the `filters` state (read from URL on mount), `filteredRows` derived value, and URL sync effect to `ContestsGrid`. Pass `filteredRows` to the DataGrid. `FilterChips` is not yet wired to JSX — that comes in Task 4.

**Files:**
- Modify: `ui/src/components/ContestsGrid.tsx`
- Modify: `ui/src/components/ContestsGrid.test.tsx`

---

- [ ] **Step 1: Write failing URL persistence tests**

Add to `ui/src/components/ContestsGrid.test.tsx`, after the `describe('FilterChips')` block:

```tsx
describe('URL persistence', () => {
  afterEach(() => {
    // Reset URL after each test
    history.replaceState(null, '', '/')
  })

  test('reads f_country from URL on mount and filters rows', async () => {
    history.replaceState(null, '', '/?f_country=Ukraine')
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')
    expect(screen.queryByText('WLE Poland')).not.toBeInTheDocument()
  })

  test('reads f_year from URL on mount and filters rows', async () => {
    vi.mocked(api.listContests).mockResolvedValue([
      { id: 1, name: 'WLM Ukraine', year: 2024, country: 'Ukraine' },
      { id: 2, name: 'WLM Poland', year: 2022, country: 'Poland' },
    ])
    history.replaceState(null, '', '/?f_year=2022')
    render(<ContestsGrid />)
    await screen.findByText('WLM Poland')
    expect(screen.queryByText('WLM Ukraine')).not.toBeInTheDocument()
  })

  test('updates URL when filter is set programmatically', async () => {
    const replaceState = vi.spyOn(history, 'replaceState')
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')
    // Verify replaceState was called on mount (with empty filters — no f_ params)
    expect(replaceState).toHaveBeenCalled()
    const lastCall = replaceState.mock.calls[replaceState.mock.calls.length - 1]
    const url = String(lastCall[2])
    expect(url).not.toContain('f_country')
    replaceState.mockRestore()
  })
})
```

---

- [ ] **Step 2: Run tests — expect failures**

```bash
cd ui && npm test
```

Expected: the `reads f_country from URL on mount` test fails because `ContestsGrid` does not yet filter based on URL params (both rows are shown).

---

- [ ] **Step 3: Add filters state and filteredRows to ContestsGrid**

In `ui/src/components/ContestsGrid.tsx`, inside the `ContestsGrid` function, add the following after the existing `useState` declarations:

```tsx
const [filters, setFilters] = useState<Filters>(() => {
  const params = new URLSearchParams(window.location.search)
  return {
    name: params.get('f_name'),
    year: params.get('f_year'),
    country: params.get('f_country'),
  }
})
```

Then add the URL sync effect after the existing `useEffect(() => { fetchContests() }, ...)`:

```tsx
useEffect(() => {
  const params = new URLSearchParams(window.location.search)
  FILTER_KEYS.forEach(key => {
    if (filters[key] !== null) {
      params.set(`f_${key}`, filters[key]!)
    } else {
      params.delete(`f_${key}`)
    }
  })
  const search = params.toString()
  history.replaceState(null, '', search ? `?${search}` : window.location.pathname)
}, [filters])
```

Then add the derived `filteredRows` after the effects:

```tsx
const filteredRows = rows.filter(row =>
  (filters.name === null || row.name === filters.name) &&
  (filters.year === null || String(row.year) === filters.year) &&
  (filters.country === null || row.country === filters.country)
)
```

Finally, in the JSX, change `rows={rows}` to `rows={filteredRows}` on the `<DataGrid>` element.

---

- [ ] **Step 4: Run tests — expect URL tests to pass**

```bash
cd ui && npm test
```

Expected: all URL persistence tests pass. All existing tests still pass (they don't set URL params so `filteredRows === rows`).

---

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/ContestsGrid.tsx ui/src/components/ContestsGrid.test.tsx
git commit -m "feat: add filter state and URL persistence to ContestsGrid"
```

---

### Task 3: Clickable renderCell for Name, Year, Country

Add a `makeFilterCell` helper and apply `renderCell` to the three filterable columns.

**Files:**
- Modify: `ui/src/components/ContestsGrid.tsx`
- Modify: `ui/src/components/ContestsGrid.test.tsx`

---

- [ ] **Step 1: Write failing renderCell tests**

Add to `ui/src/components/ContestsGrid.test.tsx`, after the `describe('URL persistence')` block:

```tsx
describe('cell click filtering', () => {
  afterEach(() => {
    history.replaceState(null, '', '/')
  })

  test('clicking a country cell filters to that country', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    await user.click(screen.getByText('Ukraine'))

    expect(screen.getByText('WLM Ukraine')).toBeInTheDocument()
    expect(screen.queryByText('WLE Poland')).not.toBeInTheDocument()
  })

  test('clicking a name cell filters to that name', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    await user.click(screen.getByText('WLM Ukraine'))

    expect(screen.getByText('WLM Ukraine')).toBeInTheDocument()
    expect(screen.queryByText('WLE Poland')).not.toBeInTheDocument()
  })

  test('clicking a year cell filters to that year', async () => {
    const user = userEvent.setup()
    vi.mocked(api.listContests).mockResolvedValue([
      { id: 1, name: 'WLM Ukraine', year: 2024, country: 'Ukraine' },
      { id: 2, name: 'WLM Poland', year: 2022, country: 'Poland' },
    ])
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    // 2022 only appears on WLM Poland's row
    await user.click(screen.getByText('2022'))

    expect(screen.getByText('WLM Poland')).toBeInTheDocument()
    expect(screen.queryByText('WLM Ukraine')).not.toBeInTheDocument()
  })

  test('country cell shows plain text (no pointer cursor role) when country filter is active', async () => {
    history.replaceState(null, '', '/?f_country=Ukraine')
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    // The country cell value is present as text
    expect(screen.getByText('Ukraine')).toBeInTheDocument()
    // But it should not be inside a clickable span (role=button or cursor:pointer)
    const ukraineEl = screen.getByText('Ukraine')
    expect(ukraineEl.closest('[data-filter-link]')).toBeNull()
  })

  test('clicking a cell updates the URL', async () => {
    const user = userEvent.setup()
    const replaceState = vi.spyOn(history, 'replaceState')
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    await user.click(screen.getByText('Ukraine'))

    const calls = replaceState.mock.calls
    const lastUrl = String(calls[calls.length - 1][2])
    expect(lastUrl).toContain('f_country=Ukraine')
    replaceState.mockRestore()
  })
})
```

---

- [ ] **Step 2: Run tests — expect failures**

```bash
cd ui && npm test
```

Expected: `clicking a country cell filters to that country` and the other click tests fail because cells are not yet clickable.

---

- [ ] **Step 3: Add makeFilterCell and renderCell to column definitions**

In `ui/src/components/ContestsGrid.tsx`, add the `makeFilterCell` helper inside the `ContestsGrid` function body, just before the `columns` definition:

```tsx
const makeFilterCell = (key: FilterKey) =>
  (params: GridRenderCellParams) => {
    const value = String(params.value ?? '')
    if (filters[key] !== null) {
      return <span>{value}</span>
    }
    return (
      <Box
        component="span"
        data-filter-link="true"
        onClick={(e: React.MouseEvent) => {
          e.stopPropagation()
          setFilters(prev => ({ ...prev, [key]: value }))
        }}
        sx={{ cursor: 'pointer', color: 'primary.main', borderBottom: '1px dotted currentColor' }}
      >
        {value}
      </Box>
    )
  }
```

Also add `GridRenderCellParams` to the existing MUI X DataGrid import:

```tsx
import {
  DataGrid,
  GridColDef,
  GridRowId,
  GridRowModel,
  GridRowModes,
  GridRowModesModel,
  GridActionsCellItem,
  GridToolbarContainer,
  GridEventListener,
  GridRowEditStopReasons,
  GridRenderCellParams,
} from '@mui/x-data-grid'
```

Then update the three column definitions in the `columns` array to add `renderCell`:

```tsx
{ field: 'name', headerName: 'Name', flex: 1, minWidth: 120, maxWidth: 220, editable: true, renderCell: makeFilterCell('name') },
{ field: 'year', headerName: 'Year', width: 110, editable: true, renderCell: makeFilterCell('year') },
{ field: 'country', headerName: 'Country', flex: 0.8, minWidth: 90, editable: true, renderCell: makeFilterCell('country') },
```

---

- [ ] **Step 4: Run tests — expect click tests to pass**

```bash
cd ui && npm test
```

Expected: all `describe('cell click filtering')` tests pass. All prior tests still pass.

---

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/ContestsGrid.tsx ui/src/components/ContestsGrid.test.tsx
git commit -m "feat: add clickable cell filtering to ContestsGrid name/year/country columns"
```

---

### Task 4: Wire FilterChips into ContestsGrid JSX + integration tests

Render `FilterChips` above the `DataGrid`, wired to real filter state. Add integration tests for the full chip flow.

**Files:**
- Modify: `ui/src/components/ContestsGrid.tsx`
- Modify: `ui/src/components/ContestsGrid.test.tsx`

---

- [ ] **Step 1: Write failing integration tests**

Add to `ui/src/components/ContestsGrid.test.tsx`, after the `describe('cell click filtering')` block:

```tsx
describe('filter chip integration', () => {
  afterEach(() => {
    history.replaceState(null, '', '/')
  })

  test('clicking a country cell shows a filter chip', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    await user.click(screen.getByText('Ukraine'))

    expect(screen.getByText('Country: Ukraine')).toBeInTheDocument()
  })

  test('chip × button removes the filter and shows all rows again', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    await user.click(screen.getByText('Ukraine'))
    expect(screen.queryByText('WLE Poland')).not.toBeInTheDocument()

    await user.click(screen.getByTestId('remove-filter-country'))

    expect(await screen.findByText('WLE Poland')).toBeInTheDocument()
    expect(screen.queryByText('Country: Ukraine')).not.toBeInTheDocument()
  })

  test('Clear all removes all chips and shows all rows', async () => {
    const user = userEvent.setup()
    vi.mocked(api.listContests).mockResolvedValue([
      { id: 1, name: 'WLM Ukraine', year: 2024, country: 'Ukraine' },
      { id: 2, name: 'WLE Ukraine', year: 2024, country: 'Ukraine' },
      { id: 3, name: 'WLM Poland', year: 2022, country: 'Poland' },
    ])
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    // Apply two filters
    await user.click(screen.getAllByText('Ukraine')[0])    // country filter
    await screen.findByText('Country: Ukraine')
    // Now only Ukraine rows show — click a year to add second filter
    await user.click(screen.getAllByText('2024')[0])

    expect(screen.queryByText('WLM Poland')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /clear all/i })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /clear all/i }))

    expect(await screen.findByText('WLM Poland')).toBeInTheDocument()
    expect(screen.queryByText('Country: Ukraine')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /clear all/i })).not.toBeInTheDocument()
  })

  test('chip bar is hidden when no filters are active', async () => {
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')
    expect(screen.queryByText('Filters:')).not.toBeInTheDocument()
  })

  test('removing a chip updates the URL', async () => {
    const user = userEvent.setup()
    history.replaceState(null, '', '/?f_country=Ukraine')
    const replaceState = vi.spyOn(history, 'replaceState')
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    await user.click(screen.getByTestId('remove-filter-country'))

    const lastUrl = String(replaceState.mock.calls[replaceState.mock.calls.length - 1][2])
    expect(lastUrl).not.toContain('f_country')
    replaceState.mockRestore()
  })
})
```

---

- [ ] **Step 2: Run tests — expect chip integration tests to fail**

```bash
cd ui && npm test
```

Expected: the chip integration tests fail because `FilterChips` is not yet rendered inside `ContestsGrid`.

---

- [ ] **Step 3: Add FilterChips to ContestsGrid JSX**

In `ui/src/components/ContestsGrid.tsx`, update the `return` statement inside `ContestsGrid`. Add `FilterChips` above the `DataGrid`, and wire `setFilters` to `onRemove` and `onClearAll`:

```tsx
const emptyFilters: Filters = { name: null, year: null, country: null }

return (
  <Box sx={{ width: '100%' }}>
    {error && (
      <Alert
        severity="error"
        onClose={() => setError(null)}
        action={
          isLoadError ? (
            <Button color="inherit" size="small" onClick={fetchContests}>
              Retry
            </Button>
          ) : undefined
        }
        sx={{ mb: 1 }}
      >
        {error}
      </Alert>
    )}
    <FilterChips
      filters={filters}
      onRemove={key => setFilters(prev => ({ ...prev, [key]: null }))}
      onClearAll={() => setFilters(emptyFilters)}
    />
    <DataGrid
      rows={filteredRows}
      columns={columns}
      loading={loading}
      editMode="row"
      rowModesModel={rowModes}
      onRowModesModelChange={setRowModes}
      onRowEditStop={handleRowEditStop}
      processRowUpdate={processRowUpdate}
      onProcessRowUpdateError={handleProcessRowUpdateError}
      showToolbar
      slots={{ toolbar: Toolbar }}
      slotProps={{ toolbar: { onAdd: handleAdd } }}
      pageSizeOptions={[25, 50, 100]}
      initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
      autoHeight
    />
  </Box>
)
```

Note: `emptyFilters` can be defined as a module-level constant to avoid re-creation on each render, or inline as shown — either is fine.

---

- [ ] **Step 4: Run all tests**

```bash
cd ui && npm test
```

Expected: all tests pass, including the new chip integration tests.

---

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/ContestsGrid.tsx ui/src/components/ContestsGrid.test.tsx
git commit -m "feat: wire FilterChips into ContestsGrid and add integration tests"
```
