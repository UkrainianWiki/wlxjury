# ContestsGrid Cell Filter Design

**Date:** 2026-04-21  
**Feature:** Clickable Name/Year/Country cells that filter the ContestsGrid

---

## Overview

Name, Year, and Country cells in ContestsGrid become clickable. Clicking a value adds a filter for that column, narrowing the grid to rows matching that value. Filters are shown as removable chips above the grid.

---

## Behaviour

### Clicking a cell
- Clicking a Name, Year, or Country cell adds a filter: `column = value`.
- Only one filter per column is possible at a time (each column has at most one active value).
- Multiple column filters combine with AND logic (e.g. Country=Ukraine AND Year=2023).
- A cell is only clickable (rendered as a link) when **no filter is active for that column**. Once a column is filtered, all visible rows show the same value — those cells render as plain text with no link affordance.

### Chip bar
- Rendered above the MUI DataGrid toolbar (outside the grid).
- Hidden entirely when no filters are active.
- Each active filter shown as a chip: `Column: Value ×`.
- Clicking `×` on a chip removes that filter.
- A "Clear all" link appears alongside the chips when 2 or more filters are active.

### Filtering
- Client-side only — `rows.filter()` applied to the full row list before passing to DataGrid.
- No API calls. Filter state is persisted in the URL query string (see below).

---

## State

```ts
type FilterKey = 'name' | 'year' | 'country'
const [filters, setFilters] = useState<Record<FilterKey, string | null>>({
  name: null, year: null, country: null
})
```

Derived: `filteredRows = rows.filter(r => every active filter matches r[key])`.

---

## URL persistence

Filter state is reflected in the URL query string so that filtered views are bookmarkable and survive page reload.

### Query parameter format

Each active filter maps to a query param with a `f_` prefix:

| Filter | Query param |
|---|---|
| `name = WLM Ukraine` | `f_name=WLM+Ukraine` |
| `year = 2023` | `f_year=2023` |
| `country = Ukraine` | `f_country=Ukraine` |

Example URL: `/?f_country=Ukraine&f_year=2023`

### Read on mount

On component mount, read `window.location.search` with `URLSearchParams` and initialise `filters` state from any `f_name` / `f_year` / `f_country` params present. Unknown or empty params are ignored.

### Write on change

A `useEffect` that depends on `filters` calls `history.replaceState` to update the query string without a page navigation. Active filters are written as `f_*` params; null filters are omitted entirely. Unrelated query params (if any) are preserved.

---

## Cell rendering

Each of the three columns gets a custom `renderCell`. The renderer:
1. Checks whether a filter is active for its column.
2. If **no filter**: renders the value as a link (`cursor: pointer`, dotted underline, primary colour). `onClick` calls `setFilters(prev => ({ ...prev, [col]: value }))`.
3. If **filter active**: renders plain text (no interaction).

Cells that are in edit mode are unaffected — the `renderCell` function is only used in view mode.

---

## Chip bar component

A small inline component (can live in the same file as `ContestsGrid`):

```
<FilterChips filters={filters} onRemove={key => setFilters(...)} onClearAll={() => setFilters(empty)} />
```

- Rendered as a `Box` with `display: flex`, `flexWrap: wrap`, `gap`, above the `DataGrid`.
- Hidden (`display: none` or conditional render) when all filter values are null.
- Uses MUI `Chip` with `onDelete` for each active filter.
- "Clear all" is a small `Button` or `Link` that appears only when ≥2 chips are active.

---

## Interaction states (summary)

| State | Chip bar | Name cell | Year cell | Country cell |
|---|---|---|---|---|
| No filters | hidden | link | link | link |
| Country=Ukraine | visible (1 chip) | link | link | plain text |
| Country=Ukraine, Year=2023 | visible (2 chips + Clear all) | link | plain text | plain text |

---

## Out of scope

- Filtering on any other column (ID, Images category, Monument template)
- Server-side filtering
