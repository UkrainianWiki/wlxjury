-- Targets: AggregatedRatingsSimulation (roundRateStat batch query)
CREATE INDEX idx_selection_round_page ON selection(round_id, page_id);
