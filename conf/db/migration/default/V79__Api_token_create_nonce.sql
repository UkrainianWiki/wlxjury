-- The form submit that created an API token (services.ApiTokenService.createOnce): the SHA-256
-- (hex) of the profile form's one-time nonce. A double click sends the same nonce twice; the
-- second create revokes the first's token if it was never used, so the token the browser shows
-- is the one usable token. A deliberate second create comes from a new page, with a new nonce,
-- and leaves the first alone. NULL for tokens created before this column, or without a form.
--
-- Undo: ALTER TABLE api_token DROP COLUMN create_nonce;
-- Numbered after S2's V77 and C1's V78 (merge those first, or renumber this one at the merge).
ALTER TABLE api_token ADD COLUMN create_nonce CHAR(64) NULL DEFAULT NULL AFTER prefix;
