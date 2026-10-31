-- Named operations replace numeric clauses; preserve the old include/exclude meanings.
ALTER TABLE rounds MODIFY COLUMN category_clause VARCHAR(16) NULL DEFAULT NULL;

UPDATE rounds
SET category_clause = CASE category_clause
    WHEN '1' THEN 'include'
    WHEN '-1' THEN 'exclude'
    WHEN '0' THEN 'exclude'
    ELSE category_clause
  END,
  created_at = created_at
WHERE category_clause IN ('1', '-1', '0');
