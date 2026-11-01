-- The user's colour mode (controllers.Theme): 'light', 'dark' or 'auto' (the browser's
-- prefers-color-scheme). NULL: never chosen, which is auto; signing in then keeps the
-- browser's theme cookie as it is.
ALTER TABLE users ADD COLUMN IF NOT EXISTS theme VARCHAR(8) NULL;
