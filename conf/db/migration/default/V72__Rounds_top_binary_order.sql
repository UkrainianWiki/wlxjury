-- The order that picks a round's top-N files from a yes/no previous round
-- (org.intracer.wmua.BinaryOrder): 'approvals' (approvals, then fewest rejections),
-- 'difference' (approvals minus rejections) or 'approvals-only'; ties by page id. NULL: the
-- default, approvals then fewest rejections.
ALTER TABLE rounds ADD COLUMN IF NOT EXISTS top_binary_order VARCHAR(16) NULL;
