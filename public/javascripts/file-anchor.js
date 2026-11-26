// A list's page opened at a file, by Back or Esc from the file's large view (RoundList.atUrl):
// its URL ends in #file-<pageId> (RoundList.fileFragment), which matches no element id, so the
// browser does not jump the file to the top edge and take the navbar out of view. Here, once the
// page is parsed, the file's element (data-file-anchor="<pageId>": the by-rate gallery's tile,
// the comments report's row) is scrolled to the middle of the window only if it is not wholly in
// view already; one in view (the first files) leaves the page at its top, the navbar shown.
// Not on a reload or Back/Forward to the page, where the browser puts back where the user was.
// Without script, or in a list without such elements (the tiles), the page opens at its top.
// A rating view opened from the round form's cut control (`?cut=N#cut`) scrolls the same way to the
// cut's divider (data-file-anchor="cut", galleryByRate.scala.html).
// The element's scroll-margin-top (main.css --wlx-file-anchor-offset) and the root's
// scroll-padding-top are the room kept above it: on a page with the sticky top bar (topbar.js,
// from 576 px wide) the bar's height and a little more, so that the file lands below the bar.
(function () {
  'use strict';

  var FRAGMENT = /^#(?:file-(\d+)|(cut))$/;

  /** The element of the file (or the cut) the URL's fragment names, or null. */
  function target(doc, hash) {
    var m = FRAGMENT.exec(hash || '');
    return m ? doc.querySelector('[data-file-anchor="' + (m[1] || m[2]) + '"]') : null;
  }

  /** The room kept above the element (its scroll-margin-top and the root's scroll-padding-top,
    * the sticky top bar's height) and the window's size. */
  function frame(win, el) {
    return {
      top: (parseFloat(win.getComputedStyle(el).scrollMarginTop) || 0) +
        (parseFloat(win.getComputedStyle(win.document.documentElement).scrollPaddingTop) || 0),
      height: win.innerHeight || win.document.documentElement.clientHeight,
      width: win.innerWidth || win.document.documentElement.clientWidth
    };
  }

  /** Whether the element is taller than the window below the room kept above it. */
  function tall(win, el) {
    var f = frame(win, el);
    return el.getBoundingClientRect().height > f.height - f.top;
  }

  /** Whether the element is wholly in the window, below the room kept above it; a tall one
    * whether its top is in the window's upper half. */
  function inView(win, el) {
    var r = el.getBoundingClientRect();
    var f = frame(win, el);
    if (r.left < 0 || r.right > f.width) return false;
    if (tall(win, el)) return r.top >= f.top && r.top <= f.height / 2;
    return r.top >= f.top && r.bottom <= f.height;
  }

  /** How the page was opened: 'navigate', 'reload', 'back_forward' (Navigation Timing). */
  function navigationType(win) {
    try {
      var entries = win.performance.getEntriesByType('navigation');
      return entries && entries[0] ? entries[0].type : 'navigate';
    } catch (e) {
      return 'navigate';
    }
  }

  /** Scrolls to the fragment's file if it is out of view; returns whether it scrolled. */
  function reveal(win) {
    if (navigationType(win) !== 'navigate') return false;
    var el = target(win.document, win.location.hash);
    if (!el || inView(win, el)) return false;
    el.scrollIntoView({ block: tall(win, el) ? 'start' : 'center' });
    return true;
  }

  /** [[reveal]] once the page is parsed, and again when it has loaded if the page is still where
    * the first one left it (the user hasn't scrolled). */
  function start(win) {
    var doc = win.document;
    var at = null;
    function first() {
      reveal(win);
      at = win.scrollY;
    }
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', first);
    else first();
    win.addEventListener('load', function () {
      if (at !== null && win.scrollY === at) reveal(win);
    });
  }

  window.wlxFileAnchor = { target: target, inView: inView, tall: tall, reveal: reveal, start: start };

  if (typeof module !== 'undefined') {
    module.exports = window.wlxFileAnchor;
  } else {
    start(window);
  }
})();
