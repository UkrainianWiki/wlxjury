# Changelog

What changed in the WLX Jury Tool, by release. Each entry gives the deploy date and when the
work was developed. Merge dates to `master` are not used, because the stacked PRs merge later.
Deploy dates come from production's migration history (`flyway_schema_history.installed_on`,
read 2026-11-03): a deploy is dated by the first migration it applied. Changes without a
migration are dated "deployed by" the next deploy that contained them, or from a deploy
record. Work that is built but not deployed is under **Unreleased**. Plans that aren't built
yet are in the [roadmap](docs/plans/2026-11-02-roadmap.md). PR numbers are the stacked series,
#184–#243 (`origin/stack/series-doc`, `docs/plans/2026-11-03-pr-series.md`).

## Unreleased (developed, not yet deployed)

### Release 1 (planned): `d8054737`, migrations V71–V81

Developed 2026-10-31 to 2026-11-02, PRs #211–#231. Pre-deploy steps: roadmap, "Before any
production deploy".

- **Light, dark and auto colour modes**, on one self-hosted layout with Bootstrap 5; the choice
  is kept per user (#211–#214, V71).
- **Yes/no rounds:** the all-jurors gallery shows each file's approvals, and "selected by at
  least N" filters by them (#215, V72–V74).
- **REST API** under `/api` with Swagger UI; root's contest endpoints; personal API tokens on
  the profile page (#218, V75).
- **Accounts:** accounts are created by invitation only (no open sign-up); an email change is
  confirmed from the new address (#225, V77).
- **Sessions:** "Keep me signed in", "Sign out everywhere", and an idle and an absolute
  lifetime (#229, V80).
- **Criteria rounds:** misplaced or duplicate criteria votes are refused, and sums stay
  consistent (#226, V78).
- **Stopped jurors** can no longer vote in that round. The image proxy serves only the page
  sizes (#230, V81).
- **Faster organizer views:** ranked and grouped all-jurors pages (#222, #224). One
  database pool (#216). No inline scripts (#217).
- **Security hardening** of roles, host names and cookies, sessions, password storage,
  output escaping, logs and exports, and security headers (#219–#231). The role fix is already
  in production as the 2026-11-02 hotfix (#223, V76). Security rules, a review checklist and
  guard specs (#228).
- **Migrations:** V71 user theme; V72–V74 binary aggregate votes; V75 API tokens; V76 role
  allowlist; V77 account verification and tokens; V78 criteria vote integrity; V79 API token create
  nonce; V80 session upgrade window; V81 legacy password retirement.

### Release 2 (planned): V82 onward

Developed 2026-11-02 to 2026-11-03, PRs #232–#236.

- **Criteria catalogue:** admins pick each round's criteria from a shared catalogue (#235, V83).
- **Round images:** each round keeps its list of files, and root's "Check every round's images"
  repairs any drift (#236, V84).
- Internal: `selection` renamed to `vote` and `rounds` to `round` (#232, V82); Flyway 13.12.0
  run by the app (#234); a faster test suite (#233).
- **Migrations:** V82 the renames; V83 criteria catalogue; V84 `round_image`.

### Developed after release 2

- **MariaDB 10.11 support** for the move to Ubuntu 24.04. Tests run on 10.6 and 10.11
  (#237, V85 gallery index, V86 timestamp definitions; developed 2026-10-19 to 2026-11-03).
- **Fewer deadlocks** when jurors vote while admins change rounds: READ COMMITTED, round-first
  locking and retry (#239, 2026-11-03).
- **Sign in with Google or Wikimedia**, to an existing account (#240, V87, 2026-11-03).
- Deployment: the adoption scanner names credentials by their leaf key (#241). Database
  access practices and guards (#242).
- **Quality** ([CI plan, "Test coverage"](docs/plans/2026-11-03-ci-and-scalawiki-packages.md#test-coverage-added-2026-11-03-pr-60)):
  - GitHub Actions runs on every PR (#238), plus a coverage workflow (#243).
  - About 2,150 automated tests: 1,902 Scala examples (0 failed; about 1,660 at the start of
    2026-11-03), 253 Jest tests, 68 Python deployment tests and 24 release-image tests.
  - Coverage on `4a861818` (run 41649422116): Scala 89.87 % of statements and 85.84 % of branches; JavaScript
    96.76 % of lines and 91.39 % of branches. Templates are not measured, and
    neither is the vendored, patched gallery (`unitegallery.js`, `ug-theme-tiles.js`), which the
    JavaScript figure excludes.
- Documentation: consolidated into practices docs with a history index (#245); practices for
  testing, workflow, operations, integrations, UI and dependencies, and a docs index
  ([docs/README.md](docs/README.md), PR 62).

## Deployed

### 2026-11-02 14:01Z: hotfix `0.13-round-filters-rolefix-f3f2c73617de`

The production build `f9c31ce5` plus the contest-role allowlist, a security fix. It has no
migration; the date comes from the deploy record
([docs/history/security/](docs/history/security/2026-11-02-role-allowlist-hotfix.md)).

### 2026-10-31: category operations (`f9c31ce5`, what production runs)

- Round filters exclude a category, and combine two categories with AND, OR or MINUS. Existing
  rounds keep their filters (#210, developed 2026-10-31; V69–V70).

### 2026-10-30: conflicts of interest

- Rounds can leave out files by the contest's organizers, jurors and staff, with a count on the
  round form (#209, developed 2026-10-29 to 2026-10-30; V67–V68).
- Deployed by this date, with no migration of their own:
  - region filter improvements: named special nominations, Wiki Loves Earth regions, cached
    counts (#205, 2026-10-25 to 2026-10-26);
  - clear access-denied pages; jurors see only their own votes; CSRF on votes (#206,
    2026-10-27; security);
  - the galleries keep the juror's place and a stable order, and each click is faster (#207,
    2026-10-27 to 2026-10-28);
  - importing international winners, and asset digests (#208, 2026-10-28 to 2026-10-29).

### 2026-10-24 and 2026-10-25: contest files gallery

- The contest's files as a gallery, with regions and a large view (#204, developed
  2026-10-24; V65 on 2026-10-24, V66 on 2026-10-25).
- Deployed by 2026-10-24: add files to a round from a pasted list, and WebP thumbnails (#203,
  2026-10-23).

### 2026-10-23: deleted and renamed Commons files

- Files deleted on Commons are hidden, and renamed files show under their new name. The large
  view loads from Wikimedia at its own sizes (#202, developed 2026-10-22 to 2026-10-23; V63–V64).

### 2026-10-22: background jobs, multi-contest accounts, InnoDB (release 0.13)

Developed 2026-10-05 to 2026-10-22 (the multi-contest work from 2026-04-12), PRs #197–#201.

- **Round creation and "distribute new files"** run as background jobs, with progress, Cancel
  and Retry. Rounds being created stay hidden from jurors (V60).
- **One account per person**, a member of several contests with a role in each; a contest
  switcher; "Import files" and "Update monuments" run in the background (V61–V62).
- **Faster and leaner:** the tables are converted to InnoDB (V55.1); indexes and gallery
  paging are improved; inserts are chunked; a submit token prevents duplicate rounds (V56–V59);
  media-type filters work again (V54); the indexes that were never applied are created (V55).
- **Deployment:** a single service with versioned releases, `wlxjury-release`, a restore path
  and a recovery runbook (#200).
- **Migrations:** V54–V62.

### 2026-09-07: query and security work from spring 2026

Developed 2026-03-10 to 2026-09-10, PRs #187–#196.

- New indexes and `monument_id` stored on votes: faster galleries and region statistics
  (#192, #193; V46–V50).
- **Security fix:** region filters are fully parameterised (#195). V51 makes the monument id
  unique.
- Round consistency checks and distribution verification (#196, V52–V53). Cascaded thumbnail
  resizing (#187).
- The migrations ran on 2026-09-07. Production later ran `8fdaa7d` (committed 2026-09-10), so a
  later deploy without migrations followed; **its date is unknown**.
- Not used in production: the B44 baseline (#188, never applied) and the blue-green scripts
  (#194, replaced on 2026-10-22).
- **Migrations:** V46–V53. V45a–V45g never ran; their indexes came as V55.

### 2026-03-08: local image cache

- Thumbnails are served from a local cache through the app's image proxy (#185, #186;
  developed 2026-03-06 to 2026-03-08; V44).

### 2024-07-08 and 2024-06-28: media type filter

- Rounds can filter by media type (V42 on 2024-06-28, V43 on 2024-07-08; developed in
  June–July 2024).

### 2023-12-07: decimal minimum rate

- A round's minimum rate from the previous round can be a decimal (V41; developed and deployed
  the same evening).

### 2022-12-25: current production database created

Migrations V1–V40 were applied at once, possibly on a new server. Changes before this date
have no deploy record. Earlier development (git history since 2013-10) is not listed.

## How the dates were found

Production's history (read-only, run by the user):

```sql
SELECT installed_rank, version, description, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank;
```

| Versions | Applied | Entry |
|---|---|---|
| V1–V40 | 2022-12-25 | database created |
| V41 | 2023-12-07 | decimal minimum rate |
| V42, V43 | 2024-06-28, 2024-07-08 | media type filter |
| V44 | 2026-03-08 | local image cache |
| V46–V53 | 2026-09-07 | spring 2026 query and security work |
| V54–V62 | 2026-10-22 | release 0.13 |
| V63–V64 | 2026-10-23 | deleted and renamed files |
| V65, V66 | 2026-10-24, 2026-10-25 | contest files gallery |
| V67–V68 | 2026-10-30 | conflicts of interest |
| V69–V70 | 2026-10-31 | category operations |

The history has no V45 row (V45a–V45g never ran) and no B44 row. When a release is
deployed, move its section from Unreleased to Deployed, date it from its first new
`flyway_schema_history` row (or from the deploy record if it has no migration), and add the
release name.
