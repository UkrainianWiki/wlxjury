# Database Performance Analysis

**Date:** 2026-03-25
**Schema baseline:** `conf/db/migration/default/B44__Baseline.sql`
**Method:** Cross-referenced schema indexes against all query patterns in `app/db/scalikejdbc/` and `app/db/scalikejdbc/rewrite/`

---

## Summary

11 tables. The primary performance problems are **missing composite indexes** on the three most-queried tables (`selection`, `rounds`, `round_user`) and a secondary **query structure issue** in the ranking queries. Every gallery page load and round management page hits these code paths.

---

## Critical Missing Indexes

### 1. `selection(jury_id, round_id)` — highest impact

The hottest query path in the application. Every juror gallery page load calls one of these:

| File | Line | Method |
|------|------|--------|
| `SelectionJdbc.scala` | 97–98 | `byUser` |
| `SelectionJdbc.scala` | 100–106 | `byUserSelected` / `byUserNotSelected` |
| `ImageJdbc.scala` | 208–224 | `byUserImageWithRating` |
| `ImageJdbc.scala` | 234–254 | `byUserImageWithRatingRanked` |

Current coverage: two separate single-column indexes (`jury_id`, `round_id`) — requires combining two B-tree lookups.

```sql
CREATE INDEX idx_selection_jury_round ON selection(jury_id, round_id);
```

---

### 2. `rounds(contest_id, active)` — critical path

Called on nearly every page load. No index at all on `rounds` beyond the PK.

| File | Line | Method |
|------|------|--------|
| `Round.scala` | 268–271 | `activeRounds(contestId)` |
| `Round.scala` | 273–297 | `activeRounds(user)` — also used by gallery |
| `RoundController.scala` | 39 | every rounds page |
| `GalleryController.scala` | 165–166 | every gallery view |

```sql
CREATE INDEX idx_rounds_contest_active ON rounds(contest_id, active);
```

---

### 3. `round_user(round_id, active)` — juror listing

Existing UNIQUE index is `(user_id, round_id)` — wrong leading column for round-first lookups.

| File | Line | Method |
|------|------|--------|
| `RoundUser.scala` | 368 | `activeJurors` |
| `Round.scala` | 273–297 | EXISTS subquery checking active jurors |

```sql
CREATE INDEX idx_round_user_round_active ON round_user(round_id, active);
```

---

## High Priority Missing Indexes

### 4. `users(contest_id)`

| File | Line | Method |
|------|------|--------|
| `User.scala` | 246–247 | `findByContest` |
| `User.scala` | 357–369 | `loadJurors` |
| `RoundController.scala` | 128 | every round edit page |

```sql
CREATE INDEX idx_users_contest ON users(contest_id);
```

---

### 5. `selection(page_id, jury_id, round_id)`

| File | Line | Method |
|------|------|--------|
| `SelectionJdbc.scala` | 126–129 | `findBy` |
| `SelectionJdbc.scala` | 149–157 | `destroy` |
| `SelectionJdbc.scala` | 159–169 | `rate` (called on every vote) |

```sql
CREATE INDEX idx_selection_page_jury_round ON selection(page_id, jury_id, round_id);
```

---

### 6. `selection(round_id, page_id)`

| File | Line | Method |
|------|------|--------|
| `SelectionJdbc.scala` | 108–124 | `byRoundAndImageWithJury` |
| `SelectionJdbc.scala` | 203–209 | `removeImage` |
| `ImageJdbc.scala` | 192–199 | `roundsStat` |

```sql
CREATE INDEX idx_selection_round_page ON selection(round_id, page_id);
```

---

### 7. `users(wiki_account)`

| File | Line | Method |
|------|------|--------|
| `User.scala` | 275–278 | `findByAccount` — OAuth login path |

```sql
CREATE INDEX idx_users_wiki_account ON users(wiki_account);
```

---

## Medium Priority

### 8. `selection(round_id, rate)` — `byRating`

`ImageJdbc.scala:322–336` filters `WHERE rate = ? AND round_id = ?`. Current `selection_rate_index` is single-column on `rate`; with round_id as leading column this becomes a range scan:

```sql
CREATE INDEX idx_selection_round_rate ON selection(round_id, rate);
```

### 9. `criteria_rate(criteria)` — no index on criteria column

Used in LEFT JOINs at `ImageJdbc.scala:295–320` and `SelectionJdbc.scala:131–147`. The join is on `selection` (indexed), but filtering by `criteria` has no coverage.

```sql
CREATE INDEX idx_criteria_rate_criteria ON criteria_rate(criteria);
```

---

## Query-Level Issues

### Ranking query executes identical subquery 3×

`ImageJdbc.scala:234–284` and `ImageDbNew.scala:243–283` materialize `WHERE jury_id = ? AND round_id = ?` three times (as `s1`, as `s2` in LEFT JOIN, and in the outer). MariaDB 10.6 supports window functions:

```sql
-- Current (3× subquery):
SELECT count(s2.page_id) + 1 AS rank, i.*, s1.*
FROM images i
JOIN (SELECT * FROM selection WHERE jury_id = ? AND round_id = ?) AS s1 ON i.page_id = s1.page_id
LEFT JOIN (SELECT * FROM selection WHERE jury_id = ? AND round_id = ?) AS s2 ON s1.rate < s2.rate
GROUP BY s1.page_id

-- Better (window function, 1× scan):
SELECT ROW_NUMBER() OVER (ORDER BY s.rate DESC, i.page_id ASC) AS rank, i.*, s.*
FROM images i
JOIN selection s ON i.page_id = s.page_id
WHERE s.jury_id = ? AND s.round_id = ?
ORDER BY rank
LIMIT ? OFFSET ?
```

### `RoundUserStat` join order — `Round.scala:332–338`

Starts scan from `users` (large) then filters to selections. Should start from `selection WHERE round_id = ?`:

```sql
-- Current (full users scan first):
SELECT u.id, s.rate, count(1) FROM users u
JOIN selection s ON s.jury_id = u.id
WHERE s.round_id = ?
GROUP BY u.id, s.rate

-- Better (index scan first):
SELECT s.jury_id, s.rate, count(1) FROM selection s
JOIN users u ON s.jury_id = u.id
WHERE s.round_id = ?
GROUP BY s.jury_id, s.rate
```

### `byRegionStat` uses `substring()` on `monument_id` — `ImageDbNew.scala:108–117`

`substring(i.monument_id, 1, 2)` cannot use any index. The `adm0` column in `monument` already holds this value with an index (`adm0_index`). Join through `monument` instead.

---

## Schema Design Issues

### `monument` has no PRIMARY KEY

`id varchar(190)` is the natural key but declared as a plain index, not a constraint:

```sql
ALTER TABLE monument MODIFY id varchar(190) NOT NULL;
ALTER TABLE monument ADD PRIMARY KEY (id);
ALTER TABLE monument DROP INDEX monument_id_index;
```

### `comment.id` — redundant UNIQUE KEY alongside PRIMARY KEY

```sql
ALTER TABLE comment DROP INDEX id;
```

### `users.roles` — comma-separated string

`User.scala:222` splits on comma at read time. Prevents index use for role filtering. Every `WHERE roles LIKE '%admin%'` is a full scan. Long-term: separate `user_roles(user_id, role)` table.

---

## Proposed Migration: `V45__Add_performance_indexes.sql`

```sql
-- Critical
CREATE INDEX idx_selection_jury_round      ON selection(jury_id, round_id);
CREATE INDEX idx_rounds_contest_active      ON rounds(contest_id, active);
CREATE INDEX idx_round_user_round_active    ON round_user(round_id, active);

-- High priority
CREATE INDEX idx_users_contest             ON users(contest_id);
CREATE INDEX idx_users_wiki_account        ON users(wiki_account);
CREATE INDEX idx_selection_page_jury_round ON selection(page_id, jury_id, round_id);
CREATE INDEX idx_selection_round_page      ON selection(round_id, page_id);

-- Medium priority
CREATE INDEX idx_selection_round_rate      ON selection(round_id, rate);
CREATE INDEX idx_criteria_rate_criteria    ON criteria_rate(criteria);

-- Schema fixes
ALTER TABLE monument MODIFY id varchar(190) NOT NULL;
ALTER TABLE monument ADD PRIMARY KEY (id);
ALTER TABLE monument DROP INDEX monument_id_index;
ALTER TABLE comment DROP INDEX id;
```

---

## Expected Impact

| Area | Estimated improvement |
|------|----------------------|
| Gallery page load (byUserImageWithRating) | 20–40% faster |
| Round management page (activeRounds) | 30–50% faster |
| Vote submission (rate) | 15–30% faster |
| Juror listing for a round | 40–60% faster |
| OAuth login (findByAccount) | 50–80% faster |
| Ranking queries (with window function rewrite) | 50–70% faster |
