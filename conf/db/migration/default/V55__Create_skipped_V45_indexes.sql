-- The V45a-V45g index migrations never ran: a Flyway version must be numeric, so
-- "V45a__..." is an invalid migration name, which Flyway skips without an error.
-- Create their indexes here (IF NOT EXISTS: harmless where one was added by hand).
-- V45a's idx_selection_jury_round and V45e's idx_selection_page_jury_round are left
-- out: V48's idx_selection_jury_round_rate_mon_page and V53's unique
-- selection_page_jury_round_uidx replaced them.

-- RoundManagement (rounds of a contest), JurorGallery (active round lookup)
CREATE INDEX IF NOT EXISTS idx_rounds_contest_active ON rounds(contest_id, active);
-- JurorGallery (jurors of a round)
CREATE INDEX IF NOT EXISTS idx_round_user_round_active ON round_user(round_id, active);
-- RoundManagement, AggregatedRatings (roundUserStat)
CREATE INDEX IF NOT EXISTS idx_users_contest ON users(contest_id);
-- AggregatedRatings (roundRateStat), RoundManagement (roundsStat): distinct images per round
CREATE INDEX IF NOT EXISTS idx_selection_round_page ON selection(round_id, page_id);
-- login lookup by wiki account
CREATE INDEX IF NOT EXISTS idx_users_wiki_account ON users(wiki_account);

ANALYZE TABLE rounds, round_user, users, selection;
