-- The contest files page (GalleryController.contestFiles) filters and counts a contest's
-- files by region, the prefix of their monument id (ImageDbNew.regionOf): it pages over
-- category_members' (category_id, page_id) index and needs each member's monument id,
-- ~40k of them in a contest's category. From the images' rows, a region's deep page and
-- the region counts would read every image row they skip; from this index (the rows'
-- page_id and monument_id only), neither reads an image row (ImageJdbc.categoryMembers,
-- which names it in FORCE INDEX).
CREATE INDEX IF NOT EXISTS images_page_monument_idx ON images (page_id, monument_id)
  ALGORITHM = INPLACE LOCK = NONE;
