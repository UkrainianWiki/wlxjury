# Database review checklist

For every change that touches SQL, schema, migrations, transactions or locks. The reviewer
quotes the lines they checked; each item points to its section of
[practices.md](practices.md). SQL-injection items are also in the
[security checklist](../security/review-checklist.md) ("SQL and queries").

## Queries

- [ ] Values bound; `SQLSyntax`/`createUnsafely` only for code-owned identifiers, in
  `RawSqlSpec.Allowed`'s files (§1.1, §1.2).
- [ ] No `INSERT IGNORE` where an error other than the duplicate is possible; row counts not
  read as "changed" (§1.8).
- [ ] A request value in `LIKE` has `\`, `%`, `_` escaped (§1.3).
- [ ] `IN` lists from data are distinct, chunked, never empty; sorted if they lock (§1.4).
- [ ] Case- or space-sensitive comparisons use `BINARY` beside the indexed one (§1.5).
- [ ] No `SQLException` message or whole exception with one in its chain in a log line or a
  stored error; `SqlLogging.describe` instead (§1.6).
- [ ] Text stored as entered; escaped at render (§1.7).

## Schema and migrations

- [ ] `V<n>__…sql`, no `B` file; the number checked against the roadmap and the other open
  branches (§8.1).
- [ ] New tables singular; names `<table>_<cols>_idx|_uidx|_fk|_chk`; `ENGINE = InnoDB`,
  `utf8mb4`; a primary key (§2.1, §2.2, §2.7).
- [ ] An invariant is a unique key, caught by its name (§2.3); a foreign key or the reason
  there is none (§2.4).
- [ ] Every new `VARCHAR(n)` has `maxLength(n)` on its form fields (§2.5).
- [ ] TIMESTAMP columns `NULL` or `NOT NULL DEFAULT …` (§2.6).
- [ ] DDL commits implicitly: what must be atomic is one `ALTER`; additive first, destructive
  last; each step rerunnable; preconditions `SIGNAL` before any change (§8.0–§8.3).
- [ ] DDL states `ALGORITHM`/`LOCK`; time on production-sized data recorded; a backfill too
  long for a deploy window is a job (§8.6).
- [ ] Version-dependent DDL is conditional and tested on 10.6 and 10.11 (§8.7).
- [ ] Pre-deploy read-only queries and rollback SQL (tested forward, back, forward) in the plan,
  and a line in the roadmap's "Before any production deploy" (§8.5).
- [ ] A migration touching legacy tables has an example in `ConvertToInnoDbMigrationSpec` or
  `FlywayUpgradeSpec` (§8.4).

## Performance

- [ ] `EXPLAIN` of each new query on `vote`, `vote_aside`, `round_image`, `images`,
  `criteria_rate` on the fixture, recorded: index, covering, no filesort on a paged list; pages
  by deferred join or keyset with a unique tie-breaker; no query per row (§3.1–3.3, §3.6).
- [ ] New counts cached with after-commit invalidation, or read from `round_image` (§3.4, §3.7).
- [ ] Batches ≤ 5,000 rows; a step ≤ 15,000 (§3.5, §5.5).
- [ ] A performance claim passes [the performance checklist](../performance/review-checklist.md)
  (paired run, SQL logging off, absolute deltas, commit ids, `FixtureVersion`, no production
  data).

## Transactions, isolation, locks

- [ ] One transaction owner (`WriteTx.write`, a job step, or the caller of a `…In` method); no
  `DB.localTx` or `AutoSession` call inside a transaction (§5.1, §5.2).
- [ ] No network, mail or file I/O inside; caches, logs and wake-ups after commit (§5.3).
- [ ] Work that can outlast a request is a job (`JobHandler`), one short transaction per step (§5.5a).
- [ ] Every decision re-read inside the transaction, after its locks (§5.4).
- [ ] Nothing relies on REPEATABLE READ: no gap lock, no subquery as a guard; upserts and FK
  checks still lock gaps (§6.2).
- [ ] Structural writes lock the round(s) first (`RoundWrites`), then files in page order;
  the lock order of §7.1 holds; a new raw writer is listed in `StructuralWritesSpec` (§7.2).
- [ ] Single rows locked by primary key, sets by one ordered locking read; `FOR UPDATE` for rows
  written, share mode for rows read to decide (§7.3, §7.4).
- [ ] Retry only 1213, only at the owner, only an idempotent body (§7.5).
- [ ] A new lock or a changed order has a lock spec (`INNODB_TRX` wait, no sleeps) that fails
  without it; the stress harness run for a changed write path (§7.6, §7.7).

## Tests

- [ ] AutoRollback for DAO specs with the session threaded, truncation for services (§9.1).
- [ ] A rewritten read has a parity spec over its cases (juror and organizer, binary and rated,
  with and without region and filters); write specs assert `RoundImageInvariants` (§9.3, §9.4).
- [ ] Run with the Commons variables unset; no credentials in tests (§9.5).
- [ ] Source-scan guards pass (no Docker): `testOnly db.MigrationNamingSpec
  db.MigrationSchemaSpec db.VoteRowLockSpec security.RawSqlSpec security.StructuralWritesSpec`;
  a hit is fixed or that one file allowlisted with a reason (§10).
