// A form sent once (data-submit-once), e.g. the profile's API token create form
// (apiTokens.scala.html): its first submit disables its submit buttons and any further submit is
// dropped, so a double click sends one request. The server still takes a repeated submit of the
// same form as one (ApiTokenService.createOnce). A page brought back from the back-forward cache
// gets its buttons back.
(function () {
  'use strict';

  /** Handles a submit of `form`: true if it may go, false (and the event cancelled) if repeated. */
  function onSubmit(form, event) {
    if (!form || !form.hasAttribute || !form.hasAttribute('data-submit-once')) return true;
    if (form.getAttribute('data-submitted') === 'true') {
      if (event) event.preventDefault();
      return false;
    }
    form.setAttribute('data-submitted', 'true');
    // disabled after the submit event: the request already holds the form's fields
    setTimeout(function () {
      form.querySelectorAll('button[type="submit"], input[type="submit"]').forEach(function (b) { b.disabled = true; });
    }, 0);
    return true;
  }

  /** Makes the page's once-only forms sendable again (a page restored by Back). */
  function reset(doc) {
    doc.querySelectorAll('form[data-submit-once]').forEach(function (form) {
      form.removeAttribute('data-submitted');
      form.querySelectorAll('button[type="submit"], input[type="submit"]').forEach(function (b) { b.disabled = false; });
    });
  }

  document.addEventListener('submit', function (e) { onSubmit(e.target, e); });
  window.addEventListener('pageshow', function (e) { if (e.persisted) reset(document); });

  if (typeof module !== 'undefined') module.exports = { onSubmit: onSubmit, reset: reset };
})();
