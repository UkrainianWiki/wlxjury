// The votes on the juror's own gallery tiles (ug-theme-tiles.js), sent by script to
// GalleryController.selectWS with the page's CSRF token; a failed one explained by
// vote-errors.js. A binary round's tile shows the file's vote as a frame (main.css:
// selected, rejected, none unrated) from its stored rate (data-rate) on, with the thumb of
// that vote pressed, as a click on it undoes the vote. A rated round's tiles get stars (raty,
// as the large view's, large-stars.js), drawn from the stored rate; a click votes, the
// stars showing the new rate at once and the last stored one again if the vote fails. While a
// tile's vote is pending (aria-busy) its further clicks are ignored, so the votes on a file are
// sent one at a time and stored in the order shown. The tile
// stays where it is, also in a gallery ordered by rate: only a reload orders it again. The
// juror's own by-rate list (galleryByRate.scala.html) gets the same stars per file, its rate
// text following the vote and the file marked changed, as its rank and place are stale.
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

  /** A binary round's `rate` on the tile: its frame, and its thumbs pressed or not. */
  function showVote(tile, rate) {
    rate = +rate || 0;
    tile.classList.toggle('wlx-tile-selected', rate === 1);
    tile.classList.toggle('wlx-tile-rejected', rate === -1);
    pressed(tile.querySelector('.ug-icon-select'), rate === 1);
    pressed(tile.querySelector('.ug-icon-reject'), rate === -1);
  }

  function pressed(icon, on) {
    if (icon) icon.setAttribute('aria-pressed', String(on));
  }

  /** A binary round's tile as the page loads: its thumbs toggle buttons, named by the page's
    * texts (data-vote-selected / -rejected), and the file's stored vote. The thumbs take the
    * focus and vote with Enter or Space as with a click (ug-theme-tiles.js: the thumb of the
    * file's vote undoes it), the key never reaching the tile, which opens the large view. */
  function addVote(tile, item) {
    var holder = document.querySelector('[data-csrf-token]');
    [['.ug-icon-select', 'selected', 1], ['.ug-icon-reject', 'rejected', -1]].forEach(function (thumb) {
      var icon = tile.querySelector(thumb[0]);
      if (!icon) return;
      icon.setAttribute('role', 'button');
      icon.setAttribute('tabindex', '0');
      var label = holder && holder.getAttribute('data-vote-' + thumb[1]);
      if (label) icon.setAttribute('aria-label', label);
      icon.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault(); // Space would scroll the page
        e.stopPropagation();
        vote(tile, item, +item.rate === thumb[2] ? 0 : thumb[2]);
      });
    });
    showVote(tile, item.rate);
  }

  /** Marks `el` busy while its vote is pending, or not. */
  function busy(el, on) {
    if (on) el.setAttribute('aria-busy', 'true');
    else el.removeAttribute('aria-busy');
  }

  function isBusy(el) {
    return el.getAttribute('aria-busy') === 'true';
  }

  /** A binary round's vote: shown once stored, explained if it fails; none sent while one on
    * the tile is pending (null then). */
  function vote(tile, item, rate) {
    if (isBusy(tile)) return null;
    busy(tile, true);
    return send(item, rate).done(function () {
      busy(tile, false);
      item.rate = rate;
      showVote(tile, rate);
    }).fail(function (jqXHR) {
      busy(tile, false);
      explain(jqXHR);
    });
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
   * `voted`, if given, is called with the rate once it is stored. While a vote is pending the
   * stars show its rate and ignore clicks; a failed one shows the last stored rate again.
   * With `options.worst` (a round with the worst mark), a ✗ button after them gives the file
   * ✗ (rate -1, its stars empty, the button pressed), and the cancel icon is an eraser that
   * clears the vote, shown only once the file has one: giving ✗ and clearing stay apart.
   */
  function addStars(tile, item, options, voted) {
    var $ = window.jQuery;
    var units = options.half ? 2 : 1;
    // the stars of a rate: none for unrated or a ✗ (-1, the worst mark)
    function starsOf(rate) { return Math.max(rate, 0) / units; }
    var currentRate = +item.rate || 0;
    var storedRate = currentRate; // the rate the server last confirmed
    var el = $('<div class="wlx-tile-stars"></div>')
      .css('--wlx-tile-stars', options.number + (options.worst ? 2 : 1))
      .on('click', function (e) { e.stopPropagation(); })
      .appendTo(tile)[0];
    var worst = null;

    /** Draws `rate`: its stars, the ✗ button pressed for a ✗, the clear (raty's cancel icon, in a
      * round with the worst mark an eraser) only once the file has a vote. */
    function draw(rate) {
      show(el, starsOf(rate));
      if (!options.worst) return;
      worst.setAttribute('aria-pressed', String(rate === -1));
      worst.classList.toggle('wlx-tile-worst-on', rate === -1);
      var clear = el.querySelector('.raty-cancel');
      if (clear) clear.hidden = rate === 0;
    }

    /** Votes `rate`: shown at once, the last stored one again if it fails; none while one is
      * pending. */
    function rateTo(rate) {
      if (isBusy(el)) {
        draw(currentRate); // the pending vote's
        return;
      }
      currentRate = rate;
      draw(rate);
      busy(el, true);
      send(item, rate).done(function () {
        busy(el, false);
        storedRate = rate;
        item.rate = rate;
        tile.setAttribute('data-rate', rate);
        if (voted) voted(rate);
      }).fail(function (jqXHR) {
        busy(el, false);
        currentRate = storedRate;
        draw(storedRate);
        explain(jqXHR);
      });
    }

    var holder = document.querySelector('[data-csrf-token]');
    $(el).raty({
      starType: 'i',
      starOn: 'bi bi-star-fill wlx-star',
      starHalf: 'bi bi-star-half wlx-star',
      starOff: 'bi bi-star wlx-star',
      // in a round with the worst mark, the cancel icon clears and looks it: ✗ is its own button
      cancelOn: options.worst ? 'bi bi-eraser-fill' : 'bi bi-x-circle-fill',
      cancelOff: options.worst ? 'bi bi-eraser' : 'bi bi-x-circle',
      cancel: true,
      cancelHint: options.worst ? (holder && holder.getAttribute('data-vote-clear')) || '' : '',
      noRatedMsg: '',
      hints: Array(options.number).fill(''),
      number: options.number,
      half: options.half,
      precision: false,
      space: false,
      score: starsOf(currentRate),
      click: function (score) {
        // the stars drawn under the pointer, as the large view gives them (large-rate.js)
        rateTo(Math.round(window.wlxLargeRate.clicked(score, options.half) * units));
        return false; // drawn above, not by raty's own rounding
      }
    });
    if (options.worst) {
      // the ✗ (the worst mark), a toggle button showing the file's ✗; a click gives it, never
      // clears it (the clear is apart), Enter or Space as a click
      var label = (holder && holder.getAttribute('data-vote-worst')) || '';
      worst = document.createElement('i');
      worst.className = 'bi bi-x-lg wlx-tile-worst';
      worst.setAttribute('role', 'button');
      worst.setAttribute('tabindex', '0');
      worst.setAttribute('aria-label', label);
      worst.title = label;
      worst.addEventListener('click', function (e) { e.stopPropagation(); rateTo(-1); });
      worst.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault();
        e.stopPropagation();
        rateTo(-1);
      });
      el.appendChild(worst);
      draw(currentRate);
    }
    return el;
  }

  /**
   * The stars of the juror's own by-rate list (`list`: its data-stars-number / -half), in
   * place of each file's static ones (.wlx-byrate-stars, with its data-round-id / -page-id /
   * -rate). A stored vote shows the new rate in the text after the stars (.wlx-rate-text: the
   * juror's rate in stored units, as ImageWithRating.rateString) and leaves the file where it
   * is, with its rank: the file marked changed (.wlx-rate-changed, titled with the list's
   * data-changed-title) unless back at its rate at load, and the hint above the list
   * (.wlx-byrate-hint) shown from the first change on.
   */
  function addListStars(list) {
    var options = {
      number: +list.getAttribute('data-stars-number'),
      half: list.getAttribute('data-stars-half') === 'true',
      worst: list.getAttribute('data-stars-worst') === 'true'
    };
    var hint = document.querySelector('.wlx-byrate-hint');
    var title = list.getAttribute('data-changed-title') || '';
    Array.prototype.forEach.call(list.querySelectorAll('.wlx-byrate-stars'), function (holder) {
      var box = holder.closest('.gallerybox') || holder;
      var text = box.querySelector('.wlx-rate-text');
      var item = {
        roundId: holder.getAttribute('data-round-id'),
        pageId: holder.getAttribute('data-page-id'),
        rate: holder.getAttribute('data-rate')
      };
      var atLoad = +item.rate || 0;
      holder.textContent = '';
      addStars(holder, item, options, function (rate) {
        var changed = rate !== atLoad;
        // a worst mark as its icon, as galleryByRate.scala.html renders it
        if (text) {
          text.textContent = rate < 0 ? '' : String(rate);
          if (rate < 0) {
            var icon = document.createElement('i');
            icon.className = 'bi bi-x-lg wlx-worst-mark';
            icon.setAttribute('aria-hidden', 'true');
            text.appendChild(icon);
          }
        }
        box.classList.toggle('wlx-rate-changed', changed);
        if (changed) box.setAttribute('title', title);
        else box.removeAttribute('title');
        if (hint) hint.hidden = false;
      });
    });
  }

  /** All the jurors' votes on a binary round's file, on its tile in the organisers' gallery of
    * them (gallery.scala.html's data-approvals, -rejections and -tally): "✓ 3 ✗ 2" as
    * binaryTally.scala.html has it, the marks and numbers hidden from screen readers, which read
    * the text; none for a file without them. */
  function addTally(tile, item) {
    if (!item.tally) return null;
    var badge = document.createElement('span');
    badge.className = 'badge wlx-tile-tally';
    badge.title = item.tally;
    var shown = document.createElement('span');
    shown.setAttribute('aria-hidden', 'true');
    [['bi-check-lg wlx-vote-selected', item.approvals], ['bi-x-lg wlx-vote-rejected', item.rejections]]
      .forEach(function (mark, i) {
        var icon = document.createElement('i');
        icon.className = 'bi ' + mark[0];
        shown.appendChild(icon);
        shown.appendChild(document.createTextNode(' ' + (+mark[1] || 0) + (i === 0 ? ' ' : '')));
      });
    var text = document.createElement('span');
    text.className = 'visually-hidden';
    text.textContent = item.tally;
    badge.appendChild(shown);
    badge.appendChild(text);
    tile.appendChild(badge);
    return badge;
  }

  window.wlxTileVotes = {
    voteUrl: voteUrl, send: send, explain: explain, addVote: addVote, vote: vote, addStars: addStars,
    addListStars: addListStars, addTally: addTally
  };

  // the by-rate list's stars, once the page is there (raty loaded only where it has them)
  document.addEventListener('DOMContentLoaded', function () {
    var list = document.querySelector('[data-stars-number]');
    if (list && window.jQuery && window.jQuery.fn.raty) addListStars(list);
  });

  if (typeof module !== 'undefined') module.exports = window.wlxTileVotes;
})();
