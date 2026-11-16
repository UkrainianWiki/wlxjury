// The Edit user form's panel when a save is refused because other accounts have the wiki account
// typed (editUser.scala.html, accounts plan A-2). "Keep them separate" puts the account's wiki
// account as stored back into this page's form (its data-wiki-account), keeping the other fields
// typed, clears the field's error and hides the panel; the form is saved only with Save. Without
// the script the link opens the edit page as stored.
(function () {
  'use strict';

  /** Puts the stored wiki account back and hides the panel: true if the page has both. */
  function keepSeparate(root) {
    var panel = root.querySelector('#wiki-taken');
    var keep = root.querySelector('#wiki-taken-keep');
    var field = root.querySelector('#wikiAccount');
    if (!panel || !keep || !field) return false;
    field.value = keep.getAttribute('data-wiki-account') || '';
    field.classList.remove('is-invalid');
    field.removeAttribute('aria-invalid');
    // the field's error goes, and its id from the field's description
    var group = root.querySelector('#wikiAccount_field') || field.parentNode;
    var errors = group.querySelectorAll('.invalid-feedback');
    var gone = [];
    for (var i = 0; i < errors.length; i++) {
      gone.push(errors[i].id);
      errors[i].remove();
    }
    var described = (field.getAttribute('aria-describedby') || '').split(/\s+/)
      .filter(function (id) { return id && gone.indexOf(id) < 0; });
    if (described.length) field.setAttribute('aria-describedby', described.join(' '));
    else field.removeAttribute('aria-describedby');
    panel.hidden = true;
    field.focus();
    return true;
  }

  function init(root) {
    var keep = root.querySelector('#wiki-taken-keep');
    if (!keep) return null;
    keep.addEventListener('click', function (e) {
      if (keepSeparate(root)) e.preventDefault();
    });
    return keep;
  }

  if (typeof document !== 'undefined') {
    document.addEventListener('DOMContentLoaded', function () { init(document); });
  }

  if (typeof module !== 'undefined') module.exports = { keepSeparate: keepSeparate, init: init };
})();
