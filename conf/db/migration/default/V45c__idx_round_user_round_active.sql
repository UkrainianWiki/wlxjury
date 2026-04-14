-- Targets: JurorGallerySimulation (juror listing per round)
CREATE INDEX idx_round_user_round_active ON round_user(round_id, active);
