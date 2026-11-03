# Performance review checklist

For a change made for speed or memory, a change to a hot path (galleries, the large view, votes,
round creation, imports, the image proxy), a new cache, chunk, pool or dispatcher, and every
performance claim in a plan or PR. Each line points to [practices.md](practices.md); query-level
items are in [the DB checklist](../db/review-checklist.md) "Performance".

## Measuring

- [ ] A measurement existed or was made before the change; the result is a paired run on the app
  path, not only the SQL (§2.1, §2.2, §3.1).
- [ ] Both variants with the same settings: SQL logging off, the same fixture, buffer pool and JVM
  flags, nothing else running; production-like runs not throttled (§2.3).
- [ ] Absolute deltas with run-to-run variation, outliers kept, limitations stated; no
  percentage of one query presented as user impact (§2.4).
- [ ] Commit ids of both variants, the command and settings, and the log/results paths recorded
  in the plan (§2.5, §1.6).
- [ ] The simulation fits the path; organizer think time or the stress flag named (§1.1, §1.3).
- [ ] `FixtureVersion` bumped if `GatlingDbSetup.load` changed (§1.2).
- [ ] No production data in the diff or under `data/`; scripts only (§2.7).
- [ ] Stress-harness latencies not quoted as production's (§2.8).

## Changing

- [ ] A new hot-page count is cached with after-commit invalidation and a TTL, or read from
  `round_image` (§3.3, §3.4).
- [ ] Work that can outlast a request is a job with bounded steps (§3.5, §4.3).
- [ ] New proxy or thumbnail sizes stay within `servedWidths` and the caps (§3.6).

## Sizing

- [ ] Memory judged by heap after GC from a GC log at `-Xmx512m` and `-Xmx1g`: no full GC, under
  about half of `-Xmx` (§4.1).
- [ ] A new chunk or batch size fits the heap and `max_allowed_packet` (§4.3, §4.4).
- [ ] A new pool, dispatcher or worker count fits the connection budget (§4.5, db §5.6).
- [ ] A MariaDB setting changed only with a bench-server measurement; durable flushing kept in
  production (§4.6).
