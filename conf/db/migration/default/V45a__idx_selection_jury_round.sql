-- Targets: JurorGallerySimulation, RegionFilterSimulation, AggregatedRatingsSimulation
CREATE INDEX idx_selection_jury_round ON selection(jury_id, round_id);
