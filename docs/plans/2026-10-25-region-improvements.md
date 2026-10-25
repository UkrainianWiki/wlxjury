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

- **D1:** the Gatling fixture (`fixture-v3`): 38,367 files in the contest's category, 20 jurors, a binary and a rated round, 10 jurors per image (383,670 selection rows, ~19k per juror), 1% of the files marked deleted by the harness.
- **D2 (optional):** a category of three copies of D1's files (~115k).
- D3 and D4 (prod-like server) are not run: the review's measurements settle the criteria, and the laptop lacks the RAM D4 needs (bench/README.md §2).
- Tool: `RegionCountBenchmark` (DB level, literal SQL for today's queries and the candidates). No Gatling A/B for the round galleries.

### Decision criteria (written before the measured runs)

Each query: 20 warm-up runs, then 100 timed (or 60 s), 1 and 5 threads; the decision uses p95 at 1 thread, checked at 5. Runs on mains power with nothing else heavy running (bench/README.md §1-2), back to back.

- **C1, keep the contest files cache:** keep it if the uncached category counts (Q1) cost ≥ 20 ms p95 more than a cache hit on D1.
- **C2, cache the deleted lists:** only if the deleted lists' counts (Q2) are > 10 ms p95 on D1 with 1% deleted (D2 if run). Otherwise they stay live.
- **R1, juror counts in round galleries (with the rate filter):** on by default if Q5's p95 ≤ Q4j's p95 + 5 ms on D1 (Q5 replaces Q4j, the DISTINCT a juror's gallery runs today).
- **R2, whole-round counts for organisers (`user/0`):** on only if Q6b's p95 ≤ Q4r's p95 + 100 ms on D1, for the unfiltered and the `rate > 0` variant. Otherwise organiser views show names only, as now. No new index (V67) and no round-count cache.

Resulting `gallery.regionCounts`: `all` if R1 and R2 pass, `juror` if only R1, `off` if R1 fails.

### Results

To be filled in after the runs.

## 5. The monument id template from the campaign config

- `wlm_ua.conf` has `"fileTemplate": "Monument Ukraine"`, `wle_ua.conf` `"fileTemplate": "UkrainianNaturalHeritageSite"` (`Contest.fileTemplate`). No scalawiki change.
- `ContestKind.monumentIdTemplate(contest)`: the admin's value if set (non-blank), else the campaign config's. Every reader uses it: the region gates (round galleries, contest files), the import (`ImportParams` at enqueue), "Update monuments" and its button, the monument lists job (`listsOf`), and a distribution's pasted titles.
- No backfill: only contests 153 (WLM 2016) and 593 (WLE 2025) have none in production. The contests page shows the effective template, marked when it comes from the config; the form isn't pre-filled, so the config's value never becomes an explicit one on a save.
- Behaviour change for 153 and 593: imports fetch the files' text, and "Update monuments" and the monument list job appear.

## Deployment notes

- **No migration.**
- The release build resolves `scalawiki-wlx 0.7.0-SNAPSHOT` from the local Ivy repository: it picks up the snapshot published from `region-prefixes` (`4405aff`), which adds the nominations' `idPrefix`. Build the release on a machine with that snapshot (or publish it there first). That branch's base, `subregion-stat`, is 79 commits ahead of scalawiki's master.
- After deploying, run **"Update monuments"** on contests **153** and **593** (item 5): their files were imported without a template, so they have no monument ids, and their regions stay empty until then.
