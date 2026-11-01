// The login page's colour mode switch (login.scala.html, signed out): a button per mode
// (data-theme-value: light, dark or auto) sets the theme cookie in the browser and the page's
// mode at once, with no request. Signed in, the navbar's switch is a POST instead
// (UserController.setTheme), which also stores the mode for the account. For auto, the
// layout's first script follows the browser's prefers-color-scheme while data-theme-pref is
// "auto"; this one sets the mode once when switching to it.
(function () {
  'use strict';

  var MODES = ['light', 'dark', 'auto'];
  var MAX_AGE = 365 * 24 * 60 * 60;

  /** One of the three modes, else dark, the default (as controllers.Theme reads the cookie). */
  function parse(value) {
    return MODES.indexOf(value) >= 0 ? value : 'dark';
  }

  /** The cookie for `mode`: a year, the whole site, Lax; Secure on an https page. */
  function cookieFor(mode, secure) {
    return 'theme=' + parse(mode) + '; Path=/; Max-Age=' + MAX_AGE + '; SameSite=Lax' + (secure ? '; Secure' : '');
  }

  /** Sets the page's mode: data-theme-pref as chosen, data-bs-theme light or dark (for auto,
    * the browser's scheme by `prefersDark`). */
  function apply(root, mode, prefersDark) {
    mode = parse(mode);
    root.setAttribute('data-theme-pref', mode);
    root.setAttribute('data-bs-theme', mode === 'auto' ? (prefersDark ? 'dark' : 'light') : mode);
  }

  /** Marks the switch's button for `mode` pressed, the others not. */
  function mark(group, mode) {
    var buttons = group.querySelectorAll('[data-theme-value]');
    for (var i = 0; i < buttons.length; i++) {
      var on = buttons[i].getAttribute('data-theme-value') === mode;
      buttons[i].classList.toggle('active', on);
      buttons[i].setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function prefersDark() {
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  /** Wires the switch with id theme-switch, if the page has it. */
  function init(doc) {
    var group = doc.getElementById('theme-switch');
    if (!group || group.tagName === 'FORM') return;
    group.addEventListener('click', function (e) {
      var button = e.target.closest ? e.target.closest('[data-theme-value]') : null;
      if (!button || !group.contains(button)) return;
      var mode = parse(button.getAttribute('data-theme-value'));
      doc.cookie = cookieFor(mode, window.location.protocol === 'https:');
      apply(doc.documentElement, mode, prefersDark());
      mark(group, mode);
    });
  }

  init(document);

  if (typeof module !== 'undefined') module.exports = { parse: parse, cookieFor: cookieFor, apply: apply, mark: mark, init: init };
})();
