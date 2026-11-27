// Labels focus select controls without opening their picker.
(function () {
  'use strict';

  function init(doc) {
    doc.addEventListener('click', function (event) {
      var label = event.target.closest('label');
      var field = label && label.control;
      if (field && field.tagName === 'SELECT' && !field.multiple) {
        event.preventDefault();
        field.focus();
      }
    });
  }

  function tomLabel(ts) {
    var label = document.getElementById(ts.focus_node.getAttribute('aria-labelledby'));
    if (!label) return;
    var mouseDown = function (event) {
      event.preventDefault();
      if (ts.isOpen) event.stopPropagation();
    };
    var click = function (event) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (!ts.isDisabled && !ts.isReadOnly) {
        var openOnFocus = ts.settings.openOnFocus;
        ts.settings.openOnFocus = false;
        ts.focus_node.focus();
        ts.settings.openOnFocus = openOnFocus;
      }
    };
    label.addEventListener('mousedown', mouseDown);
    label.addEventListener('click', click, true);
    ts.on('destroy', function () {
      label.removeEventListener('mousedown', mouseDown);
      label.removeEventListener('click', click, true);
    });
  }

  window.wlxSelectLabels = { init: init, tomLabel: tomLabel };
  if (typeof module !== 'undefined') module.exports = window.wlxSelectLabels;
  else init(document);
})();
