-- Re-open a closed real contest in the benchmark copy, for voting load: Wiki Loves
-- Monuments 2025 in Ukraine (contest 603), the largest contest of the dump (146k selection
-- rows in 32 rounds, 38,392 images in its category, 103 jurors, 4 organizers).
--
-- The rounds the jurors vote and browse in:
--   1721 "Qt-00": the largest binary round, 26,382 images, 17 jurors, one juror per image
--                 (~1,550 images per juror);
--   1775 "Обласні": the largest rated round (1-10), 872 images, 13 jurors, every juror
--                 rates every image.
-- Re-opening = the rounds and their jurors active again; nothing else changes.
UPDATE rounds SET active = 1 WHERE id IN (1721, 1775);
UPDATE round_user SET active = 1 WHERE round_id IN (1721, 1775);
SELECT id, name, active FROM rounds WHERE id IN (1721, 1775);
