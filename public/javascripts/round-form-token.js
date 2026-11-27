// The round form's page (editRound.scala.html):
// - the form puts its canonical GET URL in the page's history entry, including after a refused
//   save whose response came from a POST. For a new round, the URL includes its one-time token. After the
//   form saved a round, Back or a reload asks the server for that URL, which opens the saved round's
//   Edit page ("Round N is saved. You can change its settings here."), never the stale form; before,
//   the form again with the same token (UI-path plan §5 Q6). The page is sent `Cache-Control:
//   no-store`; a browser that still restores it from its back/forward cache (`pageshow` with
//   `persisted`) reloads it, so the server answers all the same.
// - a refused save's error summary (#round-form-errors) is focused, so it is read and seen first.
(function () {
  'use strict';

  function init() {
    var form = document.getElementById('round-form');
    var resolve = form && form.getAttribute('data-resolve-url');

    if (resolve) {
      // Draft recovery's pageshow handler runs first so this refresh can skip the warning.
      window.addEventListener('pageshow', function (event) {
        if (event.persisted) window.location.reload();
      });
      if (window.history && typeof window.history.replaceState === 'function') {
        try {
          var target = new URL(resolve, window.location.href);
          if (target.origin === window.location.origin && target.href !== window.location.href) {
            window.history.replaceState(window.history.state, '', target.pathname + target.search);
          }
        } catch (e) {
          // an old browser without URL: the entry stays as it is
        }
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
