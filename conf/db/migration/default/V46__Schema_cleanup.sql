-- Remove the redundant UNIQUE KEY on comment.id (it duplicates the PRIMARY KEY)
-- First add the PRIMARY KEY, then drop the redundant UNIQUE KEY
ALTER TABLE comment ADD PRIMARY KEY (id);
ALTER TABLE comment DROP INDEX id;

-- Covering index for byRating queries (round_id + rate together)
CREATE INDEX idx_selection_round_rate ON selection(round_id, rate);
