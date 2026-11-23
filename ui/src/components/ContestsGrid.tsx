import { useState, useEffect, useCallback } from 'react'
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
import { Button, Alert, Box, Chip } from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import EditIcon from '@mui/icons-material/Edit'
import SaveIcon from '@mui/icons-material/Save'
import CancelIcon from '@mui/icons-material/Cancel'
import { listContests, createContest, updateContest } from '../api/contests'
import type { Contest } from '../api/contests'

export type FilterKey = 'name' | 'year' | 'country'
export type Filters = Record<FilterKey, string | null>

const FILTER_LABELS: Record<FilterKey, string> = {
  name: 'Name',
  year: 'Year',
  country: 'Country',
}

const FILTER_KEYS: FilterKey[] = ['name', 'year', 'country']

export interface FilterChipsProps {
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
          deleteIcon={<CancelIcon data-testid={`remove-filter-${key}`} />}
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

declare module '@mui/x-data-grid' {
  interface ToolbarPropsOverrides {
    onAdd: () => void
  }
}

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
  const [isLoadError, setIsLoadError] = useState(false)

  const fetchContests = useCallback(async () => {
    setLoading(true)
    setError(null)
    setIsLoadError(false)
    try {
      setRows(await listContests())
    } catch {
      setError('Failed to load contests.')
      setIsLoadError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchContests() }, [fetchContests])

  const handleAdd = useCallback(() => {
    const id = -Date.now()
    setRows(prev => [
      { id, name: '', year: new Date().getFullYear(), country: '', isNew: true },
      ...prev
    ])
    setRowModes(prev => ({
      ...prev,
      [id]: { mode: GridRowModes.Edit, fieldToFocus: 'name' }
    }))
  }, [])

  const handleRowEditStop: GridEventListener<'rowEditStop'> = (params, event) => {
    if (params.reason === GridRowEditStopReasons.rowFocusOut) {
      event.defaultMuiPrevented = true
    }
  }

  const handleEdit = useCallback((id: GridRowId) => () =>
    setRowModes(prev => ({ ...prev, [id]: { mode: GridRowModes.Edit } })), [])

  const handleSave = useCallback((id: GridRowId) => () =>
    setRowModes(prev => ({ ...prev, [id]: { mode: GridRowModes.View } })), [])

  const handleCancel = useCallback((id: GridRowId) => () => {
    setRowModes(prev => ({
      ...prev,
      [id]: { mode: GridRowModes.View, ignoreModifications: true }
    }))
    setRows(prev => prev.filter(r => !(r.id === id && r.isNew)))
  }, [])

  const processRowUpdate = useCallback(async (newRow: GridRowModel): Promise<GridRowModel> => {
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
  }, [fetchContests])

  const handleProcessRowUpdateError = useCallback((err: Error) => {
    setError(err.message)
  }, [])

  const columns: GridColDef[] = [
    { field: 'id', headerName: 'ID', width: 90, editable: false },
    { field: 'name', headerName: 'Name', flex: 1, minWidth: 120, maxWidth: 220, editable: true },
    { field: 'year', headerName: 'Year', width: 80, type: 'number', editable: true },
    { field: 'country', headerName: 'Country', flex: 0.8, minWidth: 90, editable: true },
    { field: 'images', headerName: 'Images category', flex: 2, minWidth: 150, editable: true },
    { field: 'monumentIdTemplate', headerName: 'Monument template', flex: 1.5, minWidth: 140, editable: true },
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
        showToolbar
        slots={{ toolbar: Toolbar }}
        slotProps={{ toolbar: { onAdd: handleAdd } }}
        pageSizeOptions={[25, 50, 100]}
        initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
        autoHeight
      />
    </Box>
  )
}
