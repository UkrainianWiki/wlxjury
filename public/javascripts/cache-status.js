/**
 * Image cache status poller for the contest images admin page.
 *
 * formatCacheStatus(p) – pure function: given a status object, returns the
 * text to display (or null when there is nothing to show yet).
 *
 * pollCacheStatus(statusUrl) – starts the fetch/poll loop, wiring the result
 * into the #cache-status element.  The URL is supplied by the Scala template
 * so that no server-side expression leaks into this file.  On 401 or 403
 * (signed out, or no longer an admin of the contest) it stops; on other
 * failures it retries, slower.
 */

function formatCacheStatus(p) {
    if (p.total > 0) {
        var pct    = Math.round(100 * p.done / p.total);
        var errStr = (p.renamed > 0 ? ", " + p.renamed + " renamed on Commons" : "")
            + (p.deleted > 0 ? ", " + p.deleted + " deleted on Commons" : "")
            + (p.errors > 0 ? ", " + p.errors + " errors" : "");
        var rate   = p.ratePerSec > 0 ? " \u2013 " + p.ratePerSec.toFixed(1) + " img/s" : "";
        var eta    = "";
        if (p.running && p.etaSeconds > 0) {
            eta = p.etaSeconds >= 60
                ? " \u2013 ETA " + Math.floor(p.etaSeconds / 60) + "m " + (p.etaSeconds % 60) + "s"
                : " \u2013 ETA " + p.etaSeconds + "s";
        }
        return p.done + " / " + p.total + " downloaded (" + pct + "%" + errStr + ")"
            + rate + eta + (p.running ? " \u2013 running\u2026" : " \u2013 done");
    } else if (p.running) {
        return "Starting\u2026";
    }
    return null;
}

function pollCacheStatus(statusUrl, elementId) {
    elementId = elementId || 'cache-status';
    function update() {
        // X-Requested-With: a poll, which must not switch the session's contest
        return fetch(statusUrl,
                { credentials: "same-origin", headers: { "X-Requested-With": "XMLHttpRequest" } })
            .then(function(r) {
                if (r.status === 401 || r.status === 403) return { denied: true };
                return r.json();
            })
            .then(function(p) {
                if (p.denied) { return p; }
                var text = formatCacheStatus(p);
                if (text !== null) {
                    document.getElementById(elementId).innerHTML = text;
                }
                if (p.running) { setTimeout(update, 3000); }
                return p;
            })
            .catch(function() { setTimeout(update, 5000); });
    }
    return update();
}

/* CommonJS export for Jest; ignored in the browser (no module object). */
if (typeof module !== "undefined") {
    module.exports = { formatCacheStatus: formatCacheStatus, pollCacheStatus: pollCacheStatus };
}
