// A rated round's stars in the large view (large/navigationButtons.scala.html), once the page is
// parsed: raty on each .large-stars element with a data-number, from its attributes:
// - data-number: how many stars; data-half: "true" in a half-star round, where each star is
//   two rate points;
// - data-score: the file's rate in stars (3.5 in a half-star round);
// - data-rate-number: the id of the number above the stars (large-rate.js), which shows the
//   rate a click on a hovered star gives;
// - data-readonly: "true" in a view that votes nothing (another juror's, a stopped round);
// - data-vote-prefix and data-vote-query: a click votes the path prefix + the rate in rate
//   points + the query (the list's, and the criterion's), posted by large-view.js;
// - data-cancel: "false" in a round with the worst mark, whose own worst-mark and clear buttons
//   stand apart, so the stars have no cancel icon (a cross in a circle) of their own.
// The stars are Bootstrap Icons (raty's starType 'i'), coloured and sized by main.css's
// .wlx-star and .raty-cancel, without tooltips: the number shows the rate.
(function () {
  'use strict';

  var DEFAULTS = {
    starType: 'i',
    starOn: 'bi bi-star-fill wlx-star',
    starHalf: 'bi bi-star-half wlx-star',
    starOff: 'bi bi-star wlx-star',
    cancelOn: 'bi bi-x-circle-fill',
    cancelOff: 'bi bi-x-circle',
    noRatedMsg: ''
  };

  /** raty's options for the stars element `el`. */
  function options(el, doc) {
    var half = el.getAttribute('data-half') === 'true';
    var k = half ? 2 : 1;
    var n = Number(el.getAttribute('data-number'));
    var opts = {
      score: Number(el.getAttribute('data-score')) || 0,
      half: half,
      cancel: el.getAttribute('data-cancel') !== 'false',
      number: n,
      precision: false,
      space: false,
      hints: Array(n).fill('')
    };
    if (el.getAttribute('data-readonly') === 'true') {
      opts.readOnly = true;
      return opts;
    }
    var number = window.wlxLargeRate.number(doc.getElementById(el.getAttribute('data-rate-number')), half);
    var prefix = el.getAttribute('data-vote-prefix');
    var query = el.getAttribute('data-vote-query') || '';
    opts.mouseover = number.mouseover;
    opts.mouseout = number.mouseout;
    opts.click = function (score) {
      window.wlxLargeView.vote(prefix + Math.round(number.clicked(score) * k) + query);
      return false;
    };
    return opts;
  }

  /** Draws the stars of each of `doc`'s .large-stars[data-number]; returns how many. */
  function init(doc) {
    var $ = window.jQuery;
    if (!$ || !$.fn.raty) return 0;
    $.extend($.fn.raty.defaults, DEFAULTS);
    var els = doc.querySelectorAll('.large-stars[data-number]');
    Array.prototype.forEach.call(els, function (el) {
      $(el).raty(options(el, doc));
    });
    return els.length;
  }

  window.wlxLargeStars = { init: init, options: options };

  if (typeof module !== 'undefined') {
    module.exports = window.wlxLargeStars;
  } else {
    window.jQuery(function () { init(document); });
  }
})();
