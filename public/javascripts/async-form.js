// A form posted by script, so that the page neither reloads nor moves (the local image cache's
// "Download images locally": snippets/imageCacheForm.scala.html). On a form[data-async-form], a
// submit is sent with fetch, its fields as the body, the CSRF token in Play's Csrf-Token header
// (the form's data-csrf-token) and X-Requested-With, which makes the server answer as to a script
// (Secured.isScriptRequest: JSON, no redirect). On success the element whose id the form's
// data-async-refresh names gets a 'wlx:refresh' event (cache-status.js updates it at once and polls
// while the job runs). On a failure the form's [data-async-error] element says why, in the page's
// language (its data-async-* texts):
//   401 {error: "unauthenticated"}           signed out
//   403 {error: "csrf", reference}           the CSRF filter (CsrfErrorHandler): a stale page
//   403 {error: "forbidden", reference}      a denial (Secured.onUnAuthorizedJson), its reference
//   404 {error: "round_not_found"}            what the form acts on is gone (another contest's round):
//                                            as a denial, reload the page
//   anything else, or no answer             a generic failure, with the reference if any.
// Without script the form posts as any other.
(function () {
  'use strict';

  function texts(form) {
    function text(name, fallback) {
      return form.getAttribute('data-async-' + name) || fallback;
    }
    return {
      signedOut: text('signed-out', 'You have been signed out. Sign in again and reload the page.'),
      expired: text('expired', 'This page has expired. Reload it and try again.'),
      forbidden: text('forbidden', 'You may no longer do this here. Reload the page.'),
      failed: text('failed', 'This could not be done. Try again.'),
      reference: text('reference', 'Reference: {0}')
    };
  }

  /** What to say for the answer's status and JSON body (null if it has none). */
  function errorMessage(status, body, t) {
    var error = body && typeof body === 'object' ? body.error : undefined;
    var reference = body && typeof body === 'object' && body.reference ? String(body.reference) : '';
    var withReference = function (text) {
      return reference ? text + ' ' + t.reference.replace('{0}', reference) : text;
    };
    if (status === 401) return t.signedOut;
    // the filter's CSRF failure, also when the body is not JSON; a denial has error "forbidden"
    if (status === 403 && error !== 'forbidden') return withReference(t.expired);
    if (status === 403 || status === 404) return withReference(t.forbidden);
    return withReference(t.failed);
  }

  function showError(form, text) {
    var el = form.querySelector('[data-async-error]');
    if (!el) return;
    el.textContent = text || '';
    el.hidden = !text;
  }

  function body(form) {
    var params = new URLSearchParams();
    var data = new FormData(form);
    data.forEach(function (value, name) {
      if (typeof value === 'string') params.append(name, value);
    });
    return params;
  }

  /** Posts the form; resolves to whether it succeeded. */
  function submit(form, doc) {
    var button = form.querySelector('[type="submit"]');
    if (button) button.disabled = true;
    showError(form, '');
    var t = texts(form);
    return fetch(form.action, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Csrf-Token': form.getAttribute('data-csrf-token') || '',
        'X-Requested-With': 'XMLHttpRequest',
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: body(form).toString()
    }).then(function (r) {
      if (r.ok) {
        var target = doc.getElementById(form.getAttribute('data-async-refresh') || '');
        if (target) target.dispatchEvent(new CustomEvent('wlx:refresh'));
        return true;
      }
      return r.json().catch(function () { return null; }).then(function (json) {
        showError(form, errorMessage(r.status, json, t));
        return false;
      });
    }, function () {
      showError(form, t.failed);
      return false;
    }).then(function (ok) {
      if (button) button.disabled = false;
      return ok;
    });
  }

  /** Sends each form[data-async-form] of `doc` by script. */
  function bind(doc) {
    doc.addEventListener('submit', function (e) {
      var form = e.target;
      if (!form || !form.hasAttribute || !form.hasAttribute('data-async-form')) return;
      e.preventDefault();
      submit(form, doc);
    });
  }

  window.wlxAsyncForm = { bind: bind, submit: submit, errorMessage: errorMessage, texts: texts };

  if (typeof module !== 'undefined') {
    module.exports = window.wlxAsyncForm;
  } else {
    bind(document);
  }
})();
