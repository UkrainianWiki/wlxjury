# One shared store for Commons image metadata (investigation, 2026-10-22)

Findings and options, recorded without a plan: nothing here is scheduled and no code changed.
Where the code is named, it is as read at the time (wlxjury before migration V63; the scalawiki
WLX statistics module on its `subregion-stat` and `daily-server-run` branches). The deleted and
renamed files plan ([2026-10-22-deleted-renamed-files.md](../plans/2026-10-22-deleted-renamed-files.md))
took the narrow part of this and cites the rest as background.

**The question:** the WLM/WLE statistics engine (`scalawiki-wlx`, "wlx report") and the jury tool
both keep Commons file metadata. Can they keep **one** copy, in the jury's MariaDB, kept current
by **one** sync run by **one** scheduler, so that (1) the report's fetched image info is stored
in the DB instead of `csv-cache/*.csv` and (2) the reports run from the DB?

The daily statistics run is meant for the same server as the jury, so the reports could reach the
jury's database locally, and two schedulers (cron and the jury's `JobScheduler`) already share a
host.

## 1. What overlaps

| Concern | scalawiki-wlx (reports) | wlxjury |
|---|---|---|
| Storage | a CSV per campaign-year plus one with the all-time files that are in no per-year CSV (`ImageCsvExporter`, `ImageCsvImporter`) | MariaDB `images` (one global row per `page_id`), linked to contests through `category` and `category_members` |
| Sources | per-year category, all-time file template, files hosted on another wiki | the contest's `images` source: a category, template, page or pasted list (`ImportSource`) |
| Change detection | a sweep of id and latest revision, diffed by `rev_id` (`ImageDbProvider.syncImageDb`); only new or changed files are refetched | none: every import fetches the details of every file, then compares fields (`ImageService.changedImages`) |
| Deletions and removals | dropped, but only after a sweep that looks complete (at least 90% of `categoryinfo.files`) | never; `DELETE FROM images` would cascade to `selection` (V58), so votes would go too |
| Renames | the page id is stable and a move adds a revision, so the revid diff catches it | caught only because the title is one of the compared fields |
| Paging and memory | the whole set in memory | one API page per job step, constant memory |
| Scheduling | cron wrappers: a nightly run and a weekly resync of past years | `JobScheduler` with a fetch lane, per-kind limits, lease, Cancel and Retry; no recurring triggers |
| Wikitext parsing | `Image.fromPage`: every monument id, special nomination templates, author, categories | `ImageText.withText`: the first monument id (cut to 100 characters), the `{{Information}}` description, the raw author |
| Monuments | `MonumentDbProvider` with the list page's revid | `monument_list_import` job, no revids |

Volume: about 800k files in all for the two Ukrainian campaigns (at most about 46k a year). That
is a modest table, but more than fits the jury's heap as `Image` objects.

**What the jury schema lacks for the reports:** the upload timestamp and uploader; all monument
ids (not the first only); Commons categories (ineligibility, interior bonus); special nomination
templates; EXIF date and camera; a change token (`rev_id`, revision timestamp); a "deleted or no
longer in the set" state. Also `images.size` is `INT` and the import converts with `.toInt`, which
overflows for files over 2 GiB (Commons allows 5 GiB videos): it needs `BIGINT` either way.

## 2. The option: the jury's database as the store

Principles that kept the design small:

- The jury's DB is the store and its Flyway migrations own the schema. `images` is extended, not
  duplicated, so the tuned gallery and distribution queries keep reading the same table.
- One writer of Commons data: a sync that runs in the jury app (it has the scheduler, lease, bot
  login and fetch lane). The report CLI reads with a read-only DB user. A read-write CLI sync is
  only for a local database.
- Never delete an image row: deleted files get `images.deleted_at`, files that leave a set get
  `removed_at` on their membership row, so votes are never cascaded away.
- One wikitext parser: `Image.fromPage` in scalawiki; the jury maps its result and keeps its
  100-character cut as a mapping step.
- The reports stay a CLI process run by cron with their own heap. What moves is the image sync.

Schema sketch: new `images` columns (`rev_id`, `rev_ts`, `upload_ts`, `uploader`, `exif_date`,
`camera`, `synced_at`, `deleted_at`, `size BIGINT`); `category` generalised to a set (`kind`,
`site`, `prune`, `last_complete_sweep_at`); `category_members` gets `seen_by_job` and
`removed_at`; child tables `image_monument`, `image_category` and `image_nomination`. No wikitext
is stored: a changed parsing rule means resetting `rev_id` for the affected set. Storing every
category is about 8M rows at 800k files; keeping only the categories the reports read is the
fallback.

Sync algorithm, one job step per API page: list a page of the set's ids with their latest
revision; fetch details only for new or changed ones and upsert them; at the end, if the sweep
looks complete and the set may prune, mark unseen members removed and check them with one
`pageids=` request for deletion. This keeps the CSV path's semantics (revid diff, guarded
deletes, keep the stale row on a partial refetch) at the jury's memory profile, and it would make
the jury's own re-imports cheap. The algorithm would sit behind a small store port (revisions,
upsert, mark seen, mark deleted, prune unseen), with a JDBC implementation and an in-memory one
for tests.

Rollout order that keeps both applications working: shared algorithm and port in scalawiki with
tests; schema and JDBC store; reports reading from the DB (`--images-from-db`) with a
byte-identical check against the CSV output for one year of each campaign; a bootstrap that
imports the existing CSV caches (about 800k files not refetched from Commons); the jury running
the sync on a schedule (this needs recurring triggers, which the scheduler lacks: a schedule
table and a ticker; the unique active dedupe key already makes double enqueueing harmless);
optionally the reports as a job that runs the statistics jar as a child process.

## 3. Running the reports as SQL, without loading all images

The memory cost of the reports is the past years and the all-time set (about 750k of the 800k
images); the current year (at most 46k) is better kept in memory as full `Image`s, because its
reports list titles and rate per image. Every report that reads the rest reduces to aggregates
keyed by monument, region, author or year, with at most tens of thousands of result rows:

| Report | As SQL |
|---|---|
| regional table, charts, community breakdown | `GROUP BY year, region` with `COUNT(DISTINCT monument_id)`; per-year id sets for the overlap diagram |
| "newly pictured", special nominations | the distinct monument ids pictured outside the current year, or an anti-join that returns a few thousand |
| authors contributed and per region | `COUNT(DISTINCT account)` per year and region; distinct (region, account) |
| most photographed monuments | `GROUP BY monument_id` and `ROW_NUMBER() OVER (PARTITION BY region ...)` |
| rating inputs (authors, images per place, interior, old photos per monument) | one aggregate row per monument pictured before; the per-author check joins a temporary table of the current year's (author, monument) pairs |
| wrong or missing ids | a per-year filter against a temporary table of the known ids |

What the queries would need beyond the schema above: eligibility (EXIF deadline, ineligible
category, minimum size) as a `WHERE` with parameters; the region computed in Scala at write time
into a column so SQL and Scala cannot disagree; the "first id or all ids" distinction kept per
report (the most likely source of differences); author renames kept in a mapping table and
applied in every grouping; files from other wikis in a separate table, because their page ids
clash with `images.page_id`. The reports would depend on a read model with an in-memory
implementation over today's data (the oracle for equivalence tests) and a SQL one, ported one
report at a time and compared byte for byte, with a tie-breaker added first where a top-N sorts
only by count. The expected gain (heap well under the current limit, a handful of aggregate
queries instead of an all-years parse) is unmeasured.

## 4. Risks

- Cascades: any `DELETE FROM images` destroys votes, so the sync must only tombstone; a spec
  should assert it issues no such delete.
- Tombstones need filtering: round distribution and every query joining `category_members` must
  skip removed and deleted files, while existing rounds keep showing what was voted on.
- A rename changes `url`, so the local image cache downloads the file again under the new URL
  and leaves the old one for cache maintenance.
- A full all-time read is about a million rows on the jury's host: stream it, run it at night,
  and check vote latency during one run.
- scalawiki would gain a ScalikeJDBC dependency; keep it in one new module on the jury's version.
- Commons etiquette: one sync job at a time, serial requests, bot login for 500-id pages.
- Schema ownership across two repositories: the jury stays the only owner of migrations, and the
  store's specs run against a schema dump that a jury spec checks.

## 5. Open questions, with the default each was leaning to

1. Files hosted on another wiki (another page-id space): keep them out of the store and fetch
   them live for whole-`ImageDB` reads; a separate table keyed by (site, page id) once the SQL
   reports need them.
2. Should the jury's contest sets prune files that left the category? Default no for contest
   categories (the jury never unlinks today), yes for the reports' per-year and template sets.
3. Store all page categories, or only those the reports read? Default all, unless measurements
   show the table or the read is too heavy.
4. Sync years and campaigns that have no jury contest? Default yes: the sets come from the
   scalawiki contest definitions, not from the jury's contest table.

## Conclusion

Sharing one store in the jury's database looks feasible and keeps both applications working at each
step (section 2), and the report queries reduce to aggregates (section 3). It is not scheduled:
the cost is a large schema and cross-repository change, and the gains (cheaper re-imports, reports
without CSV caches, lower report heap) are unmeasured. The part the jury needs on its own, handling
deleted and renamed files without losing votes, is in the
[deleted and renamed files plan](../plans/2026-10-22-deleted-renamed-files.md). Decide the open questions above
before any of the rest is planned.
