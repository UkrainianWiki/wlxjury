// A form that asks before it is sent (data-confirm: the question, already in the page's language),
// e.g. "Stop the round" on a round's conflicts page (round/conflicts.scala.html). Cancelled, the
// submit is dropped. Without scripts the form is sent at once, as any form.
(function () {
  'use strict';

  /** Handles a submit of `form`: true if it may go, false (and the event cancelled) if declined. */
  function onSubmit(form, event, ask) {
    if (!form || !form.hasAttribute || !form.hasAttribute('data-confirm')) return true;
    var confirmFn = ask || window.confirm.bind(window);
    if (confirmFn(form.getAttribute('data-confirm'))) return true;
    if (event) event.preventDefault();
    return false;
  }

  document.addEventListener('submit', function (e) { onSubmit(e.target, e); });

  if (typeof module !== 'undefined') module.exports = { onSubmit: onSubmit };
})();
