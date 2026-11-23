# Modern Web Guidance: review and dashboard

Status: plan for the maintainer's decision (2026-11-24). Nothing is installed or changed in the
app by this PR. Request: "adopt https://github.com/googlechrome/modern-web-guidance review and
dashboard".

## 1. What the repository is

Read from the repository's README, `skills/modern-web-guidance/SKILL.md` and its GitHub metadata
on 2026-11-23. The conclusion first: **it is a knowledge base for coding agents, not a review
tool for a finished app, and its "dashboard" is the project's own dashboard of how well its
guidance works for agents, not a report about your code.**

| Question | Answer |
|---|---|
| What it ships | An agent skill (`modern-web-guidance`) plus an npm CLI of the same name (Node 22+). About 269 web-platform features and 206 use-case guides (View Transitions, popover and dialog, container queries, lazy loading, fetch priority, speculation rules and instant preloading, INP and `scheduler.yield`, `:user-invalid`, accessibility, forms, Baseline-aware fallbacks and others). Guides are Markdown, token-efficient, with code patterns, gotchas and fallbacks. |
| How an agent uses it | The skill tells the agent to run `npx -y modern-web-guidance@latest search "<query>"`, then `retrieve "<id>"`, before writing HTML, CSS or client-side JS; then to cross-check its code against the guide and end its answer with a `### Modern Web Guidance` section naming the guides applied. Search is offline (embedded model and BM25) once the npm package is cached. |
| Is there a review tool | One part: `legacy-scan <path> [--json]` finds legacy patterns by deterministic rules. It covers a curated subset (the skill says about 20 percent of the guides); each finding has a `keepWhen` / `dropWhen` rubric that the agent applies to drop false positives. It is a scanner for an agent's modernization pass; the README and skill document no CI mode, score or threshold. The skill tells agents not to run it for a specific feature task. There is no linter, Lighthouse-like audit, GitHub Action or review checklist for a project. |
| What the "dashboard" is | The **Eval Dashboard** (a GitHub Pages site of the source repository `modern-web-guidance-src`): results of the project's own evals (agents building tasks on two sample apps, with and without the skill, Playwright graders). The README's table shows, for 20 November, claude_code (opus-5-5) 54 to 94 percent, codex_cli 49 to 85 percent, antigravity 56 to 97 percent. It says nothing about this app, and a project cannot publish its own results there. |
| Licence | Software: Apache 2.0. Guide content under `guides/`: CC-BY 4.0 (attribution needed if text is copied into our docs). Compatible with this project's dependency rules (docs/dependencies/practices.md); the licence line still goes in the PR that adds it. |
| Telemetry | The CLI sends anonymous usage data (installs, calling agent name, retrieved guide IDs, the agent's search queries); opt out with `DISABLE_TELEMETRY=1`. Queries come from the agent and could carry our wording, so any adoption sets the opt-out. Raw prompts are not collected. |
| Adoption as documented | `npx modern-web-guidance@latest install` (wizard), or the Claude Code plugin (`/plugin marketplace add GoogleChrome/modern-web-guidance`, `/plugin install modern-web-guidance@googlechrome`), `gh skill install`, `npx skills add`, a Codex marketplace entry. Optional `.mwgrc` in the repo root (`allowOriginTrials`, `allowVolatileFeatures`, both off by default). Updates through `npx ...@latest update` or the plugin's auto-update. |
| Maturity | Under the GoogleChrome organisation, last push 2026-11-23, versioned skill (`--skill-version 2026_11_16-...`); the source repository has CODEOWNERS and GOVERNANCE files. Content changes weekly, so `@latest` is not reproducible. |

Not run: `legacy-scan` was not run on this tree. It is downloaded and executed code (the
sandbox declined the run), and the maintainer should decide about running `npx` packages here
(Q3). Section 3's findings are from reading the code; section 4 makes the scan the first
adoption step.

## 2. What fits this app

The app is server-rendered Twirl with Bootstrap 5.3, jQuery and small scripts under
`public/javascripts`, a strict CSP (no inline code), and large lists of Commons thumbnails (about
38 000 files in a big contest, galleries of tens to hundreds per page). Voting is by POST, with
keys in the large view. The compatibility floor is Chrome 109 and Firefox 115
(docs/ui/practices.md §6.1a), so a guide's modern API is allowed only behind a fallback or as
progressive enhancement. Mobile is not supported yet (§6.2).

| Guidance area | Fit | Why |
|---|---|---|
| Image loading (lazy, fetch priority, dimensions, `srcset`) | High | Thumbnail grids are the app's heaviest pages. |
| Interaction latency (INP, `scheduler.yield`, long frames) | Medium | Gallery init and vote handlers run on pages with hundreds of tiles; nothing measures it. |
| Back/forward cache | Medium, a decision | The vote pages are `no-store` on purpose (F5). |
| Forms (`:user-invalid`, `autocomplete`, `inputmode`, `<dialog>` for confirm) | Medium | Many admin forms; a confirmation uses `window.confirm`. |
| Accessibility | High, mostly done | axe per page in the browser tests; open items are U8, U11, U13 in docs/ui/practices.md §9. |
| Motion and preference queries (`prefers-reduced-motion`, `color-scheme`, `light-dark()`) | Low to medium | The theme system is its own and enforced (ui practices §3); little animation. |
| View Transitions, speculation rules, scroll-driven animation, anchor positioning, popover | Low | The 109/115 floor and the CSP limit them; speculation rules would prefetch vote pages, which is wrong. Possible for admin lists only. |
| Built-in AI, WebMCP, passkeys, PWA install, WebAssembly | None | Not product features. |
| Security guides (Trusted Types, SRI, Permissions-Policy, referrer policy) | Already covered | CSP, headers and sink rules are in secure-coding §5 and §8. Compare, do not duplicate. |

What the repository already has, so adoption adds only what is missing:

- Rules with guards: docs/ui/practices.md (CSP, accessibility, supported browsers, guards table),
  docs/testing/practices.md §10 (browser tests), and a review checklist per area
  (docs/*/review-checklist.md, indexed in docs/README.md). docs/performance/review-checklist.md exists but covers
  load tests, benchmarks and server and memory work, with no front-end or web-platform items;
  docs/ui has no checklist file yet (the index shows none for ui).
- Automated checks: the compatibility lint (`.browserslistrc`, `eslint-plugin-compat`,
  `stylelint-no-unsupported-browser-features`, a step of `ci.yml`); axe-core per page in
  Chromium; zero CSP violations and console errors as a guard; Playwright on Chromium, Firefox
  and WebKit on every PR (`.github/workflows/browser.yml`); the screenshot harness
  (`scripts/ui-shots.py`).
- Dashboards: an Allure report merged from the three engines, uploaded as a build artifact only,
  with history in the Actions cache; "GitHub Pages is not decided" (browser.yml comment, plan
  2026-11-04-story-coverage.md §4.5). A coverage workflow and a PR coverage comment
  (`coverage.yml`, `coverage-comment.yml`). No Lighthouse or Core Web Vitals job; no real-user
  measurement.

## 3. First review pass (by reading code; nothing was run)

Severity is for this app's users: high = measurable harm now, medium = worth a change, low = a
tidy-up or a question. "Guide topic" is what to search; the exact guide ID is looked up with
`search` when an item is taken (I did not run the CLI and do not guess IDs).

| # | Finding | Where | Guide topic | Severity |
|---|---|---|---|---|
| F1 | The thumbnail grids have no `loading="lazy"` or `decoding="async"`. Only the comments report has it (`commentsReport.scala.html:191`). The gallery tiles (`gallery.scala.html:43`), the by-rate grid (`galleryByRate.scala.html:134`), the contest files list (`contestFiles.scala.html:114`) and the large view's thumbnail strip (`large/contestFile.scala.html:91`) load every image of the page at once. Check first for `gallery`: Unite Gallery builds its own tiles from these images, so the attribute may have to be set where the plugin clones them (`gallery-init.js`, `unitegallery.js`). | `app/views/*` | lazy loading images | medium |
| F2 | No `width` / `height` on the grid images (`galleryByRate`, `contestFiles`, thumbnail strip), so layout shifts as thumbnails arrive. The data exists (`file.image.width/height`, used by `commentsReport` and the large view). | same | image dimensions, CLS | medium |
| F3 | The large view's main image has no `fetchpriority="high"`; it is the page's LCP element and the voting loop's main wait. It already sets `srcset`, `sizes` and `width/height`. | `large/large.scala.html:97`, `large/contestFile.scala.html:120,127` | fetch priority | medium |
| F4 | The next and previous files are not preloaded in the large view. Voting moves one file at a time; the next image could be fetched ahead (`<link rel="prefetch">` or an `Image()` in `large-view.js`). Not speculation rules: a page prefetch would run a page GET, and the pages are `no-store`. | `public/javascripts/large-view.js` | preloading | medium (jurors' main flow; measure first) |
| F5 | `Cache-Control: no-store` on the galleries and the large view keeps them out of the back/forward cache by design, so Back after a vote reloads (`Secured.noStore`, ui practices §4.3). The guidance's bfcache advice (refresh on `pageshow` with `persisted` instead of `no-store`) is what that scaladoc rejected for a flash of old votes. Browser handling of `no-store` pages is changing; re-test before deciding. | `app/controllers/Secured.scala` (`noStore`) | back/forward cache | low, a decision (Q4) |
| F6 | `window.confirm()` for the conflict-of-interest confirmation; a modal `<dialog>` is the guidance's pattern, accessible and testable, and inside the 109/115 floor. | `public/javascripts/round-form.js:131` | dialog, confirm dialog | low |
| F7 | `prefers-reduced-motion` is handled in one rule only (`main.css:670`). Our own CSS has about two transitions, so it is fine there; Unite Gallery's slide and zoom animations are unchecked. | `public/stylesheets/main.css`, vendored gallery | reduced motion | low |
| F8 | jQuery is a blocking script in `<head>` (`layout.scala.html:49`); Bootstrap is already `defer`, with the reason documented. Moving jQuery changes every page script's order, so it needs a measurement first. | `app/views/layout.scala.html` | script loading | low |
| F9 | The section toggle keeps a `keyCode` fallback next to `key` (`large-sections.js:42`); the guidance prefers `key` / `code`. The Mac Delete bug (U7) is this class of problem. | `public/javascripts/large-sections.js:42` (the vendored `unitegallery.js` has the same, not ours to change) | keyboard events | low (overlaps U7) |
| F10 | Focus visibility of binary thumbs (WCAG 2.4.7), the missing on-page key list, and no file title in the large view are open already as U11, U8, U13. Listed here so they are not counted twice. | docs/ui/practices.md §9 | accessibility, focus | medium (existing) |
| F11 | No lab or field measurement of INP, LCP or CLS. Gatling measures the server; the browser tests check behaviour, not timings. A performance guide cannot be shown to help without it. | none | CWV, INP diagnostics | medium (a gap, not a defect) |
| F12 | Not findings: the CSP and security headers, the theme system, headings, `rel=noopener` on `target=_blank` (no missing case found by grep), the compatibility floor and its lint, and the `pageshow` handlers (`submit-once.js`, `preview-confirm.js`, `source-identity.js`) are in place. | | | none |

Unchecked, left to the scan or a run: the CSS for legacy patterns (vendor prefixes, float
layouts), table responsiveness, `inputmode`, `autocomplete` and `enterkeyhint` on the admin forms,
and `:user-invalid` against the app's own validation styling.

## 4. Adoption plan

Options, in order of fit:

- **A. Agent skill for contributors, plus an agent review checklist (recommended).** Install the
  skill for the agents that edit templates, scripts and CSS, and add one doc section that says
  when to use it and what to report. Nothing runs in CI. No CI minutes; the maintenance is that
  `@latest` content changes weekly, so a change that relies on a guide names its ID in the PR.
- **B. A periodic scan.** `legacy-scan public app/views --json` in a manual or monthly workflow,
  its output an artifact and a short step summary. Cheap (about a minute of runner time) but
  advisory: it covers about 20 percent of the corpus and findings need a rubric pass. Not a PR
  gate.
- **C. A measured web-vitals job.** Lighthouse CI or a Playwright `PerformanceObserver` over the
  fixture's main pages (gallery, large view, round list) with budgets, beside the browser tests.
  This is what would show whether a guide helps here. It is not in the repository; it is our own
  work (about 3 days, plus tuning).
- **D. Our own dashboard.** Publish the Allure report and the scan and vitals results to GitHub
  Pages, one site. Blocked by the undecided Pages question (browser.yml comment). The upstream
  Eval Dashboard is not something to adopt: it reports on their evals.

Recommendation: A now, then B as a manual workflow, then C only if F1 to F4 are taken up (C is
how their effect would be shown), and D only with the Pages decision. "Dashboard" in the request
is therefore answered as: link the upstream Eval Dashboard from the checklist as background
reading, and host our own results beside the Allure report if and when Pages is enabled.

Steps and effort (hours of work for one person or agent, not elapsed time):

| Step | What | Effort |
|---|---|---|
| 1 | Run `legacy-scan` over `public/`, `app/views` and the CSS with telemetry off, in a throw-away checkout; triage with its rubric; add real findings to section 3 or to docs/ui/practices.md §9 as U-numbers | 2 h |
| 2 | `docs/ui/review-checklist.md` (new, short, in the format of the other review checklists; front-end items only, so it does not duplicate docs/performance/review-checklist.md, which stays server-side and gets one pointer line to it): the guidance areas of section 2 that apply, the 109/115 floor, "retrieve the guide before changing a template, script or CSS", and a PR note "Modern Web Guidance: IDs applied or none"; link it from docs/README.md and CLAUDE.md's practices list | 2 h |
| 3 | Add the skill to the agents' setup (a project-scoped plugin entry or an install note in CONTRIBUTING.md or DEV.md) with `DISABLE_TELEMETRY=1`; a committed `.mwgrc` that keeps origin trials and volatile features off | 1 h |
| 4 | Fix F1 to F3 in one PR (`loading`, `decoding`, dimensions, `fetchpriority`) with a view spec for the attributes and a browser-test check that the first images load; verify the Unite Gallery path first | 4 h |
| 5 | Optional B: `.github/workflows/web-guidance-scan.yml`, `workflow_dispatch` plus a monthly schedule, pinned Node 22 and a pinned CLI version (not `@latest`), `contents: read`, report as an artifact | 3 h |
| 6 | Optional C: vitals on the fixture (LCP, CLS, event timing on three pages), budgets recorded and not enforced for a month, then decided | 3 days |
| 7 | Optional D: Pages for Allure plus the above, after the Pages decision | 1 day |

Costs: A and B add no required CI time; C adds about 1 to 2 minutes to one browser job if run
there, or a job of its own. Maintenance: A needs the checklist kept to what we use (about an
hour per contest season); B needs its pinned CLI version updated by the dependency practices'
process; C's budgets need an owner.

Risks: the guides push modern APIs and this app's floor is Chrome 109 and Firefox 115, so an
agent that follows a guide without its fallback can break old browsers that the lint does not
catch (docs/ui/practices.md §6.1a says the lint does not flag every CSS property). The checklist item "inside the floor, or
behind the fallback the guide gives" is the control. Second, `@latest` and plugin auto-update
change what agents are told, so a PR cites the guide ID it relied on. Third, the telemetry
above. Fourth, speculation-rule or prefetch guides must not be applied to pages with side
effects or votes.

Out of scope: any change to the app, the CSP, the supported-browser floor, or required checks.

## 5. Open questions for the maintainer

- **Q1. What did "review and dashboard" mean?** The repository has neither a review of a project
  nor a dashboard of a project's results (section 1); the dashboard is their eval dashboard.
  Is the goal (a) agents write better front-end code (option A), (b) a recurring audit of our
  own pages (B and C), or (c) a published page of our own results (D)? The plan recommends A,
  then B.
- **Q2. Which agents should have the skill?** Claude Code and Codex are used here. A
  project-scoped plugin entry changes what every contributor's agent runs; a per-user install
  does not.
- **Q3. May agents run `npx modern-web-guidance@latest` (downloaded, executed code, network
  access)?** If yes, allowlist `npx -y modern-web-guidance@latest *` only, as the skill advises;
  if not, install once and pin the version.
- **Q4. Back/forward cache versus `no-store` (F5).** Keep the documented decision, or test
  whether a refresh-on-`pageshow` design now avoids the flash of old votes?
- **Q5. Take F1 to F3 now?** They are small and the most likely to be felt on a large contest,
  but they touch the gallery templates, which a branch already working in the gallery may also
  touch.
- **Q6. Performance budgets (option C).** Do you want measured LCP, CLS and INP on the fixture,
  and if so on which pages and with what failure policy (advisory first)?
- **Q7. Pages.** Is GitHub Pages for the Allure and any new reports decided yet? It gates
  option D.
- **Q8. Attribution.** If a guide's text or code is copied into our docs, CC-BY 4.0 needs a
  credit line: copy nothing, or accept a credit in NOTICE?
