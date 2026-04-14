-- Add monument_id column (nullable; backfilled in step 2)
ALTER TABLE selection ADD COLUMN monument_id VARCHAR(190) DEFAULT NULL;

-- Backfill all existing selection rows from their images
UPDATE selection s
  JOIN images i ON i.page_id = s.page_id
  SET s.monument_id = i.monument_id;

-- Drop the now-superseded two-column index (V45a); the covering index below replaces it
-- Use IF EXISTS so this is idempotent if the index was already dropped or never created
DROP INDEX IF EXISTS idx_selection_jury_round ON selection;

-- Covering index: enables STRAIGHT_JOIN selection→images with index-only sort
-- on (jury_id, round_id) predicate + (rate DESC, monument_id ASC, page_id ASC) order
CREATE INDEX idx_selection_jury_round_rate_mon_page
  ON selection(jury_id, round_id, rate, monument_id, page_id);

ANALYZE TABLE selection;
