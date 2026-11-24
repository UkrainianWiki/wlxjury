# UI review checklist (front end)

For every change to a template, a script under `public/javascripts/`, or CSS. Each line points to
[practices.md](practices.md); server-side speed items are in the
[performance checklist](../performance/review-checklist.md), security items in the
[security checklist](../security/review-checklist.md). Front-end work consults the
Modern Web Guidance skill first (below).

## Modern Web Guidance

- [ ] The skill was consulted before writing the markup, CSS or script (`search`, then `retrieve`;
  Claude Code: `.claude/skills/`, Codex: `.agents/skills/`), and the PR says "Modern Web Guidance:
  guide IDs applied, or none". A change that relies on a guide names its ID.
- [ ] Inside the floor (Chrome 109, Firefox 115), or behind the fallback the guide gives
  (practices §6.1a). The compatibility lint does not flag every CSS property.
- [ ] No speculation rules or prefetch of a page with a side effect, a vote, or `no-store`.

## Images

- [ ] Thumbnails and other images below the first screen have `loading="lazy"` and
  `decoding="async"`; the first screen's and the large view's main image do not.
- [ ] Every `<img>` has `width` and `height` (the file's, where known) or an `aspect-ratio`, so
  nothing shifts as it loads.
- [ ] `srcset` and `sizes` use only widths the server serves (`servedWidths`; performance
  practices §3.6); a thumbnail is not scaled up from a smaller one.
- [ ] The page's main image (the large view's file) has `fetchpriority="high"`; no other image
  does.
- [ ] Meaningful images have `alt` from a message key; decorative ones `alt=""`.

## CSP and scripts

- [ ] No inline `<script>`, `on*=` attribute, `javascript:` URL or inline `style=` attribute; no
  `eval`, `new Function` or string timers; data goes through `data-*` attributes (practices §2.1;
  secure-coding §5).
- [ ] Text is set with `textContent` or attributes, never as HTML, except in a listed file
  (practices §2.2, secure-coding §8).
- [ ] Everything is served from this host: no CDN, font or script from another origin.
- [ ] Votes and other POSTs carry the CSRF token and use a path on this host (practices §2.3, §4.3).
- [ ] Long work in a handler yields to the browser (no loop over hundreds of tiles in one task).

## Accessibility and keyboard

- [ ] Every control has an accessible name; a field has its `<label for>` (practices §4.1).
- [ ] One `h1` per page (practices §4.2); heading levels do not skip.
- [ ] Everything works by keyboard alone, with a visible focus indicator; the large view's keys
  still vote and move (practices §4.3). Keys are read as `key` or `code`.
- [ ] Contrast and state do not rely on colour alone; both theme modes checked
  (practices §3, §5).
- [ ] The axe check of the browser tests passes for the page, with no new CSP violation or
  console error.

## CSS

- [ ] RTL-safe: logical properties (`margin-inline-start`, `padding-block`, `inset-inline-end`,
  `text-align: start`) instead of `left` and `right`; no layout that assumes left to right.
- [ ] Colours, spacing and radii come from the design tokens, not literals (practices §3).
- [ ] `prefers-reduced-motion` respected for any new animation or transition.
- [ ] A new CSS feature is Baseline widely available, or behind `@supports` with a fallback, and
  inside the floor above.

## Caching and history

- [ ] Pages that show a vote or the juror's state stay `Cache-Control: no-store`. The
  back/forward cache stays off for them by decision (2026-11-24): do not add `pageshow`
  refresh logic or remove `no-store` to enable it.

## Updating the skill

The skill is a project copy of the upstream `SKILL.md`, release v1.0.2, with the CLI pinned to
`modern-web-guidance@1.0.2` and telemetry off (`DISABLE_TELEMETRY=1` in `.claude/settings.json`
and `.codex/config.toml`). To update, in one PR: read the upstream release notes and `SKILL.md`,
copy the new `SKILL.md` to both `.claude/skills/modern-web-guidance/` and
`.agents/skills/modern-web-guidance/`, replace `@latest` by the exact new version again, keep the
project note below the title, update the version in the NOTICE entry and in this section, and look
at one search and one retrieve. Never `@latest`. Background reading: the upstream
[Eval Dashboard](https://googlechrome.github.io/modern-web-guidance-src/) reports on the
project's own evals, not on this app.

## Credit

Modern Web Guidance's guide text is CC-BY 4.0 and its code Apache-2.0. When a guide's text or
code is copied into this repository (a doc, a comment, a script, a template), credit it with a
source link to the guide in the same place, and add a NOTICE entry for it; applying a guide
without copying needs none.
