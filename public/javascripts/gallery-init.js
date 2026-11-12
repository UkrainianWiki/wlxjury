// Starts the justified tiles (unitegallery's tiles theme, snippets/galleryScripts.scala.html) on
// the page's #gallery, once it is parsed. What the tiles do comes from #gallery's attributes
// (views.GalleryTiles): data-jury, "true" when the viewer votes on them (their own files in an
// active round); data-binary, "true" in a binary round (thumbs up and down, tile-votes.js);
// data-stars, a rated round's tile stars as JSON, {"number": 5, "half": false}, or none;
// data-view-votes, one juror's votes shown read-only (GalleryTiles.viewVotes), or none.
(function () {
  'use strict';

  /** The JSON of `el`'s attribute `name`, null for none or one not read (no stars or marks rather
    * than no gallery). */
  function parsed(el, name) {
    var json = el.getAttribute(name);
    if (!json) return null;
    try {
      return JSON.parse(json);
    } catch (e) {
      return null;
    }
  }

  /** unitegallery's options for the #gallery element `el`. */
  function options(el) {
    var stars = parsed(el, 'data-stars');
    return {
      gallery_theme: 'tiles',
      tiles_type: 'justified',
      theme_gallery_padding: 5,
      tiles_justified_space_between: 5,
      tiles_justified_row_height: 200,
      tile_overlay_opacity: 0.3,
      // no inline #F0F0F0 behind tiles: the page's grey, and main.css's for a
      // thumbnail that failed to load, follow the colour mode
      tile_enable_background: false,
      tile_enable_overlay: true,
      jury: el.getAttribute('data-jury') === 'true',
      binary: el.getAttribute('data-binary') === 'true',
      // a rated round's tile stars: their number, and whether in half stars
      wlx_stars: stars,
      // one juror's votes shown read-only on a binary round's tiles (data-view-votes:
      // {"binary": true}), tile-votes.js addVoteMark
      wlx_view_votes: parsed(el, 'data-view-votes')
    };
  }

  /** Starts the tiles on `doc`'s #gallery, if it has one; returns its options, or null. */
  function init(doc) {
    var el = doc.getElementById('gallery');
    if (!el || !window.jQuery || !window.jQuery.fn.unitegallery) return null;
    var opts = options(el);
    window.jQuery(el).unitegallery(opts);
    return opts;
  }

  if (typeof module !== 'undefined') {
    module.exports = { options: options, init: init };
  } else {
    window.jQuery(function () { init(document); });
  }
})();
