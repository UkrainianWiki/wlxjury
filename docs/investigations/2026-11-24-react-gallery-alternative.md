# Adopting React in the jury tool, and what it means for the gallery (investigation, 2026-11-24)

**Question.** The maintainer first asked to investigate a gallery built with React,
[react-photo-album](https://react-photo-album.com/) and
[yet-another-react-lightbox](https://yet-another-react-lightbox.com/) (YARL). The question is now
wider: a UI developer wants to build React screens, and GraphQL (draft PR #135,
`docs/plans/2026-11-24-graphql-sangria.md`) is the cheap API for them. So this document asks first
**how React fits this Play application**, recommends a first screen, and then re-evaluates the
gallery (plan [own-gallery-tiles](../plans/2026-11-19-own-gallery-tiles.md), PR #129, not yet on
master when this was written) as a later React target.

**The wider question** (would a SPA improve the UI; htmx, Vue with Wikimedia Codex, Lit and Alpine as alternatives; a measured prototype of the large view's vote without a reload) is in the companion [UI approach](2026-11-24-ui-approach.md). Its conclusion: no SPA; htmx 4 for the large view's vote; islands for the UI developer's new screens, with Vue plus Codex as a candidate beside React.

**Status:** investigation only; nothing in the application changes. Nothing was run in the
application (no sbt, fixture server or browser). The only measurement is a throwaway bundle-size
build in a scratch directory outside the repository (section 6.2). Effort figures are estimates
from reading the code and plans.

## Summary and recommendation

1. **Adopt React as islands on server-rendered Twirl pages, one entry per screen.** A React
   screen is an ordinary Play route and Twirl page (layout, navbar, theme, the route's
   `Secured` permission check, the CSRF token and the translated texts rendered by the
   server) with one mount point and one script. Not a SPA under `/ui`, and not a full SPA.
   Authorization, redirects and `returnTo` stay in Play; the browser calls the API on the same
   origin with the session cookie and the `Csrf-Token` header, as the gallery's votes do.
2. **The build is one new sbt task, not a new pipeline.** Vite builds into `public/`; the
   existing `sbt-digest` stage (`pipelineStages := Seq(digest)`) already gives every packaged
   asset a content-hashed name, so Vite needs no hashing of its own. The release ZIP carries the
   built files; production needs no Node. CI already has Node 22 (browser tests, `jsTest`).
   Estimate for the platform: **5-7 days** (build task, dev loop, `uiTest` in `sbt test`, CSP,
   the translated-texts mechanism, one Playwright smoke spec, docs).
3. **First screen: the root contests grid** (the `react` branch's screen), on the existing REST
   `/api/contests` now and moved to GraphQL when #135's slice 1 (foundation, `me`, `contests`, `contest`) lands. It is
   low-risk (root only; no juror sees it), useful (contest administration without a form per
   contest) and it exercises every integration point: session, CSRF, texts, CSP, build, tests.
   **Use the parked `react` and `react-custom-filter` branches as a skeleton, not as the base**:
   keep their Vite, TypeScript and Vitest setup and test style; drop MUI and MUI X (section 3.3:
   Emotion's injected styles fight #129's planned CSP step G-7, and the bundle is large; master's CSP would allow them today). Estimate for the
   screen on top of the platform: 3-5 days. A good second screen is round statistics for organizers on
   GraphQL, using `roundStat` from #135's slice 2, where GraphQL's query shape pays off.
4. **The gallery is a plausible later React target, not a first one.** #129's server half
   (keyset continuation, size schemes, prefetch, the window-size cookie, own-votes rules) serves
   a React gallery unchanged (section 5.3); only the front-end half would change. The answer on
   the gallery stays: **build #129 as decided**, then reconsider once the UI developer has shipped
   two screens and the platform has proved itself. A React gallery first would put the most
   voting-critical, no-JS-sensitive page on an unproven toolchain.
5. **Prerendering** (section 7) matters for the gallery (no-JS, first paint), little for admin
   screens. In-JVM GraalJS is possible on a stock JDK but its cost is unmeasured and production
   runs JDK 17; a better fit is a Twirl no-JS grid that React takes over. No spike now.

| What | Effort (estimate) |
|---|---|
| React platform (build, dev loop, tests, CSP, texts, docs) | 5-7 days |
| First screen: root contests grid | 3-5 days after the platform |
| GraphQL part of the first two screens: #135 slices 1-2 (not React work) | about 4-5.5 days (#135's figure) |
| #129 as planned (decided) | 28-33 days |
| Gallery in React instead of #129's front end | 45-60 days in all, with #129's server half (about 15-18 days) kept |
| #129 plus a YARL lightbox island | #129 plus 8-12 days (reopens Q2) |

## 1. What was read

- The #129 plan, the GraphQL plan (PR #135), `docs/api.md`, `conf/routes`, `build.sbt` (the
  release ZIP, `pipelineStages`, `jsTest`), the CSP in `conf/application.conf`, the CI workflows.
- The branches `react`, `react-custom-filter`, `104-port-gallery-to-react` and issue #104.
- The two libraries' documentation, the npm registry and the GitHub repositories (API).

## 2. Earlier React attempts in this repository

All three branches were ported here on 2026-11-23 from the private archive as single squashed
commits; the original dates are in the commit messages.

| Branch | What it is | Size | Status |
|---|---|---|---|
| `104-port-gallery-to-react` (22 commits, June 2020 base) | A create-react-app with `react-photo-gallery` (the older predecessor of react-photo-album) showing nine **hard-coded sample photos**; sbt hooks running `npm start` in dev mode (`ui-build.sbt`, `FrontendRunHook.scala`) | 20 files, +21,334 (almost all lockfiles) | A spike; never read real data. Issue #104 is an unrelated closed bug ("image upside down"); the branch only borrowed the number |
| `react` (21 commits, April 2026) | `ui/`: Vite, React 19, TypeScript, MUI and MUI X Data Grid; a **contests admin grid** over `/api/contests`; Vitest; a spec and plan in `docs/superpowers/` | 20 files, +5,616 | In April the Tapir API was unserved and unauthenticated, so it could not go live. That is no longer true: on master `/api` is served with a personal API token or the session cookie (`ApiRouter`, `docs/api.md`; contests for root only). Parked, not blocked |
| `react-custom-filter` | The same grid with cell filters | its own commit: 4 files, +936/-8 (24 files, about +6,552 with `react`) | Parked with `react` |

What they give: a working `ui/` skeleton (Vite, Vitest, React Testing Library, a dev proxy to Play
on :9000) and a build sketch (`npm run build` copied into `public/ui/`, routes `/ui` and
`/ui/*file`, a `uiTest` task beside `jsTest`). What they do not give: any integration with Play's
session, CSRF, messages, layout or CSP, or any screen a user sees.

## 3. How React screens fit this Play application

### 3.1 Integration shape: islands, a `/ui` SPA, or a full SPA

| | Islands on Twirl pages (recommended) | SPA routes under a prefix (`/ui`, the `react` branch) | Full SPA |
|---|---|---|---|
| Routing and authorization | Play routes and `Secured` builders, as every page today; denials, `returnTo`, 403 and 404 pages unchanged | The SPA has its own router; every `/ui/*` request returns `index.html`; the API decides access and the shell must handle 401 and 403 itself | The same, for the whole tool |
| Layout, navbar, theme, language, contest switcher | Inherited from the Twirl layout (sticky top bar, `theme-switch.js`, contest menu) | Rebuilt in React, or the screens look foreign | All rebuilt |
| Session and CSRF | Same-origin cookie; the page carries `data-csrf-token`, writes send `Csrf-Token` | Same cookie, but the token must be handed to the shell (a Twirl shell, or an endpoint) | Same |
| Deep links, Back | Plain page loads | A client router; scroll and history are the SPA's | Same |
| Adoption cost | Lowest: one screen at a time, others untouched | Medium: a shell and a second navigation world | Highest: every page |
| Fit for a UI developer | A page is a route plus a component; a small controller on the Scala side | Closer to a usual React workflow | The most freedom, the most risk |

Recommendation: islands. The tool's value is in its server-enforced rules (own votes, roles per
contest, `Secured.inContest`); the closer a screen stays to a server-rendered page, the fewer
places those rules must be re-checked. A screen can still be an app inside its island, with
client-side state and sub-views (a `?tab=` or a hash).

### 3.2 Build and release

- **Vite entries.** One entry per screen (`ui/src/screens/<name>.tsx`), built to `public/ui/`
  with stable names (`<name>.js`, `<name>.css`) and a shared vendor chunk. Twirl pages
  reference them with `routes.Assets.versioned`.
- **Cache-busting.** Already solved: `build.sbt` runs `sbt-digest` over the packaged assets
  (`<md5>-<name>` copies, served by `routes.Assets.versioned`); `sbt run` and tests serve plain
  names. No second hashing scheme.
- **sbt integration, not sbt-web's JS pipeline.** A task `uiBuild`, like `jsTest` (`npm.cmd` on
  Windows, `npm ci` when `node_modules/.package-lock.json` is stale, then `npm run build`), runs
  before assets are packaged, so `sbt dist` and `sbt run` both have the files. The built files
  are git-ignored. The release ZIP carries them inside the application jar's `public/`;
  production needs no Node.
- **CI.** Node 22.23.4 is already set up, pinned and cached, in `ci.yml` and `browser.yml`. The
  new `ui/package-lock.json` joins the dependency rules (pinned, old enough, lockfile checked).
- **Release tests.** The real-ZIP restore tests are about the ZIP and the scripts, not its
  contents; whether the ZIP's file manifest needs a line for the new assets is to be checked when
  the task is added.
- **Developer loop.** With islands the HTML comes from Play, so the developer opens the Vite
  dev server on :5173 and it proxies **everything to Play on :9000 except the script, style
  and HMR paths of the screen under development** (which Vite serves itself, and which the dev
  Twirl page references): one origin, so the session cookie and CSRF work as in production, and no
  CORS. Fallback: `vite build --watch` into `public/ui/` with `sbt ~run` (no proxy, slower
  refresh). The `react` branch's `vite.config.ts` and `DEV.md` describe a simpler proxy of `/api`
  only, which suits an SPA, not islands. The developer runs the app on #135's fixture DB (the
  Gatling dump: about 38k images, 20 jurors, a binary and a rated round) and signs in with its
  fixture accounts (juror, organizer, admin).
- **Generated types.** `graphql-codegen` runs inside `uiBuild` and in CI, from #135's SDL
  (`conf/graphql/schema.graphql`) and the `.graphql` documents in `ui/`; the generated files are
  not committed. The codegen packages join the `ui/` dependency rules.

### 3.3 Authentication, CSRF, texts, CSP, tests, API

- **Authentication.** The session cookie, same origin, through `ApiAuth.caller`; a screen never
  holds a token. GraphQL (PR #135) mounts at `POST /api/graphql` behind the same caller, with the
  same Host check and CSRF rule: **every session POST needs the `Csrf-Token` header** (the CSRF
  filter, `modules.CsrfModule`; GraphQL queries are POSTs too, so queries need it as well); a
  Bearer request without a session cookie does not. Token callers are query-only there.
- **CSRF.** The Twirl page renders `data-csrf-token` on the mount element, as the gallery does for
  votes; the client sends it as `Csrf-Token` on every request to the API (urql's fetch options,
  one place). As in #135, no shell page: each screen's own page carries it on the mount element.
- **Texts (i18n).** Messages stay in `conf/messages.{en,fr,ru,uk}`. The Twirl page puts the
  keys the screen needs, translated in the user's language, into a `data-` attribute or an inline
  JSON (a Scala list of keys per screen): no build-time export, no second source of truth, no
  client language detection. A test checks that every key of a screen exists in all four files.
  Dates and numbers use the browser's `Intl` with the page's `lang`.
- **CSP.** `script-src 'self'` plus two hashes: a bundle served from this host needs no change.
  master's `style-src` is `'self' 'unsafe-inline'` (`conf/application.conf`), so **MUI would
  work today**; the objection is #129's planned step G-7 (on PR #129, which may not be on master
  yet: a Report-Only release, then removal of `'unsafe-inline'`), and the look. Libraries that inject styles as `<style>` elements (**Emotion, hence MUI**) or as
  style attributes in server HTML fight that step; styles set through the CSSOM (React's `style`
  prop) are allowed. So I recommend no MUI; use Bootstrap 5's classes already on the page, or CSS modules,
  and a headless table helper (for example TanStack Table) where a grid is needed. YARL and
  react-photo-album set sizes through the `style` prop and CSS variables; not verified under a
  strict policy. `connect-src 'self'` already allows the same-origin API.
- **Tests.** Vitest and React Testing Library for components (as the April branches), a `uiTest`
  task beside `jsTest` in `Test / test`, and the existing Playwright suite (`browser-test/`, three
  engines) for one flow per screen against the real app. Jest stays for the plain scripts. Two unit
  runners (Jest, Vitest) is a cost; Jest can run TypeScript, so one runner is worth a short check.
- **API.** Reads and later writes go through PR #135's endpoint. Until its first PR lands, a
  screen can use REST `/api` (contests exist; rounds and the rest do not). Plain `fetch` with a
  typed query string is enough for the first screen; the default for GraphQL screens is **urql**
  with **graphql-codegen** (typed documents), as #135's section 6 proposes (the UI developer may
  swap it; Apollo is larger and its normalized cache pays only with many cross-screen updates).

## 4. First screen: recommendation

| Screen | Users | Risk | Data source | Verdict |
|---|---|---|---|---|
| **Root contests grid** (the `react` branch) | root only | very low | REST `/api/contests` now, GraphQL later | **First**: exercises every integration point; nobody else sees it |
| Round statistics (read-only) | organizers, admins | low | GraphQL `roundStat` (#135 slice 2) | Second; where GraphQL's shape pays off |
| Users page with filters | admins | medium | GraphQL users and the email rule | Later |
| A juror screen (gallery, large view) | jurors | high | votes, own-votes rules, CSRF, keyboard | Last |

The grid is a modest screen: useful for root, but its value is proving the platform. If the
maintainer wants a first screen that more people use, round statistics is better, after the
#135's slice 2.

**Base.** Take from `react` and `react-custom-filter`: the `ui/` layout, `vite.config.ts` with the
dev proxy, `tsconfig.json`, the Vitest setup and test style, the contests API wrapper. Do not take
MUI and MUI X (CSP, size, a look unlike the rest of the tool), nor `public/ui` and the `/ui`
routes (replaced by a Twirl page). Close the parked branches when the first screen's PR merges.

## 5. The gallery under adoption

### 5.1 The libraries

**react-photo-album 3.6.2** (MIT, 2026-10-17; 73 npm versions; one author; 1 open issue). Rows,
Columns and Masonry layouts; per-photo `srcSet` arrays and a `sizes` prop; render props for the
container, link, image or whole photo; an `InfiniteScroll` wrapper (`fetch` returns photos or
`null`); no windowing. **Its server output is empty markup by default**; its prerendering
options (`defaultContainerWidth`, a skeleton, an `SSR` component with CSS container queries, a
Server Component) assume a React server render. Rows are justified to the container width,
measured in script.

**yet-another-react-lightbox 3.33.1** (MIT, 2026-11-14; 157 npm versions; one author; 1 open
issue). Plugins: Captions, Counter, Download, Fullscreen, Inline, Rotate, Share, Slideshow,
Thumbnails, Video, Zoom. A client component in a portal; `render` slots for header, footer and
controls; controlled `index` and `on.view`. The documentation read does not describe rebinding
keys or a focus trap, and `controller.aria` is off by default: accessibility needs a hands-on check.

Both have peer React 18 or 19, no runtime dependencies, and active maintenance.

### 5.2 Re-evaluating A, B and C with a UI developer

The earlier verdict against B (a full React gallery now) rested on three costs: a first
production npm build, a JSON tile API that re-applies the own-votes rules, and no-JS. The UI
developer and the platform of section 3 remove the first (shared by every screen) and shrink the
second (the GraphQL work builds the visibility rules for a round's votes anyway, section 5.3). They
do not remove the third, nor the rewrite of the tile, stars, by-rate and criteria rules.

| Option | What it is | Effort | Now |
|---|---|---|---|
| **A** | #129 as decided: Twirl tiles, CSS rows, vanilla JS, Pages and Continuous | 28-33 days (decided) | **Build now.** Independent of the platform; no-JS works |
| **B** | A React gallery with both libraries, instead of #129's front end | 45-60 days in all (#129's server half, 15-18 days, kept) | **Not now.** Reconsider after two shipped screens, with an answer on prerendering (section 7) |
| **C1** | A, plus a YARL lightbox island (reopens Q2) | A plus 8-12 days (the platform part, 2-3 days of it, now exists) | Only if jurors want a quick preview |
| **C2** | A, with react-photo-album only for the rows | no net gain | Not worth it: React for about 30 lines of CSS |

The platform and first screen can run in parallel with #129, in different files (`ui/`, a new
Twirl page and controller, against #129's gallery partial, `tile-votes.js`, the cache and the
account setting). They meet in the layout, the CSP and `build.sbt`.

### 5.3 Does #129's server half serve a React gallery? Yes

| #129 server element | How it serves a React gallery |
|---|---|
| Keyset continuation (cursor in the URL: `after`, `k` values) | The same cursor goes in a GraphQL `images(roundId, after, ...)` page or a REST chunk and the client passes it back. The reason (votes change the filter) is front-end independent |
| Own-votes rules (`viewerRounds`, `mayViewRound`, `viewAs`, `canViewOrgInfo`, 404 for non-jurors) | Reused by the GraphQL resolvers (PR #135 section 4.5 says so). A JSON tile API then has the same decision point as the HTML one, which removes most of the "second place to get a rule wrong" risk |
| Thumbnail size schemes, 960/1280 prefetch, proxy and cache | The client gets a `srcset` or a list of widths per image; react-photo-album's `srcSet` array is the same data. Commons rate limits are the server's concern either way |
| Window-size cookie and `?n` | The server still sizes pages and chunks; a React client may also send `n`. Page links keep `n` |
| Account setting Pages vs Continuous (CSRF POST) | A preference, independent of the front end |
| `GalleryService` and the vote endpoint with CSRF | Unchanged; a React tile posts to the same path with `Csrf-Token` |

So #129 is not wasted if the gallery later goes React: **its server half, the larger and riskier
half, is reusable almost completely.**

### 5.4 What would change in #129 if the gallery later goes React

| #129 section | Change |
|---|---|
| Goals 1, 4, 6, 7; 3.1 tile partial; 3.2 CSS rows; 3.3 lazy loading | The tile partial becomes a component (and stays a Twirl version if prerendering is chosen, section 7); rows by react-photo-album or kept in CSS |
| 3.6 chunks | HTML sections become JSON or GraphQL pages; the cursor and `n` logic stay |
| 3.7 position and mark (Q26) | The scroll and mark wait for React to render the tile; `file-anchor.js`'s rules move into the component |
| 3.8 accessibility, 3.9 votes (`tile-votes.js`) | Rewritten as components; the roles, button names and focus rules stay as the specification |
| 3.10 CSP | Same step G-7; the bundle adds script, not inline style |
| 4 server and API; 5 steps G-1..G-5; 6 tests | New JSON endpoint steps; the Playwright specs keep their intent and change selectors |
| 3.4, 3.5, proxy, cache, prefetch, account setting, keyset logic | None |

### 5.5 Gallery comparison, requirement by requirement

A = #129. B = a React gallery. C1 = A plus a YARL island.

| # | Requirement | A | B | C1 |
|---|---|---|---|---|
| 1 | Binary voting in the grid | Server-rendered buttons, one delegated handler | React component, same endpoint | As A |
| 2 | Stars, criteria, chips, rank, deleted badge | Twirl partial options; raty | Rewrite of `galleryByRate.scala.html` and `showRateGalery` in JSX | As A |
| 3 | Voting in a preview | None (Q2): the large view votes | A YARL footer slot is feasible | A footer vote bar, same endpoint |
| 4 | Keyboard voting (by paths; `LargeViewControllerSpec`) | Unchanged in the large view | A key layer on the grid is new; YARL owns arrows and Escape | A second key map to keep in step |
| 5 | Own-votes rules | Decided while rendering | Decided in the resolvers (reused from `GalleryService`) | As A |
| 6 | CSRF | `Csrf-Token` from `data-csrf-token` | Same | Same |
| 7 | Deep links, Back, `file-anchor` and mark (Q26) | Tile is a link; the script scrolls if out of view | The target tile may not be rendered yet: the case becomes the normal one | The lightbox index must map to `#file-<pageId>` on close |
| 8 | Pages vs Continuous, `?n`, keyset | HTML chunks | JSON chunks, same cursor | As A |
| 9 | 38k-image rounds, memory | Cap measured (G-0) | React state per tile, JS layout over all loaded tiles, no windowing in either library: a lower cap, unmeasured | As A |
| 10 | Thumbnail sizes, proxy, Commons rate limits | Server prefetch | Same server work | Same |
| 11 | Accessibility | Real links and buttons, focus rules | Real elements, but empty server output until script runs | YARL's focus handling needs a check |
| 12 | No-JS | The pager works | Lost, or a prerender (section 7) | As A |
| 13 | bfcache and history | Relies on neither | Client history is harder to reason about | As A |
| 14 | i18n ru/uk/fr | `messages.*` | The page's key list (section 3.3) | YARL's `labels` prop in three languages |
| 15 | CSP | Step G-7 | CSSOM styles allowed; verify the libraries | Verify under Report-Only |
| 16 | Tests | Jest and Playwright | Vitest plus Playwright | A plus a few specs |
| 17 | Build | No npm build for app code | The platform of section 3.2 | The same |

## 6. Libraries: licence, size, maintenance

### 6.1 Licence and supply chain

Both MIT, no runtime dependencies. React and react-dom come with them, and a Vite and TypeScript
tree; the dependency rules apply to `ui/`. Each library has a single maintainer, with releases in
the last month; Unite Gallery, which we vendor and patch, has none.

### 6.2 Bundle sizes (scratch build, esbuild, minified, production React)

| Bundle | Minified | Gzipped |
|---|---|---|
| React 19.3 and react-dom, a trivial app | 223 KB | 69 KB |
| plus react-photo-album Rows | 231 KB | 72 KB |
| plus YARL core | 253 KB | 80 KB |
| plus YARL with Zoom, Captions, Thumbnails, Fullscreen | 278 KB | 88 KB |
| Today's `unitegallery.js` | 588 KB | 122 KB |

The React runtime (69 KB gzipped) is paid once and cached across screens if every entry shares
one vendor chunk. Size decides nothing for a login-only tool.

## 7. Prerendering

It matters for the **gallery** (the no-JS pager and the first paint of a page of tiles) and
little else. Admin screens are behind a login, small, and fine with a placeholder followed by data.

| Approach | What it is | Fit |
|---|---|---|
| **Twirl no-JS grid, then React takes over** | The server renders the tiles as plain HTML (the #129 partial); React replaces or hydrates the container on load | Best fit: the no-JS path and first paint come from the partial #129 builds anyway, and React is an enhancement. Cost: two renderers for one tile, kept in step by tests |
| **In-JVM GraalJS** | Run the bundle's server entry (`renderToString`) in a GraalJS context inside Play (the polyglot API and the JS language on a standard JDK), with a pool of contexts because a context is not thread-safe | Feasible, unmeasured. Open points: **the JDK.** Production and the build are JDK 17 (`docs/deployment.md`, `-release:17` in `build.sbt`). I checked the class files of `polyglot`, `truffle-api` and `js-language` on Maven Central (lines 23.1, 24.1, 24.2 and 25.0): all target Java 17, so loading on JDK 17 is not ruled out by them, but the minimum JDK the 25.x lines officially support was **not verified** (the release notes must be read). The Graal SDK 25.1.x changelog says GraalVM 25.1 "remains backwards compatible down to JDK 21, but runs without runtime compilation", so **25.1 and later most likely need JDK 21**, while 23.1 to 25.0 target Java 17 bytecode: on JDK 17 only those older lines, or a JDK upgrade first, are possible (quoted from the changelog as relayed; not re-checked here). On a stock JDK there is no Graal compiler, so Truffle runs in interpreter mode (with a warning): rendering hundreds of tiles may be much slower than in Node; each pooled context holds the bundle in memory; the JDK 17 flags must be checked; renders must run on the blocking pool; hydration needs byte-identical markup |
| **A Node render service** | A second process, `renderToString` over HTTP | Not now: a new runtime to deploy, monitor and patch |
| **Build-time prerender** | Render at build | **Not applicable**: tiles depend on the user, the round and their votes |
| **react-photo-album's own SSR options** | Skeleton, `defaultContainerWidth`, container-query SSR component, Server Component | All need a React server render (one of the above) |

No spike now. If the maintainer chooses a React gallery, a one-day spike should first measure a
GraalJS render of 24 and 80 tiles on the production JDK, cold and warm, and the memory of a pool
of 2-4 contexts.

## 8. Limits of this investigation

- Effort figures for B, C and the platform are estimates; A's is the plan's own.
- YARL's keyboard, focus and ARIA behaviour was not tested; neither library was checked under
  `style-src` without `'unsafe-inline'`.
- GraalJS's cost is unmeasured, and its supported JDK range is not confirmed (section 7).
- The GraphQL plan is a plan (draft PR #135): its slice 1, the `/api/graphql` action and the
  query-only rule for tokens do not exist yet, so the first screen starts on REST.
- No gallery prototype was built.

## 9. Open questions for the maintainer

1. **Integration shape.** Islands on Twirl pages (recommended), or an SPA under `/ui` as the
   `react` branch did? Islands keep routes and authorization in Play.
2. **First screen.** The root contests grid (low risk, small audience, can start on REST), or
   wait for #135's slice 2 and start with round statistics for organizers?
3. **UI kit.** No MUI; Bootstrap 5 classes with CSS modules and a headless table, because of
   CSP step G-7 and the look. Does the UI developer agree, or is a component kit a requirement
   (then the CSP plan changes)?
4. **Build in the release.** Is a Vite step in `sbt dist` and `sbt run` acceptable, with Node
   needed on developer and CI machines only?
5. **Texts.** Keys rendered by the server into the page (recommended) or a JSON export per
   language? The first keeps one source of truth.
6. **Test runner.** Vitest for `ui/` beside Jest, or one runner (Jest with TypeScript)?
7. **The gallery.** Confirm: build #129 now, revisit React for the gallery after two shipped
   screens. If the gallery should go React earlier, is no-JS a firm requirement (then the
   prerender spike of section 7 comes first)?
8. **Lightbox.** Is a quick preview over the gallery wanted at all (Q2 said no)? If so, C1 is a
   small follow-up now that the platform exists.
9. **Juror slice order** (the same question as #135's Q8: answer it once). Should the juror-facing slice (round images and
   the vote, #135 slices 3-4) come before or after the admin and organizer screens? Recommendation: **after**:
   the admin and organizer screens carry less risk, prove the platform, and let the gallery
   decision (#129 or React) settle first.
10. **The parked branches.** Close `react`, `react-custom-filter` and `104-port-gallery-to-react`
   when the first screen's PR merges, keeping only the skeleton pieces named in section 4?
