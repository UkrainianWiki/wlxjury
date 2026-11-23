# Which UI approach for this tool? SPA, htmx, islands, Codex (investigation, 2026-11-24)

Companion to [React adoption and the gallery](2026-11-24-react-gallery-alternative.md) (PR #136).
The maintainer's question: **would a single-page application improve the UI, and what
alternatives fit this application?** Related plans: [own gallery tiles](../plans/2026-11-19-own-gallery-tiles.md)
(PR #129) and the GraphQL plan (PR #135).

**Status:** investigation. Facts about the libraries come from the npm registry, GitHub and the
projects' documentation (2026-11-24). The numbers in section 5 come from a throwaway mock in a
scratch directory (not in this repository): it imitates the large view's vote and was run in a
real Chromium. The application itself was not run (a real run would need the database
container and the shared build slot); where a figure is for the real application it is marked as an
estimate.

## Summary and recommendation

1. **Not a full SPA, in any framework.** What makes this tool correct (own votes, roles per
   contest, `Secured`, CSRF, `file-anchor.js` position, a no-JS gallery, `returnTo`) lives on the
   server and works through plain page loads. A SPA moves routing, history, scroll and the
   rendering of those rules to the browser, for a gain (no page reload) that the large view can
   get much more cheaply (section 5).
2. **Add htmx for "no full reload" on the existing server-rendered pages, starting with the large
   view's vote.** Prototype: the vote and the move to the next file ran in about half the time of
   the full page load (the next image prefetched), Back worked, keyboard voting worked, **no
   server change** (the vote already redirects to the next file's page), htmx 4 needs **no
   style-src change** (htmx 2 needs one under #129's planned strict `style-src`), **provided the
   application never uses `hx-on`, `js:` values or trigger filters**, which htmx 4 builds with
   `new Function` (section 3.2). Cost is not in htmx but in
   the page's scripts that initialise once at load (section 5.3): about four modules. Estimate
   for the large view: 2-4 days.
3. **htmx version: start on 4.0.x, pinned and vendored.** 4.0.0 was released 2026-08-28 (stable, not
   a prerelease), 4.0.2 on 2026-11-18; it is smaller (13.4 KB gzipped against 16.8 KB), needs no
   inline style, and does not keep page snapshots in `localStorage`. Caveat: young (three months,
   three releases) and npm's `latest` tag still points to 2.0.11 (4.x is on `next`); 2.x is still
   maintained (2.0.11, GitHub release 2026-11-01). Section 3.
4. **For the UI developer's new screens (organizer statistics, admin forms, contests): islands on
   Twirl pages, framework to be chosen with them.** The platform (build, texts, CSRF on the mount
   element, GraphQL, CSP) is framework-independent. **Vue 3 with Wikimedia Codex is a serious
   candidate, not only React**: Codex is the Wikimedia design system (Vue 3 components, design
   tokens, CSS-only components usable on server-rendered pages), its licence (GPL-2.0-or-later) is
   compatible with this repository's GPL-3.0-or-later, and Montage, the closest comparable tool,
   uses it. React has no official Codex version, so with React only the design tokens would carry
   over. The deciding input is the UI developer's skills and the maintainer's wish for a
   Wikimedia look: ask them (Q1, Q2).
5. **The gallery stays as in the #136 recommendation** (#129 first). htmx does not replace it:
   chunks are already server-rendered HTML in #129's plan, which htmx can load as fragments if
   a later step wants it.

| What | Effort (estimate) |
|---|---|
| htmx on the large view (vote, next file, keys, Back, tests) | 2-4 days |
| htmx for a few more forms or filters, one by one | 0.5-1 day each |
| Platform for islands (build, texts, CSRF, dev loop) | 5-7 days (from #136) |
| A large view rewritten as an island (any framework) | 15-25 days, plus #135 slices 3 and 4 (5 days) |
| Codex CSS and tokens on existing Twirl pages (a re-skin) | not estimated: a visual decision first (Q4) |

## 1. What is being compared

Requirements of this application, used in the table of section 2:

- jurors voting in the gallery and the large view, with keyboard voting by paths and CSRF;
- organizer statistics and admin round and contest forms;
- one maintainer and one React UI developer;
- server-side authorization and own-votes rules (`Secured`, `GalleryService`);
- messages in en/ru/uk/fr from Play;
- CSP, including #129's planned step G-7 (no `'unsafe-inline'` in `style-src`);
- Back, bfcache and scroll position (`file-anchor.js`, the last-viewed mark);
- a gallery that works without script;
- the Wikimedia audience (accessibility, languages, a familiar look).

Options: (a) full SPA (React or Vue); (b) htmx; Unpoly and Turbo as siblings; (c) plain JS and Alpine
islands (today, plus #129); (d) Vue 3 islands with Codex; (e) React islands (#136's recommendation);
(f) web components (Lit).

## 2. Requirement by requirement

| Requirement | (a) Full SPA | (b) htmx | (c) Plain JS / Alpine | (d) Vue + Codex islands | (e) React islands | (f) Lit web components |
|---|---|---|---|---|---|---|
| Voting in gallery and large view, keys, CSRF | Rewritten as components; the key map and CSRF header move to JS | Pages and keys stay; the vote becomes a swap (section 5). CSRF field already in the form | Today's code; #129 keeps it | A component per screen; the large view would be a rewrite | As (d) | A component per tile; no framework state |
| Own-votes and role rules | Re-exposed through an API per field (GraphQL reuses `GalleryService`, #135) | Unchanged: the server renders what the caller may see | Unchanged | Through GraphQL for island screens only | As (d) | Unchanged if attributes come from Twirl |
| i18n en/ru/uk/fr | Client needs the strings (key list per screen, section 5 of #136) | Unchanged: Twirl and `messages.*` | Unchanged | Keys passed from Twirl; Codex messages need its `messageKeys`, and Codex has its own RTL/bidi CSS | Keys passed from Twirl | Unchanged or keys per element |
| CSP, G-7 | Libraries that inject styles are a risk (Emotion); CSSOM styles are fine | htmx 4: none; htmx 2: needs `includeIndicatorStyles` off or a nonce (measured, section 5.2) | Alpine's standard build uses `Function` (needs its CSP build); plain JS none | Vue prebuilt (SFCs compiled at build): no runtime template compiler, no `eval`; the runtime-only build has no `new Function` call sites (grep). Codex CSS is a file | React: none; CSSOM styles | Lit: shadow-DOM styles through `adoptedStyleSheets`: allowed, to verify |
| Back, bfcache, scroll, `file-anchor.js` | A client router must reproduce all; the largest risk | Boost pushes history; scroll and mark scripts must re-run after a swap | Unchanged | Same as React | A page per screen: unchanged for pages that stay Twirl | Unchanged |
| No-JS gallery | Lost or a second renderer | Kept: the pages are plain HTML; htmx only enhances | Kept | Kept for Twirl pages; island screens need JS | Same | Kept if the tile degrades to its link |
| Wikimedia audience, accessibility | Per library | Neither helps nor hinders | Today's Bootstrap | Codex: designed for it (components and icons with language and direction variants, RTL stylesheet) | Tokens only | Possible with Codex tokens |
| Maintainer plus one UI developer | Two stacks plus a SPA shell | One 13-17 KB script, almost no new concepts for the Scala side | Lowest change | Vue is a second skill for the maintainer; Codex adds a design-system upgrade cadence | React is the UI developer's stated wish | A small library, a small ecosystem |
| Build and release | Vite, a router, a store; full SPA release | **No build step** (a vendored file) | None | The platform of #136 | The platform of #136 | Bundler optional |
| Effort (estimate) for the large view's no-reload vote | 15-25 days (rewrite) | 2-4 days | 1-2 days (a fetch and a swap by hand) | As (a) for that screen | As (a) | 5-10 days |

Reading it: only (b) and (c) improve the large view cheaply and keep every server rule where it
is. (d) and (e) are for **new** screens; a SPA is the most expensive answer to a question the
large view does not ask.

## 3. htmx, htmx 4 and its siblings

### 3.1 Status of htmx 4 (checked 2026-11-24)

- **Released.** `v4.0.0` on 2026-08-28 (not a prerelease; `v4.0.0-beta6` was 2026-07-23), `v4.0.1`
  on 2026-11-01 and `v4.0.2` on 2026-11-18. The `2.x` line continues: `v2.0.11` (GitHub release
  2026-11-01, published to npm 2026-09-22; npm `latest`); npm's `next` tag is 4.0.2. Licence 0BSD, no runtime dependencies. About
  49,000 GitHub stars; the repository was pushed to on 2026-11-20.
- **Size (gzipped):** 4.0.2 is 13.4 KB (37 KB minified), 2.0.11 is 16.8 KB (52 KB).

### 3.2 What changed in 4 against 2.x (from the project's migration guide)

| Area | htmx 4 | Effect here |
|---|---|---|
| Transport | Always `fetch()`, never XHR; default timeout 60 s (2.x none); `hx-delete` no longer sends the form's inputs | Fine; nothing here relies on XHR |
| Inheritance | **Explicit**: `hx-boost` on `<body>` no longer applies to children; use `hx-boost:inherited="true"` (`:append` to extend) or `htmx.config.implicitInheritance = true` | The first thing that bites (the mock did not boost until this was set) |
| Errors | 4xx and 5xx responses **are swapped** (only 204 and 304 are not); revert with `noSwap` | Our 403 and 404 pages would be swapped in; probably what we want, but the vote's JSON refusals and CSRF errors need a decision |
| History | No page snapshots in `localStorage`; Back re-fetches the page | Better for this tool: htmx 2 stores page HTML in `localStorage`, which for a juror's page means other jurors' data and the CSRF token in browser storage. The optional `hx-history-cache` extension (a separate file, 1.7 KB gzipped) stores snapshots in `sessionStorage`: **do not load it**, for the same reason `no-store` pages are kept out of bfcache (a snapshot shows stale votes) |
| Out-of-band swaps | Run after the main swap | Minor |
| Removals and renames | `hx-disable` is now `hx-ignore`; `hx-vars`, `hx-params`, `hx-ext`, `hx-inherit`, `hx-disinherit`, `hx-history` removed; events are `htmx:phase:action` (`htmx:before:swap`); the `queue` trigger modifier is removed (use `hx-sync`); selectors with spaces need quotes | A new adoption avoids all of it |
| Extensions | A `<script>` tag loads one; `extensions` config is an allow-list; SSE, WebSocket, `hx-preload` and the history cache are **extension files in the package** (`dist/ext/hx-sse.js`, `hx-ws.js`, `hx-preload.js`, `hx-history-cache.js`), **not in `htmx.js`**; `htmx.defineExtension` is `registerExtension` | `hx-preload` (prefetch of a link) needs its own script: 0.9 KB gzipped (1.8 KB minified) on top of the core's 13.4 KB |
| CSP | `allowEval` and `allowScriptTags` removed; **`inlineStyleNonce` removed: indicator CSS uses a constructable stylesheet, so no nonce is needed**; `inlineScriptNonce` added; an `hx-csp` extension for strict policies | Measured: no style violation under `style-src 'self'` (section 5.2). But `htmx.js` 4.0.2 still builds `new Function(...)` (about line 923) for `hx-on`, `js:` values and trigger filters, and `allowEval` no longer exists to switch it off. Under our `script-src` (no `'unsafe-eval'`) any use of those three features **fails at runtime with a CSP violation**; it does not silently work. So the application must never use `hx-on`, `js:` values or trigger filters, enforced by a spec that greps `app/views` and `public/javascripts` for them; and the comment in `conf/application.conf` ("no library uses eval or new Function") must be reworded to say htmx contains the call and the policy keeps it unusable |

### 3.3 Start on 2.x or 4?

- **4.0.x** for a new adoption: the migration cost is zero, it is smaller, its style behaviour
  suits G-7 (with the `new Function` rule above) and its history behaviour suits private pages. Pin the exact version, vendor the single
  file under `public/` with the existing licence notice rules (0BSD), read the release notes on
  each update.
- **2.0.x** if the maintainer prefers the line with more mileage: most tutorials, answers and
  extensions are for 2.x; it works under the current CSP but needs `includeIndicatorStyles: false`
  (and an own CSS rule) before G-7, and its history snapshots should be turned off
  (`historyCacheSize: 0`).
- Either way the application's own code (an `hx-boost` and an after-swap hook) is small; moving
  from 2 to 4 later is a day of renaming for a few attributes. The risk of 4 is its youth.

### 3.4 Unpoly and Turbo, briefly

- **Unpoly 3.15.0** (MIT, 2026-10-26): "layers", modals, forms with validation and fragment
  updates by CSS selector, a richer model than htmx and a large unpacked package (7.7 MB with
  sources); more opinionated about history and layers. A good fit for admin forms; more to learn.
- **Turbo 8.0.23** (MIT, 2026-01-29; with Stimulus): Drive makes every link and form a
  fetch-and-swap of the body, Frames update a region, Streams push changes. Its idea is the same as
  boost; the ecosystem is Rails-centred; its last release is ten months before the others'.
- None adds something htmx 4 does not for this use; htmx is the smallest and most explicit.

## 4. Vue 3 with Wikimedia Codex, React with tokens, Lit, Alpine

### 4.1 Codex (checked in the npm package and the documentation)

- **What it is.** The Wikimedia design system: Vue 3 components, design tokens, icons and
  composables; version 2.8.0 (2026-11-13), releases every one to two months.
- **Licence.** `@wikimedia/codex` and `@wikimedia/codex-design-tokens` declare **GPL-2.0-or-later**
  (`license: "GPL-2.0+"` in the npm `package.json`; the `LICENSE` file in the package is the stock
  GPL version 2 text, so the "or later" grant comes from that field); `@wikimedia/codex-icons` is
  MIT. This repository's `LICENSE` on master is **GPL-3.0-or-later** (the README says the licence
  changed in 2026-11 from Apache-2.0), so GPL-2.0-or-later code may be used in it. **The repository
  already vendors Codex's design tokens 2.7.0 under that reading** (`NOTICE`, `docs/vendor/codex.md`,
  `licenses/GPL-2.0.txt`, `public/stylesheets/vendor/codex/`). So the open licence question is only
  the components package (and the icons, MIT), not the tokens. Not legal advice.
- **CSS-only components.** Install `@wikimedia/codex` and load `codex.style.css` once per page;
  write the component's HTML with its classes (for example `<button class="cdx-button
  cdx-button--action-progressive cdx-button--weight-primary">`). So Twirl pages can use Codex's
  look **without Vue and without script**. The RTL stylesheet is `codex.style-rtl.css`; bidirectional
  support is limited to pages that are wholly left-to-right or right-to-left. Per-component CSS
  modules exist in `dist/modules`.
- **Design tokens.** A separate package: `theme-wikimedia-ui.css` (plus root and dark-mode files for
  `prefers-color-scheme`), usable without any framework. 24 KB (6 KB gzipped), measured from the package's `theme-wikimedia-ui.css`.
- **Sizes.** Measured on the package files: `codex.style.css` is 172 KB (17 KB gzipped) in full.
  Measured in the scratch prototype build (esbuild, minified): Vue 3.5 runtime alone is 22.8 KB
  gzipped; React 19 with react-dom is 69.5 KB gzipped (with a 13-line component). **Not measured:**
  the size of a Vue app using Codex components (a quick bundle with one button was not
  trustworthy and was not run), so no figure is given.
- **React.** Codex has **no official React version** (the package and its documentation are Vue 3
  only; `@wikimedia/codex-react` does not exist on npm). Unofficial ports exist under other names; they were not
  evaluated. With React, only the tokens and the CSS-only components can be used.
- **CSP and Vue.** Codex needs Vue 3.5.13 or later. A runtime template compiler uses `new Function`
  and is not allowed under a strict `script-src`; build single-file components ahead of time (Vite's
  Vue plugin) and use the runtime-only build, which has no `eval` or `new Function` (grep of
  `vue.runtime.esm-browser.prod.js`: none). Codex's own components are prebuilt.
- **Vue itself.** MIT, 3.5.46 (2026-11-20); a 3.6 line is in release-candidate.

### 4.2 Montage, the closest comparable tool (checked on GitHub)

- Repository `hatnote/montage`, BSD-3-Clause, active (pushed 2026-11-23).
- **Backend:** Python (Flask, `app.py`, deployed on Toolforge). **Frontend** (`frontend/package.json`):
  **Vue 3** (`^3.4.27`) on **Vite 6**, **`@wikimedia/codex` and `codex-icons` `^1.14.0`**, Pinia, Vue
  Router, `vue-i18n` 10, Axios, Zod, Cypress for end-to-end tests. It is a SPA built into the Flask
  app's static directory.
- So the Wikimedia ecosystem's own juror-style tool chose Vue 3 plus Codex as a SPA. That is a
  data point for the Wikimedia look and for contributors who already know Codex; it is not an
  argument for a SPA here, because Montage's server rules are small and ours are the product.
  (Montage's Codex is one major version behind: 1.14 against 2.8.)

### 4.3 React, Lit, Alpine

- **React islands** (#136): the UI developer's stated wish; tokens and CSS-only Codex possible.
- **Lit 3.3.3** (BSD-3-Clause, 2026-05-14): 6 KB gzipped, standard web components, no build step
  needed; styles in shadow DOM through `adoptedStyleSheets`. Good for a few self-contained
  controls (a star rating, a tile), poor for whole screens, and shadow DOM complicates global CSS
  (Bootstrap, Codex) and accessibility tests. Not recommended as the main approach.
- **Alpine 3.17.5** (MIT, 2026-10-20): 20 KB gzipped; inline expressions need `Function`, so under a
  strict CSP only its separate CSP build works. It adds small client state to server pages; in
  this tool plain scripts already do that, so the gain is small.

## 5. Prototype: the large view's vote without a full page load

### 5.1 What was built

A throwaway mock in a scratch directory (nothing in this repository; the local prototype branch and
worktree were removed). It reproduces what matters from the large view: a page per file with a
`#vote-form` carrying a CSRF field, a button with `data-vote-url`, keyboard Space, an `<img>`, the
previous and next links, and the real redirect behaviour (a vote is a POST that answers 302 to the
next file's page). Mock delays: 60 ms for the vote, 40 ms for a page, 120 ms for an image, with a
`Cache-Control` on images. It runs under the application's CSP shape (`script-src 'self'`,
`style-src 'self' 'unsafe-inline'`, then the strict `style-src 'self'` of G-7).

Variants: **full** (today: `form.submit()`), **h2** (htmx 2.0.11, `hx-boost`), **h4** (htmx 4.0.2,
`hx-boost:inherited`), **island** (React 19: fetches JSON, renders the file, `pushState`, prefetches
the next image). In a real Chromium (Playwright 1.64) each variant took five Space votes and then
Back.

### 5.2 Measurements

| | full (today) | htmx 2.0.11 | htmx 4.0.2 | React island |
|---|---|---|---|---|
| Added bytes, gzipped | 0 | 16.8 KB | 13.4 KB | 69.5 KB (React and a 13-line component) |
| Keypress to next file visible, median of 5 (ms) | about 280 | about 145 | about 140 | about 95 |
| Requests during 5 votes | 34 | 27 | 28 | 15 |
| Window state kept (no reload) | no | yes | yes | yes |
| Back after 5 votes lands on the previous file | yes | yes | yes | yes, with an own `popstate` handler |
| CSP violations, `style-src 'self' 'unsafe-inline'` | none | none | none | none |
| CSP violations, strict `style-src 'self'` (G-7) | none | **2** (htmx 2's indicator `<style>`) | none | none |
| Server changes | none | **none** (the redirect already answers with the next page) | none | a JSON endpoint for the file and the vote (#135 slices 3 and 4: about 5 days) |
| Lines of the mock's own code | 4 | 3 (plus attributes) | 3 (plus attributes) | 13 (and a new view of the file) |

Reading the numbers: the delays are the mock's, so only the **differences** mean something. The full
load costs the vote, the page and the scripts again (about 280 ms); htmx saves the reload but still
pays the vote plus the page HTML (about 145 ms); the island pays the vote only, because the next
file's data is a small JSON and its image was already prefetched (about 95 ms). The real page is
heavier than the mock (layout CSS and scripts parse again on a full load), so the full-load figure is
an underestimate and the gap larger in practice. The htmx numbers could improve with `hx-select`
(swap only `#main`) and `hx-preload` on the next link (core in htmx 4).

### 5.3 What the prototype found about the real application

1. **`form.submit()` bypasses htmx.** The vote must go through `form.requestSubmit()`, which
   fires the `submit` event. In the mock `large-view.js`'s `vote(url)` was replaced by 1 line
   (`requestSubmit`).
2. **The form's action is read when htmx processes the form** (seen in 2.0.11; the mock used one
   transient form per vote, `htmx.process(form)`, for both versions, so 4.x's behaviour here was
   not isolated). The real `vote(url)` therefore clones the form, sets the action and calls
   `htmx.process`: about 6 lines.
3. **A scripts-in-head trap.** In htmx 2 a response without an explicit `<head>` has its head
   scripts swapped into the body and executed again, which doubled the key handlers and made one
   Space press cast several votes (20 requests per vote). The layout must have an explicit
   `<head>` element; `app/views/layout.scala.html` has one (about line 27), so this holds for the
   real pages.
4. **Scripts that initialise once.** In the real large view `large-keys.js` binds at jQuery ready
   and **captures the key URLs at that moment**, `large-stars.js` initialises raty at ready,
   `large-sections.js` restores sections from an inline `<script>`, and `keepCommentsInView` runs at
   `DOMContentLoaded`. After a swap they would act on the previous file (a Space vote would vote the
   old file). Each needs to be re-run from `htmx:after:swap` (4) or `htmx:afterSwap` (2), and
   `large-keys.js` should read its URLs when the key is pressed. htmx re-executes inline scripts in swapped content, and only hashed ones are allowed by the CSP: the
   `wlxLargeSections.restore` inline script is hashed (`InlineScriptHashesSpec` covers it), so it
   would run again after a swap, and moving it into `large-sections.js` is an option, not a necessity. Estimate: 25-40 lines and Jest
   specs. This, not htmx, is the cost.
5. **Error handling and flashes.** The vote's redirect carries flash messages
   (`.flashing(...)`, for a moved vote) and refusals flash too: they appear on the next page render
   after the swap, so the swapped page must show the flash (a checklist item). 4xx and 5xx swap in htmx 4; the vote's refusals (a closed round: a redirect; an
   invalid rate: the 400 error page; a 403) must look right swapped in or be handled in
   `htmx:before:swap`.
6. **Not measured:** bfcache restoration (headless Chromium: the navigation type after returning
   was `back_forward` for every variant; it could not be told a bfcache restore from a reload),
   keyboard voting beyond Space, the criteria rounds, the real page weight.

## 6. How GraphQL (#135) and htmx coexist

They answer different questions and do not compete.

- **htmx moves HTML fragments** that Twirl already renders: the large view's next page, a filter's
  result, a form's re-render. It needs **fragment templates or whole pages, not GraphQL**; the
  server rules stay in the controllers (`Secured`, `GalleryService`, CSRF), which is why it needs no
  new authorization work.
- **GraphQL serves data to islands** (React, Vue or Lit) that render on the client: organizer
  statistics, admin lists, later the juror slice. #135 is framework-independent: any island sends
  `Csrf-Token` on every request and the mount element carries `data-csrf-token`.
- A page can use both: an htmx-driven large view with an island for one panel. They share the
  session cookie and the CSRF token. Do not mix them inside one region (htmx must not swap
  content an island owns); mark island mount points `hx-ignore` (htmx 4) or keep them outside the
  boosted region.
- The decision for the juror slice of #135 (its Q8, our Q9) gets a new input: if the large view is
  enhanced with htmx, #135's slices 3 and 4 (`image`, `vote`) are less urgent for jurors.

## 7. Recommendation

1. **Large view:** htmx 4 boost for the vote and next file, `hx-preload` (its own 0.9 KB script) or a link prefetch for the
   next file and its image, after a 2-3 hour spike in the real application (the mock's findings 1-5
   as a checklist). 2-4 days with tests, in its own PR, after #129's gallery work lands or in parallel
   in the large-view files that #129 does not touch.
2. **Forms and filters:** keep as they are; use htmx where a no-reload result is wanted, one page at
   a time.
3. **New screens by the UI developer:** islands on the platform of #136, GraphQL for data. Choose
   Vue plus Codex or React with the UI developer (section 8). The Codex CSS-only components and tokens
   could also theme the Twirl pages later (a visual decision).
4. **No SPA.** Revisit only if the large view and gallery both moved to islands and a client
   router became useful.
5. **#129 stays as planned.**

## 8. Open questions for the maintainer

1. **Ask the UI developer:** is Vue 3 with Codex acceptable instead of React? Which do they know,
   and which would they rather maintain? (The platform is the same for both.)
2. **Wikimedia look.** Is Codex's look and feel (and its accessibility and language variants) a goal
   for the tool, or is Bootstrap 5 staying? Codex on island screens next to Bootstrap pages would
   look mixed.
3. **Licence.** The tokens (GPL-2.0-or-later) are already vendored with a `NOTICE` entry. Is the same
   acceptable for the Codex components package (GPL-2.0-or-later) and its icons (MIT)? (A reading
   of the licence texts; confirm with whoever owns the licence policy.)
4. **Re-skin.** Should Codex CSS and tokens replace Bootstrap on Twirl pages in time, or only be used
   in new island screens? The first is a design project of its own.
5. **htmx 4 or 2.** Start on 4.0.x (recommended: smaller, no style-src change, no `localStorage`
   snapshots; young) or on 2.0.x (more mileage)?
6. **Large view with htmx.** Go ahead with the 2-3 hour spike in the real application, then the PR?
   Is no-reload voting in the large view wanted, and is "next file prefetched" enough for jurors?
7. **Juror slice order** (this is the same as #135's Q8 and #136's Q9): with htmx on the large
   view, the juror slice of GraphQL is less urgent: after the admin and organizer screens?
8. **Unpoly or Turbo.** Any interest, or is htmx the only one to try?
