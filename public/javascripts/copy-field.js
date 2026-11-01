// A button that copies a field's text (data-copy-target: the field's id), e.g. the profile's
// new API token (apiTokens.scala.html): selects the text, puts it on the clipboard, and says
// so on the button (data-copied-text) until the next copy. Without the Clipboard API (a page
// over plain HTTP) the text stays selected for the user to copy.
(function () {
  'use strict';

  /** Copies the target's value; resolves to whether the clipboard took it. */
  function copyField(button, clipboard) {
    var field = document.getElementById(button.getAttribute('data-copy-target'));
    if (!field) return Promise.resolve(false);
    field.focus();
    field.select();
    if (!clipboard || !clipboard.writeText) return Promise.resolve(false);
    return clipboard.writeText(field.value).then(function () {
      var label = button.querySelector('span') || button;
      var copied = button.getAttribute('data-copied-text');
      if (copied) label.textContent = copied;
      return true;
    }, function () { return false; });
  }

  document.addEventListener('click', function (e) {
    var button = e.target && e.target.closest ? e.target.closest('[data-copy-target]') : null;
    if (button) copyField(button, navigator.clipboard);
  });

  if (typeof module !== 'undefined') module.exports = { copyField: copyField };
})();
