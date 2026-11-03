-- Criteria rounds' criteria (docs/plans/2026-11-02-criteria-rounds.md, C2): a global catalogue
-- (criterion) that admins add to and choose from, and each round's chosen criteria in their
-- order (round_criterion). Until now a criteria round's criteria were hard-coded: every
-- distribution pre-created rates of criteria 6-9, named by a fixed id -> name map (ids 1-5 Wiki
-- Science Competition, 6-9 WLM UA 2023) in LargeViewController. criteria_rate rows are now
-- written on a vote (an upsert per vote and criterion), so each needs its own key.
--
--  * criterion: a name is a message key (criterion.*); fallback_name is shown when the key has
--    no message in the viewer's language (a criterion an admin adds has a key no messages file
--    has until it is translated). Ids 1-9 keep today's ids and names, so the existing rates'
--    criterion ids stay as they are. Any other id found in criteria_rate (none expected) gets
--    an entry of its own under its id, "Criterion <id>".
--  * round_criterion: per round with has_criteria = 1, the criteria its votes' rates have
--    (vote and vote_aside), in id order (the large view's order until now); such a round
--    with no rates at all gets 6-9, the criteria a distribution gave it. A round whose votes
--    have rates but not has_criteria = 1 gets none: it is no criteria round and its rates are
--    read by nothing, as before. has_criteria stays, equal to "has round_criterion rows".
--  * criteria_rate: id becomes an AUTO_INCREMENT primary key (it was a unique key, written by
--    MAX(id) + n, AUTO_INCREMENT on production's MyISAM-era schema only); criteria is renamed
--    criterion_id with a foreign key to criterion; vote_id the type of vote.id; one rate per
--    vote and criterion (UNIQUE). vote_id has no foreign key: a vote moved aside leaves vote
--    (V63) and keeps its rates under the same id in vote_aside.
--  * Duplicate (vote_id, criteria) rows (none expected; production unchecked: C0 not run) are
--    archived in criteria_rate_orphan (reason 'duplicate'), the row with the highest rate kept
--    (then the lowest id); those votes' rate is recomputed as the sum of the positive rates
--    left, the rule they were written by. Nothing else of the votes' rates changes.
--  * criteria or vote ids <= 0 (impossible for the app; would need a decision), or keys of
--    criteria_rate other than every known schema's, stop the migration before it changes
--    anything (step 0).
--
-- Left as they are (U11, after the C0 queries): the unused legacy table criteria and the
-- unused vote.criteria_id.
--
-- Each step but the last is safe to run again after a failure part-way (the last is one
-- ALTER). Check on production first: the C0 queries (after V82) in the plan.

-- 0. Refuse, before any change, what this can't migrate: criterion ids that can't be keys of
--    the catalogue, vote ids that are no vote's, and criteria_rate keys other than those of
--    every known schema (UNIQUE `id` and criteria_rate_vote_id_idx, no PRIMARY KEY), which
--    the final ALTER would stop at, after the other steps (see C2 pre-deploy in the plan).
SET @bad_criteria = (SELECT COUNT(*) FROM criteria_rate WHERE criteria <= 0);
SET @bad_votes = (SELECT COUNT(*) FROM criteria_rate WHERE vote_id <= 0);
SET @primary_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'criteria_rate' AND INDEX_NAME = 'PRIMARY');
SET @id_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'criteria_rate' AND INDEX_NAME = 'id'
    AND NON_UNIQUE = 0 AND COLUMN_NAME = 'id');
SET @vote_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'criteria_rate' AND INDEX_NAME = 'criteria_rate_vote_id_idx');
EXECUTE IMMEDIATE IF(@bad_criteria > 0 OR @bad_votes > 0 OR @primary_key > 0 OR @id_key <> 1 OR @vote_key <> 1,
  CONCAT('SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''V83 stopped, nothing changed: criteria_rate has ',
         @bad_criteria, ' criteria<=0, ', @bad_votes, ' vote_id<=0, PRIMARY ', @primary_key,
         ', id key ', @id_key, ', vote_id key ', @vote_key, ''''),
  'DO 0');

-- 1. The catalogue, with today's criteria under their ids.
CREATE TABLE IF NOT EXISTS criterion (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_key   VARCHAR(100)    NOT NULL,
  fallback_name VARCHAR(255)    NOT NULL,
  created_by    BIGINT UNSIGNED NULL DEFAULT NULL,
  created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY criterion_message_key_uidx (message_key),
  UNIQUE KEY criterion_fallback_name_uidx (fallback_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT INTO criterion (id, message_key, fallback_name) VALUES
  (1, 'criterion.science', 'Науковість'),
  (2, 'criterion.caption', 'Підпис'),
  (3, 'criterion.usefulness', 'Корисність'),
  (4, 'criterion.aesthetics', 'Естетика'),
  (5, 'criterion.uniqueness', 'Унікальність'),
  (6, 'criterion.technical.quality', 'Технічна якість'),
  (7, 'criterion.wikipedia.usefulness', 'Корисність для Вікіпедії'),
  (8, 'criterion.photo.originality', 'Оригінальність фото'),
  (9, 'criterion.extra.points', 'Додаткові бали')
ON DUPLICATE KEY UPDATE id = id;

-- any other criterion id the rates have, under its own id
INSERT INTO criterion (id, message_key, fallback_name)
  SELECT DISTINCT cr.criteria, CONCAT('criterion.legacy.', cr.criteria), CONCAT('Criterion ', cr.criteria)
  FROM criteria_rate cr
  WHERE NOT EXISTS (SELECT 1 FROM criterion c WHERE c.id = cr.criteria);

-- 2. Each round's criteria.
CREATE TABLE IF NOT EXISTS round_criterion (
  round_id     BIGINT UNSIGNED NOT NULL,
  position     INT             NOT NULL,
  criterion_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (round_id, position),
  UNIQUE KEY round_criterion_round_criterion_uidx (round_id, criterion_id),
  KEY round_criterion_criterion_id_idx (criterion_id),
  CONSTRAINT round_criterion_round_id_fk FOREIGN KEY (round_id) REFERENCES round (id) ON DELETE CASCADE,
  CONSTRAINT round_criterion_criterion_id_fk FOREIGN KEY (criterion_id) REFERENCES criterion (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT INTO round_criterion (round_id, position, criterion_id)
  SELECT x.round_id, ROW_NUMBER() OVER (PARTITION BY x.round_id ORDER BY x.criteria), x.criteria
  FROM (SELECT v.round_id, cr.criteria FROM criteria_rate cr JOIN vote v ON v.id = cr.vote_id
        UNION
        SELECT d.round_id, cr.criteria FROM criteria_rate cr JOIN vote_aside d ON d.id = cr.vote_id) x
  JOIN round r ON r.id = x.round_id AND r.has_criteria = 1
  WHERE NOT EXISTS (SELECT 1 FROM round_criterion rc WHERE rc.round_id = x.round_id);

-- a criteria round without rates: the criteria a distribution gave it
INSERT INTO round_criterion (round_id, position, criterion_id)
  SELECT r.id, d.position, d.criterion_id
  FROM round r
  JOIN (SELECT 1 AS position, 6 AS criterion_id UNION ALL SELECT 2, 7 UNION ALL SELECT 3, 8 UNION ALL SELECT 4, 9) d
  WHERE r.has_criteria = 1
    AND NOT EXISTS (SELECT 1 FROM round_criterion rc WHERE rc.round_id = r.id);

-- 3. Duplicate rates of a vote and criterion: archived, then deleted, the best one kept.
ALTER TABLE criteria_rate_orphan ADD COLUMN IF NOT EXISTS reason VARCHAR(32) NULL DEFAULT NULL;

INSERT INTO criteria_rate_orphan (id, vote_id, criteria, rate, reason)
  SELECT d.id, d.vote_id, d.criteria, d.rate, 'duplicate'
  FROM (SELECT cr.id, cr.vote_id, cr.criteria, cr.rate,
               ROW_NUMBER() OVER (PARTITION BY cr.vote_id, cr.criteria ORDER BY cr.rate DESC, cr.id) AS n
        FROM criteria_rate cr) d
  WHERE d.n > 1
    AND NOT EXISTS (SELECT 1 FROM criteria_rate_orphan o
                    WHERE o.reason = 'duplicate' AND o.id = d.id AND o.vote_id = d.vote_id AND o.criteria = d.criteria);

DELETE cr FROM criteria_rate cr
  JOIN (SELECT r.id FROM (SELECT id,
                                 ROW_NUMBER() OVER (PARTITION BY vote_id, criteria ORDER BY rate DESC, id) AS n
                          FROM criteria_rate) r
        WHERE r.n > 1) d ON d.id = cr.id;

UPDATE vote v
  JOIN (SELECT DISTINCT vote_id FROM criteria_rate_orphan WHERE reason = 'duplicate') o ON o.vote_id = v.id
  SET v.rate = (SELECT COALESCE(SUM(IF(cr.rate > 0, cr.rate, 0)), 0) FROM criteria_rate cr WHERE cr.vote_id = v.id);

UPDATE vote_aside v
  JOIN (SELECT DISTINCT vote_id FROM criteria_rate_orphan WHERE reason = 'duplicate') o ON o.vote_id = v.id
  SET v.rate = (SELECT COALESCE(SUM(IF(cr.rate > 0, cr.rate, 0)), 0) FROM criteria_rate cr WHERE cr.vote_id = v.id);

-- 4. criteria_rate's keys (one copy of the table: ~166k rows on production in January 2026).
ALTER TABLE criteria_rate
  DROP INDEX id,
  MODIFY id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ADD PRIMARY KEY (id),
  MODIFY vote_id BIGINT UNSIGNED NOT NULL,
  CHANGE COLUMN criteria criterion_id BIGINT UNSIGNED NOT NULL,
  DROP INDEX criteria_rate_vote_id_idx,
  ADD UNIQUE KEY criteria_rate_vote_criterion_uidx (vote_id, criterion_id),
  ADD KEY criteria_rate_criterion_id_idx (criterion_id),
  ADD CONSTRAINT criteria_rate_criterion_id_fk FOREIGN KEY (criterion_id) REFERENCES criterion (id);
