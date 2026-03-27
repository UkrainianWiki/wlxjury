-- Remove the redundant UNIQUE KEY on comment.id (it duplicates the PRIMARY KEY)
-- First add the PRIMARY KEY, then drop the redundant UNIQUE KEY
ALTER TABLE comment ADD PRIMARY KEY (id);
ALTER TABLE comment DROP INDEX id;

-- Promote monument.id to a real PRIMARY KEY (was just a regular index)
ALTER TABLE monument MODIFY id varchar(190) NOT NULL;
ALTER TABLE monument ADD PRIMARY KEY (id);
ALTER TABLE monument DROP INDEX monument_id_index;

-- Covering index for byRating queries (round_id + rate together)
CREATE INDEX idx_selection_round_rate ON selection(round_id, rate);
