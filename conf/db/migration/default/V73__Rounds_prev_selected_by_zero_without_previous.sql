-- "Selected by at least 0" (files with an unrated vote in the previous rounds) on a round
-- with no previous round: the old round form's default, never read there (every
-- distribution path applies prev_selected_by only with a previous round). NULL, no filter,
-- says so and changes nothing. A 0 with a previous round keeps its meaning and stays.
UPDATE rounds SET prev_selected_by = NULL
WHERE prev_selected_by = 0 AND (previous IS NULL OR TRIM(previous) = '');
