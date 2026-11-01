// The votes on the juror's own gallery tiles (ug-theme-tiles.js), sent by script to
// GalleryController.selectWS with the page's CSRF token; a failed one explained by
// vote-errors.js. A rated round's tiles get stars (raty, as the large view's,
// large/raty.scala.html), drawn from the file's stored rate (data-rate); a click votes, the
// stars showing the new rate at once and the previous one again if the vote fails. The tile
// stays where it is, also in a gallery ordered by rate: only a reload orders it again.
(function () {
  'use strict';

  /** The vote URL of the tile's file (`item`: unitegallery's, with data-roundId, data-pageId). */
  function voteUrl(item, rate) {
    return '/rate/round/' + item.roundId + '/pageid/' + item.pageId + '/select/' + rate;
  }

  /** The session's CSRF token, which the gallery page renders (data-csrf-token). */
  function csrfToken() {
    var holder = document.querySelector('[data-csrf-token]');
    return holder ? holder.getAttribute('data-csrf-token') : '';
  }

  /** Sends the vote: a POST with the token in Play's header (play.filters.csrf.header.name). */
  function send(item, rate) {
    return window.jQuery.ajax({
      type: 'POST',
      url: voteUrl(item, rate),
      headers: { 'Csrf-Token': csrfToken() }
    });
  }

  /** Why a vote failed, in the page's language (vote-errors.js); signed out, on to the login page. */
  function explain(jqXHR) {
    var texts = voteErrorTexts(document.querySelector('[data-csrf-token]'));
    window.alert(voteErrorMessage(jqXHR.status, jqXHR.responseJSON || null, texts));
    if (jqXHR.status === 401 && texts.loginUrl) window.location.href = texts.loginUrl;
  }

  /** Draws `stars` (0: none) on raty's element `el`. */
  function show(el, stars) {
    if (stars > 0) window.jQuery(el).raty('score', stars);
    else window.jQuery(el).raty('cancel', false);
  }

  /**
   * Adds stars to the tile, for `options.number` stars, in half stars if `options.half` (a
   * half star stored as 1), with raty's cancel icon to unrate. The same icons as the large
   * view's, without tooltips; each star 1em wide (main.css .wlx-tile-stars), the width raty
   * finds the half by. A click on them never reaches the tile, which opens the large view.
   */
  function addStars(tile, item, options) {
    var $ = window.jQuery;
    var units = options.half ? 2 : 1;
    var current = (+item.rate || 0) / units;
    var el = $('<div class="wlx-tile-stars"></div>')
      .css('--wlx-tile-stars', options.number + 1)
      .on('click', function (e) { e.stopPropagation(); })
      .appendTo(tile)[0];

    $(el).raty({
      starType: 'i',
      starOn: 'bi bi-star-fill wlx-star',
      starHalf: 'bi bi-star-half wlx-star',
      starOff: 'bi bi-star wlx-star',
      cancelOn: 'bi bi-x-circle-fill',
      cancelOff: 'bi bi-x-circle',
      cancel: true,
      cancelHint: '',
      noRatedMsg: '',
      hints: Array(options.number).fill(''),
      number: options.number,
      half: options.half,
      precision: false,
      space: false,
      score: current,
      click: function (score) {
        // the stars drawn under the pointer, as the large view gives them (large-rate.js)
        var stars = window.wlxLargeRate.clicked(score, options.half);
        var previous = current;
        var rate = Math.round(stars * units);
        current = stars;
        show(el, stars);
        send(item, rate).done(function () {
          item.rate = rate;
          tile.setAttribute('data-rate', rate);
        }).fail(function (jqXHR) {
          current = previous;
          show(el, previous);
          explain(jqXHR);
        });
        return false; // drawn above, not by raty's own rounding
      }
    });
    return el;
  }

  window.wlxTileVotes = { voteUrl: voteUrl, send: send, explain: explain, addStars: addStars };

  if (typeof module !== 'undefined') module.exports = window.wlxTileVotes;
})();
