-- selection.monument_id is V48's copy of images.monument_id. Until the commit "Keep
-- selection.monument_id in step with the image's monument id", ImageJdbc.updateMonumentId
-- (monument ids read from the file pages) and ImageJdbc.update (images changed on
-- re-import) changed only images, so rows distributed before such a change kept the old
-- id. The galleries' single-region filter now reads selection.monument_id, so those
-- images would drop out of their region's gallery: copy the image's id again, as V48
-- did, rewriting only the rows that differ.
--
-- One statement, like V48: it reads every selection row once (an images scan with
-- selection looked up by page_id) and writes only the drifted ones. On the Gatling
-- fixture (767k selection rows) it takes 1.4 s with no drift, 2.2 s with 39k drifted
-- rows; expect roughly 2-3 s per million selection rows on a warm buffer pool. It locks
-- the selection rows it reads while it runs, so run it as Flyway does on deploy, before
-- the app serves requests, not under live voting.
UPDATE selection s
  JOIN images i ON i.page_id = s.page_id
  SET s.monument_id = i.monument_id
  WHERE NOT (s.monument_id <=> i.monument_id);
