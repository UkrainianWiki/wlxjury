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
//   link (wlx-large-failed), a grey box.
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

  if (window.jQuery) {
    window.jQuery(document).on('click', '[data-vote-url]', function (e) {
      e.preventDefault();
      vote(this.getAttribute('data-vote-url'));
    });
  }

  window.wlxLargeView = { vote: vote, imageFailed: imageFailed };

  if (typeof module !== 'undefined') module.exports = window.wlxLargeView;
})();
