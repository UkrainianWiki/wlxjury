// "Add people from another of your contests" (addFromContest.scala.html): "Tick all shown" ticks
// or unticks every person the list shows that can be ticked (not those disabled: members of the
// contest already, or invited into it), and follows the boxes ticked one by one (ticked when all
// are, mixed when some are). Without scripts the box stays hidden and each person is ticked by
// hand. A row whose role here is Admin is bold, as the page draws it, also when its select changes.
(function () {
  'use strict';

  /** The people's boxes that can be ticked. */
  function picks(root) {
    return Array.prototype.slice.call(root.querySelectorAll('input.add-from-pick')).filter(function (b) {
      return !b.disabled;
    });
  }

  /** "Tick all shown" as the boxes are: ticked if all are, mixed if some are. */
  function sync(all, root) {
    var boxes = picks(root);
    var ticked = boxes.filter(function (b) { return b.checked; }).length;
    all.checked = boxes.length > 0 && ticked === boxes.length;
    all.indeterminate = ticked > 0 && ticked < boxes.length;
  }

  /** Ticks (or unticks) every box that can be ticked. */
  function tickAll(root, checked) {
    picks(root).forEach(function (b) { b.checked = checked; });
  }

  /** Makes the select's row bold while its role is Admin. */
  function markAdmin(select) {
    var row = select.closest ? select.closest('tr') : null;
    if (row) row.classList.toggle('fw-bold', select.value === 'admin');
  }

  /** Shows "Tick all shown" (if anyone can be ticked) and wires it to the form's boxes. */
  function init(doc) {
    var all = doc.getElementById('add-from-all');
    var form = doc.getElementById('add-from-form');
    if (!all || !form) return;
    var box = doc.getElementById('add-from-all-box');
    if (box && picks(form).length > 0) box.hidden = false;
    all.addEventListener('change', function () {
      tickAll(form, all.checked);
      sync(all, form);
    });
    form.addEventListener('change', function (e) {
      var t = e.target;
      if (!t || !t.classList) return;
      if (t.classList.contains('add-from-pick')) sync(all, form);
      else if (t.classList.contains('add-from-role')) markAdmin(t);
    });
    sync(all, form);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { init(document); });
  else init(document);

  if (typeof module !== 'undefined') module.exports = { init: init, markAdmin: markAdmin, picks: picks, sync: sync, tickAll: tickAll };
})();
