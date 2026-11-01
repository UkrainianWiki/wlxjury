-- "Selected by at least 0": the old round form had no empty choice, so 0 was its way of
-- saying no filter. Since 1cc47ee8 (2018) a bug made it read only the unrated votes of the
-- previous rounds. 0 and NULL now mean the same, no filter (Round.selectedByFilter), and
-- the stored 0 become NULL.
UPDATE rounds SET prev_selected_by = NULL WHERE prev_selected_by = 0;
