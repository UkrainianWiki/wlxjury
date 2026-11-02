-- Security batch S3 (review fix): how long a session cookie from before S3 (without issued_at and
-- last_seen) is still upgraded in place (modules.SessionLifetimeFilter). Nobody is signed out at the
-- S3 deploy, but a cookie that never takes the upgraded value (a replayed copy of the old one)
-- must not live forever: after until_at such a cookie is signed out (old_cookie_expired). Anchored
-- at this migration, i.e. the deploy, so a later restart or deploy never moves it; 30 days is the
-- default idle limit (wlxjury.session.idleDays). One row (id 1), read once by the app as the time
-- left (TIMESTAMPDIFF against the database clock: DATETIME, no time zone or auto-update).
--
-- Undo: DROP TABLE session_upgrade_window; (the app then upgrades no pre-S3 cookie: all are signed out)
CREATE TABLE IF NOT EXISTS session_upgrade_window (
  id       TINYINT UNSIGNED NOT NULL,
  until_at DATETIME(3)      NOT NULL,
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT INTO session_upgrade_window (id, until_at) VALUES (1, NOW(3) + INTERVAL 30 DAY);
