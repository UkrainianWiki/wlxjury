ALTER TABLE images MODIFY title varchar(255);
CREATE INDEX images_title_index ON images(title);
DROP INDEX IF EXISTS page_url ON images;
