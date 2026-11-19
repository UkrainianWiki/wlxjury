// Commons category suggestions on the forms' category fields (inputs with data-category-suggest):
// the round form's two categories, and the import sources on the contest files and contests
// pages. A Tom Select on the text input asks the Commons API, from the browser (CORS with
// origin=*, allowed by the CSP's connect-src), for the Category: titles that start with the
// typed text; choosing one stores the full title, as the server reads a category (with its
// prefix; Round.withFixedCategories adds it to a typed name, an import needs it). Anything
// typed is kept as typed (Enter, Tab or leaving the field), and when Commons fails or is slow
// the field works as plain text, without a message. data-category-suggest="category": a field
// that holds a category only, suggested for any text; "mixed": one that may also hold a page
// (an import source: a list page, a page with files), suggested only once the text starts
// with Category:. data-category-suggest-dropdown-parent="body": the list hangs from the page's
// body (Tom Select's dropdownParent), for a field inside a box that clips (a .table-responsive
// table's overflow would cut the list off); Tom Select positions it under the field and keeps
// its aria-* and keys, and this script places it again when that box scrolls (no window event
// then). Any other value, or none, keeps the list in the field's wrapper.
(function () {
  'use strict';

  var API = 'https://commons.wikimedia.org/w/api.php';
  var PREFIX = 'Category:';
  var MIN_LENGTH = 2;
  var LIMIT = 10;
  var DELAY_MS = 250;
  var TIMEOUT_MS = 5000;

  /** `text` without a leading Category: prefix (in any case) and the spaces around it. */
  function categoryName(text) {
    return String(text || '').trim().replace(/^category\s*:\s*/i, '');
  }

  /** Whether to ask Commons for `text` in a field of `mode` ("category" or "mixed"). */
  function shouldSuggest(text, mode) {
    var trimmed = String(text || '').trim();
    if (mode === 'mixed' && !/^category\s*:/i.test(trimmed)) return false;
    return categoryName(trimmed).length >= MIN_LENGTH;
  }

  /** The API request for the categories whose names start with `name`. */
  function searchUrl(name) {
    return API + '?' + new URLSearchParams({
      action: 'query', list: 'prefixsearch', psnamespace: '14', pslimit: String(LIMIT),
      pssearch: name, format: 'json', formatversion: '2', origin: '*'
    }).toString();
  }

  /** The Category: titles of an API answer (formatversion=2); none of anything else. */
  function titles(json) {
    var pages = json && json.query && json.query.prefixsearch;
    if (!Array.isArray(pages)) return [];
    return pages.map(function (page) { return page && page.title; })
      .filter(function (title) { return typeof title === 'string' && title.indexOf(PREFIX) === 0; });
  }

  /** Makes the text `input` a Tom Select suggesting categories; a readonly or disabled one
    * (a round that can no longer be edited) stays as it is, as does any without Tom Select. */
  function categorySuggest(input) {
    if (!input || !window.TomSelect || !window.fetch || input.readOnly || input.disabled) return null;
    var mode = input.getAttribute('data-category-suggest') === 'mixed' ? 'mixed' : 'category';
    var dropdownParent = input.getAttribute('data-category-suggest-dropdown-parent') === 'body' ? 'body' : null;
    var latest = 0; // the newest request's number: an answer to an older one is dropped
    var pending = null; // its AbortController

    var ts = new window.TomSelect(input, {
      maxItems: 1,
      create: true, // free text: what is typed is the value, unless a suggestion is chosen
      createOnBlur: true,
      persist: false, // a value typed, once replaced, is no suggestion
      addPrecedence: true, // Enter keeps the text typed, Down goes to the suggestions
      delimiter: '|', // never in a title, unlike the commas some categories have
      maxOptions: LIMIT,
      dropdownParent: dropdownParent,
      sortField: [{ field: '$order' }, { field: '$score' }], // Commons' order, not the best match first
      refreshThrottle: DELAY_MS, // the pause in typing before the list and a request follow
      loadThrottle: null, // no second delay on top of it
      plugins: ['restore_on_backspace'], // Backspace edits the value instead of dropping it
      onInitialize: function () { this.wrapper.classList.add('wlx-category-suggest'); },
      shouldLoad: function (query) { return shouldSuggest(query, mode); },
      load: function (query, callback) {
        var self = this;
        var number = ++latest;
        if (pending) pending.abort();
        var controller = window.AbortController ? new window.AbortController() : null;
        pending = controller;
        var timer = setTimeout(function () { if (controller) controller.abort(); }, TIMEOUT_MS);
        // the list shows the newest answer only, in Commons' order: the earlier suggestions go
        function done(options) {
          clearTimeout(timer);
          if (pending === controller) pending = null;
          if (number !== latest) return callback([]);
          self.clearOptions();
          callback(options);
        }
        window.fetch(searchUrl(categoryName(query)), {
          credentials: 'omit', signal: controller ? controller.signal : undefined
        })
          .then(function (response) {
            if (!response.ok) throw new Error('HTTP ' + response.status);
            return response.json();
          })
          .then(function (json) {
            done(titles(json).map(function (title) { return { value: title, text: title }; }));
          })
          .catch(function () { done([]); });
      },
      render: {
        // the text typed, first, while there are suggestions for it; no row otherwise
        option_create: function (data, escape) {
          return shouldSuggest(data.input, mode) ? '<div class="create">' + escape(data.input) + '</div>' : null;
        },
        no_results: function () { return null; },
        // the value, on one line (main.css: a long one cut with an ellipsis), whole in its title
        item: function (data, escape) {
          return '<div title="' + escape(data.text) + '">' + escape(data.text) + '</div>';
        }
      }
    });
    editOnFocus(ts);
    if (dropdownParent) {
      keepInWindow(ts);
      followScrollingBox(ts, input);
    }
    return ts;
  }

  /** A value the field already has (the page's, or one chosen) is an item, not text: a click
    * could neither select nor edit it (Tom Select keeps the mouse from its box), only Backspace
    * made it text again. On focus it becomes the text typed, wholly selected, so that typing
    * replaces it and the caret moves in it. The text is put in the box here: restore_on_backspace
    * does it only while the item's option is there, and persist: false drops the option of a value
    * typed with the item. Leaving or Enter keeps that text as the value (createOnBlur, create):
    * focus and blur unchanged keep it, a box cleared and left empties the field.
    * setSelectionRange rather than select(), which iOS Safari ignores.
    * This is done on the box's own focus event too: a click or a tap on the field focuses the box
    * through Tom Select's focus(), whose own focus handling (and 'focus' event) waits for a timer;
    * until then the item stayed and the box hid what was typed (Tom Select drops the keys while
    * the item is shown), so a busy page (a phone, CI) lost the first keys typed. Whichever comes
    * first does it, the other finds no item.
    * While the field is focused, the form's own input follows its text, silently (the item is
    * removed without a change event, and no event as it is typed): a send then (a tap on plain
    * text leaves the field focused on a phone), the browser's restore of the form (F5, Back) or a
    * blur Tom Select skips (the window without focus) finds the text as typed, never an old value
    * or none. A box cleared empties it (Enter then sends it empty), and leaving it cleared fires
    * one change; leaving it with text makes that the item, with Tom Select's change.
    * Leaving the field cancels the list's refresh still waiting for the pause in typing
    * (refreshThrottle, Tom Select's internal refreshTimeout, of the version javascript-test's
    * package.json pins with build.sbt's webjar): left within it (a quick Tab), the refresh would
    * open the list and focus the field again, which turned the value just kept back into text.
    * Without that property the refresh isn't cancelled: the field may be focused again, its value
    * in the box and still in the form's input, never lost. */
  function editOnFocus(ts) {
    var kept = false; // a value made text on focus, until the field is left
    function edit() {
      var box = ts.control_input;
      if (ts.isDisabled || ts.isReadOnly || !ts.items.length || box.value) return;
      var value = ts.items[0];
      var option = ts.options[value];
      var text = option ? option[ts.settings.labelField] : value;
      ts.removeItem(value, true);
      ts.input.value = value;
      kept = true;
      if (!box.value) ts.setTextboxValue(text);
      ts.inputState(); // the box shown and typed in at once, before Tom Select's own focus handling
      box.setSelectionRange(0, box.value.length);
    }
    ts.on('focus', edit);
    ts.control_input.addEventListener('focus', edit);
    ts.control_input.addEventListener('input', function () {
      if (kept && !ts.items.length) ts.input.value = ts.control_input.value.trim();
    });
    // left before Tom Select's own focus handling ran (its timer; its blur handling skips a field
    // it never saw focused): the text, as typed or kept, is the value again, an item, silently
    // (a cleared box empties the field, with one change), as Tom Select's blur would have done
    ts.control_input.addEventListener('blur', function () {
      if (!kept || ts.isFocused || ts.items.length) return;
      var text = ts.control_input.value.trim();
      kept = false;
      ts.setTextboxValue('');
      if (text) {
        if (!ts.options[text]) {
          var option = {};
          option[ts.settings.valueField] = text;
          option[ts.settings.labelField] = text;
          ts.addOption(option);
        }
        ts.addItem(text, true);
        ts.input.value = text;
      } else {
        ts.input.value = '';
        ts.input.dispatchEvent(new Event('change', { bubbles: true }));
      }
      ts.inputState();
    });
    ts.on('blur', function () {
      if (ts.refreshTimeout) {
        clearTimeout(ts.refreshTimeout);
        ts.refreshTimeout = null;
      }
      var cleared = kept && !ts.items.length;
      kept = false;
      if (!cleared) return;
      ts.input.value = '';
      ts.input.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }

  /** A list hung from the body is placed by Tom Select under its field, which may reach past
    * the window's right edge (a field partly out of view): wherever it is placed, its left edge
    * is moved back so that its right edge stays in the window, never widening the page. */
  function keepInWindow(ts) {
    var position = ts.positionDropdown;
    ts.positionDropdown = function () {
      var result = position.apply(ts, arguments);
      var width = document.documentElement.clientWidth;
      var rect = ts.dropdown.getBoundingClientRect();
      if (width > 0 && rect.right > width) {
        var scrollX = window.pageXOffset || 0;
        ts.dropdown.style.left = Math.max(scrollX, rect.left + scrollX - (rect.right - width)) + 'px';
      }
      return result;
    };
  }

  /** A list hung from the body follows its field when the window scrolls (Tom Select does
    * that), but not when a box around the field scrolls (a .table-responsive table, sideways on
    * a phone). There, when the list opens, the box first scrolls the field wholly into its view,
    * then the list is placed under it (a field wider than the box: its left edge at the box's);
    * on each scroll of the box an open list is placed again while the field is in view (wholly,
    * or for a field wider than the box, its left edge), and closed once it isn't, so that it
    * never reaches past the box or widens the page; until `ts` is destroyed. */
  function followScrollingBox(ts, input) {
    var box = input.closest ? input.closest('.table-responsive') : null;
    if (!box) return;
    // a pixel's slack for rounding
    function outside(field, visible) {
      return field.left < visible.left - 1 || field.right > visible.right + 1;
    }
    // what reveal() can reach: the whole field, or the left edge of one wider than the box
    function inView(field, visible) {
      var leftIn = field.left >= visible.left - 1 && field.left <= visible.right;
      if (field.right - field.left > visible.right - visible.left) return leftIn;
      return !outside(field, visible);
    }
    function reveal() {
      var field = ts.wrapper.getBoundingClientRect();
      var visible = box.getBoundingClientRect();
      if (!outside(field, visible)) return;
      // its left edge in view first; then its right edge too, if it fits
      var by = field.left < visible.left ? field.left - visible.left
        : Math.min(field.right - visible.right, field.left - visible.left);
      box.scrollLeft = box.scrollLeft + by;
      ts.positionDropdown();
    }
    function follow() {
      if (!ts.isOpen) return;
      if (inView(ts.wrapper.getBoundingClientRect(), box.getBoundingClientRect())) ts.positionDropdown();
      else ts.close();
    }
    ts.on('dropdown_open', reveal);
    box.addEventListener('scroll', follow, { passive: true });
    var destroy = ts.destroy;
    ts.destroy = function () {
      box.removeEventListener('scroll', follow);
      return destroy.apply(ts, arguments);
    };
  }

  function init() {
    Array.prototype.forEach.call(document.querySelectorAll('input[data-category-suggest]'), categorySuggest);
  }

  window.wlxCategorySuggest = categorySuggest;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      categoryName: categoryName, shouldSuggest: shouldSuggest, searchUrl: searchUrl,
      titles: titles, categorySuggest: categorySuggest, init: init
    };
  } else if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
