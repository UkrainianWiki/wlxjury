-- MediaWiki's file media type (VIDEO, BITMAP, ...): tells Ogg video from audio, which the MIME type can't
alter table images add COLUMN media_type varchar(32);
