// The round form's multiple selects (editRound: previous rounds, regions, jurors) as Tom Select
// dropdowns, in place of bootstrap-multiselect, with its behaviour: a checkbox per option, a
// "select all" checkbox above them, and the control summing up the selection as it did (none
// selected; up to three names; "4 selected"; "all selected (5)"). The original <select> stays
// the form's field: Tom Select keeps its selected options and fires its change event, which the
// page's own handlers (mainAdmin.scala.html) listen to. A disabled select stays disabled.
(function () {
  'use strict';

  var NUMBER_DISPLAYED = 3;

  /** The control's text for the selected options' labels, of `total` options, as
    * bootstrap-multiselect's buttonText. `texts`: { none, n, all }. */
  function summaryText(labels, total, texts) {
    if (labels.length === 0) return texts.none;
    if (labels.length === total && total !== 1) return texts.all + ' (' + total + ')';
    if (labels.length > NUMBER_DISPLAYED) return labels.length + ' ' + texts.n;
    return labels.join(', ');
  }

  /** Makes `select` a Tom Select multiple dropdown; `texts`: { selectAll, none, n, all }. */
  function multiSelect(select, texts) {
    if (!select || !window.TomSelect) return null;
    var summary = document.createElement('span');
    summary.className = 'wlx-multi-summary';
    var selectAll;

    var ts = new window.TomSelect(select, {
      plugins: {
        checkbox_options: {},
        dropdown_header: {
          html: function () {
            return '<div class="dropdown-header wlx-select-all"><label class="form-check mb-0">' +
              '<input type="checkbox" class="form-check-input"> <span class="form-check-label"></span></label></div>';
          }
        }
      },
      controlInput: null, // no typing: a button that opens the list, as before
      hideSelected: false,
      closeAfterSelect: false,
      maxOptions: null,
      onInitialize: function () {
        var self = this;
        self.wrapper.classList.add('wlx-multi');
        self.control.appendChild(summary);
        selectAll = self.dropdown.querySelector('.wlx-select-all input');
        self.dropdown.querySelector('.wlx-select-all .form-check-label').textContent = texts.selectAll;
        selectAll.addEventListener('change', function () {
          self.setValue(selectAll.checked ? Object.keys(self.options) : []);
        });
      }
    });

    function refresh() {
      var labels = ts.items.map(function (value) { return ts.options[value].text; });
      var total = Object.keys(ts.options).length;
      summary.textContent = summaryText(labels, total, texts);
      if (selectAll) selectAll.checked = total > 0 && labels.length === total;
    }
    ts.on('change', refresh);
    refresh();
    return ts;
  }

  window.wlxMultiSelect = multiSelect;
  if (typeof module !== 'undefined') module.exports = { summaryText: summaryText, multiSelect: multiSelect };
})();
