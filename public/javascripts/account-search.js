// The users page's "Find an existing account" (users.scala.html, accounts plan A-1): sends the
// text in a POST's body with the CSRF token (the Csrf-Token header), never in a URL, and lists
// the accounts found: name (a link to the edit page where the server gives one), email (masked
// as the server sends it, or "no email"), wiki account, the contests shown with their roles, and
// "Add to this contest" (a copy of the page's template form, the account's id set) or the role
// the account already has here. Texts come from the section's data-text-* attributes; every
// value is set as text, never as HTML.
(function () {
  'use strict';

  /** The section's texts and settings. */
  function settings(box) {
    function text(name) { return box.getAttribute('data-text-' + name) || ''; }
    return {
      url: box.getAttribute('data-search-url'),
      csrf: box.getAttribute('data-csrf-token') || '',
      minLength: parseInt(box.getAttribute('data-min-length'), 10) || 3,
      none: text('none'),
      more: text('more'),
      short: text('short'),
      failed: text('failed'),
      noEmail: text('no-email'),
      verified: text('verified'),
      member: text('member')
    };
  }

  function element(tag, className, text) {
    var e = document.createElement(tag);
    if (className) e.className = className;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  /** The add form for the account: the template's, with the account's id and its own ids. */
  function addForm(box, account) {
    var template = box.querySelector('#account-search-add');
    if (!template || !template.content) return null;
    var form = template.content.firstElementChild.cloneNode(true);
    form.querySelector('input[name="accountId"]').value = String(account.id);
    Array.prototype.forEach.call(form.querySelectorAll('[data-id]'), function (field) {
      field.id = 'account-search-' + field.getAttribute('data-id') + '-' + account.id;
    });
    Array.prototype.forEach.call(form.querySelectorAll('[data-for]'), function (label) {
      label.htmlFor = 'account-search-' + label.getAttribute('data-for') + '-' + account.id;
    });
    return form;
  }

  /** One result's row. */
  function row(box, s, account) {
    var tr = element('tr', 'account-search-result');
    tr.setAttribute('data-account-id', String(account.id));

    var name = element('td');
    if (account.editUrl) {
      var link = element('a', null, account.name);
      link.href = account.editUrl;
      name.appendChild(link);
    } else name.textContent = account.name;
    tr.appendChild(name);

    var email = element('td', 'account-search-email');
    if (account.email) email.textContent = account.email;
    else email.appendChild(element('span', 'text-body-secondary', s.noEmail));
    tr.appendChild(email);

    var wiki = element('td');
    if (account.wikiAccount) {
      wiki.appendChild(document.createTextNode(account.wikiAccount));
      if (account.wikiVerified) {
        wiki.appendChild(document.createTextNode(' '));
        wiki.appendChild(element('span', 'badge text-bg-success', s.verified));
      }
    }
    tr.appendChild(wiki);

    var contests = element('td');
    (account.contests || []).forEach(function (c, i) {
      if (i > 0) contests.appendChild(element('br'));
      contests.appendChild(document.createTextNode(c.name + ': ' + c.role));
    });
    tr.appendChild(contests);

    var here = element('td');
    if (account.member) here.textContent = s.member + ' ' + account.member;
    else {
      var form = addForm(box, account);
      if (form) here.appendChild(form);
    }
    tr.appendChild(here);
    return tr;
  }

  /** Shows the server's answer: the rows, or that none was found; whether more matched. */
  function renderResults(box, data) {
    var s = settings(box);
    var table = box.querySelector('#account-search-results');
    var body = table.querySelector('tbody');
    var status = box.querySelector('#account-search-status');
    while (body.firstChild) body.removeChild(body.firstChild);
    var results = (data && data.results) || [];
    results.forEach(function (account) { body.appendChild(row(box, s, account)); });
    table.classList.toggle('d-none', results.length === 0);
    if (data && data.unsearchable) status.textContent = s.short;
    else if (results.length === 0) status.textContent = s.none;
    else status.textContent = data.more ? s.more : '';
    return results.length;
  }

  /** Searches for the text; resolves to the number of accounts shown. */
  function search(box, text, fetchImpl) {
    var s = settings(box);
    var status = box.querySelector('#account-search-status');
    var trimmed = (text || '').trim();
    if (trimmed.length < s.minLength) {
      renderResults(box, { results: [], unsearchable: true });
      return Promise.resolve(0);
    }
    var params = new URLSearchParams();
    params.append('q', trimmed);
    return fetchImpl(s.url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Csrf-Token': s.csrf,
        'X-Requested-With': 'XMLHttpRequest',
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: params.toString()
    }).then(function (response) {
      if (!response.ok) throw new Error('status ' + response.status);
      return response.json();
    }).then(function (data) {
      return renderResults(box, data);
    }, function () {
      renderResults(box, { results: [] });
      status.textContent = s.failed;
      return 0;
    });
  }

  /** Sends the search form through [[search]] instead of a page load. */
  function init(root, fetchImpl) {
    var box = root.querySelector('#account-search');
    if (!box) return null;
    var form = box.querySelector('#account-search-form');
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      search(box, form.querySelector('input[name="q"]').value, fetchImpl);
    });
    return box;
  }

  if (typeof document !== 'undefined' && typeof window !== 'undefined' && window.fetch) {
    document.addEventListener('DOMContentLoaded', function () {
      init(document, window.fetch.bind(window));
    });
  }

  if (typeof module !== 'undefined') module.exports = { renderResults: renderResults, search: search, init: init };
})();
