-- Series admins (docs/plans/2026-11-18-contest-series.md §3.3, §3.4; PR CY-2; CA3, CA17, CA19, CA20,
-- CA22 decided by the maintainer 2026-11-18): root grants an account "Series admin" of a series,
-- and a series admin may add further series admins of their own series at any depth; only root
-- revokes (db.scalikejdbc.SeriesAdminJdbc, services.series.SeriesAdmins).
--
-- Planned as V111; #124 (CY-4) holds V111 on its branch, so CY-2 took V112. Whichever of the two
-- merges second renumbers to above master's top first: Flyway never takes a lower version after a
-- higher one has run (outOfOrder off).
--
--  * series_admin: one row per grant. admin_from_year (CA20): derived Admin in the series' live
--    contests with year >= it, derived Organizer (read-only) in older ones, and account reach (CA17)
--    from it on; computed at grant time (the grantee's earliest real Admin year in the series, capped
--    at last year, else last year), editable by the granter within their bounds, later by root only.
--    granted_by: who added it (root or a series admin of the series); the chained revoke follows
--    it. revoked_by_root: a grant root revoked may be granted again by root only. A live grant:
--    revoked_at NULL, its series live and its account not deleted. The generated
--    live_grant_series_id is series_id while the grant is not revoked, and UNIQUE (user_id,
--    live_grant_series_id) keeps one live grant per account and series (as contest_jury's
--    live_series_id, V109). The grant goes with its series only if the series goes (RESTRICT: a
--    series with grants can't be deleted) and with its account (CASCADE; accounts are retired,
--    never deleted, today). granted_by and revoked_by are account ids without foreign keys: the row
--    outlives a retired actor, as account_change.actor_id.
--  * account_change's field CHECK (V96, widened by V106 and V107, whose values it keeps) gains
--    series_admin_granted (old_value: the computed default start year, new_value: the chosen one),
--    series_admin_revoked (old_value: the start year) and series_admin_from_year (old and new
--    values), each with the comment "series #<id>".
--
-- Rerunnable: CREATE TABLE IF NOT EXISTS; the CHECK dropped if there and added again. The new table
-- is empty; the CHECK copies account_change (production: a few hundred rows, milliseconds; writes
-- wait for it). The foreign keys take metadata locks on series and users for a moment. A 60 s
-- lock_wait_timeout for each statement (a long transaction or backup: it fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS series_admin (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  series_id            BIGINT UNSIGNED NOT NULL,
  user_id              BIGINT UNSIGNED NOT NULL,
  admin_from_year      INT             NOT NULL,
  granted_by           BIGINT UNSIGNED NOT NULL,
  granted_at           TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  revoked_by           BIGINT UNSIGNED NULL DEFAULT NULL,
  revoked_at           TIMESTAMP(3)    NULL DEFAULT NULL,
  revoked_by_root      BOOLEAN         NOT NULL DEFAULT FALSE,
  comment              VARCHAR(500)    NULL DEFAULT NULL,
  live_grant_series_id BIGINT UNSIGNED AS (IF(revoked_at IS NULL, series_id, NULL)) PERSISTENT,
  PRIMARY KEY (id),
  UNIQUE KEY series_admin_live_grant_uidx (user_id, live_grant_series_id),
  KEY series_admin_series_idx (series_id, granted_at),
  KEY series_admin_granted_by_idx (series_id, granted_by),
  CONSTRAINT series_admin_series_fk FOREIGN KEY (series_id) REFERENCES series (id) ON DELETE RESTRICT,
  CONSTRAINT series_admin_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk,
  ADD CONSTRAINT account_change_field_chk
    CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created',
                            'invitation', 'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended',
                            'account_joined', 'account_retired', 'series_admin_granted', 'series_admin_revoked',
                            'series_admin_from_year'));

-- A pre-V112 binary runs on this schema: it never names series_admin (no series admin has any
-- access there) and writes none of the new fields; it shows their log rows by message keys it lacks.
-- Undo, after reopen (every grant and its log rows are lost; revoke the grants first if the
-- accounts' sessions should end their series access before the older binary starts, which happens
-- anyway: the older binary reads no grant):
--   DELETE FROM account_change WHERE field IN ('series_admin_granted', 'series_admin_revoked', 'series_admin_from_year');
--   ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk,
--     ADD CONSTRAINT account_change_field_chk CHECK (BINARY field IN ('email', 'password', 'role',
--     'name', 'wiki_account', 'root', 'account_created', 'invitation', 'invitation_cancelled',
--     'password_link', 'share_link', 'sessions_ended', 'account_joined', 'account_retired'));
--   DROP TABLE IF EXISTS series_admin;
--   DELETE FROM flyway_schema_history WHERE version = '112';
