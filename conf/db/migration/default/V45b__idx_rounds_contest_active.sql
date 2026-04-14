-- Targets: RoundManagementSimulation, JurorGallerySimulation (active round lookup)
CREATE INDEX idx_rounds_contest_active ON rounds(contest_id, active);
