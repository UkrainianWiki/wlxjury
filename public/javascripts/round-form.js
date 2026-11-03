// The round form (editRound.scala.html), once the page is parsed:
// - its multiple selects (previous rounds, regions, jurors) as Tom Select dropdowns
//   (multi-select.js);
// - the previous rounds' filters: shown with a previous round chosen, a binary round's
//   selection filters (#selectFilters, which has a class "round<id>" for each binary round)
//   if any chosen round is binary, else the rates' filters (#rateFilters);
// - the distribution's choices, one per number of jurors a file goes to, from the jurors
//   chosen;
// - the conflicts of interest's confirmation, when saving a round with the box ticked would
//   take files out of it (data-coi-round-files, data-coi-confirm);
// - a new round's criteria (#round-criteria), hidden and disabled (so not sent) for a yes/no
//   or comments-only rating (#rates 1 or 0); an existing round's rating is fixed (its select
//   disabled), and its criteria stay as they are;
// - the jurors' table, loaded when its panel is first opened (#round-stat-table's data-src),
//   and the number of new files on request (#count-new-files' data-src): the edit page leaves
//   out its two expensive parts.
// Texts come from the page's <script type="application/json" id="round-form-texts">:
// { multiSelect: views.JsTexts.multiSelect, distribution: { all, some } }, "some" with $X for
// the number of jurors.
(function () {
  'use strict';

  var $ = window.jQuery;

  /** The page's texts, or empty ones. */
  function texts(doc) {
    var el = doc.getElementById('round-form-texts');
    try {
      return JSON.parse(el ? el.textContent : '{}');
    } catch (e) {
      return {};
    }
  }

  function initMultiSelects(doc, t) {
    if (!window.wlxMultiSelect) return;
    ['previousRound', 'regions', 'jurors'].forEach(function (id) {
      window.wlxMultiSelect(doc.getElementById(id), t.multiSelect || {});
    });
  }

  /** Shows the filters that fit the previous rounds chosen. */
  function previousRoundsChanged() {
    var vals = $(this).val() || [];

    $('#selectFilters, #rateFilters').hide();

    if (vals.length) {
      $('#prevRoundFilters').show();
      var anyBinary = vals.some(function (v) {
        return $('#selectFilters').hasClass('round' + v);
      });
      (anyBinary ? $('#selectFilters') : $('#rateFilters')).show();
    } else {
      $('#prevRoundFilters').hide();
    }
  }

  /** The distribution's choices for the jurors chosen: to all of them, or to 1 .. n-1. */
  function jurorsChanged(distributionTexts) {
    return function () {
      var distribution = $('#distribution');
      if (!distribution.length) return;
      var jurors = $(this).val();
      if (jurors && jurors.length) {
        distribution.prop('disabled', false);

        var selected = distribution.val();

        var opts = distribution[0].options;
        opts.length = 0;
        opts.add(new Option(distributionTexts.all, '0', false, false));

        for (var i = 1; i <= jurors.length - 1; i++) {
          opts[i] = new Option(String(distributionTexts.some).replace('$X', i), i, false, false);
        }

        if (selected <= jurors.length - 1) {
          distribution.val(selected);
        }
      } else {
        distribution.prop('disabled', true);
        distribution[0].options.length = 0;
        distribution[0].options.add(new Option(distributionTexts.all, '0', false, false));
      }
    };
  }

  /** Asks before a save that would take conflicts of interest out of the round. */
  function initCoiConfirm(doc) {
    var box = doc.querySelector('[data-coi-round-files]');
    if (!box || !box.form) return;
    box.form.addEventListener('submit', function (e) {
      if (box.checked && Number(box.getAttribute('data-coi-round-files')) > 0 &&
          !window.confirm(box.getAttribute('data-coi-confirm'))) e.preventDefault();
    });
  }

  /** Shows a new round's criteria for a rated rating only; disabled selects aren't sent. */
  function initCriteria(doc) {
    var rates = doc.getElementById('rates');
    var box = doc.getElementById('round-criteria');
    if (!rates || !box || rates.disabled) return;
    function update() {
      var rated = Number(rates.value) > 1;
      box.hidden = !rated;
      Array.prototype.forEach.call(box.querySelectorAll('select'), function (s) { s.disabled = !rated; });
    }
    rates.addEventListener('change', update);
    update();
  }

  function initLazyParts() {
    $('#collapseJurors').one('show.bs.collapse', function () {
      var el = $('#round-stat-table');
      // signed out (401) or denied (403): a short message, not the page that says so
      if (el.length) el.load(el.data('src'), function (response, status, xhr) {
        if (xhr.status === 401 || xhr.status === 403) el.text(el.data('error'));
      });
    });
    $('#count-new-files').on('click', function () {
      var b = $(this).prop('disabled', true);
      $('#new-files-count').text('…');
      $.getJSON(b.data('src'))
        .done(function (r) { $('#new-files-count').text(r.count); })
        .fail(function () { $('#new-files-count').text('?'); })
        .always(function () { b.prop('disabled', false); });
    });
  }

  function init(doc) {
    var t = texts(doc);
    initMultiSelects(doc, t);
    $('#previousRound').change(previousRoundsChanged);
    $('#jurors').change(jurorsChanged(t.distribution || {}));
    initCoiConfirm(doc);
    initCriteria(doc);
    initLazyParts();
  }

  if (typeof module !== 'undefined') {
    module.exports = { init: init, texts: texts };
  } else {
    $(function () { init(document); });
  }
})();
