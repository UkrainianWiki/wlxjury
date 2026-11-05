// The large views' keys (jquery.hotkeys.js), once the page is parsed, from the attributes of its
// #large-keys element (large/fileKeyboardNav.scala.html, large/keyboardNav.scala.html), each a
// path on this host, never an http:// URL: behind HTTPS a form posted to http:// is redirected
// as a GET and the vote is lost.
// - data-key-<key>: where the key moves to: down / up the next and previous file, pagedown /
//   pageup five files on or back, home / end the first and last shown, esc back to the list;
// - data-vote-key-<key>: the vote the key gives (large-view.js posts it), only in the juror's
//   own view of an open round: space selects and del rejects in a binary round, a digit rates
//   in a rated one (0 clears it), and in a rated round with the worst mark del gives ✗ and
//   backspace clears the vote.
// A key without its attribute does nothing here, so Space scrolls a read-only view.
(function () {
  'use strict';

  var MOVES = ['down', 'up', 'esc', 'pagedown', 'pageup', 'home', 'end'];
  var VOTES = ['space', 'del', 'backspace', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];

  function go(e, url) {
    e.preventDefault();
    window.location = url;
    return false;
  }

  /** Binds the keys of `doc`'s #large-keys; returns the keys bound. */
  function bind(doc) {
    var $ = window.jQuery;
    var el = doc.getElementById('large-keys');
    var bound = [];
    if (!el || !$) return bound;
    MOVES.forEach(function (key) {
      var url = el.getAttribute('data-key-' + key);
      if (!url) return;
      $(doc).on('keydown', null, key, function (e) { return go(e, url); });
      bound.push(key);
    });
    VOTES.forEach(function (key) {
      var url = el.getAttribute('data-vote-key-' + key);
      if (!url) return;
      $(doc).on('keydown', null, key, function (e) {
        e.preventDefault();
        window.wlxLargeView.vote(url);
        return false;
      });
      bound.push(key);
    });
    return bound;
  }

  window.wlxLargeKeys = { bind: bind };

  if (typeof module !== 'undefined') {
    module.exports = window.wlxLargeKeys;
  } else {
    window.jQuery(function () { bind(document); });
  }
})();
