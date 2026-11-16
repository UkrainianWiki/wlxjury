// The votes on the juror's own gallery tiles (ug-theme-tiles.js), sent by script to
// GalleryController.selectWS with the page's CSRF token; a failed one explained by
// vote-errors.js. A binary round's tile shows the file's vote as a ✓ or ✗ chip at its lower
// left, with the vote's time in an order by it ("✓ 14:32"), where all the jurors' tally is on the
// organisers' tiles, the comments' chip after it (chips(); none unrated; the tile's state
// classes wlx-tile-selected / -rejected draw nothing), from its stored rate (data-rate) on, with
// the button of that vote (✓ select, ✗ reject) pressed, as a click on it undoes the vote. A rated round's tiles get stars (raty,
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

  /** A binary round's `rate` on the tile: its state classes, its badge (showMark), and its ✓
    * and ✗ buttons, where it has them, pressed or not. */
  function showVote(tile, rate) {
    rate = +rate || 0;
    tile.classList.toggle('wlx-tile-selected', rate === 1);
    tile.classList.toggle('wlx-tile-rejected', rate === -1);
    pressed(tile.querySelector('.ug-icon-select'), rate === 1);
    pressed(tile.querySelector('.ug-icon-reject'), rate === -1);
    showMark(tile, rate);
  }

  /** The vote's ✓ or ✗ in the tile's vote chip ([[voteChip]]): none for an unrated file. The
    * vote's only mark on a tile at rest: the tile has no frame (too strong) and its buttons show
    * while pointed at. */
  function showMark(tile, rate) {
    chipState(tile).rate = rate;
    voteChip(tile);
  }

  /** The tile's foot: a column at its bottom edge with the chips' row ([[chips]]) above a rated
    * round's stars (addStars, gallery tiles only), so that the two never overlap however the
    * stars wrap; the by-rate gallery's rank line under the thumbnail has one with its chips alone,
    * which the server draws (galleryByRate.scala.html rankChips). It takes no clicks itself (main.css
    * .wlx-tile-foot). */
  function foot(tile) {
    for (var i = 0; i < tile.children.length; i++) {
      if (tile.children[i].classList.contains('wlx-tile-foot')) return tile.children[i];
    }
    var el = document.createElement('div');
    el.className = 'wlx-tile-foot';
    tile.appendChild(el);
    return el;
  }

  /** The tile's chips at its lower left, in one row (the maintainer's decisions of 2026-11-15):
    * first the vote ([[voteChip]]: "✓ 14:32", the juror's own or one juror's as the organisers
    * view it) or all the jurors' tally ([[addTally]]: "✓ 12 ✗ 3"), then the comments
    * ([[addComments]]: "💬 3", a link to the large view's comments). The vote and the tally
    * take no clicks (the tile's click, which opens the large view, goes through them); all of them
    * stay under the ✓ and ✗ buttons (main.css). A row too wide for the tile drops the vote's
    * time first ([[fit]], from the ResizeObserver that watches it). */
  function chips(tile) {
    var f = foot(tile);
    var row = f.querySelector('.wlx-tile-chips');
    if (!row) {
      row = document.createElement('div');
      row.className = 'wlx-tile-chips';
      f.insertBefore(row, f.firstChild);
      if (resizes) resizes.observe(row);
    }
    return row;
  }

  /** `chip` in the tile's chips: the vote or the tally first, the comments after them. */
  function place(tile, chip, first) {
    var row = chips(tile);
    if (first) row.insertBefore(chip, row.firstChild);
    else row.appendChild(chip);
    refit(row);
    return chip;
  }

  /** Removes the tile's chip `selector`, and the chips' row if that leaves it empty. */
  function unplace(tile, selector) {
    var old = tile.querySelector('.wlx-tile-chips > ' + selector);
    if (!old) return;
    var row = old.parentNode;
    row.removeChild(old);
    if (!row.firstElementChild) { // the server's row has whitespace between its chips
      if (resizes) resizes.unobserve(row);
      row.parentNode.removeChild(row);
    }
  }

  /** Rows too wide for their tiles (narrow portrait ones) drop the vote's time, which the large
    * view shows anyway; the marks and the comments' number stay. Measured by a ResizeObserver,
    * which reports each row once laid out and again as the tiles' widths change (the justified
    * rows are laid out again on a resize): all the rows at their full width first, then all their
    * widths read, then the narrow ones tightened, so a page of tiles is laid out once, not once
    * per chip. Without one a row is clipped at the tile's edge (main.css). */
  function fit(rows) {
    rows.forEach(function (row) { row.classList.remove('wlx-chips-tight'); });
    var tight = rows.filter(function (row) { return row.scrollWidth > row.clientWidth + 1; });
    tight.forEach(function (row) { row.classList.add('wlx-chips-tight'); });
  }

  /** `row`, whose chips changed (a vote, its time), measured again in the next frame with the
    * others changed by then: a new chip may not fit where the old one did, and the row's own
    * size, which the ResizeObserver watches, doesn't change with its chips. */
  var pending = [];
  function refit(row) {
    if (!resizes || typeof window.requestAnimationFrame !== 'function') return;
    if (pending.indexOf(row) < 0) pending.push(row);
    if (pending.length > 1) return;
    window.requestAnimationFrame(function () {
      var rows = pending.filter(function (r) { return r.isConnected; });
      pending = [];
      fit(rows);
    });
  }

  var resizes = typeof window.ResizeObserver === 'function'
    ? new window.ResizeObserver(function (entries) {
      fit(entries.map(function (entry) { return entry.target; }));
    })
    : null;

  /** A copy of the page's chip `name` (snippets.chipTemplates: the <template id="wlx-chip-NAME-template">
    * the server renders with snippets.voteChip, binaryTally's chip and snippets.commentChip), for
    * a tile to set its marks, numbers, times, links and words in (text and attributes only, no
    * markup from data); null without it (a page without tiles). */
  function chipFrom(name) {
    var template = document.getElementById('wlx-chip-' + name + '-template');
    var chip = template && template.content && template.content.firstElementChild;
    return chip ? chip.cloneNode(true) : null;
  }

  /** What the tile's vote chip shows: the binary vote's rate (showMark) and the vote's time
    * (addVoteTime: {iso} or {none: the page's text}), each set apart, in either order. */
  function chipState(tile) {
    if (!tile.wlxVoteChip) tile.wlxVoteChip = { rate: 0, time: null };
    return tile.wlxVoteChip;
  }

  /** The tile's vote chip, drawn again from its state, first in its chips: a binary round's ✓ or
    * ✗ in the vote's colour, then the vote's time, muted ("✓ 14:32"); the mark alone without a
    * time, or for a vote whose time wasn't recorded; in a rated round (its stars are its mark) the
    * time alone, or the text saying none was recorded. One name for screen readers and as its
    * title: the vote's (data-vote-selected / -rejected) and the time, "Selected, 12 Nov, 14:05"
    * (the title's time with its year). None for an unrated file. */
  function voteChip(tile) {
    var state = chipState(tile);
    unplace(tile, '.wlx-chip-vote');
    var time = state.time;
    if (time && time.none && state.rate) time = null; // the mark alone
    if (!state.rate && !time) return null;
    var chip = chipFrom('vote');
    if (!chip) return null;
    var holder = document.querySelector('[data-csrf-token]');
    var shown = chip.querySelector('.wlx-chip-shown');
    var icon = shown.querySelector('.wlx-chip-mark');
    var part = shown.querySelector('.wlx-chip-time');
    var said = [];
    var titled = [];
    if (state.rate) {
      var name = (holder && holder.getAttribute(state.rate > 0 ? 'data-vote-selected' : 'data-vote-rejected')) || '';
      icon.setAttribute('class', 'bi wlx-chip-mark ' + (state.rate > 0 ? 'bi-check-lg wlx-vote-selected' : 'bi-x-lg wlx-vote-rejected'));
      said.push(name);
      titled.push(name);
    } else {
      shown.removeChild(icon);
    }
    if (time) {
      part.textContent = '';
      if (time.iso) {
        part.classList.remove('wlx-vote-time-none');
        var el = timeElement(time.iso);
        part.appendChild(el);
        said.push(timeElement(time.iso)); // its own <time>, as snippets.voteChip's words have
        titled.push(el.title || el.textContent);
      } else {
        part.textContent = time.none;
        said.push(time.none);
        titled.push(time.none);
      }
    } else {
      shown.removeChild(part);
    }
    var hidden = chip.querySelector('.visually-hidden');
    hidden.textContent = '';
    said.filter(Boolean).forEach(function (part, i) {
      if (i) hidden.appendChild(document.createTextNode(', '));
      hidden.appendChild(typeof part === 'string' ? document.createTextNode(part) : part);
    });
    hidden.normalize(); // "Selected, " one text, as the server writes it
    chip.setAttribute('title', titled.filter(Boolean).join(', '));
    return place(tile, chip, true);
  }

  function pressed(icon, on) {
    if (icon) icon.setAttribute('aria-pressed', String(on));
  }

  /** A binary round's tile as the page loads: its ✓ and ✗ toggle buttons, named by the page's
    * texts (data-vote-selected / -rejected), and the file's stored vote. They take the
    * focus and vote with Enter or Space as with a click (ug-theme-tiles.js: the icon of the
    * file's vote undoes it), the key never reaching the tile, which opens the large view. */
  function addVote(tile, item) {
    var holder = document.querySelector('[data-csrf-token]');
    [['.ug-icon-select', 'selected', 1], ['.ug-icon-reject', 'rejected', -1]].forEach(function (button) {
      var icon = tile.querySelector(button[0]);
      if (!icon) return;
      icon.setAttribute('role', 'button');
      icon.setAttribute('tabindex', '0');
      var label = holder && holder.getAttribute('data-vote-' + button[1]);
      if (label) icon.setAttribute('aria-label', label);
      icon.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault(); // Space would scroll the page
        e.stopPropagation();
        vote(tile, item, +item.rate === button[2] ? 0 : button[2]);
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
    var before = +item.rate || 0;
    return send(item, rate).done(function () {
      busy(tile, false);
      item.rate = rate;
      showVote(tile, rate);
      voteTimeAfter(tile, item, before, rate);
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
   * With `options.worst` (a round with the worst mark), a worst-mark button (bi-x-lg) after them
   * gives the file the worst mark (rate -1, its stars empty, the button pressed), and the cancel
   * icon is an eraser that clears the vote, shown only once the file has one: giving the worst
   * mark and clearing stay apart.
   */
  function addStars(tile, item, options, voted) {
    var $ = window.jQuery;
    var units = options.half ? 2 : 1;
    // the stars of a rate: none for unrated or the worst mark (-1)
    function starsOf(rate) { return Math.max(rate, 0) / units; }
    var currentRate = +item.rate || 0;
    var storedRate = currentRate; // the rate the server last confirmed
    var el = $('<div class="wlx-tile-stars"></div>')
      .css('--wlx-tile-stars', options.number + (options.worst ? 2 : 1))
      .on('click', function (e) { e.stopPropagation(); })
      // on a gallery tile in its foot, below the chips; in the by-rate list in its box
      .appendTo(tile.classList.contains('ug-tile') ? foot(tile) : tile)[0];
    var worst = null;

    /** Draws `rate`: its stars, the worst-mark button pressed for a worst mark, the clear (raty's cancel icon, in a
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
        var before = storedRate;
        storedRate = rate;
        item.rate = rate;
        voteTimeAfter(tile, item, before, rate);
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
      // in a round with the worst mark, the cancel icon clears and looks it: the worst mark is its own button
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
      // the worst mark's toggle button, showing whether the file has it; a click gives it, never
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
   * (.wlx-byrate-hint) shown from the first change on. In an order by vote time (the list's
   * data-vote-times), the file's vote time follows it in the vote chip in its rank line, as on
   * the gallery's tiles ([[listVoteTimeAfter]]).
   */
  function addListStars(list) {
    var options = {
      number: +list.getAttribute('data-stars-number'),
      half: list.getAttribute('data-stars-half') === 'true',
      worst: list.getAttribute('data-stars-worst') === 'true'
    };
    var hint = document.querySelector('.wlx-byrate-hint');
    var title = list.getAttribute('data-changed-title') || '';
    var voteTimes = list.getAttribute('data-vote-times') === 'true';
    Array.prototype.forEach.call(list.querySelectorAll('.wlx-byrate-stars'), function (holder) {
      var box = holder.closest('.gallerybox') || holder;
      var text = box.querySelector('.wlx-rate-text');
      var item = {
        roundId: holder.getAttribute('data-round-id'),
        pageId: holder.getAttribute('data-page-id'),
        rate: holder.getAttribute('data-rate')
      };
      var atLoad = +item.rate || 0;
      var stored = atLoad;
      holder.textContent = '';
      addStars(holder, item, options, function (rate) {
        var changed = rate !== atLoad;
        if (voteTimes) listVoteTimeAfter(box, stored, rate);
        stored = rate;
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

  /** After a stored vote from `before` to `rate` in the by-rate list (in an order by vote time),
    * the vote chip in the file's rank line under its thumbnail (`box`'s .rank-line, its chips as
    * galleryByRate.scala.html rankChips draws them, after the rank and the stars, before the
    * comments; the chip from the page's template, as a
    * gallery tile's: [[voteChip]]): a changed vote's time is now (the browser's clock, as
    * [[voteTimeAfter]]), the chip added if the file had none; a cleared vote has none; the same
    * vote again keeps it, as the server does (VoteSql.votedAt). A rated round's: the time alone,
    * its stars being its mark. */
  function listVoteTimeAfter(box, before, rate) {
    if (rate === before) return;
    var line = box.querySelector('.rank-line') || box;
    chipState(line).time = rate === 0 ? null : { iso: new Date().toISOString() };
    voteChip(line);
  }

  /** All the jurors' votes on a binary round's file, on its tile in the organisers' gallery of
    * them (gallery.scala.html's data-approvals, -rejections and -tally): "✓ 3 ✗ 2" as
    * binaryTally.scala.html has it, the marks and numbers hidden from screen readers, which read
    * the text; none for a file without them. */
  function addTally(tile, item) {
    if (!item.tally) return null;
    var badge = chipFrom('tally');
    if (!badge) return null;
    badge.setAttribute('title', item.tally);
    badge.querySelector('.wlx-tally-approvals').textContent = String(+item.approvals || 0);
    badge.querySelector('.wlx-tally-rejections').textContent = String(+item.rejections || 0);
    badge.querySelector('.visually-hidden').textContent = item.tally;
    return place(tile, badge, true);
  }

  /** A vote's time `iso` (ISO 8601 in UTC) as snippets/voteTime.scala.html renders it, a
    * <time class="wlx-vote-time"> with it as its datetime, formatted in the viewer's time zone and
    * the page's language (vote-time.js), with the year in its tooltip; the UTC value as its text
    * without the formatter. */
  function timeElement(iso) {
    var time = document.createElement('time');
    time.className = 'wlx-vote-time';
    time.setAttribute('datetime', iso);
    var lang = document.documentElement.getAttribute('lang') || undefined;
    var format = window.wlxVoteTime ? window.wlxVoteTime.format : function () { return null; };
    time.textContent = format(iso, lang) || iso;
    // the tooltip: the same with the year, none without the formatter (no raw UTC value)
    var full = format(iso, lang, undefined, undefined, true);
    if (full) time.title = full;
    return time;
  }

  /** The time of the juror's vote on its tile, in an order by vote time (gallery.scala.html's
    * data-voted-at, ISO 8601 in UTC, on every tile then), in the vote chip after the vote's mark
    * ([[voteChip]]): formatted in the viewer's time zone and the page's language (vote-time.js),
    * or, in a rated round, the text saying none was recorded (data-voted-none); none for an
    * unrated file, or a tile in another order (no data-voted-at). Returns the vote chip, or null
    * without one. */
  function addVoteTime(tile, item) {
    if (item.votedAt === undefined || item.votedAt === null) return null;
    var state = chipState(tile);
    state.time = !(+item.rate) ? null
      : item.votedAt ? { iso: item.votedAt }
      : item.votedNone ? { none: item.votedNone } : null;
    return voteChip(tile);
  }

  /** After a stored vote from `before` to `rate`, the tile's vote time, in an order by vote time
    * (item.votedAt set): a changed vote's is now (the browser's clock: the server's time is a
    * moment earlier, and the next load shows it), a cleared vote has none; the same vote again
    * keeps it, as the server does (VoteSql.votedAt). */
  function voteTimeAfter(tile, item, before, rate) {
    if (item.votedAt === undefined || item.votedAt === null || rate === before) return;
    item.votedAt = rate === 0 ? '' : new Date().toISOString();
    addVoteTime(tile, item);
  }

  /** One juror's vote on a binary round's tile, read-only (no ✓ or ✗ buttons, no clicks: the viewer
    * doesn't vote for them), where the page shows it (gallery-init.js wlx_view_votes, `view`):
    * the vote chip of the juror's own tiles (showVote), with the vote's time in an order by it,
    * or null for an unrated file. */
  function addVoteMark(tile, item, view) {
    if (!view || !view.binary) return null;
    showVote(tile, +item.rate || 0);
    return tile.querySelector('.wlx-chip-vote');
  }

  /** The file's comments in the contest on its tile (gallery.scala.html's data-comments and
    * -comments-label), the comment chip (snippets.commentChip, as the other thumbnails, the large
    * view and the comments list have it): a speech bubble and the number, hidden from screen
    * readers; none for a file without them. The
    * last of the tile's chips, after the vote or the tally, or alone ([[chips]]), and the one
    * chip that is a link (the maintainer, 2026-11-15): to the file's large view at its comments
    * section (the tile's own data-largeUrl, its list kept, and #comments). Screen readers and its
    * title say where it goes, "3 comments: open the comments" (the page's data-comments-open, "{0}"
    * the words in the page's plural form; the words alone without it). A click on it is its own:
    * it never reaches the tile, whose click opens the large view at the top; Enter follows it as
    * any link. */
  function addComments(tile, item) {
    var count = parseInt(item.comments, 10);
    if (!(count > 0)) return null;
    var label = item.commentsLabel || String(count);
    var holder = document.querySelector('[data-csrf-token]');
    var pattern = holder && holder.getAttribute('data-comments-open');
    var name = item.largeUrl ? (pattern ? pattern.replace('{0}', label) : label) : label;
    var badge = chipFrom(item.largeUrl ? 'comments-link' : 'comments');
    if (!badge) return null;
    if (item.largeUrl) {
      badge.setAttribute('href', item.largeUrl + '#comments');
      badge.addEventListener('click', function (e) { e.stopPropagation(); });
    }
    badge.setAttribute('title', name);
    badge.querySelector('.wlx-chip-shown').textContent = String(count);
    badge.querySelector('.visually-hidden').textContent = name;
    return place(tile, badge, false);
  }

  window.wlxTileVotes = {
    voteUrl: voteUrl, send: send, explain: explain, addVote: addVote, vote: vote, addStars: addStars,
    addListStars: addListStars, addTally: addTally, addVoteTime: addVoteTime,
    voteTimeAfter: voteTimeAfter, addVoteMark: addVoteMark, addComments: addComments
  };

  // the by-rate list's stars, once the page is there (raty loaded only where it has them)
  document.addEventListener('DOMContentLoaded', function () {
    var list = document.querySelector('[data-stars-number]');
    if (list && window.jQuery && window.jQuery.fn.raty) addListStars(list);
  });

  if (typeof module !== 'undefined') module.exports = window.wlxTileVotes;
})();
