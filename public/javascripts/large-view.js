// The large views (large/main_large.scala.html, large/contestFile.scala.html), loaded in <head>:
// - a vote is a POST of the page's vote form (#vote-form, with its CSRF token) to the vote's
//   path: a GET changes nothing. The keys (large-keys.js), the stars (large-stars.js) and the
//   buttons with a data-vote-url submit it. A read-only view has no vote form, and votes
//   nothing;
// - an image that fails to load gets its mark, from one listener on the document in the
//   capture phase (an image's error event doesn't bubble), registered before the body is
//   parsed so that it sees every failure: an <img data-failed-mark="thumb"> marks its box
//   (div.thumb_small, wlx-thumb-failed); an <img data-failed-mark="large"> is loaded once
//   more from its data-fallback (this server, which finds a file renamed or deleted on
//   Commons) without its srcset and sizes, and if that fails too, or it has none, marks its
//   link (wlx-large-failed), a grey box;
// - the large image (#large_image) has its box at its loaded height until the file arrives (its
//   width and height attributes, Global.largeImageBox): once loaded they go, from one
//   capture-phase listener as the failures', and the view's rules size the file as they did
//   before, also one whose proportions differ from the stored ones;
// - a page opened at its comments (#comments: the tiles', the large view's and the comments
//   list's chips) scrolls to them once more when the image loads or fails and when the page has
//   loaded, unless the user has scrolled since: for a file of unknown size, of other proportions
//   than the stored ones, the grey box, or the browser's smooth scroll cut short.
(function () {
  'use strict';

  /** Posts the vote form to `url`, a path. */
  function vote(url) {
    var form = document.getElementById('vote-form');
    if (!form) return false;
    form.action = url;
    form.submit();
    return true;
  }

  /** The capture-phase error listener: the mark, or the fallback, of a failed image. */
  function imageFailed(e) {
    var img = e.target;
    if (!img || img.tagName !== 'IMG') return;
    var mark = img.getAttribute('data-failed-mark');
    if (mark === 'thumb') {
      var box = img.parentNode && img.parentNode.parentNode;
      if (box && box.classList) box.classList.add('wlx-thumb-failed');
    } else if (mark === 'large') {
      var fallback = img.getAttribute('data-fallback');
      if (fallback) {
        img.removeAttribute('data-fallback');
        img.removeAttribute('srcset');
        img.removeAttribute('sizes');
        img.src = fallback;
      } else if (img.parentNode && img.parentNode.classList) {
        img.parentNode.classList.add('wlx-large-failed');
      }
    }
  }

  document.addEventListener('error', imageFailed, true);

  /** The capture-phase load listener: the loaded large image without its box (its width and height). */
  function imageLoaded(e) {
    var img = e.target;
    if (img && img.tagName === 'IMG' && img.id === 'large_image') {
      img.removeAttribute('width');
      img.removeAttribute('height');
    }
  }

  document.addEventListener('load', imageLoaded, true);

  /** The user's own scrolling (or a key, a click): the end of keepCommentsInView's watch. */
  var USER_SCROLLS = ['wheel', 'touchmove', 'keydown', 'mousedown', 'pointerdown'];
  /** keepCommentsInView's wait, once the page has loaded, for the scroll position to stay put. */
  var SETTLE_STEP_MS = 50;
  var SETTLE_MAX_MS = 2000;

  /** How the page was reached: 'navigate', 'reload' or 'back_forward' (the navigation timing's
   *  type, else the older performance.navigation's). */
  function navigationType(win) {
    var perf = win.performance;
    if (!perf) return 'navigate';
    var entries = typeof perf.getEntriesByType === 'function' ? perf.getEntriesByType('navigation') : [];
    if (entries && entries[0] && entries[0].type) return entries[0].type;
    if (perf.navigation) return ['navigate', 'reload', 'back_forward'][perf.navigation.type] || 'navigate';
    return 'navigate';
  }

  /**
   * On a page opened at #comments: scrolls to the comments (#comments) again, instantly, when
   * the large image loads or fails (each time: a failed image loads its fallback) and when the
   * page has loaded, until the user scrolls, a key or a click, or the hash changes. The browser's
   * own scroll to them may have come before the image had its height, or have been cut short by
   * it (Bootstrap makes it smooth; WebKit says an image is complete before its load event).
   * Once the page has loaded, after the browser's own (smooth) scroll has settled, which may
   * still be heading where the comments were before the image changed height: then only if the
   * comments' heading isn't in view, or the image is still loading. Not on a reload or Back (the
   * view is no-store, so Back loads it again): the browser restores where the user was. Returns
   * whether it watches.
   */
  function keepCommentsInView(doc, win) {
    if (win.location.hash !== '#comments') return false;
    var how = navigationType(win);
    if (how === 'reload' || how === 'back_forward') return false;
    var target = doc.getElementById('comments');
    if (!target) return false;
    var img = doc.getElementById('large_image');
    var stopped = false;
    function userScrolled() {
      stop();
    }
    function scrollBack() {
      if (win.location.hash !== '#comments') return;
      try {
        target.scrollIntoView({ block: 'start', behavior: 'instant' });
      } catch (e) {
        // a browser without 'instant'
        target.scrollIntoView({ block: 'start' });
      }
    }
    function pageLoaded() {
      win.removeEventListener('load', pageLoaded);
      var last = null;
      var waited = 0;
      (function settle() {
        if (stopped) return;
        var y = win.pageYOffset;
        if (y !== last && waited < SETTLE_MAX_MS) {
          last = y;
          waited += SETTLE_STEP_MS;
          win.setTimeout(settle, SETTLE_STEP_MS);
          return;
        }
        var top = target.getBoundingClientRect().top;
        var inView = top >= 0 && top < (win.innerHeight || doc.documentElement.clientHeight);
        if (!(inView && (!img || img.complete))) scrollBack();
        stop();
      })();
    }
    function stop() {
      stopped = true;
      if (img) {
        img.removeEventListener('load', scrollBack);
        img.removeEventListener('error', scrollBack);
      }
      win.removeEventListener('load', pageLoaded);
      USER_SCROLLS.forEach(function (type) { win.removeEventListener(type, userScrolled, true); });
    }
    if (img) {
      img.addEventListener('load', scrollBack);
      img.addEventListener('error', scrollBack);
    }
    win.addEventListener('load', pageLoaded);
    USER_SCROLLS.forEach(function (type) { win.addEventListener(type, userScrolled, { capture: true, passive: true }); });
    return true;
  }

  document.addEventListener('DOMContentLoaded', function () { keepCommentsInView(document, window); });

  if (window.jQuery) {
    window.jQuery(document).on('click', '[data-vote-url]', function (e) {
      e.preventDefault();
      vote(this.getAttribute('data-vote-url'));
    });
  }

  window.wlxLargeView = { vote: vote, imageFailed: imageFailed, imageLoaded: imageLoaded, keepCommentsInView: keepCommentsInView };

  if (typeof module !== 'undefined') module.exports = window.wlxLargeView;
})();
