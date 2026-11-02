-- Security batch S6 (P8): legacy SHA-1 password hashes (40 lowercase hex characters, unsalted,
-- stored before bcrypt) still work for a grace period of 3 months, then they are replaced by an
-- unusable value and the account's owner sets a new password through a password link (root's
-- "reset password" button sends one; an organizer re-adding them sends an invitation).
--
-- legacy_password_retirement: when the grace period ends. Anchored at this migration, i.e. the
-- deploy, so a later restart or deploy never moves it. One row (id 1), read as the time left
-- (TIMESTAMPDIFF against the database clock: DATETIME, no time zone or auto-update). After
-- retire_at a legacy hash no longer signs in (db.scalikejdbc.User.login), and the app's periodic
-- task (modules.LegacyPasswordRetirementTask) replaces every one left
-- (db.scalikejdbc.LegacyPasswordRetirementJdbc.retire).
--
-- legacy_password_retired: the accounts whose legacy hash was replaced, and when (no hash): root
-- sends them password links. To move the end: UPDATE legacy_password_retirement SET retire_at = ...
-- WHERE id = 1.
--
-- Undo: DROP TABLE legacy_password_retirement; (the app then never retires a legacy hash: they
-- keep working until used, as before). A replaced hash is not restored (by design: it is the
-- unsalted SHA-1 the item retires); its account gets a password link (legacy_password_retired).
CREATE TABLE IF NOT EXISTS legacy_password_retirement (
  id        TINYINT UNSIGNED NOT NULL,
  retire_at DATETIME(3)      NOT NULL,
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT IGNORE INTO legacy_password_retirement (id, retire_at) VALUES (1, NOW(3) + INTERVAL 3 MONTH);

CREATE TABLE IF NOT EXISTS legacy_password_retired (
  user_id    BIGINT UNSIGNED NOT NULL,
  retired_at DATETIME(3)     NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
