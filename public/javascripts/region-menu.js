// A round gallery's region menu (regionMenu.scala.html): on its first opening, the list's
// regions and their file counts are read (GalleryController.regions) and listed, each linking
// its list's first page; kept for the page. If that fails, the menu keeps "all" and the region
// shown, and says so; opening it again tries again.
(function () {
  'use strict';

  function item(text, url, active) {
    var li = document.createElement('li');
    if (active) li.className = 'active';
    var a = document.createElement('a');
    a.href = url;
    a.textContent = text;
    li.appendChild(a);
    return li;
  }

  function show(list, status, data) {
    while (list.firstChild) list.removeChild(list.firstChild);
    list.appendChild(item(data.all.name, data.all.url, data.current == null));
    var previousKind = null;
    (data.regions || []).forEach(function (region) {
      // regions, then special nominations, then the others
      if (previousKind !== null && region.kind !== previousKind) {
        var separator = document.createElement('li');
        separator.className = 'region-separator';
        separator.setAttribute('role', 'separator');
        list.appendChild(separator);
      }
      previousKind = region.kind;
      var text = region.count == null ? region.name : region.name + ' (' + region.count + ')';
      var li = item(text, region.url, region.id === data.current);
      if (region.kind === 'Nomination') li.className += ' region-nomination';
      list.appendChild(li);
    });
  }

  function init(menu) {
    var state = 'idle'; // idle, loading, done
    menu.addEventListener('toggle', function () {
      if (!menu.open || state !== 'idle') return;
      state = 'loading';
      var list = menu.querySelector('.region-menu-list');
      var status = menu.querySelector('.region-menu-status');
      status.textContent = menu.getAttribute('data-loading');
      fetch(menu.getAttribute('data-regions-url'), {
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      }).then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      }).then(function (data) {
        show(list, status, data);
        state = 'done';
      }).catch(function () {
        status.textContent = menu.getAttribute('data-failed');
        state = 'idle';
      });
    });
  }

  function start() {
    Array.prototype.forEach.call(document.querySelectorAll('details.region-menu[data-regions-url]'), init);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
