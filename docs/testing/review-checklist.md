# Testing review checklist

For every change with tests, and every change that should have them. Each line points to
[practices.md](practices.md). DB-specific items are in [the DB checklist](../db/review-checklist.md)
"Tests"; authorization cases in [the security checklist](../security/review-checklist.md).

## Layer and isolation

- [ ] Each test sits in the lowest layer that shows the behaviour; CSRF behaviour through
  `CsrfTestApp.withCsrfApp`, headers and CSP through `ProductionFilters` (§1.1).
- [ ] No spec stops the shared app; an own app is stopped in `afterAll` (§1.2).
- [ ] AutoRollback only with the session threaded; anything through a service, `autoSession` or an
  app truncates (§2.1).
- [ ] `running(app)` is followed by `SharedTestDb.reregisterDefault()` in a `finally` (§2.4).
- [ ] The spec seeds what it reads; a raw `INSERT INTO vote` keeps `round_image` (§2.5).

## Concurrency and guards

- [ ] A race is tested with a lock spec that waits on `INNODB_TRX`, no sleeps, and fails without
  the lock (§3.1).
- [ ] Vote-writing specs assert `RoundImageInvariants` empty (§3.2).
- [ ] A new guard has a self-test that it finds a real match and flags a bad example; an allowlist
  entry names one file and a reason (§4.1, §4.2).
- [ ] A rewritten read has a parity spec over the listed cases (§4.3).
- [ ] A migration that transforms data is tested on a production-like chain (§4.4).

## Wikimedia and external services

- [ ] No credentials in test config, specs or workflows; bots from `AnonymousBots` (§5.1).
- [ ] A spec that reads live Wikimedia is behind `-Dintegration` or `-Ddocker.tests` and skips
  otherwise; a new flag is forwarded in `build.sbt` (§5.3).
- [ ] OAuth providers and mail are faked (§5.4, §5.5).

## Assertions

- [ ] Elements found by id, data attribute, role or text; no CSS class unless it is the behaviour
  (§6.1).
- [ ] Every assertion fails when its element is missing: no conditional checks, no assertion over
  a possibly empty selection (§6.2).
- [ ] Permission changes have the refused roles and other contests' resources as cases (§6.3).
- [ ] New message keys are in all four files with the same placeholders;
  `MessagesCompletenessSpec` knows any new helper or built key (§6.4).

## Runs and coverage

- [ ] No sleep-based waits or wall-clock timing; clocks injected (§7.2).
- [ ] The affected specs ran (and their results are in the PR); a local full suite only at a release
  boundary, CI's on every PR (§8.1).
- [ ] The coverage report was read for the touched files; uncovered branches there are either
  tested or explained (§9.3).
- [ ] A new user-visible flow has a story ID in [stories.md](stories.md) and a tagged test, or is
  listed there as uncovered; a new tag cites a story whose sentence the test shows (§9.4).
- [ ] A change that adds a test layer, a flag or a guard updates this practices doc in the same PR
  ([workflow](../workflow/practices.md) §8).
