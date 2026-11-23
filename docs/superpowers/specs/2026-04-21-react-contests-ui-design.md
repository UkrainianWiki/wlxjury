# React Contests UI — Design Spec

**Date:** 2026-04-21  
**Status:** Approved

## Overview

A standalone React + TypeScript UI for managing contests in WLX Jury. Backed by the existing Tapir REST API (`/api/contests`). Uses Material UI and MUI X Data Grid (free tier) with inline row editing. All functionality covered by Vitest + React Testing Library tests wired into `sbt test`.

---

## Architecture

### Project location

`ui/` — a standalone Vite + React + TypeScript project, sibling to `javascript-test/`.

### File structure

```
ui/
  src/
    api/
      contests.ts          # fetch wrappers: listContests, createContest, updateContest
      contests.test.ts     # unit tests for fetch wrappers
    components/
      ContestsGrid.tsx     # MUI X DataGrid with inline editing, toolbar, error banner
      ContestsGrid.test.tsx
    App.tsx                # root component — mounts ContestsGrid
    main.tsx               # Vite entry point — renders <App> into #root
  index.html
  vite.config.ts           # dev proxy: /api/* → localhost:9000
  tsconfig.json
  package.json
```

### Dev workflow

`npm run dev` in `ui/` starts Vite on port 5173. The `/api/*` proxy forwards requests to Play on port 9000. No CORS configuration needed.

### Production build

`npm run build` outputs static assets to `ui/dist/`. Before `sbt dist`, the CI/deploy step copies `ui/dist/` → `public/ui/`.

Play serves the built UI via two new routes added to `conf/routes`:

```
GET  /ui        controllers.Assets.at(path="/public/ui", file="index.html")
GET  /ui/*file  controllers.Assets.at(path="/public/ui", file)
```

### `sbt test` integration

`build.sbt` gains a `uiTest` task that runs `npm test` in `ui/` (Vitest with `--run` flag for CI, no watch mode). Both `jsTest` (existing) and `uiTest` are wired as dependencies of `Test / test`.

---

## UI

### ContestsGrid columns

| Column | Field | Editable | Notes |
|--------|-------|----------|-------|
| ID | `id` | No | Read-only, auto-assigned |
| Name | `name` | Yes | Required |
| Year | `year` | Yes | Required, numeric |
| Country | `country` | Yes | Required |
| Images category | `images` | Yes | Optional |
| Campaign | `campaign` | Yes | Optional |
| Monument template | `monumentIdTemplate` | Yes | Optional |
| Actions | — | — | Edit / Save✓ / Cancel✕ |

### Interactions

- **List:** Fetches `GET /api/contests` on mount; shows MUI loading overlay while in flight.
- **Add:** "＋ Add contest" toolbar button appends a new editable row (green dashed outline). Save calls `POST /api/contests`.
- **Edit:** "Edit" action button or double-click enters inline edit mode (blue outline). Save calls `PUT /api/contests`.
- **Save:** Validates name, year, country are non-blank. Shows inline field error if not. On success, row returns to view mode and the full list is re-fetched from `GET /api/contests`.
- **Cancel:** Discards changes, exits edit mode. For new rows, removes the row.
- **Error banner:** Shown on fetch failure with a "Retry" link that re-fetches.
- **Pagination:** MUI X Data Grid built-in pagination (25 rows default).

---

## Backend change

`updateContest` is defined in `Api.scala` but not wired to server logic. Add to the `routes` list in `Api.scala`:

```scala
updateContest.serverLogicSuccess { contest =>
  Future(contestService.updateContest(contest))
}
```

`ContestService.updateContest` already exists and is backed by ScalikeJDBC.

---

## Testing

**Framework:** Vitest + React Testing Library. Tests live alongside source files (`*.test.ts` / `*.test.tsx`).

### `api/contests.test.ts`

- `listContests` calls `GET /api/contests` and returns parsed JSON
- `createContest` calls `POST /api/contests` with correct body and returns the created contest
- `updateContest` calls `PUT /api/contests` with correct body
- Each function rejects with an error on non-2xx response

### `ContestsGrid.test.tsx`

- Renders contest rows returned by a mocked `listContests`
- Shows MUI loading state while fetch is in flight
- Shows error banner with retry button on fetch failure; retry re-fetches
- "Add contest" button adds an editable new row
- Double-clicking a row enters edit mode for that row
- Save in edit mode calls `updateContest` with updated fields
- Save for new row calls `createContest` with entered fields
- Cancel discards changes and removes new row / restores original values
- Blank required field (name, year, country) shows validation error on save attempt

---

## Out of scope

- Delete contest (no API endpoint exists)
- `greeting` field (complex nested object, not suitable for grid editing)
- `categoryId` / `currentRound` (read-only foreign keys, not shown)
- Authentication / role gating (existing Play session handles that at the page level)
