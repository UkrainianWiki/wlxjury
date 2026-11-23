// The sticky top bar (main.css, body class wlx-topbar-sticky; the maintainer's decision of
// 2026-11-23): on the pages whose layout opts in (main/mainAdmin `stickyBar`: the galleries, the
// file list, the by-rate gallery, the comments report, the Rounds, round statistics, Contests,
// Users and contest files pages; never the large views, which have no navbar) the navbar stays
// at the window's top from 576 px wide, as MediaWiki's sticky header does (CSS only).
// This script only keeps --wlx-topbar-height on the root element up to date (a CSSOM custom
// property, not a style attribute: allowed by the content security policy): the bar's height,
// which main.css makes the root's scroll-padding-top and the room for the bar's menus, so that
// whatever the browser scrolls into view (a focused element, a fragment's target, a found text,
// a file of file-anchor.js) lands below the bar (WCAG 2.4.11).
(function () {
  'use strict';

  var OPT_IN = 'wlx-topbar-sticky';

  /** The page's sticky bar: the navbar of a page that opts in, or null. */
  function find(doc) {
    return doc.body && doc.body.classList.contains(OPT_IN) ? doc.querySelector('body > nav.navbar') : null;
  }

  /** Sets --wlx-topbar-height to the bar's height; returns it. */
  function measure(doc, bar) {
    var height = bar.offsetHeight;
    doc.documentElement.style.setProperty('--wlx-topbar-height', height + 'px');
    return height;
  }

  /** Starts it on the page, if it opts in: now, and whenever the bar's size changes. */
  function start(win) {
    var doc = win.document;
    var bar = find(doc);
    if (!bar) return null;
    function update() { measure(doc, bar); }
    update();
    win.addEventListener('resize', update);
    if (typeof win.ResizeObserver === 'function') new win.ResizeObserver(update).observe(bar);
    return bar;
  }

  window.wlxTopbar = { find: find, measure: measure, start: start };

  if (typeof module !== 'undefined') {
    module.exports = window.wlxTopbar;
  } else if (document.readyState === 'loading') {
    // before file-anchor.js's own DOMContentLoaded handler (this script is loaded before it), so
    // that the root's scroll-padding-top has the bar's height when it scrolls
    document.addEventListener('DOMContentLoaded', function () { start(window); });
  } else {
    start(window);
  }
})();
