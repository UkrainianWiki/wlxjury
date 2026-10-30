-- "Leave out conflicts of interest" (the round form): the round's distribution leaves out
-- the files whose author is the wiki account of a contest member in a conflict-of-interest
-- role, or of a root user (db.scalikejdbc.CoiMembers). Existing rounds keep their files:
-- off, until "Leave out conflicts of interest now" sets it.
ALTER TABLE rounds ADD COLUMN IF NOT EXISTS exclude_coi BOOLEAN NOT NULL DEFAULT FALSE;
