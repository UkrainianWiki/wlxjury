# A React gallery instead of our own tiles? (investigation, 2026-11-24)

**Question (maintainer):** investigate a gallery alternative using React plus the components
[react-photo-album](https://react-photo-album.com/) and
[yet-another-react-lightbox](https://yet-another-react-lightbox.com/) (YARL).

**Status:** investigation only; nothing in the application changes. Compared against the accepted
plan [own-gallery-tiles](../plans/2026-11-19-own-gallery-tiles.md) (PR #129; "#129" below), whose
questions Q1 to Q26 the maintainer has decided. Nothing was run in the application (no sbt, no
fixture server). The only measurement is a throwaway bundle-size build in a scratch directory
outside the repository (section 3.4).

## Summary and recommendation

**Recommendation: keep option A, the #129 plan as decided. Do not build a React gallery (B).
Keep one narrow door open: a YARL lightbox as a later, optional island (C1), but only if the
maintainer reopens Q2 ("no lightbox") after #129 ships.**

Why, in five points:

1. **The two libraries solve the cheap part of #129, not the expensive part.** About half of
   #129's effort is server work that no React library touches: the keyset continuation,
   thumbnail size schemes and prefetch, the proxy and cache, the window-size cookie, the account
   setting, authorization of other jurors' votes, the cache cleanup. That work stays under every
   option.
2. **react-photo-album renders nothing without script by default.** Its documented server output
   is empty markup; its prerendering variants need a React server render, and Play has no Node
   process. #129's rows are CSS and work without script (the pager still works, goal 4). With
   React, no-JS becomes a second implementation, or is dropped.
3. **A React gallery re-implements everything the tile partial, `tile-votes.js`,
   `file-anchor.js` and the by-rate list do today** (binary thumbs, raty stars, criteria
   averages, chips, rank, deleted badge, tallies, vote time, the hint), plus a JSON API for the
   tiles and client-side i18n for ru/uk/fr. Today those are server-rendered Twirl with
   `messages.*`, covered by Jest and Playwright specs that would be rewritten.
4. **React was tried three times in this repository and none reached a page a user sees**
   (section 2): a 2020 create-react-app demo with sample photos, and two April 2026 branches of
   an admin contests grid. None touched the gallery, voting or authorization. There is no React
   build in the production pipeline, so B also pays for the first one (Node in builds, CI and
   packaging, the CSP and dependency review).
5. **The gain is real but small and separable.** YARL is well kept (MIT, last release 2026-11-14)
   and small (about 11 KB gzipped for the core, measured). Its value is a quick preview with
   zoom, which #129 rejected (Q2) because voting, comments and details live in the large view. If
   a preview is wanted later it can be added to the server-rendered page without moving the
   gallery to React (C1).

**Rough effort** (calendar days, one developer; A's figure is the plan's own, the others are
estimates from this reading, not measurements):

| Option | Effort | Risk |
|---|---|---|
| A. #129 as planned | 28-33 days (decided) | known; steps G-0..G-7 are already review-sized |
| B. Full React gallery with both libraries | about 45-60 days, and #129's server half (about 15-18 days) is still needed | high: new build chain, rewrites of voting and by-rate, no-JS lost or doubled |
| C1. A plus a YARL lightbox island | A plus about 8-12 days, after A | medium: reopens Q2; the lightbox must reuse the vote endpoint, CSRF and own-votes rules |
| C2. A, but react-photo-album for layout only | A plus about 2 days, minus the CSS rows (2-3 days): no net gain | not worth it: React for a layout that is about 30 lines of CSS, and no-JS lost |

## 1. What was read

- The #129 plan (all sections) and its decisions.
- The three React branches and issue #104.
- The current gallery code: `gallery.scala.html`, `galleryByRate.scala.html`, `tile-votes.js`,
  `file-anchor.js`, `large-*.js`, `Global.thumbnailWidths`, `ImageProxyController`,
  `LocalImageCacheService.servedWidths`, the CSP in `conf/application.conf`, `build.sbt`
  (`jsTest`), `javascript-test/`, `browser-test/`.
- The two libraries' documentation pages, the npm registry and the GitHub repositories (API).

## 2. Earlier React attempts in this repository

All three branches were ported to this repository on 2026-11-23 from the private archive as
single squashed commits; the original dates are in the commit messages.

| Branch | What it is | Size | Why it stalled |
|---|---|---|---|
| `104-port-gallery-to-react` (22 commits, based on a June 2020 master) | A create-react-app (`ui/`) with `react-photo-gallery` (the older predecessor of react-photo-album) showing nine **hard-coded sample photos** with a select-all toggle (`App.js`, `SelectedImage.js`); sbt hooks that run `npm start` in dev mode (`ui-build.sbt`, `FrontendRunHook.scala`) and a `FrontendController` | 20 files, +21,334 (almost all lockfiles and the CRA README) | A spike. It never read the application's images, votes or sessions; the gallery was later built on Unite Gallery. Issue #104 itself is an unrelated closed bug ("image upside down"); the branch name only borrowed the number |
| `react` (21 commits, April 2026) | `ui/`: Vite, React 19, TypeScript, MUI and MUI X Data Grid; a **contests admin grid** over the Tapir `/api/contests`; Vitest; a design spec and plan in `docs/superpowers/` | 20 files, +5,616 | The Tapir API is not served and has no authentication (CLAUDE.md "REST API"), so the grid could not go live |
| `react-custom-filter` (6 more commits) | The same grid with cell filters | 22 files, +6,544 in all | Same dependency on the unserved, unauthenticated API |

What they show for this question:

- A working `ui/` skeleton exists (Vite, Vitest, React Testing Library). The April spec chose
  `npm run build` copied into `public/ui/`, served by two routes, with a `uiTest` task beside
  `jsTest`. That is a build-pipeline precedent, not a gallery.
- No branch handled authorization, CSRF, sessions, i18n or the image proxy. Those are the hard
  parts of a gallery, and a React gallery needs all of them.
- The 2020 branch used react-photo-gallery, a different library with a different API from the
  react-photo-album 3 the maintainer linked.

## 3. The libraries

### 3.1 react-photo-album 3.6.2 (MIT, released 2026-10-17)

- **Layouts:** `RowsPhotoAlbum` (justified rows, like #129's), `ColumnsPhotoAlbum`,
  `MasonryPhotoAlbum`, each with its own stylesheet. Photos need `width` and `height`
  (we have both from the file's metadata, as #129 does for ratio classes).
- **Responsive images:** a per-photo `srcSet` array (`{src, width, height}`) generates `srcset`;
  a `sizes` prop describes the album's width per viewport. This fits #129's 960/1280 prefetch:
  our proxy URLs go in the array and the browser chooses, as in #129.
- **Rows:** `targetRowHeight` (a number or a function of width), rows justified to the
  container width, which is measured in script. #129's rule "a short last row stays short, rows
  grow to at most 247 px" can be approximated with the library's row options but not matched
  exactly (not tested).
- **SSR:** by default the server output is **empty** (the layout needs the container width).
  Options: `defaultContainerWidth`, a skeleton, `visibility: hidden`, an `SSR` component that
  pre-renders several breakpoint layouts with CSS container queries, and a Server Component with
  no client JS. All need a **React server render**; Play/Scala cannot do that without a Node (or
  GraalJS) rendering service in production.
- **Infinite scroll:** an `InfiniteScroll` wrapper with a `fetch` callback that returns the next
  photos or `null`; the docs advise against it with the Columns layout. **No windowing**: all
  fetched photos stay in the DOM, as in #129's first build (the cap is measured in G-0/G-5).
- **Customisation:** render props replace the container, track, wrapper, link, button, image or
  whole photo, so our tile (vote buttons, chips, rank, "last viewed" mark) can be rendered;
  `componentsProps` adds attributes such as `data-file-anchor`.
- **Accessibility:** real links and buttons with the browser's focus ring (customisable by
  CSS); `alt`, `title` and `aria-label` per photo.
- **Maintenance:** 82 releases, one every one to three months (3.6.0 April, 3.6.1 July, 3.6.2
  October 2026); a single author; 1 open issue, a push on the day of this reading; about 800
  GitHub stars. Peer dependency React 18 or 19, no runtime dependencies.

### 3.2 yet-another-react-lightbox 3.33.1 (MIT, released 2026-11-14)

- **Plugins:** Captions, Counter, Download, Fullscreen, Inline, Rotate, Share, Slideshow,
  Thumbnails, Video, Zoom. Each is a separate import.
- **Keyboard and focus:** arrows and Escape built in (`controller.closeOnEscape`). The pages read
  here say nothing about rebinding keys or adding our vote keys; those would be a `keydown`
  listener or a custom plugin that must not clash with YARL's own handling. Focus moves into the
  lightbox; a focus trap is not described, and `controller.aria` (ARIA attributes on the
  controller) is off by default. **Accessibility needs a hands-on check before any adoption.**
- **Controlled index:** `index` and `on.view` give the current slide; the `slides` array must stay
  referentially stable across renders.
- **Customisation:** toolbar buttons, `render` slots for slide header/footer/controls and icons,
  styles per slot. A vote bar in a slide footer is feasible.
- **SSR:** a client component rendered into a portal when opened. For an island on a
  server-rendered page that is no problem: there is nothing to render until it opens.
- **Maintenance:** 157 releases, the last four on 2026-05-01 (3.32.0), 07-08, 07-30, 10-30 and
  11-14; one author; 1 open issue; about 1,300 stars. Peer dependency React 16.8 to 19.

### 3.3 Licence and supply chain

- Both are MIT. Neither has runtime dependencies, but React and react-dom come with them (about
  69 KB gzipped for a trivial app, measured), and a build chain (Vite or esbuild, TypeScript,
  Vitest) with its npm tree. A pinned, reviewed, lockfile-checked npm tree in the production
  build is new for this repository (dependencies rules, sections 1 and 6, as #129 cites).
- Both libraries have one maintainer each. Unite Gallery is vendored and patched by us because
  it is unmaintained (`docs/vendor/unitegallery.md`); an active single-author library is less
  risky than that, but is a dependency to watch.

### 3.4 Bundle sizes (scratch build with esbuild, minified, production React)

| Bundle | Minified | Gzipped |
|---|---|---|
| React 19.3 and react-dom, a trivial app | 223 KB | 69 KB |
| plus react-photo-album Rows | 231 KB | 72 KB |
| plus YARL core | 253 KB | 80 KB |
| plus YARL with Zoom, Captions, Thumbnails, Fullscreen | 278 KB | 88 KB |
| For comparison: today's `unitegallery.js` (vendored, patched) | 588 KB | 122 KB |

A React gallery with both libraries would be about 90 KB gzipped, smaller than the Unite Gallery
we ship today; but #129 removes Unite Gallery for CSS and a few hundred lines of vanilla JS,
which is smaller still. Size is not a reason for or against React here (a login-only tool).

## 4. Requirement-by-requirement comparison

A = #129 as planned. B = full React gallery. C1 = A plus a YARL lightbox island.

| # | Requirement | A: #129 | B: React gallery | C1: A plus YARL island |
|---|---|---|---|---|
| 1 | **Binary voting in the grid** | Server-rendered thumbs buttons, one delegated click handler in `tile-votes.js`; Enter/Space free with `<button>` | Re-implement as a React component with per-tile state; same `selectWS` endpoint | As A |
| 2 | **Rated 1-N stars, criteria averages, chips, rank, deleted badge** | A Twirl partial with tile options; raty stays for stars | Re-implement all of `galleryByRate.scala.html` (about 150 lines of Twirl with rules) and `showRateGalery` in JSX; raty replaced or wrapped | As A |
| 3 | **Voting inside a preview** | No lightbox (Q2): the large view votes | A YARL footer slot with the same components is feasible | A vote bar in a footer slot, same endpoint; keys must be added (row 4) |
| 4 | **Keyboard voting** (by paths, never absolute URLs; `LargeViewControllerSpec` guards it) | Unchanged: `large-keys.js` in the large view; tiles are links, Enter opens | A React-managed key layer on the grid is new; in the lightbox YARL owns arrows and Escape and the vote keys sit beside them | The large view keeps its keys; the lightbox needs its own, duplicating `large-keys.js` |
| 5 | **Own-votes rules** (`viewerRounds`, `mayViewRound`, `viewAs`, 404 for non-jurors) | The server decides what each tile shows; unchanged | The server rules are unchanged, but a JSON tile API re-exposes them per field: a second place to get a security rule wrong (rendered HTML cannot leak a field that is not rendered) | Slides read the tile's attributes: no new rules |
| 6 | **CSRF** | `Csrf-Token` header from `data-csrf-token` | Same header; the token must reach the bundle (as `data-csrf-token`) | Same |
| 7 | **Large-view deep links, Back, file-anchor + mark (Q26)** | The tile is a real link; `atUrl` returns to the page holding the file; `file-anchor.js` scrolls only if out of view; the mark is a class and a label | Links survive (`render.link`), but scroll and mark must wait for React to render the tile (#129 section 3.7's "tile not yet rendered" case becomes the normal case), and the Back page needs the same JSON slice | As A; the lightbox adds a second notion of position (slide index) that must map to `#file-<pageId>` on close, or Back differs |
| 8 | **Pages vs Continuous (Q16-Q20), `?n`, keyset cursor** | HTML chunks from the server, cursor in the URL | `InfiniteScroll.fetch` returns photos; the cursor logic is the same but the response is JSON; Pages mode still needs the server path | As A |
| 9 | **38k-image rounds, memory** | Cap measured (G-0), placeholder 480, server-rendered sections | Layout recomputed in JS over all loaded photos on resize; React state per loaded tile; no windowing in either library; heavier per tile than plain DOM, so the cap would probably be lower (unmeasured) | As A (the lightbox holds only the visible slides) |
| 10 | **Thumbnail sizes, our proxy, Commons rate limits** | 960/1280 prefetch at distribution; proxy `srcset` (R6) | The same server work; the `srcSet` array is the library's form of the same `srcset`: no advantage | Same; a slide's `srcSet` can use the 1280 file |
| 11 | **Accessibility** | Real links and buttons, focus-ring rules (section 3.8), `role=status`, section labels | Real elements too, but the empty server output hides the gallery from assistive tech until script runs; focus after chunk insertion and "footer reachable" are ours to port | YARL's focus handling is undocumented (trap, return of focus): needs a hands-on check |
| 12 | **No-JS fallback** | The pager works; rows in CSS | **Lost**, or a second server-rendered path kept in parallel (against "one gallery", goal 1) | As A |
| 13 | **bfcache and history** | Relies on neither (R7) | React-managed history is harder to reason about; no gain | As A |
| 14 | **i18n ru/uk/fr** | Twirl with `messages.*`; no new mechanism | The client needs the strings: a JSON bundle per language or `data-*` attributes; the message files stay the source | The lightbox's own labels (close, next, zoom) default to English and need its `labels` prop in three languages |
| 15 | **CSP** | Removing `style-src 'unsafe-inline'` is step G-7 once Unite Gallery is gone | React sets styles through the CSSOM (allowed), but any server-rendered `style` attribute would still be blocked; not verified for either library. Our own bundle is same-origin and needs no hash | YARL's styling under Report-Only is to be verified |
| 16 | **Tests** | Jest (jsdom) for the vanilla modules, Playwright for voting, Back and paging in three engines (`browser-test/`) | Jest plus Vitest or React Testing Library (the April branches show the setup): two stacks; the Playwright DOM contract changes | A plus a few Playwright specs |
| 17 | **Build pipeline** | No npm build of application code today: `jsTest` runs Jest on plain files in `public/javascripts`, `browser-test` runs Playwright; scripts are served as they are | Needs a bundler (Vite or esbuild) producing `public/` assets: the April spec's pattern or sbt-web; Node in CI, packaging and every developer's machine; Windows `npm.cmd` quirks (`jsTest` already handles them) | The same bundler, for one component |
| 18 | **Effort and risk** | 28-33 days; decided | 45-60 days plus A's server half; high | A plus 8-12 days; medium |

## 5. Options

### Option A: #129 as planned (recommended)

Own tiles, CSS rows, server-rendered Twirl, Pages and Continuous, vanilla JS. No change from the
accepted plan. 28-33 days; no new toolchain; no-JS works; one DOM contract for the existing
Playwright specs.

### Option B: a full React gallery with both libraries

What it would be: a Vite bundle mounted into a thin Twirl page; a JSON API for tiles and chunks
(with the own-votes rules re-applied per field); react-photo-album Rows plus `InfiniteScroll`
for the grid; YARL for the preview; React components for votes, stars, criteria and chips; i18n
through a JSON bundle.

What it buys: a ready layout engine and lightbox, the zoom plugin, a component model for tiles.

What it costs, beyond section 4:

- The **JSON tile API** is a new security surface; today the same decisions are made while
  rendering HTML.
- **No-JS is lost** (#129 goal 4), or both renderers are kept.
- #129's work on exact sizing (ratio classes, caption-aware width, the 247 px cap, a short last
  row, Q14 "no view keeps a different look") would be redone against a library whose row
  algorithm differs.
- The first production React build: Node in release packaging (`packageDebSystemd` and the
  others), the release deploy-test rule, dependency review, and an SSR decision (none, or a Node
  service).
- Estimate 45-60 days. The server half of #129 (about 15-18 days: keyset, size schemes,
  prefetch, cookie and account setting, cleanup) is needed anyway, so the most B saves is the
  front-end half of A, and B's front end is larger, not smaller.

Verdict: higher cost and risk, and it gives up a decided requirement. Not recommended.

### Option C: hybrid

- **C1. YARL as an island on the server-rendered page, after A.** A small bundle (React, YARL
  core and Zoom, about 85 KB gzipped) loaded only on the gallery pages; the tiles stay Twirl
  links; a click or a key opens the lightbox over the page (progressive enhancement: without
  script, or with Ctrl-click, the link goes to the large view). Slides come from tile `data-*`
  attributes (the 960/1280 `srcset`, title, large-view URL, vote state). Closing sets
  `#file-<pageId>` through the same `atUrl` logic so Back and the mark (Q26) behave the same. A
  vote bar in the footer slot posts to the same endpoint with the CSRF header. This **reopens
  Q2** (decided: no lightbox; voting happens in the large view where comments and details are).
  Effort 8-12 days after A, including the one-component bundler setup (2-3 days), keys, i18n
  labels, an accessibility check and Playwright specs in three engines. Risk: two competing
  "open" paths and two key maps for the same action. Worth it only if jurors ask for a quick
  look without leaving the page; the large view is one click away and already has voting,
  comments and the criteria.
- **C2. react-photo-album for layout only.** Justified rows from the file's aspect ratio are what
  #129 already does in about 30 lines of CSS (flex-wrap and ratio classes), with no script and
  no layout shift; the library measures the container in script, so tiles reflow after load, and
  its server output is empty. It adds React to the page for no gain. Not recommended.

## 6. What of #129 would change

| Option | Sections of #129 that change |
|---|---|
| A | None |
| B | Goals 1, 4, 6, 7; sections 3.1 (tile partial), 3.2 (rows), 3.3 (lazy loading), 3.6 (chunks become JSON), 3.7, 3.8, 3.9 (votes), 3.10 (CSP), 3.11, 4 (a JSON API), 5 (steps G-1 to G-5 replaced), 6 (test plan), R1-R3, R7, R12. Sections 3.4, 3.5, the proxy, cache, prefetch, account setting and keyset logic stay |
| C1 | The non-goal "No lightbox or quick preview" (Q2), section 3.8 (keys, focus), a new step after G-7 ("G-8: lightbox island"), and added CSP and test-plan items. Everything else stays |
| C2 | Section 3.2 and the CSS rows (step G-1) |

## 7. Limits of this investigation

- The effort figures for B and C are estimates from reading the code and the plan, not
  measurements; their error is wider than A's.
- YARL's keyboard, focus and ARIA behaviour is not described in the documentation pages read
  here, and no browser test was run: accessibility is unverified.
- Whether `style-src` without `'unsafe-inline'` works with either library was not checked.
- No gallery prototype was built; only bundle sizes were measured, with trivial apps.

## 8. Open questions for the maintainer

1. **Confirm A.** Is the answer to "investigate a React alternative" that #129 stays as decided
   and nothing here changes it?
2. **Quick preview.** Do jurors or organizers want a preview over the gallery at all? Q2 said no
   on 2026-11-19 because the large view votes. Has anything changed (for example mobile jurors
   asking for zoom)? If yes, C1 is a follow-up after #129; if no, drop it.
3. **If C1: scope.** Zoom only (the cheapest), or zoom plus voting in the footer? Voting in a
   second place doubles the keyboard map; zoom only keeps the large view as the one place to vote.
4. **If C1: a first production npm build.** Is a bundler (Vite or esbuild) in the release build
   acceptable for one component, following the April `ui/` pattern (build, copy to `public/`, a
   test task beside `jsTest`)? Or must application code stay plain scripts?
5. **The April React grid.** `react` and `react-custom-filter` are a contests admin grid blocked
   on an unserved, unauthenticated API. Keep them, or close them as superseded? Closing removes
   the only `ui/` precedent.
6. **No-JS.** Is "the pager works without script" a firm requirement (it rules out B), or a
   nicety? The decision on B turns on this.
7. **An accessibility spike on YARL.** Should about an hour of hands-on checking (focus trap,
   return of focus, a screen reader, Escape and arrows alongside our keys) be done now, so that
   C1 is a known quantity later, or only if Q2 is reopened?
8. **Server-side rendering.** Is any Node process in production (for React SSR) acceptable? If
   not, react-photo-album's SSR options are out for good, which is the main reason it does not
   fit.
