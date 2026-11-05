# Database access practices

The rules every change to SQL, schema, transactions or migrations follows: the rule, why, and
an example in this repository. The counterpart of
[secure-coding.md](../security/secure-coding.md) (whose §1 SQL and §13 logs this document
extends, not replaces). Reviewers tick [review-checklist.md](review-checklist.md).

Stack: Play 3, Scala 2.13, ScalikeJDBC 4.3, MariaDB 10.6 (production; 10.11 after the server
move, `docs/plans/2026-10-19-mariadb-10.11.md`), Flyway 13 run by `db.Migrations`
(`docs/plans/2026-11-03-flyway.md`), HikariCP in the app, DBCP in the tests.

**Guards** are specs (§10 lists them); a hit is fixed, or that one file (or, in
`MigrationSchemaSpec`, that one name) is allowlisted with a one-line reason, never silenced by widening a pattern (the security rule). §11 lists where
the code doesn't follow a rule yet. **Sources:** the
write-path document (`docs/plans/2026-11-03-write-path-simplification.md`, matrix §2,
decisions W1–W10 §9, results §11), the round-images plan §5 and §12–§13, the criteria plan
(C1/C2), the follow-ups plan B9–B12, `docs/history/db-performance-analysis.md` (2026-03), the PR
review triage (`docs/plans/2026-11-03-pr-review-triage.md`, only on the branch
`origin/stack/series-doc`: `git show origin/stack/series-doc:docs/plans/2026-11-03-pr-review-triage.md`).

## Contents

1. [Query safety](#1-query-safety)
2. [Schema and keys](#2-schema-and-keys)
3. [Indexes and performance](#3-indexes-and-performance)
4. [Benchmarks](#4-benchmarks)
5. [Transactions](#5-transactions)
6. [Isolation](#6-isolation)
7. [Locking and deadlocks](#7-locking-and-deadlocks)
8. [Migrations](#8-migrations)
9. [Tests](#9-tests)
10. [Guards](#10-guards)
11. [Where the code doesn't follow these yet](#11-where-the-code-doesnt-follow-these-yet-for-triage)

## 1. Query safety

**1.1 Values are bound.** `sql"…"`/`sqls"…"` interpolation, `sqls.eq`/`sqls.in`, `where.eq`,
CRUDMapper helpers. Never `SQL(s"…")`, string concatenation or `createUnsafely` of a value.
*Why:* Vuln 1 (the region filter) was an interpolated value. *Example:* `VoteJdbc.voteId`
(`VoteJdbc.scala:187`: page, juror and round bound).

**1.2 `SQLSyntax` and `${…}` only for code-owned identifiers.** A table, column, order or
direction interpolated as `SQLSyntax` comes from a constant or an enumerated set in server
code (`RoundList.rankKey`, `BinaryOrder`), never from the request. `createUnsafely` only in
`RawSqlSpec.Allowed`'s files. *Why:* a `SQLSyntax` is pasted, not bound. *Example:*
`VoteJdbc.lockPairs(table: SQLSyntax, …)` takes `vote` or `vote_aside` from its callers.

**1.3 `LIKE` patterns are escaped.** A request value in a `LIKE` is bound *and* has `\`, `%`
and `_` escaped (`replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")`), with the
wildcard added after. *Why:* bound is injection-safe but `%` from the URL still widens the
match and turns a range scan into a full one. *Today:* `ImageDbNew.scala:466` binds
`region + "%"` from the path unescaped (§11, V1).

**1.4 `IN` lists are bounded.** A list from data (ids, titles) is `distinct`, sorted when it
also locks (§7.3), and chunked: titles 1,000 (`ImageJdbc.scala:131`), pages 2,000
(`RoundImageJdbc.Chunk`), deletes 5,000 (`VoteJdbc.DeleteChunk`), windows 20,000
(`RoundDistributionHandler.MaxWindow`). An empty list never reaches SQL (`IN ()` is a syntax
error): guard with `if (ids.isEmpty)`. *Why:* statement size, optimizer cost (a long `IN`
switches to a range scan) and lock footprint grow with the list.

**1.5 Comparisons know the collation.** Tables are `utf8mb4_general_ci`: case-insensitive,
accent-insensitive, `PAD SPACE` (`'a ' = 'A'`). Where case or trailing spaces matter (role
values, file titles, tokens, passwords' legacy checks) compare with `BINARY` and keep the
plain comparison for the index: `WHERE i.title IN ($c) AND BINARY i.title IN ($c)`
(`ImageJdbc.scala:133`). Allowlists in CHECK constraints are `BINARY` (V76:
`CHECK (BINARY role IN (…))`; V84's `round_image_status_chk`). A unique key on a `_ci` column
treats `Alice@x` and `alice@x` as one: normalise in code first (`LoginController`'s `login`
is trimmed and lower-cased). *Why:* under `_ci` and `PAD SPACE` a plain `IN ('jury')` also
accepts `Jury` and `jury `; V76 compares byte for byte (production had hand-edited `jury1`).

**1.6 Database errors in logs are redacted.** Log `SqlLogging.describe(e)` (class, SQLState,
vendor code) and the statement template, never `e.getMessage` of a `java.sql.SQLException` or
the exception itself where its cause chain has one: MariaDB's text quotes values
(`Duplicate entry 'alice@example.org' for key 'users_email_uidx'`). R227-1 adds a redacting
converter (keep code, SQLState, key name, template; replace quoted values by a keyed hash).
`org.mariadb.jdbc` stays at ERROR in the production logback. *Today:* `ErrorHandler.scala:63`
and `JobScheduler.scala:377,444` log the whole exception (§11, V2). Users never see driver
text (`ErrorTextSpec`, secure-coding §12).

**1.7 Escape at render, never in SQL.** Text is stored as entered (no HTML entities, no
stripping) and escaped by Twirl at output; JavaScript writes it with `textContent`.
*Why:* escaping in SQL double-escapes and breaks search and uniqueness. See secure-coding §8.

**1.8 Strict mode, `INSERT IGNORE`, affected rows.** The app assumes the server's
`STRICT_TRANS_TABLES` (MariaDB's default; nothing sets `sql_mode`): a too-long or invalid value
fails the statement instead of being truncated, so lengths are checked in forms (§2.5).
Pre-deploy: `SELECT @@sql_mode;`. `INSERT IGNORE` turns *every* error into a warning, not only
the duplicate key (truncation, bad values, FK failures): use it only where a duplicate is the
one expected case and the input is checked, else `ON DUPLICATE KEY UPDATE id = id`. Row
counts: Connector/J reports *found* rows (`useAffectedRows=false`, the default), so an
`UPDATE` that changes nothing returns the matched count, and `INSERT … ON DUPLICATE KEY UPDATE`
returns 1 for an insert, 2 for a changed row, 1 for an unchanged one; don't read "changed"
from them. *Today:* `CategoryJdbc.scala:65-66` (§11, V7).

## 2. Schema and keys

**2.1 Naming** (P0, V82; CLAUDE.md "Table names"). New tables singular snake_case (`job`,
`round_image`, `criterion`), link tables `<owner>_<thing>` (`round_criterion`), foreign key
columns `<table>_id`. Index and constraint names start with the table name:
`<table>_<cols>_idx`, unique `<table>_<cols>_uidx`, foreign key `<table>_<col>_fk`, check
`<table>_<col>_chk`. *Example:* V83's `round_criterion_round_criterion_uidx`,
`round_criterion_round_id_fk`; V84's `round_image_status_chk`. Old plural tables keep their
names. Guard: `db.MigrationSchemaSpec` (from V83; it checks the table prefix, the suffix and
that keys are named, not the `<cols>` part or singular names).

**2.2 Every table has a primary key**, a surrogate `BIGINT UNSIGNED AUTO_INCREMENT` or the
natural pair (`round_image (round_id, page_id)`, `round_criterion (round_id, position)`).
*Why:* InnoDB clusters by the primary key; without one it clusters by the first `UNIQUE NOT
NULL` key, else by a hidden row id nothing can reference. The key that clusters should be
declared as such, so plans, dumps and FKs agree. Production's `vote` (and `vote_aside`,
`vote_orphan`) and legacy `criteria` have only `UNIQUE KEY id` (the V53 dump;
`ConvertToInnoDbMigrationSpec:220` keeps it), `monument` none on fresh databases (V46's edit,
flyway plan §1): §11, V6. V83 gave `criteria_rate` a primary key.

**2.3 Business invariants are unique keys**, not code checks: one vote per
(page, juror, round) (`vote_page_jury_round_uidx`), one rate per (vote, criterion)
(`criteria_rate_vote_criterion_uidx`), one active job per dedupe key
(`job_active_dedupe_uidx`), one round per submit token. Code catches the violation by the
key's name (`RoundService` matches `submit_token_uidx`) and answers with a message key.
*Why:* a check-then-insert races; the key doesn't.

**2.4 Foreign keys where the parent outlives the child** and the cascade is wanted
(`round_image` → `round`, `images`; `round_criterion` → `round`, `criterion`;
`vote.page_id` → `images` (V58); `round_image_event` → `round` (V88), but not → `images`: its
check would share-lock the image row after `round_image`, out of the §7.1 order; an image
deleted keeps its events, which the round's history shows as `#pageId`). **Exceptions:** `vote.round_id`, `vote_aside` and
`criteria_rate.vote_id` have none: a vote moves between `vote` and `vote_aside` keeping its
id (no single parent table), and an FK check takes a share lock on the parent row for every
child row written, which for `round` in a 15k-row step is a lock the round-first order must
account for (review B1 on `round_image`'s key). Their integrity is `RoundImageInvariants`
(1–8) and the repair job (`round_image_check`, round-images §5).

**2.5 Column types and lengths match the forms.** A `VARCHAR(n)` column's form field has
`maxLength(n)` (and a typed mapping for numbers), so too-long input is a 400 with a field
error, not "Data too long" under `STRICT_TRANS_TABLES` and a 500. *Today:* R225-1:
`users.email` and `account_token.email` are `VARCHAR(190)`, the forms have no limit;
`fullname` and other free text likewise (§11, V3). Example done right:
`ApiTokensCard.scala:39` (`maxLength(ApiTokenService.NameMaxLength)`).

**2.6 TIMESTAMP columns are explicit.** `NULL` (or `NULL DEFAULT NULL`), or `NOT NULL DEFAULT
…`, never left to the server: MariaDB 10.10 turned `explicit_defaults_for_timestamp` on, so a
bare `TIMESTAMP NOT NULL` differs between 10.6 and 10.11 (V86 fixed V2, V3, V77). Guard:
`MigrationNamingSpec` (from V85).

**2.7 Charset and engine.** `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE =
utf8mb4_general_ci` on every new table (V83, V84), stated, not inherited. InnoDB only: MyISAM
has no transactions, row locks or foreign keys (V55.1 converted production;
`ConvertToInnoDbMigrationSpec`). Tables are `ROW_FORMAT = DYNAMIC` since V55.1, so an index
key may be 3,072 bytes (768 `utf8mb4` characters); 190 is the email convention (`users.email`,
`account_token.email`), not a limit. Pre-deploy for a new key on a legacy table:
`SELECT TABLE_NAME, ROW_FORMAT FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()`
(all `Dynamic`). Guard: `MigrationSchemaSpec` (engine and charset).

**2.8 Generated columns over copies.** A value derived from a row (`round_image.region AS
(…) STORED`, V84) is a generated column; a copy kept in another table (`vote.monument_id`)
must be written under the source row's lock (h6) and is checked by an invariant (6).
*Why:* the copy drifted under concurrency (write-path §2, break 6).

## 3. Indexes and performance

**3.1 `EXPLAIN` new queries on large tables.** `vote` (~3M rows in production, 384k a round
on the fixture), `vote_aside`, `round_image`, `images`, `criteria_rate`. Run `EXPLAIN` (or
`ANALYZE FORMAT=JSON`) on the Gatling fixture and record the plan in the plan doc: which
index, `Using index` (covering), `Using filesort`, rows examined. A spec may pin the plan
with `FORCE INDEX` where tiny test data would mislead the optimizer (`VoteGalleryIndexSpec`).

**3.2 Covering indexes and deferred joins for pages.** Page over the narrow table on an
index that covers the WHERE, ORDER BY and selected columns, then join the page's ids to the
wide tables: `ImageDbNew.deferredJoinPage` (`:168`) and `deferredGroupedPage` (`:192`, B12:
−103 to −337 ms). A column missing from the index makes every row a clustered-index read
(B12: the rated order's `s.monument_id`, 820 vs 180 ms).

**3.2a Paging.** Every paged `ORDER BY` ends in a unique tie-breaker (the galleries' `…,
monument_id, page_id`), else rows repeat or vanish between pages. Deep pages and "around this
file" use keysets (`ImageDbNew.Keyset`, `RoundList.scala:263-277`), not large `OFFSET`s. An
outer sort that carries `TEXT`/`BLOB` columns spills to an on-disk temporary table: sort the
ids narrow, join the wide columns after (the deferred join).

**3.3 No filesort on paged galleries.** The index's column order *and direction* match the
ORDER BY: V85's `vote_jury_round_ratedesc_mon_page_idx (…, rate DESC, …)` on 10.8+ (10.6
ignores `DESC`: the version-conditional DDL of §8.7, a startup WARN as backstop).

**3.4 Counts are cached or precomputed, not recounted per view.** `RoundImageCounts` (images
per round) and `RegionCountCache` (a category's region counts) are invalidated *after*
commit by the writers that change them, with a version check against a load racing the
commit, a TTL for outside writers, one app instance assumed. A new count on a hot page
reuses that pattern or reads `round_image` (one row per file and round).

**3.5 Batches fit `max_allowed_packet`.** A JDBC batch is one packet: 5,000 vote rows
(~200 KB, `DistributeImages.InsertChunk`); a distribution step is 15,000 rows in one
transaction (`jobs.roundDistribution.chunkRows`). The tests keep 1 MB so an oversized batch
fails there first (`DistributeImagesBatchSpec`); production's default is 16 MB.

**3.6 No N+1.** No query per row of a list: read the page's ids, then one `IN` query per
related table (chunked, §1.4), or a join. `hasManyThrough` loads lazily per entity: use it
for one entity, not in a loop. Commons-sized loops (titles, categories) are chunked reads.

**3.7 `round_image` for "which files are in the round".** Since V84 the round's files, their
status, region and monument copy are one row per (round, file): use it for membership,
counts by region and the all-jurors orders (W10, −497 ms measured) instead of `DISTINCT`
over `vote`. Use `vote` for per-juror rates and sums. Rewrites go behind a parity spec (§9.3).

**3.7a Derived counts read exactly, or from the source.** `round_image`'s vote counts (P2a,
V89) are kept by marks and a background recompute, so the stored numbers alone may be seconds
old. A reader of them (`RoundImageQuery`, behind `roundImage.read`; round-images §17) overlays
the files marked dirty with their votes' counts *in the same statement* (one READ COMMITTED
snapshot: a file's stored counts equal its votes or the file is marked), and reads the votes
for a round whose generation isn't clean (`RoundCounts.generationClean`). Exact consumers (the
CSV, a top-N, a "min average rate", a finalize) read the votes. A new count on `round_image`
follows the same rule, with a parity spec over both sources and a dirty case
(`GroupedListParitySpec`).

**3.7b One vocabulary for an order on two sources.** A list's ORDER BY and rank keys are named
once over the vote rows (`ListOrder`, `BinaryOrder`, `RankKey`) and translated for the counts
(`RoundImageQuery.translate`); an unknown column reads the votes rather than guess. Averages
in keys are exact integers (`sum * 10^9 DIV n`, `AverageRule.keySql`), never a DOUBLE whose
text form a JDBC read may round: a key read back from SQL must equal the one computed in memory.

**3.8 Indexes cost writes.** Each secondary index on `vote` is one more insert-intention and
row lock per vote row in a 15k-row step. Add one with a measured read gain, drop one nothing
uses (§11 V11 lists the candidates; check with `information_schema.STATISTICS` and the plans
first).

## 4. Benchmarks

Moved to [performance/practices.md](../performance/practices.md) §2 (2026-11-03): reuse, then a
paired pilot on the app path; paired and interleaved runs; the same settings for both (SQL
logging off) and like-with-like comparisons; absolute deltas and variation; recording with
commit ids; the bench server; production data out of repositories; the stress harness. The
Gatling fixture and `FixtureVersion` are §1.2 there.

## 5. Transactions

**5.1 One owner per transaction.** A write transaction on the tables of the §7.1 lock order
(W3's scope) starts in one place: `WriteTx.write` (request paths, `WriteTx.scala:22`), a job
step (`JobScheduler`: one transaction per step, the `job` row locked first), or the caller of a
DAO's `…In(…)(implicit session)` method. Other tables' transactions (accounts, tokens, round
members) may use a plain `DB.localTx`, without retry (§11, V8).
`…In` methods never open their own; a DAO method without a session wraps its `…In` in
`WriteTx.write` (`VoteJdbc.rate`, `VoteJdbc.scala:177-184`). *Why:* a `DB.localTx` nested in
a caller's transaction is a second connection: it doesn't see the caller's writes and can wait
on the caller's own locks until 1205.

**5.1a Isolation is the pool's.** Don't set it per transaction: every connection is READ
COMMITTED (§6.1), so `ReadCommitted.localTx` is redundant (its one caller,
`CriteriaRate.scala:46`, is a leftover of C2) and a per-transaction level would hide a pool
misconfiguration from `/ready`.

**5.2 `AutoSession` defaults are for reads.** A DAO method defaulting to `AutoSession` runs
each call on its own autocommit connection. Inside a transaction pass the session; writers
take `(implicit session: DBSession)` without a default. The round-first guard
(`RoundWrites.requireRounds`) refuses a structural write outside a transaction.

**5.3 Nothing outside the database before commit.** No HTTP (Commons, monument lists), mail
or file I/O inside a transaction: import jobs fetch before the step (`prepare`, on
`jobs.fetchWorkers`). Cache invalidations, log lines and job wake-ups run after commit
(`StepContext.afterCommit`, `JobHandler.scala:13`; `RoundImageCounts.invalidate` after the
block). *Why:* a rolled-back or retried transaction must leave no trace (W3), and a held lock
must not wait on the network.

**5.4 Decide inside the transaction.** Every decision a write depends on is read inside the
transaction, after its locks (a retry reruns only the body). A read before it may only choose
what to lock (`RoundWrites.lockForFiles`' unlocked lookup, then `lockMissing` for what it
missed).

**5.5 Short transactions.** A step holds its locks for well under a second: 15,000 vote rows
(`jobs.roundDistribution.chunkRows`), import pages of 500 files (with `apihighlimits`; 50 without, `CommonsSource.maxIdsPerRequest`), `sync` in 2,000-page groups.
A request transaction does one action. The Hikari pool has 30 connections
(`conf/application.conf:67`); a request waiting on a round row holds one while it waits.

**5.5a Long work is a job.** Work that may take more than a request's few seconds (filling or
redistributing a round, imports from Commons, checks over every round) runs as a job on the `job`
table (`JobScheduler`, a `JobHandler` per kind), never on a request thread: one short
transaction per step (§5.5), a lease for restart recovery, Cancel and Retry, and a dedupe key
(§2.3). The request only creates the job and answers. A running job whose lease expired is
taken over at most `jobs.maxAttempts` times (claims since the user's last explicit Retry,
`job.attempts`, default 5; a claim whose run recorded an outcome, a failure or a graceful
handback of a job still held, is given back, so only runs that ended without one count); then
the next poll fails it
(`job.error.attempts`) with its handler's cleanup, so a job whose runs never record an outcome (a
step that ends the JVM) isn't rerun every lease forever. Implicit resumes (the round form again,
"Distribute new files") keep the count and refuse a job given up this way. A handler's `onRetry`
marks what its last run may have left stale, and the first resumed step recounts it (the
distribution's `imagesBefore` follows the round's other files, `othersBefore`, while its own
files are checked exactly, `ownBefore`). *Why:* a request that held a thread and a
transaction for minutes hit Play's and Apache's timeouts and blocked other admins (background
distribution and import, `c3f8e68b`, `7e20a35d`).

**5.6 Blocking JDBC and the pool budget.** JDBC blocks its thread: actions with database work
run on the blocking dispatcher (`Secured.withAuthOn(blocking)`, `blocking-dispatcher` 8
threads; `sign-in-dispatcher` 4; job workers 2 + fetch workers 2), never on Play's default
pool. Together they stay well under Hikari's 30 connections, so a thread waits for a lock, not
for a connection (`connectionTimeout = 30 seconds`, then an error). A new dispatcher or pool
size is checked against that budget. *Today:* `ImageProxyController.scala:104` runs its lookup
as `Future(blocking(…))` on the default execution context (§11, V9).

## 6. Isolation

**6.1 READ COMMITTED on every connection** (W1). Production's pool:
`db.default.hikaricp.transactionIsolation = "TRANSACTION_READ_COMMITTED"`
(`conf/application.conf:70`; `production.conf` must not override it: `ProductionConfigSpec`);
the tests' DBCP pool through the driver URL (`SharedTestDb.readCommittedUrl`). `/ready`
answers 503 unless a pooled connection reports `READ-COMMITTED` (`ReadinessCheck.isolation`;
`ReadinessControllerSpec`, `ReadinessDatabaseSpec`). Write-path §4 (b): −60 % deadlocks. The
counts' recompute depends on it (a plain read of the votes after its locks) and checks it in
each window's transaction (`@@tx_isolation`; MariaDB 10.6 has no `@@transaction_isolation`): at
another level it writes nothing and logs `round_counts_skipped reason=isolation` (round-images
§16).

**6.2 What it implies.**
- **No gap locks for reads.** A locking read or a searched `UPDATE`/`DELETE` locks the rows it
  finds, not the gaps: "no such row yet" is not protected. Uniqueness is a unique key (§2.3),
  never a `FOR UPDATE` that found nothing. **But** duplicate-key and foreign-key checks still
  take next-key (gap) locks at READ COMMITTED: `INSERT` into a unique index, `INSERT IGNORE`,
  `ON DUPLICATE KEY UPDATE` and `REPLACE` lock the duplicate's index entry and the gap before
  it, and a child row's FK check share-locks the parent (`vote.page_id` → `images`). So
  neighbouring upserts can still deadlock: the criteria vote's upsert
  (`CriteriaRate.scala:63-64`) is why its sum is a plain read (`:65-69`).
- **Each statement sees the latest commit.** Two plain reads in one transaction can disagree;
  a value a write depends on is read with a lock (§7.4) or re-checked in the write's `WHERE`.
- **Subqueries in writes don't lock.** `INSERT … SELECT` and `UPDATE … WHERE (SELECT …)` read
  their source consistently, unlocked: a check there doesn't stop a concurrent writer. Lock,
  then write: the juror vote reads `round_user` and `round_criterion` `LOCK IN SHARE MODE`
  before its `UPDATE` (`VoteJdbc.scala:190-200`; C2 F2).
- RR-era locking reads ("as committed now") stay correct.

**6.3 Binlog.** With `log_bin` on, `binlog_format` must be `MIXED` or `ROW`: `STATEMENT` with
READ COMMITTED refuses writes. Pre-deploy: `SELECT @@log_bin, @@binlog_format;`.

## 7. Locking and deadlocks

**7.1 Lock order.** `job` (a step's own row) → `round` → `images` → `vote` →
(`round_user`, `round_criterion`: share-locked by a vote after its row) → `criteria_rate` →
`vote_aside` → `round_image` → `round_image_dirty` → `round_image_event` (round-images §5,
§15 and §16, write-path §8). A juror's vote whose rate changes marks its file last
(`RoundCounts.markFile`: an upsert of its `round_image_dirty` entry, an exclusive record lock;
not `INSERT IGNORE`, whose shared lock deadlocked votes re-inserting an entry the recompute had
just deleted); it never locks `round` or `round_image`. The counts' recompute locks the round
for update, then a window's `round_image` rows, then their dirty entries, and reads `vote`
plainly; a bulk writer bumps its round's `counts_gen` under the round lock it holds. A
criteria change (`RoundCriterion.update`) locks the round and its votes before writing
`round_criterion`, so it never waits on a vote holding them. Within a table in key order:
rounds by id, files by `page_id`. `round_image` last in every transaction but for a
disqualify's or requalify's events (V88), inserted after its rows (`RoundImageEventJdbc.record`,
called by `RoundImageJdbc.disqualify` and `requalifyRows`; the round held for update, so the
insert's foreign key check on `round` waits on nothing); `criteria_rate` written only while
its vote row is locked.

**7.2 Round first** (W2). A structural write (inserting, deleting or moving votes or aside
rows, `round_image` rows, criteria, monument copies) locks its round(s) `FOR UPDATE` first, in
id order: `RoundWrites.lock` (`RoundWrites.scala:68`). A writer that starts from files locks
the files' rounds (`lockForFiles`, `:89`), then the files (`lockFiles`, page order);
`lockMissing` takes a round the lookup missed (out of order: a deadlock it causes is retried).
The DAO's structural writers `require` the recorded locks at run time (`requireRounds`,
`requireFiles`; `RoundWritesGuardSpec`); a raw write elsewhere fails
`security.StructuralWritesSpec` until listed with the lock it takes. Juror votes are row-level
and never lock the round.

**7.3 Lock by primary key, sets in key order.** A single-row write finds its id with a plain
read and locks `WHERE id = ?` (h4, `VoteJdbc.rateIn`): locking through the secondary unique
key, then the clustered row, deadlocked with a delete that held them the other way round
(break 10). h4 narrows break 10 but doesn't remove it (a delete that found the row by another
index still meets the juror's statement), so the juror vote keeps `WriteTx`'s retry. Guard:
`db.VoteRowLockSpec` (a tripwire for the pre-h4 literal SQL). A set of rows is locked with one
`ORDER BY <key> FOR UPDATE` (`ImageJdbc.scala:311`), never row by row in listing order
(break 3, h2).

**7.4 `FOR UPDATE` or `LOCK IN SHARE MODE`.** Under READ COMMITTED a plain read holds nothing.
`FOR UPDATE`: rows the transaction writes, and parent rows that serialise writers (the round).
`LOCK IN SHARE MODE`: rows read to decide that must not change until commit, while other
readers proceed (`round_criterion` and `round_user` in a vote; the files' monument ids before
building vote rows, h6; the deleted files after the round, h5). A share lock later upgraded to
X deadlocks with a second upgrader: take `FOR UPDATE` at once if the row may be written.

**7.5 Retry 1213 only, idempotent bodies only.** `WriteTx` reruns a deadlock victim (1213) up
to 3 attempts: the whole transaction was rolled back. A lock wait timeout (1205) rolls back
only the statement, after 50 s: request paths don't retry it. Job steps retry both
(`JobScheduler.isTransient`): each attempt is a fresh transaction with a fresh after-commit
buffer. Never retry inside a caller's transaction, nor after an unknown outcome (a connection
lost at commit): `Round.create` without a submit token would create twice.

**7.6 Testing a race: lock specs.** Hold the lock in one transaction, start the other on a
thread, wait until it blocks (`information_schema.INNODB_TRX`, `trx_state = 'LOCK WAIT'`,
polled every 200 ms since the view refreshes only after 100 ms unread, 20 s at most, read as
root for PROCESS), assert nothing changed, release, assert the outcome. No sleeps. Check that
the spec fails without the lock (C1 M3). *Examples:* `CriteriaRateSpec`, `RoundImageLockSpec`,
`WritePathLockSpec`.

**7.7 The stress harness.** `test/stress/WritePathStress.scala` (`db.stress.WritePathStress`,
a `main`, opt-in, never in `sbt test`): 17 production write paths on threads (the counts'
recompute, `recount`, since P2a), each as its own MariaDB user so a printed deadlock names its
pair, invariants checked at the end (5, the counts, after a final recount). Run it for a
change to a write path's locks; compare variants in one JVM (write-path §3; §11: 0, 1, 0
deadlocks over three 90 s runs).

## 8. Migrations

**8.0 DDL commits implicitly.** MariaDB commits before and after each DDL statement: a
migration is not a transaction, can't be rolled back, and a failure leaves it part-applied
(Flyway marks it failed; `wlxjury-release restore` is the way back). Hence §8.2–§8.3: a change
that must be all-or-nothing is one `ALTER` (V87 drops and re-adds `account_token`'s check in
one statement), and every step is safe to run again.

**8.1 Names and order.** `V<n>__<description>.sql` only (Flyway silently skips what it can't
parse: V45a–V45g never ran). No `B` (baseline) files: from Flyway 9.21 the newest `B` builds
every empty database, and B44 didn't match V1–V44 (fresh databases failed at V51; flyway plan
§1). A baseline is cut deliberately with a schema diff against the replay, on 10.6 and 10.11.
Numbers are assigned at merge and renumbered on collision (roadmap, "Migration numbers").
Guards: `MigrationNamingSpec`, `MigrationsSettingsSpec`, `modules.MigrationOrderSpec`.

**8.2 Additive first, destructive last, each step rerunnable.** Add the new (columns, indexes,
tables) before old code stops using the old; rename or drop last (V82: index renames and the
FK swap on the old names, the atomic `RENAME TABLE` last). Each statement can be rerun after a
part-way failure: `IF NOT EXISTS`, `ON DUPLICATE KEY UPDATE id = id`, V85's reads of
`information_schema` that pick the statement for the state it finds.

**8.3 Step 0 refuses before changing anything.** Preconditions on production's data are
checked first and `SIGNAL SQLSTATE '45000'` stops the migration with nothing changed
(V83:51, "V83 stopped, nothing changed: …"); a failure leaves a failed Flyway row and the
deploy tool's restore path (V76's role query is run before the deploy for the same reason).

**8.4 Tested on production-like chains.** Production's history is not a fresh replay:
`ConvertToInnoDbMigrationSpec` runs the chain from production's V53 MyISAM schema
(`test/resources/db/prod-v53-myisam-schema.sql`); `FlywayUpgradeSpec` starts from 9.16.3's
V70 and fresh-V82 databases (schemas and histories). A migration that touches legacy tables
gets an example there.

**8.5 Pre-deploy queries and rollback SQL.** Each migration's plan section lists read-only
queries run on production before the deploy (version, last Flyway row, the indexes and keys it
expects, rows it would refuse, views/triggers/grants on renamed tables: P0 §12), and the
roadmap's "Before any production deploy" links them. Rollback after `reopen` is SQL tested
forward, back (identical `SHOW CREATE TABLE`) and forward again (V82, V84, V88). Until `reopen`,
`wlxjury-release restore` is the path.

**8.6 Online DDL, timed on a copy.** State `ALGORITHM=INSTANT`/`NOCOPY`/`INPLACE, LOCK=NONE`
so a statement fails rather than copies a large table (V56, V58, V82, V85); an FK on a large
table is added with `foreign_key_checks = 0` after a data check (V58, V65, V82). Time the DDL
and any backfill on production-sized data and record it (V82: ≤ 0.034 s on 1M rows; V85:
10.9 s on 3.07M rows). Backfills that don't fit a deploy window are jobs. A new, empty table
(V84 before its backfill, V88's `round_image_event`, V89's `round_image_dirty`) is a plain
`CREATE TABLE IF NOT EXISTS`: no copy, and its foreign keys only touch the parents' metadata.
Columns are added `ALGORITHM = INSTANT` with `ADD COLUMN IF NOT EXISTS` (V89's counts on
`round_image` and generations on `round`: metadata only, existing rows read the default). A
backfill a deploy needn't wait for is left to the app: V89's defaults mark every round dirty
and the background recompute fills the counts (round-images §16).

**8.7 Version-conditional DDL.** When a server version changes the meaning (10.6 parses
`DESC` in an index and ignores it), read `VERSION()` and `information_schema`, run the
statement by `EXECUTE IMMEDIATE`, and warn (`SIGNAL SQLSTATE '01000'`) instead of failing on
the old server (V85), with a startup check as backstop (`Migrations.galleryIndexLeftAscending`)
and specs on both servers (`VoteGalleryIndexSpec`, `-Dwlxjury.test.mariadb-image`).

## 9. Tests

**9.1 Isolation of specs.** Pure DAO specs with the session threaded: `AutoRollbackDb` /
`dbTest`. Anything through a service or `AutoSession`: `BeforeAll` +
`BeforeEach(SharedTestDb.truncateAll())` (CLAUDE.md's table). A spec using `running(app)`
calls `SharedTestDb.reregisterDefault()` after.

**9.2 One shared server.** `SharedTestDb` (tmpfs, 128M buffer pool, 1M `max_allowed_packet`,
READ COMMITTED URL) for every spec; databases of their own on it for migration specs
(`FlywayUpgradeSpec`, `ConvertToInnoDbMigrationSpec`), dropped after. 10.11 by
`-Dwlxjury.test.mariadb-image`.

**9.3 Parity specs for rewritten reads.** A rewritten query passes the same spec as the old
one, checked against the old code once: `GroupedListParitySpec` (an in-memory model of the
all-jurors lists, run on B12's base), `RoundImageReadParitySpec` (P1's reads from
`round_image` against `vote`). The spec covers the read's cases on the same data: juror and
organizer, binary and rated, with and without a region and a filter. *Source:* gallery-latency
plan (`98a8a79e:docs/plans/2026-10-28-gallery-latency.md`), "Rules".

**9.4 Invariants after writes.** Specs that write votes assert
`RoundImageInvariants.violations(roundId)` (or `all()`) empty after each scenario; the harness
checks them after stress.

**9.5 Anonymous Commons only.** Tests never use Commons credentials: the test config sets
`commons.user`/`commons.password` to `""` (no `${?COMMONS_USER}`), `CommonsLogin` refuses a
login in Mode.Test unless `commons.allowTestLogin`, and bots come from `AnonymousBots`
(`TestConfigAnonymousSpec`, `AnonymousBotsSpec`; details in CLAUDE.md "Testing"). Still run
sbt by hand with `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u
SCALAWIKI_PASSWORD`; live-Commons specs only with `-Dintegration=true`.

## 10. Guards

| Rule | Enforced by |
|---|---|
| 1.1, 1.2 no unbound SQL text outside listed files | `security.RawSqlSpec` |
| 1.6 no exception text shown (not log redaction) | `security.ErrorTextSpec`, `SqlLoggingSpec` (templates, `?` for values) |
| 2.1 index/constraint names: table prefix, kind suffix, no unnamed keys (not `<cols>` or singular tables); 2.7 InnoDB, utf8mb4 (from V83) | `db.MigrationSchemaSpec` (new; `(file, name)` allowlist) |
| 2.6 explicit TIMESTAMP (from V85); 8.1 names, no B files | `db.MigrationNamingSpec` |
| 2.7 InnoDB conversion, V82 names start with the table | `db.ConvertToInnoDbMigrationSpec` |
| 3.3 no filesort on the juror gallery (10.8+) | `db.VoteGalleryIndexSpec` |
| 3.5 batches fit 1M packets | `DistributeImagesBatchSpec` |
| 6.1 READ COMMITTED | `ReadinessControllerSpec`, `ReadinessDatabaseSpec`, `ProductionConfigSpec`, `CriteriaRateSpec` |
| 7.2 round first | `RoundWritesGuardSpec`, `RoundImageEventSpec` and `RoundCountsSpec` (run time), `security.StructuralWritesSpec` (raw writes, `round_image_event` too since V88, `round_image_dirty` since V89) |
| 7.3 vote rows locked by primary key | `db.VoteRowLockSpec` (new): a tripwire for literal SQL of the pre-h4 shape (locking, writing or upserting a vote by `page_id` and `jury_id` without the id), not full enforcement |
| 8.4 production-like chains | `FlywayUpgradeSpec`, `ConvertToInnoDbMigrationSpec` |
| 9.4 invariants | `RoundImageInvariants` in the write specs; invariant 5 (the counts) once a round's counts are clean (`RoundCounts.isClean`), so after `RoundCountsRecompute.untilClean` |
| 7.1 the counts' marks and recompute | `RoundCountsLockSpec` (INNODB_TRX waits both ways; 20 jurors on one file while the recompute runs: no lost vote, no failure) |
| 9.5 anonymous Commons in tests | `TestConfigAnonymousSpec`, `AnonymousBotsSpec` |

**Proposed, by value and cost** (not built):
1. **Form length vs column length** (2.5, R225-1): high value, medium cost. A source scan
   ratchet that every `text`/`nonEmptyText`/`email` mapping in `app/controllers` has
   `maxLength` or an allowlist entry; stronger: a DB spec mapping forms to columns.
2. **Log redaction** (1.6, R227-1): high value, medium cost: with the redacting converter, a
   spec logging a duplicate-key error and asserting no quoted value in the output.
3. **`LIKE` escaping** (1.3): low cost: `like $x` in SQL only through an escaping helper.
4. **Pre-deploy note per migration** (8.5): low cost: each new `V<n>` named in the roadmap's
   pre-deploy list or a plan section titled with it.
5. **Singular new tables** (2.1): low cost, noisy (`status`, `news`): allowlist-based.
6. **N+1 / missing `EXPLAIN`**: not mechanisable; review item.

## 11. Where the code doesn't follow these yet (for triage)

- **V1 (1.3), Low, correctness only:** `ImageDbNew.scala:466` binds `region + "%"` from the URL
  without escaping `%`/`_`. Reached only with `subRegions` on (Віа Регіа) and a region of at
  most 2 characters; it stays within the juror's own files (a wider match, a range scan).
- **V2 (1.6, R227-1):** whole exceptions logged, and a driver message quotes values:
  `ErrorHandler.scala:63`; `JobScheduler.scala:134,179,230,245,377,444,466,472`;
  `RoundController.scala:314`; `ImageProxyController.scala:108` (also the message and the
  filename). `JobScheduler.scala:159,440` log a message (a poll failure's may carry values;
  deadlock/timeout text has none).
- **V3 (2.5, R225-1):** no `maxLength` on `UserController`'s `email`/`fullname`,
  `LoginController`'s `login`, `ContestController`'s fields, `CriteriaController`'s `name`,
  `ImageDiscussionController`'s `text`, `EditRound`'s text fields (only `ApiTokensCard` has one).
- **V4 (9.5): fixed** (`9cc2fd85`, `0880122b`): the test config no longer reads
  `COMMONS_USER`/`COMMONS_PASSWORD`, and scalawiki's environment login is closed for its bots.
- **V5 (2.1):** pre-P0 names remain (`FK_round_user_round_id`, `fk_api_token_user`,
  `fk_account_token_*` from V75/V77, `fk_uc_*`): renamed only when a migration touches them
  (V87 renamed V77's `chk_account_token_purpose` to `account_token_purpose_chk`).
- **V6 (2.2):** no declared primary key: production's `vote`, `vote_aside`, `vote_orphan` and
  legacy `criteria` (only `UNIQUE KEY id`, which InnoDB clusters on; the V53 dump), and
  `monument` on fresh databases (flyway plan §1).
- **V7 (1.8, 1.5), Low:** `CategoryJdbc.scala:65-66` `INSERT IGNORE`s a Commons category title
  into `category.title` (`VARCHAR(190)`, `_ci`, unique): case variants collapse into one row,
  a longer title is truncated with a warning, and the following `SELECT … WHERE title = ?`
  then finds another row or none (`.get` throws).
- **V8 (5.1):** plain `DB.localTx` (no retry), outside the vote tables: `Round.scala:193`
  (`addUsers`, writes `round_user`), `CriterionJdbc.scala:40`, `User.scala:765,798,855`,
  `ApiTokenService.scala:160`, `AccountTokenService.scala:227,257`, `UserService.scala:213`, `ImageService.scala:68,87`.
  `RoundWrites.inRounds` (`RoundWrites.scala:106`) is unused.
- **V9 (5.6):** `ImageProxyController.scala:104` blocks Play's default pool on JDBC.
- **V10 (6.2), Low:** `CriteriaRate.scala:55-58` checks `round_user` in an unlocked
  `NOT EXISTS` inside the vote's locking read: under READ COMMITTED the subquery is a
  consistent read, so a stop committed meanwhile isn't waited for. `VoteJdbc.rateIn` reads it
  `LOCK IN SHARE MODE` as a separate statement.
- **V11 (3.8), index candidates.** The 2026-03 analysis (`docs/history/db-performance-analysis.md`,
  pre-V82 names) is applied: V45–V47 indexes, V46 schema fixes (except `monument`'s key, V6),
  V50 region stats, the ranking and `roundUserStat` rewrites. Left: single-column `vote`
  indexes that are prefixes of composite ones (`vote_round_idx`, `vote_jury_id_idx`) and
  `vote_rate_idx` (rate alone): drop candidates under §3.8, the same on `vote_aside`, after
  `EXPLAIN` of the queries that might use them.
