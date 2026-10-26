# Region filter improvements (October 2026)

**Goal:** five improvements to the galleries' region filter (`regionNav`):

1. Special-nomination id prefixes are named after their nomination instead of "Unknown".
2. Wiki Loves Earth Ukraine contests get the region filter too, and regions are named by the contest's kind (its campaign, or its name and country), not always as Ukraine's.
3. The contest files page's region counts are cached in memory, with invalidation.
4. A benchmark of the region counts decides whether round galleries show counts.
5. A contest's monument id template comes from its campaign config when an admin set none, resolved at read time; no migration.

Branch `ukwikidev/region-improvements` (from `1c1677a`), with scalawiki branch `region-prefixes` (`4405aff`, from `a19d6d3f` on `subregion-stat`).

## 1. Special nominations

A nomination's lists give the monuments of their own an id whose first segment is the nomination's, not the region's (`99-141-2306`: Jewish heritage; Donetsk, `14`, in the second segment). The rest of their rows carry the regions' own ids.

scalawiki's `SpecialNomination` gets `idPrefix` (from `wlm_ua.conf`) and `byIdPrefix(nominations, year)` / `byIdPrefix(nominations)`. The prefixes were checked on 2026-10-25 on every list page of each nomination (the `ID` column), not only from the files' categories:

| prefix | nomination | evidence on its lists |
|---|---|---|
| 00 | Війна руйнує пам'ятки | 23 ids on 4 of 16 pages |
| 88 | Млини | 650 ids on 25 of 27 pages |
| 90 | Чеська спадщина | 278 ids on 19 of 22 pages |
| 92 | Болгарська спадщина | 97 ids on 6 of 8 pages |
| 93 | Польська спадщина | 547 ids on 21 of 26 pages |
| 94 | Німецька спадщина | 350 ids on 19 of 24 pages |
| 95 | Пам'ятки Подесення | 5 ids on 1 of 3 pages (the only prefix of its own there; 2021's 7 files all in "Desna Region") |
| 96 | Пам'ятки дерев'яної архітектури України | 1,093 ids on 31 of 45 pages |
| 97 | Національно-визвольні (2021) | 22 ids on 6 of 14 pages |
| 98 | Квіти України (2021) | 207 ids on 17 of 20 pages |
| 99 | Єврейська спадщина | 965 ids on 27 of 27 pages |

- The plan's open 97 and 98 are resolved by their lists: 98 is "Квіти України", 97 "Національно-визвольні". Other lists cite a few ids of these (a Polish list has three `96-` ids); those are the other nomination's monuments.
- Регламент 2025 (ua.wikimedia.org) lists no special nominations by name, so it confirms neither Млини nor Болгарська спадщина for 2025: their `years` stay as they were. The 2025 files that carry `88` and `92` are named through the any-year fallback (below).

In the jury (`services.regions.RegionScheme`), a prefix is named from the nominations of the contest's year first, then from the campaign's nominations of any year. Regions come first (by id), then nominations (Ukrainian collation), then "Other (xx)" for a prefix that is neither, still filterable. Nominations are read from the contest's campaign config (`ContestKind`), so a WLE contest has none.

## 2. Wiki Loves Earth, and the contest's kind

- WLE Ukraine ids have WLM's format (`XX-YYY-ZZZZ`, KOATUU oblast first), so the KOATUU names apply to both.
- Production's contests have no `campaign` (NULL on all 607); `ContestKind` resolves a contest by its campaign if set, else by its name ("Wiki Loves Monuments", "Wiki Loves Earth") and its country, matched exactly by name or code ("Ukraine", "ua"): "Ukraine Int", "Ukraine, Animals" and "Ukraine ineligible" are no country.
- `RegionScheme` names regions by the contest's country: KOATUU (with the messages' names) for Ukraine, nothing for an unknown country (the id itself). A non-Ukrainian contest no longer names `07` "Volyn O.".
- The only practical limit for WLE was the gate on the monument id template; item 5 removes it.
- Round galleries filter a single region by `s.monument_id LIKE 'xx%' AND regionOf(s.monument_id) = 'xx'`: the LIKE keeps the index range, the second condition makes it the same rule as the counts. A three-character "Other" prefix (WLE free text such as `Печ`) used to go to the monument join and list nothing.

## 3. The contest-level region count cache

- `db.scalikejdbc.RegionCountCache`, on `RoundImageCounts`' pattern: in memory, keyed by category id, a per-category version and a global monument-id version, read before a load and compared after it, invalidated after commit; a 10-minute TTL that only bounds writers outside this JVM. One app instance assumed.
- It stores raw counts (per region, and the files without one), so `allCount` is their total and the `COUNT(*)` of the category's members goes away. The deleted lists stay live.
- Invalidated by: an import step that links files to the category (`afterCommit`), any changed file of an import (monument ids may change: global version), "Update monuments" changing ids (global), `ImageService.importFiles` copying a category, `ImageJdbc.update`/`updateMonumentId`.
- Kill switch: `regions.countCache = true` in `application.conf`.

## 4. Benchmark and decisions

### Datasets and tools

- **D1:** the Gatling fixture (`fixture-v3`): 38,367 files in the contest's category, 20 jurors, a binary and a rated round, 10 jurors per image (383,670 selection rows per round, 767,340 total, ~19k per juror per round), 1% of the files marked deleted by the harness.
- **D2 (optional):** a category of three copies of D1's files (~115k).
- D3 and D4 (prod-like server) are not run: the review's measurements settle the criteria, and the laptop lacks the RAM D4 needs (bench/README.md §2).
- Tools: `RegionCountBenchmark` (literal SQL plus the application query methods) and `RegionCountPairedBenchmark` (repeated alternating SQL/controller comparisons). No Gatling HTTP A/B for the round galleries.

### Original decision criteria (written before the measured runs)

Each query: 20 warm-up runs, then 100 timed (or 60 s), 1 and 5 threads; the decision uses p95 at 1 thread, checked at 5. Runs on mains power with nothing else heavy running (bench/README.md §1-2), back to back.

- **C1, keep the contest files cache:** keep it if the uncached category counts (Q1) cost ≥ 20 ms p95 more than a cache hit on D1.
- **C2, cache the deleted lists:** only if the deleted lists' counts (Q2) are > 10 ms p95 on D1 with 1% deleted (D2 if run). Otherwise they stay live.
- **R1, juror counts in round galleries (with the rate filter):** on by default if Q5's p95 ≤ Q4j's p95 + 5 ms on D1 (Q5 replaces Q4j, the DISTINCT a juror's gallery runs today).
- **R2, whole-round counts for organisers (`user/0`):** on only if Q6b's p95 ≤ Q4r's p95 + 100 ms on D1, for the unfiltered and the `rate > 0` variant. Otherwise organiser views show names only, as now. No new index (V67) and no round-count cache.

Resulting `gallery.regionCounts`: `all` if R1 and R2 pass, `juror` if only R1, `off` if R1 fails.

### Follow-up: user-visible cost and measurement variation

The user requested this follow-up on 2026-10-25 after the initial query measurements. The original cutoffs above are preserved as the pre-measurement hypothesis, not silently changed to fit a preferred result.

A percentage of an isolated SQL query is not a user-visible latency budget: 10% of 40 ms is 4 ms. The relevant cost is the absolute addition to a gallery response, including authentication, the pager, the other gallery queries and HTML rendering. Network transfer, browser layout and thumbnails are additional costs outside this local benchmark. A fixed 5 ms SQL cutoff alone cannot establish a material user-facing regression. For context, [Chrome's RAIL guidance](https://web.dev/articles/rail) targets a visible response within about 100 ms and treats 100–1000 ms as the range for continuous task progression. These are budgets for the user's complete interaction, not a licence to add 100 ms to each SQL query, and not a just-noticeable-difference experiment for this gallery. A repeatable addition of tens to hundreds of milliseconds to a page, or a contention increase pushing its tail toward a second, would merit attention here. A 1–4 ms addition alone would not be a reason to remove useful counts. Likewise a nonzero difference is not automatically measurement noise: a small, repeatable SQL cost can matter to capacity even when it has little effect on an individual page.

The three saved repeat runs ending `100135`, `100451`, and `100806` illustrate the problem (one worker, p95 ms):

| run | baseline DISTINCT | counts | difference |
|---|---:|---:|---:|
| 100135 | 24.3 | 31.0 | +6.7 |
| 100451 | 31.1 | 27.5 | -3.6 |
| 100806 | 27.6 | 29.6 | +2.0 |

The baseline's 6.8 ms range is larger than the original 5 ms allowance. With five workers the same baseline ranges 43.0–48.2 ms, counts 40.8–63.4 ms: load-tail variability is larger still. These are independently measured quantiles, not paired effects or confidence intervals. The older Q5 literal also computes five aggregates, whereas the actual page computes one aggregate for the active filter; decisions should use the application method.

`RegionCountPairedBenchmark` therefore alternates AB/BA on identical inputs within one warmed disposable D1 fixture, in three blocks at one and five workers. It records each pair and reports baseline/count p50, p95 and mean plus the mean and median paired difference. Juror blocks have 100 pairs; whole-round blocks have 40 (their p95 estimates are consequently less precise). Every juror and every rate filter is sampled, including empty/unrated views. The page experiment executes the actual controller with its normal dependency graph, authentication, DB queries and rendered HTML. It bypasses HTTP filters, sockets and browser assets; it is an application response measurement, not a claim about browser load time. Response status, the region nav and the count setting are checked to avoid timing a redirect or an unintended configuration.

The follow-up decision uses absolute application response costs and their repeatability, rather than treating a query percentage or a single noisy p95 as a binary UX test. It retains the one/five-worker comparison to expose contention. These local fixture results do not establish production capacity or a universal human-perception threshold. Unlike the category-query experiment, the paired experiment leaves the restored fixture's deleted marks unchanged; neither round query excludes deleted files, and the selection population is identical.

### Results

Query run `region-counts-20261025-103504` (100 timed samples after 20 warm-ups, each at one and five workers) used MariaDB 10.6.22, the existing synthetic `fixture-v3` dump and a 512 MiB InnoDB buffer pool. The test JVM was capped at 768 MiB and sbt at 512 MiB. The laptop was on mains; no test or other benchmark was started concurrently. During the query run the host had 2.06 GiB free RAM and 8.76 GiB free disk. This is the small local fixture experiment, **not** the 4.9 GiB production-like server experiment described in `bench/README.md`; that experiment's 8 GiB free-host-RAM prerequisite was not met. No production server or database was accessed and no power settings were changed.

| application query (D1) | p95, 1 worker (ms) | p95, 5 workers (ms) |
|---|---:|---:|
| category counts, uncached | 129.38 | 227.78 |
| category counts, cache hit | 0.012 | 0.014 |
| deleted regions + deleted pager count, live | 6.88 | 10.59 |
| juror DISTINCT baseline | 26.99 | 43.75 |
| juror counts, active filter | 55.80 | 45.38 |
| whole-round DISTINCT baseline | 450.17 | 808.14 |
| whole-round counts, unfiltered | 253.36 | 427.03 |
| whole-round counts, rate > 0 | 257.50 | 600.98 |

C1 passes by about 129 ms: retain the contest count cache. C2 does not trigger on this D1 run: keep deleted counts live. The earlier optional D2 run (`095757`, 115,101 files) measured Q1 p95 351.0/804.5 ms and deleted region counts 11.2/15.4 ms at one/five workers. That marginal deleted-list cost does not justify another cache and its deletion/restore invalidation paths. D2 was not rerun during completion; it is exploratory evidence.

The strict original R1 test fails this run (+28.81 ms at one worker), while the five-worker difference is only +1.62 ms. The one-worker count distribution has p50 22.56 ms, p95 55.80 ms and max 125.55 ms. Do not discard that tail or average it out of the report; use the repeated paired experiment to investigate it. R2 passes for both variants, and whole-round counts are faster than the baseline query.

Machine-readable query timings and conditions are retained in `docs/benchmarks/region-counts-2026-10-25.json`. Raw query output remains in `target/gatling/region-counts-20261025-103504.{txt,json}`. The completed paired run `region-pairs-20261025-103750` has 2,160 AB/BA pairs in 36 blocks and exited successfully. Ranges below are the minimum and maximum block estimates across three repeats, **not confidence intervals**. Negative deltas mean counts are faster.

| scenario | workers | baseline mean range (ms) | counts mean range (ms) | paired mean delta range (ms) | paired median delta range (ms) |
|---|---:|---:|---:|---:|---:|
| juror SQL | 1 | 22.87 to 30.86 | 23.79 to 32.24 | 0.93 to 1.38 | 0.27 to 0.91 |
| juror SQL | 5 | 46.63 to 49.95 | 48.24 to 52.56 | 0.85 to 2.62 | 0.95 to 1.62 |
| juror response | 1 | 64.82 to 72.86 | 65.59 to 73.39 | 0.52 to 1.75 | 0.75 to 1.21 |
| juror response | 5 | 126.06 to 134.83 | 126.40 to 132.15 | -2.68 to 2.65 | -1.85 to 2.74 |
| whole-round SQL | 1 | 296.18 to 302.59 | 171.82 to 180.98 | -124.36 to -121.61 | -122.85 to -115.11 |
| whole-round SQL | 5 | 582.68 to 645.15 | 323.72 to 347.80 | -297.35 to -258.97 | -280.25 to -254.98 |
| whole-round SQL, rated | 1 | 297.31 to 1168.54 | 174.44 to 309.59 | -858.95 to -120.73 | -145.30 to -114.89 |
| whole-round SQL, rated | 5 | 701.44 to 790.49 | 384.98 to 461.62 | -331.92 to -316.46 | -323.72 to -304.07 |
| whole-round response | 1 | 1471.96 to 1573.34 | 1328.41 to 1377.80 | -195.54 to -143.56 | -170.93 to -142.44 |
| whole-round response | 5 | 3060.92 to 3219.14 | 2790.85 to 2963.18 | -270.07 to -255.95 | -305.09 to -252.52 |
| whole-round response, rated | 1 | 1455.65 to 1495.81 | 1319.77 to 1350.65 | -156.64 to -118.48 | -145.93 to -123.72 |
| whole-round response, rated | 5 | 3065.58 to 3204.61 | 2786.12 to 2961.00 | -279.46 to -243.61 | -289.80 to -239.75 |

The juror SQL has a small repeatable cost (roughly 1 ms mean at one worker). Its baseline p95 varies 32.20–55.10 ms across the same three blocks, a 22.90 ms spread, while the counts p95 varies 33.86–60.72 ms. For the juror's complete application response, baseline means are 64.82–72.86 ms and counts add only 0.52–1.75 ms on average. At five workers, response differences range from -2.68 to +2.65 ms; the experiment does not resolve a consistent response regression under that load. This is not a claim that the SQL cost is zero.

Whole-round counts reduce both query and response means in every block, including the positive-rate variant. The whole-round response at one worker saves 143.56–195.54 ms unfiltered and 118.48–156.64 ms rated. At five workers the savings are 255.95–270.07 ms and 243.61–279.46 ms respectively. Existing gallery image aggregation remains the larger cost (around 1.3–1.6 s response means at one worker; 2.7–3.2 s at five), outside this region-filter change.

Tail variation is retained: the one-worker rated whole-round SQL block 3 includes a 26.178 s pause in the **baseline** DISTINCT query. Its baseline mean is 1,168.54 ms rather than about 300 ms; the counted mean also rises to 309.59 ms. Its paired median remains -145.30 ms. No block was discarded and the pause's cause is not established. A mid-run check still showed mains power and 1.54 GiB free host RAM. In the five-worker unfiltered response, block 1 p95 is 3,687.16 ms baseline versus 3,977.05 ms counted (+289.89 ms), while blocks 2 and 3 improve by 241.68 and 181.11 ms. Forty pairs cannot settle those tail differences precisely. Means/medians, tail observations and block-to-block variation are reported separately; the run is not a production capacity test.

**Decision:** `gallery.regionCounts = all`. The original SQL-only +5 ms gate is not retained as a UX veto: its outcome changes with tail variation, while the paired application responses show a roughly 1 ms juror cost and a substantial whole-round saving. Keep `juror` and `off` as operational switches. Subregion navigation remains names-only. Keep `regions.countCache = true`, deleted counts live, and add neither a round-count cache nor an index/migration. This decision follows the user's request to evaluate absolute user-facing cost and variation; it does not claim that the original R1 p95 test passed.

The machine-readable record includes every block, p50/p95/p99/max, paired deltas, conditions and the SHA-256 of the raw pairs. Raw pairs and the readable block log remain at `target/gatling/region-pairs-20261025-103750.{csv,txt}`. The normal/rated/grouped region links now preserve their active filters; the measured SQL/count paths are unchanged by that subsequent link fix.

Reproduction on Windows (direct launcher invocation preserves the quotes that `sbt.bat` strips):

```powershell
$launch = 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar'
java '-Xmx512m' '-Dfile.encoding=UTF-8' '-Dsbt.server.forcestart=true' '-jar' $launch 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dbench.only=Q1app,Q1hit,Q2,Q2app,Q4j,Q5app,Q4r,Q6all,Q6rated")' 'Test/runMain gatling.setup.RegionCountBenchmark'
java '-Xmx512m' '-Dfile.encoding=UTF-8' '-Dsbt.server.forcestart=true' '-jar' $launch 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dbench.blocks=3", "-Dbench.pairs=100", "-Dbench.roundPairs=40")' 'Test/runMain gatling.setup.RegionCountPairedBenchmark'
```

Both harnesses refuse external targets before accessing a fixture or changing data. Run them serially, on mains, with no tests or other benchmarks concurrently. The paired response part takes roughly half an hour on this laptop.

### Completion review and validation

The completion review fixed mixed-case campaign config lookup, explicit campaign precedence, monument-list and round-form country resolution, missing/uncounted Kyiv subregion handling, combined `rate`/`rated` predicates, and region links that lost their rated filter. Regression coverage exercises these cases and all three count settings, including whole-round vote deduplication. The campaign template remains a read-time fallback; the form stores the admin's actual value.

Validation completed on 2026-10-25 with the direct sbt launcher, UTF-8, a 512 MiB sbt heap, a 768 MiB test heap and serial test execution:

- Full Scala suite: **743 passed, 0 failed, 0 errors, 6 skipped, 1 pending** (sbt total 749; 8 min 7 s). Log: `target/region-completion-full-test-final.log`.
- JavaScript: **35 tests in 2 suites passed** as part of the same run.
- The full run exposed four SQL assertions left at the old LIKE-only region filter; these now assert the additional exact-region predicate and both bound parameters. It also exposed an existing asynchronous image-proxy test lifetime race: the test now awaits Play's response construction before stopping its app and deleting the temporary image. Both fixes pass in the complete rerun.
- The successful run still emits test-fixture data-source/pool shutdown diagnostics after assertions complete; these did not fail the run. No production shutdown code was changed.
- An independent agent reviewed the cumulative implementation against `1c1677a`, the completion changes, benchmark harnesses and saved results, including the raw-pair SHA-256. It reported **no actionable findings** and separately checked the final test fixes. Its review was read-only; the full suite above was run by the implementing agent.
- `git diff --check` passes. No deployment or production data change was performed; the release requirements below remain operational steps.

## 5. The monument id template from the campaign config

- `wlm_ua.conf` has `"fileTemplate": "Monument Ukraine"`, `wle_ua.conf` `"fileTemplate": "UkrainianNaturalHeritageSite"` (`Contest.fileTemplate`). No scalawiki change.
- `ContestKind.monumentIdTemplate(contest)`: the admin's value if set (non-blank), else the campaign config's. Every reader uses it: the region gates (round galleries, contest files), the import (`ImportParams` at enqueue), "Update monuments" and its button, the monument lists job (`listsOf`), and a distribution's pasted titles.
- No backfill: only contests 153 (WLM 2016) and 593 (WLE 2025) have none in production. The contests page shows the effective template, marked when it comes from the config; the form isn't pre-filled, so the config's value never becomes an explicit one on a save.
- Behaviour change for 153 and 593: imports fetch the files' text, and "Update monuments" and the monument list job appear.

## Deployment notes

- **No migration.**
- The release build resolves `scalawiki-wlx 0.7.0-SNAPSHOT` from the local Ivy repository: it picks up the snapshot published from `region-prefixes` (`4405aff`), which adds the nominations' `idPrefix`. Build the release on a machine with that snapshot (or publish it there first). That branch's base, `subregion-stat`, is 79 commits ahead of scalawiki's master.
- After deploying, run **"Update monuments"** on contests **153** and **593** (item 5): their files were imported without a template, so they have no monument ids, and their regions stay empty until then.
