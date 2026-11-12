// A vote's time (vote.voted_at, V97) in the viewer's own time zone and the page's language:
// the server renders <time class="wlx-vote-time" datetime="2026-11-12T14:05:00Z"> with the UTC
// value as its text (snippets/voteTime.scala.html), and this replaces the text with the day,
// the short month and the time (HH:mm), and the year when it isn't the current one. The
// gallery's tiles, drawn by script (tile-votes.js addVoteTime), format theirs with format().
(function () {
  'use strict';

  /** The year of `date` in `timeZone` (the browser's if none). */
  function yearOf(date, timeZone) {
    var options = { year: 'numeric' };
    if (timeZone) options.timeZone = timeZone;
    return new Intl.DateTimeFormat('en', options).format(date);
  }

  /** The time `iso` (ISO 8601) as the page shows it, in `locale` and `timeZone` (the browser's
    * if none), with the year if it isn't `now`'s, or always (`withYear`: the tooltip's); null for
    * no valid time. */
  function format(iso, locale, timeZone, now, withYear) {
    var date = new Date(iso);
    if (!iso || isNaN(date.getTime())) return null;
    var options = { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };
    if (timeZone) options.timeZone = timeZone;
    try {
      if (withYear || yearOf(date, timeZone) !== yearOf(now || new Date(), timeZone)) options.year = 'numeric';
      return new Intl.DateTimeFormat(locale || undefined, options).format(date);
    } catch (e) {
      return null; // an unknown locale or time zone: the server's text stays
    }
  }

  /** Formats every vote time under `root` in the page's language, its tooltip the same with the year. */
  function localize(root) {
    var lang = document.documentElement.getAttribute('lang') || undefined;
    Array.prototype.forEach.call(root.querySelectorAll('time.wlx-vote-time[datetime]'), function (el) {
      var iso = el.getAttribute('datetime');
      var text = format(iso, lang);
      if (text) {
        el.textContent = text;
        el.title = format(iso, lang, undefined, undefined, true);
      }
    });
  }

  window.wlxVoteTime = { format: format, localize: localize };

  document.addEventListener('DOMContentLoaded', function () { localize(document); });

  if (typeof module !== 'undefined') module.exports = window.wlxVoteTime;
})();
