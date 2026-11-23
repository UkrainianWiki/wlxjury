# React Contests UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standalone React + TypeScript UI at `/ui` that displays a contests table with inline row editing, backed by the existing Tapir REST API.

**Architecture:** Standalone Vite project in `ui/`, proxying `/api/*` to Play in dev. MUI X DataGrid with `editMode="row"` for inline editing. The Scala backend needs one addition: `ContestService.updateContest` and wiring it into `Api.scala`. Tests use Vitest + React Testing Library, run as a `uiTest` sbt task.

**Tech Stack:** React 19, TypeScript 5, Vite 8, MUI v9 + MUI X DataGrid v9 (free), Vitest 4, React Testing Library 16.

---

## File Map

**New files:**
- `ui/package.json` — Vite project manifest, all deps, test script
- `ui/vite.config.ts` — Vite config with dev proxy and Vitest config
- `ui/tsconfig.json` — TypeScript config
- `ui/index.html` — HTML entry point
- `ui/src/test-setup.ts` — Vitest setup (jest-dom + ResizeObserver polyfill)
- `ui/src/main.tsx` — React entry point
- `ui/src/App.tsx` — Root component, mounts ContestsGrid
- `ui/src/api/contests.ts` — Fetch wrappers: listContests, createContest, updateContest
- `ui/src/api/contests.test.ts` — Unit tests for fetch wrappers
- `ui/src/components/ContestsGrid.tsx` — DataGrid with inline editing, toolbar, error banner
- `ui/src/components/ContestsGrid.test.tsx` — Component tests

**Modified files:**
- `app/services/ContestService.scala` — add `updateContest` method
- `app/api/Api.scala` — wire `updateContest` server logic
- `build.sbt` — add `uiTest` task, wire into `Test / test`
- `conf/routes` — add `/ui` and `/ui/*file` routes
- `.gitignore` — add `public/ui/`

---

## Task 1: Wire updateContest in the Scala backend

**Files:**
- Modify: `app/services/ContestService.scala`
- Modify: `app/api/Api.scala`

- [ ] **Step 1: Add `updateContest` to ContestService**

  Open `app/services/ContestService.scala`. Add this method after `getContest`:

  ```scala
  def updateContest(contest: ContestJury): Unit =
    contest.id.foreach { id =>
      ContestJuryJdbc.updateById(id).withAttributes(
        "name"                -> contest.name,
        "year"                -> contest.year,
        "country"             -> contest.country,
        "images"              -> contest.images,
        "campaign"            -> contest.campaign,
        "monumentIdTemplate"  -> contest.monumentIdTemplate
      )
    }
  ```

  `updateById` comes from `CRUDMapper` (the parent of `ContestJuryJdbc`). Attribute names are Scala camelCase field names; ScalikeJDBC converts them to snake_case for SQL automatically.

- [ ] **Step 2: Wire `updateContest` in Api.scala**

  Open `app/api/Api.scala`. The current `routes` list has three entries. Add the fourth:

  ```scala
  val routes = PekkoHttpServerInterpreter().toRoute(List(
    createContest.serverLogicSuccess { contest =>
      Future(contestService.createContest(contest))
    },
    getContest.serverLogicSuccess { id =>
      Future(contestService.getContest(id).get)
    },
    listContests.serverLogicSuccess { _ =>
      Future(contestService.findContests())
    },
    updateContest.serverLogicSuccess { contest =>
      Future(contestService.updateContest(contest))
    }
  ))
  ```

  The `updateContest` endpoint already has `serverLogicSuccess` infer `Future[Unit]` from the method return type.

- [ ] **Step 3: Compile to verify**

  ```bash
  sbt compile
  ```

  Expected: `[success] Total time: ...` with no errors.

- [ ] **Step 4: Commit**

  ```bash
  git add app/services/ContestService.scala app/api/Api.scala
  git commit -m "feat: add updateContest to ContestService and wire in Api"
  ```

---

## Task 2: Scaffold the ui/ project

**Files:** All new files under `ui/`.

- [ ] **Step 1: Create `ui/package.json`**

  ```json
  {
    "name": "wlxjury-ui",
    "private": true,
    "version": "0.0.1",
    "type": "module",
    "scripts": {
      "dev": "vite",
      "build": "tsc && vite build",
      "test": "vitest run",
      "test:watch": "vitest"
    },
    "dependencies": {
      "@emotion/react": "^11.14.0",
      "@emotion/styled": "^11.14.0",
      "@mui/icons-material": "^9.0.0",
      "@mui/material": "^9.0.0",
      "@mui/x-data-grid": "^9.0.0",
      "react": "^19.0.0",
      "react-dom": "^19.0.0"
    },
    "devDependencies": {
      "@testing-library/jest-dom": "^6.6.0",
      "@testing-library/react": "^16.0.0",
      "@testing-library/user-event": "^14.5.0",
      "@types/react": "^19.0.0",
      "@types/react-dom": "^19.0.0",
      "@vitejs/plugin-react": "^5.0.0",
      "jsdom": "^25.0.0",
      "typescript": "^5.3.0",
      "vite": "^8.0.0",
      "vitest": "^4.0.0"
    }
  }
  ```

- [ ] **Step 2: Create `ui/vite.config.ts`**

  ```typescript
  import { defineConfig } from 'vite'
  import react from '@vitejs/plugin-react'

  export default defineConfig({
    plugins: [react()],
    server: {
      proxy: {
        '/api': 'http://localhost:9000'
      }
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test-setup.ts'],
      globals: true
    }
  })
  ```

- [ ] **Step 3: Create `ui/tsconfig.json`**

  ```json
  {
    "compilerOptions": {
      "target": "ES2020",
      "useDefineForClassFields": true,
      "lib": ["ES2020", "DOM", "DOM.Iterable"],
      "module": "ESNext",
      "skipLibCheck": true,
      "moduleResolution": "bundler",
      "allowImportingTsExtensions": true,
      "resolveJsonModule": true,
      "isolatedModules": true,
      "noEmit": true,
      "jsx": "react-jsx",
      "strict": true,
      "types": ["vitest/globals"]
    },
    "include": ["src"]
  }
  ```

- [ ] **Step 4: Create `ui/index.html`**

  ```html
  <!DOCTYPE html>
  <html lang="en">
    <head>
      <meta charset="UTF-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <title>WLX Jury — Contests</title>
    </head>
    <body>
      <div id="root"></div>
      <script type="module" src="/src/main.tsx"></script>
    </body>
  </html>
  ```

- [ ] **Step 5: Create `ui/src/test-setup.ts`**

  ```typescript
  import '@testing-library/jest-dom'

  // MUI DataGrid uses ResizeObserver — polyfill for jsdom
  class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  window.ResizeObserver = ResizeObserver
  ```

- [ ] **Step 6: Create `ui/src/main.tsx`**

  ```typescript
  import { StrictMode } from 'react'
  import { createRoot } from 'react-dom/client'
  import App from './App'

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>
  )
  ```

- [ ] **Step 7: Create `ui/src/App.tsx`**

  ```typescript
  import ContestsGrid from './components/ContestsGrid'

  export default function App() {
    return <ContestsGrid />
  }
  ```

- [ ] **Step 8: Install dependencies**

  ```bash
  cd ui && npm install
  ```

  Expected: `node_modules/` created, no peer-dep errors.

- [ ] **Step 9: Verify TypeScript compiles**

  ```bash
  cd ui && npx tsc --noEmit
  ```

  Expected: no output (success). If errors appear, fix before continuing.

- [ ] **Step 10: Commit**

  ```bash
  git add ui/
  git commit -m "feat: scaffold ui/ Vite+React project"
  ```

---

## Task 3: API fetch wrappers (TDD)

**Files:**
- Create: `ui/src/api/contests.ts`
- Create: `ui/src/api/contests.test.ts`

- [ ] **Step 1: Write the failing tests**

  Create `ui/src/api/contests.test.ts`:

  ```typescript
  import { describe, test, expect, vi, beforeEach } from 'vitest'
  import { listContests, createContest, updateContest } from './contests'
  import type { Contest } from './contests'

  const mockContest: Contest = { id: 1, name: 'WLM', year: 2024, country: 'Ukraine' }

  function mockFetch(body: unknown, ok = true, status = 200) {
    global.fetch = vi.fn().mockResolvedValue({
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
      expect(global.fetch).toHaveBeenCalledWith('/api/contests')
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
      expect(global.fetch).toHaveBeenCalledWith('/api/contests', expect.objectContaining({
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
      expect(global.fetch).toHaveBeenCalledWith('/api/contests', expect.objectContaining({
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
  ```

- [ ] **Step 2: Run tests to confirm failure**

  ```bash
  cd ui && npm test
  ```

  Expected: FAIL — `Cannot find module './contests'`.

- [ ] **Step 3: Implement `ui/src/api/contests.ts`**

  ```typescript
  export interface Greeting {
    text?: string
    use: boolean
  }

  export interface Contest {
    id?: number
    name: string
    year: number
    country: string
    images?: string
    campaign?: string
    monumentIdTemplate?: string
    greeting?: Greeting
  }

  async function request<T>(url: string, init?: RequestInit): Promise<T> {
    const res = await fetch(url, init)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return res.json()
  }

  export function listContests(): Promise<Contest[]> {
    return request('/api/contests')
  }

  export function createContest(contest: Contest): Promise<Contest> {
    return request('/api/contests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(contest)
    })
  }

  export async function updateContest(contest: Contest): Promise<void> {
    const res = await fetch('/api/contests', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(contest)
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
  }
  ```

- [ ] **Step 4: Run tests to confirm pass**

  ```bash
  cd ui && npm test
  ```

  Expected: all 6 tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add ui/src/api/
  git commit -m "feat: add contests API fetch wrappers with tests"
  ```

---

## Task 4: ContestsGrid component (TDD)

**Files:**
- Create: `ui/src/components/ContestsGrid.tsx`
- Create: `ui/src/components/ContestsGrid.test.tsx`

- [ ] **Step 1: Write the failing tests**

  Create `ui/src/components/ContestsGrid.test.tsx`:

  ```typescript
  import { describe, test, expect, vi, beforeEach } from 'vitest'
  import { render, screen, waitFor, fireEvent } from '@testing-library/react'
  import userEvent from '@testing-library/user-event'
  import ContestsGrid from './ContestsGrid'
  import * as api from '../api/contests'
  import type { Contest } from '../api/contests'

  vi.mock('../api/contests')

  const contest1: Contest = { id: 1, name: 'WLM Ukraine', year: 2024, country: 'Ukraine' }
  const contest2: Contest = { id: 2, name: 'WLE Poland', year: 2024, country: 'Poland' }

  beforeEach(() => {
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

    await user.click(screen.getByRole('menuitem', { name: /save/i }, { hidden: true }))

    await waitFor(() => expect(api.createContest).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'New Contest', year: 2025, country: 'Germany' })
    ))
    expect(api.listContests).toHaveBeenCalledTimes(2)
  })

  test('clicking Edit on a row enters edit mode', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    const editButtons = screen.getAllByRole('button', { name: /edit/i, hidden: true })
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

    const editButtons = screen.getAllByRole('button', { name: /edit/i, hidden: true })
    await user.click(editButtons[0])

    const nameInput = await screen.findByDisplayValue('WLM Ukraine')
    await user.clear(nameInput)
    await user.type(nameInput, 'WLM Ukraine Updated')

    await user.click(screen.getByRole('menuitem', { name: /save/i }, { hidden: true }))

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

    await user.click(screen.getByRole('button', { name: /cancel/i, hidden: true }))

    await waitFor(() => expect(screen.queryAllByRole('textbox').length).toBe(0))
    expect(api.createContest).not.toHaveBeenCalled()
  })

  test('cancelling an edit restores original values', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    const editButtons = screen.getAllByRole('button', { name: /edit/i, hidden: true })
    await user.click(editButtons[0])

    const nameInput = await screen.findByDisplayValue('WLM Ukraine')
    await user.clear(nameInput)
    await user.type(nameInput, 'Changed Name')

    await user.click(screen.getByRole('button', { name: /cancel/i, hidden: true }))

    await waitFor(() => expect(screen.getByText('WLM Ukraine')).toBeInTheDocument())
    expect(api.updateContest).not.toHaveBeenCalled()
  })

  test('saving with blank name shows error and does not call API', async () => {
    const user = userEvent.setup()
    render(<ContestsGrid />)
    await screen.findByText('WLM Ukraine')

    const editButtons = screen.getAllByRole('button', { name: /edit/i, hidden: true })
    await user.click(editButtons[0])

    const nameInput = await screen.findByDisplayValue('WLM Ukraine')
    await user.clear(nameInput)

    await user.click(screen.getByRole('menuitem', { name: /save/i }, { hidden: true }))

    await waitFor(() => expect(screen.getByText(/name.*required|required.*name/i)).toBeInTheDocument())
    expect(api.updateContest).not.toHaveBeenCalled()
  })
  ```

- [ ] **Step 2: Run tests to confirm failure**

  ```bash
  cd ui && npm test
  ```

  Expected: FAIL — `Cannot find module './ContestsGrid'`.

- [ ] **Step 3: Implement `ui/src/components/ContestsGrid.tsx`**

  ```typescript
  import React, { useState, useEffect, useCallback } from 'react'
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
  } from '@mui/x-data-grid'
  import { Button, Alert, Box } from '@mui/material'
  import AddIcon from '@mui/icons-material/Add'
  import EditIcon from '@mui/icons-material/Edit'
  import SaveIcon from '@mui/icons-material/Save'
  import CancelIcon from '@mui/icons-material/Cancel'
  import { listContests, createContest, updateContest } from '../api/contests'
  import type { Contest } from '../api/contests'

  type Row = Contest & { isNew?: boolean }

  function Toolbar({ onAdd }: { onAdd: () => void }) {
    return (
      <GridToolbarContainer>
        <Button startIcon={<AddIcon />} onClick={onAdd} aria-label="Add contest">
          Add contest
        </Button>
      </GridToolbarContainer>
    )
  }

  export default function ContestsGrid() {
    const [rows, setRows] = useState<Row[]>([])
    const [rowModes, setRowModes] = useState<GridRowModesModel>({})
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const fetchContests = useCallback(async () => {
      setLoading(true)
      setError(null)
      try {
        setRows(await listContests())
      } catch {
        setError('Failed to load contests.')
      } finally {
        setLoading(false)
      }
    }, [])

    useEffect(() => { fetchContests() }, [fetchContests])

    const handleAdd = () => {
      const id = -Date.now()
      setRows(prev => [
        { id, name: '', year: new Date().getFullYear(), country: '', isNew: true },
        ...prev
      ])
      setRowModes(prev => ({
        ...prev,
        [id]: { mode: GridRowModes.Edit, fieldToFocus: 'name' }
      }))
    }

    const handleRowEditStop: GridEventListener<'rowEditStop'> = (params, event) => {
      if (params.reason === GridRowEditStopReasons.rowFocusOut) {
        event.defaultMuiPrevented = true
      }
    }

    const handleEdit = (id: GridRowId) => () =>
      setRowModes(prev => ({ ...prev, [id]: { mode: GridRowModes.Edit } }))

    const handleSave = (id: GridRowId) => () =>
      setRowModes(prev => ({ ...prev, [id]: { mode: GridRowModes.View } }))

    const handleCancel = (id: GridRowId) => () => {
      setRowModes(prev => ({
        ...prev,
        [id]: { mode: GridRowModes.View, ignoreModifications: true }
      }))
      setRows(prev => prev.filter(r => !(r.id === id && r.isNew)))
    }

    const processRowUpdate = async (newRow: GridRowModel): Promise<GridRowModel> => {
      const row = newRow as Row
      if (!row.name?.trim() || !row.year || !row.country?.trim()) {
        throw new Error('Name, year, and country are required.')
      }
      if (row.isNew) {
        const { isNew: _, ...contestData } = row
        const created = await createContest({ ...contestData, id: undefined })
        await fetchContests()
        return created
      } else {
        await updateContest(row)
        await fetchContests()
        return newRow
      }
    }

    const handleProcessRowUpdateError = (err: Error) => {
      setError(err.message)
    }

    const columns: GridColDef[] = [
      { field: 'id', headerName: 'ID', width: 70, editable: false },
      { field: 'name', headerName: 'Name', flex: 1, editable: true },
      { field: 'year', headerName: 'Year', width: 80, type: 'number', editable: true },
      { field: 'country', headerName: 'Country', width: 120, editable: true },
      { field: 'images', headerName: 'Images category', flex: 1, editable: true },
      { field: 'campaign', headerName: 'Campaign', width: 130, editable: true },
      { field: 'monumentIdTemplate', headerName: 'Monument template', width: 160, editable: true },
      {
        field: 'actions',
        type: 'actions',
        headerName: 'Actions',
        width: 100,
        getActions: ({ id }) => {
          const isEditing = rowModes[id]?.mode === GridRowModes.Edit
          if (isEditing) {
            return [
              <GridActionsCellItem
                icon={<SaveIcon />}
                label="Save"
                onClick={handleSave(id)}
              />,
              <GridActionsCellItem
                icon={<CancelIcon />}
                label="Cancel"
                onClick={handleCancel(id)}
              />,
            ]
          }
          return [
            <GridActionsCellItem
              icon={<EditIcon />}
              label="Edit"
              onClick={handleEdit(id)}
            />,
          ]
        }
      }
    ]

    return (
      <Box sx={{ width: '100%' }}>
        {error && (
          <Alert
            severity="error"
            onClose={() => setError(null)}
            action={
              error === 'Failed to load contests.' ? (
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
        <DataGrid
          rows={rows}
          columns={columns}
          loading={loading}
          editMode="row"
          rowModesModel={rowModes}
          onRowModesModelChange={setRowModes}
          onRowEditStop={handleRowEditStop}
          processRowUpdate={processRowUpdate}
          onProcessRowUpdateError={handleProcessRowUpdateError}
          slots={{ toolbar: Toolbar }}
          slotProps={{ toolbar: { onAdd: handleAdd } as { onAdd: () => void } }}
          pageSizeOptions={[25, 50, 100]}
          initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
          autoHeight
        />
      </Box>
    )
  }
  ```

- [ ] **Step 4: Run tests**

  ```bash
  cd ui && npm test
  ```

  Expected: all tests pass. If any test fails due to DataGrid interaction specifics (role names, aria attributes), inspect the rendered HTML with `screen.debug()` and adjust the query (`getByRole`, `getByLabelText`, etc.) to match what DataGrid actually renders. The logic under test does not change — only the query selectors.

- [ ] **Step 5: Commit**

  ```bash
  git add ui/src/components/
  git commit -m "feat: add ContestsGrid component with inline editing and tests"
  ```

---

## Task 5: Wire uiTest into sbt

**Files:**
- Modify: `build.sbt`

- [ ] **Step 1: Add `uiTest` task and wire both into `Test / test`**

  Open `build.sbt`. Find these two lines:

  ```scala
  // Wire jsTest into the normal `sbt test` run.
  Test / test := (Test / test).dependsOn(jsTest).value
  ```

  Replace them with:

  ```scala
  // Run Vitest tests in ui/ as part of `sbt test`.
  lazy val uiTest = taskKey[Unit]("Run Vitest tests in ui/")

  uiTest := {
    val uiDir = baseDirectory.value / "ui"
    val result = scala.sys.process.Process(Seq("npm", "test"), uiDir).!
    if (result != 0) throw new MessageOnlyException("Vitest tests failed")
  }

  // Wire jsTest and uiTest into the normal `sbt test` run.
  Test / test := (Test / test).dependsOn(jsTest, uiTest).value
  ```

- [ ] **Step 2: Verify `sbt test` runs ui tests**

  ```bash
  sbt "testOnly db.scalikejdbc.MonumentSpec"
  ```

  This does NOT run UI tests (it's a specific test class). Now run:

  ```bash
  sbt test 2>&1 | grep -E "Vitest|PASS|FAIL|vitest"
  ```

  Expected: output showing Vitest running and passing all tests.

- [ ] **Step 3: Commit**

  ```bash
  git add build.sbt
  git commit -m "build: wire uiTest into sbt test"
  ```

---

## Task 6: Add Play routes and gitignore

**Files:**
- Modify: `conf/routes`
- Modify: `.gitignore`

- [ ] **Step 1: Add routes for `/ui` to `conf/routes`**

  Open `conf/routes`. Find the static assets route:

  ```
  GET         /assets/*file                                                           @controllers.Assets.at(path="/public", file)
  ```

  Add two new routes immediately after it:

  ```
  GET         /ui                                                                     controllers.Assets.at(path="/public/ui", file="index.html")
  GET         /ui/*file                                                               controllers.Assets.at(path="/public/ui", file)
  ```

  These routes serve the production Vite build. During development, use `npm run dev` in `ui/` instead.

- [ ] **Step 2: Add `public/ui/` to `.gitignore`**

  Open `.gitignore` and add:

  ```
  public/ui/
  ```

  `public/ui/` is the output of `npm run build` copied for production — it's generated, not source.

- [ ] **Step 3: Compile to verify routes parse correctly**

  ```bash
  sbt compile
  ```

  Expected: `[success]`. Routes are parsed at compile time; a typo would be a compile error.

- [ ] **Step 4: Commit**

  ```bash
  git add conf/routes .gitignore
  git commit -m "feat: add Play routes for /ui static assets"
  ```

---

## Dev Workflow Reference

**Start development:**
```bash
# Terminal 1 — Play backend
sbt run

# Terminal 2 — Vite dev server (proxies /api to localhost:9000)
cd ui && npm run dev
# Open: http://localhost:5173
```

**Run all tests:**
```bash
sbt test   # runs Scala tests + javascript-test Jest + ui Vitest
```

**Build for production:**
```bash
cd ui && npm run build
cp -r ui/dist/* public/ui/
sbt dist
```
