// The round form's cut list, loaded in place (round/edit/topN; regional winners plan §10.4, decided
// 2026-11-26: no "Choose N" button, no page reload, no fallback without scripts):
// - the form is posted (fetch, its fields as the body, the Csrf-Token header from the
//   form's data-csrf-token, X-Requested-With so the server answers as to a script) to #top-n's data-cut-url
//   (RoundController.cutList), which answers with the cut box (round/edit/topNBox, an HTML fragment
//   rendered and translated by the server), put in #top-n-box (only a 2xx text/html answer);
// - when: as the form opens with a previous round chosen (editing, a re-apply, a form shown again),
//   unless the page came with the control (data-cut-loaded: a save that refused its cut); and, after
//   a short pause, when a field that ranks the files changes (RANKING_FIELDS: the previous rounds and
//   the filters of the rounds' files, the same fields the server's ranking reads). A form without a
//   previous round isn't posted while the box has no list to clear;
// - while loading, "Updating the cuts…" (data-cut-updating) in the polite live region #top-n-status;
//   then what the box says (its data-cut-announce: the number of cuts, or why there are none); focus is
//   never moved, except back to the same control when it was in the box being replaced;
// - only the latest request counts: an older one is aborted, and a response that arrives after a newer
//   request started is ignored (a sequence number), so a slow answer never overwrites a newer one;
// - a failure says so in the live region with the page's texts (signed out, an expired CSRF token, a
//   round whose filters can't change any more: the server's translated message; else a generic line),
//   never the response's raw text;
// - the cut's link to the previous round's rating view (#top-n-cut-link) follows the chosen cut (its
//   option's data-cut-href), hidden when none is chosen.
// - while the box says the form's errors keep the list from loading, any field's change reloads;
// - until the admin picks a cut, a refresh posts the cut the page held (its hidden top N and threshold,
//   or a refused save's control), so a held threshold or a legacy round's two cuts are held again
//   (preselected or explained), never turned into the count they took before; a cut picked while a
//   list loads is put back into the new list, or asked for again if the list doesn't have it;
// - while a list is pending or loading, the form's submit buttons are disabled (and a submit held,
//   saying "Updating the cuts…"); the save checks the cut again anyway.
(function () {
  'use strict';

  /** The form fields whose change changes the ranking, by name (a multiple select's "[]" ignored). */
  var RANKING_FIELDS = ['previousRound', 'regions', 'source', 'excludeCategory', 'categoryCombine',
    'categoryClause', 'mediaType', 'minSize', 'minMpx', 'excludeRounds', 'excludeCoi', 'minJurors',
    'rejectedBy', 'rejectedMode', 'topBinaryOrder', 'monumentIds', 'specialNomination'];

  /** The pause after a choice (a select, a box) and after typing, before the list is asked for. */
  var CHANGE_DELAY = 150;
  var TYPING_DELAY = 500;

  /** The cut's own fields: its control, a held threshold, and what the round keeps. */
  var CUT_FIELDS = ['topImages', 'minAvgRate', 'cutKeep'];

  /** The cut's fields of the form as the page came, by name. */
  function heldFields(form) {
    var held = {};
    CUT_FIELDS.forEach(function (name) { held[name] = []; });
    new FormData(form).forEach(function (value, key) {
      if (CUT_FIELDS.indexOf(key) >= 0 && typeof value === 'string') held[key].push(value);
    });
    return held;
  }

  /** A field's name without its "[]" or "[0]". */
  function baseName(name) {
    return String(name || '').replace(/\[\d*\]$/, '');
  }

  /** Whether a change of `el` changes the ranking. */
  function ranks(el) {
    return !!el && RANKING_FIELDS.indexOf(baseName(el.name)) >= 0;
  }

  /** Whether `el` is typed into (its `input` events are the typing; selects and boxes have `change`). */
  function typed(el) {
    return el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|hidden)$/i.test(el.type));
  }

  /** The form's fields as a URL-encoded body. */
  function body(form) {
    var params = new URLSearchParams();
    new FormData(form).forEach(function (value, key) {
      if (typeof value === 'string') params.append(key, value);
    });
    return params.toString();
  }

  /** Whether the body names a previous round. */
  function hasPrevious(encoded) {
    var found = false;
    new URLSearchParams(encoded).forEach(function (value, key) {
      if (baseName(key) === 'previousRound' && value.trim() !== '') found = true;
    });
    return found;
  }

  /** The cut's link follows the chosen cut: its option's data-cut-href, hidden without one. */
  function updateCutLink(doc) {
    var select = doc.getElementById('topImages');
    var link = doc.getElementById('top-n-cut-link');
    if (!select || !link || select.tagName !== 'SELECT') return;
    var option = select.options[select.selectedIndex];
    var href = option ? option.getAttribute('data-cut-href') : null;
    link.setAttribute('href', href || '#');
    link.hidden = !href;
  }

  /**
    * The cut list of the page's form: loads it as described above. `opts.fetch` (window.fetch by
    * default) and the delays may be given for tests. Returns { load, schedule } or null without a
    * cut box to fill.
    */
  function create(doc, opts) {
    opts = opts || {};
    var wrap = doc.getElementById('top-n');
    var box = doc.getElementById('top-n-box');
    var status = doc.getElementById('top-n-status');
    var form = wrap && wrap.closest ? wrap.closest('form') : null;
    var url = wrap && wrap.getAttribute('data-cut-url');
    var fetchFn = opts.fetch || (typeof window !== 'undefined' && window.fetch ? window.fetch.bind(window) : null);
    if (!wrap || !box || !status || !form || !url || !fetchFn) return null;
    var changeDelay = opts.changeDelay != null ? opts.changeDelay : CHANGE_DELAY;
    var typingDelay = opts.typingDelay != null ? opts.typingDelay : TYPING_DELAY;
    var seq = 0;
    var controller = null;
    var timer = null;
    var lastBody = null;
    var loading = false;
    // the cut as the page held it (its hidden fields, or a refused save's control): posted again by an
    // automatic refresh until the admin picks a cut, so a held threshold or a legacy round's two cuts
    // are held again (preselected or explained), never turned into the count they took before
    var held = heldFields(form);
    var touched = false;
    // a cut picked while a list was loading: put back into the list that answer brings
    var pendingPick = null;

    function text(name) {
      return wrap.getAttribute('data-cut-' + name) || '';
    }

    function csrfToken() {
      var token = form.getAttribute('data-csrf-token');
      if (token) return token;
      var field = form.querySelector('input[name="csrfToken"]');
      return field ? field.value : '';
    }

    function say(message, failed) {
      status.textContent = message;
      status.classList.toggle('text-danger', !!failed);
    }

    function busy() {
      return loading || timer !== null;
    }

    /** The form's submit buttons, disabled while a list is pending or loading (the cut shown may be about
      * to change); only those this script disabled are enabled again. */
    function guardSave(on) {
      Array.prototype.forEach.call(form.querySelectorAll('button[type="submit"], input[type="submit"]'), function (b) {
        if (on && !b.disabled) {
          b.disabled = true;
          b.setAttribute('data-cut-busy', '');
        } else if (!on && b.hasAttribute('data-cut-busy')) {
          b.disabled = false;
          b.removeAttribute('data-cut-busy');
        }
      });
    }

    /** The form's body; until the admin picks a cut, with the cut the page held. */
    function requestBody(overrides) {
      var params = new URLSearchParams(body(form));
      var replace = overrides || (touched ? null : held);
      if (replace) {
        CUT_FIELDS.forEach(function (name) { params.delete(name); });
        CUT_FIELDS.forEach(function (name) {
          (replace[name] || []).forEach(function (value) { params.append(name, value); });
        });
      }
      return params.toString();
    }

    /** The cut chosen in the box now: its control's value and what the round keeps. */
    function currentPick() {
      var select = box.querySelector('select#topImages');
      var keep = box.querySelector('input[name="cutKeep"]:checked');
      return { topImages: select ? [select.value] : [], minAvgRate: [], cutKeep: keep ? [keep.value] : [] };
    }

    /** Puts a cut picked during the load into the new list, if it has it; true if it did. */
    function reapply(pick) {
      var select = box.querySelector('select#topImages');
      var value = pick.topImages[0];
      if (!select || value === undefined) return false;
      var found = Array.prototype.some.call(select.options, function (o) { return o.value === value; });
      if (!found) return false;
      select.value = value;
      var keepValue = pick.cutKeep[0];
      Array.prototype.forEach.call(box.querySelectorAll('input[name="cutKeep"]'), function (r) {
        if (keepValue !== undefined) r.checked = r.value === keepValue;
      });
      return true;
    }

    function render(html) {
      var active = doc.activeElement;
      var focusId = active && active.id && box.contains(active) ? active.id : null;
      box.innerHTML = html;
      var pick = pendingPick;
      pendingPick = null;
      if (pick && !reapply(pick)) {
        // the new list doesn't have it: asked again with it, so the server explains (or refuses) it
        load(pick);
        return;
      }
      loading = false;
      box.removeAttribute('aria-busy');
      guardSave(busy());
      var root = box.firstElementChild;
      say(root ? root.getAttribute('data-cut-announce') || '' : '', false);
      updateCutLink(doc);
      lastBody = requestBody();
      if (focusId) {
        var again = doc.getElementById(focusId);
        if (again && box.contains(again)) again.focus();
      }
    }

    function fail(httpStatus, json) {
      loading = false;
      pendingPick = null;
      box.removeAttribute('aria-busy');
      guardSave(busy());
      lastBody = null; // the same form may be asked for again
      var message;
      if (httpStatus === 401) message = text('signed-out');
      else if (json && json.error === 'csrf') message = text('csrf');
      else if (httpStatus === 409 && json && typeof json.message === 'string') message = json.message;
      else message = text('failed');
      say(message, true);
    }

    /** Asks for the list of the form as it is now (the cut's fields from `overrides`, if given); the
      * answer to an older request is dropped. */
    function load(overrides) {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      var encoded = requestBody(overrides);
      lastBody = encoded;
      seq += 1;
      var mine = seq;
      if (controller) controller.abort();
      controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
      loading = true;
      guardSave(true);
      say(text('updating'), false);
      box.setAttribute('aria-busy', 'true');
      var init = {
        method: 'POST',
        body: encoded,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
          'Csrf-Token': csrfToken(),
          'X-Requested-With': 'XMLHttpRequest',
          Accept: 'text/html'
        }
      };
      if (controller) init.signal = controller.signal;
      var request;
      try {
        request = Promise.resolve(fetchFn(url, init));
      } catch (e) {
        request = Promise.reject(e);
      }
      return request
        .then(function (response) {
          if (mine !== seq) return null;
          var type = (response.headers && response.headers.get && response.headers.get('Content-Type')) || '';
          if (response.ok && /^text\/html/i.test(type)) {
            return response.text().then(function (html) { if (mine === seq) render(html); });
          }
          return response.json().catch(function () { return {}; })
            .then(function (json) { if (mine === seq) fail(response.status, json); });
        })
        .catch(function (e) {
          if (mine !== seq || (e && e.name === 'AbortError')) return;
          fail(0, null);
        });
    }

    /** Whether the box has nothing to clear: no list, no error or explanation (its announcement is
      * empty), and no failure said. */
    function plainBox() {
      var root = box.firstElementChild;
      return !box.querySelector('select#topImages') && !(root && root.getAttribute('data-cut-announce')) &&
        !status.classList.contains('text-danger');
    }

    /** Loads after `delay`, once the changes stop; nothing if the form is as last asked for, or if it
      * has no previous round while the box has nothing to clear. */
    function schedule(delay) {
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () {
        timer = null;
        var encoded = requestBody();
        if (encoded === lastBody || (!hasPrevious(encoded) && plainBox())) {
          guardSave(busy());
          return;
        }
        load();
      }, delay);
      guardSave(true);
    }

    /** While the box says the form's errors keep the list from loading, any field outside it counts. */
    function formInvalid() {
      return !!box.querySelector('#top-n-form-invalid');
    }

    form.addEventListener('change', function (e) {
      var t = e.target;
      if (t && box.contains(t)) {
        if (t.id === 'topImages') updateCutLink(doc);
        if (t.id === 'topImages' || t.name === 'cutKeep') {
          touched = true;
          if (loading) pendingPick = currentPick();
        }
        return;
      }
      if (ranks(t) || formInvalid()) schedule(changeDelay);
    });
    form.addEventListener('input', function (e) {
      var t = e.target;
      if (t && box.contains(t)) return;
      if ((ranks(t) || formInvalid()) && typed(t)) schedule(typingDelay);
    });
    // a save while the list is pending or loading (Enter in a field, a button not disabled): held until
    // the list is in, saying so; the save itself checks the cut again
    form.addEventListener('submit', function (e) {
      if (!busy()) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      say(text('updating'), false);
    }, true);

    // a page restored from the back/forward cache: no load of it is running, so its Save is never left
    // disabled (the list it shows is the one it had)
    var view = doc.defaultView;
    if (view) {
      view.addEventListener('pageshow', function (e) {
        if (!e.persisted) return;
        if (timer) clearTimeout(timer);
        timer = null;
        loading = false;
        box.removeAttribute('aria-busy');
        guardSave(false);
      });
    }

    updateCutLink(doc);
    if (!wrap.hasAttribute('data-cut-loaded') && hasPrevious(body(form))) load();
    else lastBody = requestBody();

    return { load: load, schedule: schedule };
  }

  if (typeof module !== 'undefined') {
    module.exports = { create: create, ranks: ranks, baseName: baseName, hasPrevious: hasPrevious,
      updateCutLink: updateCutLink, RANKING_FIELDS: RANKING_FIELDS };
  } else if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { create(document); });
  } else {
    create(document);
  }
})();
