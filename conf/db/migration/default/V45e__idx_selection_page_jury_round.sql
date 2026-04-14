-- Targets: VotingSimulation (per-image vote lookup)
CREATE INDEX idx_selection_page_jury_round ON selection(page_id, jury_id, round_id);
