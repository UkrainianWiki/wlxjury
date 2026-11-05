// The number by the large view's stars (large/navigationButtons.scala.html, large-stars.js):
// the file's rate, as the page renders it ("–" unrated, 3.5 in a half-star round), and while
// the pointer is over a star, the rate a click on it gives; the rendered one again when the
// pointer leaves the stars. Only the visible number changes: screen readers read the stored
// rate from the text beside it, not each hover. A read-only view binds no hover, so its
// number stays. The gallery's tile stars (tile-votes.js) vote the stars `clicked` gives.
(function () {
  'use strict';

  /** The stars a click at raty's `score` gives, as raty draws them while hovering: in a
    * half-star round (`half`) the pointer's position over the stars, a half star over a
    * star's left half and the whole star over its right half; null for the cancel icon,
    * none. */
  function clicked(score, half) {
    if (score === null) return 0;
    if (!half) return score;
    var whole = Math.ceil(score);
    return Math.max(+(score % 1).toFixed(2) > 0.5 ? whole : whole - 0.5, 0.5);
  }

  /** A rate in stars as the number shows it: "–" for none. */
  function text(stars) {
    return stars > 0 ? String(stars) : '–';
  }

  /** raty's `mouseover` and `mouseout` for the number `number` (an element), and the stars a
    * click at a score gives (`clicked`), the same as the number shows. The number as rendered
    * is kept as nodes, so a worst mark's icon comes back when the pointer leaves. */
  function number(number, half) {
    var stored = Array.prototype.map.call(number.childNodes, function (node) { return node.cloneNode(true); });
    return {
      mouseover: function (score) { number.textContent = text(clicked(score, half)); },
      mouseout: function () {
        number.textContent = '';
        stored.forEach(function (node) { number.appendChild(node.cloneNode(true)); });
      },
      clicked: function (score) { return clicked(score, half); }
    };
  }

  window.wlxLargeRate = { number: number, clicked: clicked };

  if (typeof module !== 'undefined') module.exports = { clicked: clicked, text: text, number: number };
})();
