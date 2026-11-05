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
// its aria-* and keys. Any other value, or none, keeps the list in the field's wrapper.
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

    return new window.TomSelect(input, {
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
        no_results: function () { return null; }
      }
    });
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
