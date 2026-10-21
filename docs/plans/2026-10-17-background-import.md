# Background Commons Imports Plan (October 2026)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** "Import files" and "Update monuments" on the contest files page (`POST /contest/:id/importimages`) run as background jobs on the generic `job` table and `JobScheduler`, as round distribution does since `ukwikidev/background-distribution`:
- no request thread, no `Await` of many minutes, no dependence on Play's `idleTimeout` or Apache's `ProxyTimeout`;
- memory of one page of files (or list pages) instead of the whole contest;
- progress, Cancel and Retry; a failure loses one page, not the whole import;
- distributions, votes and galleries keep the speed measured in the distribution plan's 13.7 while an import runs.

**Builds on:** branch `ukwikidev/user-in-multiple-contests` at `4337c96`, which contains `ukwikidev/background-distribution`: the `job` table (V60), `services.jobs.{Job, JobHandler, JobScheduler}`, `JobController` (`/admin/jobs/:id`, cancel, retry), `JobSchedulerProvider`, and the status component `app/views/snippets/jobStatus.scala.html` + `public/javascripts/job-status.js`. Read `docs/plans/2026-10-07-background-distribution.md` first, especially 13.1, 13.4, 13.5 and "Future job kinds"; this plan refines that section.

**Why (findings, 2026-10-17, code at `4337c96`):**
- **Import files** (`ImageController.importImages` → `ImageService.appendImages` → `initImagesFromSource`) runs in the request, ending in `Await.result(…, 500.minutes)`.
  - It holds in memory every image of the contest already stored (`ImageJdbc.findByContest`), the image info of every file of the source (`FetchImageInfo`), and, with a monument id template, the wikitext of every file page (`FetchImageText`: revisions with content), zipped together. For the WLM UA fixture (~38k files) that is all of it at once.
  - Writes: one unchunked `ImageJdbc.batchInsert` of all new images and one `CategoryLinkJdbc.addToCategory` (autoSession, no transaction), then `ImageJdbc.update` per changed image, each in its own `DB.localTx`. A failure half-way leaves a partial import with no record; the only retry is to run it all again, refetching everything.
  - Both inserts are plain `INSERT`s: a file inserted meanwhile by another contest's import (contests can share files) fails the whole batch, as does a race on `category_members` (unique `cm_category_page`).
  - A big import outlives Apache's 60 s `ProxyTimeout`: the admin sees an error while it continues, and may click again, starting a second import of the same contest.
- **Update monuments** (`ImageService.updateImageMonuments`):
  - for a Ukrainian contest with a monument id template, `MonumentService.updateLists` runs in the request: `MonumentQuery.byMonumentTemplate` fetches and parses every list page (`Await` 30 min), builds a `ContestStat` / `MonumentDB`, for WLM also the special nominations, then one unchunked `MonumentJdbc.batchInsert` upsert of everything;
  - then a **fire-and-forget** `Future` over the source's file revisions (all collected before the `map`) calls `ImageJdbc.updateMonumentId` per file, each its own transaction, written even when unchanged. Errors go to `println`; nobody learns whether it finished.
- **Special nominations:** the city-based ones and the Desna one select monuments *from the main lists* (`SpecialNomination.monumentsInCities`, `fromDb`), which `updateLists` then drops (`filterNot(m => monumentIds.contains(m.id))`). Only the nominations' **own list pages** (`byPages(nomination.pages, listTemplate)`) add monuments. So the special-nomination part reduces to "fetch these extra list pages with their list template"; no `ContestStat` or `MonumentDB` is needed.
- **scalawiki has no paging API today:** `SinglePageQuery.*ByGenerator` ignore their `continueParam`, and `DslQuery.run` follows `continue` to the end, accumulating every page. But:
  - `MwBot.post(params)` + `new Parser(action).parse(body)` + `parser.continue` fetches **exactly one API page** and returns its continue map, so wlxjury can page without changing scalawiki;
  - `bot.run` on ≤ 50 page ids merges partial pages (`rvcontinue`, `iicontinue`) by id (`PageList.addPages`), so detail fetches by ids can stay on `bot.run`;
  - `MonumentQuery.monumentsByPages(titles, listTemplate)` already fetches and parses given list pages, and `listPageRevs` lists them cheaply (titles and revision ids, no content).

**Design summary:**
- **Two new job kinds on the generic `job` table; no migration.**
  - `commons_image_import`: subject the contest. Modes `import` (Import files) and `monument_ids` (re-read the files' monument ids: the image half of Update monuments). Both modes share one dedupe key per contest (`commons_image_import:contest:7`), since both write the contest's images.
  - `monument_list_import`: subject the contest (listing, permissions, fairness), but the dedupe key is the **list source** (`monument_list_import:<listsHost>:<list template>`): contests of different years share the `monument` table.
- **Each step fetches one page outside any transaction, then writes it in one short transaction with the next cursor.** The cursor is the API's continue map (or an index into a pasted list), kept in `job.state`. A crash between fetch and commit refetches that page; every write is idempotent.
- **External fetches run on a separate small fetch lane** of the scheduler, so a slow Commons call never holds one of the 2 DB workers that 13.7 chose for the 2-CPU server.
- **Update monuments = two independent jobs** (the list import and the files' monument ids), enqueued in one transaction. Neither waits for the other: `images.monument_id` is the id string from the file's wikitext; galleries join `monument` by it at read time.

---

## Task 1: Commons paging (`services.commons`)

- **`CommonsSource`** (injectable, so specs bind a fake):
  - `listPage(source, cursor, limit): (Seq[PageRef], Option[Cursor])`: one API page of the source's files;
  - `fileDetails(pageIds, withText): Seq[Page]`: one `bot.run` with `pageids` (≤ 50), `prop=imageinfo` with `FetchImageInfo`'s props (`timestamp|user|size|url|mime|mediatype`) and, with text, `revisions` (`content|timestamp|user|comment`);
  - `fileDetailsByTitles(titles)`: for a pasted list, with `FetchImageInfo.titlesQuery`'s URL decoding and normalisation; reports missing titles;
  - `categoryFileCount(category)`: `FetchImageInfo.numberOfImages`, for the progress total.
- **Source kinds as today** (`generatorParams`):
  - `Category:…` → `list=categorymembers`, `cmtype=file`, cursor `cmcontinue`;
  - `Template:…` → `list=embeddedin`, namespace File, cursor `eicontinue`;
  - any other page → `prop=images` of that page, cursor `imcontinue`;
  - pasted titles → an index into the list (the list itself is in the job's `params`).
- **Listing and details are separate calls** (rather than one generator query with `prop=imageinfo|revisions`): a generator's continue mixes the generator's and the props' tokens, and a step would have to loop until `batchcomplete`. Two calls give a clean cursor (the list token) and let the details reuse `bot.run`'s merging. The cost is one cheap list call per step.
- **Implementation:** build the list `Action` with scalawiki's DSL, `bot.post(action.pairs ++ format=json ++ cursor)`, parse with `Parser`, take `parser.continue` as the next cursor (empty: last page). Retry transient failures (non-JSON body, HTTP 5xx / 429, `maxlag`, timeouts) with backoff, as `DslQuery` does; give up after a few tries, which fails the job (Retry resumes it).
- **File → `Image`:** `ImageJdbc.fromPage(page, contest)` for the info. Refactor `FetchImageText.monumentIds / descriptions / authors`, which work on sorted lists of pages, into a per-page function, and combine info and text **by page id** (as `ImageEnricher.zipWithRevData` does), not by position.
- **Tests:** a `FakeCommonsSource` over in-memory images (for the job specs); a pager spec against a stubbed `MwBot.post`: the cursor of each source kind, the last page, a retried failure.
- Later, upstream: a `DslQuery.runPage(continue)` in scalawiki would replace the `post` + `Parser` code; `CommonsSource` keeps its interface either way.

## Task 2: Scheduler: an external fetch lane

- **Problem:** `JobHandler.prepare` runs on a job worker. A Commons call takes 0.5–5 s; with `jobs.workers = 2`, one import would take half the DB capacity and slow the distributions measured in 13.7.
- **`FetchingJobHandler[A]`** (extends `JobHandler`): `fetch(job): A` outside any transaction, for the cursor in `job.state`; `write(job, ctx, a): StepResult` in the step's transaction. Its `step` is implemented once in the trait.
- **The scheduler runs `fetch` on its own pool** (`jobs.fetchWorkers`, default 2). It keeps the result per job id together with the cursor it was fetched for; the job joins the DB-step queue only when its page is there. The step compares the cursor in the locked job row with the fetched one; if a Retry or a cancel moved it, the page is dropped and fetched again.
- **Turns:** only DB-step time counts in the jobs' and contests' virtual time; fetching uses no DB worker.
- **Limits:** the kind limits cover fetches too: `jobs.concurrency.commons_image_import = 1`, `monument_list_import = 1`, i.e. one request at a time to Commons and one to the lists' wiki, across all contests, as the Wikimedia API etiquette asks (serial requests).
- **Inline mode:** fetch, then write, on the calling thread.
- **Not now:** prefetching the next page while the current one writes; measure first (Task 7).
- `RoundDistributionHandler` is unchanged; its `prepare` (a category's file ids, once per job) could move to the lane later.
- **Tests (`JobSchedulerSpec`):** a slow fetch doesn't delay another kind's DB steps; a moved cursor is refetched; cancel during a fetch; a fetch failure fails the job with its error.

## Task 3: `commons_image_import` (`services.imports.ImageImportHandler`)

- **`params`** (fixed at creation): `contestId`, `categoryId`, `source`, `titles` (the pasted list, may be empty), `mode` (`import` | `monument_ids`), `monumentIdTemplate` (text is fetched when set), `max` (today's `max`, 0 = all).
- **`state`:** `cursor`, counters (`seen`, `inserted`, `linked`, `updated`, `monumentIdsChanged`), `missingTitles` (the first 100).
- **Progress:** `seen / total` files. The total is the category's file count for a category, the list's size for pasted titles, unknown (NULL) for a template or a page.
- **A step, mode `import`:** one page of files, as many as one request of their details takes (the API's page id limit: 500 with `apihighlimits`, 50 without), or `jobs.commonsImport.pageSize` when it is set.
  - *Since 2026-10-21 (`ukwikidev/commons-bot-login`):* the jobs log in with `commons.user` / `commons.password` (a bot password), and when unset, the page size follows the account: 500 with `apihighlimits`, 50 without (`CommonsLogin`, `CommonsSource.maxIdsPerRequest`).
  - Fetched (outside the transaction): the list page, then the files' info (and text).
  - Written (one transaction): one query loads the page's stored rows and whether each is linked to the contest's category:
    `SELECT i.*, cm.page_id IS NOT NULL AS linked FROM images i LEFT JOIN category_members cm ON cm.page_id = i.page_id AND cm.category_id = :cat WHERE i.page_id IN (:ids)`.
    The rules of `initImagesFromSource`, per page instead of per contest:
    - not in `images` → insert and link;
    - in `images` but not linked to this contest (another contest's file) → link only, not updated (as today);
    - linked → update if `ImageService.changedImages` says it changed, with the selection rows' monument id (`syncSelectionMonumentId`).
  - **Idempotent writes:** `INSERT IGNORE` for images (a concurrent import of a contest sharing the file doesn't fail the step) and for links (unique `cm_category_page`): a page repeated after a crash or a Retry is a no-op.
  - **Session-threaded DAO methods:** `ImageJdbc.update` and `updateMonumentId` open their own `DB.localTx`, and `batchInsert` / `addToCategory` default to `AutoSession`; inside a step they would escape its transaction. Add variants that take the step's session; keep the old signatures for the other callers.
  - `max`: done once `seen >= max`.
- **A step, mode `monument_ids`:** the list page and the files' text only (no image info); for the files already stored, update `images.monument_id` **only where it differs** (today every file is written), with the selection rows' copy. Ids are validated with `\d\d-\d\d\d-\d\d\d\d`, as today.
- **The request:** in one transaction, `ContestJuryJdbc.setImagesSource` (as today) and the enqueue; then 303 back to the files page. If the contest has an active import, attach to it and flash "an import is already running".
- **A new submit after a failed import** starts a new job from the first page (cheap per step, idempotent); the failed job's Retry button resumes it instead. Simpler than the distribution's resume rules, which exist because a round's creation must complete.
- **Cancel keeps what was imported** (harmless; a later import skips or updates it).
- `details(job)`: inserted, linked, updated, missing titles.

## Task 4: `monument_list_import` (`services.imports.MonumentListImportHandler`)

- **`params`:** `contestId`; the scalawiki contest it maps to, chosen as `updateImageMonuments` does today (`Contest.WLEUkraine(year)` / `WLMUkraine(year)` by name, Ukrainian contests with a monument id template only); its `listsHost` and list template; for WLM, the special nominations that have a list template and pages (`SpecialNomination.nominations`), snapshotted for the record.
- **Phases:**
  1. `nominations`: the nominations' own list pages, ≤ `pageSize` pages per step, through `monumentsByPages(pages, Some(listTemplate))`. Cursor: nomination index + page index.
  2. `lists`: `list=embeddedin` of the list template (main and project namespaces), ≤ `jobs.monumentImport.pageSize` titles per step (default 20: list pages are large), cursor `eicontinue`, then `monumentsByPages(titles, None)`. It skips "новий АТУ" pages, as `byMonumentTemplate` does.
- **Each step** keeps the ids matching `\d{2}-\d{3}-\d{4}` and upserts them with `MonumentJdbc.batchInsert` in the step's transaction (session threaded).
- **Nominations first, then the lists:** the lists' upserts overwrite, so a monument on both keeps the main list's data, as today's `filterNot(monumentIds.contains)` does.
- **Progress:** list pages done / total. The first step counts the list pages with `listPageRevs` (titles and revision ids only) for the total, without storing them.
- **Memory:** ≤ 20 list pages' wikitext and their monuments; no `MonumentDB`, no `ContestStat`.
- Monuments removed from the lists stay in the table, as today.

## Task 5: Requests and UI

- **`POST /contest/:id/importimages`:**
  - `import.files` → a `commons_image_import` job, mode `import`;
  - `update.monuments` → in one transaction, a `monument_list_import` job (Ukrainian contests with a template, as today) and a `commons_image_import` job, mode `monument_ids`.
- **Files page (`contest_images.scala.html`):** the latest job of each kind for the contest (`JobJdbc.latestBySubjects(kind, "contest", Seq(contestId))`), shown with `snippets/jobStatus` and `job-status.js`, reloading on done so the database image count updates. Pass `returnTo` = the files page: `JobController.back` defaults to the rounds list.
- **Titles:** "Importing files from Category:…", "Updating monument ids of the files in …", "Updating monument lists (WLM Ukraine 2026)"; message keys in `conf/messages*`.
- **The files page calls Commons on every render** (`getNumberOfImages`, `.await`, on the request thread). Show the latest import job's total instead, or load the count asynchronously. Small, optional.
- **Permissions:** the form keeps its contest admin check; the job endpoints check the job's contest, which both kinds set.

## Task 6: Tests

- **Lifecycle, both kinds:** create → done; a failure on page *k* (the fake source throws) → failed with its cursor → Retry → done with no duplicates; cancel; restart recovery (lease), reusing `JobSchedulerSpec`'s helpers.
- **Equivalence with `initImagesFromSource`:** the cases of `AppendImagesSpec` and `ImagesFromListSpec` give the same images, links and monument ids through the job: new files; stored files, changed and unchanged; another contest's files (linked, not updated); a pasted list with missing titles; `max`; with and without a monument id template; `selection.monument_id` follows. These specs move from the `SinglePageQuery` mocks to `FakeCommonsSource`; `jobs.mode = inline` keeps them synchronous.
- **Monument lists:** on recorded list pages (a few lists, a nomination page, a "новий АТУ" page), the old `updateLists` and the job write the same rows, including a monument on both a list and a nomination page. Check that `byPage(page, listTemplate)` and `monumentsByPages(Set(page), Some(listTemplate))` parse alike (both through `OtherTemplateListConfig`).
- **Concurrency:** two submits for one contest → one job; imports of two contests sharing files both succeed.
- **Memory:** a fake source of the 38k files of `data/wlm-UA-images-2025.csv` with synthetic wikitext; heap after GC stays at about one page (HeapWatcher, as in the distribution benchmark), against the old path.
- **Pager:** see Task 1.

## Task 7: Benchmark

- **`ImportSimulation`** (Gatling): a fake `CommonsSource` bound by config (e.g. `commons.fake.csv`) in the forked `TestServer`, with a per-call latency (e.g. 300 ms) standing in for Commons. An admin imports the 38k-file fixture into a new contest while VotingSimulation's jurors vote and a large round distribution runs.
- **Measure:** the import's duration; the distribution's submit-to-done against the same run without the import (the fetch lane should leave it unchanged); vote and gallery latency; heap after GC at `-Xmx512m`, with the production JVM flags and one GC log per JVM.
- **Choose** `jobs.commonsImport.pageSize`, `jobs.monumentImport.pageSize` and `jobs.fetchWorkers` from these runs and record them here.

## Open questions

1. Mode `import` stores any value of the monument id template (≤ 100 characters); mode `monument_ids` stores only valid ids. Unify? Default: keep both as they are.
2. Should Update monuments also refresh the special nominations for WLE (today WLM only)? Default: no.

## Implementation notes (2026-10-17)

Where the code differs from the tasks above:
- **Task 1:** a source's files are listed with a **generator** query without props (`generator=categorymembers|embeddedin|images`), not with `list=`: the pages come back the same way for all three sources, and with no props the `continue` map has only the generator's token. The one-page request and the retries are in `services.imports.MwApi`, shared with the monument lists.
- **Task 2:** `FetchingJobHandler[A].fetchStep` fetches and returns the step that writes what it got, only if the locked row still has the cursor it was fetched for. The scheduler runs it on its fetch lane and holds the step with the job (`Held.fetched`) until a worker runs it; nothing is cached in the handler. The heartbeat renews only the jobs the scheduler holds, so a job it dropped after a crash it couldn't record is taken over when its lease expires.
- **Task 3:** `max` is gone: it was only passed to scalawiki's progress events, never a limit. A pasted list's files get their text by title when the contest has a monument id template; the old path took the text from the source category instead, and failed for a listed file that wasn't in it.
- **Task 4:** the contest's own lists come **first**, then the nominations', which write only monuments not stored yet or stored from the same list page: a job that fails or is cancelled during the own lists leaves no nomination data over them (with nominations first, it did).
- **Task 4:** **most WLM special-nomination "pages" are navbar templates** (`Template:WLM замки і фортеці`, …), which scalawiki's `byPage` expanded to every page that embeds them. So a monument import is a list of sources, each a list page or a template whose embedding pages are lists, read with the same `embeddedin` paging as the contest's own lists. The progress has no total (list pages done only).
- **Task 5:** the files page still asks Commons for the category's file count on every render, now through `CommonsSource.categoryFileCount` (with its retries); `FetchImageInfo`, `FetchImageText` and `ImageEnricher` are gone, the page-text helpers are `services.imports.ImageText`.
- **Task 7:** not done as a Gatling simulation. `ImportScaleSpec` (`-Dimport.scale=true`, optional `-Dimport.latencyMs`, `-Dimport.pageSize`) imports the 38k-file fixture through a background scheduler over a fake Commons, and writes the time and the heap after GC to `target/gatling/import-memory.txt`.
  - **Measured 2026-10-17** (this laptop, test JVM defaults, no API latency, page size 50): 38,367 files in 109 s (768 steps, ~140 ms each: parsing 50 file texts and one transaction); heap after GC 75 MB before (the fake Commons holds the whole fixture) and at most 78 MB during the import, 0 full GCs. With Commons, each step adds its two API calls (list, details); one job's fetch and write alternate, so prefetching the next page during the write is the next step if imports must be faster.
  - Not measured: an import alongside distributions and jurors (the Gatling scenario above).

---

**Out of scope:**
- creating contests from a category (`ContestService.importCategory`);
- the local image cache downloads (`LocalImageCacheService`), which have their own background progress; they could move to the job table later;
- "Count new files" as a job;
- deleting images or monuments removed from Commons or the lists;
- the paging API in scalawiki itself.

**Rules:**
- branch `ukwikidev/background-import` from `ukwikidev/user-in-multiple-contests` (`4337c96`);
- one commit per logical step;
- the full `sbt test` must pass;
- no migration is expected; if one is needed, it takes the next number after V62;
- run Gatling with the production JVM flags;
- no server changes.
