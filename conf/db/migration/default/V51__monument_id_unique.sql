ALTER TABLE monument
  DROP INDEX monument_id_index,
  ADD UNIQUE INDEX monument_id_unique (id);
