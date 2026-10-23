-- The files marked deleted on Commons (V63), few, found without reading all images: a
-- round that opens, ends a distribution or gets votes merged in moves their votes aside
-- (ImageJdbc.archiveDeletedVotesInRound), starting from them, not from its own votes.
CREATE INDEX IF NOT EXISTS images_deleted_at_idx ON images (deleted_at);
