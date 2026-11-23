// Keeps an open top-bar menu inside a narrow window (below 576 px: phones; the maintainer's
// decision of 2026-11-20). Bootstrap shows a navbar's menus under their toggle without Popper,
// from the toggle's left edge (or its right edge, dropdown-menu-end), so a menu opened near the
// bar's right edge (the Comments and Region menus) ran past the window's and the page scrolled
// sideways. An open menu that would end past the right edge is moved left by as much, never past
// the left edge (main.css caps its width at the window's less 1 rem there, wrapping long items),
// and fitted again while it is open as its size changes (the region menu fills itself after
// opening) or the window's does; closing it clears that. In a wider window nothing changes.
(function () {
  'use strict';

  /** main.css's narrow-window query (Bootstrap's sm breakpoint). */
  var NARROW = '(max-width: 575.98px)';
  /** The room kept between a moved menu and the window's edges, in px (main.css's 1 rem / 2). */
  var GUTTER = 8;

  /** The horizontal move (px; negative: to the left) that brings a box spanning left..right inside
    * from..to (window coordinates; from 0 if not given) with GUTTER at each side; the left edge
    * wins for a box too wide. */
  function shift(left, right, to, from) {
    var start = (from || 0) + GUTTER;
    var end = to - GUTTER;
    var dx = 0;
    if (right > end) dx = end - right;
    if (left + dx < start) dx = Math.max(dx, start - left);
    return Math.round(dx);
  }

  /** The toggle's menu: the dropdown-menu next to it in its item. */
  function menuOf(toggle) {
    var item = toggle && toggle.parentElement;
    if (!item) return null;
    for (var el = item.firstElementChild; el; el = el.nextElementSibling) {
      if (el.classList.contains('dropdown-menu')) return el;
    }
    return null;
  }

  function create(win) {
    var doc = win.document;
    var observers = typeof win.ResizeObserver === 'function' ? new Map() : null;

    function narrow() {
      return typeof win.matchMedia === 'function' && win.matchMedia(NARROW).matches;
    }

    /** Moves an open menu inside the window, or back where Bootstrap put it. */
    function fit(menu) {
      menu.style.transform = '';
      if (!narrow() || !menu.classList.contains('show')) return;
      var box = menu.getBoundingClientRect();
      // inside the window and the bar both: main.css clips the bar sideways, and on a page
      // scrolled sideways (wider than the window) the bar's edge may be inside the window
      var bar = menu.closest('.navbar');
      var barBox = bar ? bar.getBoundingClientRect() : { left: 0, right: Infinity };
      var dx = shift(box.left, box.right, Math.min(doc.documentElement.clientWidth, barBox.right), Math.max(0, barBox.left));
      if (dx !== 0) menu.style.transform = 'translateX(' + dx + 'px)';
    }

    function inBar(toggle) {
      return !!(toggle && toggle.closest && toggle.closest('.navbar'));
    }

    function onShown(event) {
      if (!inBar(event.target)) return;
      var menu = menuOf(event.target);
      if (!menu) return;
      fit(menu);
      if (observers && !observers.has(menu)) {
        var observer = new win.ResizeObserver(function () { fit(menu); });
        observer.observe(menu);
        observers.set(menu, observer);
      }
    }

    function onHidden(event) {
      if (!inBar(event.target)) return;
      var menu = menuOf(event.target);
      if (!menu) return;
      menu.style.transform = '';
      if (observers && observers.has(menu)) {
        observers.get(menu).disconnect();
        observers.delete(menu);
      }
    }

    function onResize() {
      Array.prototype.forEach.call(doc.querySelectorAll('.navbar .dropdown-menu.show'), fit);
    }

    return { fit: fit, onShown: onShown, onHidden: onHidden, onResize: onResize };
  }

  /** Listens on the page: Bootstrap 5 dispatches shown/hidden.bs.dropdown on the toggle; they bubble. */
  function start(win) {
    var state = create(win);
    win.document.addEventListener('shown.bs.dropdown', state.onShown);
    win.document.addEventListener('hidden.bs.dropdown', state.onHidden);
    win.addEventListener('resize', state.onResize);
    return state;
  }

  window.wlxMenuFit = { shift: shift, menuOf: menuOf, create: create, start: start, NARROW: NARROW, GUTTER: GUTTER };

  if (typeof module !== 'undefined') {
    module.exports = window.wlxMenuFit;
  } else {
    start(window);
  }
})();
