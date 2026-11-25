// A round form refused to wait for another round's job (round/edit/waitBox.scala.html, UI-path plan
// Q7): polls that job's status (GET data-src, JobController.status: a script request, which never
// switches the session's contest) and shows its progress. While it runs, the form's Save buttons
// are disabled; when it has finished, the box says so and Save is enabled again, the form as
// entered (no reload). Failed or cancelled: the box says what to do, Save enabled (the server
// decides). Signed out or no access: Save enabled, polling stops.
(function () {
  'use strict';

  var INTERVAL = 3000;

  function active(status) {
    return status === 'queued' || status === 'running';
  }

  function init() {
    var box = document.getElementById('round-form-wait');
    if (!box) return;
    var form = document.getElementById('round-form');
    var buttons = form ? Array.prototype.slice.call(form.querySelectorAll('button[type=submit]')) : [];
    var running = document.getElementById('round-form-wait-running');
    var done = document.getElementById('round-form-wait-done');
    var ended = document.getElementById('round-form-wait-ended');
    var progress = document.getElementById('round-form-wait-progress');
    var template = box.getAttribute('data-progress-text') || '{0} / {1} {2}';
    var lang = document.documentElement.lang || undefined;

    function save(enabled) {
      buttons.forEach(function (b) { b.disabled = !enabled; });
    }

    function count(n) {
      return Number(n).toLocaleString(lang);
    }

    function show(status) {
      box.setAttribute('data-status', status);
      running.hidden = !active(status);
      done.hidden = status !== 'done';
      ended.hidden = active(status) || status === 'done';
      if (status === 'done') {
        box.classList.remove('alert-warning');
        box.classList.add('alert-success');
      }
      save(!active(status));
    }

    function update() {
      fetch(box.getAttribute('data-src'),
        { credentials: 'same-origin', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(function (r) {
          if (!r.ok) return { denied: true };
          return r.json();
        })
        .then(function (p) {
          if (p.denied) {
            save(true);
            return;
          }
          if (progress && p.progressDone != null && p.progressTotal > 0) {
            progress.textContent = template.replace('{0}', count(p.progressDone))
              .replace('{1}', count(p.progressTotal)).replace('{2}', p.progressUnit || '');
          }
          show(p.status);
          if (active(p.status)) setTimeout(update, INTERVAL);
        })
        .catch(function () { setTimeout(update, INTERVAL * 2); });
    }

    show(box.getAttribute('data-status'));
    if (active(box.getAttribute('data-status'))) setTimeout(update, INTERVAL);
  }

  if (typeof module !== 'undefined') {
    // Jest (javascript-test/round-form-wait.test.js): init on its own document
    module.exports = { init: init, INTERVAL: INTERVAL };
  } else if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
