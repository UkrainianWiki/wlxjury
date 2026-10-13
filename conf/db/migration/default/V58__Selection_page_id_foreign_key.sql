-- Selection rows whose image is gone ("orphans", from manual deletes or old scripts:
-- the app itself never deletes images). Every gallery, rank and result query joins
-- images, so orphans only showed in counts, and the juror gallery's paging had to join
-- images to stay consistent with the image rank. Archive them, delete them, and add a
-- foreign key so there can be no new ones: deleting an image now deletes its
-- selection rows, as it already did its category_members rows
-- (FK_category_member_page_id, V24).
--
-- Check on production first (the rows this archives and deletes):
--   SELECT COUNT(*) FROM selection s LEFT JOIN images i ON i.page_id = s.page_id
--   WHERE s.page_id IS NOT NULL AND i.page_id IS NULL;

-- 1. Archive: the same columns (and indexes), all data kept. Idempotent.
CREATE TABLE IF NOT EXISTS selection_orphans LIKE selection;

INSERT IGNORE INTO selection_orphans
  SELECT s.* FROM selection s
  LEFT JOIN images i ON i.page_id = s.page_id
  WHERE s.page_id IS NOT NULL AND i.page_id IS NULL;

-- 2. Delete them.
DELETE s FROM selection s
  LEFT JOIN images i ON i.page_id = s.page_id
  WHERE s.page_id IS NOT NULL AND i.page_id IS NULL;

-- 3. The foreign key. With foreign_key_checks on, InnoDB can only add it by copying
-- the whole table (ALGORITHM=COPY, writes blocked for the duration); with them off it
-- is an in-place, metadata-only change that doesn't scan the rows. Not checking them is
-- safe here: step 2 just deleted every row the constraint would reject, and Flyway runs
-- this before the app serves requests. selection_page_id_index (page_id) serves as the
-- constraint's index.
SET foreign_key_checks = 0;

ALTER TABLE selection
  ADD CONSTRAINT FK_selection_page_id FOREIGN KEY IF NOT EXISTS (page_id)
    REFERENCES images (page_id) ON DELETE CASCADE,
  ALGORITHM = INPLACE, LOCK = NONE;

SET foreign_key_checks = 1;
