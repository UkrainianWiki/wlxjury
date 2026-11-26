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
(function () {
  'use strict';

  /** The form fields whose change changes the ranking, by name (a multiple select's "[]" ignored). */
  var RANKING_FIELDS = ['previousRound', 'regions', 'source', 'excludeCategory', 'categoryCombine',
    'categoryClause', 'mediaType', 'minSize', 'minMpx', 'excludeRounds', 'excludeCoi', 'minJurors',
    'rejectedBy', 'rejectedMode', 'topBinaryOrder', 'monumentIds', 'specialNomination'];

  /** The pause after a choice (a select, a box) and after typing, before the list is asked for. */
  var CHANGE_DELAY = 150;
  var TYPING_DELAY = 500;

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

    function render(html) {
      var active = doc.activeElement;
      var focusId = active && active.id && box.contains(active) ? active.id : null;
      box.innerHTML = html;
      box.removeAttribute('aria-busy');
      var root = box.firstElementChild;
      say(root ? root.getAttribute('data-cut-announce') || '' : '', false);
      updateCutLink(doc);
      if (focusId) {
        var again = doc.getElementById(focusId);
        if (again && box.contains(again)) again.focus();
      }
    }

    function fail(httpStatus, json) {
      box.removeAttribute('aria-busy');
      lastBody = null; // the same form may be asked for again
      var message;
      if (httpStatus === 401) message = text('signed-out');
      else if (json && json.error === 'csrf') message = text('csrf');
      else if (httpStatus === 409 && json && typeof json.message === 'string') message = json.message;
      else message = text('failed');
      say(message, true);
    }

    /** Asks for the list of the form as it is now; the answer to an older request is dropped. */
    function load() {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      var encoded = body(form);
      lastBody = encoded;
      seq += 1;
      var mine = seq;
      if (controller) controller.abort();
      controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
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

    /** Loads after `delay`, once the changes stop; nothing if the form is as last asked for, or if it
      * has no previous round while the box has no list to clear. */
    function schedule(delay) {
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () {
        timer = null;
        var encoded = body(form);
        if (encoded === lastBody) return;
        if (!hasPrevious(encoded) && !box.querySelector('select#topImages')) return;
        load();
      }, delay);
    }

    form.addEventListener('change', function (e) {
      var t = e.target;
      if (t && t.id === 'topImages') updateCutLink(doc);
      if (ranks(t)) schedule(changeDelay);
    });
    form.addEventListener('input', function (e) {
      var t = e.target;
      if (ranks(t) && typed(t)) schedule(typingDelay);
    });

    updateCutLink(doc);
    if (!wrap.hasAttribute('data-cut-loaded') && hasPrevious(body(form))) load();
    else if (wrap.hasAttribute('data-cut-loaded')) lastBody = body(form);

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
