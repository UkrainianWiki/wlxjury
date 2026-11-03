-- Refresh optimizer statistics after the V45/V46 index additions.
-- Without up-to-date statistics MariaDB may choose images as the driving table
-- in selection-filtered queries, causing full scans of the 38k-row images table
-- instead of using the compact selection indexes.
ANALYZE TABLE images, selection, rounds, users, round_user, monument;
