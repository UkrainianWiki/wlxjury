(function () {
  'use strict';

  var prefix = 'wlxjury:round-draft:';
  var keyPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
  // The editable controls only. In particular, no request tokens, hidden flags or context ids.
  var editable = [
    'name', 'trackLabel', 'previousRound', 'excludeRounds', 'minJurors', 'rejectionFilter',
    'topBinaryOrder', 'mediaType', 'categoryCombine', 'source', 'excludeCategory', 'regions',
    'monumentIds', 'minMpx', 'minSize', 'specialNomination', 'excludeCoi', 'fileList', 'jurors',
    'distribution', 'rates', 'averageRule', 'worstMark', 'criteria', 'newImages', 'topUpWaiting'
  ];
  var cutFields = ['topImages', 'minAvgRate', 'cutKeep', 'cutHeldRate'];

  function nameOf(field) {
    return (field.name || '').replace(/\[\d*\]$/, '');
  }

  function controls(form) {
    var groups = Object.create(null);
    Array.from(form.elements).forEach(function (el) {
      var name = nameOf(el);
      if (!editable.includes(name) || el.disabled || el.readOnly || el.type === 'hidden') return;
      if (!groups[name]) groups[name] = [];
      groups[name].push(el);
    });
    return groups;
  }

  function valuesOf(fields) {
    var first = fields[0];
    if (first.type === 'radio' || first.type === 'checkbox')
      return fields.filter(function (el) { return el.checked; }).map(function (el) { return el.value; });
    if (first.multiple)
      return Array.from(first.selectedOptions, function (opt) { return opt.value; });
    return fields.map(function (el) { return el.value; });
  }

  function restoreFields(fields, values) {
    fields.forEach(function (el, i) {
      if (el.type === 'radio' || el.type === 'checkbox') {
        el.checked = values.includes(el.value);
      } else if (el.multiple) {
        Array.from(el.options).forEach(function (opt) { opt.selected = values.includes(opt.value); });
      } else if (i < values.length) {
        var value = values[i];
        if (el.tagName === 'SELECT' && !Array.from(el.options).some(function (opt) { return opt.value === value; })) {
          // These choices are populated by the preview. It will supply their labels or mark a held value.
          var pending = (el.name === 'minJurors' && /^[1-9]\d*$/.test(value)) ||
            (el.name === 'rejectionFilter' && /^(exclude|only):[1-9]\d*$/.test(value));
          if (!pending) return;
          var option = el.ownerDocument.createElement('option');
          option.value = value;
          option.textContent = value;
          el.add(option);
        }
        el.value = value;
      }
    });
  }

  /** A cut starts as hidden fields and is redrawn by the preview. Read what that preview will post. */
  function cutValues(form) {
    var data = new form.ownerDocument.defaultView.FormData(form);
    var values = cutFields.map(function (name) { return data.get(name) || ''; });
    values[2] = values[0] ? values[2] || 'top' : '';
    return values;
  }

  function restoreCut(box, values) {
    cutFields.forEach(function (name, i) {
      box.querySelectorAll('[name="' + name + '"]').forEach(function (field) { field.remove(); });
      if (!values[i]) return;
      var field = box.ownerDocument.createElement('input');
      field.type = 'hidden';
      field.name = name;
      field.value = values[i];
      box.appendChild(field);
    });
  }

  function stringArray(value) {
    return Array.isArray(value) && value.every(function (part) { return typeof part === 'string'; });
  }

  function read(win, key) {
    try {
      var record = JSON.parse(win.sessionStorage.getItem(prefix + key));
      return record && record.version === 1 && record.changes ? record.changes : null;
    } catch (e) {
      return null;
    }
  }

  function write(win, key, changes) {
    try {
      if (changes && Object.keys(changes).length)
        win.sessionStorage.setItem(prefix + key, JSON.stringify({ version: 1, changes: changes }));
      else win.sessionStorage.removeItem(prefix + key);
      return true;
    } catch (e) {
      // A tab without storage can still warn about its unsaved edits.
      return false;
    }
  }

  function init(doc) {
    var win = doc.defaultView;
    var receipt = doc.querySelector('[data-round-draft-saved]');
    if (receipt && keyPattern.test(receipt.dataset.roundDraftSaved))
      write(win, receipt.dataset.roundDraftSaved, null);
    var form = doc.getElementById('round-form');
    var key = form && form.getAttribute('data-round-draft-key');
    if (!key || !keyPattern.test(key)) return;

    var groups = controls(form);
    var cutBox = form.querySelector('#top-n #top-n-box');
    var original = Object.create(null);
    var changes = Object.create(null);
    var refused = !!form.querySelector('#round-form-errors');
    var prior = read(win, key);
    var warning = false;
    var skipUnload = false;
    var savedLocally = false;
    var submitted = false;
    var discarded = false;

    Object.keys(groups).forEach(function (name) { original[name] = valuesOf(groups[name]); });
    if (cutBox) original.cut = cutValues(form);

    function collect(name) {
      var before = original[name];
      var after = name === 'cut' ? cutValues(form) : valuesOf(groups[name]);
      if (before.length === after.length && before.every(function (part, i) { return part === after[i]; }))
        delete changes[name];
      else changes[name] = { before: before, after: after };
    }

    function beforeUnload(event) {
      if (skipUnload) {
        skipUnload = false;
        return;
      }
      event.preventDefault();
      event.returnValue = true;
    }

    function updateWarning() {
      var dirty = Object.keys(changes).length > 0;
      if (dirty === warning) return;
      warning = dirty;
      if (warning) win.addEventListener('beforeunload', beforeUnload);
      else win.removeEventListener('beforeunload', beforeUnload);
    }

    function persist() {
      savedLocally = write(win, key, changes);
      updateWarning();
    }

    if (prior) {
      Object.keys(prior).forEach(function (name) {
        var entry = prior[name];
        if (!original[name] || !entry || !stringArray(entry.before) || !stringArray(entry.after)) return;
        if (refused) {
          // The server has echoed the posted values. Keep its corrections and errors on that page.
          original[name] = entry.before;
        } else if (name === 'cut') restoreCut(cutBox, entry.after);
        else restoreFields(groups[name], entry.after);
        collect(name);
      });
      if (!refused && Object.keys(changes).length) {
        form.setAttribute('data-round-draft-restored', '');
        var notice = doc.getElementById('round-draft-restored');
        if (notice) notice.hidden = false;
      }
      persist();
    }

    function changed(event) {
      var name = nameOf(event.target);
      if (cutFields.includes(name)) name = 'cut';
      if (!original[name]) return;
      skipUnload = false;
      collect(name);
      // Changing the jurors can change the distribution select at the same time.
      if (name === 'jurors' && original.distribution) collect('distribution');
      persist();
    }
    form.addEventListener('input', changed);
    form.addEventListener('change', changed);

    // At document level, after the form's preview hold and confirmation handlers.
    doc.addEventListener('submit', function (event) {
      if (event.target !== form || event.defaultPrevented || !warning) return;
      submitted = true;
      skipUnload = true;
    });

    var cancel = doc.getElementById('round-form-cancel');
    if (cancel) cancel.addEventListener('click', function (event) {
      if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      discarded = true;
      changes = Object.create(null);
      persist();
    });

    win.addEventListener('pageshow', function (event) {
      if (!event.persisted) return;
      skipUnload = false;
      var resolves = form.hasAttribute('data-resolve-url');
      if (discarded || (submitted && savedLocally && !read(win, key))) {
        changes = Object.create(null);
        updateWarning();
        // The token handler reloads the canonical GET itself when this form has one to resolve.
        if (!resolves) win.location.reload();
      } else if (resolves && savedLocally) {
        skipUnload = true;
      }
    });
  }

  if (typeof module !== 'undefined') {
    module.exports = { init: init };
  } else if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { init(document); });
  } else {
    init(document);
  }
})();
