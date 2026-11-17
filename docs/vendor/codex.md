# Vendored design tokens: Wikimedia Codex 2.7.0

The app's colours, link and focus styles follow the Wikimedia design system
([Codex](https://doc.wikimedia.org/codex/latest/)). Only its **design tokens** (CSS custom
properties) are used; no Codex components, Vue code or fonts.

| | |
|---|---|
| Package | `@wikimedia/codex-design-tokens` **2.7.0** (npm; released 2026-09-09, the newest release at least two weeks old on 2026-11-17; 2.8.0 is from 2026-11-13) |
| Source | https://www.npmjs.com/package/@wikimedia/codex-design-tokens , https://gerrit.wikimedia.org/g/design/codex/ |
| Tarball sha256 | `df69fb657139c67b218668de8e07676b54b7c7abf0f671f9191b75461d471148` (`wikimedia-codex-design-tokens-2.7.0.tgz`) |
| Licence | GPL-2.0-or-later (package `license` field `GPL-2.0+`), used under this project's GPL-3.0-or-later; see `NOTICE` |

## Files (`public/stylesheets/vendor/codex/`)

| File | Origin | sha256 |
|---|---|---|
| `theme-wikimedia-ui-root.css` | `dist/theme-wikimedia-ui-root.css`, unchanged (light theme, sizes, spacing) | `1680596430c574060f1338faa7aa18efab4782427ff51e726a76733eb59ec70a` |
| `theme-wikimedia-ui-mode-dark.css` | `dist/theme-wikimedia-ui-mode-dark.css`, unchanged (dark mode colours) | `0ef2fc576f5e5abae42b150d0da82887484c3c78cbcacfcb90381766143e7300` |
| `codex-tokens.css` | **generated** by `scripts/codex-tokens.py`: the two files' text with only the `:root` selector replaced by `:root, [data-bs-theme=light]` and `[data-bs-theme=dark]` | |

The page loads `codex-tokens.css` (the app picks the mode with `data-bs-theme` on `<html>`, so a
file that puts the dark colours on `:root` could not be loaded as it is) and then
`codex-theme.css`, our own file: it maps the tokens onto Bootstrap's `--bs-*` variables and
styles the components. Every deviation from a Codex value is in `codex-theme.css`, commented
with its contrast numbers; the vendored files are never edited.

Bootstrap also reads `rgb()` companions of some colours (`--bs-body-bg-rgb`, `--bs-link-color-rgb`,
...), which can't be computed from a `var()`: `codex-theme.css` writes them out, each line naming
its token (`--bs-body-bg-rgb: 255, 255, 255; /* = --background-color-base */`).
`python scripts/codex-tokens.py --check` fails when `codex-tokens.css` is out of date or when a
companion differs from its token's vendored value in its rule's mode (or names no token).

`.gitattributes` marks `public/stylesheets/vendor/codex/**` `-text`, so a checkout with
`core.autocrlf` keeps the vendored bytes and their hashes above.

## Updating

1. `npm pack @wikimedia/codex-design-tokens@<version>` in an empty directory and unpack it.
2. Copy `dist/theme-wikimedia-ui-root.css` and `dist/theme-wikimedia-ui-mode-dark.css` over the
   two files above; run `python scripts/codex-tokens.py`, then `python scripts/codex-tokens.py
   --check` (fix any `rgb()` companion it names in `codex-theme.css`).
3. Update the version, tarball hash and file hashes here and the version in `NOTICE`.
4. Diff the token values; check `codex-theme.css` (contrast comments) and run the browser tests
   `theme.spec.js`, `voting.spec.js`, `navbar.spec.js`.
