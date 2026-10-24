-- Production's category_members has no foreign keys: V24 declared FK_category_member_page_id
-- (to images) and FK_category_member_category (to category), both ON DELETE CASCADE, but
-- the table was MyISAM, which ignores them (it keeps only their indexes), and V55.1
-- changed the engine without adding them. The test schema (B44 baseline, dumped from an
-- InnoDB database) has them. ImageJdbc.categoryFiles pages and counts a contest's files
-- over category_members alone and joins images after the LIMIT, so it needs every member
-- to have its image, as FK_category_member_page_id guarantees. Archive the members whose
-- image or category is gone ("orphans", from manual deletes or old scripts: the app
-- deletes neither), delete them, and add both keys. A no-op where the keys exist.
--
-- Check on production first (the rows this archives and deletes, and the keys it adds):
--   SELECT COUNT(*) FROM category_members cm
--   LEFT JOIN images i ON i.page_id = cm.page_id LEFT JOIN category c ON c.id = cm.category_id
--   WHERE i.page_id IS NULL OR c.id IS NULL;
--   SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS
--   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'category_members';

-- 1. Archive: the same columns (and indexes), all data kept. Idempotent.
CREATE TABLE IF NOT EXISTS category_members_orphans LIKE category_members;

INSERT IGNORE INTO category_members_orphans
  SELECT cm.* FROM category_members cm
  LEFT JOIN images i ON i.page_id = cm.page_id
  LEFT JOIN category c ON c.id = cm.category_id
  WHERE i.page_id IS NULL OR c.id IS NULL;

-- 2. Delete them.
DELETE cm FROM category_members cm
  LEFT JOIN images i ON i.page_id = cm.page_id
  LEFT JOIN category c ON c.id = cm.category_id
  WHERE i.page_id IS NULL OR c.id IS NULL;

-- 3. The page_id index the key uses. Production keeps it from V24 (MyISAM created the
-- index of the key it ignored); a no-op there and on the test schema.
CREATE INDEX IF NOT EXISTS FK_category_member_page_id ON category_members (page_id)
  ALGORITHM = INPLACE LOCK = NONE;

-- 4. The foreign keys, metadata-only with foreign_key_checks off (as in V58): step 2 just
-- deleted every row they would reject, and Flyway runs this before the app serves
-- requests. cm_category_page (category_id, page_id) serves as FK_category_member_category's
-- index.
SET foreign_key_checks = 0;

ALTER TABLE category_members
  ADD CONSTRAINT FK_category_member_page_id FOREIGN KEY IF NOT EXISTS (page_id)
    REFERENCES images (page_id) ON DELETE CASCADE,
  ADD CONSTRAINT FK_category_member_category FOREIGN KEY IF NOT EXISTS (category_id)
    REFERENCES category (id) ON DELETE CASCADE,
  ALGORITHM = INPLACE, LOCK = NONE;

SET foreign_key_checks = 1;
