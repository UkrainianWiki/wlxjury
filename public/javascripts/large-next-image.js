// The large view's next file's photo, loaded by script once the page is up, so that the next
// vote's page takes it from the browser cache (docs/performance/2026-11-24-large-view-timing.md,
// variant S: 84-103 ms saved per vote at 80 ms latency, 347-369 ms at 8 Mbit/s). The server
// renders #wlx-next-image with the src, srcset and sizes the next file's own page gives its
// #large_image (large/nextImage.scala.html); an Image made with the same ones, sizes before
// srcset, picks the same candidate and so the same cache entry. Not with Save-Data, nor
// without the element (the last file, a video, a file through this server, a deleted file).
(function () {
  'use strict';

  /** True if the browser asks for less data (Save-Data). */
  function saveData(nav) {
    var connection = nav && (nav.connection || nav.mozConnection || nav.webkitConnection);
    return !!(connection && connection.saveData);
  }

  /** Loads the next file's photo; returns the Image, or null if there is none to load. */
  function prefetch(doc, nav, ImageClass) {
    var next = doc.getElementById('wlx-next-image');
    if (!next || saveData(nav)) return null;
    var image = new ImageClass();
    image.decoding = 'async';
    // sizes first: srcset's candidate is picked by it as the src is set
    var sizes = next.getAttribute('data-sizes');
    if (sizes) image.sizes = sizes;
    var srcset = next.getAttribute('data-srcset');
    if (srcset) image.srcset = srcset;
    image.src = next.getAttribute('data-src') || '';
    return image;
  }

  window.wlxNextImage = { prefetch: prefetch, saveData: saveData };

  document.addEventListener('DOMContentLoaded', function () {
    prefetch(document, window.navigator, window.Image);
  });

  if (typeof module !== 'undefined') module.exports = window.wlxNextImage;
})();
