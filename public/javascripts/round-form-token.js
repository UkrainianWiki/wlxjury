// The round form's page (editRound.scala.html):
// - a new round's form (its `data-resolve-url`: the form's URL with its one-time token) puts that URL
//   in the page's history entry, also on a refused save's page (whose URL is the POST's). After the
//   form saved a round, Back or a reload asks the server for that URL, which opens the saved round's
//   Edit page ("Round N is saved. You can change its settings here."), never the stale form; before,
//   the form again with the same token (UI-path plan §5 Q6). The page is sent `Cache-Control:
//   no-store`; a browser that still restores it from its back/forward cache (`pageshow` with
//   `persisted`) reloads it, so the server answers all the same.
// - a refused save's error summary (#round-form-errors) is focused, so it is read and seen first.
(function () {
  'use strict';

  var resolve = null;

  window.addEventListener('pageshow', function (event) {
    if (event.persisted && resolve) window.location.reload();
  });

  function init() {
    var form = document.getElementById('round-form');
    resolve = form && form.getAttribute('data-resolve-url');

    if (resolve && window.history && typeof window.history.replaceState === 'function') {
      try {
        var target = new URL(resolve, window.location.href);
        if (target.origin === window.location.origin && target.href !== window.location.href) {
          window.history.replaceState(window.history.state, '', target.pathname + target.search);
        }
      } catch (e) {
        // an old browser without URL: the entry stays as it is
      }
    }

    var summary = document.getElementById('round-form-errors');
    if (summary) {
      summary.focus();
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
