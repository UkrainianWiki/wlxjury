// The confirm of a page that shows what an action does before it is done: adding a juror to a running
// round and reassigning a juror's files (round/addJuror.scala.html, round/reassignJuror.scala.html).
// Its form (data-preview-form = the id of the form that chose, a GET) posts the choice the figures were
// shown for, not the boxes as they are now. While the choice differs from the one the page was rendered
// with (the boxes' and options' default state), its submit buttons are disabled and its hint
// (data-preview-hint: a status, always in the page, empty) gets its text (data-stale-text) and
// describes them (aria-describedby, only then): "Show what this does" first. A status filled in is
// announced; one shown from hidden may not be. The server checks the posted choice again
// either way. A page brought back from the back-forward cache, its boxes as left, is checked again.
(function () {
  'use strict';

  /** The option a select was rendered with: the one marked selected, else its first. */
  function renderedIndex(select) {
    for (var i = 0; i < select.options.length; i++) {
      if (select.options[i].defaultSelected) return i;
    }
    return select.options.length > 0 ? 0 : -1;
  }

  /** Whether the choose form's boxes or selects differ from the choice the page was rendered with. */
  function stale(choose) {
    var boxes = choose.querySelectorAll('input[type="checkbox"], input[type="radio"]');
    for (var i = 0; i < boxes.length; i++) {
      if (boxes[i].checked !== boxes[i].defaultChecked) return true;
    }
    var selects = choose.querySelectorAll('select');
    for (var j = 0; j < selects.length; j++) {
      if (selects[j].selectedIndex !== renderedIndex(selects[j])) return true;
    }
    return false;
  }

  /** Disables `confirm`'s submit buttons and shows its hint while its choose form is stale; returns
    * whether it is. */
  function sync(confirm, doc) {
    var choose = doc.getElementById(confirm.getAttribute('data-preview-form') || '');
    if (!choose) return false;
    var isStale = stale(choose);
    var hint = doc.getElementById(confirm.getAttribute('data-preview-hint') || '');
    confirm.querySelectorAll('button[type="submit"], input[type="submit"]').forEach(function (b) {
      b.disabled = isStale;
      // the hint describes the button only while it applies: a fresh button announces nothing more
      if (hint && isStale) b.setAttribute('aria-describedby', hint.id);
      else b.removeAttribute('aria-describedby');
    });
    if (hint) hint.textContent = isStale ? (hint.getAttribute('data-stale-text') || '') : '';
    return isStale;
  }

  /** Wires the page's confirm forms: checked now, on every change of their choose form, and on submit. */
  function init(doc) {
    doc.querySelectorAll('form[data-preview-form]').forEach(function (confirm) {
      var choose = doc.getElementById(confirm.getAttribute('data-preview-form') || '');
      if (!choose) return;
      choose.addEventListener('change', function () { sync(confirm, doc); });
      // a submit that skips the disabled button is dropped too
      confirm.addEventListener('submit', function (e) { if (sync(confirm, doc)) e.preventDefault(); });
      sync(confirm, doc);
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { init(document); });
  else init(document);
  // after submit-once.js's reset of the buttons on a page restored by Back (registered before this one)
  window.addEventListener('pageshow', function (e) {
    if (e.persisted) document.querySelectorAll('form[data-preview-form]').forEach(function (f) { sync(f, document); });
  });

  if (typeof module !== 'undefined') module.exports = { stale: stale, sync: sync, init: init };
})();
