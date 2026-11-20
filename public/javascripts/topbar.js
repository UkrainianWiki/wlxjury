// The reappearing top bar (a trial, the maintainer's decision of 2026-11-20): on the pages whose
// layout opts in (main/mainAdmin `reappearingBar`, the body class wlx-topbar-reappears: the
// galleries, the file list, the by-rate gallery, the comments report, the Rounds, round statistics,
// Contests, Users and contest files pages; never the large views, which have no navbar), the navbar
// is sticky at the window's top (main.css) and this script hides it (the class wlx-topbar-hidden,
// a transform, animated unless the user prefers reduced motion) while the user scrolls down, and
// shows it again as soon as they scroll up a little. It stays shown:
//   - near the page's top (scrolled less than its own height);
//   - while one of its dropdown menus is open (a menu that scrolls inside itself, the region and
//     round menus, may pass its scroll on to the page);
//   - when the focus moves into it, and while it is there after a Tab (keyboard users);
//   - and the focus on the page is never under it: an element focused under its place is
//     moved below it by a keyboard user's focus (scroll-padding-top does that, but not in WebKit);
//   - after file-anchor.js scrolled to a list's file (Back to gallery; the event wlx:file-anchor),
//     until the user's next wheel, key, tap or click: the file lands below the bar (the bar's
//     height, --wlx-topbar-height, is the root's scroll-padding-top, main.css), with the list's
//     menus in view as at the page's top.
// It also keeps --wlx-topbar-height on the root element up to date (a CSSOM custom property, not a
// style attribute: allowed by the content security policy): the root's scroll-padding-top, so that
// whatever the browser scrolls into view (a focused element, a fragment's target, a found text, a
// file of file-anchor.js) lands below the bar, also when the bar comes back (WCAG 2.4.11); and the
// height of the bar's menus, so that a menu in the sticky bar never ends below the window. A page
// restored from the back/forward cache starts afresh (pageshow).
(function () {
  'use strict';

  /** The body class a page opts in with, and the class that hides the bar. */
  var OPT_IN = 'wlx-topbar-reappears';
  var HIDDEN = 'wlx-topbar-hidden';
  /** How far, in px, the page moves in one direction before the bar follows (no jitter). */
  var THRESHOLD = 10;
  /** How long, in ms, a newly focused element is kept below the bar while the browser scrolls. */
  var FOCUS_MS = 1000;

  /** The page's sticky bar: the navbar of a page that opts in, or null. */
  function find(doc) {
    return doc.body && doc.body.classList.contains(OPT_IN) ? doc.querySelector('body > nav.navbar') : null;
  }

  /**
    * The bar's state machine. `win` is the window (scrollY, document); `bar` the navbar.
    * Returns its handlers (onScroll, onFocusIn, onPageFocus, onKey, onPointer, onWheel, onMenuShow,
    * onAnchor, onPageShow), measure and hidden.
    */
  function create(win, bar) {
    var doc = win.document;
    var lastY = Math.max(0, win.scrollY || 0);
    var keyboard = false;
    var held = false;
    /** A keyboard user's newly focused element kept below the bar for a moment: { el, until }. */
    var focused = null;
    /** The bar's height, as last measured (not read on each scroll). */
    var height = bar.offsetHeight;

    function hidden() { return bar.classList.contains(HIDDEN); }
    function show() { bar.classList.remove(HIDDEN); }
    function hide() { bar.classList.add(HIDDEN); }

    function menuOpen() { return !!bar.querySelector('.dropdown-menu.show'); }
    /** Whether a keyboard user's focus is in the bar (moved there by Tab; a click leaves the
      * focus on a toggle, which must not keep the bar shown). */
    function keyboardFocus() { return keyboard && bar.contains(doc.activeElement); }

    /** Measures the bar and sets --wlx-topbar-height to its height. */
    function measure() {
      height = bar.offsetHeight;
      doc.documentElement.style.setProperty('--wlx-topbar-height', height + 'px');
    }

    function onScroll() {
      keepFocusInView(true);
      var y = Math.max(0, win.scrollY || 0);
      if (y <= height || held || menuOpen() || keyboardFocus()) {
        show();
        lastY = y;
        return;
      }
      var dy = y - lastY;
      if (Math.abs(dy) < THRESHOLD) return;
      if (dy > 0) hide(); else show();
      lastY = y;
    }

    /** The user's own input: ends the hold after a file-anchor scroll. */
    function release() {
      if (held) {
        held = false;
        lastY = Math.max(0, win.scrollY || 0);
      }
    }

    /** The focus moved to an element of the page: if it ends under the bar's place (WebKit's
      * focus scroll ignores the root's scroll-padding-top, and Shift+Tab onto an element above
      * the window puts it at the window's top edge), the page is scrolled up so that it starts
      * below the bar, which that scroll up shows (WCAG 2.4.11). Checked when the focus moves and
      * on each scroll for FOCUS_MS after it (WebKit's own scroll to the element comes later and
      * may be animated), until the user's next key, wheel, tap or click, the first scroll that
      * leaves the element below the bar (then a drag of the scrollbar is the user's), or a
      * correction that didn't move the element (one fixed in the window). Only for a keyboard
      * user's focus (a click's focus stays where it is), and not at the page's top, where the bar
      * is in its place. */
    function onPageFocus(e) {
      var el = e && e.target;
      if (!keyboard || !el || el === doc || !el.getBoundingClientRect || bar.contains(el)) return;
      focused = { el: el, until: Date.now() + FOCUS_MS };
      if (typeof win.requestAnimationFrame === 'function') win.requestAnimationFrame(function () { keepFocusInView(false); });
      else keepFocusInView(false);
    }

    /** `scrolled`: called for a scroll event (not when the focus moved). */
    function keepFocusInView(scrolled) {
      if (!focused) return;
      if (Date.now() > focused.until || doc.activeElement !== focused.el) {
        focused = null;
        return;
      }
      var top = focused.el.getBoundingClientRect().top;
      if (top >= height) {
        // below the bar after a scroll (the browser's, its own correction's, a drag of the
        // scrollbar): done, so that it never fights the user's own scrolling
        if (scrolled) focused = null;
        return;
      }
      var y = win.scrollY || 0;
      if (y <= 0) return;
      win.scrollTo({ top: Math.max(0, y + top - height), behavior: 'instant' });
      // an element that doesn't move with the page (fixed or sticky): nothing to correct
      if (focused.el.getBoundingClientRect().top === top) focused = null;
    }

    /** A page brought back from the back/forward cache: no hold, the position as it is. */
    function onPageShow(e) {
      if (!e || !e.persisted) return;
      held = false;
      lastY = Math.max(0, win.scrollY || 0);
      measure();
    }

    return {
      hidden: hidden,
      measure: measure,
      onScroll: onScroll,
      onPageShow: onPageShow,
      onFocusIn: function () { show(); },
      onPageFocus: onPageFocus,
      // only Tab moves the focus by keyboard; any key ends the hold
      onKey: function (e) {
        if (e && e.key === 'Tab') keyboard = true;
        else focused = null;
        release();
      },
      onPointer: function () { keyboard = false; focused = null; release(); },
      onWheel: function () { focused = null; release(); },
      onMenuShow: function () { show(); },
      onAnchor: function () { held = true; show(); }
    };
  }

  /** Starts the bar on the page, if it opts in. */
  function start(win) {
    var doc = win.document;
    var bar = find(doc);
    if (!bar) return null;
    var state = create(win, bar);
    state.measure();
    win.addEventListener('scroll', state.onScroll, { passive: true });
    win.addEventListener('resize', state.measure);
    win.addEventListener('pageshow', state.onPageShow);
    if (typeof win.ResizeObserver === 'function') new win.ResizeObserver(state.measure).observe(bar);
    bar.addEventListener('focusin', state.onFocusIn);
    doc.addEventListener('focusin', state.onPageFocus);
    // Bootstrap 5 dispatches show.bs.dropdown on the toggle; it bubbles
    bar.addEventListener('show.bs.dropdown', state.onMenuShow);
    doc.addEventListener('keydown', state.onKey, true);
    doc.addEventListener('pointerdown', state.onPointer, true);
    doc.addEventListener('touchstart', state.onPointer, { capture: true, passive: true });
    doc.addEventListener('wheel', state.onWheel, { capture: true, passive: true });
    doc.addEventListener('wlx:file-anchor', state.onAnchor);
    return state;
  }

  window.wlxTopbar = { find: find, create: create, start: start, HIDDEN: HIDDEN, THRESHOLD: THRESHOLD, FOCUS_MS: FOCUS_MS };

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
