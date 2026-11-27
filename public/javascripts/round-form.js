// The round form (editRound.scala.html), once the page is parsed:
// - its multiple selects (previous rounds, rounds to leave out, regions, jurors) as Tom Select dropdowns
//   (multi-select.js), a locked one left as rendered (its selected options as text);
// - the previous rounds' filters: shown with a previous round chosen, a binary round's
//   selection filters (#selectFilters, which has a class "round<id>" for each binary round)
//   if any chosen round is binary (a rated round's threshold is the cut control's, #top-n);
// - the distribution's choices, one per number of jurors a file goes to, from the jurors
//   chosen;
// - the conflicts of interest's confirmation, when saving a round with the box ticked would
//   take files out of it (data-coi-round-files, data-coi-confirm);
// - a new round's criteria (#round-criteria), hidden and disabled (so not sent) for a yes/no
//   or comments-only rating (#rates 1 or 0); an existing round's rating is fixed (its select
//   disabled), and its criteria stay as they are; an existing yes/no or comments-only round's
//   page has neither the criteria nor the worst mark (editRound.scala.html); its selects start
//   small: the chosen ones, each with a remove button, and "Add criteria" / "Add another
//   criterion" (initCriteriaSlots);
// - the worst mark's checkbox (#round-worst-mark), hidden and disabled (so not sent) for a
//   yes/no or comments-only rating; "rejected by at least" (#rejectFilters, classes "round<id>"
//   of the binary rounds and the rated ones with the worst mark) shown when one is chosen;
// - the score rule's warning (#averageRule_warning_row, rendered for a rated round with a vote
//   of stars and shown as it is without scripts): shown only while #averageRule differs from the
//   saved rule (its data-stored-rule);
// - the jurors' table, loaded when its panel is first opened (#round-stat-table's data-src),
//   and the number of new files on request (#count-new-files' data-src): the edit page leaves
//   out its two expensive parts;
// - Space on a card's header toggle (snippets/collapsible.scala.html, an a[role=button] for
//   Bootstrap's collapse) opens or closes it as on a button, instead of scrolling the page;
//   Enter already follows the link (initCardToggleKeys).
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

  /** The multiple selects as Tom Selects, except a locked one (data-wlx-locked: hidden, its
    * selected options shown as text next to it, bs.select's `_locked`). */
  function initMultiSelects(doc, t) {
    if (!window.wlxMultiSelect) return;
    ['previousRound', 'excludeRounds', 'regions', 'jurors'].forEach(function (id) {
      var select = doc.getElementById(id);
      if (select && select.hasAttribute('data-wlx-locked')) return;
      window.wlxMultiSelect(select, t.multiSelect || {});
    });
  }

  /** Shows the filters that fit the previous rounds chosen. */
  function previousRoundsChanged() {
    var vals = $(this).val() || [];
    var binary = anySource(vals, '#selectFilters');
    var rejecting = anySource(vals, '#rejectFilters');
    $('#prevRoundFilters').toggle(vals.length > 0);
    $('#selectFilters').toggle(binary);
    $('#rejectFilters').toggle(rejecting);
    voteFilterSent('#minJurors_field', binary);
    voteFilterSent('#rejectFilters', rejecting);
  }

  /** Whether a chosen previous round supports the filter. */
  function anySource(vals, group) {
    return vals.some(function (v) { return $(group).hasClass('round' + v); });
  }

  /** A vote filter is sent only with a supporting source. Locked selects post their hidden value. */
  function voteFilterSent(group, on) {
    $(group).find('select').not('[data-wlx-locked]').prop('disabled', !on);
    $(group).find('input[type=hidden]').prop('disabled', !on);
  }

  /** Preserve fields rendered read only, then use the current sources to decide what to send. */
  function initVoteFilters() {
    $('#minJurors, #rejectFilters select').each(function () {
      if (this.disabled) this.setAttribute('data-wlx-locked', '');
    });
    var vals = $('#previousRound').val() || [];
    voteFilterSent('#minJurors_field', anySource(vals, '#selectFilters'));
    voteFilterSent('#rejectFilters', anySource(vals, '#rejectFilters'));
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

  /** A criteria form's selects (#round-criteria-selects, all rendered for a page without
    * scripts) start small: only the chosen ones are shown, each with a remove button, then the
    * add button (#criteria-add, its row hidden as rendered): "Add criteria" with none chosen,
    * which shows one select, else "Add another criterion", up to the selects' number (10, or a
    * legacy round's more). A page shown again with the posted values (a refused save) shows the
    * selects up to the last chosen one (the criteria's errors are the form's, not a select's). A removal moves the next criteria up a place, so the
    * shown selects stay the first ones, and empties the last (an empty select isn't a
    * criterion); focus goes to the select now in the removed one's place, else the one before,
    * else the add button. Hidden selects stay empty, so what is sent is what is shown. */
  function initCriteriaSlots(doc) {
    var box = doc.getElementById('round-criteria-selects');
    var add = doc.getElementById('criteria-add');
    var addRow = doc.getElementById('criteria-add-row');
    if (!box || !add || !addRow) return;
    var selects = Array.prototype.slice.call(box.querySelectorAll('select'));
    if (!selects.length) return;
    var removeLabel = box.getAttribute('data-remove-label') || '';
    var wrappers = selects.map(function (s) { return doc.getElementById(s.id + '_field') || s.parentNode; });
    var shown = 0;
    selects.forEach(function (s, i) { if (s.value !== '') shown = i + 1; });

    function render() {
      wrappers.forEach(function (w, i) { w.hidden = i >= shown; });
      add.textContent = add.getAttribute(shown ? 'data-text-more' : 'data-text-first');
      addRow.hidden = shown >= selects.length;
    }

    function changed(s) {
      s.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function remove(i) {
      for (var j = i; j < shown - 1; j++) {
        selects[j].value = selects[j + 1].value;
        changed(selects[j]);
      }
      selects[shown - 1].value = '';
      changed(selects[shown - 1]);
      shown--;
      render();
      if (shown === 0) add.focus();
      else selects[Math.min(i, shown - 1)].focus();
    }

    selects.forEach(function (s, i) {
      var group = doc.createElement('div');
      group.className = 'input-group';
      s.parentNode.insertBefore(group, s);
      group.appendChild(s);
      var button = doc.createElement('button');
      button.type = 'button';
      button.className = 'btn btn-outline-secondary wlx-criterion-remove';
      button.setAttribute('aria-label', removeLabel.replace('$X', String(i + 1)));
      button.title = button.getAttribute('aria-label');
      var mark = doc.createElement('span');
      mark.setAttribute('aria-hidden', 'true');
      mark.textContent = '\u00d7';
      button.appendChild(mark);
      button.addEventListener('click', function () { remove(i); });
      group.appendChild(button);
    });

    add.addEventListener('click', function () {
      if (shown >= selects.length) return;
      shown++;
      render();
      selects[shown - 1].focus();
    });
    render();
  }

  /** Shows the worst mark's checkbox for a rated rating without criteria only (a criteria round's
    * votes are per criterion, and the server refuses the option there); disabled inputs aren't
    * sent, so a yes/no, comments-only or criteria round gets none. An existing round's rating is
    * fixed. */
  function initWorstMark(doc) {
    var rates = doc.getElementById('rates');
    var box = doc.getElementById('round-worst-mark');
    if (!rates || !box) return;
    var criteria = doc.querySelectorAll('#round-criteria select');
    function update() {
      var withCriteria = Array.prototype.some.call(criteria, function (s) { return !s.disabled && s.value !== ''; });
      var shown = Number(rates.value) > 1 && !withCriteria;
      box.hidden = !shown;
      Array.prototype.forEach.call(box.querySelectorAll('input'), function (i) { i.disabled = !shown; });
    }
    Array.prototype.forEach.call(criteria, function (s) { s.addEventListener('change', update); });
    if (!rates.disabled) rates.addEventListener('change', update);
    update();
  }

  /** Shows the score rule's warning only while the select differs from the saved rule. */
  function initScoreRuleWarning(doc) {
    var select = doc.getElementById('averageRule');
    var row = doc.getElementById('averageRule_warning_row');
    if (!select || !row) return;
    var stored = row.getAttribute('data-stored-rule');
    function update() {
      row.hidden = select.value === stored;
    }
    select.addEventListener('change', update);
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
        // a message the server gives (Commons not answering for a category filter), else '?'
        .fail(function (xhr) {
          var r = xhr.responseJSON;
          $('#new-files-count').text(r && typeof r.message === 'string' ? r.message : '?');
        })
        .always(function () { b.prop('disabled', false); });
    });
  }

  /** Space on a card's toggle: a click, as a role=button promises, and no page scroll. */
  function initCardToggleKeys(doc) {
    doc.addEventListener('keydown', function (e) {
      if (!(e.key === ' ' || e.keyCode === 32) || e.altKey || e.ctrlKey || e.metaKey) return;
      var t = e.target;
      if (!t || !t.matches || !t.matches('a[role="button"][data-bs-toggle="collapse"]')) return;
      e.preventDefault();
      t.click();
    });
  }

  function init(doc) {
    var t = texts(doc);
    initMultiSelects(doc, t);
    $('#previousRound').change(previousRoundsChanged);
    initVoteFilters();
    var previous = doc.getElementById('previousRound');
    if (previous && previous.form && previous.form.hasAttribute('data-round-draft-restored')) previousRoundsChanged.call(previous);
    $('#jurors').change(jurorsChanged(t.distribution || {}));
    initCoiConfirm(doc);
    initCriteriaSlots(doc);
    initCriteria(doc);
    initWorstMark(doc);
    initScoreRuleWarning(doc);
    initLazyParts();
    initCardToggleKeys(doc);
  }

  if (typeof module !== 'undefined') {
    module.exports = { init: init, texts: texts };
  } else if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { init(document); });
  } else {
    init(document);
  }
})();
