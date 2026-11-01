// The large views' collapsible side sections (large/sideSection.scala.html): the file's details,
// open by default, and the files after it, closed. Bootstrap's collapse shows and hides them;
// this keeps each one's state per viewer in the browser (localStorage) and applies it on the
// next large view. The column's last script calls restore() as soon as the column is parsed,
// before it is painted, so that a section the viewer changed doesn't show in its default state
// first (as the layout's first script sets the colour mode before any stylesheet). Without
// storage (blocked, a private window, full) a section keeps the state the page renders.
(function () {
  'use strict';

  var PREFIX = 'wlx.large.section.';

  /** The stored state of section `id`: "open", "closed", or null for none or no storage. */
  function read(id) {
    try {
      var value = window.localStorage.getItem(PREFIX + id);
      return value === 'open' || value === 'closed' ? value : null;
    } catch (e) {
      return null;
    }
  }

  function write(id, open) {
    try {
      window.localStorage.setItem(PREFIX + id, open ? 'open' : 'closed');
    } catch (e) {
      // not kept: the next view shows the default
    }
  }

  /** Opens or closes a section, and marks its toggle, as Bootstrap's collapse does. */
  function set(toggle, panel, open) {
    panel.classList.toggle('show', open);
    toggle.classList.toggle('collapsed', !open);
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  /** Space and Enter on a focused toggle open or close its section, and don't reach the page's
    * keys (keyboardNav.scala.html), where Space votes in a binary round. Digits and arrows do:
    * the toggle has no use for them. */
  function keepKey(e) {
    if (e.key === ' ' || e.key === 'Enter' || e.keyCode === 32 || e.keyCode === 13) e.stopPropagation();
  }

  /** Clicked with the mouse, a toggle gives up its focus, so that Space votes again. */
  function blurOnMouse(e) {
    if (e.detail > 0) e.currentTarget.blur();
  }

  /** Applies each section's stored state, and stores its changes from now on. */
  function restore(doc) {
    var toggles = doc.querySelectorAll('[data-large-section]');
    for (var i = 0; i < toggles.length; i++) {
      var toggle = toggles[i], id = toggle.getAttribute('data-large-section');
      var panel = doc.getElementById(id);
      if (!panel || toggle.hasAttribute('data-large-section-ready')) continue;
      toggle.setAttribute('data-large-section-ready', '');
      var stored = read(id);
      if (stored) set(toggle, panel, stored === 'open');
      // as it starts: a vote right after it, which leaves the page, keeps it
      panel.addEventListener('show.bs.collapse', function (e) { if (e.target === this) write(this.id, true); });
      panel.addEventListener('hide.bs.collapse', function (e) { if (e.target === this) write(this.id, false); });
      toggle.addEventListener('keydown', keepKey);
      toggle.addEventListener('click', blurOnMouse);
    }
  }

  window.wlxLargeSections = { restore: restore };

  if (typeof module !== 'undefined') module.exports = { PREFIX: PREFIX, read: read, write: write, restore: restore };
})();
