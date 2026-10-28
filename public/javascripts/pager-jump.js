// The pager's page box (pageLinks.scala.html), in a dropdown: goes to the page typed, kept
// within the list's pages, by the list's page URL with {page} in place of the number
// (data-page-url); focuses the number when the dropdown opens.
(function () {
  'use strict';

  /** The URL of the page typed in the form, or null if it is not a number. */
  function pageUrl(form) {
    var input = form.querySelector('input[name=page]');
    var page = parseInt(input.value, 10);
    if (isNaN(page)) return null;
    var max = parseInt(input.getAttribute('max'), 10);
    page = Math.max(1, isNaN(max) ? page : Math.min(page, max));
    return form.getAttribute('data-page-url').replace('{page}', String(page));
  }

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form.classList || !form.classList.contains('pager-jump')) return;
    e.preventDefault();
    var url = pageUrl(form);
    if (url) window.location.assign(url);
  });

  if (window.jQuery) {
    window.jQuery(document).on('shown.bs.dropdown', '.pager-jump-menu', function () {
      var input = this.querySelector('input[name=page]');
      if (input) { input.focus(); input.select(); }
    });
  }

  if (typeof module !== 'undefined') module.exports = { pageUrl: pageUrl };
})();
